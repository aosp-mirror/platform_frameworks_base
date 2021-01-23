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

package com.android.server.role.util;

import android.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@link Collection} utilities.
 */
public class CollectionUtils {
    private CollectionUtils() {}

    /**
     * Get the first element of a {@link List}, or {@code null} if none.
     *
     * @param list the {@link List}, or {@code null}
     * @param <E> the element type of the {@link List}
     * @return the first element of the {@link List}, or {@code 0} if none
     */
    @Nullable
    public static <E> E firstOrNull(@Nullable List<E> list) {
        return !isEmpty(list) ? list.get(0) : null;
    }

    /**
     * Check whether a {@link Collection} is empty or {@code null}.
     *
     * @param collection the {@link Collection}, or {@code null}
     * @return whether the {@link Collection} is empty or {@code null}
     */
    public static boolean isEmpty(@Nullable Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * Get the size of a {@link Collection}, or {@code 0} if {@code null}.
     *
     * @param collection the {@link Collection}, or {@code null}
     * @return the size of the {@link Collection}, or {@code 0} if {@code null}
     */
    public static int size(@Nullable Collection<?> collection) {
        return collection != null ? collection.size() : 0;
    }

    /**
     * Get the size of a {@link Map}, or {@code 0} if {@code null}.
     *
     * @param collection the {@link Map}, or {@code null}
     * @return the size of the {@link Map}, or {@code 0} if {@code null}
     */
    public static int size(@Nullable Map<?, ?> collection) {
        return collection != null ? collection.size() : 0;
    }
}
