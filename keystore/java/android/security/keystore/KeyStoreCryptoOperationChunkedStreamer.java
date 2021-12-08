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
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;

import libcore.util.EmptyArray;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStore} service's
 * {@code update} and {@code finish} operations.
 *
 * <p>The helper abstracts away issues that need to be solved in most code that uses KeyStore's
 * update and finish operations. Firstly, KeyStore's update operation can consume only a limited
 * amount of data in one go because the operations are marshalled via Binder. Secondly, the update
 * operation may consume less data than provided, in which case the caller has to buffer the
 * remainder for next time. Thirdly, when the input is smaller than a threshold, skipping update
 * and passing input data directly to final improves performance. This threshold is configurable;
 * using a threshold <= 1 causes the helper act eagerly, which may be required for some types of
 * operations (e.g. ciphers).
 *
 * <p>The helper exposes {@link #update(byte[], int, int) update} and
 * {@link #doFinal(byte[], int, int, byte[], byte[]) doFinal} operations which can be used to
 * conveniently implement various JCA crypto primitives.
 *
 * <p>Bidirectional chunked streaming of data via a KeyStore crypto operation is abstracted away as
 * a {@link Stream} to avoid having this class deal with operation tokens and occasional additional
 * parameters to {@code update} and {@code final} operations.
 *
 * @hide
 */
class KeyStoreCryptoOperationChunkedStreamer implements KeyStoreCryptoOperationStreamer {

    /**
     * Bidirectional chunked data stream over a KeyStore crypto operation.
     */
    interface Stream {
        /**
         * Returns the result of the KeyStore {@code update} operation or null if keystore couldn't
         * be reached.
         */
        OperationResult update(byte[] input);

        /**
         * Returns the result of the KeyStore {@code finish} operation or null if keystore couldn't
         * be reached.
         */
        OperationResult finish(byte[] input, byte[] siganture, byte[] additionalEntropy);
    }

    // Binder buffer is about 1MB, but it's shared between all active transactions of the process.
    // Thus, it's safer to use a much smaller upper bound.
    private static final int DEFAULT_CHUNK_SIZE_MAX = 64 * 1024;
    // The chunk buffer will be sent to update until its size under this threshold.
    // This threshold should be <= the max input allowed for finish.
    // Setting this threshold <= 1 will effectivley disable buffering between updates.
    private static final int DEFAULT_CHUNK_SIZE_THRESHOLD = 2 * 1024;

    private final Stream mKeyStoreStream;
    private final int mChunkSizeMax;
    private final int mChunkSizeThreshold;
    private final byte[] mChunk;
    private int mChunkLength = 0;
    private long mConsumedInputSizeBytes;
    private long mProducedOutputSizeBytes;

    KeyStoreCryptoOperationChunkedStreamer(Stream operation) {
        this(operation, DEFAULT_CHUNK_SIZE_THRESHOLD, DEFAULT_CHUNK_SIZE_MAX);
    }

    KeyStoreCryptoOperationChunkedStreamer(Stream operation, int chunkSizeThreshold) {
        this(operation, chunkSizeThreshold, DEFAULT_CHUNK_SIZE_MAX);
    }

    KeyStoreCryptoOperationChunkedStreamer(Stream operation, int chunkSizeThreshold,
            int chunkSizeMax) {
        mKeyStoreStream = operation;
        mChunkSizeMax = chunkSizeMax;
        if (chunkSizeThreshold <= 0) {
            mChunkSizeThreshold = 1;
        } else if (chunkSizeThreshold > chunkSizeMax) {
            mChunkSizeThreshold = chunkSizeMax;
        } else {
            mChunkSizeThreshold = chunkSizeThreshold;
        }
        mChunk = new byte[mChunkSizeMax];
    }

    public byte[] update(byte[] input, int inputOffset, int inputLength) throws KeyStoreException {
        if (inputLength == 0 || input == null) {
            // No input provided
            return EmptyArray.BYTE;
        }
        if (inputLength < 0 || inputOffset < 0 || (inputOffset + inputLength) > input.length) {
            throw new KeyStoreException(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                "Input offset and length out of bounds of input array");
        }

        byte[] output = EmptyArray.BYTE;

        while (inputLength > 0 || mChunkLength >= mChunkSizeThreshold) {
            int inputConsumed = ArrayUtils.copy(input, inputOffset, mChunk, mChunkLength,
                    inputLength);
            inputLength -= inputConsumed;
            inputOffset += inputConsumed;
            mChunkLength += inputConsumed;
            mConsumedInputSizeBytes += inputConsumed;

            if (mChunkLength > mChunkSizeMax) {
                throw new KeyStoreException(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH,
                        "Chunk size exceeded max chunk size. Max: " + mChunkSizeMax
                                + " Actual: " + mChunkLength);
            }

            if (mChunkLength >= mChunkSizeThreshold) {
                OperationResult opResult = mKeyStoreStream.update(
                        ArrayUtils.subarray(mChunk, 0, mChunkLength));

                if (opResult == null) {
                    throw new KeyStoreConnectException();
                } else if (opResult.resultCode != KeyStore.NO_ERROR) {
                    throw KeyStore.getKeyStoreException(opResult.resultCode);
                }
                if (opResult.inputConsumed == 0) {
                    // Some KM implementations do not consume data in certain block modes unless a
                    // full block of data was presented.
                    if (inputLength > 0) {
                        // More input is available, but it wasn't included into the previous chunk
                        // because the chunk reached its maximum permitted size.
                        // Shouldn't have happened.
                        throw new KeyStoreException(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH,
                                "Keystore consumed nothing from max-sized chunk: " + mChunkLength
                                        + " bytes");
                    }
                } else if (opResult.inputConsumed > mChunkLength || opResult.inputConsumed < 0) {
                    throw new KeyStoreException(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                            "Keystore consumed more input than provided (or inputConsumed was "
                                    + "negative."
                                    + " Provided: " + mChunkLength
                                    + ", consumed: " + opResult.inputConsumed);
                }
                mChunkLength -= opResult.inputConsumed;

                if (mChunkLength > 0) {
                    // Partially consumed, shift chunk contents
                    ArrayUtils.copy(mChunk, opResult.inputConsumed, mChunk, 0, mChunkLength);
                }

                if ((opResult.output != null) && (opResult.output.length > 0)) {
                    // Output was produced
                    mProducedOutputSizeBytes += opResult.output.length;
                    output = ArrayUtils.concat(output, opResult.output);
                }
            }
        }
        return output;
    }

    public byte[] doFinal(byte[] input, int inputOffset, int inputLength,
            byte[] signature, byte[] additionalEntropy) throws KeyStoreException {
        byte[] output = update(input, inputOffset, inputLength);
        byte[] finalChunk = ArrayUtils.subarray(mChunk, 0, mChunkLength);
        OperationResult opResult = mKeyStoreStream.finish(finalChunk, signature, additionalEntropy);

        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeyStore.getKeyStoreException(opResult.resultCode);
        }
        // If no error, assume all input consumed
        mConsumedInputSizeBytes += finalChunk.length;

        if ((opResult.output != null) && (opResult.output.length > 0)) {
            mProducedOutputSizeBytes += opResult.output.length;
            output = ArrayUtils.concat(output, opResult.output);
        }

        return output;
    }

    @Override
    public long getConsumedInputSizeBytes() {
        return mConsumedInputSizeBytes;
    }

    @Override
    public long getProducedOutputSizeBytes() {
        return mProducedOutputSizeBytes;
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
        public OperationResult finish(byte[] input, byte[] signature, byte[] additionalEntropy) {
            return mKeyStore.finish(mOperationToken, null, input, signature, additionalEntropy);
        }
    }
}
