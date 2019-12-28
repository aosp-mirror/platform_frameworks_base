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

package android.media.tv.tuner;

import android.media.tv.tuner.TunerConstants.FrontendType;

/**
 * Frontend info.
 * @hide
 */
public class FrontendInfo {
    private final int mId;
    private final int mType;
    private final int mMinFrequency;
    private final int mMaxFrequency;
    private final int mMinSymbolRate;
    private final int mMaxSymbolRate;
    private final int mAcquireRange;
    private final int mExclusiveGroupId;
    private final int[] mStatusCaps;
    private final FrontendCapabilities mFrontendCap;

    FrontendInfo(int id, int type, int minFrequency, int maxFrequency, int minSymbolRate,
            int maxSymbolRate, int acquireRange, int exclusiveGroupId, int[] statusCaps,
            FrontendCapabilities frontendCap) {
        mId = id;
        mType = type;
        mMinFrequency = minFrequency;
        mMaxFrequency = maxFrequency;
        mMinSymbolRate = minSymbolRate;
        mMaxSymbolRate = maxSymbolRate;
        mAcquireRange = acquireRange;
        mExclusiveGroupId = exclusiveGroupId;
        mStatusCaps = statusCaps;
        mFrontendCap = frontendCap;
    }

    /** Gets frontend ID. */
    public int getId() {
        return mId;
    }
    /** Gets frontend type. */
    @FrontendType
    public int getType() {
        return mType;
    }
    /** Gets min frequency. */
    public int getMinFrequency() {
        return mMinFrequency;
    }
    /** Gets max frequency. */
    public int getMaxFrequency() {
        return mMaxFrequency;
    }
    /** Gets min symbol rate. */
    public int getMinSymbolRate() {
        return mMinSymbolRate;
    }
    /** Gets max symbol rate. */
    public int getMaxSymbolRate() {
        return mMaxSymbolRate;
    }
    /** Gets acquire range. */
    public int getAcquireRange() {
        return mAcquireRange;
    }
    /** Gets exclusive group ID. */
    public int getExclusiveGroupId() {
        return mExclusiveGroupId;
    }
    /** Gets status capabilities. */
    public int[] getStatusCapabilities() {
        return mStatusCaps;
    }
    /** Gets frontend capability. */
    public FrontendCapabilities getFrontendCapability() {
        return mFrontendCap;
    }
}
