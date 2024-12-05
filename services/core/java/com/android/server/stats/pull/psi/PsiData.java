/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.stats.pull.psi;

/**
 * Wraps PSI (Pressure Stall Information) corresponding to a system resource. See more details about
 * PSI, see https://docs.kernel.org/accounting/psi.html#psi-pressure-stall-information.
 */
public class PsiData {
    public enum ResourceType {
        CPU,
        MEMORY,
        IO
    }

    static class AppsStallInfo {

        /** Past 10s average % of wasted CPU cycles when apps tasks are stalled on mResourceType.*/
        private final float mAvg10SecPercentage;

        /** Past 60s average % of wasted CPU cycles when apps tasks are stalled on mResourceType.*/
        private final float mAvg60SecPercentage;

        /** Past 300s average % of wasted CPU cycles when apps tasks are stalled on mResourceType.*/
        private final float mAvg300SecPercentage;

        /** Total number of microseconds that apps tasks are stalled on mResourceType.*/
        private final long mTotalUsec;

        AppsStallInfo(
                float avg10SecPercentage, float avg60SecPercentage,
                float avg300SecPercentage, long totalUsec) {
            mAvg10SecPercentage = avg10SecPercentage;
            mAvg60SecPercentage = avg60SecPercentage;
            mAvg300SecPercentage = avg300SecPercentage;
            mTotalUsec = totalUsec;
        }
    }

    /** The system resource type of this {@code PsiData}. */
    private final ResourceType mResourceType;

    /** Info on some tasks are stalled on mResourceType. */
    private final AppsStallInfo mSomeAppsStallInfo;

    /**
     * Info on all non-idle tasks are stalled on mResourceType. For the CPU ResourceType,
     * all fields will always be 0 as it's undefined.
     */
    private final AppsStallInfo mFullAppsStallInfo;

    PsiData(
            ResourceType resourceType,
            AppsStallInfo someAppsStallInfo,
            AppsStallInfo fullAppsStallInfo) {
        mResourceType = resourceType;
        mSomeAppsStallInfo = someAppsStallInfo;
        mFullAppsStallInfo = fullAppsStallInfo;
    }

    public ResourceType getResourceType() {
        return mResourceType;
    }

    public float getSomeAvg10SecPercentage() {
        return mSomeAppsStallInfo.mAvg10SecPercentage; }

    public float getSomeAvg60SecPercentage() {
        return mSomeAppsStallInfo.mAvg60SecPercentage; }

    public float getSomeAvg300SecPercentage() {
        return mSomeAppsStallInfo.mAvg300SecPercentage; }

    public long getSomeTotalUsec() {
        return mSomeAppsStallInfo.mTotalUsec;
    }

    public float getFullAvg10SecPercentage() {
        return mFullAppsStallInfo.mAvg10SecPercentage;
    }

    public float getFullAvg60SecPercentage() {
        return mFullAppsStallInfo.mAvg60SecPercentage;
    }

    public float getFullAvg300SecPercentage() {
        return mFullAppsStallInfo.mAvg300SecPercentage; }

    public long getFullTotalUsec() {
        return mFullAppsStallInfo.mTotalUsec;
    }
}
