package network;

import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class NetworkDiscovery {
    private static final int DISCOVERY_PORT = 9876;
    private static final String DISCOVERY_MESSAGE = "P2P_AUCTION_DISCOVERY";

    public static int getDiscoveryPort() {
        return DISCOVERY_PORT;
    }

    public static String getDiscoveryMessage() {
        return DISCOVERY_MESSAGE;
    }

    public static class NetworkInterface {
        private final String name;
        private final String displayName;
        private final String ipAddress;
        private final boolean isActive;

        public NetworkInterface(String name, String displayName, String ipAddress, boolean isActive) {
            this.name = name;
            this.displayName = displayName;
            this.ipAddress = ipAddress;
            this.isActive = isActive;
        }

        public String getName() { return name; }
        public String getDisplayName() { return displayName; }
        public String getIpAddress() { return ipAddress; }
        public boolean isActive() { return isActive; }

        @Override
        public String toString() {
            return String.format("%s (%s) - %s", displayName, name, ipAddress);
        }
    }

    public static List<NetworkInterface> discoverNetworkInterfaces() {
        List<NetworkInterface> interfaces = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = networkInterfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String displayName = ni.getDisplayName();
                String name = ni.getName();
                boolean isActive = ni.isUp() && !ni.isLoopback();

                // Get IPv4 addresses
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress inetAddress = addr.getAddress();
                    if (inetAddress instanceof Inet4Address) {
                        interfaces.add(new NetworkInterface(
                            name,
                            displayName,
                            inetAddress.getHostAddress(),
                            isActive
                        ));
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error discovering network interfaces: " + e.getMessage());
        }
        return interfaces;
    }

    public static String getLocalIpAddress() {
        try {
            // Try to get a non-loopback address
            return discoverNetworkInterfaces().stream()
                .filter(NetworkInterface::isActive)
                .map(NetworkInterface::getIpAddress)
                .findFirst()
                .orElse("127.0.0.1");
        } catch (Exception e) {
            System.err.println("Error getting local IP address: " + e.getMessage());
            return "127.0.0.1";
        }
    }

    public static List<String> discoverPeers() {
        List<String> peers = new ArrayList<>();
        try {
            // Create a multicast socket for discovery
            MulticastSocket socket = new MulticastSocket(DISCOVERY_PORT);
            InetAddress group = InetAddress.getByName("230.0.0.1");
            SocketAddress groupAddress = new InetSocketAddress(group, DISCOVERY_PORT);
            java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            socket.joinGroup(groupAddress, networkInterface);

            // Send discovery message
            byte[] sendData = DISCOVERY_MESSAGE.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(
                sendData, 
                sendData.length, 
                group, 
                DISCOVERY_PORT
            );
            socket.send(sendPacket);

            // Listen for responses
            socket.setSoTimeout(2000); // 2 second timeout
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

            while (true) {
                try {
                    socket.receive(receivePacket);
                    String response = new String(
                        receivePacket.getData(), 
                        0, 
                        receivePacket.getLength()
                    );
                    if (!response.equals(DISCOVERY_MESSAGE)) {
                        peers.add(receivePacket.getAddress().getHostAddress());
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }

            socket.leaveGroup(groupAddress, networkInterface);
            socket.close();
        } catch (Exception e) {
            System.err.println("Error discovering peers: " + e.getMessage());
        }
        return peers;
    }
} 