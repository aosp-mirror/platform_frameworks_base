/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.SystemApi;

/**
 * Capabilities for analog tuners.
 *
 * @hide
 */
@SystemApi
public class AnalogFrontendCapabilities extends FrontendCapabilities {
    @AnalogFrontendSettings.SignalType
    private final int mTypeCap;
    @AnalogFrontendSettings.SifStandard
    private final int mSifStandardCap;

    // Called by JNI code.
    private AnalogFrontendCapabilities(int typeCap, int sifStandardCap) {
        mTypeCap = typeCap;
        mSifStandardCap = sifStandardCap;
    }

    /**
     * Gets analog signal type capability.
     */
    @AnalogFrontendSettings.SignalType
    public int getSignalTypeCapability() {
        return mTypeCap;
    }
    /**
     * Gets Standard Interchange Format (SIF) capability.
     */
    @AnalogFrontendSettings.SifStandard
    public int getSifStandardCapability() {
        return mSifStandardCap;
    }
}
