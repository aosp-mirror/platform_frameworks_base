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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;

/**
 * Utility methods for handling {@link Bundle}.
 *
 */
public final class BundleUtils {
    private BundleUtils() {
    }

    /**
     * Returns true if {@code in} is null or empty.
     */
    public static boolean isEmpty(@Nullable Bundle in) {
        return (in == null) || (in.size() == 0);
    }

    /**
     * Creates a copy of the {@code in} Bundle.  If {@code in} is null, it'll return an empty
     * bundle.
     *
     * <p>The resulting {@link Bundle} is always writable. (i.e. it won't return
     * {@link Bundle#EMPTY})
     */
    public static @NonNull Bundle clone(@Nullable Bundle in) {
        return (in != null) ? new Bundle(in) : new Bundle();
    }

}
