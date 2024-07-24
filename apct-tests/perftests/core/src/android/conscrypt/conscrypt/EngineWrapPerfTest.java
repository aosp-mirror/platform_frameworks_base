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

/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.conscrypt;

import static org.conscrypt.TestUtils.doEngineHandshake;
import static org.conscrypt.TestUtils.newTextMessage;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmark comparing performance of various engine implementations to conscrypt.
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class EngineWrapPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Provider for the benchmark configuration
     */
    private class Config {
        BufferType a_bufferType;
        int c_messageSize;
        String d_cipher;
        Config(BufferType bufferType,
            int messageSize,
            String cipher) {
          a_bufferType = bufferType;
          c_messageSize = messageSize;
          d_cipher = cipher;
        }
        public BufferType bufferType() {
            return a_bufferType;
        }

        public int messageSize() {
            return c_messageSize;
        }

        public String cipher() {
            return d_cipher;
        }
    }

    public Collection getParams() {
        final List<Object[]> params = new ArrayList<>();
        for (BufferType bufferType : BufferType.values()) {
            params.add(new Object[] {new Config(bufferType, 64,
                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")});
            params.add(new Object[] {new Config(bufferType, 512,
                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")});
            params.add(new Object[] {new Config(bufferType, 4096,
                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")});
        }
        return params;
    }


    private EngineFactory engineFactory = new EngineFactory();
    private String cipher;
    private SSLEngine clientEngine;
    private SSLEngine serverEngine;

    private ByteBuffer messageBuffer;
    private ByteBuffer clientApplicationBuffer;
    private ByteBuffer clientPacketBuffer;
    private ByteBuffer serverApplicationBuffer;
    private ByteBuffer serverPacketBuffer;
    private ByteBuffer preEncryptedBuffer;

    private void setup(Config config) throws Exception {
        cipher = config.cipher();
        BufferType bufferType = config.bufferType();

        clientEngine = engineFactory.newClientEngine(cipher);
        serverEngine = engineFactory.newServerEngine(cipher);

        // Create the application and packet buffers for both endpoints.
        clientApplicationBuffer = bufferType.newApplicationBuffer(clientEngine);
        serverApplicationBuffer = bufferType.newApplicationBuffer(serverEngine);
        clientPacketBuffer = bufferType.newPacketBuffer(clientEngine);
        serverPacketBuffer = bufferType.newPacketBuffer(serverEngine);

        // Generate the message to be sent from the client.
        int messageSize = config.messageSize();
        messageBuffer = bufferType.newBuffer(messageSize);
        messageBuffer.put(newTextMessage(messageSize));
        messageBuffer.flip();

        // Complete the initial TLS handshake.
        doEngineHandshake(clientEngine, serverEngine, clientApplicationBuffer, clientPacketBuffer,
                serverApplicationBuffer, serverPacketBuffer, true);

        // Populate the pre-encrypted buffer for use with the unwrap benchmark.
        preEncryptedBuffer = bufferType.newBuffer(clientEngine.getSession().getPacketBufferSize());
        doWrap(messageBuffer, preEncryptedBuffer);
        doUnwrap(preEncryptedBuffer, serverApplicationBuffer);
    }

    void teardown() {
        engineFactory.dispose(clientEngine);
        engineFactory.dispose(serverEngine);
    }

    @Test
    @Parameters(method = "getParams")
    public void wrap(Config config) throws Exception {
        setup(config);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // Reset the buffers.
            messageBuffer.position(0);
            clientPacketBuffer.clear();
            // Wrap the original message and create the encrypted data.
            doWrap(messageBuffer, clientPacketBuffer);

            // Lightweight comparison - just make sure the data length is correct.
            assertEquals(preEncryptedBuffer.limit(), clientPacketBuffer.limit());
        }
        teardown();
    }

    /**
     * Simple benchmark that sends a single message from client to server.
     */
    @Test
    @Parameters(method = "getParams")
    public void wrapAndUnwrap(Config config) throws Exception {
        setup(config);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // Reset the buffers.
            messageBuffer.position(0);
            clientPacketBuffer.clear();
            serverApplicationBuffer.clear();
            // Wrap the original message and create the encrypted data.
            doWrap(messageBuffer, clientPacketBuffer);

            // Unwrap the encrypted data and get back the original result.
            doUnwrap(clientPacketBuffer, serverApplicationBuffer);

            // Lightweight comparison - just make sure the unencrypted data length is correct.
            assertEquals(messageBuffer.limit(), serverApplicationBuffer.limit());
        }
        teardown();
    }

    private void doWrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
        // Wrap the original message and create the encrypted data.
        verifyResult(src, clientEngine.wrap(src, dst));
        dst.flip();
    }

    private void doUnwrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
        verifyResult(src, serverEngine.unwrap(src, dst));
        dst.flip();
    }

    private void verifyResult(ByteBuffer src, SSLEngineResult result) {
        if (result.getStatus() != SSLEngineResult.Status.OK) {
            throw new RuntimeException("Operation returned unexpected result " + result);
        }
        if (result.bytesConsumed() != src.limit()) {
            throw new RuntimeException(
                    String.format(Locale.US,
                            "Operation didn't consume all bytes. Expected %d, consumed %d.",
                            src.limit(), result.bytesConsumed()));
        }
    }
}