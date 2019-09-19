/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.annotation.Nullable;
import android.util.Slog;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunk.ChunkListingMap;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes batches of {@link EncryptedChunk} to a diff script, and generates the associated {@link
 * ChunksMetadataProto.ChunkListing} and {@link ChunksMetadataProto.ChunkOrdering}.
 */
public class BackupFileBuilder {
    private static final String TAG = "BackupFileBuilder";

    private static final int BYTES_PER_KILOBYTE = 1024;

    private final BackupWriter mBackupWriter;
    private final EncryptedChunkEncoder mEncryptedChunkEncoder;
    private final ChunkListingMap mOldChunkListing;
    private final ChunksMetadataProto.ChunkListing mNewChunkListing;
    private final ChunksMetadataProto.ChunkOrdering mChunkOrdering;
    private final List<ChunksMetadataProto.Chunk> mKnownChunks = new ArrayList<>();
    private final List<Integer> mKnownStarts = new ArrayList<>();
    private final Map<ChunkHash, Long> mChunkStartPositions;

    private long mNewChunksSizeBytes;
    private boolean mFinished;

    /**
     * Constructs a new instance which writes raw data to the given {@link OutputStream}, without
     * generating a diff.
     *
     * <p>This class never closes the output stream.
     */
    public static BackupFileBuilder createForNonIncremental(OutputStream outputStream) {
        return new BackupFileBuilder(
                new RawBackupWriter(outputStream), new ChunksMetadataProto.ChunkListing());
    }

    /**
     * Constructs a new instance which writes a diff script to the given {@link OutputStream} using
     * a {@link SingleStreamDiffScriptWriter}.
     *
     * <p>This class never closes the output stream.
     *
     * @param oldChunkListing against which the diff will be generated.
     */
    public static BackupFileBuilder createForIncremental(
            OutputStream outputStream, ChunksMetadataProto.ChunkListing oldChunkListing) {
        return new BackupFileBuilder(
                DiffScriptBackupWriter.newInstance(outputStream), oldChunkListing);
    }

    private BackupFileBuilder(
            BackupWriter backupWriter, ChunksMetadataProto.ChunkListing oldChunkListing) {
        this.mBackupWriter = backupWriter;
        // TODO(b/77188289): Use InlineLengthsEncryptedChunkEncoder for key-value backups
        this.mEncryptedChunkEncoder = new LengthlessEncryptedChunkEncoder();
        this.mOldChunkListing = ChunkListingMap.fromProto(oldChunkListing);

        mNewChunkListing = new ChunksMetadataProto.ChunkListing();
        mNewChunkListing.cipherType = ChunksMetadataProto.AES_256_GCM;
        mNewChunkListing.chunkOrderingType = ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;

        mChunkOrdering = new ChunksMetadataProto.ChunkOrdering();
        mChunkStartPositions = new HashMap<>();
    }

    /**
     * Writes the given chunks to the output stream, and adds them to the new chunk listing and
     * chunk ordering.
     *
     * <p>Sorts the chunks in lexicographical order before writing.
     *
     * @param allChunks The hashes of all the chunks, in the order they appear in the plaintext.
     * @param newChunks A map from hash to {@link EncryptedChunk} containing the new chunks not
     *     present in the previous backup.
     */
    public void writeChunks(List<ChunkHash> allChunks, Map<ChunkHash, EncryptedChunk> newChunks)
            throws IOException {
        checkState(!mFinished, "Cannot write chunks after flushing.");

        List<ChunkHash> sortedChunks = new ArrayList<>(allChunks);
        Collections.sort(sortedChunks);
        for (ChunkHash chunkHash : sortedChunks) {
            // As we have already included this chunk in the backup file, don't add it again to
            // deduplicate identical chunks.
            if (!mChunkStartPositions.containsKey(chunkHash)) {
                // getBytesWritten() gives us the start of the chunk.
                mChunkStartPositions.put(chunkHash, mBackupWriter.getBytesWritten());

                writeChunkToFileAndListing(chunkHash, newChunks);
            }
        }

        long totalSizeKb = mBackupWriter.getBytesWritten() / BYTES_PER_KILOBYTE;
        long newChunksSizeKb = mNewChunksSizeBytes / BYTES_PER_KILOBYTE;
        Slog.d(
                TAG,
                "Total backup size: "
                        + totalSizeKb
                        + " kb, new chunks size: "
                        + newChunksSizeKb
                        + " kb");

        for (ChunkHash chunkHash : allChunks) {
            mKnownStarts.add(mChunkStartPositions.get(chunkHash).intValue());
        }
    }

