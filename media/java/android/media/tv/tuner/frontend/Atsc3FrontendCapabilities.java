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

/**
 * ATSC-3 Capabilities.
 * @hide
 */
public class Atsc3FrontendCapabilities extends FrontendCapabilities {
    private final int mBandwidthCap;
    private final int mModulationCap;
    private final int mTimeInterleaveModeCap;
    private final int mCodeRateCap;
    private final int mFecCap;
    private final int mDemodOutputFormatCap;

    Atsc3FrontendCapabilities(int bandwidthCap, int modulationCap, int timeInterleaveModeCap,
            int codeRateCap, int fecCap, int demodOutputFormatCap) {
        mBandwidthCap = bandwidthCap;
        mModulationCap = modulationCap;
        mTimeInterleaveModeCap = timeInterleaveModeCap;
        mCodeRateCap = codeRateCap;
        mFecCap = fecCap;
        mDemodOutputFormatCap = demodOutputFormatCap;
    }

    /** Gets bandwidth capability. */
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
    /** Gets modulation capability. */
    public int getModulationCapability() {
        return mModulationCap;
    }
    /** Gets time interleave mod capability. */
    public int getTimeInterleaveModeCapability() {
        return mTimeInterleaveModeCap;
    }
    /** Gets code rate capability. */
    public int getCodeRateCapability() {
        return mCodeRateCap;
    }
    /** Gets FEC capability. */
    public int getFecCapability() {
        return mFecCap;
    }
    /** Gets demodulator output format capability. */
    public int getDemodOutputFormatCapability() {
        return mDemodOutputFormatCap;
    }
}
