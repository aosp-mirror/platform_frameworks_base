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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * A result returned from {@link android.app.usage.UsageStatsManager#queryEvents(long, long)}
 * from which to read {@link android.app.usage.UsageEvents.Event} objects.
 */
public final class UsageEvents implements Parcelable {

    /** @hide */
    public static final String INSTANT_APP_PACKAGE_NAME = "android.instant_app";

    /** @hide */
    public static final String INSTANT_APP_CLASS_NAME = "android.instant_class";

    /** @hide */
    public static final String OBFUSCATED_NOTIFICATION_CHANNEL_ID = "unknown_channel_id";

    /**
     * Flag: indicates to not obfuscate or hide any usage event data when being queried.
     * @hide
     */
    public static final int SHOW_ALL_EVENT_DATA = 0x00000000;

    /**
     * Flag: indicates to obfuscate package and class names for instant apps when querying usage
     * events.
     * @hide
     */
    public static final int OBFUSCATE_INSTANT_APPS = 0x00000001;

    /**
     * Flag: indicates to hide all {@link Event#SHORTCUT_INVOCATION} events when querying usage
     * events.
     * @hide
     */
    public static final int HIDE_SHORTCUT_EVENTS = 0x00000002;

    /**
     * Flag: indicates to obfuscate the notification channel id for all notification events,
     * such as {@link Event#NOTIFICATION_SEEN} and {@link Event#NOTIFICATION_INTERRUPTION} events,
     * when querying usage events.
     * @hide
     */
    public static final int OBFUSCATE_NOTIFICATION_EVENTS = 0x00000004;

    /**
     * Flag: indicates to hide all {@link Event#LOCUS_ID_SET} events when querying usage events.
     * @hide
     */
    public static final int HIDE_LOCUS_EVENTS = 0x00000008;

    /**
     * An event representing a state change for a component.
     */
    public static final class Event {

        /**
         * No event type.
         */
        public static final int NONE = 0;

        /**
         * A device level event like {@link #DEVICE_SHUTDOWN} does not have package name, but some
         * user code always expect a non-null {@link #mPackage} for every event. Use
         * {@link #DEVICE_EVENT_PACKAGE_NAME} as packageName for these device level events.
         * @hide
         */
        public static final String DEVICE_EVENT_PACKAGE_NAME = "android";

        /**
         * @deprecated by {@link #ACTIVITY_RESUMED}
         */
        @Deprecated
        public static final int MOVE_TO_FOREGROUND = 1;

        /**
         * An event type denoting that an {@link android.app.Activity} moved to the foreground.
         * This event has a package name and class name associated with it and can be retrieved
         * using {@link #getPackageName()} and {@link #getClassName()}.
         * If a package has multiple activities, this event is reported for each activity that moves
         * to foreground.
         * This event is corresponding to {@link android.app.Activity#onResume()} of the
         * activity's lifecycle.
         */
        public static final int ACTIVITY_RESUMED = MOVE_TO_FOREGROUND;

        /**
         * @deprecated by {@link #ACTIVITY_PAUSED}
         */
        @Deprecated
        public static final int MOVE_TO_BACKGROUND = 2;

        /**
         * An event type denoting that an {@link android.app.Activity} moved to the background.
         * This event has a package name and class name associated with it and can be retrieved
         * using {@link #getPackageName()} and {@link #getClassName()}.
         * If a package has multiple activities, this event is reported for each activity that moves
         * to background.
         * This event is corresponding to {@link android.app.Activity#onPause()} of the activity's
         * lifecycle.
         */
        public static final int ACTIVITY_PAUSED = MOVE_TO_BACKGROUND;

        /**
         * An event type denoting that a component was in the foreground when the stats
         * rolled-over. This is effectively treated as a {@link #ACTIVITY_PAUSED}.
         * This event has a non-null packageName, and a null className.
         * {@hide}
         */
        public static final int END_OF_DAY = 3;

        /**
         * An event type denoting that a component was in the foreground the previous day.
         * This is effectively treated as a {@link #ACTIVITY_RESUMED}.
         * {@hide}
         */
        public static final int CONTINUE_PREVIOUS_DAY = 4;

        /**
         * An event type denoting that the device configuration has changed.
         */
        public static final int CONFIGURATION_CHANGE = 5;

        /**
         * An event type denoting that a package was interacted with in some way by the system.
         * @hide
         */
        @SystemApi
        public static final int SYSTEM_INTERACTION = 6;

        /**
         * An event type denoting that a package was interacted with in some way by the user.
         */
        public static final int USER_INTERACTION = 7;

        /**
         * An event type denoting that an action equivalent to a ShortcutInfo is taken by the user.
         *
         * @see android.content.pm.ShortcutManager#reportShortcutUsed(String)
         */
        public static final int SHORTCUT_INVOCATION = 8;

        /**
         * An event type denoting that a package was selected by the user for ChooserActivity.
         * @hide
         */
        public static final int CHOOSER_ACTION = 9;

        /**
         * An event type denoting that a notification was viewed by the user.
         * @hide
         */
        @SystemApi
        public static final int NOTIFICATION_SEEN = 10;

        /**
         * An event type denoting a change in App Standby Bucket. The new bucket can be
         * retrieved by calling {@link #getAppStandbyBucket()}.
         *
         * @see UsageStatsManager#getAppStandbyBucket()
         */
        public static final int STANDBY_BUCKET_CHANGED = 11;

        /**
         * An event type denoting that an app posted an interruptive notification. Visual and
         * audible interruptions are included.
         * @hide
         */
        @SystemApi
        public static final int NOTIFICATION_INTERRUPTION = 12;

        /**
         * A Slice was pinned by the default launcher or the default assistant.
         * @hide
         */
        @SystemApi
        public static final int SLICE_PINNED_PRIV = 13;

        /**
         * A Slice was pinned by an app.
         * @hide
         */
        @SystemApi
        public static final int SLICE_PINNED = 14;

        /**
         * An event type denoting that the screen has gone in to an interactive state (turned
         * on for full user interaction, not ambient display or other non-interactive state).
         */
        public static final int SCREEN_INTERACTIVE = 15;

        /**
         * An event type denoting that the screen has gone in to a non-interactive state
         * (completely turned off or turned on only in a non-interactive state like ambient
         * display).
         */
        public static final int SCREEN_NON_INTERACTIVE = 16;

        /**
         * An event type denoting that the screen's keyguard has been shown, whether or not
         * the screen is off.
         */
        public static final int KEYGUARD_SHOWN = 17;

        /**
         * An event type denoting that the screen's keyguard has been hidden.  This typically
         * happens when the user unlocks their phone after turning it on.
         */
        public static final int KEYGUARD_HIDDEN = 18;

        /**
         * An event type denoting start of a foreground service.
         * This event has a package name and class name associated with it and can be retrieved
         * using {@link #getPackageName()} and {@link #getClassName()}.
         * If a package has multiple foreground services, this event is reported for each service
         * that is started.
         */
        public static final int FOREGROUND_SERVICE_START = 19;

        /**
         * An event type denoting stop of a foreground service.
         * This event has a package name and class name associated with it and can be retrieved
         * using {@link #getPackageName()} and {@link #getClassName()}.
         * If a package has multiple foreground services, this event is reported for each service
         * that is stopped.
         */
        public static final int FOREGROUND_SERVICE_STOP = 20;

        /**
         * An event type denoting that a foreground service is at started state at beginning of a
         * time interval.
         * This is effectively treated as a {@link #FOREGROUND_SERVICE_START}.
         * {@hide}
         */
        public static final int CONTINUING_FOREGROUND_SERVICE = 21;

        /**
         * An event type denoting that a foreground service is at started state when the stats
         * rolled-over at the end of a time interval.
         * {@hide}
         */
        public static final int ROLLOVER_FOREGROUND_SERVICE = 22;

        /**
         * An activity becomes invisible on the UI, corresponding to
         * {@link android.app.Activity#onStop()} of the activity's lifecycle.
         */
        public static final int ACTIVITY_STOPPED = 23;

        /**
         * An activity object is destroyed, corresponding to
         * {@link android.app.Activity#onDestroy()} of the activity's lifecycle.
         * {@hide}
         */
        public static final int ACTIVITY_DESTROYED = 24;

        /**
         * The event type demoting that a flush of UsageStatsDatabase to file system. Before the
         * flush all usage stats need to be updated to latest timestamp to make sure the most
         * up to date stats are persisted.
         * @hide
         */
        public static final int FLUSH_TO_DISK = 25;

        /**
         * An event type denoting that the Android runtime underwent a shutdown process.
         * A DEVICE_SHUTDOWN event should be treated as if all started activities and foreground
         * services are now stopped and no explicit {@link #ACTIVITY_STOPPED} and
         * {@link #FOREGROUND_SERVICE_STOP} events will be generated for them.
         *
         * <p>The DEVICE_SHUTDOWN timestamp is actually the last time UsageStats database is
         * persisted before the actual shutdown. Events (if there are any) between this timestamp
         * and the actual shutdown is not persisted in the database. So any open events without
         * matching close events between DEVICE_SHUTDOWN and {@link #DEVICE_STARTUP} should be
         * ignored because the closing time is unknown.</p>
         */
        public static final int DEVICE_SHUTDOWN = 26;

        /**
         * An event type denoting that the Android runtime started up. This could be after a
         * shutdown or a runtime restart. Any open events without matching close events between
         * {@link #DEVICE_SHUTDOWN} and DEVICE_STARTUP should be ignored because the closing time is
         * unknown.
         */
        public static final int DEVICE_STARTUP = 27;

        /**
         * An event type denoting that a user has been unlocked for the first time. This event
         * mainly indicates when the user's credential encrypted storage was first accessible.
         * @hide
         */
        public static final int USER_UNLOCKED = 28;

        /**
         * An event type denoting that a user has been stopped. This typically happens when the
         * system is being turned off or when users are being switched.
         * @hide
         */
        public static final int USER_STOPPED = 29;

        /**
         * An event type denoting that new locusId has been set for a given activity.
         * @hide
         */
        public static final int LOCUS_ID_SET = 30;

        /**
         * An event type denoting that a component in the package has been used (e.g. broadcast
         * receiver, service, content provider). This generally matches up with usage that would
         * cause an app to leave force stop. The component itself is not provided as we are only
         * interested in whether the package is used, not the component itself.
         * @hide
         */
        public static final int APP_COMPONENT_USED = 31;

        /**
         * Keep in sync with the greatest event type value.
         * @hide
         */
        public static final int MAX_EVENT_TYPE = 31;

        /** @hide */
        public static final int FLAG_IS_PACKAGE_INSTANT_APP = 1 << 0;

        /** @hide */
        @IntDef(flag = true, prefix = { "FLAG_" }, value = {
                FLAG_IS_PACKAGE_INSTANT_APP,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface EventFlags {}

        /**
         * Bitwise OR all valid flag constants to create this constant.
         * @hide
         */
        public static final int VALID_FLAG_BITS = FLAG_IS_PACKAGE_INSTANT_APP;

        /**
         * @hide
         */
        private static final int UNASSIGNED_TOKEN = -1;

        /**
         * {@hide}
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String mPackage;

        /**
         * {@hide}
         */
        public int mPackageToken = UNASSIGNED_TOKEN;

        /**
         * {@hide}
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String mClass;

        /**
         * {@hide}
         */
        public int mClassToken = UNASSIGNED_TOKEN;

        /**
         * Uniquely identifies an activity. It's possible for two activities with the same
         * pkg/class name to be in lifecycle at the same time. The mInstanceId is guaranteed to be
         * unique per activity across all apps (not just within a single app).
         *
         * {@hide}
         */
        public int mInstanceId;

        /**
         * {@hide}
         */
        public String mTaskRootPackage;

        /**
         * {@hide}
         */
        public int mTaskRootPackageToken = UNASSIGNED_TOKEN;

        /**
         * {@hide}
         */
        public String mTaskRootClass;

        /**
         * {@hide}
         */
        public int mTaskRootClassToken = UNASSIGNED_TOKEN;

        /**
         * {@hide}
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public long mTimeStamp;

        /**
         * {@hide}
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mEventType;

        /**
         * Only present for {@link #CONFIGURATION_CHANGE} event types.
         * {@hide}
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public Configuration mConfiguration;

        /**
         * ID of the shortcut.
         * Only present for {@link #SHORTCUT_INVOCATION} event types.
         * {@hide}
         */
        public String mShortcutId;

        /**
         * {@hide}
         */
        public int mShortcutIdToken = UNASSIGNED_TOKEN;

        /**
         * Action type passed to ChooserActivity
         * Only present for {@link #CHOOSER_ACTION} event types.
         * {@hide}
         */
        public String mAction;

        /**
         * Content type passed to ChooserActivity.
         * Only present for {@link #CHOOSER_ACTION} event types.
         * {@hide}
         */
        public String mContentType;

        /**
         * Content annotations passed to ChooserActivity.
         * Only present for {@link #CHOOSER_ACTION} event types.
         * {@hide}
         */
        public String[] mContentAnnotations;

        /**
         * The app standby bucket assigned and reason. Bucket is the high order 16 bits, reason
         * is the low order 16 bits.
         * Only present for {@link #STANDBY_BUCKET_CHANGED} event types
         * {@hide}
         */
        public int mBucketAndReason;

        /**
         * The id of the {@link android.app.NotificationChannel} to which an interruptive
         * notification was posted.
         * Only present for {@link #NOTIFICATION_INTERRUPTION} event types.
         * {@hide}
         */
        public String mNotificationChannelId;

        /**
         * {@hide}
         */
        public int mNotificationChannelIdToken = UNASSIGNED_TOKEN;

        /**
         * LocusId.
         * Currently LocusId only present for {@link #LOCUS_ID_SET} event types.
         * {@hide}
         */
        public String mLocusId;

        /**
         * {@hide}
         */
        public int mLocusIdToken = UNASSIGNED_TOKEN;

        /** @hide */
        @EventFlags
        public int mFlags;

        public Event() {
        }

        /** @hide */
        public Event(int type,  long timeStamp) {
            mEventType = type;
            mTimeStamp = timeStamp;
        }

        /** @hide */
        public Event(Event orig) {
            copyFrom(orig);
        }

        /**
         * The package name of the source of this event.
         */
        public String getPackageName() {
            return mPackage;
        }

        /**
         * Indicates whether it is an instant app.
         * @hide
         */
        @SystemApi
        public boolean isInstantApp() {
            return (mFlags & FLAG_IS_PACKAGE_INSTANT_APP) == FLAG_IS_PACKAGE_INSTANT_APP;
        }

        /**
         * The class name of the source of this event. This may be null for
         * certain events.
         */
        public String getClassName() {
            return mClass;
        }

        /**
         *  An activity can be instantiated multiple times, this is the unique activity instance ID.
         *  For non-activity class, instance ID is always zero.
         *  @hide
         */
        @SystemApi
        public int getInstanceId() {
            return mInstanceId;
        }

        /**
         * The package name of the task root when this event was reported.
         * Or {@code null} for queries from apps without {@link
         * android.Manifest.permission#PACKAGE_USAGE_STATS}
         * @hide
         */
        @SystemApi
        public @Nullable String getTaskRootPackageName() {
            return mTaskRootPackage;
        }

        /**
         * The class name of the task root when this event was reported.
         * Or {@code null} for queries from apps without {@link
         * android.Manifest.permission#PACKAGE_USAGE_STATS}
         * @hide
         */
        @SystemApi
        public @Nullable String getTaskRootClassName() {
            return mTaskRootClass;
        }

        /**
         * The time at which this event occurred, measured in milliseconds since the epoch.
         * <p/>
         * See {@link System#currentTimeMillis()}.
         */
        public long getTimeStamp() {
            return mTimeStamp;
        }

        /**
         * The event type.
         * @see #ACTIVITY_PAUSED
         * @see #ACTIVITY_RESUMED
         * @see #CONFIGURATION_CHANGE
         * @see #USER_INTERACTION
         * @see #STANDBY_BUCKET_CHANGED
         * @see #FOREGROUND_SERVICE_START
         * @see #FOREGROUND_SERVICE_STOP
         * @see #ACTIVITY_STOPPED
         */
        public int getEventType() {
            return mEventType;
        }

        /**
         * Returns a {@link Configuration} for this event if the event is of type
         * {@link #CONFIGURATION_CHANGE}, otherwise it returns null.
         */
        public Configuration getConfiguration() {
            return mConfiguration;
        }

        /**
         * Returns the ID of a {@link android.content.pm.ShortcutInfo} for this event
         * if the event is of type {@link #SHORTCUT_INVOCATION}, otherwise it returns null.
         *
         * @see android.content.pm.ShortcutManager#reportShortcutUsed(String)
         */
        public String getShortcutId() {
            return mShortcutId;
        }

        /**
         * Returns the standby bucket of the app, if the event is of type
         * {@link #STANDBY_BUCKET_CHANGED}, otherwise returns 0.
         * @return the standby bucket associated with the event.
         * @hide
         */
        public int getStandbyBucket() {
            return (mBucketAndReason & 0xFFFF0000) >>> 16;
        }

        /**
         * Returns the standby bucket of the app, if the event is of type
         * {@link #STANDBY_BUCKET_CHANGED}, otherwise returns 0.
         * @return the standby bucket associated with the event.
         *
         */
        public int getAppStandbyBucket() {
            return (mBucketAndReason & 0xFFFF0000) >>> 16;
        }

        /**
         * Returns the reason for the bucketing, if the event is of type
         * {@link #STANDBY_BUCKET_CHANGED}, otherwise returns 0. Reason values include
         * the main reason which is one of REASON_MAIN_*, OR'ed with REASON_SUB_*, if there
         * are sub-reasons for the main reason, such as REASON_SUB_USAGE_* when the main reason
         * is REASON_MAIN_USAGE.
         * @hide
         */
        public int getStandbyReason() {
            return mBucketAndReason & 0x0000FFFF;
        }

        /**
         * Returns the ID of the {@link android.app.NotificationChannel} for this event if the
         * event is of type {@link #NOTIFICATION_INTERRUPTION}, otherwise it returns null;
         * @hide
         */
        @Nullable
        @SystemApi
        public String getNotificationChannelId() {
            return mNotificationChannelId;
        }

        /** @hide */
        public Event getObfuscatedIfInstantApp() {
            if (!isInstantApp()) {
                return this;
            }
            final Event ret = new Event(this);
            ret.mPackage = INSTANT_APP_PACKAGE_NAME;
            ret.mClass = INSTANT_APP_CLASS_NAME;

            // Note there are other string fields too, but they're for app shortcuts and choosers,
            // which instant apps can't use anyway, so there's no need to hide them.
            return ret;
        }

        /** @hide */
        public Event getObfuscatedNotificationEvent() {
            final Event ret = new Event(this);
            ret.mNotificationChannelId = OBFUSCATED_NOTIFICATION_CHANNEL_ID;
            return ret;
        }

        /**
         * Returns the locusId for this event if the event is of type {@link #LOCUS_ID_SET},
         * otherwise it returns null.
         * @hide
         */
        @Nullable
        public String getLocusId() {
            return mLocusId;
        }

        private void copyFrom(Event orig) {
            mPackage = orig.mPackage;
            mClass = orig.mClass;
            mInstanceId = orig.mInstanceId;
            mTaskRootPackage = orig.mTaskRootPackage;
            mTaskRootClass = orig.mTaskRootClass;
            mTimeStamp = orig.mTimeStamp;
            mEventType = orig.mEventType;
            mConfiguration = orig.mConfiguration;
            mShortcutId = orig.mShortcutId;
            mAction = orig.mAction;
            mContentType = orig.mContentType;
            mContentAnnotations = orig.mContentAnnotations;
            mFlags = orig.mFlags;
            mBucketAndReason = orig.mBucketAndReason;
            mNotificationChannelId = orig.mNotificationChannelId;
            mLocusId = orig.mLocusId;
        }
    }

    // Only used when creating the resulting events. Not used for reading/unparceling.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private List<Event> mEventsToWrite = null;

    // Only used for reading/unparceling events.
    @UnsupportedAppUsage
    private Parcel mParcel = null;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mEventCount;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mIndex = 0;

    // Only used when parceling events. If false, task roots will be omitted from the parcel
    private final boolean mIncludeTaskRoots;

    /*
     * In order to save space, since ComponentNames will be duplicated everywhere,
     * we use a map and index into it.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private String[] mStringPool;

    /**
     * Construct the iterator from a parcel.
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public UsageEvents(Parcel in) {
        byte[] bytes = in.readBlob();
        Parcel data = Parcel.obtain();
        data.unmarshall(bytes, 0, bytes.length);
        data.setDataPosition(0);
        mEventCount = data.readInt();
        mIndex = data.readInt();
        if (mEventCount > 0) {
            mStringPool = data.createStringArray();

            final int listByteLength = data.readInt();
            final int positionInParcel = data.readInt();
            mParcel = Parcel.obtain();
            mParcel.setDataPosition(0);
            mParcel.appendFrom(data, data.dataPosition(), listByteLength);
            mParcel.setDataSize(mParcel.dataPosition());
            mParcel.setDataPosition(positionInParcel);
        }
        mIncludeTaskRoots = true;
    }

    /**
     * Create an empty iterator.
     * {@hide}
     */
    UsageEvents() {
        mEventCount = 0;
        mIncludeTaskRoots = true;
    }

    /**
     * Construct the iterator in preparation for writing it to a parcel.
     * Defaults to excluding task roots from the parcel.
     * {@hide}
     */
    public UsageEvents(List<Event> events, String[] stringPool) {
        this(events, stringPool, false);
    }

    /**
     * Construct the iterator in preparation for writing it to a parcel.
     * {@hide}
     */
    public UsageEvents(List<Event> events, String[] stringPool, boolean includeTaskRoots) {
        mStringPool = stringPool;
        mEventCount = events.size();
        mEventsToWrite = events;
        mIncludeTaskRoots = includeTaskRoots;
    }

    /**
     * Returns whether or not there are more events to read using
     * {@link #getNextEvent(android.app.usage.UsageEvents.Event)}.
     *
     * @return true if there are more events, false otherwise.
     */
    public boolean hasNextEvent() {
        return mIndex < mEventCount;
    }

    /**
     * Retrieve the next {@link android.app.usage.UsageEvents.Event} from the collection and put the
     * resulting data into {@code eventOut}.
     *
     * @param eventOut The {@link android.app.usage.UsageEvents.Event} object that will receive the
     *                 next event data.
     * @return true if an event was available, false if there are no more events.
     */
    public boolean getNextEvent(Event eventOut) {
        if (mIndex >= mEventCount) {
            return false;
        }

        if (mParcel != null) {
            readEventFromParcel(mParcel, eventOut);
        } else {
            eventOut.copyFrom(mEventsToWrite.get(mIndex));
        }

        mIndex++;
        if (mIndex >= mEventCount && mParcel != null) {
            mParcel.recycle();
            mParcel = null;
        }
        return true;
    }

    /**
     * Resets the collection so that it can be iterated over from the beginning.
     *
     * @hide When this object is iterated to completion, the parcel is destroyed and
     * so resetToStart doesn't work.
     */
    public void resetToStart() {
        mIndex = 0;
        if (mParcel != null) {
            mParcel.setDataPosition(0);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int findStringIndex(String str) {
        final int index = Arrays.binarySearch(mStringPool, str);
        if (index < 0) {
            throw new IllegalStateException("String '" + str + "' is not in the string pool");
        }
        return index;
    }

    /**
     * Writes a single event to the parcel. Modify this when updating {@link Event}.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private void writeEventToParcel(Event event, Parcel p, int flags) {
        final int packageIndex;
        if (event.mPackage != null) {
            packageIndex = findStringIndex(event.mPackage);
        } else {
            packageIndex = -1;
        }

        final int classIndex;
        if (event.mClass != null) {
            classIndex = findStringIndex(event.mClass);
        } else {
            classIndex = -1;
        }

        final int taskRootPackageIndex;
        if (mIncludeTaskRoots && event.mTaskRootPackage != null) {
            taskRootPackageIndex = findStringIndex(event.mTaskRootPackage);
        } else {
            taskRootPackageIndex = -1;
        }

        final int taskRootClassIndex;
        if (mIncludeTaskRoots && event.mTaskRootClass != null) {
            taskRootClassIndex = findStringIndex(event.mTaskRootClass);
        } else {
            taskRootClassIndex = -1;
        }
        p.writeInt(packageIndex);
        p.writeInt(classIndex);
        p.writeInt(event.mInstanceId);
        p.writeInt(taskRootPackageIndex);
        p.writeInt(taskRootClassIndex);
        p.writeInt(event.mEventType);
        p.writeLong(event.mTimeStamp);

        switch (event.mEventType) {
            case Event.CONFIGURATION_CHANGE:
                event.mConfiguration.writeToParcel(p, flags);
                break;
            case Event.SHORTCUT_INVOCATION:
                p.writeString(event.mShortcutId);
                break;
            case Event.CHOOSER_ACTION:
                p.writeString(event.mAction);
                p.writeString(event.mContentType);
                p.writeStringArray(event.mContentAnnotations);
                break;
            case Event.STANDBY_BUCKET_CHANGED:
                p.writeInt(event.mBucketAndReason);
                break;
            case Event.NOTIFICATION_INTERRUPTION:
                p.writeString(event.mNotificationChannelId);
                break;
            case Event.LOCUS_ID_SET:
                p.writeString(event.mLocusId);
                break;
        }
        p.writeInt(event.mFlags);
    }

    /**
     * Reads a single event from the parcel. Modify this when updating {@link Event}.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private void readEventFromParcel(Parcel p, Event eventOut) {
        final int packageIndex = p.readInt();
        if (packageIndex >= 0) {
            eventOut.mPackage = mStringPool[packageIndex];
        } else {
            eventOut.mPackage = null;
        }

        final int classIndex = p.readInt();
        if (classIndex >= 0) {
            eventOut.mClass = mStringPool[classIndex];
        } else {
            eventOut.mClass = null;
        }
        eventOut.mInstanceId = p.readInt();

        final int taskRootPackageIndex = p.readInt();
        if (taskRootPackageIndex >= 0) {
            eventOut.mTaskRootPackage = mStringPool[taskRootPackageIndex];
        } else {
            eventOut.mTaskRootPackage = null;
        }

        final int taskRootClassIndex = p.readInt();
        if (taskRootClassIndex >= 0) {
            eventOut.mTaskRootClass = mStringPool[taskRootClassIndex];
        } else {
            eventOut.mTaskRootClass = null;
        }

        eventOut.mEventType = p.readInt();
        eventOut.mTimeStamp = p.readLong();

        // Fill out the event-dependant fields.
        eventOut.mConfiguration = null;
        eventOut.mShortcutId = null;
        eventOut.mAction = null;
        eventOut.mContentType = null;
        eventOut.mContentAnnotations = null;
        eventOut.mNotificationChannelId = null;
        eventOut.mLocusId = null;

        switch (eventOut.mEventType) {
            case Event.CONFIGURATION_CHANGE:
                // Extract the configuration for configuration change events.
                eventOut.mConfiguration = Configuration.CREATOR.createFromParcel(p);
                break;
            case Event.SHORTCUT_INVOCATION:
                eventOut.mShortcutId = p.readString();
                break;
            case Event.CHOOSER_ACTION:
                eventOut.mAction = p.readString();
                eventOut.mContentType = p.readString();
                eventOut.mContentAnnotations = p.createStringArray();
                break;
            case Event.STANDBY_BUCKET_CHANGED:
                eventOut.mBucketAndReason = p.readInt();
                break;
            case Event.NOTIFICATION_INTERRUPTION:
                eventOut.mNotificationChannelId = p.readString();
                break;
            case Event.LOCUS_ID_SET:
                eventOut.mLocusId = p.readString();
                break;
        }
        eventOut.mFlags = p.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        Parcel data = Parcel.obtain();
        data.writeInt(mEventCount);
        data.writeInt(mIndex);
        if (mEventCount > 0) {
            data.writeStringArray(mStringPool);

            if (mEventsToWrite != null) {
                // Write out the events
                Parcel p = Parcel.obtain();
                try {
                    p.setDataPosition(0);
                    for (int i = 0; i < mEventCount; i++) {
                        final Event event = mEventsToWrite.get(i);
                        writeEventToParcel(event, p, flags);
                    }

                    final int listByteLength = p.dataPosition();

                    // Write the total length of the data.
                    data.writeInt(listByteLength);

                    // Write our current position into the data.
                    data.writeInt(0);

                    // Write the data.
                    data.appendFrom(p, 0, listByteLength);
                } finally {
                    p.recycle();
                }

            } else if (mParcel != null) {
                // Write the total length of the data.
                data.writeInt(mParcel.dataSize());

                // Write out current position into the data.
                data.writeInt(mParcel.dataPosition());

                // Write the data.
                data.appendFrom(mParcel, 0, mParcel.dataSize());
            } else {
                throw new IllegalStateException(
                        "Either mParcel or mEventsToWrite must not be null");
            }
        }
        // Data can be too large for a transact. Write the data as a Blob, which will be written to
        // ashmem if too large.
        dest.writeBlob(data.marshall());
    }

    public static final @android.annotation.NonNull Creator<UsageEvents> CREATOR = new Creator<UsageEvents>() {
        @Override
        public UsageEvents createFromParcel(Parcel source) {
            return new UsageEvents(source);
        }

        @Override
        public UsageEvents[] newArray(int size) {
            return new UsageEvents[size];
        }
    };
}
