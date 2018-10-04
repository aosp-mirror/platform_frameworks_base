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

import static android.app.usage.UsageEvents.Event.CONTINUE_PREVIOUS_DAY;
import static android.app.usage.UsageEvents.Event.CONTINUING_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.END_OF_DAY;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_STOP;
import static android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND;
import static android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND;
import static android.app.usage.UsageEvents.Event.ROLLOVER_FOREGROUND_SERVICE;

import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

/**
 * Contains usage statistics for an app package for a specific
 * time range.
 */
public final class UsageStats implements Parcelable {

    /**
     * {@hide}
     */
    @UnsupportedAppUsage
    public String mPackageName;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage
    public long mEndTimeStamp;

    /**
     * Last time used by the user with an explicit action (notification, activity launch)
     * {@hide}
     */
    @UnsupportedAppUsage
    public long mLastTimeUsed;

    /**
     * Total time this package's activity is in foreground.
     * {@hide}
     */
    @UnsupportedAppUsage
    public long mTotalTimeInForeground;

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

    /** Last activity MOVE_TO_FOREGROUND or MOVE_TO_BACKGROUND event.
     * {@hide}
     * @deprecated use {@link #mLastForegroundActivityEventMap} instead.
     */
    @UnsupportedAppUsage
    @Deprecated
    public int mLastEvent;

    /**
     * If an activity is in foreground, it has one entry in this map.
     * When activity moves to background, it is removed from this map.
     * Key is activity class name.
     * Value is last time this activity MOVE_TO_FOREGROUND or MOVE_TO_BACKGROUND event.
     * {@hide}
     */
    public ArrayMap<String, Integer> mLastForegroundActivityEventMap = new ArrayMap<>();

    /**
     * If a foreground service is started, it has one entry in this map.
     * When a foreground service is stopped, it is removed from this map.
     * Key is foreground service class name.
     * Value is last foreground service FOREGROUND_SERVICE_START ot FOREGROUND_SERVICE_STOP event.
     * {@hide}
     */
    public ArrayMap<String, Integer> mLastForegroundServiceEventMap = new ArrayMap<>();

    /**
     * {@hide}
     */
    public ArrayMap<String, ArrayMap<String, Integer>> mChooserCounts = new ArrayMap<>();

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
        mLastTimeForegroundServiceUsed = stats.mLastTimeForegroundServiceUsed;
        mTotalTimeInForeground = stats.mTotalTimeInForeground;
        mTotalTimeForegroundServiceUsed = stats.mTotalTimeForegroundServiceUsed;
        mLaunchCount = stats.mLaunchCount;
        mAppLaunchCount = stats.mAppLaunchCount;
        mLastEvent = stats.mLastEvent;
        mLastForegroundActivityEventMap = stats.mLastForegroundActivityEventMap;
        mLastForegroundServiceEventMap = stats.mLastForegroundServiceEventMap;
        mChooserCounts = stats.mChooserCounts;
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
     * Get the total time this package spent in the foreground, measured in milliseconds.
     */
    public long getTotalTimeInForeground() {
        return mTotalTimeInForeground;
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
            mergeEventMap(mLastForegroundActivityEventMap, right.mLastForegroundActivityEventMap);
            mergeEventMap(mLastForegroundServiceEventMap, right.mLastForegroundServiceEventMap);
            mLastTimeUsed = Math.max(mLastTimeUsed, right.mLastTimeUsed);
            mLastTimeForegroundServiceUsed = Math.max(mLastTimeForegroundServiceUsed,
                    right.mLastTimeForegroundServiceUsed);
        }
        mBeginTimeStamp = Math.min(mBeginTimeStamp, right.mBeginTimeStamp);
        mEndTimeStamp = Math.max(mEndTimeStamp, right.mEndTimeStamp);
        mTotalTimeInForeground += right.mTotalTimeInForeground;
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
     * Tell if an event indicate activity is in foreground or not.
     * @param event the activity event.
     * @return true if activity is in foreground, false otherwise.
     * @hide
     */
    private boolean isActivityInForeground(int event) {
        return event == MOVE_TO_FOREGROUND
                || event == CONTINUE_PREVIOUS_DAY;
    }

    /**
     * Tell if an event indicate foreground sevice is started or not.
     * @param event the foreground service event.
     * @return true if foreground service is started, false if stopped.
     * @hide
     */
    private boolean isForegroundServiceStarted(int event) {
        return event == FOREGROUND_SERVICE_START
                || event == CONTINUING_FOREGROUND_SERVICE;
    }

    /**
     * If any activity in foreground or any foreground service is started, the app is considered in
     * use.
     * @return true if in use, false otherwise.
     * @hide
     */
    private boolean isAppInUse() {
        return !mLastForegroundActivityEventMap.isEmpty()
                || !mLastForegroundServiceEventMap.isEmpty();
    }

