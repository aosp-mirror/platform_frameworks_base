/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppBatteryTracker.BATTERY_USAGE_NONE;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.STATE_TYPE_NUM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.AppBatteryExemptionTracker.AppBatteryExemptionPolicy;
import com.android.server.am.AppBatteryExemptionTracker.UidBatteryStates;
import com.android.server.am.AppBatteryTracker.AppBatteryPolicy;
import com.android.server.am.AppBatteryTracker.BatteryUsage;
import com.android.server.am.AppBatteryTracker.ImmutableBatteryUsage;
import com.android.server.am.AppRestrictionController.TrackerType;
import com.android.server.am.BaseAppStateTimeEvents.BaseTimeEvent;
import com.android.server.am.BaseAppStateTracker.Injector;
import com.android.server.am.BaseAppStateTracker.StateListener;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * A helper class to track the current drains that should be excluded from the current drain
 * accounting, examples are media playback, location sharing, etc.
 *
 * <p>
 * Note: as the {@link AppBatteryTracker#getUidBatteryUsage} could return the battery usage data
 * from most recent polling due to throttling, the battery usage of a certain event here
 * would NOT be the exactly same amount that it actually costs.
 * </p>
 */
final class AppBatteryExemptionTracker
        extends BaseAppStateDurationsTracker<AppBatteryExemptionPolicy, UidBatteryStates>
        implements BaseAppStateEvents.Factory<UidBatteryStates>, StateListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppBatteryExemptionTracker" : TAG_AM;

    private static final boolean DEBUG_BACKGROUND_BATTERY_EXEMPTION_TRACKER = false;

    // As it's a UID-based tracker, anywhere which requires a package name, use this default name.
    static final String DEFAULT_NAME = "";

    // As it's a UID-based tracker, while the state change event it receives could be
    // in the combination of UID + package name, we'd have to leverage each package's state.
    @GuardedBy("mLock")
    private UidProcessMap<Integer> mUidPackageStates = new UidProcessMap<>();

    AppBatteryExemptionTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppBatteryExemptionTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppBatteryExemptionPolicy>> injector,
            Object outerContext) {
        super(context, controller, injector, outerContext);
        mInjector.setPolicy(new AppBatteryExemptionPolicy(mInjector, this));
    }

    @Override
    @TrackerType int getType() {
        return AppRestrictionController.TRACKER_TYPE_BATTERY_EXEMPTION;
    }

    @Override
    void onSystemReady() {
        super.onSystemReady();
        mAppRestrictionController.forEachTracker(tracker -> {
            tracker.registerStateListener(this);
        });
    }

    @Override
    public UidBatteryStates createAppStateEvents(int uid, String packageName) {
        return new UidBatteryStates(uid, TAG, mInjector.getPolicy());
    }

    @Override
    public UidBatteryStates createAppStateEvents(UidBatteryStates other) {
        return new UidBatteryStates(other);
    }

    @Override
    public void onStateChange(int uid, String packageName, boolean start, long now, int stateType) {
        if (!mInjector.getPolicy().isEnabled()) {
            return;
        }
        final ImmutableBatteryUsage batteryUsage = mAppRestrictionController
                .getUidBatteryUsage(uid);
        final int stateTypeIndex = stateTypeToIndex(stateType);
        synchronized (mLock) {
            final SparseArray<ArrayMap<String, Integer>> map = mUidPackageStates.getMap();
            ArrayMap<String, Integer> pkgsStates = map.get(uid);
            if (pkgsStates == null) {
                pkgsStates = new ArrayMap<>();
                map.put(uid, pkgsStates);
            }
            int states = 0;
            int indexOfPkg = pkgsStates.indexOfKey(packageName);
            if (indexOfPkg >= 0) {
                states = pkgsStates.valueAt(indexOfPkg);
            } else {
                pkgsStates.put(packageName, 0);
                indexOfPkg = pkgsStates.indexOfKey(packageName);
            }
            boolean addEvent = false;
            if (start) {
                // Check if there is another package within this UID with this type of event start.
                boolean alreadyStarted = false;
                for (int i = pkgsStates.size() - 1; i >= 0; i--) {
                    final int s = pkgsStates.valueAt(i);
                    if ((s & stateType) != 0) {
                        alreadyStarted = true;
                        break;
                    }
                }
                pkgsStates.setValueAt(indexOfPkg, states | stateType);
                if (!alreadyStarted) {
                    // This is the first package within this UID with this type of event start.
                    addEvent = true;
                }
            } else {
                states &= ~stateType;
                pkgsStates.setValueAt(indexOfPkg, states);
                boolean allStopped = true;
                for (int i = pkgsStates.size() - 1; i >= 0; i--) {
                    final int s = pkgsStates.valueAt(i);
                    if ((s & stateType) != 0) {
                        allStopped = false;
                        break;
                    }
                }
                if (allStopped) {
                    // None of the packages in this UID has an active event of this type.
                    addEvent = true;
                }
                if (states == 0) { // None of the states of this package are active, prune it.
                    pkgsStates.removeAt(indexOfPkg);
                    if (pkgsStates.size() == 0) {
                        map.remove(uid);
                    }
                }
            }
            if (addEvent) {
                UidBatteryStates pkg = mPkgEvents.get(uid, DEFAULT_NAME);
                if (pkg == null) {
                    pkg = createAppStateEvents(uid, DEFAULT_NAME);
                    mPkgEvents.put(uid, DEFAULT_NAME, pkg);
                }
                pkg.addEvent(start, now, batteryUsage, stateTypeIndex);
            }
        }
    }

    @VisibleForTesting
    @Override
    void reset() {
        super.reset();
        synchronized (mLock) {
            mUidPackageStates.clear();
        }
    }

    private void onTrackerEnabled(boolean enabled) {
        if (!enabled) {
            synchronized (mLock) {
                mPkgEvents.clear();
                mUidPackageStates.clear();
            }
        }
    }

    /**
     * @return The to-be-exempted battery usage of the given UID in the given duration; it could
     *         be considered as "exempted" due to various use cases, i.e. media playback.
     */
    ImmutableBatteryUsage getUidBatteryExemptedUsageSince(int uid, long since, long now,
            int types) {
        if (!mInjector.getPolicy().isEnabled()) {
            return BATTERY_USAGE_NONE;
        }
        Pair<ImmutableBatteryUsage, ImmutableBatteryUsage> result;
        synchronized (mLock) {
            final UidBatteryStates pkg = mPkgEvents.get(uid, DEFAULT_NAME);
            if (pkg == null) {
                return BATTERY_USAGE_NONE;
            }
            result = pkg.getBatteryUsageSince(since, now, types);
        }
        if (!result.second.isEmpty()) {
            // We have an open event (just start, no stop), get the battery usage till now.
            final ImmutableBatteryUsage batteryUsage = mAppRestrictionController
                    .getUidBatteryUsage(uid);
            return result.first.mutate().add(batteryUsage).subtract(result.second).unmutate();
        }
        return result.first;
    }

    static final class UidBatteryStates extends BaseAppStateDurations<UidStateEventWithBattery> {
        UidBatteryStates(int uid, @NonNull String tag,
                @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
            super(uid, DEFAULT_NAME, STATE_TYPE_NUM, tag, maxTrackingDurationConfig);
        }

        UidBatteryStates(@NonNull UidBatteryStates other) {
            super(other);
        }

        /**
         * @param start {@code true} if it's a start event.
         * @param now   The timestamp when this event occurred.
         * @param batteryUsage The background current drain since the system boots.
         * @param eventType One of STATE_TYPE_INDEX_* defined in the class BaseAppStateTracker.
         */
        void addEvent(boolean start, long now, ImmutableBatteryUsage batteryUsage, int eventType) {
            if (start) {
                addEvent(start, new UidStateEventWithBattery(start, now, batteryUsage, null),
                        eventType);
            } else {
                final UidStateEventWithBattery last = getLastEvent(eventType);
                if (last == null || !last.isStart()) {
                    if (DEBUG_BACKGROUND_BATTERY_EXEMPTION_TRACKER) {
                        Slog.wtf(TAG, "Unexpected stop event " + eventType);
                    }
                    return;
                }
                addEvent(start, new UidStateEventWithBattery(start, now,
                        batteryUsage.mutate().subtract(last.getBatteryUsage()).unmutate(), last),
                        eventType);
            }
        }

        UidStateEventWithBattery getLastEvent(int eventType) {
            return mEvents[eventType] != null ? mEvents[eventType].peekLast() : null;
        }

        private Pair<ImmutableBatteryUsage, ImmutableBatteryUsage> getBatteryUsageSince(long since,
                long now, LinkedList<UidStateEventWithBattery> events) {
            if (events == null || events.size() == 0) {
                return Pair.create(BATTERY_USAGE_NONE, BATTERY_USAGE_NONE);
            }
            final BatteryUsage batteryUsage = new BatteryUsage();
            UidStateEventWithBattery lastEvent = null;
            for (UidStateEventWithBattery event : events) {
                lastEvent = event;
                if (event.getTimestamp() < since || event.isStart()) {
                    continue;
                }
                batteryUsage.add(event.getBatteryUsage(since, Math.min(now, event.getTimestamp())));
                if (now <= event.getTimestamp()) {
                    break;
                }
            }
            return Pair.create(batteryUsage.unmutate(), lastEvent.isStart()
                    ? lastEvent.getBatteryUsage() : BATTERY_USAGE_NONE);
        }

        /**
         * @return The pair of bg battery usage of given duration; the first value in the pair
         *         is the aggregated battery usage of selected events in this duration; while
         *         the second value is the battery usage since the system boots, if there is
         *         an open event(just start, no stop) at the end of the duration.
         */
        Pair<ImmutableBatteryUsage, ImmutableBatteryUsage> getBatteryUsageSince(long since,
                long now, int types) {
            LinkedList<UidStateEventWithBattery> result = new LinkedList<>();
            for (int i = 0; i < mEvents.length; i++) {
                if ((types & stateIndexToType(i)) != 0) {
                    result = add(result, mEvents[i]);
                }
            }
            return getBatteryUsageSince(since, now, result);
        }

        /**
         * Merge the two given duration table and return the result.
         */
        @VisibleForTesting
        @Override
        LinkedList<UidStateEventWithBattery> add(LinkedList<UidStateEventWithBattery> durations,
                LinkedList<UidStateEventWithBattery> otherDurations) {
            if (otherDurations == null || otherDurations.size() == 0) {
                return durations;
            }
            if (durations == null || durations.size() == 0) {
                return (LinkedList<UidStateEventWithBattery>) otherDurations.clone();
            }
            final Iterator<UidStateEventWithBattery> itl = durations.iterator();
            final Iterator<UidStateEventWithBattery> itr = otherDurations.iterator();
            UidStateEventWithBattery l = itl.next(), r = itr.next();
            LinkedList<UidStateEventWithBattery> dest = new LinkedList<>();
            boolean actl = false, actr = false, overlapping = false;
            final BatteryUsage batteryUsage = new BatteryUsage();
            long recentActTs = 0, overlappingDuration = 0;
            for (long lts = l.getTimestamp(), rts = r.getTimestamp();
                    lts != Long.MAX_VALUE || rts != Long.MAX_VALUE;) {
                final boolean actCur = actl || actr;
                final UidStateEventWithBattery earliest;
                if (lts == rts) {
                    earliest = l;
                    // we'll deal with the double counting problem later.
                    if (actl) batteryUsage.add(l.getBatteryUsage());
                    if (actr) batteryUsage.add(r.getBatteryUsage());
                    overlappingDuration += overlapping && (actl || actr)
                            ? (lts - recentActTs) : 0;
                    actl = !actl;
                    actr = !actr;
                    lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
                    rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
                } else if (lts < rts) {
                    earliest = l;
                    if (actl) batteryUsage.add(l.getBatteryUsage());
                    overlappingDuration += overlapping && actl ? (lts - recentActTs) : 0;
                    actl = !actl;
                    lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
                } else {
                    earliest = r;
                    if (actr) batteryUsage.add(r.getBatteryUsage());
                    overlappingDuration += overlapping && actr ? (rts - recentActTs) : 0;
                    actr = !actr;
                    rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
                }
                overlapping = actl && actr;
                if (actl || actr) {
                    recentActTs = earliest.getTimestamp();
                }
                if (actCur != (actl || actr)) {
                    final UidStateEventWithBattery event =
                            (UidStateEventWithBattery) earliest.clone();
                    if (actCur) {
                        // It's an stop/end event, update the start timestamp and batteryUsage.
                        final UidStateEventWithBattery lastEvent = dest.peekLast();
                        final long startTs = lastEvent.getTimestamp();
                        final long duration = event.getTimestamp() - startTs;
                        final long durationWithOverlapping = duration + overlappingDuration;
                        // Get the proportional batteryUsage.
                        if (durationWithOverlapping != 0) {
                            batteryUsage.scale(duration * 1.0d / durationWithOverlapping);
                            event.update(lastEvent, new ImmutableBatteryUsage(batteryUsage));
                        } else {
                            event.update(lastEvent, BATTERY_USAGE_NONE);
                        }
                        batteryUsage.setTo(BATTERY_USAGE_NONE);
                        overlappingDuration = 0;
                    }
                    dest.add(event);
                }
            }
            return dest;
        }
    }

    private void trimDurations() {
        final long now = SystemClock.elapsedRealtime();
        trim(Math.max(0, now - mInjector.getPolicy().getMaxTrackingDuration()));
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        // We're dumping the data in AppBatteryTracker actually, so just dump the policy here.
        mInjector.getPolicy().dump(pw, prefix);
    }

    /**
     * A basic event marking a certain event, i.e., a FGS start/stop;
     * it'll record the background battery usage data over the start/stop.
     */
    static final class UidStateEventWithBattery extends BaseTimeEvent {
        /**
         * Whether or not this is a start event.
         */
        private boolean mIsStart;

        /**
         * The known background battery usage; it will be the total bg battery usage since
         * the system boots if the {@link #mIsStart} is true, but will be the delta of the bg
         * battery usage since the start event if the {@link #mIsStart} is false.
         */
        private @NonNull ImmutableBatteryUsage mBatteryUsage;

        /**
         * The peer event of this pair (a pair of start/stop events).
         */
        private @Nullable UidStateEventWithBattery mPeer;

        UidStateEventWithBattery(boolean isStart, long now,
                @NonNull ImmutableBatteryUsage batteryUsage,
                @Nullable UidStateEventWithBattery peer) {
            super(now);
            mIsStart = isStart;
            mBatteryUsage = batteryUsage;
            mPeer = peer;
            if (peer != null) {
                peer.mPeer = this;
            }
        }

        UidStateEventWithBattery(UidStateEventWithBattery other) {
            super(other);
            mIsStart = other.mIsStart;
            mBatteryUsage = other.mBatteryUsage;
            // Don't copy the peer object though.
        }

        @Override
        void trimTo(long timestamp) {
            // We don't move the stop event.
            if (!mIsStart || timestamp < mTimestamp) {
                return;
            }
            if (mPeer != null) {
                // Reduce the bg battery usage proportionally.
                final ImmutableBatteryUsage batteryUsage = mPeer.getBatteryUsage();
                mPeer.mBatteryUsage = mPeer.getBatteryUsage(timestamp, mPeer.mTimestamp);
                // Update the battery data of the start event too.
                mBatteryUsage = mBatteryUsage.mutate()
                        .add(batteryUsage)
                        .subtract(mPeer.mBatteryUsage)
                        .unmutate();
            }
            mTimestamp = timestamp;
        }

        void update(@NonNull UidStateEventWithBattery peer,
                @NonNull ImmutableBatteryUsage batteryUsage) {
            mPeer = peer;
            peer.mPeer = this;
            mBatteryUsage = batteryUsage;
        }

        boolean isStart() {
            return mIsStart;
        }

        @NonNull ImmutableBatteryUsage getBatteryUsage(long start, long end) {
            if (mIsStart || start >= mTimestamp || end <= start) {
                return BATTERY_USAGE_NONE;
            }
            start = Math.max(start, mPeer.mTimestamp);
            end = Math.min(end, mTimestamp);
            final long totalDur = mTimestamp - mPeer.mTimestamp;
            final long inputDur = end - start;
            return totalDur != 0 ? (totalDur == inputDur ? mBatteryUsage : mBatteryUsage.mutate()
                    .scale((1.0d * inputDur) / totalDur).unmutate()) : BATTERY_USAGE_NONE;
        }

        @NonNull ImmutableBatteryUsage getBatteryUsage() {
            return mBatteryUsage;
        }

        @Override
        public Object clone() {
            return new UidStateEventWithBattery(this);
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (other.getClass() != UidStateEventWithBattery.class) {
                return false;
            }
            final UidStateEventWithBattery otherEvent = (UidStateEventWithBattery) other;
            return otherEvent.mIsStart == mIsStart
                    && otherEvent.mTimestamp == mTimestamp
                    && mBatteryUsage.equals(otherEvent.mBatteryUsage);
        }

        @Override
        public String toString() {
            return "UidStateEventWithBattery(" + mIsStart + ", " + mTimestamp
                    + ", " + mBatteryUsage + ")";
        }

        @Override
        public int hashCode() {
            return (Boolean.hashCode(mIsStart) * 31
                    + Long.hashCode(mTimestamp)) * 31
                    + mBatteryUsage.hashCode();
        }
    }

    static final class AppBatteryExemptionPolicy
            extends BaseAppStateEventsPolicy<AppBatteryExemptionTracker> {
        /**
         * Whether or not we should enable the exemption of certain battery drains.
         */
        static final String KEY_BG_BATTERY_EXEMPTION_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "battery_exemption_enabled";

        /**
         * Default value to {@link #mTrackerEnabled}.
         */
        static final boolean DEFAULT_BG_BATTERY_EXEMPTION_ENABLED = true;

        AppBatteryExemptionPolicy(@NonNull Injector injector,
                @NonNull AppBatteryExemptionTracker tracker) {
            super(injector, tracker,
                    KEY_BG_BATTERY_EXEMPTION_ENABLED, DEFAULT_BG_BATTERY_EXEMPTION_ENABLED,
                    AppBatteryPolicy.KEY_BG_CURRENT_DRAIN_WINDOW,
                    tracker.mContext.getResources()
                    .getInteger(R.integer.config_bg_current_drain_window));
        }

        @Override
        public void onMaxTrackingDurationChanged(long maxDuration) {
            mTracker.mBgHandler.post(mTracker::trimDurations);
        }

        @Override
        public void onTrackerEnabled(boolean enabled) {
            mTracker.onTrackerEnabled(enabled);
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP BATTERY EXEMPTION TRACKER POLICY SETTINGS:");
            final String indent = "  ";
            prefix = indent + prefix;
            super.dump(pw, prefix);
        }
    }
}
