package server;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;
import network.NetworkConfig;
import network.NetworkDiscovery;

public class Server implements AuctionFinalizer {
    private static final int UDP_PORT = 5000;
    private DatagramSocket socket;
    private ExecutorService executor;
    private AuctionManager auctionManager;
    private NetworkConfig networkConfig;

    // In-memory registration of clients: name -> ClientInfo
    private ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();

    public Server() throws SocketException {
        socket = new DatagramSocket(UDP_PORT);
        executor = Executors.newCachedThreadPool();
        auctionManager = new AuctionManager(this);
        networkConfig = NetworkConfig.getInstance();
        
        // Print network information
        networkConfig.printNetworkInfo();
        System.out.println("Server started on UDP port " + UDP_PORT);
        
        // Start discovery responder
        startDiscoveryResponder();
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
            case "BID":
                handleBid(tokens, packet);
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
            udpPort = Integer.parseInt(tokens[5]);
            tcpPort = Integer.parseInt(tokens[6]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port format in REGISTER.");
            return;
        }
        ClientInfo clientInfo = new ClientInfo(name, role, ip, udpPort, tcpPort);
        if (clients.containsKey(name)) {
            sendUDPMessage("REGISTER-DENIED " + rq + " Name already registered", 
                packet.getAddress(), packet.getPort());
        } else {
            clients.put(name, clientInfo);
            sendUDPMessage("REGISTERED " + rq, packet.getAddress(), packet.getPort());
            System.out.println("Registered client: " + name + " as " + role);
            printConnectedClients();
        }
    }

    private void handleDeregister(String[] tokens) {
        // Format: DE-REGISTER RQ# Name
        if (tokens.length < 3) {
            System.out.println("Invalid DE-REGISTER message.");
            return;
        }
        String name = tokens[2];
        clients.remove(name);
        System.out.println("Deregistered client: " + name);
        printConnectedClients();
    }

    private void handleListItem(String[] tokens, DatagramPacket packet) {
        // Format: LIST_ITEM RQ# Item_Name Item_Description Start_Price Duration
        if (tokens.length < 6) {
            System.out.println("Invalid LIST_ITEM message.");
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
            return;
        }
        // Identify seller using the packet's IP
        String senderIp = packet.getAddress().getHostAddress();
        int senderPort = packet.getPort();
        String sellerName = "unknown";
        for (ClientInfo info : clients.values()) {
            // Compare BOTH IP and UDP port
            if (info.getIp().equals(senderIp) && info.getUdpPort() == senderPort) {
                sellerName = info.getName();
                break;
            }
        }
        Auction auction = new Auction(itemName, itemDescription, startPrice, duration, sellerName, packet.getAddress(), 0);
        auctionManager.addAuction(auction);
        sendUDPMessage("ITEM_LISTED " + rq, packet.getAddress(), packet.getPort());
        System.out.println("Auction listed for item: " + itemName + " by " + sellerName);
    }

    private void handleSubscribe(String[] tokens, DatagramPacket packet) {
        // Format: SUBSCRIBE RQ# Item_Name
        if (tokens.length < 3) {
            System.out.println("Invalid SUBSCRIBE message.");
            return;
        }
        String rq = tokens[1];
        String itemName = tokens[2];
        // For this demo we simply acknowledge the subscription.
        sendUDPMessage("SUBSCRIBED " + rq, packet.getAddress(), packet.getPort());
        System.out.println("Subscription received for item: " + itemName);
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

            System.out.println("New bid for item: " + itemName + " Amount: " + bidAmount + " by " + bidderName);
        } else {
            sendUDPMessage("BID_REJECTED " + rq + " Bid lower than current bid",
                    packet.getAddress(), packet.getPort());
        }
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
            // WINNER_SOLD logic
            ClientInfo buyerInfo = clients.get(highestBidder);
            ClientInfo sellerInfo = clients.get(sellerName);
            if (buyerInfo != null && sellerInfo != null) {
                String winnerMsg = String.format("WINNER_SOLD %s %.2f %s", itemName, finalPrice, highestBidder);
                sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), winnerMsg);
                sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), winnerMsg);
            }
            sendShippingInfo(auction);
        } else {
            // NON_OFFER logic
            ClientInfo sellerInfo = clients.get(sellerName);
            if (sellerInfo != null) {
                String noOfferMsg = String.format("NON_OFFER %s", itemName);
                sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), noOfferMsg);
            }
        }
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

    private void sendShippingInfo(Auction auction) {
        String itemName = auction.getItemName();
        String highestBidder = auction.getHighestBidder();
        ClientInfo buyerInfo = clients.get(highestBidder);
        ClientInfo sellerInfo = clients.get(auction.getSellerName());

        if (buyerInfo != null && sellerInfo != null) {
            String shippingMsg = String.format("Shipping_Info %s %s %s", itemName, buyerInfo.getName(), buyerInfo.getIp());
            sendTCPMessage(buyerInfo.getIp(), buyerInfo.getTcpPort(), shippingMsg);
            sendTCPMessage(sellerInfo.getIp(), sellerInfo.getTcpPort(), shippingMsg);
            System.out.println("Shipping information sent for item: " + itemName);
        }
    }

    // ----- Inner class: ClientInfo -----
    private static class ClientInfo {
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

    public static void main(String[] args) {
        try {
            Server server = new Server();
            server.start();
        } catch (SocketException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
