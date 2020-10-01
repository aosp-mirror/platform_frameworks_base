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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Set;

/**
 * A read only {@code Map}-style interface of {@link AudioMetadata.Key} value pairs used
 * for {@link AudioMetadata}.
 *
 * <p>Using a {@link AudioMetadata.Key} interface,
 * this map looks up the corresponding value.
 * Read-only maps are thread-safe for lookup, but the underlying object
 * values may need their own thread protection if mutable.</p>
 *
 * {@see AudioMetadataMap}
 */
public interface AudioMetadataReadMap {
    /**
     * Returns true if the key exists in the map.
     *
     * @param key interface for requesting the value.
     * @param <T> type of value.
     * @return true if key exists in the Map.
     */
    <T> boolean containsKey(@NonNull AudioMetadata.Key<T> key);

    /**
     * Returns a copy of the map.
     *
     * This is intended for safe conversion between a {@link AudioMetadataReadMap}
     * interface and a {@link AudioMetadataMap} interface.
     * Currently only simple objects are used for key values which
     * means a shallow copy is sufficient.
     *
     * @return a Map copied from the existing map.
     */
    @NonNull
    AudioMetadataMap dup(); // lint checker doesn't like clone().

    /**
     * Returns the value associated with the key.
     *
     * @param key interface for requesting the value.
     * @param <T> type of value.
     * @return returns the value of associated with key or null if it doesn't exist.
     */
    @Nullable
    <T> T get(@NonNull AudioMetadata.Key<T> key);

    /**
     * Returns a {@code Set} of keys associated with the map.
     * @hide
     */
    @NonNull
    Set<AudioMetadata.Key<?>> keySet();

    /**
     * Returns the number of elements in the map.
     */
    @IntRange(from = 0)
    int size();
}
