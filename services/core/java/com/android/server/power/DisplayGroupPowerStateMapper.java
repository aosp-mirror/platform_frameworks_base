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

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import static com.android.server.power.DisplayGroupPowerStateMapper.DisplayGroupPowerChangeListener.DISPLAY_GROUP_ADDED;
import static com.android.server.power.DisplayGroupPowerStateMapper.DisplayGroupPowerChangeListener.DISPLAY_GROUP_CHANGED;
import static com.android.server.power.DisplayGroupPowerStateMapper.DisplayGroupPowerChangeListener.DISPLAY_GROUP_REMOVED;

import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManagerInternal;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.display.DisplayGroup;

/**
 * Responsible for creating {@link DisplayPowerRequest}s and associating them with
 * {@link com.android.server.display.DisplayGroup}s.
 *
 * Each {@link com.android.server.display.DisplayGroup} has a single {@link DisplayPowerRequest}
 * which is used to request power state changes to every display in the group.
 */
public class DisplayGroupPowerStateMapper {

    private static final String TAG = "DisplayPowerRequestMapper";

    /** Lock obtained from {@link PowerManagerService}. */
    private final Object mLock;

    /** Listener to inform of changes to display groups. */
    private final DisplayGroupPowerChangeListener mListener;

    /** A mapping from DisplayGroup Id to DisplayGroup information. */
    @GuardedBy("mLock")
    private final SparseArray<DisplayGroupInfo> mDisplayGroupInfos = new SparseArray<>();

    /** A cached array of DisplayGroup Ids. */
    @GuardedBy("mLock")
    private int[] mDisplayGroupIds;

    private final DisplayManagerInternal.DisplayGroupListener mDisplayGroupListener =
            new DisplayManagerInternal.DisplayGroupListener() {
                @Override
                public void onDisplayGroupAdded(int groupId) {
                    synchronized (mLock) {
                        if (mDisplayGroupInfos.contains(groupId)) {
                            Slog.e(TAG, "Tried to add already existing group:" + groupId);
                            return;
                        }
                        // For now, only the default group supports sandman (dream/AOD).
                        final boolean supportsSandman = groupId == Display.DEFAULT_DISPLAY_GROUP;
                        final DisplayGroupInfo displayGroupInfo = new DisplayGroupInfo(
                                new DisplayPowerRequest(),
                                getGlobalWakefulnessLocked(),
                                /* ready= */ false,
                                supportsSandman);
                        mDisplayGroupInfos.append(groupId, displayGroupInfo);
                        mDisplayGroupIds = ArrayUtils.appendInt(mDisplayGroupIds, groupId);
                        mListener.onDisplayGroupEventLocked(DISPLAY_GROUP_ADDED, groupId);
                    }
                }

                @Override
                public void onDisplayGroupRemoved(int groupId) {
                    synchronized (mLock) {
                        if (!mDisplayGroupInfos.contains(groupId)) {
                            Slog.e(TAG, "Tried to remove non-existent group:" + groupId);
                            return;
                        }
                        mDisplayGroupInfos.delete(groupId);
                        mDisplayGroupIds = ArrayUtils.removeInt(mDisplayGroupIds, groupId);
                        mListener.onDisplayGroupEventLocked(DISPLAY_GROUP_REMOVED, groupId);
                    }
                }

                @Override
                public void onDisplayGroupChanged(int groupId) {
                    synchronized (mLock) {
                        mListener.onDisplayGroupEventLocked(DISPLAY_GROUP_CHANGED, groupId);
                    }
                }
            };

    DisplayGroupPowerStateMapper(Object lock, DisplayManagerInternal displayManagerInternal,
            DisplayGroupPowerChangeListener listener) {
        mLock = lock;
        mListener = listener;
        displayManagerInternal.registerDisplayGroupListener(mDisplayGroupListener);

        final DisplayGroupInfo displayGroupInfo = new DisplayGroupInfo(
                new DisplayPowerRequest(), WAKEFULNESS_AWAKE, /* ready= */
                false, /* supportsSandman= */ true);
        mDisplayGroupInfos.append(Display.DEFAULT_DISPLAY_GROUP, displayGroupInfo);
        mDisplayGroupIds = new int[]{Display.DEFAULT_DISPLAY_GROUP};
    }

