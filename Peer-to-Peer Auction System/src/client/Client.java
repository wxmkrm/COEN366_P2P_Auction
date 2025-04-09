package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.Enumeration;

public class Client {
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private int serverPort = 5000;
    private int nextRq = 1;
    private String name;
    private String role; // buyer or seller
    private ServerSocket tcpServer;
    private int tcpPort;
    // Constants for multicast discovery
    private static final String MULTICAST_ADDRESS = "230.0.0.0";
    private static final int MULTICAST_PORT = 4446;

    public Client(String name, String role) throws IOException {
        this.name = name;
        this.role = role;

        // Create a UDP socket
        udpSocket = new DatagramSocket();

        // Initially, we don't know the server's address.
        serverAddress = null;
        serverPort = 0;

        // Start the multicast listener thread to discover the server.
        startMulticastListener();

        // Create a TCP server socket on any available port (can throw IOException)
        tcpServer = new ServerSocket(0);
        tcpPort = tcpServer.getLocalPort();

        // Start TCP listener thread for finalization messages
        new Thread(new TCPListener()).start();

        // Start UDP receiver thread for multi-message handling
        new Thread(new UDPReceiver()).start();
    }

    private NetworkInterface getPreferredNetworkInterface() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            String displayName = iface.getDisplayName().toLowerCase();
            // Skip loopback, virtual, or VPN-like interfaces
            if (iface.isLoopback() || iface.isVirtual() || displayName.contains("nordlynx")) {
                continue;
            }
            if (iface.isUp() && iface.supportsMulticast()) {
                return iface;
            }
        }
        // Fallback: if nothing is found, return the interface from the default local host
        try {
            return NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private void startMulticastListener() {
        new Thread(() -> {
            try (MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT)) {
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                NetworkInterface networkInterface = getPreferredNetworkInterface();
                if (networkInterface == null) {
                    System.out.println("No suitable network interface found.");
                    return;
                }
                multicastSocket.joinGroup(new InetSocketAddress(group, MULTICAST_PORT), networkInterface);
                System.out.println("Joined multicast group on interface: " + networkInterface.getDisplayName());

                // Loop until the server address is discovered.
                while (serverAddress == null) {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    multicastSocket.receive(packet);
                    String advertisement = new String(packet.getData(), 0, packet.getLength());
                    if (advertisement.startsWith("SERVER_IP")) {
                        String[] tokens = advertisement.split(" ");
                        if (tokens.length >= 3) {
                            String discoveredIp = tokens[1];
                            int discoveredUdpPort = Integer.parseInt(tokens[2]);
                            serverAddress = InetAddress.getByName(discoveredIp);
                            serverPort = discoveredUdpPort;
                            System.out.println("Discovered Server: " + serverAddress.getHostAddress() + ", UDP Port: " + serverPort);
                        }
                    }
                }
                // Exiting the loop stops further printing.
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void start() {

        // Wait until the server advertisement has been received.
        while (serverAddress == null) {
            System.out.println("Waiting for server advertisement...");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
        }

        // 1) Register with the server
        // Format: REGISTER RQ# Name Role IP UDP_Port TCP_Port
        String registerMessage = String.format("REGISTER %d %s %s %s %d %d",
                nextRq++,
                name,
                role,
                serverAddress.getHostAddress(),
                udpSocket.getLocalPort(),
                tcpPort
        );
        sendUDPMessage(registerMessage);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }

        // Print basic info
        System.out.println("----------------------------------------------------");
        System.out.println("Registered as " + name + " with role " + role);
        System.out.println("Server IP: " + serverAddress.getHostAddress() + ", UDP Port: " + serverPort);
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

    // Prints usage instructions depending on the user's role (seller or buyer).
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
            Scanner inputScanner = new Scanner(System.in); // Create a Scanner for user input
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

                            // Prompt the user for the required details
                            System.out.print("Enter your name: ");
                            String userName = inputScanner.nextLine();

                            System.out.print("Enter your credit card number: ");
                            String ccNumber = inputScanner.nextLine();

                            System.out.print("Enter your credit card expiration date (MM/YY): ");
                            String ccExpDate = inputScanner.nextLine();

                            System.out.print("Enter your shipping address: ");
                            String address = inputScanner.nextLine();

                            // Construct the INFORM_RES message
                            // Format: INFORM_RES RQ# Name CC# CC_Exp_Date Address
                            String informResMsg = String.format("INFORM_RES %s %s %s %s %s", rq, userName, ccNumber, ccExpDate, address);

                            // Send the INFORM_RES back to the server's finalization listener
                            try (Socket responseSocket = new Socket(serverAddress, 6000);
                                 PrintWriter out = new PrintWriter(responseSocket.getOutputStream(), true)) {
                                out.println(informResMsg);
                                System.out.println("Sent INFORM_RES: " + informResMsg);
                            } catch (IOException e) {
                                System.out.println("Error sending INFORM_RES:");
                                e.printStackTrace();
                            }
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
