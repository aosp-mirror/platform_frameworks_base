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
 * ISDBC Capabilities.
 * @hide
 */
public class IsdbcFrontendCapabilities extends FrontendCapabilities {
    private final int mModeCap;
    private final int mBandwidthCap;
    private final int mModulationCap;
    private final int mCoderateCap;
    private final int mGuardIntervalCap;

    IsdbcFrontendCapabilities(int modeCap, int bandwidthCap, int modulationCap, int coderateCap,
            int guardIntervalCap) {
        mModeCap = modeCap;
        mBandwidthCap = bandwidthCap;
        mModulationCap = modulationCap;
        mCoderateCap = coderateCap;
        mGuardIntervalCap = guardIntervalCap;
    }

    /** Gets mode capability. */
    public int getModeCapability() {
        return mModeCap;
    }
    /** Gets bandwidth capability. */
    public int getBandwidthCapability() {
        return mBandwidthCap;
    }
    /** Gets modulation capability. */
    public int getModulationCapability() {
        return mModulationCap;
    }
    /** Gets code rate capability. */
    public int getCodeRateCapability() {
        return mCoderateCap;
    }
    /** Gets guard interval capability. */
    public int getGuardIntervalCapability() {
        return mGuardIntervalCap;
    }
}
