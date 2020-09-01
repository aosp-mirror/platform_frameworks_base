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
 * ATSC-3 Capabilities.
 *
 * @hide
 */
@SystemApi
public class Atsc3FrontendCapabilities extends FrontendCapabilities {
    private final int mBandwidthCap;
    private final int mModulationCap;
    private final int mTimeInterleaveModeCap;
    private final int mCodeRateCap;
    private final int mFecCap;
    private final int mDemodOutputFormatCap;

    private Atsc3FrontendCapabilities(int bandwidthCap, int modulationCap,
            int timeInterleaveModeCap, int codeRateCap, int fecCap, int demodOutputFormatCap) {
        mBandwidthCap = bandwidthCap;
        mModulationCap = modulationCap;
        mTimeInterleaveModeCap = timeInterleaveModeCap;
        mCodeRateCap = codeRateCap;
        mFecCap = fecCap;
        mDemodOutputFormatCap = demodOutputFormatCap;
    }

    /**
     * Gets bandwidth capability.
     */
    @Atsc3FrontendSettings.Bandwidth
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
    /**
     * Gets modulation capability.
     */
    @Atsc3FrontendSettings.Modulation
    public int getModulationCapability() {
        return mModulationCap;
    }
    /**
     * Gets time interleave mod capability.
     */
    @Atsc3FrontendSettings.TimeInterleaveMode
    public int getTimeInterleaveModeCapability() {
        return mTimeInterleaveModeCap;
    }
    /**
     * Gets code rate capability.
     */
    @Atsc3FrontendSettings.CodeRate
    public int getPlpCodeRateCapability() {
        return mCodeRateCap;
    }
    /**
     * Gets FEC capability.
     */
    @Atsc3FrontendSettings.Fec
    public int getFecCapability() {
        return mFecCap;
    }
    /**
     * Gets demodulator output format capability.
     */
    @Atsc3FrontendSettings.DemodOutputFormat
    public int getDemodOutputFormatCapability() {
        return mDemodOutputFormatCap;
    }
}
