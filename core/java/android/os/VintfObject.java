/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.os;

import java.util.ArrayList;

import android.util.Log;

/** @hide */
public class VintfObject {

    private static final String LOG_TAG = "VintfObject";

    /**
     * Slurps all device information (both manifests and both matrices)
     * and report them.
     * If any error in getting one of the manifests, it is not included in
     * the list.
     */
    public static native String[] report();

    /**
     * Verify that the given metadata for an OTA package is compatible with
     * this device.
     *
     * @param packageInfo a list of serialized form of HalMaanifest's /
     * CompatibilityMatri'ces (XML).
     * @return = 0 if success (compatible)
     *         > 0 if incompatible
     *         < 0 if any error (mount partition fails, illformed XML, etc.)
     */
    public static native int verify(String[] packageInfo);

}
