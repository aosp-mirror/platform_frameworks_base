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
import android.media.tv.tuner.TunerVersionChecker;

/**
 * ISDBT Capabilities.
 *
 * @hide
 */
@SystemApi
public class IsdbtFrontendCapabilities extends FrontendCapabilities {
    private final int mModeCap;
    private final int mBandwidthCap;
    private final int mModulationCap;
    private final int mCodeRateCap;
    private final int mGuardIntervalCap;
    private final int mTimeInterleaveCap;
    private final boolean mIsSegmentAutoSupported;
    private final boolean mIsFullSegmentSupported;

    private IsdbtFrontendCapabilities(int modeCap, int bandwidthCap, int modulationCap,
            int codeRateCap, int guardIntervalCap, int timeInterleaveCap,
            boolean isSegmentAutoSupported, boolean isFullSegmentSupported) {
        mModeCap = modeCap;
        mBandwidthCap = bandwidthCap;
        mModulationCap = modulationCap;
        mCodeRateCap = codeRateCap;
        mGuardIntervalCap = guardIntervalCap;
        mTimeInterleaveCap = timeInterleaveCap;
        mIsSegmentAutoSupported = isSegmentAutoSupported;
        mIsFullSegmentSupported = isFullSegmentSupported;
    }

    /**
     * Gets mode capability.
     */
    @IsdbtFrontendSettings.Mode
    public int getModeCapability() {
        return mModeCap;
    }
    /**
     * Gets bandwidth capability.
     */
    @IsdbtFrontendSettings.Bandwidth
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
    /**
     * Gets modulation capability.
     */
    @IsdbtFrontendSettings.Modulation
    public int getModulationCapability() {
        return mModulationCap;
    }
    /**
     * Gets code rate capability.
     */
    @DvbtFrontendSettings.CodeRate
    public int getCodeRateCapability() {
        return mCodeRateCap;
    }
    /**
     * Gets guard interval capability.
     */
    @DvbtFrontendSettings.GuardInterval
    public int getGuardIntervalCapability() {
        return mGuardIntervalCap;
    }
    /**
     * Gets time interleave mode capability.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version will
     * return {@link IsdbtFrontendSettings#TIME_INTERLEAVE_MODE_UNDEFINED}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @IsdbtFrontendSettings.TimeInterleaveMode
    public int getTimeInterleaveModeCapability() {
        return mTimeInterleaveCap;
    }
    /**
     * If auto segment is supported or not.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version will
     * return false.
     * Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public boolean isSegmentAutoSupported() {
        return mIsSegmentAutoSupported;
    }
    /**
     * If full segment is supported or not.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version will
     * return false.
     * Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public boolean isFullSegmentSupported() {
        return mIsFullSegmentSupported;
    }
}
