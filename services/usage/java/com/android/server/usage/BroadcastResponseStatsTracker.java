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

package com.android.server.usage;

import static android.app.ActivityManager.procStateToString;

import static com.android.server.usage.UsageStatsService.DEBUG_RESPONSE_STATS;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager.ProcessState;
import android.app.usage.BroadcastResponseStats;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class BroadcastResponseStatsTracker {
    private static final String TAG = "ResponseStatsTracker";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NOTIFICATION_EVENT"}, value = {
            NOTIFICATION_EVENT_POSTED,
            NOTIFICATION_EVENT_UPDATED,
            NOTIFICATION_EVENT_CANCELLED
    })
    public @interface NotificationEvent {}

    private static final int NOTIFICATION_EVENT_POSTED = 0;
    private static final int NOTIFICATION_EVENT_UPDATED = 1;
    private static final int NOTIFICATION_EVENT_CANCELLED = 2;

    private final Object mLock = new Object();

    /**
     * Contains the mapping of user -> UserBroadcastEvents data.
     */
    @GuardedBy("mLock")
    private SparseArray<UserBroadcastEvents> mUserBroadcastEvents = new SparseArray<>();

    /**
     * Contains the mapping of sourceUid -> {targetUser -> UserBroadcastResponseStats} data.
     * Here sourceUid refers to the uid that sent a broadcast and targetUser is the user that the
     * broadcast was directed to.
     */
    @GuardedBy("mLock")
    private SparseArray<SparseArray<UserBroadcastResponseStats>> mUserResponseStats =
            new SparseArray<>();

    private AppStandbyInternal mAppStandby;

    BroadcastResponseStatsTracker(@NonNull AppStandbyInternal appStandby) {
        mAppStandby = appStandby;
    }

    // TODO (206518114): Move all callbacks handling to a handler thread.
    void reportBroadcastDispatchEvent(int sourceUid, @NonNull String targetPackage,
            UserHandle targetUser, long idForResponseEvent,
            @ElapsedRealtimeLong long timestampMs, @ProcessState int targetUidProcState) {
        if (DEBUG_RESPONSE_STATS) {
            Slog.d(TAG, TextUtils.formatSimple("reportBroadcastDispatchEvent; "
                            + "srcUid=%d, tgtPkg=%s, tgtUsr=%d, id=%d, ts=%s, state=%s",
                    sourceUid, targetPackage, targetUser, idForResponseEvent,
                    TimeUtils.formatDuration(timestampMs), procStateToString(targetUidProcState)));
        }
        if (targetUidProcState <= mAppStandby.getBroadcastResponseFgThresholdState()) {
            // No need to track the broadcast response state while the target app is
            // in the foreground.
            return;
        }
        synchronized (mLock) {
            final LongSparseArray<BroadcastEvent> broadcastEvents =
                    getOrCreateBroadcastEventsLocked(targetPackage, targetUser);
            final BroadcastEvent broadcastEvent = new BroadcastEvent(
                    sourceUid, targetPackage, targetUser.getIdentifier(), idForResponseEvent);
            broadcastEvents.append(timestampMs, broadcastEvent);
            final BroadcastResponseStats responseStats =
                    getOrCreateBroadcastResponseStats(broadcastEvent);
            responseStats.incrementBroadcastsDispatchedCount(1);
        }
    }

    void reportNotificationPosted(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_POSTED, packageName, user, timestampMs);
    }

    void reportNotificationUpdated(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_UPDATED, packageName, user, timestampMs);

    }

    void reportNotificationCancelled(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_CANCELLED, packageName, user, timestampMs);
    }

    private void reportNotificationEvent(@NotificationEvent int event,
            @NonNull String packageName, UserHandle user, @ElapsedRealtimeLong long timestampMs) {
        if (DEBUG_RESPONSE_STATS) {
            Slog.d(TAG, TextUtils.formatSimple(
                    "reportNotificationEvent; event=<%s>, pkg=%s, usr=%d, ts=%s",
                    notificationEventToString(event), packageName, user.getIdentifier(),
                    TimeUtils.formatDuration(timestampMs)));
        }
        // TODO (206518114): Store last N events to dump for debugging purposes.
        synchronized (mLock) {
            final LongSparseArray<BroadcastEvent> broadcastEvents =
                    getBroadcastEventsLocked(packageName, user);
            if (broadcastEvents == null) {
                return;
            }
            // TODO (206518114): Add LongSparseArray.removeAtRange()
            for (int i = broadcastEvents.size() - 1; i >= 0; --i) {
                final long dispatchTimestampMs = broadcastEvents.keyAt(i);
                final long elapsedDurationMs = timestampMs - dispatchTimestampMs;
                if (elapsedDurationMs <= 0) {
                    continue;
                }
                if (dispatchTimestampMs >= timestampMs) {
                    continue;
                }
                if (elapsedDurationMs <= mAppStandby.getBroadcastResponseWindowDurationMs()) {
                    final BroadcastEvent broadcastEvent = broadcastEvents.valueAt(i);
                    final BroadcastResponseStats responseStats =
                            getBroadcastResponseStats(broadcastEvent);
                    if (responseStats == null) {
                        continue;
                    }
                    switch (event) {
                        case NOTIFICATION_EVENT_POSTED:
                            responseStats.incrementNotificationsPostedCount(1);
                            break;
                        case NOTIFICATION_EVENT_UPDATED:
                            responseStats.incrementNotificationsUpdatedCount(1);
                            break;
                        case NOTIFICATION_EVENT_CANCELLED:
                            responseStats.incrementNotificationsCancelledCount(1);
                            break;
                        default:
                            Slog.wtf(TAG, "Unknown event: " + event);
                    }
                }
                broadcastEvents.removeAt(i);
            }
        }
    }

    @NonNull BroadcastResponseStats queryBroadcastResponseStats(int callingUid,
            @NonNull String packageName, long id, @UserIdInt int userId) {
        final BroadcastResponseStats aggregatedResponseStats =
                new BroadcastResponseStats(packageName);
        synchronized (mLock) {
            final SparseArray<UserBroadcastResponseStats> responseStatsForCaller =
                    mUserResponseStats.get(callingUid);
            if (responseStatsForCaller == null) {
                return aggregatedResponseStats;
            }
            final UserBroadcastResponseStats responseStatsForUser =
                    responseStatsForCaller.get(userId);
            if (responseStatsForUser == null) {
                return aggregatedResponseStats;
            }
            responseStatsForUser.aggregateBroadcastResponseStats(aggregatedResponseStats,
                    packageName, id);
        }
        return aggregatedResponseStats;
    }

    void clearBroadcastResponseStats(int callingUid, @NonNull String packageName, long id,
            @UserIdInt int userId) {
        synchronized (mLock) {
            final SparseArray<UserBroadcastResponseStats> responseStatsForCaller =
                    mUserResponseStats.get(callingUid);
            if (responseStatsForCaller == null) {
                return;
            }
            final UserBroadcastResponseStats responseStatsForUser =
                    responseStatsForCaller.get(userId);
            if (responseStatsForUser == null) {
                return;
            }
            responseStatsForUser.clearBroadcastResponseStats(packageName, id);
        }
    }

    void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            mUserBroadcastEvents.remove(userId);

            for (int i = mUserResponseStats.size() - 1; i >= 0; --i) {
                mUserResponseStats.valueAt(i).remove(userId);
            }
        }
    }

    void onPackageRemoved(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            final UserBroadcastEvents userBroadcastEvents = mUserBroadcastEvents.get(userId);
            if (userBroadcastEvents != null) {
                userBroadcastEvents.onPackageRemoved(packageName);
            }

            for (int i = mUserResponseStats.size() - 1; i >= 0; --i) {
                final UserBroadcastResponseStats userResponseStats =
                        mUserResponseStats.valueAt(i).get(userId);
                if (userResponseStats != null) {
                    userResponseStats.onPackageRemoved(packageName);
                }
            }
        }
    }

    void onUidRemoved(int uid) {
        synchronized (mLock) {
            for (int i = mUserBroadcastEvents.size() - 1; i >= 0; --i) {
                mUserBroadcastEvents.valueAt(i).onUidRemoved(uid);
            }

            mUserResponseStats.remove(uid);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private LongSparseArray<BroadcastEvent> getBroadcastEventsLocked(
            @NonNull String packageName, UserHandle user) {
        final UserBroadcastEvents userBroadcastEvents = mUserBroadcastEvents.get(
                user.getIdentifier());
        if (userBroadcastEvents == null) {
            return null;
        }
        return userBroadcastEvents.getBroadcastEvents(packageName);
    }

    @GuardedBy("mLock")
    @NonNull
    private LongSparseArray<BroadcastEvent> getOrCreateBroadcastEventsLocked(
            @NonNull String packageName, UserHandle user) {
        UserBroadcastEvents userBroadcastEvents = mUserBroadcastEvents.get(user.getIdentifier());
        if (userBroadcastEvents == null) {
            userBroadcastEvents = new UserBroadcastEvents();
            mUserBroadcastEvents.put(user.getIdentifier(), userBroadcastEvents);
        }
        return userBroadcastEvents.getOrCreateBroadcastEvents(packageName);
    }

    @GuardedBy("mLock")
    @Nullable
    private BroadcastResponseStats getBroadcastResponseStats(
            @NonNull BroadcastEvent broadcastEvent) {
        final int sourceUid = broadcastEvent.getSourceUid();
        final SparseArray<UserBroadcastResponseStats> responseStatsForUid =
                mUserResponseStats.get(sourceUid);
        return getBroadcastResponseStats(responseStatsForUid, broadcastEvent);
    }

    @GuardedBy("mLock")
    @Nullable
    private BroadcastResponseStats getBroadcastResponseStats(
            @Nullable SparseArray<UserBroadcastResponseStats> responseStatsForUid,
            @NonNull BroadcastEvent broadcastEvent) {
        if (responseStatsForUid == null) {
            return null;
        }
        final UserBroadcastResponseStats userResponseStats = responseStatsForUid.get(
                broadcastEvent.getTargetUserId());
        if (userResponseStats == null) {
            return null;
        }
        return userResponseStats.getBroadcastResponseStats(broadcastEvent);
    }

    @GuardedBy("mLock")
    @NonNull
    private BroadcastResponseStats getOrCreateBroadcastResponseStats(
            @NonNull BroadcastEvent broadcastEvent) {
        final int sourceUid = broadcastEvent.getSourceUid();
        SparseArray<UserBroadcastResponseStats> userResponseStatsForUid =
                mUserResponseStats.get(sourceUid);
        if (userResponseStatsForUid == null) {
            userResponseStatsForUid = new SparseArray<>();
            mUserResponseStats.put(sourceUid, userResponseStatsForUid);
        }
        UserBroadcastResponseStats userResponseStats = userResponseStatsForUid.get(
                broadcastEvent.getTargetUserId());
        if (userResponseStats == null) {
            userResponseStats = new UserBroadcastResponseStats();
            userResponseStatsForUid.put(broadcastEvent.getTargetUserId(), userResponseStats);
        }
        return userResponseStats.getOrCreateBroadcastResponseStats(broadcastEvent);
    }

    @NonNull
    private String notificationEventToString(@NotificationEvent int event) {
        switch (event) {
            case NOTIFICATION_EVENT_POSTED:
                return "posted";
            case NOTIFICATION_EVENT_UPDATED:
                return "updated";
            case NOTIFICATION_EVENT_CANCELLED:
                return "cancelled";
            default:
                return String.valueOf(event);
        }
    }

    void dump(@NonNull IndentingPrintWriter ipw) {
        ipw.println("Broadcast response stats:");
        ipw.increaseIndent();

        synchronized (mLock) {
            dumpBroadcastEventsLocked(ipw);
            ipw.println();
            dumpResponseStatsLocked(ipw);
        }

        ipw.decreaseIndent();
    }

    @GuardedBy("mLock")
    private void dumpBroadcastEventsLocked(@NonNull IndentingPrintWriter ipw) {
        ipw.println("Broadcast events:");
        ipw.increaseIndent();
        for (int i = 0; i < mUserBroadcastEvents.size(); ++i) {
            final int userId = mUserBroadcastEvents.keyAt(i);
            final UserBroadcastEvents userBroadcastEvents = mUserBroadcastEvents.valueAt(i);
            ipw.println("User " + userId + ":");
            ipw.increaseIndent();
            userBroadcastEvents.dump(ipw);
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
    }

    @GuardedBy("mLock")
    private void dumpResponseStatsLocked(@NonNull IndentingPrintWriter ipw) {
        ipw.println("Response stats:");
        ipw.increaseIndent();
        for (int i = 0; i < mUserResponseStats.size(); ++i) {
            final int sourceUid = mUserResponseStats.keyAt(i);
            final SparseArray<UserBroadcastResponseStats> userBroadcastResponseStats =
                    mUserResponseStats.valueAt(i);
            ipw.println("Uid " + sourceUid + ":");
            ipw.increaseIndent();
            for (int j = 0; j < userBroadcastResponseStats.size(); ++j) {
                final int userId = userBroadcastResponseStats.keyAt(j);
                final UserBroadcastResponseStats broadcastResponseStats =
                        userBroadcastResponseStats.valueAt(j);
                ipw.println("User " + userId + ":");
                ipw.increaseIndent();
                broadcastResponseStats.dump(ipw);
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
    }
}

