/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import static android.app.usage.UsageEvents.Event.ACTIVITY_DESTROYED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED;
import static android.app.usage.UsageEvents.Event.CONTINUING_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN;
import static android.app.usage.UsageEvents.Event.END_OF_DAY;
import static android.app.usage.UsageEvents.Event.FLUSH_TO_DISK;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_STOP;
import static android.app.usage.UsageEvents.Event.ROLLOVER_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;

import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseIntArray;

/**
 * Contains usage statistics for an app package for a specific
 * time range.
 */
public final class UsageStats implements Parcelable {

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public String mPackageName;

    /**
     * {@hide}
     */
    public int mPackageToken = -1;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mEndTimeStamp;

    /**
     * Last time an activity is at foreground (have focus), this is corresponding to
     * {@link android.app.usage.UsageEvents.Event#ACTIVITY_RESUMED} event.
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mLastTimeUsed;

    /**
     * Last time an activity is visible.
     * @hide
     */
    public long mLastTimeVisible;

    /**
     * Total time this package's activity is in foreground.
     * {@hide}
     */
    @UnsupportedAppUsage
    public long mTotalTimeInForeground;

    /**
     * Total time this package's activity is visible.
     * {@hide}
     */
    public long mTotalTimeVisible;

    /**
     * Last time foreground service is started.
     * {@hide}
     */
    public long mLastTimeForegroundServiceUsed;

    /**
     * Total time this package's foreground service is started.
     * {@hide}
     */
    public long mTotalTimeForegroundServiceUsed;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage
    public int mLaunchCount;

    /**
     * {@hide}
     */
    public int mAppLaunchCount;

    /** Last activity ACTIVITY_RESUMED or ACTIVITY_PAUSED event.
     * {@hide}
     * @deprecated use {@link #mActivities} instead.
     */
    @UnsupportedAppUsage
    @Deprecated
    public int mLastEvent;

    /**
     * Key is instanceId of the activity (ActivityRecode appToken hashCode)..
     * Value is this activity's last event, one of ACTIVITY_RESUMED, ACTIVITY_PAUSED or
     * ACTIVITY_STOPPED.
     * {@hide}
     */
    public SparseIntArray mActivities = new SparseIntArray();
    /**
     * If a foreground service is started, it has one entry in this map.
     * When a foreground service is stopped, it is removed from this set.
     * Key is foreground service class name.
     * Value is the foreground service's last event, it is FOREGROUND_SERVICE_START.
     * {@hide}
     */
    public ArrayMap<String, Integer> mForegroundServices = new ArrayMap<>();

    /**
     * {@hide}
     */
    public ArrayMap<String, ArrayMap<String, Integer>> mChooserCounts = new ArrayMap<>();

    /**
     * {@hide}
     */
    public SparseArray<SparseIntArray> mChooserCountsObfuscated = new SparseArray<>();

    /**
     * {@hide}
     */
    public UsageStats() {
    }

    public UsageStats(UsageStats stats) {
        mPackageName = stats.mPackageName;
        mBeginTimeStamp = stats.mBeginTimeStamp;
        mEndTimeStamp = stats.mEndTimeStamp;
        mLastTimeUsed = stats.mLastTimeUsed;
        mLastTimeVisible = stats.mLastTimeVisible;
        mLastTimeForegroundServiceUsed = stats.mLastTimeForegroundServiceUsed;
        mTotalTimeInForeground = stats.mTotalTimeInForeground;
        mTotalTimeVisible = stats.mTotalTimeVisible;
        mTotalTimeForegroundServiceUsed = stats.mTotalTimeForegroundServiceUsed;
        mLaunchCount = stats.mLaunchCount;
        mAppLaunchCount = stats.mAppLaunchCount;
        mLastEvent = stats.mLastEvent;
        mActivities = stats.mActivities.clone();
        mForegroundServices = new ArrayMap<>(stats.mForegroundServices);
        mChooserCounts = new ArrayMap<>(stats.mChooserCounts);
    }

