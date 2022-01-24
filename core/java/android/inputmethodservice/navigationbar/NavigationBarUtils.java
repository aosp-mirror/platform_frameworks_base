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

package android.inputmethodservice.navigationbar;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import android.content.res.Resources;
import android.util.TypedValue;

final class NavigationBarUtils {
    private NavigationBarUtils() {
        // Not intended to be instantiated.
    }

    /**
     * A utility method to convert "dp" to "pixel".
     *
     * <p>TODO(b/215443343): Remove this method by migrating DP values from
     * {@link NavigationBarConstants} to resource files.</p>
     *
     * @param dpValue "dp" value to be converted to "pixel"
     * @param res {@link Resources} to be used when dealing with "dp".
     * @return the pixels for a given dp value.
     */
    static int dpToPx(float dpValue, Resources res) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, res.getDisplayMetrics());
    }
}
