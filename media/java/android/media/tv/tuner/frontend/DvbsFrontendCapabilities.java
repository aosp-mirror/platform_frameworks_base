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
 * DVBS Capabilities.
 * @hide
 */
public class DvbsFrontendCapabilities extends FrontendCapabilities {
    private final int mModulationCap;
    private final long mInnerFecCap;
    private final int mStandard;

    DvbsFrontendCapabilities(int modulationCap, long innerFecCap, int standard) {
        mModulationCap = modulationCap;
        mInnerFecCap = innerFecCap;
        mStandard = standard;
    }

    /** Gets modulation capability. */
    public int getModulationCapability() {
        return mModulationCap;
    }
    /** Gets inner FEC capability. */
    public long getInnerFecCapability() {
        return mInnerFecCap;
    }
    /** Gets DVBS standard capability. */
    public int getStandardCapability() {
        return mStandard;
    }
}
