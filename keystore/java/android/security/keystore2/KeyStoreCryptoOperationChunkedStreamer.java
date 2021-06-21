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

package android.security.keystore2;

import android.annotation.NonNull;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;

import libcore.util.EmptyArray;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStoreOperation}
 * service's {@code update} and {@code finish} operations.
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
         * Returns the result of the KeyStoreOperation {@code update} if applicable.
         * The return value may be null, e.g., when supplying AAD or to-be-signed data.
         *
         * @param input Data to update a KeyStoreOperation with.
         *
         * @throws KeyStoreException in case of error.
         */
        byte[] update(@NonNull byte[] input) throws KeyStoreException;

        /**
         * Returns the result of the KeyStore {@code finish} if applicable.
         *
         * @param input Optional data to update the operation with one last time.
         *
         * @param signature Optional HMAC signature when verifying an HMAC signature, must be
         *                  null otherwise.
         *
         * @return Optional output data. Depending on the operation this may be a signature,
         *                  some final bit of cipher, or plain text.
         *
         * @throws KeyStoreException in case of error.
         */
        byte[] finish(byte[] input, byte[] signature) throws KeyStoreException;
    }

    // Binder buffer is about 1MB, but it's shared between all active transactions of the process.
    // Thus, it's safer to use a much smaller upper bound.
    private static final int DEFAULT_CHUNK_SIZE_MAX = 32 * 1024;
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
        mChunkLength = 0;
        mConsumedInputSizeBytes = 0;
        mProducedOutputSizeBytes = 0;
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

        // Preamble: If there is leftover data, we fill it up with the new data provided
        // and send it to Keystore.
        if (mChunkLength > 0) {
            // Fill current chunk and send it to Keystore
            int inputConsumed = ArrayUtils.copy(input, inputOffset, mChunk, mChunkLength,
                    inputLength);
            inputLength -= inputConsumed;
            inputOffset += inputConsumed;
            mChunkLength += inputConsumed;
            if (mChunkLength < mChunkSizeMax) return output;
            byte[] o = mKeyStoreStream.update(mChunk);
            if (o != null) {
                output = ArrayUtils.concat(output, o);
            }
            mConsumedInputSizeBytes += inputConsumed;
            mChunkLength = 0;
        }

        // Main loop: Send large enough chunks to Keystore.
        while (inputLength >= mChunkSizeThreshold) {
            int nextChunkSize = inputLength < mChunkSizeMax ? inputLength : mChunkSizeMax;
            byte[] o = mKeyStoreStream.update(ArrayUtils.subarray(input, inputOffset,
                    nextChunkSize));
            inputLength -= nextChunkSize;
            inputOffset += nextChunkSize;
            mConsumedInputSizeBytes += nextChunkSize;
            if (o != null) {
                output = ArrayUtils.concat(output, o);
            }
        }

        // If we have left over data, that did not make the threshold, we store it in the chunk
        // store.
        if (inputLength > 0) {
            mChunkLength = ArrayUtils.copy(input, inputOffset, mChunk, 0, inputLength);
            mConsumedInputSizeBytes += inputLength;
        }

        mProducedOutputSizeBytes += output.length;
        return output;
    }

    public byte[] doFinal(byte[] input, int inputOffset, int inputLength,
            byte[] signature) throws KeyStoreException {
        byte[] output = update(input, inputOffset, inputLength);
        byte[] finalChunk = ArrayUtils.subarray(mChunk, 0, mChunkLength);
        byte[] o = mKeyStoreStream.finish(finalChunk, signature);

        if (o != null) {
            // Output produced by update is already accounted for. We only add the bytes
            // produced by finish.
            mProducedOutputSizeBytes += o.length;
            if (output != null) {
                output = ArrayUtils.concat(output, o);
            } else {
                output = o;
            }
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

        private final KeyStoreOperation mOperation;

        MainDataStream(KeyStoreOperation operation) {
            mOperation = operation;
        }

        @Override
        public byte[] update(byte[] input) throws KeyStoreException {
            return mOperation.update(input);
        }

        @Override
        public byte[] finish(byte[] input, byte[] signature)
                throws KeyStoreException {
            return mOperation.finish(input, signature);
        }
    }
}