    /**
     * Update by an event of an activity.
     * @param className className of the activity.
     * @param timeStamp timeStamp of the event.
     * @param eventType type of the event.
     * @hide
     */
    private void updateForegroundActivity(String className, long timeStamp, int eventType) {
        if (eventType != MOVE_TO_BACKGROUND
                && eventType != MOVE_TO_FOREGROUND
                && eventType != END_OF_DAY) {
            return;
        }

        final Integer lastEvent = mLastForegroundActivityEventMap.get(className);
        if (lastEvent != null) {
            if (isActivityInForeground(lastEvent)) {
                if (timeStamp > mLastTimeUsed) {
                    mTotalTimeInForeground += timeStamp - mLastTimeUsed;
                    mLastTimeUsed = timeStamp;
                }
            }
            if (eventType == MOVE_TO_BACKGROUND) {
                mLastForegroundActivityEventMap.remove(className);
            } else {
                mLastForegroundActivityEventMap.put(className, eventType);
            }
        } else if (eventType == MOVE_TO_FOREGROUND) {
            if (!isAppInUse()) {
                mLastTimeUsed = timeStamp;
            }
            mLastForegroundActivityEventMap.put(className, eventType);
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
                && eventType != FOREGROUND_SERVICE_START
                && eventType != ROLLOVER_FOREGROUND_SERVICE) {
            return;
        }
        final Integer lastEvent = mLastForegroundServiceEventMap.get(className);
        if (lastEvent != null) {
            if (isForegroundServiceStarted(lastEvent)) {
                if (timeStamp > mLastTimeForegroundServiceUsed) {
                    mTotalTimeForegroundServiceUsed +=
                            timeStamp - mLastTimeForegroundServiceUsed;
                    mLastTimeForegroundServiceUsed = timeStamp;
                }
            }
            if (eventType == FOREGROUND_SERVICE_STOP) {
                mLastForegroundServiceEventMap.remove(className);
            } else {
                mLastForegroundServiceEventMap.put(className, eventType);
            }
        } else if (eventType == FOREGROUND_SERVICE_START) {
            if (!isAppInUse()) {
                mLastTimeForegroundServiceUsed = timeStamp;
            }
            mLastForegroundServiceEventMap.put(className, eventType);
        }
    }

    /**
     * Update the UsageStats by a activity or foreground service event.
     * @param className class name of a activity or foreground service, could be null to mark
     *                  END_OF_DAY or rollover.
     * @param timeStamp Epoch timestamp in milliseconds.
     * @param eventType event type as in {@link UsageEvents.Event}
     * @hide
     */
    public void update(String className, long timeStamp, int eventType) {
        switch(eventType) {
            case MOVE_TO_BACKGROUND:
            case MOVE_TO_FOREGROUND:
                updateForegroundActivity(className, timeStamp, eventType);
                break;
            case END_OF_DAY:
                // END_OF_DAY means updating all activities.
                final int size = mLastForegroundActivityEventMap.size();
                for (int i = 0; i < size; i++) {
                    final String name = mLastForegroundActivityEventMap.keyAt(i);
                    updateForegroundActivity(name, timeStamp, eventType);
                }
                break;
            case CONTINUE_PREVIOUS_DAY:
                mLastTimeUsed = timeStamp;
                mLastForegroundActivityEventMap.put(className, eventType);
                break;
            case FOREGROUND_SERVICE_STOP:
            case FOREGROUND_SERVICE_START:
                updateForegroundService(className, timeStamp, eventType);
                break;
            case ROLLOVER_FOREGROUND_SERVICE:
                // ROLLOVER_FOREGROUND_SERVICE means updating all foreground services.
                final int size2 = mLastForegroundServiceEventMap.size();
                for (int i = 0; i < size2; i++) {
                    final String name = mLastForegroundServiceEventMap.keyAt(i);
                    updateForegroundService(name, timeStamp, eventType);
                }
                break;
            case CONTINUING_FOREGROUND_SERVICE:
                mLastTimeForegroundServiceUsed = timeStamp;
                mLastForegroundServiceEventMap.put(className, eventType);
                break;
            default:
                break;
        }
        mEndTimeStamp = timeStamp;

        if (eventType == MOVE_TO_FOREGROUND) {
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
        dest.writeLong(mLastTimeForegroundServiceUsed);
        dest.writeLong(mTotalTimeInForeground);
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

        final Bundle foregroundActivityEventBundle = new Bundle();
        final int foregroundEventSize = mLastForegroundActivityEventMap.size();
        for (int i = 0; i < foregroundEventSize; i++) {
            foregroundActivityEventBundle.putInt(mLastForegroundActivityEventMap.keyAt(i),
                    mLastForegroundActivityEventMap.valueAt(i));
        }
        dest.writeBundle(foregroundActivityEventBundle);

        final Bundle foregroundServiceEventBundle = new Bundle();
        final int foregroundServiceEventSize = mLastForegroundServiceEventMap.size();
        for (int i = 0; i < foregroundServiceEventSize; i++) {
            foregroundServiceEventBundle.putInt(mLastForegroundServiceEventMap.keyAt(i),
                    mLastForegroundServiceEventMap.valueAt(i));
        }
        dest.writeBundle(foregroundServiceEventBundle);
    }

    public static final Creator<UsageStats> CREATOR = new Creator<UsageStats>() {
        @Override
        public UsageStats createFromParcel(Parcel in) {
            UsageStats stats = new UsageStats();
            stats.mPackageName = in.readString();
            stats.mBeginTimeStamp = in.readLong();
            stats.mEndTimeStamp = in.readLong();
            stats.mLastTimeUsed = in.readLong();
            stats.mLastTimeForegroundServiceUsed = in.readLong();
            stats.mTotalTimeInForeground = in.readLong();
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
            readBundleToEventMap(stats.mLastForegroundActivityEventMap, in.readBundle());
            readBundleToEventMap(stats.mLastForegroundServiceEventMap, in.readBundle());
            return stats;
        }

        private void readBundleToEventMap(ArrayMap<String, Integer> eventMap, Bundle bundle) {
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
