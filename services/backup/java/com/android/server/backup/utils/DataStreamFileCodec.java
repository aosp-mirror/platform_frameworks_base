/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Provides an interface for serializing an object to a file and deserializing it back again.
 *
 * <p>Serialization logic is implemented as a {@link DataStreamCodec}.
 *
 * @param <T> The type of object to serialize / deserialize.
 */
public final class DataStreamFileCodec<T> {
    private final File mFile;
    private final DataStreamCodec<T> mCodec;

    /**
     * Constructs an instance to serialize to or deserialize from the given file, with the given
     * serialization / deserialization strategy.
     */
    public DataStreamFileCodec(File file, DataStreamCodec<T> codec) {
        mFile = file;
        mCodec = codec;
    }

    /**
     * Deserializes a {@code T} from the file, automatically closing input streams.
     *
     * @return The deserialized object.
     * @throws IOException if an IO error occurred.
     */
    public T deserialize() throws IOException {
        try (
            FileInputStream fileInputStream = new FileInputStream(mFile);
            DataInputStream dataInputStream = new DataInputStream(fileInputStream)
        ) {
            return mCodec.deserialize(dataInputStream);
        }
    }

    /**
     * Serializes {@code t} to the file, automatically flushing and closing output streams.
     *
     * @param t The object to serialize.
     * @throws IOException if an IO error occurs.
     */
    public void serialize(T t) throws IOException {
        try (
            FileOutputStream fileOutputStream = new FileOutputStream(mFile);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            DataOutputStream dataOutputStream = new DataOutputStream(bufferedOutputStream)
        ) {
            mCodec.serialize(t, dataOutputStream);
            dataOutputStream.flush();
        }
    }
}
