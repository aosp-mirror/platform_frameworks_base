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
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A simple socket-based test server.
 */
final class ServerEndpoint {
    /**
     * A processor for receipt of a single message.
     */
    public interface MessageProcessor {
        void processMessage(byte[] message, int numBytes, OutputStream os);
    }

    /**
     * A {@link MessageProcessor} that simply echos back the received message to the client.
     */
    public static final class EchoProcessor implements MessageProcessor {
        @Override
        public void processMessage(byte[] message, int numBytes, OutputStream os) {
            try {
                os.write(message, 0, numBytes);
                os.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private final ServerSocket serverSocket;
    private final SSLSocketFactory socketFactory;
    private final int messageSize;
    private final String[] protocols;
    private final String[] cipherSuites;
    private final byte[] buffer;
    private SSLSocket socket;
    private ExecutorService executor;
    private InputStream inputStream;
    private OutputStream outputStream;
    private volatile boolean stopping;
    private volatile MessageProcessor messageProcessor = new EchoProcessor();
    private volatile Future<?> processFuture;

    ServerEndpoint(SSLSocketFactory socketFactory, SSLServerSocketFactory serverSocketFactory,
            int messageSize, String[] protocols,
            String[] cipherSuites) throws IOException {
        this.serverSocket = serverSocketFactory.createServerSocket();
        this.socketFactory = socketFactory;
        this.messageSize = messageSize;
        this.protocols = protocols;
        this.cipherSuites = cipherSuites;
        buffer = new byte[messageSize];
    }

    void setMessageProcessor(MessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
    }

    Future<?> start() throws IOException {
        executor = Executors.newSingleThreadExecutor();
        return executor.submit(new AcceptTask());
    }

    void stop() {
        try {
            stopping = true;

            if (socket != null) {
                socket.close();
                socket = null;
            }

            if (processFuture != null) {
                processFuture.get(5, TimeUnit.SECONDS);
            }

            serverSocket.close();

            if (executor != null) {
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                executor = null;
            }
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private final class AcceptTask implements Runnable {
        @Override
        public void run() {
            try {
                if (stopping) {
                    return;
                }
                socket = (SSLSocket) serverSocket.accept();
                socket.setEnabledProtocols(protocols);
                socket.setEnabledCipherSuites(cipherSuites);

                socket.startHandshake();

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                if (stopping) {
                    return;
                }
                processFuture = executor.submit(new ProcessTask());
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private final class ProcessTask implements Runnable {
        @Override
        public void run() {
            try {
                Thread thread = Thread.currentThread();
                while (!stopping && !thread.isInterrupted()) {
                    int bytesRead = readMessage();
                    if (!stopping && !thread.isInterrupted()) {
                        messageProcessor.processMessage(buffer, bytesRead, outputStream);
                    }
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        private int readMessage() throws IOException {
            int totalBytesRead = 0;
            while (!stopping && totalBytesRead < messageSize) {
                try {
                    int remaining = messageSize - totalBytesRead;
                    int bytesRead = inputStream.read(buffer, totalBytesRead, remaining);
                    if (bytesRead == -1) {
                        break;
                    }
                    totalBytesRead += bytesRead;
                } catch (SSLException e) {
                    if (e.getCause() instanceof EOFException) {
                        break;
                    }
                    throw e;
                } catch (ClosedChannelException e) {
                    // Thrown for channel-based sockets. Just treat like EOF.
                    break;
                } catch (SocketException e) {
                    // The socket was broken. Just treat like EOF.
                    break;
                }
            }
            return totalBytesRead;
        }
    }
}