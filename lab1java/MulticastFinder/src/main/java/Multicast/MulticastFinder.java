package Multicast;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MulticastFinder implements AutoCloseable {
    private final int PORT = 1234;
    private final int SEND_INTERVAL = 2;
    private final int TIMEOUT = 5;

    private final InetAddress groupAddress;
    private final MulticastSocket socket;

    private static class Info {
        public InetAddress address;
        public int id;

        public Info(InetAddress address, int id) {
            this.address = address;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Info info = (Info) obj;
            return id == info.id && Objects.equals(address, info.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, id);
        }
    }

    private final int ownId;
    private final ConcurrentHashMap<Info, Long> aliveAddresses;


    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Object lock = new Object();

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.out.println("Bad arguments count");
            return;
        }
        String groupAddressArg = args[0];
        InetAddress groupAddress = InetAddress.getByName(groupAddressArg);

        String portArg = args[1];
        int port = Integer.parseInt(portArg);

        MulticastFinder multicastFinder = new MulticastFinder(groupAddress);
    }

    public MulticastFinder(InetAddress groupAddress) throws IOException {
        this.groupAddress = groupAddress;
        aliveAddresses = new ConcurrentHashMap<>();

        Random random = new Random();
        ownId = random.nextInt();
        System.out.println("Own id: " + ownId);

        socket = new MulticastSocket(PORT);
        socket.joinGroup(groupAddress);



        startListener();
        startSender();
        startCleaner();
    }

    public void startCleaner() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            Set<Info> toRemove = new HashSet<>();

            for (Map.Entry<Info, Long> entry : aliveAddresses.entrySet()) {
                if (now - entry.getValue() > TIMEOUT * 1000) {
                    toRemove.add(entry.getKey());
                }
            }

            if (!toRemove.isEmpty()) {
                toRemove.forEach(aliveAddresses::remove);
                System.out.println("Removed");
                printAliveAddresses();
            }
        }, 0, TIMEOUT, TimeUnit.SECONDS);
    }

    public void send() throws IOException {
        byte[] idBytes = ByteBuffer.allocate(4).putInt(ownId).array();
        DatagramPacket packet = new DatagramPacket(idBytes, idBytes.length, groupAddress, PORT);
        socket.send(packet);
    }

    public void startSender() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                send();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, 0, SEND_INTERVAL, TimeUnit.SECONDS);
    }

    public void receive() throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        processMessage(packet);
    }

    public void processMessage(DatagramPacket receivedMessage) {
        byte[] receivedData = receivedMessage.getData();
        int senderId = ByteBuffer.wrap(receivedData).getInt();

        InetAddress senderAddress = receivedMessage.getAddress();

        Info senderInfo = new Info(senderAddress, senderId);

        if (ownId == senderId) {
            return;
        }

        boolean isNew = !aliveAddresses.containsKey(senderInfo);

        long now = System.currentTimeMillis();
        aliveAddresses.put(senderInfo, now);

        if (isNew) {
            System.out.println("Added");
            printAliveAddresses();
        }
    }

    public void startListener() {
        scheduler.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    receive();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public void printAliveAddresses() {
        System.out.println("Alive copies:");
        synchronized (lock) {
            if (aliveAddresses.isEmpty()) {
                System.out.println("None");
            } else {
                for (Info info : aliveAddresses.keySet()) {
                    System.out.println(info.address.getHostAddress());
                }
            }
        }
        System.out.println();
    }

    @Override
    public void close() throws Exception {
        socket.leaveGroup(groupAddress);
        socket.close();
        scheduler.shutdownNow();
    }
}
