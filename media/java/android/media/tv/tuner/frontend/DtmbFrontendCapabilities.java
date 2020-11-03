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
     * @return full modulation capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.Modulation} is true, then that modulation is supported.
     */
    @DtmbFrontendSettings.Modulation
    public int getModulationCapability() {
        return mModulationCap;
    }

    /**
     * Gets Transmission Mode capability.
     *
     * @return full Transmission Mode capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.TransmissionMode} is true, then that transmission mode
     * is supported.
     */
    @DtmbFrontendSettings.TransmissionMode
    public int getTransmissionModeCapability() {
        return mTransmissionModeCap;
    }

    /**
     * Gets Guard Interval capability.
     *
     * @return full Guard Interval capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.GuardInterval} is true, then that Guard Interval is
     * supported.
     */
    @DtmbFrontendSettings.GuardInterval
    public int getGuardIntervalCapability() {
        return mGuardIntervalCap;
    }

    /**
     * Gets Time Interleave Mode capability.
     *
     * @return full Time Interleave Mode capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.TimeInterleaveMode} is true, then that Time Interleave
     * Mode is supported.
     */
    @DtmbFrontendSettings.TimeInterleaveMode
    public int getTimeInterleaveModeCapability() {
        return mTimeInterleaveModeCap;
    }

    /**
     * Gets Code Rate capability.
     *
     * @return full Code Rate capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.CodeRate} is true, then that Code Rate is supported.
     */
    @DtmbFrontendSettings.CodeRate
    public int getCodeRateCapability() {
        return mCodeRateCap;
    }

    /**
     * Gets Bandwidth capability.
     *
     * @return full Bandwidth capabilies. If the caps bitwise AND with any value from
     * bit masks {@link DtmbFrontendSettings.Bandwidth} is true, then that Bandwidth is supported.
     */
    @DtmbFrontendSettings.Bandwidth
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
}
