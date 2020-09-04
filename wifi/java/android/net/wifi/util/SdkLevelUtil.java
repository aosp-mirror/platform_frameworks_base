/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi.util;

import android.os.Build;

/**
 * Utility to check the SDK version of the device that the code is running on.
 *
 * This can be used to disable new Wifi APIs added in Mainline updates on older SDK versions.
 *
 * @hide
 */
public class SdkLevelUtil {

    /** This class is instantiable to allow easy mocking. */
    public SdkLevelUtil() { }

    /** See {@link #isAtLeastS()}. This version is non-static to allow easy mocking. */
    public boolean isAtLeastSMockable() {
        return isAtLeastS();
    }

    /** Returns true if the Android platform SDK is at least "S", false otherwise. */
    public static boolean isAtLeastS() {
        // TODO(b/167575586): after S SDK finalization, this method should just be
        //  `return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;`

        // at least S: return true
        // this condition only evaluates to true after S SDK finalization when VERSION_CODES.S
        // is set to something like "31", before SDK finalization the value is "10000"
        // Note that Build.VERSION_CODES.S is inlined at compile time. If it's inlined to 10000,
        // this condition never evaluates to true.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return true;
        }

        // Assume for now that S = R + 1
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            return true;
        }

        // R: check CODENAME
        // Before S SDK finalization, SDK_INT = R = 30 i.e. remains on the previous version
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // CODENAME = "REL" on R release builds
            // CODENAME = "S" on S development builds
            return "S".equals(Build.VERSION.CODENAME);
        }

        // older than R: return false
        return false;
    }
}
