/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dumprendertree.forwarder;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 *
 * Worker class for {@link ForwardServer}. A Forwarder will be created once the ForwardServer
 * accepts an incoming connection, and it will then forward the incoming/outgoing streams to a
 * connection already proxied by adb networking (see also {@link AdbUtils}).
 *
 */
public class Forwarder {

    private ForwardServer server;
    private Socket from, to;

    private static final String LOGTAG = "Forwarder";
    private static final int BUFFER_SIZE = 16384;

    public Forwarder (Socket from, Socket to, ForwardServer server) {
        this.server = server;
        this.from = from;
        this.to = to;
        server.register(this);
    }

    public void start() {
        Thread outgoing = new Thread(new SocketPipe(from, to));
        Thread incoming = new Thread(new SocketPipe(to, from));
        outgoing.setName(LOGTAG);
        incoming.setName(LOGTAG);
        outgoing.start();
        incoming.start();
    }

    public void stop() {
        shutdown(from);
        shutdown(to);
    }

    private void shutdown(Socket socket) {
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            Log.v(LOGTAG, "Socket#shutdownInput", e);
        }
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            Log.v(LOGTAG, "Socket#shutdownOutput", e);
        }
        try {
            socket.close();
        } catch (IOException e) {
            Log.v(LOGTAG, "Socket#close", e);
        }
    }

    private class SocketPipe implements Runnable {

        private Socket in, out;

        public SocketPipe(Socket in, Socket out) {
            this.in = in;
            this.out = out;
        }

        public void run() {
            try {
                int length;
                InputStream is = in.getInputStream();
                OutputStream os = out.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            } catch (IOException ioe) {
            } finally {
                server.unregister(Forwarder.this);
            }
        }

        @Override
        public String toString() {
            return "SocketPipe{" + in + "=>" + out  + "}";
        }
    }
}
