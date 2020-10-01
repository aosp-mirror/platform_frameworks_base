/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * AudioMetadataMap is a writeable {@code Map}-style
 * interface of {@link AudioMetadata.Key} value pairs.
 * This interface is not guaranteed to be thread-safe
 * unless the underlying implementation for the {@code AudioMetadataMap}
 * states it as thread safe.
 *
 * {@see AudioMetadataReadMap}
 */
// TODO: Create a wrapper like java.util.Collections.synchronizedMap?

public interface AudioMetadataMap extends AudioMetadataReadMap {
    /**
     * Removes the value associated with the key.
     * @param key interface for storing the value.
     * @param <T> type of value.
     * @return the value of the key, null if it doesn't exist.
     */
    @Nullable
    <T> T remove(@NonNull AudioMetadata.Key<T> key);

    /**
     * Sets a value for the key.
     *
     * @param key interface for storing the value.
     * @param <T> type of value.
     * @param value a non-null value of type T.
     * @return the previous value associated with key or null if it doesn't exist.
     */
    // See automatic Kotlin overloading for Java interoperability.
    // https://kotlinlang.org/docs/reference/java-interop.html#operators
    // See also Kotlin set for overloaded operator indexing.
    // https://kotlinlang.org/docs/reference/operator-overloading.html#indexed
    // Also the Kotlin mutable-list set.
    // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-list/set.html
    @Nullable
    <T> T set(@NonNull AudioMetadata.Key<T> key, @NonNull T value);
}
