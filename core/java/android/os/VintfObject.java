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

import android.annotation.TestApi;

import java.util.Map;

/**
 * Java API for libvintf.
 *
 * @hide
 */
@TestApi
public class VintfObject {

    /**
     * Slurps all device information (both manifests and both matrices)
     * and report them.
     * If any error in getting one of the manifests, it is not included in
     * the list.
     *
     * @hide
     */
    @TestApi
    public static native String[] report();

    /**
     * Verify that the given metadata for an OTA package is compatible with
     * this device.
     *
     * @param packageInfo a list of serialized form of HalManifest's /
     * CompatibilityMatri'ces (XML).
     * @return = 0 if success (compatible)
     *         > 0 if incompatible
     *         < 0 if any error (mount partition fails, illformed XML, etc.)
     *
     * @hide
     */
    public static native int verify(String[] packageInfo);

    /**
     * Verify Vintf compatibility on the device without checking AVB
     * (Android Verified Boot). It is useful to verify a running system
     * image where AVB check is irrelevant.
     *
     * @return = 0 if success (compatible)
     *         > 0 if incompatible
     *         < 0 if any error (mount partition fails, illformed XML, etc.)
     *
     * @hide
     */
    public static native int verifyWithoutAvb();

    /**
     * @return a list of HAL names and versions that is supported by this
     * device as stated in device and framework manifests. For example,
     * ["android.hidl.manager@1.0", "android.hardware.camera.device@1.0",
     *  "android.hardware.camera.device@3.2"]. There are no duplicates.
     *
     * @hide
     */
    @TestApi
    public static native String[] getHalNamesAndVersions();

    /**
     * @return the BOARD_SEPOLICY_VERS build flag available in device manifest.
     *
     * @hide
     */
    @TestApi
    public static native String getSepolicyVersion();

    /**
     * @return a list of VNDK snapshots supported by the framework, as
     * specified in framework manifest. For example,
     * [("27", ["libjpeg.so", "libbase.so"]),
     *  ("28", ["libjpeg.so", "libbase.so"])]
     *
     * @hide
     */
    @TestApi
    public static native Map<String, String[]> getVndkSnapshots();

    /**
     * @return Target Framework Compatibility Matrix (FCM) version, a number
     * specified in the device manifest indicating the FCM version that the
     * device manifest implements. Null if device manifest doesn't specify this
     * number (for legacy devices).
     *
     * @hide
     */
    @TestApi
    public static native Long getTargetFrameworkCompatibilityMatrixVersion();

    private VintfObject() {}
}
