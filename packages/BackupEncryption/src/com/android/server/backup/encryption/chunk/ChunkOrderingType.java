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

package com.android.server.backup.encryption.chunk;

import static com.android.server.backup.encryption.chunk.ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;
import static com.android.server.backup.encryption.chunk.ChunksMetadataProto.EXPLICIT_STARTS;
import static com.android.server.backup.encryption.chunk.ChunksMetadataProto.INLINE_LENGTHS;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** IntDef corresponding to the ChunkOrderingType enum in the ChunksMetadataProto protobuf. */
@IntDef({CHUNK_ORDERING_TYPE_UNSPECIFIED, EXPLICIT_STARTS, INLINE_LENGTHS})
@Retention(RetentionPolicy.SOURCE)
public @interface ChunkOrderingType {}
