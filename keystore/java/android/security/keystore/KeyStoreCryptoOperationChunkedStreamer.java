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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.ProviderException;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStore} service's
 * {@code update} and {@code finish} operations.
 *
 * <p>The helper abstracts away to issues that need to be solved in most code that uses KeyStore's
 * update and finish operations. Firstly, KeyStore's update operation can consume only a limited
 * amount of data in one go because the operations are marshalled via Binder. Secondly, the update
 * operation may consume less data than provided, in which case the caller has to buffer the
 * remainder for next time. The helper exposes {@link #update(byte[], int, int) update} and
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
        OperationResult finish(byte[] siganture, byte[] additionalEntropy);
    }

    // Binder buffer is about 1MB, but it's shared between all active transactions of the process.
    // Thus, it's safer to use a much smaller upper bound.
    private static final int DEFAULT_MAX_CHUNK_SIZE = 64 * 1024;

    private final Stream mKeyStoreStream;
    private final int mMaxChunkSize;

    private byte[] mBuffered = EmptyArray.BYTE;
    private int mBufferedOffset;
    private int mBufferedLength;
    private long mConsumedInputSizeBytes;
    private long mProducedOutputSizeBytes;

    public KeyStoreCryptoOperationChunkedStreamer(Stream operation) {
        this(operation, DEFAULT_MAX_CHUNK_SIZE);
    }

    public KeyStoreCryptoOperationChunkedStreamer(Stream operation, int maxChunkSize) {
        mKeyStoreStream = operation;
        mMaxChunkSize = maxChunkSize;
    }

    @Override
    public byte[] update(byte[] input, int inputOffset, int inputLength) throws KeyStoreException {
        if (inputLength == 0) {
            // No input provided
            return EmptyArray.BYTE;
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
            mConsumedInputSizeBytes += inputBytesInChunk;

            OperationResult opResult = mKeyStoreStream.update(chunk);
            if (opResult == null) {
                throw new KeyStoreConnectException();
            } else if (opResult.resultCode != KeyStore.NO_ERROR) {
                throw KeyStore.getKeyStoreException(opResult.resultCode);
            }

            if (opResult.inputConsumed == chunk.length) {
                // The whole chunk was consumed
                mBuffered = EmptyArray.BYTE;
                mBufferedOffset = 0;
                mBufferedLength = 0;
            } else if (opResult.inputConsumed <= 0) {
                // Nothing was consumed. More input needed.
                if (inputLength > 0) {
                    // More input is available, but it wasn't included into the previous chunk
                    // because the chunk reached its maximum permitted size.
                    // Shouldn't have happened.
                    throw new KeyStoreException(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                            "Keystore consumed nothing from max-sized chunk: " + chunk.length
                                    + " bytes");
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
                throw new KeyStoreException(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                        "Keystore consumed more input than provided. Provided: " + chunk.length
                                + ", consumed: " + opResult.inputConsumed);
            }

            if ((opResult.output != null) && (opResult.output.length > 0)) {
                if (inputLength > 0) {
                    // More output might be produced in this loop -- buffer the current output
                    if (bufferedOutput == null) {
                        bufferedOutput = new ByteArrayOutputStream();
                        try {
                            bufferedOutput.write(opResult.output);
                        } catch (IOException e) {
                            throw new ProviderException("Failed to buffer output", e);
                        }
                    }
                } else {
                    // No more output will be produced in this loop
                    byte[] result;
                    if (bufferedOutput == null) {
                        // No previously buffered output
                        result = opResult.output;
                    } else {
                        // There was some previously buffered output
                        try {
                            bufferedOutput.write(opResult.output);
                        } catch (IOException e) {
                            throw new ProviderException("Failed to buffer output", e);
                        }
                        result = bufferedOutput.toByteArray();
                    }
                    mProducedOutputSizeBytes += result.length;
                    return result;
                }
            }
        }

        byte[] result;
        if (bufferedOutput == null) {
            // No output produced
            result = EmptyArray.BYTE;
        } else {
            result = bufferedOutput.toByteArray();
        }
        mProducedOutputSizeBytes += result.length;
        return result;
    }

    @Override
    public byte[] doFinal(byte[] input, int inputOffset, int inputLength,
            byte[] signature, byte[] additionalEntropy) throws KeyStoreException {
        if (inputLength == 0) {
            // No input provided -- simplify the rest of the code
            input = EmptyArray.BYTE;
            inputOffset = 0;
        }

        // Flush all buffered input and provided input into keystore/keymaster.
        byte[] output = update(input, inputOffset, inputLength);
        output = ArrayUtils.concat(output, flush());

        OperationResult opResult = mKeyStoreStream.finish(signature, additionalEntropy);
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeyStore.getKeyStoreException(opResult.resultCode);
        }
        mProducedOutputSizeBytes += opResult.output.length;

        return ArrayUtils.concat(output, opResult.output);
    }

    public byte[] flush() throws KeyStoreException {
        if (mBufferedLength <= 0) {
            return EmptyArray.BYTE;
        }

        // Keep invoking the update operation with remaining buffered data until either all of the
        // buffered data is consumed or until update fails to consume anything.
        ByteArrayOutputStream bufferedOutput = null;
        while (mBufferedLength > 0) {
            byte[] chunk = ArrayUtils.subarray(mBuffered, mBufferedOffset, mBufferedLength);
            OperationResult opResult = mKeyStoreStream.update(chunk);
            if (opResult == null) {
                throw new KeyStoreConnectException();
            } else if (opResult.resultCode != KeyStore.NO_ERROR) {
                throw KeyStore.getKeyStoreException(opResult.resultCode);
            }

            if (opResult.inputConsumed <= 0) {
                // Nothing was consumed. Break out of the loop to avoid an infinite loop.
                break;
            }

            if (opResult.inputConsumed >= chunk.length) {
                // All of the input was consumed
                mBuffered = EmptyArray.BYTE;
                mBufferedOffset = 0;
                mBufferedLength = 0;
            } else {
                // Some of the input was not consumed
                mBuffered = chunk;
                mBufferedOffset = opResult.inputConsumed;
                mBufferedLength = chunk.length - opResult.inputConsumed;
            }

            if (opResult.inputConsumed > chunk.length) {
                throw new KeyStoreException(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                        "Keystore consumed more input than provided. Provided: "
                                + chunk.length + ", consumed: " + opResult.inputConsumed);
            }

            if ((opResult.output != null) && (opResult.output.length > 0)) {
                // Some output was produced by this update operation
                if (bufferedOutput == null) {
                    // No output buffered yet.
                    if (mBufferedLength == 0) {
                        // No more output will be produced by this flush operation
                        mProducedOutputSizeBytes += opResult.output.length;
                        return opResult.output;
                    } else {
                        // More output might be produced by this flush operation -- buffer output.
                        bufferedOutput = new ByteArrayOutputStream();
                    }
                }
                // Buffer the output from this update operation
                try {
                    bufferedOutput.write(opResult.output);
                } catch (IOException e) {
                    throw new ProviderException("Failed to buffer output", e);
                }
            }
        }

        if (mBufferedLength > 0) {
            throw new KeyStoreException(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH,
                    "Keystore failed to consume last "
                            + ((mBufferedLength != 1) ? (mBufferedLength + " bytes") : "byte")
                            + " of input");
        }

        byte[] result = (bufferedOutput != null) ? bufferedOutput.toByteArray() : EmptyArray.BYTE;
        mProducedOutputSizeBytes += result.length;
        return result;
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
        public OperationResult finish(byte[] signature, byte[] additionalEntropy) {
            return mKeyStore.finish(mOperationToken, null, signature, additionalEntropy);
        }
    }
}
