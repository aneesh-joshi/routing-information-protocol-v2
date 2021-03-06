import java.io.*;
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
    private byte id;
    private MulticastSocket socket;
    private InetAddress group, destAddress;
    private Map<InetAddress, RoutingTableEntry> routingTable;
    private Map<InetAddress, List<RoutingTableEntry>> neighborRoutingTableEntriesCache;
    private Map<InetAddress, Timer> neighborTimers;
    private InetAddress myPublicAddress, myPrivateAddress;
    private int multicastPort;
    private String fileToSend;
    private DatagramSocket udpSocket, udpAckSocket;


    private final static Logger LOGGER = Logger.getLogger("ROVER");
    private final static int
            RIP_LISTEN_WINDOW = 1024,
            ROUTE_UPDATE_TIME = 5,
            ROUTE_DELAY_TIME = 1,
            ROVER_OFFLINE_TIME_LIMIT = 10, // Time to wait before considering a rover to be dead
            ROVER_OFFLINE_TIMER_START_DELAY = 5,
            FILE_TRANSFER_MAX_READ_WINDOW = 6000,
            DOES_NOT_MATTER = 0,
            WAIT_TIME_BEFORE_TRANSFER = 3, // Time to wait before transferring the file
            INFINITY = 16,
            UDP_PORT = 6161,
            UDP_ACK_PORT = 5454,
            ACK_WAIT_TIMEOUT = 1000,
            WAIT_TIME_TILL_ROUTE_APPEARS = 5, // Time to wait before checking if the route to the destination rover is up
            MAX_HEADER_SIZE = 10, // The maximum data a header can take (never listen for a packet smaller than this)
            MAX_PAYLOAD_SIZE = 5000; // The chunks in which the data will be sent
    private final static byte RIP_REQUEST = 1,
            RIP_UPDATE = 2,
            SUBNET_MASK = 24;
    private final static String OUTPUT_FILENAME = "OUTPUT_FILE";
    private Map<InetAddress, InetAddress> privateToPublicAddresCache;


    /**
     * Constructs a rover with the given id with an IP ending in that id
     *
     * @param id
     */
    private Rover(byte id, int multicastPort, InetAddress multicastIP, String fileToSend, InetAddress destAddress) throws IOException {
        this.id = id;
        this.multicastPort = multicastPort;
        this.fileToSend = fileToSend;
        this.destAddress = destAddress;
        udpSocket = new DatagramSocket(UDP_PORT);
        udpAckSocket = new DatagramSocket(UDP_ACK_PORT);

        routingTable = new ConcurrentHashMap<>();
        neighborRoutingTableEntriesCache = new HashMap<>();
        neighborTimers = new HashMap<>();
        privateToPublicAddresCache = new HashMap<>();

        myPublicAddress = getMyInetAddress();
        myPrivateAddress = idToPrivateIp(id);

        LOGGER.info("Rover: " + id + " has a public IP address of " + myPublicAddress + " and a private address of " +
                myPrivateAddress + ((fileToSend == null) ? "" : " and will be sending the file " + fileToSend + " to " + this.destAddress));
        socket = new MulticastSocket(multicastPort);
        group = multicastIP;
        socket.joinGroup(group);


        // Send my routing tables every 5 seconds
        TimerTask timerTask = new TimerTask() {
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
        routeUpdateTimer.scheduleAtFixedRate(timerTask, 0, ROUTE_UPDATE_TIME * 1000);

        // Listen for updates from other rovers
        new Thread(() -> {
            try {
                listenMulticast();
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(0);
            }
        }).start();

        if (destAddress != null) {
            new Thread(this::sendFile).start();
        }

        new Thread(this::listenForFileTransfer).start();

    }

    /**
     * Sends the file mentioned in the constructor.
     */
    private void sendFile() {
        try {
            // wait for paths to normalize before sending the packet
            Thread.sleep(WAIT_TIME_BEFORE_TRANSFER * 1000);
            while (!routingTable.containsKey(destAddress)) {
                LOGGER.info("No entry for " + destAddress + ". Waiting for " + WAIT_TIME_TILL_ROUTE_APPEARS + " seconds.");
                Thread.sleep(WAIT_TIME_TILL_ROUTE_APPEARS * 1000);
            }

            // Initialize needed variables
            long totalSize = new File(fileToSend).length();
            BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(fileToSend));
            byte[] buffer = new byte[MAX_PAYLOAD_SIZE];
            byte[] recvBuffer = new byte[MAX_PAYLOAD_SIZE];
            DatagramPacket packet;
            int bytesRead = 0;
            byte[] packetToSend, actualPacket;
            int seqNumber = 1;
            boolean synSent = false, repeat = false;

            while (repeat || (bytesRead = bufferedInputStream.read(buffer, 0, buffer.length)) != -1) {

                if (repeat) {
                    LOGGER.info("This is a repeat message because ACK was not received");
                }

                // If the size of the buffer to be sent is less than MAX_PAYLOAD_SIZE, we will reduce it to have
                // only the things we need
                if (bytesRead < MAX_PAYLOAD_SIZE) {
                    buffer = Arrays.copyOf(buffer, bytesRead);
                    LOGGER.info("Resized the buffer to " + buffer.length);
                }

                if (!synSent) {
                    packetToSend = JPacketUtil.jPacket2Arr(destAddress, this.myPrivateAddress,
                            DOES_NOT_MATTER, DOES_NOT_MATTER, BitUtils.setBitInByte((byte) 0, JPacketUtil.SYN_INDEX),
                            buffer, (int) totalSize);
                    synSent = true;
                } else {
                    packetToSend = JPacketUtil.jPacket2Arr(destAddress, this.myPrivateAddress,
                            repeat ? seqNumber - 1 : seqNumber++, DOES_NOT_MATTER, BitUtils.setBitInByte((byte) 0,
                                    JPacketUtil.NORMAL_INDEX), buffer, DOES_NOT_MATTER);
                }


                System.out.println("About to send packet ");
                System.out.println(JPacketUtil.arr2JPacket(packetToSend));
                System.out.println("-----------------------------\n");

                packet = new DatagramPacket(packetToSend, packetToSend.length, routingTable.get(destAddress).nextHop, UDP_PORT);
                udpSocket.send(packet);

                LOGGER.info("Sent the packet, Waiting for ACK\n");

                JPacket recvdJPacket;
                udpAckSocket.setSoTimeout(ACK_WAIT_TIMEOUT);
                do {
                    packet = new DatagramPacket(recvBuffer, recvBuffer.length);
                    try {
                        udpAckSocket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        LOGGER.info("ACK wait timer timed out");
                        repeat = true;
                        break;
                    }
                    repeat = false;
                    actualPacket = Arrays.copyOfRange(recvBuffer, 0, packet.getLength());
                    recvdJPacket = JPacketUtil.arr2JPacket(actualPacket);
                } while (!JPacketUtil.isBitSet(recvdJPacket.flags, JPacketUtil.ACK_INDEX) && recvdJPacket.ackNumber != seqNumber);
                LOGGER.info("Got an ack, moving ahead");
            }
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
            System.exit(42);
        }
    }

    /**
     * Listens for file transfer and processes if it's its own or forwards
     */
    private void listenForFileTransfer() {
        DatagramPacket packet;
        byte[] buffer = new byte[FILE_TRANSFER_MAX_READ_WINDOW];
        byte[] actualPacket;
        int totalFileSize = 0, prevSequenceNumber = 0;

        try (FileOutputStream fileOutputStream = new FileOutputStream(OUTPUT_FILENAME)) {
            while (true) {
                packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                actualPacket = Arrays.copyOfRange(buffer, 0, packet.getLength());
                JPacket jPacket = JPacketUtil.arr2JPacket(actualPacket);


                LOGGER.info("Got this packet\n");
                System.out.println(jPacket);
                System.out.println("\n~~~~~~~~~~~~~~");

                // No need to check for ACK since it'll be sent to the ACK socket, not the data transfer socket
                if (!jPacket.destAddress.equals(myPrivateAddress)) {
                    udpSocket.send(
                            new DatagramPacket(actualPacket, actualPacket.length,
                                    routingTable.get(jPacket.destAddress).nextHop,
                                    (routingTable.get(jPacket.destAddress).metric == 1 &&
                                            JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.ACK_INDEX)) ? UDP_ACK_PORT : UDP_PORT));

                    System.out.println("Not meant for me. Sent it to " + routingTable.get(jPacket.destAddress).nextHop);
                    continue;
                }

                // drop packet if:
                // 1. It's a SYN and we're not expecting a SYN
                // 2. It's a normal packet and we're not expecting that sequence number
                if ((JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.NORMAL_INDEX) && jPacket.seqNumber != prevSequenceNumber) ||
                        (JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.SYN_INDEX) && prevSequenceNumber != 0)) {
                    continue;
                }

                if (JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.SYN_INDEX)) {
                    assert jPacket.payload != null;
                    totalFileSize = jPacket.totalSize - jPacket.payload.length;
                    sendAckForPacket(jPacket);

                } else if (JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.NORMAL_INDEX)) {
                    assert jPacket.payload != null;
                    totalFileSize -= jPacket.payload.length;
                    sendAckForPacket(jPacket);
                }

                prevSequenceNumber += 1;
                fileOutputStream.write(jPacket.payload);
                System.out.println("Remaining file to be received is " + totalFileSize);

                if (totalFileSize == 0 && !JPacketUtil.isBitSet(jPacket.flags, JPacketUtil.ACK_INDEX)) {
                    System.out.println("FILE FULLY RECEIVED. Saved as 'OUTPUT_FILE' ============================");
                    fileOutputStream.close();
                    System.exit(42);
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(42);
        }
    }

    /**
     * Sends an ACK for the given jPacket
     * @param jPacket the packet which needs to be acknowledged
     * @throws IOException
     */
    private void sendAckForPacket(JPacket jPacket) throws IOException {
        byte[] ackPacket = JPacketUtil.jPacket2Arr(jPacket.sourceAddress, myPrivateAddress, DOES_NOT_MATTER,
                jPacket.seqNumber + 1,
                BitUtils.setBitInByte((byte) 0, JPacketUtil.ACK_INDEX),
                new byte[0], DOES_NOT_MATTER);

        System.out.println("Sending ACK to " + routingTable.get(jPacket.sourceAddress).nextHop);
        udpSocket.send(new DatagramPacket(ackPacket, ackPacket.length,
                routingTable.get(jPacket.sourceAddress).nextHop,
                routingTable.get(jPacket.sourceAddress).metric == 1 ? UDP_ACK_PORT : UDP_PORT));
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
     *
     * @param id the rover's id
     * @return a private IP based on the IP address
     */
    private InetAddress idToPrivateIp(byte id) throws UnknownHostException {
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
        byte[] buf = new byte[RIP_LISTEN_WINDOW];
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

        LOGGER.info(myPublicAddress + "'s table as updated after rover death is \n" + getStringRoutingTable());

        // send a triggered update
        sendRIPUpdate();
    }

    /**
     * Update the routing table based on the given entry.
     * Note: this function was separated from updateRoutingTable since it is also used when a neighbor dies
     *
     * @param neighborPublicIp the ip of the neighbor who sent this entry
     * @param entry            the entry in that neighbor's table
     */
    private void updateTableFromEntry(InetAddress neighborPublicIp, RoutingTableEntry entry) {

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
            routingTable.get(entry.ipAddress).metric =
                    (byte) ((1 + entryVal) >= INFINITY ? INFINITY : 1 + entryVal);
            routingTable.get(entry.ipAddress).nextHop = neighborPublicIp;
            routingTable.get(entry.ipAddress).subnetMask = entry.subnetMask;
        } else {
        }
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
            new Rover(argsParser.roverId, argsParser.multicastPort, argsParser.multicastAddress, argsParser.fileToSend,
                    argsParser.destAddress);
        }
    }
}
