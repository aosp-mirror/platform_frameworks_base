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
import android.app.smartspace.uitemplatedata.Text;
import android.text.TextUtils;

/**
 * Utilities for Smartspace data.
 *
 * @hide
 */
public final class SmartspaceUtils {

    private SmartspaceUtils() {
    }

    /** Returns true if the passed in {@link Text} is null or its content is empty. */
    public static boolean isEmpty(@Nullable Text text) {
        return text == null || TextUtils.isEmpty(text.getText());
    }

    /** Returns true if the passed-in {@link Text}s are equal. */
    public static boolean isEqual(@Nullable Text text1, @Nullable Text text2) {
        if (text1 == null && text2 == null) return true;
        if (text1 == null || text2 == null) return false;
        return text1.equals(text2);
    }

    /** Returns true if the passed-in {@link CharSequence}s are equal. */
    public static boolean isEqual(@Nullable CharSequence cs1, @Nullable CharSequence cs2) {
        if (cs1 == null && cs2 == null) return true;
        if (cs1 == null || cs2 == null) return false;
        return cs1.toString().contentEquals(cs2);
    }
}
