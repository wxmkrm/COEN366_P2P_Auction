package server;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import network.NetworkConfig;
import network.NetworkDiscovery;

public class Server implements AuctionFinalizer {
    private static class ServerState implements Serializable {
        private static final long serialVersionUID = 1L;
        ConcurrentHashMap<String, ClientInfo> clients;
        // We store auctions as a Map from itemName to Auction.
        ConcurrentHashMap<String, Auction> activeAuctions;
        // Subscriptions: itemName -> set of subscriber names.
        ConcurrentHashMap<String, Set<String>> subscriptions;
    }
    private static final String STATE_FILE = "server_state.dat";
    private static final int UDP_PORT = 5000;
    private DatagramSocket socket;
    private ExecutorService executor;
    private AuctionManager auctionManager;
    private NetworkConfig networkConfig;
    // In-memory registration of clients: name -> ClientInfo
    private ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    // Map from the INFORM_REQ's RQ# to the item being purchased
    private ConcurrentHashMap<String, String> informRequests = new ConcurrentHashMap<>();
    // Holds buyer/seller data for finalization
    private ConcurrentHashMap<String, FinalizationData> finalizationRecords = new ConcurrentHashMap<>();
    private static AtomicInteger serverRQCounter = new AtomicInteger(1000);

    private String generateServerRQ() {
        return String.valueOf(serverRQCounter.getAndIncrement());
    }

    public Server() throws SocketException {
        socket = new DatagramSocket(UDP_PORT);
        executor = Executors.newCachedThreadPool();
        auctionManager = new AuctionManager(this); // Server implements AuctionFinalizer
        networkConfig = NetworkConfig.getInstance();

        // Print network information
        networkConfig.printNetworkInfo();
        // Start discovery responder
        startDiscoveryResponder();

        System.out.println("Server started on UDP port " + UDP_PORT);
        // Load any persisted state before starting the finalization listener.
        loadState();
        startFinalizationListener();
    }

