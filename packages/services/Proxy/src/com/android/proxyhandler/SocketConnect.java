package com.android.proxyhandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * @hide
 */
public class SocketConnect extends Thread {

    private InputStream from;
    private OutputStream to;

    public SocketConnect(Socket from, Socket to) throws IOException {
        this.from = from.getInputStream();
        this.to = to.getOutputStream();
        start();
    }

    @Override
    public void run() {
        final byte[] buffer = new byte[512];

        try {
            while (true) {
                int r = from.read(buffer);
                if (r < 0) {
                    break;
                }
                to.write(buffer, 0, r);
            }
            from.close();
            to.close();
        } catch (IOException io) {

        }
    }

    public static void connect(Socket first, Socket second) {
        try {
            SocketConnect sc1 = new SocketConnect(first, second);
            SocketConnect sc2 = new SocketConnect(second, first);
            try {
                sc1.join();
            } catch (InterruptedException e) {
            }
            try {
                sc2.join();
            } catch (InterruptedException e) {
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
