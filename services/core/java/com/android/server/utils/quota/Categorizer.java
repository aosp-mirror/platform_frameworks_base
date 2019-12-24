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

package com.android.server.utils.quota;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Identifies the {@link Category} that each UPTC belongs in.
 *
 * @see Uptc
 */
public interface Categorizer {
    /** A {@link Categorizer} that always returns {@link Category.SINGLE_CATEGORY}. */
    Categorizer SINGLE_CATEGORIZER = (userId, packageName, tag) -> Category.SINGLE_CATEGORY;

    /**
     * Return the {@link Category} that this UPTC belongs to.
     *
     * @see Uptc
     */
    @NonNull
    Category getCategory(int userId, @NonNull String packageName, @Nullable String tag);
}
