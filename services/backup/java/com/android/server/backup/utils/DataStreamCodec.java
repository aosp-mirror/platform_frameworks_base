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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Implements how to serialize a {@code T} to a {@link DataOutputStream} and how to deserialize a
 * {@code T} from a {@link DataInputStream}.
 *
 * @param <T> Type of object to be serialized / deserialized.
 */
public interface DataStreamCodec<T> {
    /**
     * Serializes {@code t} to {@code dataOutputStream}.
     */
    void serialize(T t, DataOutputStream dataOutputStream) throws IOException;

    /**
     * Deserializes {@code t} from {@code dataInputStream}.
     */
    T deserialize(DataInputStream dataInputStream) throws IOException;
}

