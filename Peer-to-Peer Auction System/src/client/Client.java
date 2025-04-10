package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.Enumeration;
import java.util.List;
import network.NetworkConfig;

public class Client {
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private int serverPort = 5000;
    private int nextRq = 1;
    private String name;
    private String role; // buyer or seller
    private ServerSocket tcpServer;
    private int tcpPort;
    private NetworkConfig networkConfig;
    // Constants for multicast discovery
//    private static final String MULTICAST_ADDRESS = "230.0.0.0";
//    private static final int MULTICAST_PORT = 4446;

    public Client(String name, String role) throws IOException {
        this.name = name;
        this.role = role;
        this.networkConfig = NetworkConfig.getInstance();

        // Create a UDP socket
        udpSocket = new DatagramSocket();

        // Discover server using network discovery
        discoverServer();

        // Initially, we don't know the server's address.
        //serverAddress = null;
        //serverPort = 0;

        // Start the multicast listener thread to discover the server.
        //startMulticastListener();

        // Create a TCP server socket on any available port (can throw IOException)
        tcpServer = new ServerSocket(0);
        tcpPort = tcpServer.getLocalPort();

        // Start TCP listener thread for finalization messages
        new Thread(new TCPListener()).start();

        // Start UDP receiver thread for multi-message handling
        new Thread(new UDPReceiver()).start();
    }

    private void discoverServer() {
        System.out.println("\n=== Network Discovery Started ===");
        System.out.println("Local network information:");
        networkConfig.printNetworkInfo();
        System.out.println("Discovering server on the network...");
        networkConfig.refreshNetworkInfo();

        // Print all available network interfaces for debugging
        try {
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
        } catch (SocketException e) {
            System.err.println("Failed to enumerate network interfaces: " + e.getMessage());
        }

        List<String> peers = networkConfig.getDiscoveredPeers();
        if (!peers.isEmpty()) {
            System.out.println("Discovered peers: " + String.join(", ", peers));
            // Use the first discovered peer as the server
            try {
                serverAddress = InetAddress.getByName(peers.get(0));
                System.out.println("Found server at: " + serverAddress.getHostAddress());
            } catch (UnknownHostException e) {
                System.err.println("Failed to resolve server address, falling back to localhost");
                useLocalhostAsServer();
            }
        } else {
            System.out.println("No server found on network, using localhost");
            useLocalhostAsServer();
        }
        System.out.println("=== Network Discovery Completed ===\n");
    }

