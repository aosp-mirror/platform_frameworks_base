/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.smartspace;

import android.annotation.Nullable;

/**
 * Utilities for Smartspace data.
 *
 * @hide
 */
public final class SmartspaceUtils {

    private SmartspaceUtils() {
    }

    /** Returns true if the passed-in {@link CharSequence}s are equal. */
    public static boolean isEqual(@Nullable CharSequence cs1, @Nullable CharSequence cs2) {
        if ((cs1 == null && cs2 != null) || (cs1 != null && cs2 == null)) return false;
        if (cs1 == null && cs2 == null) return true;
        return cs1.toString().contentEquals(cs2);
    }
}
