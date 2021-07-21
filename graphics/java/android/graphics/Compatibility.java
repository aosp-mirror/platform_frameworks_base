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

package android.graphics;

/**
 * Helper class for graphics classes to retrieve the targetSdkVersion, as
 * specified by the app.
 * @hide
 */
public final class Compatibility {
    private Compatibility() {}

    private static int sTargetSdkVersion = 0;

    /**
     * Exposed so that ActivityThread can set it correctly once when binding the
     * application. No other code should call this.
     * @hide
     */
    public static void setTargetSdkVersion(int targetSdkVersion) {
        sTargetSdkVersion = targetSdkVersion;
        Canvas.setCompatibilityVersion(targetSdkVersion);
    }

    /**
     * Public for access by other packages in the module (like android.graphics.drawable),
     * but should not be accessed outside the module.
     * @hide
     */
    public static int getTargetSdkVersion() {
        return sTargetSdkVersion;
    }
}
