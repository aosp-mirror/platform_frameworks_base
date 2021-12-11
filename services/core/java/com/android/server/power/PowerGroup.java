/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.power;

import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.view.Display;

/**
 * Used to store power related requests to every display in a
 * {@link com.android.server.display.DisplayGroup}.
 * For each {@link com.android.server.display.DisplayGroup} there exists a {@link PowerGroup}.
 * The mapping is tracked in {@link PowerManagerService}.
 * <p><b>Note:</b> Methods with the {@code *Locked} suffix require the
 * {@code PowerManagerService#mLock} to be held by the caller.
 */
public class PowerGroup {
    private static final String TAG = PowerGroup.class.getSimpleName();

    private final DisplayPowerRequest mDisplayPowerRequest;
    private final boolean mSupportsSandman;
    private final int mGroupId;

    // True if DisplayManagerService has applied all the latest display states that were requested
    // for this group
    private boolean mReady;
    // True if this group is in the process of powering on
    private boolean mPoweringOn;
    // True if this group is about to dream
    private boolean mIsSandmanSummoned;
    private int mUserActivitySummary;
    // The current wakefulness of this group
    private int mWakefulness;
    private int mWakeLockSummary;
    private long mLastPowerOnTime;
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;

    PowerGroup(int groupId, DisplayPowerRequest displayPowerRequest, int wakefulness, boolean ready,
            boolean supportsSandman) {
        this.mGroupId = groupId;
        this.mDisplayPowerRequest = displayPowerRequest;
        this.mWakefulness = wakefulness;
        this.mReady = ready;
        this.mSupportsSandman = supportsSandman;
    }

    PowerGroup() {
        this.mGroupId = Display.DEFAULT_DISPLAY_GROUP;
        this.mDisplayPowerRequest = new DisplayPowerRequest();
        this.mWakefulness = WAKEFULNESS_AWAKE;
        this.mReady = false;
        this.mSupportsSandman = true;
    }

    DisplayPowerRequest getDisplayPowerRequestLocked() {
        return mDisplayPowerRequest;
    }

    int getWakefulnessLocked() {
        return mWakefulness;
    }

    int getGroupId() {
        return mGroupId;
    }

    /**
     * Sets the {@code wakefulness} value for this {@link PowerGroup}.
     *
     * @return {@code true} if the wakefulness value was changed; {@code false} otherwise.
     */
    boolean setWakefulnessLocked(int newWakefulness) {
        if (mWakefulness != newWakefulness) {
            mWakefulness = newWakefulness;
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if every display in this group has its requested state matching
     * its actual state.
     */
    boolean isReadyLocked() {
        return mReady;
    }

    /**
     * Sets whether the displays of this group are all ready.
     *
     * <p>A display is ready if its reported
     * {@link DisplayManagerInternal.DisplayPowerCallbacks#onStateChanged() actual state} matches
     * its {@link DisplayManagerInternal#requestPowerState requested state}.
     *
     * @param isReady {@code true} if every display in the group is ready; otherwise {@code false}.
     * @return {@code true} if the ready state changed; otherwise {@code false}.
     */
    boolean setReadyLocked(boolean isReady) {
        if (mReady != isReady) {
            mReady = isReady;
            return true;
        }
        return false;
    }

    long getLastPowerOnTimeLocked() {
        return mLastPowerOnTime;
    }

    void setLastPowerOnTimeLocked(long time) {
        mLastPowerOnTime = time;
    }

    boolean isPoweringOnLocked() {
        return mPoweringOn;
    }

    void setIsPoweringOnLocked(boolean isPoweringOnNew) {
        mPoweringOn = isPoweringOnNew;
    }

    boolean isSandmanSummonedLocked() {
        return mIsSandmanSummoned;
    }

    /**
     * Sets whether or not the sandman is summoned for this {@link PowerGroup}.
     *
     * @param isSandmanSummoned {@code true} to summon the sandman; {@code false} to unsummon.
     */
    void setSandmanSummonedLocked(boolean isSandmanSummoned) {
        mIsSandmanSummoned = isSandmanSummoned;
    }

    long getLastUserActivityTimeLocked() {
        return mLastUserActivityTime;
    }

    void setLastUserActivityTimeLocked(long lastUserActivityTime) {
        mLastUserActivityTime = lastUserActivityTime;
    }

    public long getLastUserActivityTimeNoChangeLightsLocked() {
        return mLastUserActivityTimeNoChangeLights;
    }

    public void setLastUserActivityTimeNoChangeLightsLocked(long time) {
        mLastUserActivityTimeNoChangeLights = time;
    }

    public int getUserActivitySummaryLocked() {
        return mUserActivitySummary;
    }

    public void setUserActivitySummaryLocked(int summary) {
        mUserActivitySummary = summary;
    }

    public int getWakeLockSummaryLocked() {
        return mWakeLockSummary;
    }

    public void setWakeLockSummaryLocked(int summary) {
        mWakeLockSummary = summary;
    }

    /**
     * Whether or not this DisplayGroup supports dreaming.
     * @return {@code true} if this DisplayGroup supports dreaming; otherwise {@code false}.
     */
    public boolean supportsSandmanLocked() {
        return mSupportsSandman;
    }
}
