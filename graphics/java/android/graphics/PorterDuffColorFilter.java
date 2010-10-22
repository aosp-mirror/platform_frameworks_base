/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics;

public class PorterDuffColorFilter extends ColorFilter {
    /**
     * Create a colorfilter that uses the specified color and porter-duff mode.
     *
     * @param srcColor       The source color used with the specified
     *                       porter-duff mode
     * @param mode           The porter-duff mode that is applied
     */
    public PorterDuffColorFilter(int srcColor, PorterDuff.Mode mode) {
        native_instance = native_CreatePorterDuffFilter(srcColor, mode.nativeInt);
        nativeColorFilter = nCreatePorterDuffFilter(native_instance, srcColor, mode.nativeInt);
    }

    private static native int native_CreatePorterDuffFilter(int srcColor, int porterDuffMode);
    private static native int nCreatePorterDuffFilter(int nativeFilter, int srcColor,
            int porterDuffMode);
}
