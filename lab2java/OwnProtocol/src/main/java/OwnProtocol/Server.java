package OwnProtocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Server implements AutoCloseable {
    private static final int BUFFER_SIZE = 8192;
    private static final String UPLOAD_DIR = "uploads";
    private static final int MAGIC_NUMBER = 42;

    private final ServerSocket serverSocket;

    public Server(int port) throws IOException {
        Path dir = Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        this.serverSocket = new ServerSocket(port);
        System.out.println("SERVER IS READY on port " + port);
        start();
    }

    public void start() throws IOException {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> {
                    try {
                        handleClient(client);
                    } catch (Exception e) {
                        System.err.println("Error handling client: " + e.getMessage());
                        sendErrorCode(client, (byte) 1);
                    } finally {
                        safeClose(client);
                    }
                }).start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("Accept failed: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        try (InputStream input = client.getInputStream();
             OutputStream output = client.getOutputStream()) {

            // Read magic number
            int magicNumber = readInt(input);
            if (magicNumber != MAGIC_NUMBER) {
                throw new IOException("Invalid magic number: " + magicNumber);
            }

            // Read filename size
            int filenameBytesSize = readInt(input);
            if (filenameBytesSize <= 0 || filenameBytesSize > 1024) { // reasonable limit
                throw new IOException("Invalid filename size: " + filenameBytesSize);
            }

            // Read filename
            byte[] filenameBytes = new byte[filenameBytesSize];
            readFully(input, filenameBytes, 0, filenameBytesSize);
            String filename = new String(filenameBytes, StandardCharsets.UTF_8);

            // Read file size
            long fileSize = readLong(input);
            if (fileSize < 0 || fileSize > 10L * 1024 * 1024 * 1024) { // limit to 10 GB
                throw new IOException("Invalid file size: " + fileSize);
            }

            // Write file
            Path outputPath = Paths.get(UPLOAD_DIR, filename).toAbsolutePath().normalize();
            if (!outputPath.startsWith(Paths.get(UPLOAD_DIR).toAbsolutePath())) {
                throw new IOException("Invalid file path");
            }


            // Upload with speed tracking
            long totalBytesRead = 0;
            final long startTimeNanos = System.nanoTime();
            long lastReportTimeNanos = startTimeNanos;
            long lastReportBytes = 0;
            final long REPORT_INTERVAL_NANOS = 3_000_000_000L; // 3 seconds

            try (FileOutputStream fileOutput = new FileOutputStream(outputPath.toFile())) {
                byte[] fileBuffer = new byte[BUFFER_SIZE];

                while (totalBytesRead < fileSize) {
                    long remaining = fileSize - totalBytesRead;
                    int toRead = (int) Math.min(fileBuffer.length, remaining);
                    int bytesRead = input.read(fileBuffer, 0, toRead);
                    if (bytesRead == -1) {
                        throw new IOException("Connection closed unexpectedly. Expected " +
                                (fileSize - totalBytesRead) + " more bytes.");
                    }

                    fileOutput.write(fileBuffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    // Speed calculation
                    long nowNanos = System.nanoTime();
                    long elapsedSinceLastReport = nowNanos - lastReportTimeNanos;

                    if (elapsedSinceLastReport >= REPORT_INTERVAL_NANOS) {
                        // Recent (last 3s) speed
                        long bytesInInterval = totalBytesRead - lastReportBytes;
                        double intervalSeconds = elapsedSinceLastReport / 1_000_000_000.0;
                        double recentSpeedMBps = (bytesInInterval / (1024.0 * 1024.0)) / intervalSeconds;

                        // Overall average speed
                        double totalElapsedSeconds = (nowNanos - startTimeNanos) / 1_000_000_000.0;
                        double overallAvgSpeedMBps = (totalBytesRead / (1024.0 * 1024.0)) / totalElapsedSeconds;

                        System.out.printf(
                                "[UPLOADING %s] Recent: %.2f MB/s | Overall: %.2f MB/s | (%.1f%%)%n",
                                filename,
                                recentSpeedMBps,
                                overallAvgSpeedMBps,
                                (totalBytesRead * 100.0) / fileSize
                        );

                        // Update report markers
                        lastReportTimeNanos = nowNanos;
                        lastReportBytes = totalBytesRead;
                    }
                }
            }

            // Final stats
            long totalTimeNanos = System.nanoTime() - startTimeNanos;
            double totalTimeSeconds = totalTimeNanos / 1_000_000_000.0;
            double finalAvgSpeedMBps = (totalBytesRead / (1024.0 * 1024.0)) / totalTimeSeconds;

            output.write(0); // success

            System.out.printf(
                    "Upload complete: %s | Final Avg Speed: %.2f MB/s | Total Time: %.2f s%n",
                    outputPath.getFileName(),
                    finalAvgSpeedMBps,
                    totalTimeSeconds
            );
        }
    }

    private void readFully(InputStream in, byte[] buffer, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buffer, off + total, len - total);
            if (n == -1) {
                throw new IOException("Unexpected EOF while reading " + (len - total) + " more bytes");
            }
            total += n;
        }
    }

    private int readInt(InputStream in) throws IOException {
        byte[] buf = new byte[4];
        readFully(in, buf, 0, 4);
        return ByteBuffer.wrap(buf).getInt();
    }

    private long readLong(InputStream in) throws IOException {
        byte[] buf = new byte[Long.BYTES];
        readFully(in, buf, 0, Long.BYTES);
        return ByteBuffer.wrap(buf).getLong();
    }

    private void sendErrorCode(Socket client, byte code) {
        try (OutputStream out = client.getOutputStream()) {
            out.write(code);
        } catch (IOException e) {
            System.err.println("Failed to send error code: " + e.getMessage());
        }
    }

    private void safeClose(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Failed to close client socket: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            System.out.println("Server socket closed.");
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Server <port>");
            return;
        }
        try {
            int serverPort = Integer.parseInt(args[0]);
            try (Server server = new Server(serverPort)) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.close();
                    } catch (Exception e) {
                        System.err.println("Error during shutdown: " + e.getMessage());
                    }
                }));
                synchronized (server) {
                    server.wait();
                }
            }
        } catch (IOException e) {
            System.err.println("Server failed to start: " + e.getMessage());
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            System.out.println("Server interrupted.");
        }
    }
}