    /**
     * Returns a new listing for all of the chunks written so far, setting the given fingerprint
     * mixer salt (this overrides the {@link ChunksMetadataProto.ChunkListing#fingerprintMixerSalt}
     * in the old {@link ChunksMetadataProto.ChunkListing} passed into the
     * {@link #BackupFileBuilder).
     */
    public ChunksMetadataProto.ChunkListing getNewChunkListing(
            @Nullable byte[] fingerprintMixerSalt) {
        // TODO: b/141537803 Add check to ensure this is called only once per instance
        mNewChunkListing.fingerprintMixerSalt =
                fingerprintMixerSalt != null
                        ? Arrays.copyOf(fingerprintMixerSalt, fingerprintMixerSalt.length)
                        : new byte[0];
        mNewChunkListing.chunks = mKnownChunks.toArray(new ChunksMetadataProto.Chunk[0]);
        return mNewChunkListing;
    }

    /** Returns a new ordering for all of the chunks written so far, setting the given checksum. */
    public ChunksMetadataProto.ChunkOrdering getNewChunkOrdering(byte[] checksum) {
        // TODO: b/141537803 Add check to ensure this is called only once per instance
        mChunkOrdering.starts = new int[mKnownStarts.size()];
        for (int i = 0; i < mKnownStarts.size(); i++) {
            mChunkOrdering.starts[i] = mKnownStarts.get(i).intValue();
        }
        mChunkOrdering.checksum = Arrays.copyOf(checksum, checksum.length);
        return mChunkOrdering;
    }

    /**
     * Finishes the backup file by writing the chunk metadata and metadata position.
     *
     * <p>Once this is called, calling {@link #writeChunks(List, Map)} will throw {@link
     * IllegalStateException}.
     */
    public void finish(ChunksMetadataProto.ChunksMetadata metadata) throws IOException {
        checkNotNull(metadata, "Metadata cannot be null");

        long startOfMetadata = mBackupWriter.getBytesWritten();
        mBackupWriter.writeBytes(ChunksMetadataProto.ChunksMetadata.toByteArray(metadata));
        mBackupWriter.writeBytes(toByteArray(startOfMetadata));

        mBackupWriter.flush();
        mFinished = true;
    }

    /**
     * Checks if the given chunk hash references an existing chunk or a new chunk, and adds this
     * chunk to the backup file and new chunk listing.
     */
    private void writeChunkToFileAndListing(
            ChunkHash chunkHash, Map<ChunkHash, EncryptedChunk> newChunks) throws IOException {
        checkNotNull(chunkHash, "Hash cannot be null");

        if (mOldChunkListing.hasChunk(chunkHash)) {
            ChunkListingMap.Entry oldChunk = mOldChunkListing.getChunkEntry(chunkHash);
            mBackupWriter.writeChunk(oldChunk.getStart(), oldChunk.getLength());

            checkArgument(oldChunk.getLength() >= 0, "Chunk must have zero or positive length");
            addChunk(chunkHash.getHash(), oldChunk.getLength());
        } else if (newChunks.containsKey(chunkHash)) {
            EncryptedChunk newChunk = newChunks.get(chunkHash);
            mEncryptedChunkEncoder.writeChunkToWriter(mBackupWriter, newChunk);
            int length = mEncryptedChunkEncoder.getEncodedLengthOfChunk(newChunk);
            mNewChunksSizeBytes += length;

            checkArgument(length >= 0, "Chunk must have zero or positive length");
            addChunk(chunkHash.getHash(), length);
        } else {
            throw new IllegalArgumentException(
                    "Chunk did not exist in old chunks or new chunks: " + chunkHash);
        }
    }

    private void addChunk(byte[] chunkHash, int length) {
        ChunksMetadataProto.Chunk chunk = new ChunksMetadataProto.Chunk();
        chunk.hash = Arrays.copyOf(chunkHash, chunkHash.length);
        chunk.length = length;
        mKnownChunks.add(chunk);
    }

    private static byte[] toByteArray(long value) {
        // Note that this code needs to stay compatible with GWT, which has known
        // bugs when narrowing byte casts of long values occur.
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (value & 0xffL);
            value >>= 8;
        }
        return result;
    }
}
