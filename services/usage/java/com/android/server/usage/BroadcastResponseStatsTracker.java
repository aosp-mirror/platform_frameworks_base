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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager.ProcessState;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.BroadcastResponseStats;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongArrayQueue;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

class BroadcastResponseStatsTracker {
    static final String TAG = "ResponseStatsTracker";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NOTIFICATION_EVENT_TYPE_"}, value = {
            NOTIFICATION_EVENT_TYPE_POSTED,
            NOTIFICATION_EVENT_TYPE_UPDATED,
            NOTIFICATION_EVENT_TYPE_CANCELLED
    })
    public @interface NotificationEventType {}

    static final int NOTIFICATION_EVENT_TYPE_POSTED = 0;
    static final int NOTIFICATION_EVENT_TYPE_UPDATED = 1;
    static final int NOTIFICATION_EVENT_TYPE_CANCELLED = 2;

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

    /**
     * Cache of package names holding exempted roles.
     *
     * Contains the mapping of userId -> {roleName -> <packages>} data.
     */
    // TODO: Use SparseArrayMap to simplify the logic.
    @GuardedBy("mLock")
    private SparseArray<ArrayMap<String, List<String>>> mExemptedRoleHoldersCache =
            new SparseArray<>();
    private final OnRoleHoldersChangedListener mRoleHoldersChangedListener =
            this::onRoleHoldersChanged;

    private AppStandbyInternal mAppStandby;
    private BroadcastResponseStatsLogger mLogger;
    private RoleManager mRoleManager;
    private final Context mContext;

    BroadcastResponseStatsTracker(@NonNull AppStandbyInternal appStandby,
            @NonNull Context context) {
        mAppStandby = appStandby;
        mContext = context;
        mLogger = new BroadcastResponseStatsLogger();
    }

    void onSystemServicesReady(Context context) {
        mRoleManager = context.getSystemService(RoleManager.class);
        mRoleManager.addOnRoleHoldersChangedListenerAsUser(BackgroundThread.getExecutor(),
                mRoleHoldersChangedListener, UserHandle.ALL);
    }

    // TODO (206518114): Move all callbacks handling to a handler thread.
    void reportBroadcastDispatchEvent(int sourceUid, @NonNull String targetPackage,
            UserHandle targetUser, long idForResponseEvent,
            @ElapsedRealtimeLong long timestampMs, @ProcessState int targetUidProcState) {
        mLogger.logBroadcastDispatchEvent(sourceUid, targetPackage, targetUser,
                idForResponseEvent, timestampMs, targetUidProcState);
        if (targetUidProcState <= mAppStandby.getBroadcastResponseFgThresholdState()) {
            // No need to track the broadcast response stats while the target app is
            // in the foreground.
            return;
        }
        if (doesPackageHoldExemptedRole(targetPackage, targetUser)) {
            // Package holds an exempted role, so no need to track the broadcast response stats.
            return;
        }
        if (doesPackageHoldExemptedPermission(targetPackage, targetUser)) {
            // Package holds an exempted permission, so no need to track the broadcast response
            // stats
            return;
        }
        synchronized (mLock) {
            final ArraySet<BroadcastEvent> broadcastEvents =
                    getOrCreateBroadcastEventsLocked(targetPackage, targetUser);
            final BroadcastEvent broadcastEvent = getOrCreateBroadcastEvent(broadcastEvents,
                    sourceUid, targetPackage, targetUser.getIdentifier(), idForResponseEvent);
            broadcastEvent.addTimestampMs(timestampMs);

            // Delete any old broadcast event related data so that we don't keep accumulating them.
            recordAndPruneOldBroadcastDispatchTimestamps(broadcastEvent);
        }
    }

    void reportNotificationPosted(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_TYPE_POSTED, packageName, user, timestampMs);
    }

    void reportNotificationUpdated(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_TYPE_UPDATED, packageName, user, timestampMs);

    }

    void reportNotificationCancelled(@NonNull String packageName, UserHandle user,
            @ElapsedRealtimeLong long timestampMs) {
        reportNotificationEvent(NOTIFICATION_EVENT_TYPE_CANCELLED, packageName, user, timestampMs);
    }

    private void reportNotificationEvent(@NotificationEventType int event,
            @NonNull String packageName, UserHandle user, @ElapsedRealtimeLong long timestampMs) {
        mLogger.logNotificationEvent(event, packageName, user, timestampMs);
        synchronized (mLock) {
            final ArraySet<BroadcastEvent> broadcastEvents =
                    getBroadcastEventsLocked(packageName, user);
            if (broadcastEvents == null) {
                return;
            }
            final long broadcastResponseWindowDurationMs =
                    mAppStandby.getBroadcastResponseWindowDurationMs();
            final long broadcastsSessionWithResponseDurationMs =
                    mAppStandby.getBroadcastSessionsWithResponseDurationMs();
            final boolean recordAllBroadcastsSessionsWithinResponseWindow =
                    mAppStandby.shouldNoteResponseEventForAllBroadcastSessions();
            for (int i = broadcastEvents.size() - 1; i >= 0; --i) {
                final BroadcastEvent broadcastEvent = broadcastEvents.valueAt(i);
                recordAndPruneOldBroadcastDispatchTimestamps(broadcastEvent);

                final LongArrayQueue dispatchTimestampsMs = broadcastEvent.getTimestampsMs();
                long broadcastsSessionEndTimestampMs = 0;
                // We only need to look at the broadcast events that occurred before
                // this notification related event.
                while (dispatchTimestampsMs.size() > 0
                        && dispatchTimestampsMs.peekFirst() < timestampMs) {
                    final long dispatchTimestampMs = dispatchTimestampsMs.peekFirst();
                    final long elapsedDurationMs = timestampMs - dispatchTimestampMs;
                    // Only increment the counts if the broadcast was sent not too long ago, as
                    // decided by 'broadcastResponseWindowDurationMs' and is part of a new session.
                    // That is, it occurred 'broadcastsSessionWithResponseDurationMs' after the
                    // previously handled broadcast event which is represented by
                    // 'broadcastsSessionEndTimestampMs'.
                    if (elapsedDurationMs <= broadcastResponseWindowDurationMs
                            && dispatchTimestampMs >= broadcastsSessionEndTimestampMs) {
                        if (broadcastsSessionEndTimestampMs != 0
                                && !recordAllBroadcastsSessionsWithinResponseWindow) {
                            break;
                        }
                        final BroadcastResponseStats responseStats =
                                getOrCreateBroadcastResponseStats(broadcastEvent);
                        responseStats.incrementBroadcastsDispatchedCount(1);
                        broadcastsSessionEndTimestampMs = dispatchTimestampMs
                                + broadcastsSessionWithResponseDurationMs;
                        switch (event) {
                            case NOTIFICATION_EVENT_TYPE_POSTED:
                                responseStats.incrementNotificationsPostedCount(1);
                                break;
                            case NOTIFICATION_EVENT_TYPE_UPDATED:
                                responseStats.incrementNotificationsUpdatedCount(1);
                                break;
                            case NOTIFICATION_EVENT_TYPE_CANCELLED:
                                responseStats.incrementNotificationsCancelledCount(1);
                                break;
                            default:
                                Slog.wtf(TAG, "Unknown event: " + event);
                        }
                    }
                    dispatchTimestampsMs.removeFirst();
                }
                if (dispatchTimestampsMs.size() == 0) {
                    broadcastEvents.removeAt(i);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void recordAndPruneOldBroadcastDispatchTimestamps(BroadcastEvent broadcastEvent) {
        final LongArrayQueue timestampsMs = broadcastEvent.getTimestampsMs();
        final long broadcastResponseWindowDurationMs =
                mAppStandby.getBroadcastResponseWindowDurationMs();
        final long broadcastsSessionDurationMs =
                mAppStandby.getBroadcastSessionsDurationMs();
        final long nowElapsedMs = SystemClock.elapsedRealtime();
        long broadcastsSessionEndTimestampMs = 0;
        while (timestampsMs.size() > 0
                && timestampsMs.peekFirst() < (nowElapsedMs - broadcastResponseWindowDurationMs)) {
            final long eventTimestampMs = timestampsMs.peekFirst();
            if (eventTimestampMs >= broadcastsSessionEndTimestampMs) {
                final BroadcastResponseStats responseStats =
                        getOrCreateBroadcastResponseStats(broadcastEvent);
                responseStats.incrementBroadcastsDispatchedCount(1);
                broadcastsSessionEndTimestampMs = eventTimestampMs + broadcastsSessionDurationMs;
            }
            timestampsMs.removeFirst();
        }
    }

    @NonNull List<BroadcastResponseStats> queryBroadcastResponseStats(int callingUid,
            @Nullable String packageName, @IntRange(from = 0) long id, @UserIdInt int userId) {
        final List<BroadcastResponseStats> broadcastResponseStatsList = new ArrayList<>();
        synchronized (mLock) {
            final SparseArray<UserBroadcastResponseStats> responseStatsForCaller =
                    mUserResponseStats.get(callingUid);
            if (responseStatsForCaller == null) {
                return broadcastResponseStatsList;
            }
            final UserBroadcastResponseStats responseStatsForUser =
                    responseStatsForCaller.get(userId);
            if (responseStatsForUser == null) {
                return broadcastResponseStatsList;
            }
            responseStatsForUser.populateAllBroadcastResponseStats(
                    broadcastResponseStatsList, packageName, id);
        }
        return broadcastResponseStatsList;
    }

    void clearBroadcastResponseStats(int callingUid, @Nullable String packageName, long id,
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

    void clearBroadcastEvents(int callingUid, @UserIdInt int userId) {
        synchronized (mLock) {
            final UserBroadcastEvents userBroadcastEvents = mUserBroadcastEvents.get(userId);
            if (userBroadcastEvents == null) {
                return;
            }
            userBroadcastEvents.clear(callingUid);
        }
    }

    boolean isPackageExemptedFromBroadcastResponseStats(@NonNull String packageName,
            @NonNull UserHandle user) {
        synchronized (mLock) {
            if (doesPackageHoldExemptedPermission(packageName, user)) {
                return true;
            }
            if (doesPackageHoldExemptedRole(packageName, user)) {
                return true;
            }
            return false;
        }
    }

    boolean doesPackageHoldExemptedRole(@NonNull String packageName, @NonNull UserHandle user) {
        final List<String> exemptedRoles = mAppStandby.getBroadcastResponseExemptedRoles();
        synchronized (mLock) {
            for (int i = exemptedRoles.size() - 1; i >= 0; --i) {
                final String roleName = exemptedRoles.get(i);
                final List<String> roleHolders = getRoleHoldersLocked(roleName, user);
                if (CollectionUtils.contains(roleHolders, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean doesPackageHoldExemptedPermission(@NonNull String packageName,
            @NonNull UserHandle user) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUidAsUser(
                    packageName, user.getIdentifier());
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        final List<String> exemptedPermissions =
                mAppStandby.getBroadcastResponseExemptedPermissions();
        for (int i = exemptedPermissions.size() - 1; i >= 0; --i) {
            final String permissionName = exemptedPermissions.get(i);
            if (mContext.checkPermission(permissionName, Process.INVALID_PID, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    @Nullable
    private List<String> getRoleHoldersLocked(@NonNull String roleName, @NonNull UserHandle user) {
        ArrayMap<String, List<String>> roleHoldersForUser = mExemptedRoleHoldersCache.get(
                user.getIdentifier());
        if (roleHoldersForUser == null) {
            roleHoldersForUser = new ArrayMap<>();
            mExemptedRoleHoldersCache.put(user.getIdentifier(), roleHoldersForUser);
        }
        List<String> roleHolders = roleHoldersForUser.get(roleName);
        if (roleHolders == null && mRoleManager != null) {
            roleHolders = mRoleManager.getRoleHoldersAsUser(roleName, user);
            roleHoldersForUser.put(roleName, roleHolders);
        }
        return roleHolders;
    }

    private void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
        synchronized (mLock) {
            final ArrayMap<String, List<String>> roleHoldersForUser =
                    mExemptedRoleHoldersCache.get(user.getIdentifier());
            if (roleHoldersForUser == null) {
                return;
            }
            roleHoldersForUser.remove(roleName);
        }
    }

    void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            mUserBroadcastEvents.remove(userId);

            for (int i = mUserResponseStats.size() - 1; i >= 0; --i) {
                mUserResponseStats.valueAt(i).remove(userId);
            }
            mExemptedRoleHoldersCache.remove(userId);
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
    private ArraySet<BroadcastEvent> getBroadcastEventsLocked(
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
    private ArraySet<BroadcastEvent> getOrCreateBroadcastEventsLocked(
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

    private static BroadcastEvent getOrCreateBroadcastEvent(
            ArraySet<BroadcastEvent> broadcastEvents,
            int sourceUid, String targetPackage, int targetUserId, long idForResponseEvent) {
        final BroadcastEvent broadcastEvent = new BroadcastEvent(
                sourceUid, targetPackage, targetUserId, idForResponseEvent);
        final int index = broadcastEvents.indexOf(broadcastEvent);
        if (index >= 0) {
            return broadcastEvents.valueAt(index);
        } else {
            broadcastEvents.add(broadcastEvent);
            return broadcastEvent;
        }
    }

    void dump(@NonNull IndentingPrintWriter ipw) {
        ipw.println("Broadcast response stats:");
        ipw.increaseIndent();

        synchronized (mLock) {
            dumpBroadcastEventsLocked(ipw);
            ipw.println();
            dumpResponseStatsLocked(ipw);
            ipw.println();
            dumpRoleHoldersLocked(ipw);
            ipw.println();
            mLogger.dumpLogs(ipw);
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

    @GuardedBy("mLock")
    private void dumpRoleHoldersLocked(@NonNull IndentingPrintWriter ipw) {
        ipw.println("Role holders:");
        ipw.increaseIndent();
        for (int userIdx = 0; userIdx < mExemptedRoleHoldersCache.size(); ++userIdx) {
            final int userId = mExemptedRoleHoldersCache.keyAt(userIdx);
            final ArrayMap<String, List<String>> roleHoldersForUser =
                    mExemptedRoleHoldersCache.valueAt(userIdx);
            ipw.println("User " + userId + ":");
            ipw.increaseIndent();
            for (int roleIdx = 0; roleIdx < roleHoldersForUser.size(); ++roleIdx) {
                final String roleName = roleHoldersForUser.keyAt(roleIdx);
                final List<String> holders = roleHoldersForUser.valueAt(roleIdx);
                ipw.print(roleName + ": ");
                for (int holderIdx = 0; holderIdx < holders.size(); ++holderIdx) {
                    if (holderIdx > 0) {
                        ipw.print(", ");
                    }
                    ipw.print(holders.get(holderIdx));
                }
                ipw.println();
            }
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
    }
}

