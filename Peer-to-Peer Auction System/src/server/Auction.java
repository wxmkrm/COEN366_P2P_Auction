package server;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.io.Serializable;

public class Auction implements Serializable {
    private String itemName;
    private String itemDescription;
    private double startingPrice;
    private double currentBid;
    private String highestBidder;
    private String sellerName;
    private InetAddress sellerAddress;
    private int sellerTCPPort;
    private LocalDateTime endTime;

    public Auction(String itemName,
                   String itemDescription,
                   double startingPrice,
                   int durationSeconds,
                   String sellerName,
                   InetAddress sellerAddress,
                   int sellerTCPPort) {
        this.itemName = itemName;
        this.itemDescription = itemDescription;
        this.startingPrice = startingPrice;
        this.currentBid = startingPrice;
        this.sellerName = sellerName;
        this.sellerAddress = sellerAddress;
        this.sellerTCPPort = sellerTCPPort;
        this.endTime = LocalDateTime.now().plusSeconds(durationSeconds);
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemDescription() {
        return itemDescription;
    }

    public double getStartingPrice() {
        return startingPrice;
    }

    public double getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(double currentBid) {
        this.currentBid = currentBid;
    }

    public String getHighestBidder() {
        return highestBidder;
    }

    public void setHighestBidder(String highestBidder) {
        this.highestBidder = highestBidder;
    }

    public String getSellerName() {
        return sellerName;
    }

    public InetAddress getSellerAddress() {
        return sellerAddress;
    }

    public int getSellerTCPPort() {
        return sellerTCPPort;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }
}