    DisplayPowerRequest getPowerRequestLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).displayPowerRequest;
    }

    int[] getDisplayGroupIdsLocked() {
        return mDisplayGroupIds;
    }

    int getDisplayGroupCountLocked() {
        return mDisplayGroupIds.length;
    }

    int getWakefulnessLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).wakefulness;
    }

    void setLastPowerOnTimeLocked(int groupId, long eventTime) {
        mDisplayGroupInfos.get(groupId).lastPowerOnTime = eventTime;
    }

    long getLastPowerOnTimeLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).lastPowerOnTime;
    }

    void setPoweringOnLocked(int groupId, boolean poweringOn) {
        mDisplayGroupInfos.get(groupId).poweringOn = poweringOn;
    }

    boolean isPoweringOnLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).poweringOn;
    }

    /**
     * Returns the amalgamated wakefulness of all {@link DisplayGroup DisplayGroups}.
     *
     * <p>This will be the highest wakeful state of all {@link DisplayGroup DisplayGroups}; ordered
     * from highest to lowest:
     * <ol>
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_AWAKE}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_DREAMING}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_DOZING}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_ASLEEP}
     * </ol>
     */
    int getGlobalWakefulnessLocked() {
        final int size = mDisplayGroupInfos.size();
        int deviceWakefulness = WAKEFULNESS_ASLEEP;
        for (int i = 0; i < size; i++) {
            final int wakefulness = mDisplayGroupInfos.valueAt(i).wakefulness;
            if (wakefulness == WAKEFULNESS_AWAKE) {
                return WAKEFULNESS_AWAKE;
            } else if (wakefulness == WAKEFULNESS_DREAMING
                    && (deviceWakefulness == WAKEFULNESS_ASLEEP
                    || deviceWakefulness == WAKEFULNESS_DOZING)) {
                deviceWakefulness = WAKEFULNESS_DREAMING;
            } else if (wakefulness == WAKEFULNESS_DOZING
                    && deviceWakefulness == WAKEFULNESS_ASLEEP) {
                deviceWakefulness = WAKEFULNESS_DOZING;
            }
        }

        return deviceWakefulness;
    }

    /**
     * Sets the {@code wakefulness} value for the {@link DisplayGroup} specified by the provided
     * {@code groupId}.
     *
     * @return {@code true} if the wakefulness value was changed; {@code false} otherwise.
     */
    boolean setWakefulnessLocked(int groupId, int wakefulness) {
        final DisplayGroupInfo displayGroupInfo = mDisplayGroupInfos.get(groupId);
        if (displayGroupInfo.wakefulness != wakefulness) {
            displayGroupInfo.wakefulness = wakefulness;
            return true;
        }

        return false;
    }

    boolean isSandmanSummoned(int groupId) {
        return mDisplayGroupInfos.get(groupId).sandmanSummoned;
    }

    boolean isSandmanSupported(int groupId) {
        return mDisplayGroupInfos.get(groupId).supportsSandman;
    }

    /**
     * Sets whether or not the sandman is summoned for the given {@code groupId}.
     *
     * @param groupId         Signifies the DisplayGroup for which to summon or unsummon the
     *                        sandman.
     * @param sandmanSummoned {@code true} to summon the sandman; {@code false} to unsummon.
     */
    void setSandmanSummoned(int groupId, boolean sandmanSummoned) {
        final DisplayGroupInfo displayGroupInfo = mDisplayGroupInfos.get(groupId);
        displayGroupInfo.sandmanSummoned = displayGroupInfo.supportsSandman && sandmanSummoned;
    }

    /**
     * Returns {@code true} if every display in the specified group has its requested state matching
     * its actual state.
     *
     * @param groupId The identifier for the display group to check for readiness.
     */
    boolean isReady(int groupId) {
        return mDisplayGroupInfos.get(groupId).ready;
    }

    /** Returns {@code true} if every display has its requested state matching its actual state. */
    boolean areAllDisplaysReadyLocked() {
        final int size = mDisplayGroupInfos.size();
        for (int i = 0; i < size; i++) {
            if (!mDisplayGroupInfos.valueAt(i).ready) {
                return false;
            }
        }

        return true;
    }

    /**
     * Sets whether the displays specified by the provided {@code groupId} are all ready.
     *
     * <p>A display is ready if its reported
     * {@link DisplayManagerInternal.DisplayPowerCallbacks#onStateChanged() actual state} matches
     * its {@link DisplayManagerInternal#requestPowerState requested state}.
     *
     * @param groupId The identifier for the display group.
     * @param ready   {@code true} if every display in the group is ready; otherwise {@code false}.
     * @return {@code true} if the ready state changed; otherwise {@code false}.
     */
    boolean setDisplayGroupReadyLocked(int groupId, boolean ready) {
        final DisplayGroupInfo displayGroupInfo = mDisplayGroupInfos.get(groupId);
        if (displayGroupInfo.ready != ready) {
            displayGroupInfo.ready = ready;
            return true;
        }

        return false;
    }

    long getLastUserActivityTimeLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).lastUserActivityTime;
    }

    long getLastUserActivityTimeNoChangeLightsLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).lastUserActivityTimeNoChangeLights;
    }

    int getUserActivitySummaryLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).userActivitySummary;
    }

    void setLastUserActivityTimeLocked(int groupId, long time) {
        mDisplayGroupInfos.get(groupId).lastUserActivityTime = time;
    }

    void setLastUserActivityTimeNoChangeLightsLocked(int groupId, long time) {
        mDisplayGroupInfos.get(groupId).lastUserActivityTimeNoChangeLights = time;
    }

    void setUserActivitySummaryLocked(int groupId, int summary) {
        mDisplayGroupInfos.get(groupId).userActivitySummary = summary;
    }

    int getWakeLockSummaryLocked(int groupId) {
        return mDisplayGroupInfos.get(groupId).wakeLockSummary;
    }

    void setWakeLockSummaryLocked(int groupId, int summary) {
        mDisplayGroupInfos.get(groupId).wakeLockSummary = summary;
    }

    /**
     * Interface through which an interested party may be informed of {@link DisplayGroup} events.
     */
    interface DisplayGroupPowerChangeListener {
        int DISPLAY_GROUP_ADDED = 0;
        int DISPLAY_GROUP_REMOVED = 1;
        int DISPLAY_GROUP_CHANGED = 2;

        void onDisplayGroupEventLocked(int event, int groupId);
    }

    private static final class DisplayGroupInfo {
        public final DisplayPowerRequest displayPowerRequest;
        public int wakefulness;
        public boolean ready;
        public long lastPowerOnTime;
        boolean poweringOn;
        public boolean sandmanSummoned;
        public long lastUserActivityTime;
        public long lastUserActivityTimeNoChangeLights;
        public int userActivitySummary;
        public int wakeLockSummary;

        /** {@code true} if this DisplayGroup supports dreaming; otherwise {@code false}. */
        public boolean supportsSandman;

        DisplayGroupInfo(DisplayPowerRequest displayPowerRequest, int wakefulness, boolean ready,
                boolean supportsSandman) {
            this.displayPowerRequest = displayPowerRequest;
            this.wakefulness = wakefulness;
            this.ready = ready;
            this.supportsSandman = supportsSandman;
        }
    }
}
