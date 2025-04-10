package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.List;
import network.NetworkConfig;

public class Client {
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private int serverPort = 5000;
    private String name;
    private String role; // buyer or seller
    private ServerSocket tcpServer;
    private int tcpPort;
    private NetworkConfig networkConfig;

    public Client(String name, String role) throws IOException {
        this.name = name;
        this.role = role;
        this.networkConfig = NetworkConfig.getInstance();

        // Create a UDP socket
        udpSocket = new DatagramSocket();

        // Discover server using network discovery
        discoverServer();

        // Create a TCP server socket on any available port
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

    public void start() {
        Scanner scanner = new Scanner(System.in);

        // Print network information
        networkConfig.printNetworkInfo();

        // 1) Register with the server
        // Format: REGISTER RQ# Name Role IP UDP_Port TCP_Port
        String registerMessage = String.format("REGISTER 1 %s %s %s %d %d",
                name,
                role,
                networkConfig.getLocalIpAddress(),
                udpSocket.getLocalPort(),
                tcpPort
        );
        sendUDPMessage(registerMessage);

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
        while (true) {
            System.out.println("\nEnter command (or type EXIT to quit):");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("EXIT")) {
                // De-register from the server
                sendUDPMessage("DE-REGISTER 2 " + name);
                break;
            }
            if (!input.isEmpty()) {
                sendUDPMessage(input);
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

    /**
     * Prints usage instructions depending on the user's role (seller or buyer).
     */
    private void printRoleInstructions() {
        if (role.equalsIgnoreCase("seller")) {
            System.out.println("--------------- SELLER COMMANDS ---------------");
            System.out.println("To list an item for auction:");
            System.out.println("  LIST_ITEM <RQ#> <ItemName> <ItemDescription> <StartPrice> <DurationInSeconds>");
            System.out.println("Example:");
            System.out.println("  LIST_ITEM 3 phone Smartphone 100.0 60");
            System.out.println("----------------------------------------------");
        } else if (role.equalsIgnoreCase("buyer")) {
            System.out.println("--------------- BUYER COMMANDS ---------------");
            System.out.println("To subscribe to an item:");
            System.out.println("  SUBSCRIBE <RQ#> <ItemName>");
            System.out.println("Example:");
            System.out.println("  SUBSCRIBE 5 phone");
            System.out.println("\nTo place a bid:");
            System.out.println("  BID <RQ#> <ItemName> <BidAmount>");
            System.out.println("Example:");
            System.out.println("  BID 10 phone 120.0");
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
            System.out.print("Enter your name: ");
            String name = scanner.nextLine().trim();
            System.out.print("Enter your role (buyer/seller): ");
            String role = scanner.nextLine().trim();

            // Note: We now handle IOException here
            Client client = new Client(name, role);
            client.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
