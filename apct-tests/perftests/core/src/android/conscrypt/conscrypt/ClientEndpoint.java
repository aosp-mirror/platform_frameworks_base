/*
 * Copyright 2017 The Android Open Source Project
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

package android.conscrypt;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.conscrypt.ChannelType;

/**
 * Client-side endpoint. Provides basic services for sending/receiving messages from the client
 * socket.
 */
final class ClientEndpoint {
    private final SSLSocket socket;
    private InputStream input;
    private OutputStream output;

    ClientEndpoint(SSLSocketFactory socketFactory, ChannelType channelType, int port,
            String[] protocols, String[] ciphers) throws IOException {
        socket = channelType.newClientSocket(socketFactory, InetAddress.getLoopbackAddress(), port);
        socket.setEnabledProtocols(protocols);
        socket.setEnabledCipherSuites(ciphers);
    }

    void start() {
        try {
            socket.startHandshake();
            input = socket.getInputStream();
            output = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    void stop() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    int readMessage(byte[] buffer) {
        try {
            int totalBytesRead = 0;
            while (totalBytesRead < buffer.length) {
                int remaining = buffer.length - totalBytesRead;
                int bytesRead = input.read(buffer, totalBytesRead, remaining);
                if (bytesRead == -1) {
                    break;
                }
                totalBytesRead += bytesRead;
            }
            return totalBytesRead;
        } catch (SSLException e) {
            if (e.getCause() instanceof EOFException) {
                return -1;
            }
            throw new RuntimeException(e);
        } catch (ClosedChannelException e) {
            // Thrown for channel-based sockets. Just treat like EOF.
            return -1;
        }  catch (SocketException e) {
            // The socket was broken. Just treat like EOF.
            return -1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void sendMessage(byte[] data) {
        try {
            output.write(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void flush() {
        try {
            output.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}