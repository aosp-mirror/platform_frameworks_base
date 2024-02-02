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

import android.annotation.NonNull;
import android.annotation.TestApi;

import java.util.Map;

/**
 * Java API for libvintf.
 *
 * @hide
 */
@TestApi
public class VintfObject {

    private static final String LOG_TAG = "VintfObject";

    static {
        System.loadLibrary("vintf_jni");
    }

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
     * Verify Vintf compatibility on the device at boot time. Certain checks
     * like kernel checks, AVB checks are disabled.
     *
     * @return = 0 if success (compatible)
     *         > 0 if incompatible
     *         < 0 if any error (mount partition fails, illformed XML, etc.)
     *
     * @hide
     */
    public static native int verifyBuildAtBoot();

    /**
     * @return a list of HAL names and versions that is supported by this
     * device as stated in device and framework manifests. For example,
     * ["android.hidl.manager@1.0", "android.hardware.camera.device@1.0",
     *  "android.hardware.camera.device@3.2"]. There are no duplicates.
     *
     * For AIDL HALs, the version is a single number
     * (e.g. "android.hardware.light@1"). Historically, this API strips the
     * version number for AIDL HALs (e.g. "android.hardware.light"). Users
     * of this API must be able to handle both for backwards compatibility.
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
     * @return the PLATFORM_SEPOLICY_VERSION build flag available in framework
     * compatibility matrix.
     *
     * @hide
     */
    @TestApi
    public static native @NonNull String getPlatformSepolicyVersion();

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