    private void useLocalhostAsServer() {
        try {
            serverAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            System.err.println("Failed to get localhost, using 127.0.0.1");
            try {
                serverAddress = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
                System.err.println("Critical error: Could not resolve any IP address");
                System.exit(1);
            }
        }
    }

//    private NetworkInterface getPreferredNetworkInterface() throws SocketException {
//        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
//        while (interfaces.hasMoreElements()) {
//            NetworkInterface iface = interfaces.nextElement();
//            String displayName = iface.getDisplayName().toLowerCase();
//            // Skip loopback, virtual, or VPN-like interfaces
//            if (iface.isLoopback() || iface.isVirtual() || displayName.contains("nordlynx")) {
//                continue;
//            }
//            if (iface.isUp() && iface.supportsMulticast()) {
//                return iface;
//            }
//        }
//        // Fallback: if nothing is found, return the interface from the default local host
//        try {
//            return NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
//        } catch (UnknownHostException e) {
//            throw new RuntimeException(e);
//        }
//    }

//    private void startMulticastListener() {
//        new Thread(() -> {
//            try (MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT)) {
//                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
//                NetworkInterface networkInterface = getPreferredNetworkInterface();
//                if (networkInterface == null) {
//                    System.out.println("No suitable network interface found.");
//                    return;
//                }
//                multicastSocket.joinGroup(new InetSocketAddress(group, MULTICAST_PORT), networkInterface);
//                System.out.println("Joined multicast group on interface: " + networkInterface.getDisplayName());
//
//                // Loop until the server address is discovered.
//                while (serverAddress == null) {
//                    byte[] buffer = new byte[1024];
//                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
//                    multicastSocket.receive(packet);
//                    String advertisement = new String(packet.getData(), 0, packet.getLength());
//                    if (advertisement.startsWith("SERVER_IP")) {
//                        String[] tokens = advertisement.split(" ");
//                        if (tokens.length >= 3) {
//                            String discoveredIp = tokens[1];
//                            int discoveredUdpPort = Integer.parseInt(tokens[2]);
//                            serverAddress = InetAddress.getByName(discoveredIp);
//                            serverPort = discoveredUdpPort;
//                            System.out.println("Discovered Server: " + serverAddress.getHostAddress() + ", UDP Port: " + serverPort);
//                        }
//                    }
//                }
//                // Exiting the loop stops further printing.
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }).start();
//    }

    public void start() {

        // Wait until the server advertisement has been received.
//        while (serverAddress == null) {
//            System.out.println("Waiting for server advertisement...");
//            try {
//                Thread.sleep(3000);
//            } catch (InterruptedException e) {
//            }
//        }

        // Print network information
        networkConfig.printNetworkInfo();

        // 1) Register with the server
        // Format: REGISTER RQ# Name Role IP UDP_Port TCP_Port
        String registerMessage = String.format("REGISTER %d %s %s %s %d %d",
                nextRq++,
                name,
                role,
                networkConfig.getLocalIpAddress(),
                /*serverAddress.getHostAddress(),*/
                udpSocket.getLocalPort(),
                tcpPort
        );
        sendUDPMessage(registerMessage);

//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//        }

        // Print basic info
        System.out.println("----------------------------------------------------");
        System.out.println("Registered as " + name + " with role " + role);
        System.out.println("Server IP: " + serverAddress.getHostAddress() + ", UDP Port: " + serverPort);
        System.out.println("Your IP: " + networkConfig.getLocalIpAddress());
        System.out.println("Your TCP listening port: " + tcpPort);
        System.out.println("----------------------------------------------------");

        // 2) Show user instructions based on role
        printRoleInstructions();

        // 3) Command loop
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\nEnter command (or type EXIT to quit):");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("EXIT")) {
                // De-register from the server
                sendUDPMessage("DE-REGISTER 666 " + name);
                break;
            }
            if (!input.isEmpty()) {
                // If the command starts with INFORM_RES, send it via TCP to server's finalization listener.
                if (input.startsWith("INFORM_RES")) {
                    try (Socket responseSocket = new Socket(serverAddress, 6000);
                         PrintWriter out = new PrintWriter(responseSocket.getOutputStream(), true)) {
                        out.println(input);
                        System.out.println("Sent INFORM_RES: " + input);
                    } catch (IOException e) {
                        System.out.println("Error sending INFORM_RES:");
                        e.printStackTrace();
                    }
                } else {
                    // For all other commands, use UDP as before.
                    sendUDPMessage(input);
                }
            }
        }

        scanner.close();
        udpSocket.close();
        try {
            tcpServer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Client closed.");
    }

    // Prints usage instructions depending on the user's role (seller or buyer).
    private void printRoleInstructions() {
        // General Commands (Common to both buyers and sellers)
        System.out.println("--------------- GENERAL COMMANDS ---------------");
        System.out.println("To register with the server:");
        System.out.println("  REGISTER <RQ#> <Name> <Role> <IP Address> <UDP Socket#> <TCP Socket#>");
        System.out.println("Example:");
        System.out.println("  REGISTER 1 Ryan seller 192.168.0.200 52529 6000");
        System.out.println();
        System.out.println("To de-register from the server:");
        System.out.println("  DE-REGISTER <RQ#> <Name>");
        System.out.println("Example:");
        System.out.println("  DE-REGISTER 2 Ryan");
        System.out.println();
        System.out.println("To respond to a finalization request:");
        System.out.println("  INFORM_RES <RQ#> <Name> <CC#> <CC_Exp_Date> <Address>");
        System.out.println("Example:");
        System.out.println("  INFORM_RES 3 Buyer2 1234567890123456 12/25 123_Main_St_Montreal_QC_H3J1N5_Canada");
        System.out.println("----------------------------------------------");

        // Role-Specific Commands
        if (role.equalsIgnoreCase("seller")) {
            System.out.println("--------------- SELLER COMMANDS ---------------");
            System.out.println("To list an item for auction:");
            System.out.println("  LIST_ITEM <RQ#> <ItemName> <ItemDescription> <StartPrice> <DurationInSeconds>");
            System.out.println("Example:");
            System.out.println("  LIST_ITEM 4 phone Smartphone 100.0 60");
            System.out.println();
            System.out.println("When asked for price negotiation (if applicable):");
            System.out.println("  ACCEPT <RQ#> <ItemName> <New_Price>   - to lower the price");
            System.out.println("  REFUSE <RQ#> <ItemName> REJECT        - to decline negotiation");
            System.out.println();
            System.out.println("When asked for price negotiation (if applicable):");
            System.out.println("  ACCEPT <RQ#> <ItemName> <New_Price>   - to lower the price");
            System.out.println("  REFUSE <RQ#> <ItemName> REJECT        - to decline negotiation");
            System.out.println("----------------------------------------------");
        } else if (role.equalsIgnoreCase("buyer")) {
            System.out.println("--------------- BUYER COMMANDS ---------------");
            System.out.println("To subscribe to an item for auction updates:");
            System.out.println("  SUBSCRIBE <RQ#> <ItemName>");
            System.out.println("Example:");
            System.out.println("  SUBSCRIBE 5 phone");
            System.out.println();
            System.out.println("To de-subscribe from an item:");
            System.out.println("  DE-SUBSCRIBE <RQ#> <ItemName>");
            System.out.println("Example:");
            System.out.println("  DE-SUBSCRIBE 6 phone");
            System.out.println();
            System.out.println("To place a bid on an item:");
            System.out.println("  BID <RQ#> <ItemName> <BidAmount>");
            System.out.println("Example:");
            System.out.println("  BID 7 phone 120.0");
            System.out.println("----------------------------------------------");
        }
    }

    private void sendUDPMessage(String message) {
        try {
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
            udpSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Thread that continuously receives UDP messages from the server
    private class UDPReceiver implements Runnable {
        public void run() {
            while (!udpSocket.isClosed()) {
                try {
                    byte[] buffer = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    String response = new String(packet.getData(), 0, packet.getLength());
                    System.out.println("UDP Received: " + response);
                } catch (IOException e) {
                    if (!udpSocket.isClosed()) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // Thread that listens for TCP finalization messages from the server
    private class TCPListener implements Runnable {
        public void run() {
            while (!tcpServer.isClosed()) {
                try {
                    Socket tcpSocket = tcpServer.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                    String message = in.readLine();
                    System.out.println("TCP Received: " + message);

                    // Check if the message is an INFORM_REQ
                    if (message.startsWith("INFORM_REQ")) {
                        // Expected format: INFORM_REQ RQ# Item_Name Final_Price
                        String[] tokens = message.split(" ");
                        if (tokens.length >= 4) {
                            String rq = tokens[1];
                            String itemName = tokens[2];
                            String finalPrice = tokens[3];

                            System.out.println("Auction for item '" + itemName + "' has closed at price " + finalPrice + ".");
                            System.out.println("Please provide your payment and shipping details.");
                        } else {
                            System.out.println("Malformed INFORM_REQ message.");
                        }
                    }
                    tcpSocket.close();
                } catch (IOException e) {
                    if (!tcpServer.isClosed()) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // Main method to launch the client
    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            String name;
            String role;

            // Loop until a valid, non-empty name is entered
            while (true) {
                System.out.print("Enter your name: ");
                name = scanner.nextLine().trim();
                if (!name.isEmpty()) break;
                System.out.println("Name cannot be empty. Please try again.");
            }

            // Loop until a valid role is entered
            while (true) {
                System.out.print("Enter your role (buyer/seller): ");
                role = scanner.nextLine().trim().toLowerCase();
                if (role.equals("buyer") || role.equals("seller")) break;
                System.out.println("Invalid role. Please enter either 'buyer' or 'seller'.");
            }

            Client client = new Client(name, role);
            client.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}