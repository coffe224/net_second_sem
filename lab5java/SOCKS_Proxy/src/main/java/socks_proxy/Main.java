package socks_proxy;


import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("You must enter the <port> where the proxy will wait for incoming connections from clients");
            return;
        }

        try {
            int port = Integer.parseInt(args[0]);
            SocksProxy proxy = new SocksProxy(port);
            proxy.execute();
        }
        catch (NumberFormatException nfe) {
            System.out.println("Cannot to get the port from '" + args[0] + "'");
            return;
        }
        catch (IOException io) {
            System.out.println(io.getMessage());
            return;
        }
    }
}