    /**
     * {@hide}
     */
    public UsageStats getObfuscatedForInstantApp() {
        final UsageStats ret = new UsageStats(this);

        ret.mPackageName = UsageEvents.INSTANT_APP_PACKAGE_NAME;

        return ret;
    }

    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the beginning of the time range this {@link android.app.usage.UsageStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getFirstTimeStamp() {
        return mBeginTimeStamp;
    }

    /**
     * Get the end of the time range this {@link android.app.usage.UsageStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeStamp() {
        return mEndTimeStamp;
    }

    /**
     * Get the last time this package's activity was used, measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeUsed() {
        return mLastTimeUsed;
    }

    /**
     * Get the last time this package's activity is visible in the UI, measured in milliseconds
     * since the epoch.
     */
    public long getLastTimeVisible() {
        return mLastTimeVisible;
    }

    /**
     * Get the total time this package spent in the foreground, measured in milliseconds.
     */
    public long getTotalTimeInForeground() {
        return mTotalTimeInForeground;
    }

    /**
     * Get the total time this package's activity is visible in the UI, measured in milliseconds.
     */
    public long getTotalTimeVisible() {
        return mTotalTimeVisible;
    }

    /**
     * Get the last time this package's foreground service was used, measured in milliseconds since
     * the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeForegroundServiceUsed() {
        return mLastTimeForegroundServiceUsed;
    }

    /**
     * Get the total time this package's foreground services are started, measured in milliseconds.
     */
    public long getTotalTimeForegroundServiceUsed() {
        return mTotalTimeForegroundServiceUsed;
    }

    /**
     * Returns the number of times the app was launched as an activity from outside of the app.
     * Excludes intra-app activity transitions.
     * @hide
     */
    @SystemApi
    public int getAppLaunchCount() {
        return mAppLaunchCount;
    }

    private void mergeEventMap(SparseIntArray left, SparseIntArray right) {
        final int size = right.size();
        for (int i = 0; i < size; i++) {
            final int instanceId = right.keyAt(i);
            final int event = right.valueAt(i);
            final int index = left.indexOfKey(instanceId);
            if (index >= 0) {
                left.put(instanceId, Math.max(left.valueAt(index), event));
            } else {
                left.put(instanceId, event);
            }
        }
    }

    private void mergeEventMap(ArrayMap<String, Integer> left, ArrayMap<String, Integer> right) {
        final int size = right.size();
        for (int i = 0; i < size; i++) {
            final String className = right.keyAt(i);
            final Integer event = right.valueAt(i);
            if (left.containsKey(className)) {
                left.put(className, Math.max(left.get(className), event));
            } else {
                left.put(className, event);
            }
        }
    }

    /**
     * Add the statistics from the right {@link UsageStats} to the left. The package name for
     * both {@link UsageStats} objects must be the same.
     * @param right The {@link UsageStats} object to merge into this one.
     * @throws java.lang.IllegalArgumentException if the package names of the two
     *         {@link UsageStats} objects are different.
     */
    public void add(UsageStats right) {
        if (!mPackageName.equals(right.mPackageName)) {
            throw new IllegalArgumentException("Can't merge UsageStats for package '" +
                    mPackageName + "' with UsageStats for package '" + right.mPackageName + "'.");
        }

        // We use the mBeginTimeStamp due to a bug where UsageStats files can overlap with
        // regards to their mEndTimeStamp.
        if (right.mBeginTimeStamp > mBeginTimeStamp) {
            // Even though incoming UsageStat begins after this one, its last time used fields
            // may somehow be empty or chronologically preceding the older UsageStat.
            mergeEventMap(mActivities, right.mActivities);
            mergeEventMap(mForegroundServices, right.mForegroundServices);
            mLastTimeUsed = Math.max(mLastTimeUsed, right.mLastTimeUsed);
            mLastTimeVisible = Math.max(mLastTimeVisible, right.mLastTimeVisible);
            mLastTimeForegroundServiceUsed = Math.max(mLastTimeForegroundServiceUsed,
                    right.mLastTimeForegroundServiceUsed);
        }
        mBeginTimeStamp = Math.min(mBeginTimeStamp, right.mBeginTimeStamp);
        mEndTimeStamp = Math.max(mEndTimeStamp, right.mEndTimeStamp);
        mTotalTimeInForeground += right.mTotalTimeInForeground;
        mTotalTimeVisible += right.mTotalTimeVisible;
        mTotalTimeForegroundServiceUsed += right.mTotalTimeForegroundServiceUsed;
        mLaunchCount += right.mLaunchCount;
        mAppLaunchCount += right.mAppLaunchCount;
        if (mChooserCounts == null) {
            mChooserCounts = right.mChooserCounts;
        } else if (right.mChooserCounts != null) {
            final int chooserCountsSize = right.mChooserCounts.size();
            for (int i = 0; i < chooserCountsSize; i++) {
                String action = right.mChooserCounts.keyAt(i);
                ArrayMap<String, Integer> counts = right.mChooserCounts.valueAt(i);
                if (!mChooserCounts.containsKey(action) || mChooserCounts.get(action) == null) {
                    mChooserCounts.put(action, counts);
                    continue;
                }
                final int annotationSize = counts.size();
                for (int j = 0; j < annotationSize; j++) {
                    String key = counts.keyAt(j);
                    int rightValue = counts.valueAt(j);
                    int leftValue = mChooserCounts.get(action).getOrDefault(key, 0);
                    mChooserCounts.get(action).put(key, leftValue + rightValue);
                }
            }
        }
    }

    /**
     * Tell if any activity is in foreground.
     * @return
     */
    private boolean hasForegroundActivity() {
        final int size = mActivities.size();
        for (int i = 0; i < size; i++) {
            if (mActivities.valueAt(i) == ACTIVITY_RESUMED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tell if any activity is visible.
     * @return
     */
    private boolean hasVisibleActivity() {
        final int size = mActivities.size();
        for (int i = 0; i < size; i++) {
            final int type = mActivities.valueAt(i);
            if (type == ACTIVITY_RESUMED
                    || type == ACTIVITY_PAUSED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tell if any foreground service is started.
     * @return
     */
    private boolean anyForegroundServiceStarted() {
        return !mForegroundServices.isEmpty();
    }

    /**
     * Increment total time in foreground and update last time in foreground.
     * @param timeStamp current timestamp.
     */
    private void incrementTimeUsed(long timeStamp) {
        if (timeStamp > mLastTimeUsed) {
            mTotalTimeInForeground += timeStamp - mLastTimeUsed;
            mLastTimeUsed = timeStamp;
        }
    }

    /**
     * Increment total time visible and update last time visible.
     * @param timeStamp current timestmap.
     */
    private void incrementTimeVisible(long timeStamp) {
        if (timeStamp > mLastTimeVisible) {
            mTotalTimeVisible += timeStamp - mLastTimeVisible;
            mLastTimeVisible = timeStamp;
        }
    }

    /**
     * Increment total time foreground service is used and update last time foreground service is
     * used.
     * @param timeStamp current timestamp.
     */
    private void incrementServiceTimeUsed(long timeStamp) {
        if (timeStamp > mLastTimeForegroundServiceUsed) {
            mTotalTimeForegroundServiceUsed +=
                    timeStamp - mLastTimeForegroundServiceUsed;
            mLastTimeForegroundServiceUsed = timeStamp;
        }
    }

    /**
     * Update by an event of an activity.
     * @param className className of the activity.
     * @param timeStamp timeStamp of the event.
     * @param eventType type of the event.
     * @param instanceId hashCode of the ActivityRecord's appToken.
     * @hide
     */
    private void updateActivity(String className, long timeStamp, int eventType, int instanceId) {
        if (eventType != ACTIVITY_RESUMED
                && eventType != ACTIVITY_PAUSED
                && eventType != ACTIVITY_STOPPED
                && eventType != ACTIVITY_DESTROYED) {
            return;
        }

        // update usage.
        final int index = mActivities.indexOfKey(instanceId);
        if (index >= 0) {
            final int lastEvent = mActivities.valueAt(index);
            switch (lastEvent) {
                case ACTIVITY_RESUMED:
                    incrementTimeUsed(timeStamp);
                    incrementTimeVisible(timeStamp);
                    break;
                case ACTIVITY_PAUSED:
                    incrementTimeVisible(timeStamp);
                    break;
                default:
                    break;
            }
        }

        // update current event.
        switch(eventType) {
            case ACTIVITY_RESUMED:
                if (!hasVisibleActivity()) {
                    // this is the first visible activity.
                    mLastTimeUsed = timeStamp;
                    mLastTimeVisible = timeStamp;
                } else if (!hasForegroundActivity()) {
                    // this is the first foreground activity.
                    mLastTimeUsed = timeStamp;
                }
                mActivities.put(instanceId, eventType);
                break;
            case ACTIVITY_PAUSED:
                if (!hasVisibleActivity()) {
                    // this is the first visible activity.
                    mLastTimeVisible = timeStamp;
                }
                mActivities.put(instanceId, eventType);
                break;
            case ACTIVITY_STOPPED:
            case ACTIVITY_DESTROYED:
                // remove activity from the map.
                mActivities.delete(instanceId);
                break;
            default:
                break;
        }
    }

    /**
     * Update by an event of an foreground service.
     * @param className className of the foreground service.
     * @param timeStamp timeStamp of the event.
     * @param eventType type of the event.
     * @hide
     */
    private void updateForegroundService(String className, long timeStamp, int eventType) {
        if (eventType != FOREGROUND_SERVICE_STOP
                && eventType != FOREGROUND_SERVICE_START) {
            return;
        }
        final Integer lastEvent = mForegroundServices.get(className);
        // update usage.
        if (lastEvent != null) {
            switch (lastEvent) {
                case FOREGROUND_SERVICE_START:
                case CONTINUING_FOREGROUND_SERVICE:
                    incrementServiceTimeUsed(timeStamp);
                    break;
                default:
                    break;
            }
        }

        // update current event.
        switch (eventType) {
            case FOREGROUND_SERVICE_START:
                if (!anyForegroundServiceStarted()) {
                    mLastTimeForegroundServiceUsed = timeStamp;
                }
                mForegroundServices.put(className, eventType);
                break;
            case FOREGROUND_SERVICE_STOP:
                mForegroundServices.remove(className);
                break;
            default:
                break;
        }
    }

    /**
     * Update the UsageStats by a activity or foreground service event.
     * @param className class name of a activity or foreground service, could be null to if this
     *                  is sent to all activities/services in this package.
     * @param timeStamp Epoch timestamp in milliseconds.
     * @param eventType event type as in {@link UsageEvents.Event}
     * @param instanceId if className is an activity, the hashCode of ActivityRecord's appToken.
     *                 if className is not an activity, instanceId is not used.
     * @hide
     */
    public void update(String className, long timeStamp, int eventType, int instanceId) {
        switch(eventType) {
            case ACTIVITY_RESUMED:
            case ACTIVITY_PAUSED:
            case ACTIVITY_STOPPED:
            case ACTIVITY_DESTROYED:
                updateActivity(className, timeStamp, eventType, instanceId);
                break;
            case END_OF_DAY:
                // END_OF_DAY updates all activities.
                if (hasForegroundActivity()) {
                    incrementTimeUsed(timeStamp);
                }
                if (hasVisibleActivity()) {
                    incrementTimeVisible(timeStamp);
                }
                break;
            case FOREGROUND_SERVICE_START:
            case FOREGROUND_SERVICE_STOP:
                updateForegroundService(className, timeStamp, eventType);
                break;
            case ROLLOVER_FOREGROUND_SERVICE:
                // ROLLOVER_FOREGROUND_SERVICE updates all foreground services.
                if (anyForegroundServiceStarted()) {
                    incrementServiceTimeUsed(timeStamp);
                }
                break;
            case CONTINUING_FOREGROUND_SERVICE:
                mLastTimeForegroundServiceUsed = timeStamp;
                mForegroundServices.put(className, eventType);
                break;
            case DEVICE_SHUTDOWN:
            case FLUSH_TO_DISK:
                // update usage of all active activities/services.
                if (hasForegroundActivity()) {
                    incrementTimeUsed(timeStamp);
                }
                if (hasVisibleActivity()) {
                    incrementTimeVisible(timeStamp);
                }
                if (anyForegroundServiceStarted()) {
                    incrementServiceTimeUsed(timeStamp);
                }
                break;
            case USER_INTERACTION:
                if (hasForegroundActivity()) {
                    incrementTimeUsed(timeStamp);
                } else {
                    mLastTimeUsed = timeStamp;
                }
                if (hasVisibleActivity()) {
                    incrementTimeVisible(timeStamp);
                } else {
                    mLastTimeVisible = timeStamp;
                }
                break;
            default:
                break;
        }
        mEndTimeStamp = timeStamp;

        if (eventType == ACTIVITY_RESUMED) {
            mLaunchCount += 1;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeLong(mBeginTimeStamp);
        dest.writeLong(mEndTimeStamp);
        dest.writeLong(mLastTimeUsed);
        dest.writeLong(mLastTimeVisible);
        dest.writeLong(mLastTimeForegroundServiceUsed);
        dest.writeLong(mTotalTimeInForeground);
        dest.writeLong(mTotalTimeVisible);
        dest.writeLong(mTotalTimeForegroundServiceUsed);
        dest.writeInt(mLaunchCount);
        dest.writeInt(mAppLaunchCount);
        dest.writeInt(mLastEvent);
        Bundle allCounts = new Bundle();
        if (mChooserCounts != null) {
            final int chooserCountSize = mChooserCounts.size();
            for (int i = 0; i < chooserCountSize; i++) {
                String action = mChooserCounts.keyAt(i);
                ArrayMap<String, Integer> counts = mChooserCounts.valueAt(i);
                Bundle currentCounts = new Bundle();
                final int annotationSize = counts.size();
                for (int j = 0; j < annotationSize; j++) {
                    currentCounts.putInt(counts.keyAt(j), counts.valueAt(j));
                }
                allCounts.putBundle(action, currentCounts);
            }
        }
        dest.writeBundle(allCounts);

        writeSparseIntArray(dest, mActivities);
        dest.writeBundle(eventMapToBundle(mForegroundServices));
    }

    private void writeSparseIntArray(Parcel dest, SparseIntArray arr) {
        final int size = arr.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            dest.writeInt(arr.keyAt(i));
            dest.writeInt(arr.valueAt(i));
        }
    }

    private Bundle eventMapToBundle(ArrayMap<String, Integer> eventMap) {
        final Bundle bundle = new Bundle();
        final int size = eventMap.size();
        for (int i = 0; i < size; i++) {
            bundle.putInt(eventMap.keyAt(i), eventMap.valueAt(i));
        }
        return bundle;
    }

    public static final @android.annotation.NonNull Creator<UsageStats> CREATOR = new Creator<UsageStats>() {
        @Override
        public UsageStats createFromParcel(Parcel in) {
            UsageStats stats = new UsageStats();
            stats.mPackageName = in.readString();
            stats.mBeginTimeStamp = in.readLong();
            stats.mEndTimeStamp = in.readLong();
            stats.mLastTimeUsed = in.readLong();
            stats.mLastTimeVisible = in.readLong();
            stats.mLastTimeForegroundServiceUsed = in.readLong();
            stats.mTotalTimeInForeground = in.readLong();
            stats.mTotalTimeVisible = in.readLong();
            stats.mTotalTimeForegroundServiceUsed = in.readLong();
            stats.mLaunchCount = in.readInt();
            stats.mAppLaunchCount = in.readInt();
            stats.mLastEvent = in.readInt();
            Bundle allCounts = in.readBundle();
            if (allCounts != null) {
                stats.mChooserCounts = new ArrayMap<>();
                for (String action : allCounts.keySet()) {
                    if (!stats.mChooserCounts.containsKey(action)) {
                        ArrayMap<String, Integer> newCounts = new ArrayMap<>();
                        stats.mChooserCounts.put(action, newCounts);
                    }
                    Bundle currentCounts = allCounts.getBundle(action);
                    if (currentCounts != null) {
                        for (String key : currentCounts.keySet()) {
                            int value = currentCounts.getInt(key);
                            if (value > 0) {
                                stats.mChooserCounts.get(action).put(key, value);
                            }
                        }
                    }
                }
            }
            readSparseIntArray(in, stats.mActivities);
            readBundleToEventMap(in.readBundle(), stats.mForegroundServices);
            return stats;
        }

        private void readSparseIntArray(Parcel in, SparseIntArray arr) {
            final int size = in.readInt();
            for (int i = 0; i < size; i++) {
                final int key = in.readInt();
                final int value = in.readInt();
                arr.put(key, value);
            }
        }

        private void readBundleToEventMap(Bundle bundle, ArrayMap<String, Integer> eventMap) {
            if (bundle != null) {
                for (String className : bundle.keySet()) {
                    final int event = bundle.getInt(className);
                    eventMap.put(className, event);
                }
            }
        }

        @Override
        public UsageStats[] newArray(int size) {
            return new UsageStats[size];
        }
    };
}
