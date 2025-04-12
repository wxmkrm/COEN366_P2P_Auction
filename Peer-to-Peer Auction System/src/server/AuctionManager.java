package server;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuctionManager {
    private final Map<String, Auction> activeAuctions = new ConcurrentHashMap<>();
    private final Timer timer = new Timer(true);
    private AuctionFinalizer finalizer;

    public AuctionManager(AuctionFinalizer finalizer) {
        this.finalizer = finalizer;
    }

    public void addAuction(Auction auction) {
        activeAuctions.put(auction.getItemName(), auction);
        long delay = Duration.between(LocalDateTime.now(), auction.getEndTime()).toMillis();
        if (delay < 0) {
            closeAuction(auction.getItemName());
            return;
        }
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                closeAuction(auction.getItemName());
            }
        }, delay);
    }

    public Auction getAuction(String itemName) {
        return activeAuctions.get(itemName);
    }

    // Returns a copy of the active auctions map.
    public Map<String, Auction> getActiveAuctionsMap() {
        return new ConcurrentHashMap<>(activeAuctions);
    }

    // Replaces the current active auctions with the provided map.
    public void setActiveAuctionsMap(Map<String, Auction> auctions) {
        activeAuctions.clear();
        activeAuctions.putAll(auctions);
    }

    public void closeAuction(String itemName) {
        Auction auction = activeAuctions.remove(itemName);
        if (auction != null) {
            System.out.println("Auction closed for item: " + itemName);
            if (finalizer != null) {
                finalizer.finalizeAuction(auction);
            }
        }
    }

    public Collection<Auction> getAllAuctions() {
        return activeAuctions.values();
    }
}
