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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.tuner.frontend.FrontendSettings.Type;
import android.media.tv.tuner.frontend.FrontendStatus.FrontendStatusType;
import android.util.Range;

/**
 * This class is used to specify meta information of a frontend.
 *
 * @hide
 */
@SystemApi
public class FrontendInfo {
    private final int mId;
    private final int mType;
    private final Range<Integer> mFrequencyRange;
    private final Range<Integer> mSymbolRateRange;
    private final int mAcquireRange;
    private final int mExclusiveGroupId;
    private final int[] mStatusCaps;
    private final FrontendCapabilities mFrontendCap;

    private FrontendInfo(int id, int type, int minFrequency, int maxFrequency, int minSymbolRate,
            int maxSymbolRate, int acquireRange, int exclusiveGroupId, int[] statusCaps,
            FrontendCapabilities frontendCap) {
        mId = id;
        mType = type;
        mFrequencyRange = new Range<>(minFrequency, maxFrequency);
        mSymbolRateRange = new Range<>(minSymbolRate, maxSymbolRate);
        mAcquireRange = acquireRange;
        mExclusiveGroupId = exclusiveGroupId;
        mStatusCaps = statusCaps;
        mFrontendCap = frontendCap;
    }

    /**
     * Gets frontend ID.
     */
    public int getId() {
        return mId;
    }
    /**
     * Gets frontend type.
     */
    @Type
    public int getType() {
        return mType;
    }

    /**
     * Gets supported frequency range in Hz.
     */
    @NonNull
    public Range<Integer> getFrequencyRange() {
        return mFrequencyRange;
    }

    /**
     * Gets symbol rate range in symbols per second.
     */
    @NonNull
    public Range<Integer> getSymbolRateRange() {
        return mSymbolRateRange;
    }

    /**
     * Gets acquire range in Hz.
     *
     * <p>The maximum frequency difference the frontend can detect.
     */
    public int getAcquireRange() {
        return mAcquireRange;
    }
    /**
     * Gets exclusive group ID.
     *
     * <p>Frontends with the same exclusive group ID indicates they can't function at same time. For
     * instance, they share some hardware modules.
     */
    public int getExclusiveGroupId() {
        return mExclusiveGroupId;
    }
    /**
     * Gets status capabilities.
     *
     * @return An array of supported status types.
     */
    @FrontendStatusType
    @NonNull
    public int[] getStatusCapabilities() {
        return mStatusCaps;
    }
    /**
     * Gets frontend capabilities.
     */
    @NonNull
    public FrontendCapabilities getFrontendCapabilities() {
        return mFrontendCap;
    }
}
