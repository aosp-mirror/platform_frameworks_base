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

import org.conscrypt.ChannelType;
import static org.conscrypt.TestUtils.getCommonProtocolSuites;
import static org.conscrypt.TestUtils.newTextMessage;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import android.conscrypt.ServerEndpoint.MessageProcessor;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmark for comparing performance of server socket implementations.
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class ServerSocketPerfTest {

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Provider for the benchmark configuration
     */
    private class Config {
        EndpointFactory a_clientFactory;
        EndpointFactory b_serverFactory;
        int c_messageSize;
        String d_cipher;
        ChannelType e_channelType;
        Config(EndpointFactory clientFactory,
            EndpointFactory serverFactory,
            int messageSize,
            String cipher,
            ChannelType channelType) {
          a_clientFactory = clientFactory;
          b_serverFactory = serverFactory;
          c_messageSize = messageSize;
          d_cipher = cipher;
          e_channelType = channelType;
        }
        public EndpointFactory clientFactory() {
            return a_clientFactory;
        }

        public EndpointFactory serverFactory() {
            return b_serverFactory;
        }

        public int messageSize() {
            return c_messageSize;
        }

        public String cipher() {
            return d_cipher;
        }

        public ChannelType channelType() {
            return e_channelType;
        }
    }

    public Collection getParams() {
        final List<Object[]> params = new ArrayList<>();
        for (EndpointFactory endpointFactory : EndpointFactory.values()) {
            for (ChannelType channelType : ChannelType.values()) {
                for (int messageSize : ConscryptParams.messageSizes) {
                    for (String cipher : ConscryptParams.ciphers) {
                        params.add(new Object[] {new Config(endpointFactory,
                            endpointFactory, messageSize, cipher, channelType)});
                    }
                }
            }
        }
        return params;
    }

    private SocketPair socketPair = new SocketPair();
    private ExecutorService executor;
    private Future<?> receivingFuture;
    private volatile boolean stopping;
    private static final AtomicLong bytesCounter = new AtomicLong();
    private AtomicBoolean recording = new AtomicBoolean();

    private static class SocketPair implements AutoCloseable {
        public ClientEndpoint client;
        public ServerEndpoint server;

        SocketPair() {
            client = null;
            server = null;
        }

        @Override
        public void close() {
            if (client != null) {
                client.stop();
            }
            if (server != null) {
                server.stop();
            }
        }
    }

    private void setup(final Config config) throws Exception {
        recording.set(false);

        byte[] message = newTextMessage(config.messageSize());

        final ChannelType channelType = config.channelType();

        socketPair.server = config.serverFactory().newServer(config.messageSize(),
            new String[] {"TLSv1.3", "TLSv1.2"}, ciphers(config));
        socketPair.server.init();
        socketPair.server.setMessageProcessor(new MessageProcessor() {
            @Override
            public void processMessage(byte[] inMessage, int numBytes, OutputStream os) {
                try {
                    try {
                        while (!stopping) {
                            os.write(inMessage, 0, numBytes);
                        }
                    } finally {
                        os.flush();
                    }
                } catch (SocketException e) {
                    // Just ignore.
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        Future<?> connectedFuture = socketPair.server.start();

        // Always use the same client for consistency across the benchmarks.
        socketPair.client = config.clientFactory().newClient(
                ChannelType.CHANNEL, socketPair.server.port(),
                new String[] {"TLSv1.3", "TLSv1.2"}, ciphers(config));
        socketPair.client.start();

        // Wait for the initial connection to complete.
        connectedFuture.get(5, TimeUnit.SECONDS);

        // Start the server-side streaming by sending a message to the server.
        socketPair.client.sendMessage(message);
        socketPair.client.flush();

        executor = Executors.newSingleThreadExecutor();
        receivingFuture = executor.submit(new Runnable() {
            @Override
            public void run() {
                Thread thread = Thread.currentThread();
                byte[] buffer = new byte[config.messageSize()];
                while (!stopping && !thread.isInterrupted()) {
                    int numBytes = socketPair.client.readMessage(buffer);
                    if (numBytes < 0) {
                        return;
                    }
                    assertEquals(config.messageSize(), numBytes);

                    // Increment the message counter if we're recording.
                    if (recording.get()) {
                        bytesCounter.addAndGet(numBytes);
                    }
                }
            }
        });
    }

    void close() throws Exception {
        stopping = true;
        // Stop and wait for sending to complete.
        if (socketPair != null) {
            socketPair.close();
        }
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (receivingFuture != null) {
            receivingFuture.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Parameters(method = "getParams")
    public void throughput(Config config) throws Exception {
        try {
            setup(config);
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                recording.set(true);
                while (bytesCounter.get() < config.messageSize()) {
                }
                bytesCounter.set(0);
                recording.set(false);
            }
        } finally {
            close();
        }
    }

    @After
    public void tearDown() throws Exception {
        close();
    }

    private String[] ciphers(Config config) {
        return new String[] {config.cipher()};
    }
}