/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking.cdc;

import static com.android.internal.util.Preconditions.checkArgument;

import com.android.server.backup.encryption.chunking.Chunker;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Splits a stream of bytes into variable-sized chunks, using content-defined chunking. */
public class ContentDefinedChunker implements Chunker {
    private static final int WINDOW_SIZE = 31;
    private static final byte DEFAULT_OUT_BYTE = (byte) 0;

    private final byte[] mChunkBuffer;
    private final RabinFingerprint64 mRabinFingerprint64;
    private final FingerprintMixer mFingerprintMixer;
    private final BreakpointPredicate mBreakpointPredicate;
    private final int mMinChunkSize;
    private final int mMaxChunkSize;

    /**
     * Constructor.
     *
     * @param minChunkSize The minimum size of a chunk. No chunk will be produced of a size smaller
     *     than this except possibly at the very end of the stream.
     * @param maxChunkSize The maximum size of a chunk. No chunk will be produced of a larger size.
     * @param rabinFingerprint64 Calculates fingerprints, with which to determine breakpoints.
     * @param breakpointPredicate Given a Rabin fingerprint, returns whether this ought to be a
     *     breakpoint.
     */
    public ContentDefinedChunker(
            int minChunkSize,
            int maxChunkSize,
            RabinFingerprint64 rabinFingerprint64,
            FingerprintMixer fingerprintMixer,
            BreakpointPredicate breakpointPredicate) {
        checkArgument(
                minChunkSize >= WINDOW_SIZE,
                "Minimum chunk size must be greater than window size.");
        checkArgument(
                maxChunkSize >= minChunkSize,
                "Maximum chunk size cannot be smaller than minimum chunk size.");
        mChunkBuffer = new byte[maxChunkSize];
        mRabinFingerprint64 = rabinFingerprint64;
        mBreakpointPredicate = breakpointPredicate;
        mFingerprintMixer = fingerprintMixer;
        mMinChunkSize = minChunkSize;
        mMaxChunkSize = maxChunkSize;
    }

    /**
     * Breaks the input stream into variable-sized chunks.
     *
     * @param inputStream The input bytes to break into chunks.
     * @param chunkConsumer A function to process each chunk as it's generated.
     * @throws IOException Thrown if there is an issue reading from the input stream.
     * @throws GeneralSecurityException Thrown if the {@link ChunkConsumer} throws it.
     */
    @Override
    public void chunkify(InputStream inputStream, ChunkConsumer chunkConsumer)
            throws IOException, GeneralSecurityException {
        int chunkLength;
        int initialReadLength = mMinChunkSize - WINDOW_SIZE;

        // Performance optimization - there is no reason to calculate fingerprints for windows
        // ending before the minimum chunk size.
        while ((chunkLength =
                        inputStream.read(mChunkBuffer, /*off=*/ 0, /*len=*/ initialReadLength))
                != -1) {
            int b;
            long fingerprint = 0L;

            while ((b = inputStream.read()) != -1) {
                byte inByte = (byte) b;
                byte outByte = getCurrentWindowStartByte(chunkLength);
                mChunkBuffer[chunkLength++] = inByte;

                fingerprint =
                        mRabinFingerprint64.computeFingerprint64(inByte, outByte, fingerprint);

                if (chunkLength >= mMaxChunkSize
                        || (chunkLength >= mMinChunkSize
                                && mBreakpointPredicate.isBreakpoint(
                                        mFingerprintMixer.mix(fingerprint)))) {
                    chunkConsumer.accept(Arrays.copyOf(mChunkBuffer, chunkLength));
                    chunkLength = 0;
                    break;
                }
            }

            if (chunkLength > 0) {
                chunkConsumer.accept(Arrays.copyOf(mChunkBuffer, chunkLength));
            }
        }
    }

    private byte getCurrentWindowStartByte(int chunkLength) {
        if (chunkLength < mMinChunkSize) {
            return DEFAULT_OUT_BYTE;
        } else {
            return mChunkBuffer[chunkLength - WINDOW_SIZE];
        }
    }

    /** Whether the current fingerprint indicates the end of a chunk. */
    public interface BreakpointPredicate {

        /**
         * Returns {@code true} if the fingerprint of the last {@code WINDOW_SIZE} bytes indicates
         * the chunk ought to end at this position.
         *
         * @param fingerprint Fingerprint of the last {@code WINDOW_SIZE} bytes.
         * @return Whether this ought to be a chunk breakpoint.
         */
        boolean isBreakpoint(long fingerprint);
    }
}
