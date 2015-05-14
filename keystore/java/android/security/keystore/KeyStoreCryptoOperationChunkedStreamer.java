/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore;

import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.OperationResult;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStore} service's
 * {@code update} and {@code finish} operations.
 *
 * <p>The helper abstracts away to issues that need to be solved in most code that uses KeyStore's
 * update and finish operations. Firstly, KeyStore's update operation can consume only a limited
 * amount of data in one go because the operations are marshalled via Binder. Secondly, the update
 * operation may consume less data than provided, in which case the caller has to buffer the
 * remainder for next time. The helper exposes {@link #update(byte[], int, int) update} and
 * {@link #doFinal(byte[], int, int) doFinal} operations which can be used to conveniently implement
 * various JCA crypto primitives.
 *
 * <p>Bidirectional chunked streaming of data via a KeyStore crypto operation is abstracted away as
 * a {@link Stream} to avoid having this class deal with operation tokens and occasional additional
 * parameters to {@code update} and {@code final} operations.
 *
 * @hide
 */
public class KeyStoreCryptoOperationChunkedStreamer {

    /**
     * Bidirectional chunked data stream over a KeyStore crypto operation.
     */
    public interface Stream {
        /**
         * Returns the result of the KeyStore {@code update} operation or null if keystore couldn't
         * be reached.
         */
        OperationResult update(byte[] input);

        /**
         * Returns the result of the KeyStore {@code finish} operation or null if keystore couldn't
         * be reached.
         */
        OperationResult finish();
    }

    // Binder buffer is about 1MB, but it's shared between all active transactions of the process.
    // Thus, it's safer to use a much smaller upper bound.
    private static final int DEFAULT_MAX_CHUNK_SIZE = 64 * 1024;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final Stream mKeyStoreStream;
    private final int mMaxChunkSize;

    private byte[] mBuffered = EMPTY_BYTE_ARRAY;
    private int mBufferedOffset;
    private int mBufferedLength;

    public KeyStoreCryptoOperationChunkedStreamer(Stream operation) {
        this(operation, DEFAULT_MAX_CHUNK_SIZE);
    }

    public KeyStoreCryptoOperationChunkedStreamer(Stream operation, int maxChunkSize) {
        mKeyStoreStream = operation;
        mMaxChunkSize = maxChunkSize;
    }

    public byte[] update(byte[] input, int inputOffset, int inputLength) throws KeyStoreException {
        if (inputLength == 0) {
            // No input provided
            return EMPTY_BYTE_ARRAY;
        }

        ByteArrayOutputStream bufferedOutput = null;

        while (inputLength > 0) {
            byte[] chunk;
            int inputBytesInChunk;
            if ((mBufferedLength + inputLength) > mMaxChunkSize) {
                // Too much input for one chunk -- extract one max-sized chunk and feed it into the
                // update operation.
                inputBytesInChunk = mMaxChunkSize - mBufferedLength;
                chunk = ArrayUtils.concat(mBuffered, mBufferedOffset, mBufferedLength,
                        input, inputOffset, inputBytesInChunk);
            } else {
                // All of available input fits into one chunk.
                if ((mBufferedLength == 0) && (inputOffset == 0)
                        && (inputLength == input.length)) {
                    // Nothing buffered and all of input array needs to be fed into the update
                    // operation.
                    chunk = input;
                    inputBytesInChunk = input.length;
                } else {
                    // Need to combine buffered data with input data into one array.
                    inputBytesInChunk = inputLength;
                    chunk = ArrayUtils.concat(mBuffered, mBufferedOffset, mBufferedLength,
                            input, inputOffset, inputBytesInChunk);
                }
            }
            // Update input array references to reflect that some of its bytes are now in mBuffered.
            inputOffset += inputBytesInChunk;
            inputLength -= inputBytesInChunk;

            OperationResult opResult = mKeyStoreStream.update(chunk);
            if (opResult == null) {
                throw new KeyStoreConnectException();
            } else if (opResult.resultCode != KeyStore.NO_ERROR) {
                throw KeyStore.getKeyStoreException(opResult.resultCode);
            }

            if (opResult.inputConsumed == chunk.length) {
                // The whole chunk was consumed
                mBuffered = EMPTY_BYTE_ARRAY;
                mBufferedOffset = 0;
                mBufferedLength = 0;
            } else if (opResult.inputConsumed == 0) {
                // Nothing was consumed. More input needed.
                if (inputLength > 0) {
                    // More input is available, but it wasn't included into the previous chunk
                    // because the chunk reached its maximum permitted size.
                    // Shouldn't have happened.
                    throw new IllegalStateException("Nothing consumed from max-sized chunk: "
                            + chunk.length + " bytes");
                }
                mBuffered = chunk;
                mBufferedOffset = 0;
                mBufferedLength = chunk.length;
            } else if (opResult.inputConsumed < chunk.length) {
                // The chunk was consumed only partially -- buffer the rest of the chunk
                mBuffered = chunk;
                mBufferedOffset = opResult.inputConsumed;
                mBufferedLength = chunk.length - opResult.inputConsumed;
            } else {
                throw new IllegalStateException("Consumed more than provided: "
                        + opResult.inputConsumed + ", provided: " + chunk.length);
            }

            if ((opResult.output != null) && (opResult.output.length > 0)) {
                if (inputLength > 0) {
                    // More output might be produced in this loop -- buffer the current output
                    if (bufferedOutput == null) {
                        bufferedOutput = new ByteArrayOutputStream();
                        try {
                            bufferedOutput.write(opResult.output);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to buffer output", e);
                        }
                    }
                } else {
                    // No more output will be produced in this loop
                    if (bufferedOutput == null) {
                        // No previously buffered output
                        return opResult.output;
                    } else {
                        // There was some previously buffered output
                        try {
                            bufferedOutput.write(opResult.output);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to buffer output", e);
                        }
                        return bufferedOutput.toByteArray();
                    }
                }
            }
        }

        if (bufferedOutput == null) {
            // No output produced
            return EMPTY_BYTE_ARRAY;
        } else {
            return bufferedOutput.toByteArray();
        }
    }

    public byte[] doFinal(byte[] input, int inputOffset, int inputLength)
            throws KeyStoreException {
        if (inputLength == 0) {
            // No input provided -- simplify the rest of the code
            input = EMPTY_BYTE_ARRAY;
            inputOffset = 0;
        }

        // Flush all buffered input and provided input into keystore/keymaster.
        byte[] output = update(input, inputOffset, inputLength);
        output = ArrayUtils.concat(output, flush());

        OperationResult opResult = mKeyStoreStream.finish();
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeyStore.getKeyStoreException(opResult.resultCode);
        }

        return ArrayUtils.concat(output, opResult.output);
    }

    /**
     * Passes all of buffered input into the the KeyStore operation (via the {@code update}
     * operation) and returns output.
     */
    public byte[] flush() throws KeyStoreException {
        if (mBufferedLength <= 0) {
            return EmptyArray.BYTE;
        }

        byte[] chunk = ArrayUtils.subarray(mBuffered, mBufferedOffset, mBufferedLength);
        mBuffered = EmptyArray.BYTE;
        mBufferedLength = 0;
        mBufferedOffset = 0;

        OperationResult opResult = mKeyStoreStream.update(chunk);
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeyStore.getKeyStoreException(opResult.resultCode);
        }

        if (opResult.inputConsumed < chunk.length) {
            throw new IllegalStateException("Keystore failed to consume all input. Provided: "
                    + chunk.length + ", consumed: " + opResult.inputConsumed);
        } else if (opResult.inputConsumed > chunk.length) {
            throw new IllegalStateException("Keystore consumed more input than provided"
                    + " . Provided: " + chunk.length + ", consumed: " + opResult.inputConsumed);
        }

        return (opResult.output != null) ? opResult.output : EmptyArray.BYTE;
    }

    /**
     * Main data stream via a KeyStore streaming operation.
     *
     * <p>For example, for an encryption operation, this is the stream through which plaintext is
     * provided and ciphertext is obtained.
     */
    public static class MainDataStream implements Stream {

        private final KeyStore mKeyStore;
        private final IBinder mOperationToken;

        public MainDataStream(KeyStore keyStore, IBinder operationToken) {
            mKeyStore = keyStore;
            mOperationToken = operationToken;
        }

        @Override
        public OperationResult update(byte[] input) {
            return mKeyStore.update(mOperationToken, null, input);
        }

        @Override
        public OperationResult finish() {
            return mKeyStore.finish(mOperationToken, null, null);
        }
    }
}
