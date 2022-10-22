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

package com.android.server.power;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemProperties;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Wrapper interface to access {@link SystemProperties}.
 *
 * @hide
 */
@VisibleForTesting
interface SystemPropertiesWrapper {
    /**
     * Get the String value for the given {@code key}.
     *
     * @param key the key to lookup
     * @param def the default value in case the property is not set or empty
     * @return if the {@code key} isn't found, return {@code def} if it isn't null, or an empty
     * string otherwise
     */
    @NonNull
    String get(@NonNull String key, @Nullable String def);

    /**
     * Set the value for the given {@code key} to {@code val}.
     *
     * @throws IllegalArgumentException if the {@code val} exceeds 91 characters
     * @throws RuntimeException if the property cannot be set, for example, if it was blocked by
     * SELinux. libc will log the underlying reason.
     */
    void set(@NonNull String key, @Nullable String val);
}
