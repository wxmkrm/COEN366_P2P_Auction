package network;

import java.util.List;

public class NetworkConfig {
    private static NetworkConfig instance;
    private String localIpAddress;
    private List<NetworkDiscovery.NetworkInterface> availableInterfaces;
    private List<String> discoveredPeers;

    private NetworkConfig() {
        refreshNetworkInfo();
    }

    public static NetworkConfig getInstance() {
        if (instance == null) {
            instance = new NetworkConfig();
        }
        return instance;
    }

    public void refreshNetworkInfo() {
        this.availableInterfaces = NetworkDiscovery.discoverNetworkInterfaces();
        this.localIpAddress = NetworkDiscovery.getLocalIpAddress();
        this.discoveredPeers = NetworkDiscovery.discoverPeers();
    }

    public String getLocalIpAddress() {
        return localIpAddress;
    }

    public List<NetworkDiscovery.NetworkInterface> getAvailableInterfaces() {
        return availableInterfaces;
    }

    public List<String> getDiscoveredPeers() {
        return discoveredPeers;
    }

    public void printNetworkInfo() {
        System.out.println("\n=== Network Configuration ===");
        System.out.println("Local IP Address: " + localIpAddress);
        
        System.out.println("\nAvailable Network Interfaces:");
        for (NetworkDiscovery.NetworkInterface ni : availableInterfaces) {
            System.out.println("- " + ni.toString());
        }

        System.out.println("\nDiscovered Peers:");
        if (discoveredPeers.isEmpty()) {
            System.out.println("No peers discovered");
        } else {
            for (String peer : discoveredPeers) {
                System.out.println("- " + peer);
            }
        }
        System.out.println("===========================\n");
    }
} 