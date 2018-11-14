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

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.SecretKey;

/** Tests for {@link ContentDefinedChunker}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ContentDefinedChunkerTest {
    private static final int WINDOW_SIZE_BYTES = 31;
    private static final int MIN_SIZE_BYTES = 40;
    private static final int MAX_SIZE_BYTES = 300;
    private static final String CHUNK_BOUNDARY = "<----------BOUNDARY----------->";
    private static final byte[] CHUNK_BOUNDARY_BYTES = CHUNK_BOUNDARY.getBytes(UTF_8);
    private static final String CHUNK_1 = "This is the first chunk";
    private static final String CHUNK_2 = "And this is the second chunk";
    private static final String CHUNK_3 = "And finally here is the third chunk";
    private static final String SMALL_CHUNK = "12345678";

    private FingerprintMixer mFingerprintMixer;
    private RabinFingerprint64 mRabinFingerprint64;
    private ContentDefinedChunker mChunker;

    /** Set up a {@link ContentDefinedChunker} and dependencies for use in the tests. */
    @Before
    public void setUp() throws Exception {
        SecretKey secretKey = generateAesKey();
        byte[] salt = new byte[FingerprintMixer.SALT_LENGTH_BYTES];
        Random random = new Random();
        random.nextBytes(salt);
        mFingerprintMixer = new FingerprintMixer(secretKey, salt);

        mRabinFingerprint64 = new RabinFingerprint64();
        long chunkBoundaryFingerprint = calculateFingerprint(CHUNK_BOUNDARY_BYTES);
        mChunker =
                new ContentDefinedChunker(
                        MIN_SIZE_BYTES,
                        MAX_SIZE_BYTES,
                        mRabinFingerprint64,
                        mFingerprintMixer,
                        (fingerprint) -> fingerprint == chunkBoundaryFingerprint);
    }

    /**
     * Creating a {@link ContentDefinedChunker} with a minimum chunk size that is smaller than the
     * window size should throw an {@link IllegalArgumentException}.
     */
    @Test
    public void create_withMinChunkSizeSmallerThanWindowSize_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ContentDefinedChunker(
                                WINDOW_SIZE_BYTES - 1,
                                MAX_SIZE_BYTES,
                                mRabinFingerprint64,
                                mFingerprintMixer,
                                null));
    }

    /**
     * Creating a {@link ContentDefinedChunker} with a maximum chunk size that is smaller than the
     * minimum chunk size should throw an {@link IllegalArgumentException}.
     */
    @Test
    public void create_withMaxChunkSizeSmallerThanMinChunkSize_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ContentDefinedChunker(
                                MIN_SIZE_BYTES,
                                MIN_SIZE_BYTES - 1,
                                mRabinFingerprint64,
                                mFingerprintMixer,
                                null));
    }

    /**
     * {@link ContentDefinedChunker#chunkify(InputStream, Chunker.ChunkConsumer)} should split the
     * input stream across chunk boundaries by default.
     */
    @Test
    public void chunkify_withLargeChunks_splitsIntoChunksAcrossBoundaries() throws Exception {
        byte[] input =
                (CHUNK_1 + CHUNK_BOUNDARY + CHUNK_2 + CHUNK_BOUNDARY + CHUNK_3).getBytes(UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        ArrayList<String> result = new ArrayList<>();

        mChunker.chunkify(inputStream, (chunk) -> result.add(new String(chunk, UTF_8)));

        assertThat(result)
                .containsExactly(CHUNK_1 + CHUNK_BOUNDARY, CHUNK_2 + CHUNK_BOUNDARY, CHUNK_3)
                .inOrder();
    }

    /** Chunks should be combined across boundaries until they reach the minimum chunk size. */
    @Test
    public void chunkify_withSmallChunks_combinesChunksUntilMinSize() throws Exception {
        byte[] input =
                (SMALL_CHUNK + CHUNK_BOUNDARY + CHUNK_2 + CHUNK_BOUNDARY + CHUNK_3).getBytes(UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        ArrayList<String> result = new ArrayList<>();

        mChunker.chunkify(inputStream, (chunk) -> result.add(new String(chunk, UTF_8)));

        assertThat(result)
                .containsExactly(SMALL_CHUNK + CHUNK_BOUNDARY + CHUNK_2 + CHUNK_BOUNDARY, CHUNK_3)
                .inOrder();
        assertThat(result.get(0).length()).isAtLeast(MIN_SIZE_BYTES);
    }

    /** Chunks can not be larger than the maximum chunk size. */
    @Test
    public void chunkify_doesNotProduceChunksLargerThanMaxSize() throws Exception {
        byte[] largeInput = new byte[MAX_SIZE_BYTES * 10];
        Arrays.fill(largeInput, "a".getBytes(UTF_8)[0]);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(largeInput);
        ArrayList<String> result = new ArrayList<>();

        mChunker.chunkify(inputStream, (chunk) -> result.add(new String(chunk, UTF_8)));

        byte[] expectedChunkBytes = new byte[MAX_SIZE_BYTES];
        Arrays.fill(expectedChunkBytes, "a".getBytes(UTF_8)[0]);
        String expectedChunk = new String(expectedChunkBytes, UTF_8);
        assertThat(result)
                .containsExactly(
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk,
                        expectedChunk)
                .inOrder();
    }

    /**
     * If the input stream signals zero availablility, {@link
     * ContentDefinedChunker#chunkify(InputStream, Chunker.ChunkConsumer)} should still work.
     */
    @Test
    public void chunkify_withInputStreamReturningZeroAvailability_returnsChunks() throws Exception {
        byte[] input = (SMALL_CHUNK + CHUNK_BOUNDARY + CHUNK_2).getBytes(UTF_8);
        ZeroAvailabilityInputStream zeroAvailabilityInputStream =
                new ZeroAvailabilityInputStream(input);
        ArrayList<String> result = new ArrayList<>();

        mChunker.chunkify(
                zeroAvailabilityInputStream, (chunk) -> result.add(new String(chunk, UTF_8)));

        assertThat(result).containsExactly(SMALL_CHUNK + CHUNK_BOUNDARY + CHUNK_2).inOrder();
    }

    /**
     * {@link ContentDefinedChunker#chunkify(InputStream, Chunker.ChunkConsumer)} should rethrow any
     * exception thrown by its consumer.
     */
    @Test
    public void chunkify_whenConsumerThrowsException_rethrowsException() throws Exception {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[] {1});

        assertThrows(
                GeneralSecurityException.class,
                () ->
                        mChunker.chunkify(
                                inputStream,
                                (chunk) -> {
                                    throw new GeneralSecurityException();
                                }));
    }

    private long calculateFingerprint(byte[] bytes) {
        long fingerprint = 0;
        for (byte inByte : bytes) {
            fingerprint =
                    mRabinFingerprint64.computeFingerprint64(
                            /*inChar=*/ inByte, /*outChar=*/ (byte) 0, fingerprint);
        }
        return mFingerprintMixer.mix(fingerprint);
    }

    private static class ZeroAvailabilityInputStream extends ByteArrayInputStream {
        ZeroAvailabilityInputStream(byte[] wrapped) {
            super(wrapped);
        }

        @Override
        public synchronized int available() {
            return 0;
        }
    }
}
