package OwnProtocol;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Client implements AutoCloseable {
    private static final int MAX_FILENAME_LENGTH_BYTES = 4096;
    private static final long MAX_FILE_SIZE_BYTES = 1L << 40; // 1 TB
    private static final int MAGIC_NUMBER = 42;
    private static final int BUFFER_SIZE = 8192;

    private final Socket socket;
    private final File file;
    private final byte[] filenameBytes;
    private final long fileSize;

    public Client(String filePath, String serverDomain, int serverPort) throws IOException {
        this.file = new File(filePath).getCanonicalFile(); // resolves . and ..

        if (!file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + filePath);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Path is not a file: " + filePath);
        }

        String filename = file.getName();
        if (filename.isEmpty()) {
            throw new IllegalArgumentException("File has empty name");
        }

        this.filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
        if (filenameBytes.length > MAX_FILENAME_LENGTH_BYTES) {
            throw new IllegalArgumentException("Filename too long: " + filename);
        }

        this.fileSize = file.length();
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File too large: " + fileSize + " bytes (max: " + MAX_FILE_SIZE_BYTES + ")");
        }

        InetAddress serverAddress = InetAddress.getByName(serverDomain);
        this.socket = new Socket(serverAddress, serverPort);
        System.out.println("Connected to server: " + serverAddress.getHostAddress() + ":" + serverPort);
    }

    public void upload() throws IOException {
        try (OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream()) {

            //Send magic number
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putInt(MAGIC_NUMBER);
            output.write(buf.array(), 0, Integer.BYTES);

            // Send filename length
            int nameLen = filenameBytes.length;
            buf.clear();
            buf.putInt(nameLen);
            output.write(buf.array(), 0, Integer.BYTES);
            System.out.println("Sending filename length: " + nameLen);

            // Send filename
            output.write(filenameBytes);
            System.out.println("Sending filename: " + new String(filenameBytes, StandardCharsets.UTF_8));

            // Send file size
            buf.clear();
            buf.putLong(fileSize);
            output.write(buf.array(), 0, Long.BYTES);
            System.out.println("Sending file size: " + fileSize);

            // Send file content
            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalSent = 0;
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }
                if (totalSent != fileSize) {
                    throw new IOException("Sent " + totalSent + " bytes, but file is " + fileSize + " bytes");
                }
            }
            output.flush();
            System.out.println("File data sent.");

            // Read server response
            int response = input.read();
            if (response == -1) {
                throw new IOException("Server closed connection unexpectedly");
            }

            if (response == 0) {
                System.out.println("File uploaded successfully.");
            } else {
                throw new IOException("Server rejected upload. Error code: " + response);
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Warning: failed to close socket: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: java Client <file_path> <server_domain> <server_port>");
            return;
        }

        String filePath = args[0];
        String serverDomain = args[1];
        int serverPort;
        try {
            serverPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + args[2]);
            return;
        }

        Client client = null;
        try {
            client = new Client(filePath, serverDomain, serverPort);
            client.upload();
        } catch (Exception e) {
            System.err.println("Upload failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    System.err.println("Error closing client: " + e.getMessage());
                }
            }
        }
    }
}