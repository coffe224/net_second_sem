package socks_proxy;

import java.io.*;
import java.nio.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import org.xbill.DNS.*;

public class SocksProxy {
    private enum SessionState {
        GREETING,
        REQUEST,
        CONNECTING,
        RELAYING,
        RESOLVING,
        CLOSED
    }

    private static class Session {
        SocketChannel client;
        SocketChannel remote;

        SelectionKey clientKey;
        SelectionKey remoteKey;

        ByteBuffer clientToRemoteBuff = ByteBuffer.allocateDirect(64 * 1024);
        ByteBuffer remoteToClientBuff = ByteBuffer.allocateDirect(64 * 1024);
        ByteBuffer messagesBuff = ByteBuffer.allocateDirect(2 * 1024);

        SessionState state = SessionState.GREETING;

        String targetHost;
        int targetPort;

        volatile boolean endRemoteChannel = false;
        volatile boolean endClientChannel = false;

        Session(SocketChannel client) {
            this.client = client;
        }

        public boolean isClosed() {
            return (state == SessionState.CLOSED);
        }

        public void close() {
            closeQuietly(client);
            closeQuietly(remote);

            if (clientKey != null)
                clientKey.cancel();
            if (remoteKey != null)
                remoteKey.cancel();

            state = SessionState.CLOSED;
        }

