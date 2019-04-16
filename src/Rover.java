import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A Rover class which runs the RIPv2 protocol and updates it's tables accordingly.
 *
 * @author Aneesh Joshi
 */
public class Rover {
    byte id;
    private MulticastSocket socket;
    private InetAddress group;
    private Map<InetAddress, RoutingTableEntry> routingTable;
    private Map<InetAddress, List<RoutingTableEntry>> neighborRoutingTableEntriesCache;
    private Map<InetAddress, Timer> neighborTimers;
    private TimerTask timerTask;
    private InetAddress myPublicAddress, myPrivateAddress;
    private int multicastPort;


    private final static Logger LOGGER = Logger.getLogger("ROVER");
    private final static int LISTEN_WINDOW = 1024,
            ROUTE_UPDATE_TIME = 5,
            ROUTE_DELAY_TIME = 1,
            ROVER_OFFLINE_TIME_LIMIT = 10, // Time to wait before considering a rover to be dead
            ROVER_OFFLINE_TIMER_START_DELAY = 5,
            INFINITY = 16;
    private final static byte RIP_REQUEST = 1,
            RIP_UPDATE = 2,
            SUBNET_MASK = 24;
    private Map<InetAddress, InetAddress> privateToPublicAddresCache;

    /**
     * Constructs a rover with the given id with an IP ending in that id
     *
     * @param id
     */
    Rover(byte id, int multicastPort, InetAddress multicastIP) throws IOException {
        this.id = id;
        this.multicastPort = multicastPort;
        routingTable = new ConcurrentHashMap<>();
        neighborRoutingTableEntriesCache = new HashMap<>();
        neighborTimers = new HashMap<>();
        privateToPublicAddresCache = new HashMap<>();

        myPublicAddress = getMyInetAddress();
        myPrivateAddress = idToPrivateIp(id);

        LOGGER.info("Rover: " + id + " has a public IP address of " + myPublicAddress + " and a private address of " + myPrivateAddress);
        socket = new MulticastSocket(multicastPort);
        group = multicastIP;
        socket.joinGroup(group);


        // Send my routing tables every 5 seconds
        timerTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    sendRIPUpdate();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        Timer routeUpdateTimer = new Timer("RIP Route Update Timer");
        routeUpdateTimer.scheduleAtFixedRate(timerTask, 0, 5 * 1000);

        // Listen for updates from other rovers
        new Thread(() -> {
            try {
                listenMulticast();
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(0);
            }
        }).start();

    }

    /**
     * Updates entries as per the Distance Vector Algorithm when new entries are received
     *
     * @param newEntries The entries received
     */
    private void updateEntries(InetAddress sourcePublicAddress, byte sourceRoverId,
                               byte ripCommand, List<RoutingTableEntry> newEntries) throws IOException {

        // Drop your own table entries
        if (sourceRoverId == id) {
            return;
        }

        String oldRoutingTableString = routingTable.toString();

        InetAddress sourcePrivateAddress = idToPrivateIp(sourceRoverId);

        // Cache the entries of neighbors to recalculate the path when a router dies
        neighborRoutingTableEntriesCache.put(sourcePrivateAddress, newEntries);
        privateToPublicAddresCache.put(sourcePrivateAddress, sourcePublicAddress);


        // Since we got a message from this router, it must be at a distance of 1
        routingTable.put(sourcePrivateAddress, new RoutingTableEntry(sourcePrivateAddress, (byte) 24, sourcePublicAddress, (byte) 1));


        // restart the timer task since we have received the heart beat
        if (neighborTimers.containsKey(sourcePrivateAddress)) {
            neighborTimers.get(sourcePrivateAddress).cancel();
        }

        neighborTimers.put(sourcePrivateAddress, new Timer(sourcePrivateAddress + " Death Timer"));
        neighborTimers.get(sourcePrivateAddress).schedule(
                                                        new RouterDeathTimerTask(
                                                                this, sourcePrivateAddress, sourcePublicAddress),
                                                        7 * 1000
            );

        for (RoutingTableEntry entry : newEntries) {
            // skip your own multicast
            if (myPrivateAddress.equals(entry.ipAddress)) {
                continue;
            }

            updateTableFromEntry(sourcePublicAddress, entry);
        }

        boolean updateHappened = !oldRoutingTableString.equals(routingTable.toString());
//        System.out.println("toString looks like "  + oldRoutingTableString);
        // Send an update if
        if (updateHappened) {
            LOGGER.info(myPrivateAddress + "'s table was updated from received entries. New table is ->\n" + getStringRoutingTable() + "\n");
            sendRIPUpdate();
        } else if (ripCommand == RIP_REQUEST) { // If a request was made, we have to send the update
            LOGGER.info(myPrivateAddress + " got a RIP request. Going to send a RIP update -> \n" + getStringRoutingTable() + " \n");
            sendRIPUpdate();
        }
    }

    /**
     * Returns a private IP based on the IP address
     * @param id the rover's id
     * @return a private IP based on the IP address
     */
    private InetAddress idToPrivateIp(byte id) throws UnknownHostException{
        return InetAddress.getByName("10." + id + ".0.1");
    }

    /**
     * Send update packets out
     */
    private void sendRIPUpdate() throws IOException {
//        LOGGER.info(myPrivateAddress + " is sending a RIP update\n");
        multicast(RIPPacketUtil.getRIPPacket(RIP_UPDATE, id, routingTable));
    }