    public void start() {
        while (true) {
            try {
                byte[] buffer = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                executor.submit(() -> handlePacket(packet));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void persistState() {
        ServerState state = new ServerState();
        // Create shallow copies so that the state is consistent.
        state.clients = new ConcurrentHashMap<>(this.clients);
        // Use AuctionManager's helper method to get the current active auctions.
        state.activeAuctions = new ConcurrentHashMap<>(auctionManager.getActiveAuctionsMap());
        state.subscriptions = new ConcurrentHashMap<>(this.subscriptions);

        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(STATE_FILE))) {
            out.writeObject(state);
        } catch (IOException e) {
            System.out.println("Error persisting server state:");
            e.printStackTrace();
        }
    }

    private synchronized void loadState() {
        File file = new File(STATE_FILE);
        if (!file.exists()) {
            System.out.println("No previous server state found; starting fresh.");
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            ServerState state = (ServerState) in.readObject();
            // Restore state
            this.clients = state.clients;
            this.subscriptions = state.subscriptions;
            auctionManager.setActiveAuctionsMap(state.activeAuctions);
            System.out.println("Server state loaded successfully.");
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Error loading server state:");
            e.printStackTrace();
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String message = new String(packet.getData(), 0, packet.getLength()).trim();
        System.out.println("Received: " + message);
        if (message.isEmpty()) return;

        String[] tokens = message.split(" ");
        String command = tokens[0];

        switch (command) {
            case "REGISTER":
                handleRegister(tokens, packet);
                break;
            case "DE-REGISTER":
                handleDeregister(tokens);
                break;
            case "LIST_ITEM":
                handleListItem(tokens, packet);
                break;
            case "SUBSCRIBE":
                handleSubscribe(tokens, packet);
                break;
            case "DE-SUBSCRIBE":
                handleDeSubscribe(tokens, packet);
                break;
            case "BID":
                handleBid(tokens, packet);
                break;
            case "ACCEPT":
                handleAccept(tokens, packet);
                break;
            case "REFUSE":
                handleRefuse(tokens, packet);
                break;
            default:
                System.out.println("Unknown command: " + command);
        }
    }

    private void handleRegister(String[] tokens, DatagramPacket packet) {
        // Format: REGISTER RQ# Name Role IP UDP_Port TCP_Port
        if (tokens.length < 7) {
            System.out.println("Invalid REGISTER message.");
            return;
        }
        String rq = tokens[1];
        String name = tokens[2];
        String role = tokens[3];
        // Use the actual IP from the packet
        String ip = packet.getAddress().getHostAddress();
        int udpPort, tcpPort;
        try {
            //udpPort = packet.getPort();
            udpPort = Integer.parseInt(tokens[5]);
            tcpPort = Integer.parseInt(tokens[6]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port format in REGISTER.");
            return;
        }
        ClientInfo clientInfo = new ClientInfo(name, role, ip, udpPort, tcpPort);
        if (clients.containsKey(name)) {
            sendUDPMessage("REGISTER-DENIED " + rq + " Name already registered", packet.getAddress(),/*updPort*/ packet.getPort());
            clients.put(name, new ClientInfo(name, role, ip, udpPort, tcpPort));
            System.out.println("Refreshed existing client record for " + name);
            printConnectedClients();
        } else {
            clients.put(name, new ClientInfo(name, role, ip, udpPort, tcpPort));
            sendUDPMessage("REGISTERED " + rq, packet.getAddress(), packet.getPort());
            System.out.println("Registered client: " + name + " as " + role);
            printConnectedClients();
        }
        persistState();
    }

    private void handleDeregister(String[] tokens) {
        // Format: DE-REGISTER RQ# Name
        if (tokens.length < 3) {
            System.out.println("Invalid DE-REGISTER message.");
            return;
        }
        String name = tokens[2];
        clients.remove(name);
        persistState();
        System.out.println("Deregistered client: " + name);
        printConnectedClients();
    }

    private void handleListItem(String[] tokens, DatagramPacket packet) {
        // Format: LIST_ITEM RQ# Item_Name Item_Description Start_Price Duration
        // Check if enough tokens were provided.
        if (tokens.length < 6) {
            // Use token[1] if available; otherwise default to "unknown".
            String rq = (tokens.length >= 2) ? tokens[1] : "unknown";
            System.out.println("Invalid LIST_ITEM message: not enough parameters.");
            sendUDPMessage("LIST_DENIED " + rq + " Insufficient parameters for listing item", packet.getAddress(), packet.getPort());
            return;
        }

        String rq = tokens[1];
        String itemName = tokens[2];
        String itemDescription = tokens[3];
        double startPrice;
        int duration;
        try {
            startPrice = Double.parseDouble(tokens[4]);
            duration = Integer.parseInt(tokens[5]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid price or duration in LIST_ITEM.");
            sendUDPMessage("LIST_DENIED " + rq + " Invalid number format for price or duration", packet.getAddress(), packet.getPort());
            return;
        }

        // Identify seller using the packet's IP
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();
        String sellerName = null;
        for (ClientInfo info : clients.values()) {
            if (info.getIp().equals(senderIp) && info.getUdpPort() == senderPort) {
                sellerName = info.getName();
                break;
            }
        }

        // If seller is not found, we should deny the listing.
        if (sellerName == null) {
            System.out.println("Listing rejected: Seller not registered.");
            sendUDPMessage("LIST_DENIED " + rq + " Seller not registered", packet.getAddress(), packet.getPort());
            return;
        }

        // Everything is valid; create the auction.
        Auction auction = new Auction(itemName, itemDescription, startPrice, duration, sellerName, packet.getAddress(), 0);
        auctionManager.addAuction(auction);
        persistState();
        sendUDPMessage("ITEM_LISTED " + rq, packet.getAddress(), packet.getPort());
        System.out.println("Auction listed for item: " + itemName + " by " + sellerName);

        // Broadcast the auction announcement to all subscribers of the item.
        broadcastAuctionAnnouncement(itemName);

        // Schedule a negotiation check at half the auction duration.
        long durationMillis = java.time.Duration.between(LocalDateTime.now(), auction.getEndTime()).toMillis();
        long negotiationDelay = durationMillis / 2;

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                // If no bid has been placed, i.e. current bid equals starting price and no highest bidder.
                if (auction.getCurrentBid() == auction.getStartingPrice() &&
                        (auction.getHighestBidder() == null || auction.getHighestBidder().equals("unknown"))) {
                    // Generate a server-side request number.
                    String negotiationRQ = generateServerRQ();
                    // Calculate remaining time.
                    long secondsLeft = java.time.Duration.between(LocalDateTime.now(), auction.getEndTime()).getSeconds();
                    if (secondsLeft < 0) secondsLeft = 0;
                    // Construct the NEGOTIATE_REQ message.
                    String negotiateMsg = String.format("NEGOTIATE_REQ %s %s %.2f %d",
                            negotiationRQ, auction.getItemName(), auction.getCurrentBid(), secondsLeft);
                    // Retrieve seller info from the clients map.
                    ClientInfo sellerInfo = clients.get(auction.getSellerName());
                    if (sellerInfo != null) {
                        try {
                            InetAddress sellerAddress = InetAddress.getByName(sellerInfo.getIp());
                            int sellerUdpPort = sellerInfo.getUdpPort();
                            sendUDPMessage(negotiateMsg, sellerAddress, sellerUdpPort);
                            System.out.println("Negotiation request sent for item: " + auction.getItemName());
                        } catch (UnknownHostException e) {
                            System.out.println("Unknown host for seller: " + auction.getSellerName());
                        }
                    }
                }
            }
        }, negotiationDelay);
    }

    private void handleSubscribe(String[] tokens, DatagramPacket packet) {
        // Format: SUBSCRIBE RQ# Item_Name
        if (tokens.length < 3) {
            String rq = (tokens.length >= 2) ? tokens[1] : "unknown";
            System.out.println("Invalid SUBSCRIBE message: insufficient parameters.");
            sendUDPMessage("SUBSCRIPTION_DENIED " + rq + " Insufficient parameters for subscription", packet.getAddress(), packet.getPort());
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];

        // Check if an auction for the given item exists
        Auction auction = auctionManager.getAuction(itemName);
        if (auction == null) {
            System.out.println("Subscription rejected: No active auction for item " + itemName);
            sendUDPMessage("SUBSCRIPTION_DENIED " + rq + " No active auction for item", packet.getAddress(), packet.getPort());
            return;
        }

        // Identify the client (subscriber) based on packet's IP and UDP port
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();
        String clientName = null;
        for (ClientInfo info : clients.values()) {
            if (info.getIp().equals(senderIp) && info.getUdpPort() == senderPort) {
                clientName = info.getName();
                break;
            }
        }

        // If the client is not registered, deny the subscription
        if (clientName == null) {
            System.out.println("Subscription rejected: Client not registered.");
            sendUDPMessage("SUBSCRIPTION_DENIED " + rq + " Client not registered", packet.getAddress(), packet.getPort());
            return;
        }

        // Add the client to the subscription list for the item
        Set<String> subscriberSet = subscriptions.computeIfAbsent(itemName, k -> new CopyOnWriteArraySet<>());

        // If the client is already subscribed, deny the subscription
        if (subscriberSet.contains(clientName)) {
            System.out.println("Subscription rejected: " + clientName + " is already subscribed to " + itemName);
            sendUDPMessage("SUBSCRIPTION_DENIED " + rq + " Already subscribed", packet.getAddress(), packet.getPort());
            return;
        }

        // Accept the subscription if all checks pass
        subscriberSet.add(clientName);
        persistState();
        sendUDPMessage("SUBSCRIBED " + rq, packet.getAddress(), packet.getPort());
        System.out.println("Subscription accepted for item: " + itemName + " by " + clientName);
    }

    private void handleDeSubscribe(String[] tokens, DatagramPacket packet) {
        // Expected format: DE-SUBSCRIBE RQ# Item_Name
        if (tokens.length < 3) {
            String rq = (tokens.length >= 2) ? tokens[1] : "unknown";
            System.out.println("Invalid DE-SUBSCRIBE message: insufficient parameters.");
            sendUDPMessage("DE-SUBSCRIBE_DENIED " + rq + " Insufficient parameters", packet.getAddress(), packet.getPort());
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];

        // Identify the client using the packet's IP and UDP port.
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();
        String clientName = null;
        for (ClientInfo info : clients.values()) {
            if (info.getIp().equals(senderIp) && info.getUdpPort() == senderPort) {
                clientName = info.getName();
                break;
            }
        }
        if (clientName == null) {
            System.out.println("De-subscribe rejected: Client not registered.");
            sendUDPMessage("DE-SUBSCRIBE_DENIED " + rq + " Client not registered", packet.getAddress(), packet.getPort());
            return;
        }

        // Check if the client is subscribed to the item.
        Set<String> subscriberSet = subscriptions.get(itemName);
        if (subscriberSet == null || !subscriberSet.contains(clientName)) {
            System.out.println("De-subscribe rejected: " + clientName + " is not subscribed to " + itemName);
            sendUDPMessage("DE-SUBSCRIBE_DENIED " + rq + " Not subscribed to item", packet.getAddress(), packet.getPort());
            return;
        }

        // Remove the client from the subscription set.
        subscriberSet.remove(clientName);
        // Clean up the map if no subscribers remain.
        if (subscriberSet.isEmpty()) {
            subscriptions.remove(itemName);
        }
        persistState();

        // Send confirmation of de-subscription.
        sendUDPMessage("DE-SUBSCRIBED " + rq, packet.getAddress(), packet.getPort());
        System.out.println("De-subscribed " + clientName + " from " + itemName);
    }

    private void broadcastAuctionAnnouncement(String itemName) {
        // Retrieve the auction object
        Auction auction = auctionManager.getAuction(itemName);
        if (auction == null) {
            System.out.println("No active auction found for item: " + itemName);
            return;
        }

        // Generate a server-side request number
        String rq = generateServerRQ();

        // Calculate time left (in seconds)
        long secondsLeft = java.time.Duration.between(
                java.time.LocalDateTime.now(),
                auction.getEndTime()
        ).getSeconds();
        if (secondsLeft < 0) {
            secondsLeft = 0;
        }

        // Construct the AUCTION_ANNOUNCE message
        String message = String.format("AUCTION_ANNOUNCE %s %s %s %.2f %d",
                rq,
                auction.getItemName(),
                auction.getItemDescription(),
                auction.getCurrentBid(),
                secondsLeft
        );

        // Get the set of subscribers for this item
        Set<String> subscriberSet = subscriptions.get(itemName);
        if (subscriberSet == null || subscriberSet.isEmpty()) {
            System.out.println("No subscribers for item: " + itemName);
            return;
        }

        // Broadcast the announcement to each subscribed client
        for (String subscriberName : subscriberSet) {
            ClientInfo clientInfo = clients.get(subscriberName);
            if (clientInfo != null) {
                try {
                    InetAddress address = InetAddress.getByName(clientInfo.getIp());
                    int port = clientInfo.getUdpPort();
                    sendUDPMessage(message, address, port);
                } catch (UnknownHostException e) {
                    System.out.println("Unknown host for subscriber: " + subscriberName);
                }
            }
        }
        System.out.println("Auction announcement sent for item: " + itemName);
    }

    private void handleBid(String[] tokens, DatagramPacket packet) {
        // Format: BID RQ# Item_Name Bid_Amount
        if (tokens.length < 4) {
            System.out.println("Invalid BID message.");
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];
        double bidAmount;
        try {
            bidAmount = Double.parseDouble(tokens[3]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid bid amount in BID.");
            return;
        }

        Auction auction = auctionManager.getAuction(itemName);
        if (auction == null) {
            sendUDPMessage("BID_REJECTED " + rq + " No active auction for item",
                    packet.getAddress(), packet.getPort());
            return;
        }

        if (bidAmount > auction.getCurrentBid()) {
            auction.setCurrentBid(bidAmount);

            // Identify bidder using BOTH IP and UDP port
            String senderIp = packet.getAddress().getHostAddress();
            int senderPort = packet.getPort();
            String bidderName = "unknown";
            for (ClientInfo info : clients.values()) {
                if (info.getIp().equals(senderIp) && info.getUdpPort() == senderPort) {
                    bidderName = info.getName();
                    break;
                }
            }
            auction.setHighestBidder(bidderName);
            persistState();

            sendUDPMessage("BID_ACCEPTED " + rq, packet.getAddress(), packet.getPort());

            // Calculate time left
            long secondsLeft = java.time.Duration.between(
                    java.time.LocalDateTime.now(),
                    auction.getEndTime()
            ).getSeconds();
            if (secondsLeft < 0) secondsLeft = 0;

            String updateMsg = String.format(
                    "BID_UPDATE %s %s %.2f %s %d",
                    rq, itemName, bidAmount, bidderName, secondsLeft
            );
            sendUDPMessage(updateMsg, packet.getAddress(), packet.getPort());

            // Broadcast the updated auction announcement to all subscribers of the item.
            broadcastAuctionAnnouncement(itemName);

            System.out.println("New bid for item: " + itemName + " Amount: " + bidAmount + " by " + bidderName);
        } else {
            sendUDPMessage("BID_REJECTED " + rq + " Bid lower than current bid",
                    packet.getAddress(), packet.getPort());
        }
    }

    private void handleAccept(String[] tokens, DatagramPacket packet) {
        // Expected Format: ACCEPT RQ# Item_Name New_Price
        if (tokens.length < 4) {
            String rq = tokens.length >= 2 ? tokens[1] : "unknown";
            System.out.println("Invalid ACCEPT message: insufficient parameters.");
            // Optionally, send an error response.
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];
        double newPrice;
        try {
            newPrice = Double.parseDouble(tokens[3]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid new price in ACCEPT message.");
            return;
        }
        // Retrieve the auction for the given item.
        Auction auction = auctionManager.getAuction(itemName);
        if (auction == null) {
            System.out.println("ACCEPT received for non-existent auction: " + itemName);
            return;
        }
        // Update the auction price.
        auction.setCurrentBid(newPrice);

        // Generate a server request number for the price adjustment broadcast.
        String serverRQ = generateServerRQ();
        // Calculate remaining time.
        long secondsLeft = java.time.Duration.between(LocalDateTime.now(), auction.getEndTime()).getSeconds();
        if (secondsLeft < 0) secondsLeft = 0;
        // Construct the PRICE_ADJUSTMENT message.
        String message = String.format("PRICE_ADJUSTMENT %s %s %.2f %d",
                serverRQ, itemName, newPrice, secondsLeft);

        // Broadcast the price adjustment to all subscribers.
        Set<String> subscriberSet = subscriptions.get(itemName);
        if (subscriberSet != null) {
            for (String subscriberName : subscriberSet) {
                ClientInfo clientInfo = clients.get(subscriberName);
                if (clientInfo != null) {
                    try {
                        InetAddress address = InetAddress.getByName(clientInfo.getIp());
                        int port = clientInfo.getUdpPort();
                        sendUDPMessage(message, address, port);
                    } catch (UnknownHostException e) {
                        System.out.println("Unknown host for subscriber: " + subscriberName);
                    }
                }
            }
        }
        System.out.println("Seller accepted negotiation for item: " + itemName + " New Price: " + newPrice);
        persistState();
    }

    private void handleRefuse(String[] tokens, DatagramPacket packet) {
        // Expected Format: REFUSE RQ# Item_Name REJECT
        if (tokens.length < 3) {
            String rq = tokens.length >= 2 ? tokens[1] : "unknown";
            System.out.println("Invalid REFUSE message: insufficient parameters.");
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];

        // Log the refusal.
        System.out.println("Seller refused negotiation for item: " + itemName);
    }

    private void handlePaymentAndShipping(String itemName, FinalizationData record) {
        // 1) Attempt to charge the buyer
        boolean paymentSuccess = processPayment(record.buyerCC, record.buyerExp, record.finalPrice);
        ClientInfo buyerInfo = clients.get(record.buyerName);
        ClientInfo sellerInfo = clients.get(record.sellerName);

        if (!paymentSuccess) {
            // Payment failed => CANCEL
            sendCancel("Payment processing failed", buyerInfo, sellerInfo);
            return;
        }

        // 2) If payment succeeds, credit the seller with 90%
        double sellerAmount = record.finalPrice * 0.90;
        System.out.println("Crediting seller with 90% of price: " + sellerAmount + ", retaining 10% as fee.");

        // 3) Send shipping info to the seller
        // Format: Shipping_Info RQ# Name Winner_Address
        String shippingRQ = generateServerRQ();
        String shippingMsg = String.format("Shipping_Info %s %s %s", shippingRQ, record.buyerName, record.buyerAddress);
        sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), shippingMsg);
        System.out.println("Sent shipping info to seller: " + record.sellerName);
    }

    private boolean processPayment(String ccNumber, String ccExpDate, double amount) {
        System.out.println("Processing payment of " + amount + " for CC: " + ccNumber + " Exp: " + ccExpDate);

        // Simulate payment failure if CC number starts with 4 zeros,
        // Simulate success otherwise
        if (ccNumber.startsWith("0000"))
            return false;
        else
            return true;
    }

    private void sendUDPMessage(String message, InetAddress address, int port) {
        try {
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ----- AuctionFinalizer Implementation -----
    // When an auction closes, this method is called.
    @Override
    public void finalizeAuction(Auction auction) {
        String itemName = auction.getItemName();
        double finalPrice = auction.getCurrentBid();
        String sellerName = auction.getSellerName();
        String highestBidder = auction.getHighestBidder();
        System.out.println("Finalizing auction for item: " + itemName);

        // Save auction result persistently
        saveAuctionResult(auction);

        if (highestBidder != null && !highestBidder.equals("unknown")) {
            ClientInfo buyerInfo = clients.get(highestBidder);
            ClientInfo sellerInfo = clients.get(sellerName);
            if (buyerInfo != null && sellerInfo != null) {
                // Send WINNER and SOLD
                String buyerMsg = String.format("WINNER %s %.2f %s", itemName, finalPrice, sellerName);
                sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), buyerMsg);
                String sellerMsg = String.format("SOLD %s %.2f %s", itemName, finalPrice, highestBidder);
                sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), sellerMsg);

                // Create a record to store finalization data
                FinalizationData record = new FinalizationData();
                record.buyerName = highestBidder;
                record.sellerName = sellerName;
                record.finalPrice = finalPrice;
                finalizationRecords.put(itemName, record);

                // Generate an RQ# for INFORM_REQ and remember which item it refers to
                String informRQ = generateServerRQ();
                informRequests.put(informRQ, itemName);

                // Send INFORM_REQ to both buyer and seller
                // Format: INFORM_REQ RQ# Item_Name Final_Price
                String informReqMsg = String.format("INFORM_REQ %s %s %.2f", informRQ, itemName, finalPrice);
                sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), informReqMsg);
                sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), informReqMsg);

                System.out.println("Sent INFORM_REQ to both buyer and seller for item: " + itemName);
            } else {
                System.out.println("Could not find buyer or seller info for finalization.");
            }
        } else {
            // No valid bid -> NON_OFFER
            ClientInfo sellerInfo = clients.get(sellerName);
            if (sellerInfo != null) {
                String rq = generateServerRQ();
                String nonOfferMsg = String.format("NON_OFFER %s %s", rq, itemName);
                sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), nonOfferMsg);
                System.out.println("Auction ended with no bids. NON_OFFER sent to seller: " + sellerName);
            } else {
                System.out.println("Seller info not found for auction NON_OFFER message.");
            }

            // Notify all subscribed buyers that the auction is closed
            Set<String> subscriberSet = subscriptions.get(itemName);
            if (subscriberSet != null && !subscriberSet.isEmpty()) {
                for (String buyerName : subscriberSet) {
                    ClientInfo buyerInfo = clients.get(buyerName);
                    if (buyerInfo != null) {
                        String rqBuyer = generateServerRQ();
                        String nonOfferMsgBuyer = String.format("AUCTION_ENDED %s %s", rqBuyer, itemName);
                        sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), nonOfferMsgBuyer);
                    }
                }
            }
        }
        persistState();
    }

    private void startFinalizationListener() {
        new Thread(() -> {
            try (ServerSocket finalizationSocket = new ServerSocket(6000)) {
                System.out.println("Finalization listener started on TCP port 6000");
                while (true) {
                    Socket socket = finalizationSocket.accept();
                    new Thread(() -> {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                            String message = in.readLine();
                            System.out.println("Received INFORM_RES: " + message);
                            // Expected format: INFORM_RES RQ# Name CC# CC_Exp_Date Address
                            String[] tokens = message.split(" ", 6);
                            if (tokens.length < 6) {
                                System.out.println("Malformed INFORM_RES message received.");
                                return;
                            }
                            String cmd = tokens[0];    // INFORM_RES
                            String rq = tokens[1];     // RQ# from the server
                            String clientName = tokens[2];
                            String ccNumber = tokens[3];
                            String ccExpDate = tokens[4];
                            String address = tokens[5];

                            // Find which item this RQ# corresponds to
                            String itemName = informRequests.get(rq);
                            if (itemName == null) {
                                System.out.println("No matching itemName for RQ#: " + rq);
                                return;
                            }

                            // Retrieve the finalization record
                            FinalizationData record = finalizationRecords.get(itemName);
                            if (record == null) {
                                System.out.println("No finalization record found for item: " + itemName);
                                return;
                            }

                            // Check if this client is buyer or seller
                            if (clientName.equals(record.buyerName)) {
                                // Store buyer's data
                                record.buyerCC = ccNumber;
                                record.buyerExp = ccExpDate;
                                record.buyerAddress = address;
                                record.buyerInfoReceived = true;
                                System.out.println("Buyer info stored for item: " + itemName);
                            } else if (clientName.equals(record.sellerName)) {
                                // Store seller's data
                                record.sellerCC = ccNumber;
                                record.sellerExp = ccExpDate;
                                record.sellerAddress = address;
                                record.sellerInfoReceived = true;
                                System.out.println("Seller info stored for item: " + itemName);
                            } else {
                                System.out.println("INFORM_RES from unknown client: " + clientName);
                                return;
                            }

                            // If we have both buyer & seller info, attempt payment
                            if (record.buyerInfoReceived && record.sellerInfoReceived) {
                                handlePaymentAndShipping(itemName, record);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                socket.close();
                            } catch (IOException e) {
                            }
                        }
                    }).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Helper: Send a TCP message to a client
    private void sendTCPMessage(String ip, int port, String message) {
        try (Socket tcpSocket = new Socket(ip, port);
             PrintWriter out = new PrintWriter(tcpSocket.getOutputStream(), true)) {
            out.println(message);
            System.out.println("TCP message sent to " + ip + ":" + port + " -> " + message);
        } catch (IOException e) {
            System.out.println("Error sending TCP message to " + ip + ":" + port);
            e.printStackTrace();
        }
    }

    private void sendCancel(String reason, ClientInfo buyerInfo, ClientInfo sellerInfo) {
        // Generate a unique server request number for the cancellation message.
        String rq = generateServerRQ();
        // Construct the CANCEL message.
        String cancelMsg = String.format("CANCEL %s %s", rq, reason);

        // Send CANCEL to the buyer if available.
        if (buyerInfo != null) {
            sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), cancelMsg);
            System.out.println("Sent CANCEL to buyer: " + buyerInfo.getName() + " -> " + cancelMsg);
        }

        // Send CANCEL to the seller if available.
        if (sellerInfo != null) {
            sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), cancelMsg);
            System.out.println("Sent CANCEL to seller: " + sellerInfo.getName() + " -> " + cancelMsg);
        }
    }

    // Helper: Persist auction result to a file
    private void saveAuctionResult(Auction auction) {
        try (FileWriter fw = new FileWriter("auctions.log", true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println("Auction Closed: " + auction.getItemName() +
                    ", Final Price: " + auction.getCurrentBid() +
                    ", Seller: " + auction.getSellerName() +
                    ", Winner: " + auction.getHighestBidder() +
                    ", End Time: " + auction.getEndTime());
        } catch (IOException e) {
            System.out.println("Error saving auction result to file.");
            e.printStackTrace();
        }
    }

    private void printConnectedClients() {
        System.out.println("\n=== Connected Clients ===");
        if (clients.isEmpty()) {
            System.out.println("No clients connected");
        } else {
            clients.forEach((name, info) -> {
                System.out.printf("Client: %s (%s)\n", name, info.getRole());
                System.out.printf("  IP: %s, UDP Port: %d, TCP Port: %d\n",
                        info.getIp(), info.getUdpPort(), info.getTcpPort());
            });
        }
        System.out.println("=====================\n");
    }

    private void startDiscoveryResponder() {
        new Thread(() -> {
            try (MulticastSocket socket = new MulticastSocket(NetworkDiscovery.getDiscoveryPort())) {
                InetAddress group = InetAddress.getByName("230.0.0.1");
                SocketAddress groupAddress = new InetSocketAddress(group, NetworkDiscovery.getDiscoveryPort());

                // Print all available network interfaces for debugging
                System.out.println("\n=== Available Network Interfaces ===");
                NetworkInterface.getNetworkInterfaces().asIterator().forEachRemaining(ni -> {
                    try {
                        if (ni.isUp() && !ni.isLoopback()) {
                            System.out.println("Interface: " + ni.getDisplayName());
                            ni.getInterfaceAddresses().forEach(addr ->
                                    System.out.println("  Address: " + addr.getAddress().getHostAddress()));
                        }
                    } catch (SocketException e) {
                        e.printStackTrace();
                    }
                });
                System.out.println("================================\n");

                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(
                        InetAddress.getByName(networkConfig.getLocalIpAddress())
                );
                socket.joinGroup(groupAddress, networkInterface);

                System.out.println("Discovery responder started on port " + NetworkDiscovery.getDiscoveryPort());
                System.out.println("Listening for discovery messages...");

                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        socket.receive(packet);
                        String message = new String(packet.getData(), 0, packet.getLength());
                        System.out.println("Received discovery message from: " + packet.getAddress().getHostAddress());

                        if (message.equals(NetworkDiscovery.getDiscoveryMessage())) {
                            // Send response with server's IP
                            String response = networkConfig.getLocalIpAddress();
                            byte[] responseData = response.getBytes();
                            DatagramPacket responsePacket = new DatagramPacket(
                                    responseData,
                                    responseData.length,
                                    packet.getAddress(),
                                    packet.getPort()
                            );
                            socket.send(responsePacket);
                            System.out.println("Sent discovery response to: " + packet.getAddress().getHostAddress());
                        }
                    } catch (IOException e) {
                        if (!socket.isClosed()) {
                            System.err.println("Error in discovery responder: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to start discovery responder: " + e.getMessage());
                e.printStackTrace();
            }
        }, "DiscoveryResponder").start();
    }

    private static class FinalizationData {
        // Buyer data
        String buyerName;
        String buyerCC;
        String buyerExp;
        String buyerAddress;
        boolean buyerInfoReceived = false;

        // Seller data
        String sellerName;
        String sellerCC;
        String sellerExp;
        String sellerAddress;
        boolean sellerInfoReceived = false;

        // Auction info
        double finalPrice;
    }

    // ----- Inner class: ClientInfo -----
    private static class ClientInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private String name;
        private String role;
        private String ip;
        private int udpPort;
        private int tcpPort;

        public ClientInfo(String name, String role, String ip, int udpPort, int tcpPort) {
            this.name = name;
            this.role = role;
            this.ip = ip;
            this.udpPort = udpPort;
            this.tcpPort = tcpPort;
        }

        public String getName() { return name; }
        public String getRole() { return role; }
        public String getIp() { return ip; }
        public int getUdpPort() { return udpPort; }
        public int getTcpPort() { return tcpPort; }
    }

    public static void main(String[] args) {
        try {
            Server server = new Server();
            server.start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }
}