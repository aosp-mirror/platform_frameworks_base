/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;

/**
 * Proto utility that reads and writes proto for some data.
 *
 * @param <T> data that can be written and read from a proto
 */
interface ProtoReadWriter<T> {

    /**
     * Write data to a proto stream
     */
    void writeToProto(@NonNull ProtoOutputStream stream, @NonNull T data);

    /**
     * Parse data from the proto stream and return
     */
    @Nullable T readFromProto(@NonNull ProtoInputStream stream) throws IOException;
}
