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

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import androidx.test.filters.LargeTest;

import org.conscrypt.TestUtils;

import java.nio.ByteBuffer;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmark for comparing cipher encrypt performance.
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class CipherEncryptPerfTest {

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public enum BufferType {
        ARRAY,
        HEAP_HEAP,
        HEAP_DIRECT,
        DIRECT_DIRECT,
        DIRECT_HEAP
    }

    private enum MyCipherFactory implements CipherFactory {
        JDK {
            @Override
            public Cipher newCipher(String transformation)
                throws NoSuchPaddingException, NoSuchAlgorithmException {
                return Cipher.getInstance(transformation);
            }
        },
        CONSCRYPT {
            @Override
            public Cipher newCipher(String transformation)
                throws NoSuchPaddingException, NoSuchAlgorithmException {
                return Cipher.getInstance(transformation, TestUtils.getConscryptProvider());
            }
        };
    }

    private class Config {
        BufferType b_bufferType;
        CipherFactory c_provider;
        Transformation a_tx;
        Config(BufferType bufferType, CipherFactory cipherFactory, Transformation transformation) {
          b_bufferType = bufferType;
          c_provider = cipherFactory;
          a_tx = transformation;
        }
        public BufferType bufferType() {
            return b_bufferType;
        }

        public CipherFactory cipherFactory() {
            return c_provider;
        }

        public Transformation transformation() {
            return a_tx;
        }
    }

    private Object[] getParams() {
        return new Object[][] {
            new Object[] {new Config(BufferType.ARRAY,
                              MyCipherFactory.CONSCRYPT,
                              Transformation.AES_CBC_PKCS5)},
            new Object[] {new Config(BufferType.ARRAY,
                              MyCipherFactory.CONSCRYPT,
                              Transformation.AES_ECB_PKCS5)},
            new Object[] {new Config(BufferType.ARRAY,
                              MyCipherFactory.CONSCRYPT,
                              Transformation.AES_GCM_NO)},
            new Object[] {new Config(BufferType.ARRAY,
                              MyCipherFactory.CONSCRYPT,
                              Transformation.AES_GCM_SIV)},
        };
    }

    private EncryptStrategy encryptStrategy;

    @Test
    @Parameters(method = "getParams")
    public void encrypt(Config config) throws Exception {
        switch (config.bufferType()) {
            case ARRAY:
                encryptStrategy = new ArrayStrategy(config);
                break;
            default:
                encryptStrategy = new ByteBufferStrategy(config);
                break;
        }
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
          encryptStrategy.encrypt();
        }
    }

    private static abstract class EncryptStrategy {
        private final Key key;
        final Cipher cipher;
        final int outputSize;

        EncryptStrategy(Config config) throws Exception {
            Transformation tx = config.transformation();
            key = tx.newEncryptKey();
            cipher = config.cipherFactory().newCipher(tx.toFormattedString());
            initCipher();

            int messageSize = messageSize(tx.toFormattedString());
            outputSize = cipher.getOutputSize(messageSize);
        }

        final void initCipher() throws Exception {
            cipher.init(Cipher.ENCRYPT_MODE, key);
        }

        final int messageSize(String transformation) throws Exception {
            Cipher conscryptCipher = Cipher.getInstance(
                transformation, TestUtils.getConscryptProvider());
            conscryptCipher.init(Cipher.ENCRYPT_MODE, key);
            return conscryptCipher.getBlockSize() > 0 ?
                conscryptCipher.getBlockSize() : 128;
        }

        final byte[] newMessage() {
            return TestUtils.newTextMessage(cipher.getBlockSize());
        }

        abstract int encrypt() throws Exception;
    }

    private static final class ArrayStrategy extends EncryptStrategy {
        private final byte[] plainBytes;
        private final byte[] cipherBytes;

        ArrayStrategy(Config config) throws Exception {
            super(config);

            plainBytes = newMessage();
            cipherBytes = new byte[outputSize];
        }

        @Override
        int encrypt() throws Exception {
            initCipher();
            return cipher.doFinal(plainBytes, 0, plainBytes.length, cipherBytes, 0);
        }
    }

    private static final class ByteBufferStrategy extends EncryptStrategy {
        private final ByteBuffer input;
        private final ByteBuffer output;

        ByteBufferStrategy(Config config) throws Exception {
            super(config);

            switch (config.bufferType()) {
                case HEAP_HEAP:
                    input = ByteBuffer.wrap(newMessage());
                    output = ByteBuffer.allocate(outputSize);
                    break;
                case HEAP_DIRECT:
                    input = ByteBuffer.wrap(newMessage());
                    output = ByteBuffer.allocateDirect(outputSize);
                    break;
                case DIRECT_DIRECT:
                    input = toDirect(newMessage());
                    output = ByteBuffer.allocateDirect(outputSize);
                    break;
                case DIRECT_HEAP:
                    input = toDirect(newMessage());
                    output = ByteBuffer.allocate(outputSize);
                    break;
                default: {
                    throw new IllegalStateException(
                            "Unexpected buffertype: " + config.bufferType());
                }
            }
        }

        @Override
        int encrypt() throws Exception {
            initCipher();
            input.position(0);
            output.clear();
            return cipher.doFinal(input, output);
        }

        private static ByteBuffer toDirect(byte[] data) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
            buffer.put(data);
            buffer.flip();
            return buffer;
        }
    }
}