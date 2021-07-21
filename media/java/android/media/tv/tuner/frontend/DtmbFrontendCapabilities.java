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

package android.media.tv.tuner.frontend;

import android.annotation.SystemApi;

/**
 * DTMB Capabilities.
 *
 * <p>DTMB Frontend is only supported in Tuner HAL 1.1 or higher.
 * @hide
 */
@SystemApi
public final class DtmbFrontendCapabilities extends FrontendCapabilities {
    private final int mModulationCap;
    private final int mTransmissionModeCap;
    private final int mGuardIntervalCap;
    private final int mTimeInterleaveModeCap;
    private final int mCodeRateCap;
    private final int mBandwidthCap;

    private DtmbFrontendCapabilities(int modulationCap, int transmissionModeCap,
            int guardIntervalCap, int timeInterleaveModeCap, int codeRateCap, int bandwidthCap) {
        mModulationCap = modulationCap;
        mTransmissionModeCap = transmissionModeCap;
        mGuardIntervalCap = guardIntervalCap;
        mTimeInterleaveModeCap = timeInterleaveModeCap;
        mCodeRateCap = codeRateCap;
        mBandwidthCap = bandwidthCap;
    }

    /**
     * Gets modulation capability.
     *
     * @return the bit mask of all the supported modulations.
     */
    @DtmbFrontendSettings.Modulation
    public int getModulationCapability() {
        return mModulationCap;
    }

    /**
     * Gets Transmission Mode capability.
     *
     * @return the bit mask of all the supported transmission modes.
     */
    @DtmbFrontendSettings.TransmissionMode
    public int getTransmissionModeCapability() {
        return mTransmissionModeCap;
    }

    /**
     * Gets Guard Interval capability.
     *
     * @return the bit mask of all the supported guard intervals.
     */
    @DtmbFrontendSettings.GuardInterval
    public int getGuardIntervalCapability() {
        return mGuardIntervalCap;
    }

    /**
     * Gets Time Interleave Mode capability.
     *
     * @return the bit mask of all the supported time interleave modes.
     */
    @DtmbFrontendSettings.TimeInterleaveMode
    public int getTimeInterleaveModeCapability() {
        return mTimeInterleaveModeCap;
    }

    /**
     * Gets Code Rate capability.
     *
     * @return the bit mask of all the supported code rates.
     */
    @DtmbFrontendSettings.CodeRate
    public int getCodeRateCapability() {
        return mCodeRateCap;
    }

    /**
     * Gets Bandwidth capability.
     *
     * @return the bit mask of all the supported bandwidth.
     */
    @DtmbFrontendSettings.Bandwidth
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
}
