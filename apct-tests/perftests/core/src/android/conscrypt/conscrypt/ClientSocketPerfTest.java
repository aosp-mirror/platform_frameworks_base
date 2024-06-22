/*
 * Copyright (C) 2016 The Android Open Source Project
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
import org.conscrypt.TestUtils;
import static org.conscrypt.TestUtils.getCommonProtocolSuites;
import static org.conscrypt.TestUtils.newTextMessage;
import static org.junit.Assert.assertEquals;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import androidx.test.filters.LargeTest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import android.conscrypt.ServerEndpoint.MessageProcessor;

/**
 * Benchmark for comparing performance of server socket implementations.
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class ClientSocketPerfTest {

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Provider for the test configuration
     */
    private class Config {
        EndpointFactory a_clientFactory;
        EndpointFactory b_serverFactory;
        int c_messageSize;
        String d_cipher;
        ChannelType e_channelType;
        PerfTestProtocol f_protocol;
        Config(EndpointFactory clientFactory,
            EndpointFactory serverFactory,
            int messageSize,
            String cipher,
            ChannelType channelType,
            PerfTestProtocol protocol) {
          a_clientFactory = clientFactory;
          b_serverFactory = serverFactory;
          c_messageSize = messageSize;
          d_cipher = cipher;
          e_channelType = channelType;
          f_protocol = protocol;
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

        public PerfTestProtocol protocol() {
            return f_protocol;
        }
    }

    private Object[] getParams() {
        return new Object[][] {
            new Object[] {new Config(
                              EndpointFactory.CONSCRYPT,
                              EndpointFactory.CONSCRYPT,
                              64,
                              "AES128-GCM",
                              ChannelType.CHANNEL,
                              PerfTestProtocol.TLSv13)},
        };
    }


    private ClientEndpoint client;
    private ServerEndpoint server;
    private byte[] message;
    private ExecutorService executor;
    private Future<?> sendingFuture;
    private volatile boolean stopping;

    private static final AtomicLong bytesCounter = new AtomicLong();
    private AtomicBoolean recording = new AtomicBoolean();

    private void setup(Config config) throws Exception {
        message = newTextMessage(512);

        // Always use the same server for consistency across the benchmarks.
        server = config.serverFactory().newServer(
                ChannelType.CHANNEL, config.messageSize(), config.protocol().getProtocols(),
                ciphers(config));

        server.setMessageProcessor(new ServerEndpoint.MessageProcessor() {
            @Override
            public void processMessage(byte[] inMessage, int numBytes, OutputStream os) {
                if (recording.get()) {
                    // Server received a message, increment the count.
                    bytesCounter.addAndGet(numBytes);
                }
            }
        });
        Future<?> connectedFuture = server.start();

        client = config.clientFactory().newClient(
            config.channelType(), server.port(), config.protocol().getProtocols(), ciphers(config));
        client.start();

        // Wait for the initial connection to complete.
        connectedFuture.get(5, TimeUnit.SECONDS);

        executor = Executors.newSingleThreadExecutor();
        sendingFuture = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread thread = Thread.currentThread();
                    while (!stopping && !thread.isInterrupted()) {
                        client.sendMessage(message);
                    }
                } finally {
                    client.flush();
                }
            }
        });
    }

    void close() throws Exception {
        stopping = true;

        // Wait for the sending thread to stop.
        sendingFuture.get(5, TimeUnit.SECONDS);

        client.stop();
        server.stop();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Simple benchmark for the amount of time to send a given number of messages
     */
    @Test
    @Parameters(method = "getParams")
    public void time(Config config) throws Exception {
        reset();
        setup(config);
        recording.set(true);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
          while (bytesCounter.get() < config.messageSize()) {
          }
          bytesCounter.set(0);
        }
        recording.set(false);
        close();
    }

    void reset() {
        stopping = false;
        bytesCounter.set(0);
    }

    private String[] ciphers(Config config) {
        return new String[] {config.cipher()};
    }
}