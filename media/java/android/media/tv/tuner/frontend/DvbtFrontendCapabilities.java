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
 * DVBT Capabilities.
 *
 * @hide
 */
@SystemApi
public class DvbtFrontendCapabilities extends FrontendCapabilities {
    private final int mTransmissionModeCap;
    private final int mBandwidthCap;
    private final int mConstellationCap;
    private final int mCodeRateCap;
    private final int mHierarchyCap;
    private final int mGuardIntervalCap;
    private final boolean mIsT2Supported;
    private final boolean mIsMisoSupported;

    private DvbtFrontendCapabilities(int transmissionModeCap, int bandwidthCap,
            int constellationCap, int codeRateCap, int hierarchyCap, int guardIntervalCap,
            boolean isT2Supported, boolean isMisoSupported) {
        mTransmissionModeCap = transmissionModeCap;
        mBandwidthCap = bandwidthCap;
        mConstellationCap = constellationCap;
        mCodeRateCap = codeRateCap;
        mHierarchyCap = hierarchyCap;
        mGuardIntervalCap = guardIntervalCap;
        mIsT2Supported = isT2Supported;
        mIsMisoSupported = isMisoSupported;
    }

    /**
     * Gets transmission mode capability.
     */
    @DvbtFrontendSettings.TransmissionMode
    public int getTransmissionModeCapability() {
        return mTransmissionModeCap;
    }
    /**
     * Gets bandwidth capability.
     */
    @DvbtFrontendSettings.Bandwidth
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
    /**
     * Gets constellation capability.
     */
    @DvbtFrontendSettings.Constellation
    public int getConstellationCapability() {
        return mConstellationCap;
    }
    /**
     * Gets code rate capability.
     */
    @DvbtFrontendSettings.CodeRate
    public int getCodeRateCapability() {
        return mCodeRateCap;
    }
    /**
     * Gets hierarchy capability.
     */
    @DvbtFrontendSettings.Hierarchy
    public int getHierarchyCapability() {
        return mHierarchyCap;
    }
    /**
     * Gets guard interval capability.
     */
    @DvbtFrontendSettings.GuardInterval
    public int getGuardIntervalCapability() {
        return mGuardIntervalCap;
    }
    /**
     * Returns whether T2 is supported.
     */
    public boolean isT2Supported() {
        return mIsT2Supported;
    }
    /**
     * Returns whether MISO is supported.
     */
    public boolean isMisoSupported() {
        return mIsMisoSupported;
    }
}
