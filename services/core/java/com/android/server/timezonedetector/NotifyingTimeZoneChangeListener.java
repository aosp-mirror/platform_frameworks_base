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

package com.android.server.timezonedetector;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.provider.Settings.ACTION_DATE_SETTINGS;

import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_LOCATION;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_MANUAL;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_TELEPHONY;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_UNKNOWN;

import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.icu.text.DateFormat;
import android.icu.text.SimpleDateFormat;
import android.icu.util.TimeZone;
import android.os.Handler;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.LocalServices;
import com.android.server.flags.Flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An implementation of {@link TimeZoneChangeListener} that fires notifications.
 */
public class NotifyingTimeZoneChangeListener implements TimeZoneChangeListener {
    @IntDef({STATUS_UNKNOWN, STATUS_UNTRACKED, STATUS_REJECTED,
            STATUS_ACCEPTED, STATUS_SUPERSEDED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @interface TimeZoneChangeStatus {}

    /** Used to indicate the status could not be inferred. */
    @TimeZoneChangeStatus
    static final int STATUS_UNKNOWN = 0;
    /** Used to indicate the change is not one that needs to be tracked. */
    @TimeZoneChangeStatus
    static final int STATUS_UNTRACKED = 1;
    @TimeZoneChangeStatus
    static final int STATUS_REJECTED = 2;
    @TimeZoneChangeStatus
    static final int STATUS_ACCEPTED = 3;
    /** Used to indicate a change was superseded before its status could be determined. */
    @TimeZoneChangeStatus
    static final int STATUS_SUPERSEDED = 4;

    @IntDef({SIGNAL_TYPE_UNKNOWN, SIGNAL_TYPE_NONE, SIGNAL_TYPE_NOTIFICATION,
            SIGNAL_TYPE_HEURISTIC})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @interface SignalType {}

    /** Used when the signal type cannot be inferred. */
    @SignalType
    static final int SIGNAL_TYPE_UNKNOWN = 0;
    /** Used when the status is not one that needs a signal type. */
    @SignalType
    static final int SIGNAL_TYPE_NONE = 1;
    @SignalType
    static final int SIGNAL_TYPE_NOTIFICATION = 2;
    @SignalType
    static final int SIGNAL_TYPE_HEURISTIC = 3;

    private static final int MAX_EVENTS_TO_TRACK = 10;

    @VisibleForTesting
    @DurationMillisLong
    static final long AUTO_REVERT_THRESHOLD = Duration.ofMinutes(15).toMillis();

    private static final String TAG = "TimeZoneChangeTracker";
    private static final String NOTIFICATION_TAG = "TimeZoneDetector";
    private static final int TZ_CHANGE_NOTIFICATION_ID = 1001;

    private static final String ACTION_NOTIFICATION_DELETED =
            "com.android.server.timezonedetector.TimeZoneNotificationDeleted";

    private static final String NOTIFICATION_INTENT_EXTRA_USER_ID = "user_id";
    private static final String NOTIFICATION_INTENT_EXTRA_CHANGE_ID = "change_id";

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final ActivityManagerInternal mActivityManagerInternal;

    // For scheduling callbacks
    private final Handler mHandler;
    private final ServiceConfigAccessor mServiceConfigAccessor;
    private final AtomicInteger mNextChangeEventId = new AtomicInteger(1);

    private final Resources mRes = Resources.getSystem();

    @GuardedBy("mTimeZoneChangeRecord")
    private final ReferenceWithHistory<TimeZoneChangeRecord> mTimeZoneChangeRecord =
            new ReferenceWithHistory<>(MAX_EVENTS_TO_TRACK);

    private final BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_NOTIFICATION_DELETED:
                    int notifiedUserId = intent.getIntExtra(
                            NOTIFICATION_INTENT_EXTRA_USER_ID, UserHandle.USER_NULL);
                    int changeEventId = intent.getIntExtra(
                            NOTIFICATION_INTENT_EXTRA_CHANGE_ID, 0);
                    notificationSwipedAway(notifiedUserId, changeEventId);
                    break;
                default:
                    Log.d(TAG, "Unknown intent action received: " + intent.getAction());
            }
        }
    };

    @NonNull
    private final Environment mEnvironment;

    private final Object mConfigurationLock = new Object();
    @GuardedBy("mConfigurationLock")
    private ConfigurationInternal mConfigurationInternal;
    @GuardedBy("mConfigurationLock")
    private boolean mIsRegistered;

    private int mAcceptedManualChanges;
    private int mAcceptedTelephonyChanges;
    private int mAcceptedLocationChanges;
    private int mAcceptedUnknownChanges;
    private int mRejectedTelephonyChanges;
    private int mRejectedLocationChanges;
    private int mRejectedUnknownChanges;

    /** Create and initialise a new {@code TimeZoneChangeTrackerImpl} */
    @RequiresPermission("android.permission.INTERACT_ACROSS_USERS_FULL")
    public static NotifyingTimeZoneChangeListener create(Handler handler, Context context,
            ServiceConfigAccessor serviceConfigAccessor,
            @NonNull Environment environment) {
        NotifyingTimeZoneChangeListener changeTracker =
                new NotifyingTimeZoneChangeListener(handler,
                        context,
                        serviceConfigAccessor,
                        context.getSystemService(NotificationManager.class),
                        environment);

        // Pretend there was an update to initialize configuration.
        changeTracker.handleConfigurationUpdate();

        return changeTracker;
    }

    @VisibleForTesting
    NotifyingTimeZoneChangeListener(Handler handler, Context context,
            ServiceConfigAccessor serviceConfigAccessor, NotificationManager notificationManager,
            @NonNull Environment environment) {
        mHandler = Objects.requireNonNull(handler);
        mContext = Objects.requireNonNull(context);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);
        mServiceConfigAccessor.addConfigurationInternalChangeListener(
                this::handleConfigurationUpdate);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mNotificationManager = notificationManager;
        mEnvironment = Objects.requireNonNull(environment);
    }

    @RequiresPermission("android.permission.INTERACT_ACROSS_USERS_FULL")
    private void handleConfigurationUpdate() {
        synchronized (mConfigurationLock) {
            ConfigurationInternal oldConfigurationInternal = mConfigurationInternal;
            mConfigurationInternal = mServiceConfigAccessor.getCurrentUserConfigurationInternal();

            if (areNotificationsEnabled() && isNotificationTrackingSupported()) {
                if (!mIsRegistered) {
                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(ACTION_NOTIFICATION_DELETED);
                    mContext.registerReceiverForAllUsers(mNotificationReceiver, intentFilter,
                            /* broadcastPermission= */ null, mHandler, RECEIVER_NOT_EXPORTED);
                    mIsRegistered = true;
                }
            } else if (mIsRegistered) {
                mContext.unregisterReceiver(mNotificationReceiver);
                mIsRegistered = false;
            }

            if (oldConfigurationInternal != null) {
                boolean userChanged =
                        oldConfigurationInternal.getUserId() != mConfigurationInternal.getUserId();

                if (!areNotificationsEnabled() || userChanged) {
                    // Clear any notifications that are no longer needed.
                    clearNotificationForUser(oldConfigurationInternal.getUserId());
                }
            }
        }
    }

    private void notificationSwipedAway(@UserIdInt int userId, int changeEventId) {
        // User swiping away a notification is interpreted as "user accepted the change".
        if (isNotificationTrackingSupported()) {
            markChangeAsAccepted(changeEventId, userId, SIGNAL_TYPE_NOTIFICATION);
        }
    }

    private boolean areNotificationsEnabled() {
        synchronized (mConfigurationLock) {
            return mConfigurationInternal.getNotificationsEnabledBehavior();
        }
    }

    private boolean isNotificationTrackingSupported() {
        synchronized (mConfigurationLock) {
            return mConfigurationInternal.isNotificationTrackingSupported();
        }
    }

    private boolean isManualChangeTrackingSupported() {
        synchronized (mConfigurationLock) {
            return mConfigurationInternal.isManualChangeTrackingSupported();
        }
    }

    /**
     * Marks a change event as accepted by the user
     *
     * <p>A change event is said to be accepted when the client does not revert an automatic time
     * zone change by manually changing the time zone within {@code AUTO_REVERT_THRESHOLD} of the
     * notification being received.
     */
    private void markChangeAsAccepted(int changeEventId, @UserIdInt int userId,
            @SignalType int signalType) {
        if (!isUserIdCurrentUser(userId)) {
            return;
        }

        synchronized (mTimeZoneChangeRecord) {
            TimeZoneChangeRecord lastTimeZoneChangeRecord = mTimeZoneChangeRecord.get();
            if (lastTimeZoneChangeRecord != null) {
                if (lastTimeZoneChangeRecord.getId() != changeEventId) {
                    // To be accepted, the change being accepted has to still be the latest.
                    return;
                }
                if (lastTimeZoneChangeRecord.getStatus() != STATUS_UNKNOWN) {
                    // Change status has already been set.
                    return;
                }
                lastTimeZoneChangeRecord.setAccepted(signalType);

                switch (lastTimeZoneChangeRecord.getEvent().getOrigin()) {
                    case ORIGIN_MANUAL:
                        mAcceptedManualChanges += 1;
                        break;
                    case ORIGIN_TELEPHONY:
                        mAcceptedTelephonyChanges += 1;
                        break;
                    case ORIGIN_LOCATION:
                        mAcceptedLocationChanges += 1;
                        break;
                    default:
                        mAcceptedUnknownChanges += 1;
                        break;
                }
            }
        }
    }

    private boolean isUserIdCurrentUser(@UserIdInt int userId) {
        synchronized (mConfigurationLock) {
            return userId == mConfigurationInternal.getUserId();
        }
    }

    /**
     * Marks a change event as rejected by the user
     *
     * <p>A change event is said to be rejected when the client reverts an automatic time zone
     * change by manually changing the time zone within {@code AUTO_REVERT_THRESHOLD} of the
     * notification being received.
     */
    @GuardedBy("mTimeZoneChangeRecord")
    private void markChangeAsRejected(int changeEventId, @UserIdInt int userId,
            @SignalType int signalType) {
        if (!isUserIdCurrentUser(userId)) {
            return;
        }

        TimeZoneChangeRecord lastTimeZoneChangeRecord = mTimeZoneChangeRecord.get();
        if (lastTimeZoneChangeRecord != null) {
            if (lastTimeZoneChangeRecord.getId() != changeEventId) {
                // To be accepted, the change being accepted has to still be the latest.
                return;
            }
            if (lastTimeZoneChangeRecord.getStatus() != STATUS_UNKNOWN) {
                // Change status has already been set.
                return;
            }
            lastTimeZoneChangeRecord.setRejected(signalType);

            switch (lastTimeZoneChangeRecord.getEvent().getOrigin()) {
                case ORIGIN_TELEPHONY:
                    mRejectedTelephonyChanges += 1;
                    break;
                case ORIGIN_LOCATION:
                    mRejectedLocationChanges += 1;
                    break;
                default:
                    mRejectedUnknownChanges += 1;
                    break;
            }
        }
    }

    @Override
    public void process(TimeZoneChangeEvent changeEvent) {
        final TimeZoneChangeRecord trackedChangeEvent;

        synchronized (mTimeZoneChangeRecord) {
            fixPotentialHistoryCorruption(changeEvent);

            TimeZoneChangeRecord lastTimeZoneChangeRecord = mTimeZoneChangeRecord.get();
            int changeEventId = mNextChangeEventId.getAndIncrement();
            trackedChangeEvent = new TimeZoneChangeRecord(changeEventId, changeEvent);

            if (isManualChangeTrackingSupported()) {
                // Time-based heuristic for "user is undoing a mistake made by the time zone
                // detector".
                if (lastTimeZoneChangeRecord != null
                        && lastTimeZoneChangeRecord.getStatus() == STATUS_UNKNOWN) {
                    TimeZoneChangeEvent lastChangeEvent = lastTimeZoneChangeRecord.getEvent();

                    if (shouldRejectChangeEvent(changeEvent, lastChangeEvent)) {
                        markChangeAsRejected(lastTimeZoneChangeRecord.getId(),
                                changeEvent.getUserId(), SIGNAL_TYPE_HEURISTIC);
                    }
                }

                // Schedule a callback for the new time zone so that we can implement "user accepted
                // the change because they didn't revert it"
                scheduleChangeAcceptedHeuristicCallback(trackedChangeEvent, AUTO_REVERT_THRESHOLD);
            }

            if (lastTimeZoneChangeRecord != null
                    && lastTimeZoneChangeRecord.getStatus() == STATUS_UNKNOWN) {
                lastTimeZoneChangeRecord.setStatus(STATUS_SUPERSEDED, SIGNAL_TYPE_NONE);
            }

            if (changeEvent.getOrigin() == ORIGIN_MANUAL) {
                trackedChangeEvent.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);
            }

            mTimeZoneChangeRecord.set(trackedChangeEvent);
        }

        if (areNotificationsEnabled()) {
            int currentUserId;
            synchronized (mConfigurationLock) {
                currentUserId = mConfigurationInternal.getUserId();
            }

            if (changeEvent.getOrigin() == ORIGIN_MANUAL) {
                // Just clear any existing notification.
                clearNotificationForUser(currentUserId);
            } else {
                notifyOfTimeZoneChange(currentUserId, trackedChangeEvent);
            }
        }
    }

    /**
     * Checks if the history of time zone change events is corrupted and fixes it, if needed
     *
     * <p>The history of changes is considered corrupted if a transition is missing. That is, if
     * {@code events[i-1].newTimeZoneId != events[i].oldTimeZoneId}. In that case, a "synthetic"
     * event is added to the history to bridge the gap between the last reported time zone ID and
     * the time zone ID that the new event is replacing.
     *
     * <p>Note: we are not expecting this method to be required often (if ever) but in the
     * eventuality that an event gets lost, we want to keep the history coherent.
     */
    @GuardedBy("mTimeZoneChangeRecord")
    private void fixPotentialHistoryCorruption(TimeZoneChangeEvent changeEvent) {
        TimeZoneChangeRecord lastTimeZoneChangeRecord = mTimeZoneChangeRecord.get();

        if (lastTimeZoneChangeRecord != null) {
            // The below block takes care of the case where we are missing record(s) of time
            // zone changes
            TimeZoneChangeEvent lastChangeEvent = lastTimeZoneChangeRecord.getEvent();
            if (!changeEvent.getOldZoneId().equals(lastChangeEvent.getNewZoneId())) {
                int changeEventId = mNextChangeEventId.getAndIncrement();
                TimeZoneChangeEvent syntheticChangeEvent = new TimeZoneChangeEvent(
                        mEnvironment.elapsedRealtimeMillis(), mEnvironment.currentTimeMillis(),
                        ORIGIN_UNKNOWN, UserHandle.USER_NULL, lastChangeEvent.getNewZoneId(),
                        changeEvent.getOldZoneId(), 0, "Synthetic");
                TimeZoneChangeRecord syntheticTrackedChangeEvent =
                        new TimeZoneChangeRecord(changeEventId, syntheticChangeEvent);
                syntheticTrackedChangeEvent.setStatus(STATUS_SUPERSEDED, SIGNAL_TYPE_NONE);

                mTimeZoneChangeRecord.set(syntheticTrackedChangeEvent);

                // Housekeeping for the last reported time zone change: try to ensure it has
                // a status too.
                if (lastTimeZoneChangeRecord.getStatus() == STATUS_UNKNOWN) {
                    lastTimeZoneChangeRecord.setStatus(STATUS_SUPERSEDED, SIGNAL_TYPE_NONE);
                }
            }
        }
    }

    private static boolean shouldRejectChangeEvent(TimeZoneChangeEvent changeEvent,
            TimeZoneChangeEvent lastChangeEvent) {
        return changeEvent.getOrigin() == ORIGIN_MANUAL
                && lastChangeEvent.getOrigin() != ORIGIN_MANUAL
                && (changeEvent.getElapsedRealtimeMillis()
                - lastChangeEvent.getElapsedRealtimeMillis() < AUTO_REVERT_THRESHOLD);
    }

    private void scheduleChangeAcceptedHeuristicCallback(
            TimeZoneChangeRecord trackedChangeEvent,
            @DurationMillisLong long delayMillis) {
        mHandler.postDelayed(
                () -> changeAcceptedTimeHeuristicCallback(trackedChangeEvent.getId()), delayMillis);
    }

    private void changeAcceptedTimeHeuristicCallback(int changeEventId) {
        if (isManualChangeTrackingSupported()) {
            int currentUserId = mActivityManagerInternal.getCurrentUserId();
            markChangeAsAccepted(changeEventId, currentUserId, SIGNAL_TYPE_HEURISTIC);
        }
    }

    private void clearNotificationForUser(@UserIdInt int userId) {
        mNotificationManager.cancelAsUser(NOTIFICATION_TAG, TZ_CHANGE_NOTIFICATION_ID,
                UserHandle.of(userId));
    }

    private void notifyOfTimeZoneChange(@UserIdInt int userId,
            TimeZoneChangeRecord trackedChangeEvent) {
        TimeZoneChangeEvent changeEvent = trackedChangeEvent.getEvent();

        if (!Flags.datetimeNotifications() || !areNotificationsEnabled()) {
            return;
        }

        TimeZone oldTimeZone = TimeZone.getTimeZone(changeEvent.getOldZoneId());
        TimeZone newTimeZone = TimeZone.getTimeZone(changeEvent.getNewZoneId());
        long unixEpochTimeMillis = changeEvent.getUnixEpochTimeMillis();
        boolean hasOffsetChanged = newTimeZone.getOffset(unixEpochTimeMillis)
                == oldTimeZone.getOffset(unixEpochTimeMillis);

        if (hasOffsetChanged) {
            // If the time zone ID changes but not the offset, we do not send a notification to
            // the user. This is to prevent spamming users and reduce the number of notification
            // we send overall.
            Log.d(TAG, "The time zone ID has changed but the offset remains the same.");
            return;
        }

        final CharSequence title = mRes.getString(R.string.time_zone_change_notification_title);
        final CharSequence body = getNotificationBody(newTimeZone, unixEpochTimeMillis);

        final Intent clickNotificationIntent = new Intent(ACTION_DATE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final Intent clearNotificationIntent = new Intent(ACTION_NOTIFICATION_DELETED)
                .putExtra(NOTIFICATION_INTENT_EXTRA_USER_ID, userId)
                .putExtra(NOTIFICATION_INTENT_EXTRA_CHANGE_ID, trackedChangeEvent.getId());

        Notification notification = new Notification.Builder(mContext,
                SystemNotificationChannels.TIME)
                .setSmallIcon(R.drawable.btn_clock_material)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(true)
                .setColor(mContext.getColor(R.color.system_notification_accent_color))
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(PendingIntent.getActivityAsUser(
                        mContext,
                        /* requestCode= */ 0,
                        clickNotificationIntent,
                        /* flags= */ FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE,
                        /* options= */ null,
                        UserHandle.of(userId)))
                .setDeleteIntent(PendingIntent.getBroadcast(
                        mContext,
                        /* requestCode= */ 0,
                        clearNotificationIntent,
                        /* flags= */ FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE))
                .setAutoCancel(true) // auto-clear notification on selection
                .build();

        mNotificationManager.notifyAsUser(NOTIFICATION_TAG,
                TZ_CHANGE_NOTIFICATION_ID, notification, UserHandle.of(userId));
    }

    private CharSequence getNotificationBody(TimeZone newTimeZone, long unixEpochTimeMillis) {
        DateFormat timeFormat = SimpleDateFormat.getInstanceForSkeleton("zzzz");
        DateFormat offsetFormat = SimpleDateFormat.getInstanceForSkeleton("ZZZZ");

        String newTime = formatInZone(timeFormat, newTimeZone, unixEpochTimeMillis);
        String newOffset = formatInZone(offsetFormat, newTimeZone, unixEpochTimeMillis);

        return mRes.getString(R.string.time_zone_change_notification_body, newTime, newOffset);
    }

    private static String formatInZone(DateFormat timeFormat, TimeZone timeZone,
            long unixEpochTimeMillis) {
        timeFormat.setTimeZone(timeZone);
        return timeFormat.format(unixEpochTimeMillis);
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
        synchronized (mConfigurationLock) {
            pw.println("currentUserId=" + mConfigurationInternal.getUserId());
            pw.println("notificationsEnabledBehavior="
                    + mConfigurationInternal.getNotificationsEnabledBehavior());
            pw.println("notificationTrackingSupported="
                    + mConfigurationInternal.isNotificationTrackingSupported());
            pw.println("manualChangeTrackingSupported="
                    + mConfigurationInternal.isManualChangeTrackingSupported());
        }

        pw.println("mAcceptedLocationChanges=" + mAcceptedLocationChanges);
        pw.println("mAcceptedManualChanges=" + mAcceptedManualChanges);
        pw.println("mAcceptedTelephonyChanges=" + mAcceptedTelephonyChanges);
        pw.println("mAcceptedUnknownChanges=" + mAcceptedUnknownChanges);
        pw.println("mRejectedLocationChanges=" + mRejectedLocationChanges);
        pw.println("mRejectedTelephonyChanges=" + mRejectedTelephonyChanges);
        pw.println("mRejectedUnknownChanges=" + mRejectedUnknownChanges);
        pw.println("mNextChangeEventId=" + mNextChangeEventId);

        pw.println("mTimeZoneChangeRecord:");
        pw.increaseIndent();
        synchronized (mTimeZoneChangeRecord) {
            mTimeZoneChangeRecord.dump(pw);
        }
        pw.decreaseIndent();
    }

    @VisibleForTesting
    static class TimeZoneChangeRecord {

        private final int mId;
        private final TimeZoneChangeEvent mEvent;
        private @TimeZoneChangeStatus int mStatus = STATUS_UNKNOWN;
        private @SignalType int mSignalType = SIGNAL_TYPE_UNKNOWN;

        TimeZoneChangeRecord(int id, TimeZoneChangeEvent event) {
            mId = id;
            mEvent = Objects.requireNonNull(event);
        }

        public int getId() {
            return mId;
        }

        public @TimeZoneChangeStatus int getStatus() {
            return mStatus;
        }

        public void setAccepted(int signalType) {
            setStatus(STATUS_ACCEPTED, signalType);
        }

        public void setRejected(int signalType) {
            setStatus(STATUS_REJECTED, signalType);
        }

        public void setStatus(@TimeZoneChangeStatus int status, @SignalType int signalType) {
            mStatus = status;
            mSignalType = signalType;
        }

        public TimeZoneChangeEvent getEvent() {
            return mEvent;
        }

        @Override
        public String toString() {
            return "TrackedTimeZoneChangeEvent{"
                    + "mId=" + mId
                    + ", mEvent=" + mEvent
                    + ", mStatus=" + mStatus
                    + ", mSignalType=" + mSignalType
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof TimeZoneChangeRecord that) {
                return mId == that.mId
                        && mEvent.equals(that.mEvent)
                        && mStatus == that.mStatus
                        && mSignalType == that.mSignalType;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId, mEvent, mStatus, mSignalType);
        }
    }

    @VisibleForTesting
    TimeZoneChangeRecord getLastTimeZoneChangeRecord() {
        synchronized (mTimeZoneChangeRecord) {
            return mTimeZoneChangeRecord.get();
        }
    }
}
