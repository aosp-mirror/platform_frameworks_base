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

import java.util.Map;

import android.util.Log;

/**
 * Java API for libvintf.
 * @hide
 */
public class VintfObject {

    /// ---------- OTA

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

    /// ---------- CTS Device Info

    /**
     * @return a list of HAL names and versions that is supported by this
     * device as stated in device and framework manifests. For example,
     * ["android.hidl.manager@1.0", "android.hardware.camera.device@1.0",
     *  "android.hardware.camera.device@3.2"]. There are no duplicates.
     */
    public static native String[] getHalNamesAndVersions();

    /**
     * @return the BOARD_SEPOLICY_VERS build flag available in device manifest.
     */
    public static native String getSepolicyVersion();

    /**
     * @return a list of VNDK snapshots supported by the framework, as
     * specified in framework manifest. For example,
     * [("25.0.5", ["libjpeg.so", "libbase.so"]),
     *  ("25.1.3", ["libjpeg.so", "libbase.so"])]
     */
    public static native Map<String, String[]> getVndkSnapshots();
}
