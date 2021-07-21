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
 * DVBC Capabilities.
 *
 * @hide
 */
@SystemApi
public class DvbcFrontendCapabilities extends FrontendCapabilities {
    private final int mModulationCap;
    private final long mFecCap;
    private final int mAnnexCap;

    private DvbcFrontendCapabilities(int modulationCap, long fecCap, int annexCap) {
        mModulationCap = modulationCap;
        mFecCap = fecCap;
        mAnnexCap = annexCap;
    }

    /**
     * Gets modulation capability.
     */
    @DvbcFrontendSettings.Modulation
    public int getModulationCapability() {
        return mModulationCap;
    }
    /**
     * Gets inner FEC capability.
     *
     * @deprecated Use {@link #getInnerFecCapability()} with long return value instead. This
     *             function returns the correct cap value when the value is not bigger than the max
     *             integer value. Otherwise it returns {@link FrontendSettings#FEC_UNDEFINED}.
     */
    @Deprecated
    @FrontendSettings.InnerFec
    public int getFecCapability() {
        if (mFecCap > Integer.MAX_VALUE) {
            return (int) FrontendSettings.FEC_UNDEFINED;
        }
        return (int) mFecCap;
    }
    /**
     * Gets code rate capability.
     */
    @FrontendSettings.InnerFec
    public long getCodeRateCapability() {
        return mFecCap;
    }
    /**
     * Gets annex capability.
     */
    @DvbcFrontendSettings.Annex
    public int getAnnexCapability() {
        return mAnnexCap;
    }
}