    /**
     * Listens on the multicast ip and updates the routing table entries accordingly
     *
     * @throws IOException
     */
    private void listenMulticast() throws IOException {
        byte[] buf = new byte[LISTEN_WINDOW];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            List<RoutingTableEntry> entries = RIPPacketUtil.decodeRIPPacket(packet.getData(), packet.getLength());
            updateEntries(packet.getAddress(), packet.getData()[2], packet.getData()[0], entries);
        }
    }

    /**
     * Called by RouterDeathTimerTask object when
     *
     * @param deadRoverPrivateAddress IP of the rover which died/is offline
     */
    void registerNeighborDeath(InetAddress deadRoverPrivateAddress, InetAddress deadRoverPublicAddress) throws IOException {
        LOGGER.info(deadRoverPrivateAddress + " just died :(\n\n\n");

        neighborTimers.get(deadRoverPrivateAddress).cancel();

        routingTable.get(deadRoverPrivateAddress).metric = INFINITY;

        for (InetAddress inetAddress : this.routingTable.keySet()) {
            if (routingTable.get(inetAddress).nextHop.equals(deadRoverPublicAddress)) {
                routingTable.get(inetAddress).metric = INFINITY;
            }
        }

        /**neighborRoutingTableEntriesCache.remove(deadRoverPrivateAddress);

        for (InetAddress neighborIp : neighborRoutingTableEntriesCache.keySet()) {
            for (RoutingTableEntry entry : neighborRoutingTableEntriesCache.get(neighborIp)) {
                if (entry.ipAddress.equals(myPrivateAddress) ||
                        entry.ipAddress.equals(deadRoverPrivateAddress) ||
                        entry.nextHop.equals(myPublicAddress) ||
                        entry.nextHop.equals(deadRoverPublicAddress)) {
                    continue;
                }

                // put all neighbors at 1 distance
                routingTable.put(neighborIp, new RoutingTableEntry(neighborIp, (byte) SUBNET_MASK,
                                 privateToPublicAddresCache.get(neighborIp), (byte) 1));

                updateTableFromEntry(privateToPublicAddresCache.get(neighborIp), entry);
            }
        }**/

        LOGGER.info(myPublicAddress + "'s table as updated after rover death is \n" + getStringRoutingTable());

        // send a triggered update
        sendRIPUpdate();
    }

    /**
     * Update the routing table based on the given entry.
     * Note: this function was separated from updateRoutingTable since it is also used when a neighbor dies
     *
     * @param neighborPublicIp the ip of the neighbor who sent this entry
     * @param entry      the entry in that neighbor's table
     * @return true if the entry updates something, false otherwise
     */
    private boolean updateTableFromEntry(InetAddress neighborPublicIp, RoutingTableEntry entry) {

        // If the entry uses me as its next hop, I can't believe it and will read it as INFINITY
        int entryVal = entry.nextHop.equals(myPublicAddress) ? INFINITY : entry.metric;

        // If we've never seen the entry's IP before, we immediately add it
        if (!routingTable.containsKey(entry.ipAddress)) {
            routingTable.put(entry.ipAddress, new RoutingTableEntry(entry.ipAddress,
                                                                    entry.subnetMask,
                                                                    neighborPublicIp,
                                                                    (byte) ((1 + entryVal) >= INFINITY ? INFINITY : 1 + entryVal)));
        }
        // If the entry is this tables next hop, we will trust it
        // Or if the entry is shorter, we update our entry
        else if (routingTable.get(entry.ipAddress).nextHop.equals(neighborPublicIp) ||
                routingTable.get(entry.ipAddress).metric > 1 + entryVal) {

//            RoutingTableEntry oldEntry = new RoutingTableEntry(entry.ipAddress, entry.subnetMask, entry.nextHop, entry.metric);

            routingTable.get(entry.ipAddress).metric =
                    (byte) ((1 + entryVal) >= INFINITY ? INFINITY : 1 + entryVal);
            routingTable.get(entry.ipAddress).nextHop = neighborPublicIp;
            routingTable.get(entry.ipAddress).subnetMask = entry.subnetMask;

//            if(routingTable.get(entry.ipAddress).equals(oldEntry)){
//                return false;
//            }
        } else {
            return false; // if none of the above conditions hit, we didn't update anything
        }
        return true;
    }

    /**
     * Mulicasts the given byte over the network
     * <p>
     * Note: I am not closing the socket since it is intended to be used often.
     *
     * @param buffer packet to be sent
     */
    private void multicast(byte[] buffer) throws IOException {
        DatagramPacket packet
                = new DatagramPacket(buffer, buffer.length, group, multicastPort);
        socket.send(packet);
    }

    /**
     * Ping Google's DNS server in order to get your own IP address on the correct interface
     *
     * @return this machine's IP on the outgoing interface
     */
    private InetAddress getMyInetAddress() throws IOException {

        DatagramSocket tempSocket = new DatagramSocket();
        tempSocket.connect(InetAddress.getByName("8.8.8.8"), 20800);
        return tempSocket.getLocalAddress();
    }


    /**
     * Returns a neat representation of the routing table
     *
     * @return a neat representation of the routing table
     */
    private String getStringRoutingTable() {
        StringBuilder res = new StringBuilder("IP Address\tNextHop\t\tMetric\n");
        for (RoutingTableEntry entry : routingTable.values()) {
            res.append(entry.toString() + " \n");
        }
        return res.toString();
    }


    /**
     * Driver function for the Rover class
     *
     * @param args the arguments passed to the Rover
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        ArgumentParser argsParser = new ArgumentParser(args);
        if (argsParser.success) {
            new Rover(argsParser.roverId, argsParser.multicastPort, argsParser.multicastAddress);
        }
    }
}