        private void closeQuietly(Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (IOException ignored) {
                    // Intentionally ignored
                }
            }
        }
    }

    private static class DnsQuery {
        Session dnsSession;
        final long waitingTime;

        DnsQuery(Session session) {
            dnsSession = session;
            waitingTime = System.nanoTime();
        }
    }

    private static final long DNS_TIMEOUT = 8_000_000_000L;
    private static final long SELECTOR_TIMEOUT = 1_000;

    private int port = 5252;
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final DatagramChannel dnsChannel;
    private final InetSocketAddress dnsResolver;

    private final Map<Integer, DnsQuery> dnsQueries = new ConcurrentHashMap<>();

    public SocksProxy(int suggestedPort) throws IOException {
        validatePort(suggestedPort);

        selector = Selector.open();
        serverChannel = createServerChannel();
        dnsChannel = createDnsChannel();
        dnsResolver = ResolverConfig.getCurrentConfig().server();

        System.out.println("SOCKS5 proxy listening on port " + port);
    }

    private void validatePort(int suggestedPort) {
        if ((suggestedPort < 0) || (suggestedPort > 65535)) {
            System.out.println("The number " + suggestedPort + " is not within the acceptable range of the port. Will be set default: 5252");
        } else {
            port = suggestedPort;
        }
    }

    private ServerSocketChannel createServerChannel() throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress(port));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);
        return channel;
    }

    private DatagramChannel createDnsChannel() throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.bind(null);
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ);
        return channel;
    }

    public void execute() throws IOException {
        while (true) {
            cleanupTimedOutQueries();

            int readyChannelsNumber = selector.select(SELECTOR_TIMEOUT);
            if (readyChannelsNumber == 0)
                continue;

            processSelectedKeys();
        }
    }

    private void cleanupTimedOutQueries() {
        long currentTime = System.nanoTime();
        List<Integer> toRemove = new ArrayList<>();

        for(Map.Entry<Integer, DnsQuery> query : dnsQueries.entrySet()) {
            if ((currentTime - query.getValue().waitingTime) > DNS_TIMEOUT) {
                Session session = query.getValue().dnsSession;
                try {
                    sendErrorToClient(session, (byte) 0x04);
                } catch (IOException ignored) {
                    // Intentionally ignored
                }
                session.close();
                toRemove.add(query.getKey());
            }
        }

        for (Integer id : toRemove)
            dnsQueries.remove(id);
    }

    private void processSelectedKeys() throws IOException {
        Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
        while(keyIterator.hasNext()) {
            SelectionKey key = keyIterator.next();
            keyIterator.remove();

            if (!key.isValid())
                continue;

            try {
                handleKeyEvents(key);
            } catch (IOException io) {
                Object attachment = key.attachment();
                if (attachment instanceof Session)
                    ((Session) attachment).close();

                System.out.println("Something gone wrong: " + io.getMessage());
            }
        }
    }

    private void handleKeyEvents(SelectionKey key) throws IOException {
        if (key.isAcceptable()) {
            handleAccept(key);
            return;
        }

        if (key.isReadable() && key.channel() == dnsChannel) {
            handleDnsRead();
            return;
        }

        if (key.isReadable()) {
            handleRead(key);
        }

        if (key.isValid() && key.isWritable()) {
            handleWrite(key);
        }

        if (key.isValid() && key.isConnectable()) {
            handleRemoteConnect(key);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        SocketChannel socketChannel = serverChannel.accept();
        if (socketChannel == null)
            return;

        socketChannel.configureBlocking(false);
        Session session = new Session(socketChannel);
        SelectionKey clientKey = socketChannel.register(selector, SelectionKey.OP_READ, session);
        session.clientKey = clientKey;
    }

    private void handleDnsRead() throws IOException {
        ByteBuffer dnsBuffer = ByteBuffer.allocate(4 * 1024);
        SocketAddress sender = dnsChannel.receive(dnsBuffer);
        if (sender == null)
            return;

        dnsBuffer.flip();
        try {
            Message msg = new Message(dnsBuffer);
            int dnsQueryId = msg.getHeader().getID();
            DnsQuery dnsQuery = dnsQueries.remove(dnsQueryId);
            if (dnsQuery == null)
                return;

            InetAddress resolved = extractResolvedAddress(msg);
            Session session = dnsQuery.dnsSession;

            if (resolved == null) {
                sendErrorToClient(session, (byte) 0x04);
                session.close();
            } else {
                startConnection(session, resolved);
            }
        } catch (IOException ignored) {
            // Intentionally ignored
        }
    }

    private InetAddress extractResolvedAddress(Message msg) {
        List<org.xbill.DNS.Record> answers = msg.getSection(Section.ANSWER);
        for (org.xbill.DNS.Record answer : answers) {
            if (answer instanceof ARecord) {
                return ((ARecord) answer).getAddress();
            }
        }
        return null;
    }

    private void resolveHostName(Session session) {
        try {
            Name resolvingName = Name.fromString(session.targetHost.endsWith(".") ?
                    session.targetHost : session.targetHost + ".");
            org.xbill.DNS.Record record = org.xbill.DNS.Record.newRecord(resolvingName, Type.A, DClass.IN);
            Message msg = Message.newQuery(record);

            if (dnsQueries.size() >= 65536)
                throw new IOException("DNS Queries map is already full");

            int queryId = generateUniqueQueryId();
            msg.getHeader().setID(queryId);

            byte[] data = msg.toWire();
            ByteBuffer toDns = ByteBuffer.wrap(data);
            dnsChannel.send(toDns, dnsResolver);
            dnsQueries.put(queryId, new DnsQuery(session));

            session.state = SessionState.RESOLVING;
        } catch (IOException e) {
            try {
                sendErrorToClient(session, (byte) 0x04);
            } catch (IOException ignored) {
                // Intentionally ignored
            }
            session.close();
        }
    }

    private int generateUniqueQueryId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int queryId = random.nextInt(1, 65536);
        while (dnsQueries.containsKey(queryId)) {
            queryId = random.nextInt(1, 65536);
        }
        return queryId;
    }

    private void handleRead(SelectionKey key) throws IOException {
        Session session = (Session) key.attachment();
        if ((session == null) || session.isClosed() || !key.isValid())
            return;

        if (key.channel() == session.client)
            handleClientRead(session);
        else if (key.channel() == session.remote)
            handleRemoteRead(session);
    }

    private void handleClientRead(Session session) throws IOException {
        if (session.isClosed())
            return;

        if ((session.state == SessionState.GREETING) || (session.state == SessionState.REQUEST)) {
            if (!readMessageBuffer(session)) {
                return;
            }

            session.messagesBuff.flip();

            if (session.state == SessionState.GREETING && !handleGreeting(session)) {
                session.messagesBuff.compact();
                return;
            }

            if (session.state == SessionState.REQUEST && !handleRequest(session)) {
                session.messagesBuff.compact();
                return;
            }

            session.messagesBuff.compact();
            return;
        }

        if (session.state == SessionState.RELAYING) {
            handleRelayingRead(session, session.client, session.clientToRemoteBuff,
                    session.remoteKey, true);
        }
    }

    private boolean readMessageBuffer(Session session) throws IOException {
        int readBytes = session.client.read(session.messagesBuff);
        if (readBytes == -1) {
            session.close();
            return false;
        }
        return readBytes > 0;
    }

    private boolean handleGreeting(Session session) throws IOException {
        ByteBuffer buff = session.messagesBuff;
        if (buff.remaining() < 2)
            return false;

        buff.mark();
        byte version = buff.get();
        byte methodsNumber = buff.get();
        if (buff.remaining() < (methodsNumber & 0xFF)) {
            buff.reset();
            return false;
        }

        boolean isAuth = false;
        for (int i = 0; i < (methodsNumber & 0xFF); i++) {
            if (buff.get() == 0x00) {
                isAuth = true;
                break;
            }
        }

        if (!isAuth) {
            sendAuthMethod(session, (byte) 0xFF);
            session.close();
            return false;
        }

        sendAuthMethod(session, (byte) 0x00);
        session.state = SessionState.REQUEST;

        return true;
    }

    private boolean handleRequest(Session session) throws IOException {
        ByteBuffer buff = session.messagesBuff;
        if (buff.remaining() < 10)
            return false;

        buff.mark();
        byte version = buff.get();
        byte code = buff.get();
        buff.get(); // Skip reserved byte
        byte type = buff.get();

        if ((version != 0x05) || (code != 0x01)) {
            sendErrorToClient(session, (byte) 0x07);
            session.close();
            return false;
        }

        if (type == 0x01) {
            return handleIPv4Request(session, buff);
        } else if (type == (byte) 0x03) {
            return handleDomainNameRequest(session, buff);
        } else {
            sendErrorToClient(session, (byte) 0x08);
            session.close();
            return false;
        }
    }

    private boolean handleIPv4Request(Session session, ByteBuffer buff) throws IOException {
        if (buff.remaining() < 6) {
            buff.reset();
            return false;
        }

        byte[] ipAddress = new byte[4];
        buff.get(ipAddress);
        int port = readPort(buff);

        try {
            InetAddress address = InetAddress.getByAddress(ipAddress);
            session.targetHost = address.getHostAddress();
            session.targetPort = port;

            startConnection(session, address);
            return true;
        } catch (UnknownHostException e) {
            sendErrorToClient(session, (byte) 0x04);
            session.close();
            return false;
        }
    }

    private boolean handleDomainNameRequest(Session session, ByteBuffer buff) throws IOException {
        if (buff.remaining() < 1) {
            buff.reset();
            return false;
        }

        byte len = buff.get();
        if (buff.remaining() < (len + 2)) {
            buff.reset();
            return false;
        }

        byte[] byteName = new byte[len];
        buff.get(byteName);
        int port = readPort(buff);

        session.targetHost = new String(byteName, StandardCharsets.UTF_8);
        session.targetPort = port;

        resolveHostName(session);
        return true;
    }

    private int readPort(ByteBuffer buff) {
        return ((buff.get() & 0xFF) << 8) | (buff.get() & 0xFF);
    }

    private void startConnection(Session session, InetAddress address) throws IOException {
        if (session.isClosed())
            return;

        session.remote = SocketChannel.open();
        session.remote.configureBlocking(false);

        try {
            session.remote.connect(new InetSocketAddress(address, session.targetPort));
        } catch (UnresolvedAddressException e) {
            sendErrorToClient(session, (byte) 0x04);
            session.close();
            return;
        }

        session.remoteKey = session.remote.register(selector, SelectionKey.OP_CONNECT, session);
        session.state = SessionState.CONNECTING;
    }

    private void handleRemoteRead(Session session) throws IOException {
        if (session.isClosed())
            return;

        handleRelayingRead(session, session.remote, session.remoteToClientBuff,
                session.clientKey, false);
    }

    private void handleRelayingRead(Session session, SocketChannel channel,
                                    ByteBuffer buffer, SelectionKey oppositeKey, boolean isClient) throws IOException {
        int readBytes = channel.read(buffer);
        if (readBytes == -1) {
            shutdownOpposite(session, isClient);
            updateKeyInterest(oppositeKey, false, SelectionKey.OP_READ);

            if (isClient) {
                session.endClientChannel = true;
            } else {
                session.endRemoteChannel = true;
            }
            closeOnEnd(session);
            return;
        }

        if (readBytes == 0)
            return;

        if (buffer.position() > 0 && oppositeKey != null && oppositeKey.isValid() && !session.isClosed()) {
            oppositeKey.interestOps(oppositeKey.interestOps() | SelectionKey.OP_WRITE);
        }

        if (buffer.position() == buffer.capacity() && !buffer.hasRemaining()) {
            updateKeyInterest(oppositeKey, false, SelectionKey.OP_READ);
        }
    }

    private void shutdownOpposite(Session session, boolean isClient) throws IOException {
        SocketChannel oppositeChannel = isClient ? session.remote : session.client;
        if (oppositeChannel != null) {
            oppositeChannel.shutdownOutput();
        }
    }

    private void updateKeyInterest(SelectionKey key, boolean add, int ops) {
        if (key != null && key.isValid()) {
            if (add) {
                key.interestOps(key.interestOps() | ops);
            } else {
                key.interestOps(key.interestOps() & ~ops);
            }
        }
    }

    private void handleRemoteConnect(SelectionKey key) throws IOException {
        Session session = (Session) key.attachment();
        if ((session == null) || (session.remote == null) || !key.isValid() || session.isClosed())
            return;

        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.finishConnect()) {
            InetSocketAddress localBind = (InetSocketAddress) channel.getLocalAddress();
            sendResponseToClient(session, localBind.getAddress(), localBind.getPort());
            session.state = SessionState.RELAYING;

            updateKeyInterest(session.clientKey, true, SelectionKey.OP_READ);
            updateKeyInterest(session.remoteKey, true, SelectionKey.OP_READ);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        Session session = (Session) key.attachment();
        if ((session == null) || session.isClosed() || !key.isValid())
            return;

        if (key.channel() == session.client)
            handleClientWrite(session);
        else if (key.channel() == session.remote)
            handleRemoteWrite(session);
    }

    private void handleClientWrite(Session session) throws IOException {
        handleChannelWrite(session, session.client, session.remoteToClientBuff,
                session.clientKey, session.remoteKey);
    }

    private void handleRemoteWrite(Session session) throws IOException {
        handleChannelWrite(session, session.remote, session.clientToRemoteBuff,
                session.remoteKey, session.clientKey);
    }

    private void handleChannelWrite(Session session, SocketChannel channel,
                                    ByteBuffer buffer, SelectionKey currentKey, SelectionKey oppositeKey) throws IOException {
        buffer.flip();
        if (buffer.hasRemaining()) {
            channel.write(buffer);
        }

        if (!buffer.hasRemaining()) {
            updateKeyInterest(currentKey, false, SelectionKey.OP_WRITE);
        }

        buffer.compact();
        updateKeyInterest(oppositeKey, true, SelectionKey.OP_READ);

        closeOnEnd(session);
    }

    private void closeOnEnd(Session session) {
        boolean isBuffersEmpty = (session.clientToRemoteBuff.position() == 0) &&
                (session.remoteToClientBuff.position() == 0);

        if (session.endClientChannel && session.endRemoteChannel && isBuffersEmpty)
            session.close();
    }

    private void sendAuthMethod(Session session, byte method) throws IOException {
        ByteBuffer out = ByteBuffer.wrap(new byte[] { (byte) 0x05, method });
        writeToClient(session, out);
    }

    private void sendErrorToClient(Session session, byte errorCode) throws IOException {
        ByteBuffer error = ByteBuffer.allocate(10);
        error.put((byte) 0x05);
        error.put(errorCode);
        error.put((byte) 0x00);
        error.put((byte) 0x01);
        error.put(new byte[] { 0, 0, 0, 0 });
        error.putShort((short) 0);
        error.flip();

        writeToClient(session, error);
    }

    private void sendResponseToClient(Session session, InetAddress address, int fromPort) throws IOException {
        ByteBuffer response = ByteBuffer.allocate(10);
        response.put((byte) 0x05);
        response.put((byte) 0x00);
        response.put((byte) 0x00);
        response.put((byte) 0x01);
        response.put(address.getAddress());
        response.putShort((short) fromPort);
        response.flip();

        writeToClient(session, response);
    }

    private void writeToClient(Session session, ByteBuffer data) throws IOException {
        session.client.write(data);
        if (data.hasRemaining()) {
            data.flip();
            session.remoteToClientBuff.put(data);
            updateKeyInterest(session.clientKey, true, SelectionKey.OP_WRITE);
        }
    }
}