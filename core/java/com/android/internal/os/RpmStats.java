/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.os;

import android.util.ArrayMap;

import java.util.Map;

/**
 * Container for Resource Power Manager states and their data.
 * Values can be populated by the BatteryStatsService.fillLowPowerStats jni function.
 */
public final class RpmStats {
    public Map<String, PowerStatePlatformSleepState> mPlatformLowPowerStats = new ArrayMap<>();
    public Map<String, PowerStateSubsystem> mSubsystemLowPowerStats = new ArrayMap<>();

    /**
     * Finds the PowerStatePlatformSleepState with the given name (creating it if it doesn't exist),
     * updates its timeMs and count, and returns it.
     */
    @SuppressWarnings("unused")
    public PowerStatePlatformSleepState getAndUpdatePlatformState(
            String name, long timeMs, int count) {

        PowerStatePlatformSleepState e = mPlatformLowPowerStats.get(name);
        if (e == null) {
            e = new PowerStatePlatformSleepState();
            mPlatformLowPowerStats.put(name, e);
        }
        e.mTimeMs = timeMs;
        e.mCount = count;
        return e;
    }

    /**
     * Returns the PowerStateSubsystem with the given name (creating it if it doesn't exist).
     */
    public PowerStateSubsystem getSubsystem(String name) {
        PowerStateSubsystem e = mSubsystemLowPowerStats.get(name);
        if (e == null) {
            e = new PowerStateSubsystem();
            mSubsystemLowPowerStats.put(name, e);
        }
        return e;
    }

    /** Represents a subsystem state or a platform voter. */
    public static class PowerStateElement {
        public long mTimeMs; // totalTimeInMsecVotedForSinceBoot
        public int mCount; // totalNumberOfTimesVotedSinceBoot

        private PowerStateElement(long timeMs, int count) {
            this.mTimeMs = timeMs;
            this.mCount = count;
        }
    }

    /** Represents a PowerStatePlatformSleepState, per hardware/interfaces/power/1.0/types.hal */
    public static class PowerStatePlatformSleepState {
        public long mTimeMs; // residencyInMsecSinceBoot
        public int mCount; // totalTransitions
        public Map<String, PowerStateElement> mVoters = new ArrayMap<>(); // voters for this platform-level sleep state

        /**
         * Updates (creating if necessary) the voter with the given name, with the given timeMs and
         * count.
         */
        @SuppressWarnings("unused")
        public void putVoter(String name, long timeMs, int count) {
            PowerStateElement e = mVoters.get(name);
            if (e == null) {
                mVoters.put(name, new PowerStateElement(timeMs, count));
            } else {
                e.mTimeMs = timeMs;
                e.mCount = count;
            }
        }
    }

    /** Represents a PowerStateSubsystem, per hardware/interfaces/power/1.1/types.hal */
    public static class PowerStateSubsystem {
        public Map<String, PowerStateElement> mStates = new ArrayMap<>(); // sleep states supported by this susbsystem

        /**
         * Updates (creating if necessary) the subsystem state with the given name, with the given
         * timeMs and count.
         */
        @SuppressWarnings("unused")
        public void putState(String name, long timeMs, int count) {
            PowerStateElement e = mStates.get(name);
            if (e == null) {
                mStates.put(name, new PowerStateElement(timeMs, count));
            } else {
                e.mTimeMs = timeMs;
                e.mCount = count;
            }
        }
    }
}
