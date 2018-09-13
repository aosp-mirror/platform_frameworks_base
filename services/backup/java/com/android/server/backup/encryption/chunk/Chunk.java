package com.android.server.backup.encryption.chunk;

import android.util.proto.ProtoInputStream;

import java.io.IOException;

/**
 * Information about a chunk entry in a protobuf. Only used for reading from a {@link
 * ProtoInputStream}.
 */
public class Chunk {
    /**
     * Reads a Chunk from a {@link ProtoInputStream}. Expects the message to be of format {@link
     * ChunksMetadataProto.Chunk}.
     *
     * @param inputStream currently at a {@link ChunksMetadataProto.Chunk} message.
     * @throws IOException when the message is not structured as expected or a field can not be
     *     read.
     */
    static Chunk readFromProto(ProtoInputStream inputStream) throws IOException {
        Chunk result = new Chunk();

        while (inputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (inputStream.getFieldNumber()) {
                case (int) ChunksMetadataProto.Chunk.HASH:
                    result.mHash = inputStream.readBytes(ChunksMetadataProto.Chunk.HASH);
                    break;
                case (int) ChunksMetadataProto.Chunk.LENGTH:
                    result.mLength = inputStream.readInt(ChunksMetadataProto.Chunk.LENGTH);
                    break;
            }
        }

        return result;
    }

    private int mLength;
    private byte[] mHash;

    /** Private constructor. This class should only be instantiated by calling readFromProto. */
    private Chunk() {
        // Set default values for fields in case they are not available in the proto.
        mHash = new byte[]{};
        mLength = 0;
    }

    public int getLength() {
        return mLength;
    }

    public byte[] getHash() {
        return mHash;
    }
}