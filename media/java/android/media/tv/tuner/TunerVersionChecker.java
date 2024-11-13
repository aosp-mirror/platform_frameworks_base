/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.media.tv.flags.Flags;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utility class to check the currently running Tuner Hal implementation version.
 *
 * APIs that are not supported by the HAL implementation version would be no-op.
 *
 * @hide
 */
@SystemApi
public final class TunerVersionChecker {
    private static final String TAG = "TunerVersionChecker";

    private TunerVersionChecker() {}

    /** @hide */
    @IntDef(prefix = "TUNER_VERSION_",
            value = {TUNER_VERSION_UNKNOWN, TUNER_VERSION_1_0, TUNER_VERSION_1_1,
                    TUNER_VERSION_2_0, TUNER_VERSION_3_0, TUNER_VERSION_4_0})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TunerVersion {}
    /**
     * Unknown Tuner version.
     */
    public static final int TUNER_VERSION_UNKNOWN = 0;
    /**
     * Tuner version 1.0.
     */
    public static final int TUNER_VERSION_1_0 = (1 << 16);
    /**
     * Tuner version 1.1.
     */
    public static final int TUNER_VERSION_1_1 = ((1 << 16) | 1);
    /**
     * Tuner version 2.0.
     */
    public static final int TUNER_VERSION_2_0 = (2 << 16);
    /**
     * Tuner version 3.0.
     */
    public static final int TUNER_VERSION_3_0 = (3 << 16);
    /**
     * Tuner version 4.0.
     */
    @FlaggedApi(Flags.FLAG_TUNER_W_APIS)
    public static final int TUNER_VERSION_4_0 = (4 << 16);

    /**
     * Get the current running Tuner version.
     *
     * @return Tuner version.
     */
    @TunerVersion
    public static int getTunerVersion() {
        return Tuner.getTunerVersion();
    }

    /**
     * Check if the current running Tuner version supports the given version.
     *
     * <p>Note that we treat different major versions as unsupported among each other. If any
     * feature could be supported across major versions, please use
     * {@link #isHigherOrEqualVersionTo(int)} to check.
     *
     * @param version the version to support.
     *
     * @return true if the current version is under the same major version as the given version
     * and has higher or the same minor version as the given version.
     * @hide
     */
    @TestApi
    public static boolean supportTunerVersion(@TunerVersion int version) {
        int currentVersion = Tuner.getTunerVersion();
        return isHigherOrEqualVersionTo(version)
                && (getMajorVersion(version) == getMajorVersion(currentVersion));
    }

    /**
     * Check if the current running Tuner version is higher than or equal to a given version.
     *
     * @param version the version to compare.
     *
     * @return true if the current version is higher or equal to the support version.
     * @hide
     */
    @TestApi
    public static boolean isHigherOrEqualVersionTo(@TunerVersion int version) {
        int currentVersion = Tuner.getTunerVersion();
        return currentVersion >= version;
    }

    /**
     * Get the major version from a version number.
     *
     * @param version the version to be checked.
     *
     * @return the major version number.
     * @hide
     */
    @TestApi
    public static int getMajorVersion(@TunerVersion int version) {
        return ((version & 0xFFFF0000) >>> 16);
    }

    /**
     * Get the major version from a version number.
     *
     * @param version the version to be checked.
     *
     * @return the minor version number.
     * @hide
     */
    @TestApi
    public static int getMinorVersion(@TunerVersion int version) {
        return (version & 0xFFFF);
    }

    /** @hide */
    public static boolean checkHigherOrEqualVersionTo(
            @TunerVersion int version, String methodName) {
        if (!TunerVersionChecker.isHigherOrEqualVersionTo(version)) {
            Log.e(TAG, "Current Tuner version "
                    + TunerVersionChecker.getMajorVersion(Tuner.getTunerVersion()) + "."
                    + TunerVersionChecker.getMinorVersion(Tuner.getTunerVersion())
                    + " does not support " + methodName + ".");
            return false;
        }
        return true;
    }

    /** @hide */
    public static boolean checkSupportVersion(@TunerVersion int version, String methodName) {
        if (!TunerVersionChecker.supportTunerVersion(version)) {
            Log.e(TAG, "Current Tuner version "
                    + TunerVersionChecker.getMajorVersion(Tuner.getTunerVersion()) + "."
                    + TunerVersionChecker.getMinorVersion(Tuner.getTunerVersion())
                    + " does not support " + methodName + ".");
            return false;
        }
        return true;
    }
}
