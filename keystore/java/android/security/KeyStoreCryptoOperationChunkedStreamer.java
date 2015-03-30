package android.security;

import android.security.keymaster.OperationResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStore} service's
 * {@code update} and {@code finish} operations.
 *
 * <p>The helper abstracts away to issues that need to be solved in most code that uses KeyStore's
 * update and finish operations. Firstly, KeyStore's update and finish operations can consume only a
 * limited amount of data in one go because the operations are marshalled via Binder. Secondly, the
 * update operation may consume less data than provided, in which case the caller has to buffer
 * the remainder for next time. The helper exposes {@link #update(byte[], int, int) update} and
 * {@link #doFinal(byte[], int, int) doFinal} operations which can be used to conveniently implement
 * various JCA crypto primitives.
 *
 * <p>KeyStore operation through which data is streamed is abstracted away as
 * {@link KeyStoreOperation} to avoid having this class deal with operation tokens and occasional
 * additional parameters to update and final operations.
 *
 * @hide
 */
public class KeyStoreCryptoOperationChunkedStreamer {
    public interface KeyStoreOperation {
        /**
         * Returns the result of the KeyStore update operation or null if keystore couldn't be
         * reached.
         */
        OperationResult update(byte[] input);

        /**
         * Returns the result of the KeyStore finish operation or null if keystore couldn't be
         * reached.
         */
        OperationResult finish(byte[] input);
    }

    // Binder buffer is about 1MB, but it's shared between all active transactions of the process.
    // Thus, it's safer to use a much smaller upper bound.
    private static final int DEFAULT_MAX_CHUNK_SIZE = 64 * 1024;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final KeyStoreOperation mKeyStoreOperation;
    private final int mMaxChunkSize;

    private byte[] mBuffered = EMPTY_BYTE_ARRAY;
    private int mBufferedOffset;
    private int mBufferedLength;

    public KeyStoreCryptoOperationChunkedStreamer(KeyStoreOperation operation) {
        this(operation, DEFAULT_MAX_CHUNK_SIZE);
    }

    public KeyStoreCryptoOperationChunkedStreamer(KeyStoreOperation operation, int maxChunkSize) {
        mKeyStoreOperation = operation;
        mMaxChunkSize = maxChunkSize;
    }

    public byte[] update(byte[] input, int inputOffset, int inputLength) throws KeymasterException {
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
                chunk = new byte[mMaxChunkSize];
                System.arraycopy(mBuffered, mBufferedOffset, chunk, 0, mBufferedLength);
                inputBytesInChunk = chunk.length - mBufferedLength;
                System.arraycopy(input, inputOffset, chunk, mBufferedLength, inputBytesInChunk);
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
                    chunk = new byte[mBufferedLength + inputLength];
                    inputBytesInChunk = inputLength;
                    System.arraycopy(mBuffered, mBufferedOffset, chunk, 0, mBufferedLength);
                    System.arraycopy(input, inputOffset, chunk, mBufferedLength, inputLength);
                }
            }
            // Update input array references to reflect that some of its bytes are now in mBuffered.
            inputOffset += inputBytesInChunk;
            inputLength -= inputBytesInChunk;

            OperationResult opResult = mKeyStoreOperation.update(chunk);
            if (opResult == null) {
                throw new KeyStoreConnectException();
            } else if (opResult.resultCode != KeyStore.NO_ERROR) {
                throw KeymasterUtils.getExceptionForKeymasterError(opResult.resultCode);
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
                    throw new CryptoOperationException("Nothing consumed from max-sized chunk: "
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
                throw new CryptoOperationException("Consumed more than provided: "
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
                            throw new CryptoOperationException("Failed to buffer output", e);
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
                            throw new CryptoOperationException("Failed to buffer output", e);
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
            throws KeymasterException {
        if (inputLength == 0) {
            // No input provided -- simplify the rest of the code
            input = EMPTY_BYTE_ARRAY;
            inputOffset = 0;
        }

        byte[] updateOutput = null;
        if ((mBufferedLength + inputLength) > mMaxChunkSize) {
            updateOutput = update(input, inputOffset, inputLength);
            inputOffset += inputLength;
            inputLength = 0;
        }
        // All of available input fits into one chunk.

        byte[] finalChunk;
        if ((mBufferedLength == 0) && (inputOffset == 0)
                && (inputLength == input.length)) {
            // Nothing buffered and all of input array needs to be fed into the finish operation.
            finalChunk = input;
        } else {
            // Need to combine buffered data with input data into one array.
            finalChunk = new byte[mBufferedLength + inputLength];
            System.arraycopy(mBuffered, mBufferedOffset, finalChunk, 0, mBufferedLength);
            System.arraycopy(input, inputOffset, finalChunk, mBufferedLength, inputLength);
        }
        mBuffered = EMPTY_BYTE_ARRAY;
        mBufferedLength = 0;
        mBufferedOffset = 0;

        OperationResult opResult = mKeyStoreOperation.finish(finalChunk);
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeymasterUtils.getExceptionForKeymasterError(opResult.resultCode);
        }

        if (opResult.inputConsumed != finalChunk.length) {
            throw new CryptoOperationException("Unexpected number of bytes consumed by finish: "
                    + opResult.inputConsumed + " instead of " + finalChunk.length);
        }

        // Return the concatenation of the output of update and finish.
        byte[] result;
        byte[] finishOutput = opResult.output;
        if ((updateOutput == null) || (updateOutput.length == 0)) {
            result = finishOutput;
        } else if ((finishOutput == null) || (finishOutput.length == 0)) {
            result = updateOutput;
        } else {
            result = new byte[updateOutput.length + finishOutput.length];
            System.arraycopy(updateOutput, 0, result, 0, updateOutput.length);
            System.arraycopy(finishOutput, 0, result, updateOutput.length, finishOutput.length);
        }
        return (result != null) ? result : EMPTY_BYTE_ARRAY;
    }
}
