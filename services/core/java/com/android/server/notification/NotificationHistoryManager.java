/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IoThread;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps track of per-user notification histories.
 */
public class NotificationHistoryManager {
    private static final String TAG = "NotificationHistory";
    private static final boolean DEBUG = NotificationManagerService.DBG;

    @VisibleForTesting
    static final String DIRECTORY_PER_USER = "notification_history";

    private final Context mContext;
    private final UserManager mUserManager;
    @VisibleForTesting
    final SettingsObserver mSettingsObserver;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<NotificationHistoryDatabase> mUserState = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseBooleanArray mUserUnlockedStates = new SparseBooleanArray();
    // TODO: does this need to be persisted across reboots?
    @GuardedBy("mLock")
    private final SparseArray<List<String>> mUserPendingPackageRemovals = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseBooleanArray mHistoryEnabled = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseBooleanArray mUserPendingHistoryDisables = new SparseBooleanArray();

    public NotificationHistoryManager(Context context, Handler handler) {
        mContext = context;
        mUserManager = context.getSystemService(UserManager.class);
        mSettingsObserver = new SettingsObserver(handler);
    }

    @VisibleForTesting
    void onDestroy() {
        mSettingsObserver.stopObserving();
    }

    void onBootPhaseAppsCanStart() {
        mSettingsObserver.observe();
    }

    void onUserUnlocked(@UserIdInt int userId) {
        synchronized (mLock) {
            mUserUnlockedStates.put(userId, true);
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            if (userHistory == null) {
                Slog.i(TAG, "Attempted to unlock gone/disabled user " + userId);
                return;
            }

            // remove any packages that were deleted while the user was locked
            final List<String> pendingPackageRemovals = mUserPendingPackageRemovals.get(userId);
            if (pendingPackageRemovals != null) {
                for (int i = 0; i < pendingPackageRemovals.size(); i++) {
                    userHistory.onPackageRemoved(pendingPackageRemovals.get(i));
                }
                mUserPendingPackageRemovals.put(userId, null);
            }

            // delete history if it was disabled when the user was locked
            if (mUserPendingHistoryDisables.get(userId)) {
                disableHistory(userHistory, userId);
            }
        }
    }

    public void onUserStopped(@UserIdInt int userId) {
        synchronized (mLock) {
            mUserUnlockedStates.put(userId, false);
            mUserState.put(userId, null); // release the service (mainly for GC)
        }
    }

    public void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            // Actual data deletion is handled by other parts of the system (the entire directory is
            // removed) - we just need clean up our internal state for GC
            mUserPendingPackageRemovals.put(userId, null);
            mHistoryEnabled.put(userId, false);
            mUserPendingHistoryDisables.put(userId, false);
            onUserStopped(userId);
        }
    }

    public void onPackageRemoved(@UserIdInt int userId, String packageName) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.get(userId, false)) {
                if (mHistoryEnabled.get(userId, false)) {
                    List<String> userPendingRemovals =
                            mUserPendingPackageRemovals.get(userId, new ArrayList<>());
                    userPendingRemovals.add(packageName);
                    mUserPendingPackageRemovals.put(userId, userPendingRemovals);
                }
                return;
            }
            final NotificationHistoryDatabase userHistory = mUserState.get(userId);
            if (userHistory == null) {
                return;
            }

            userHistory.onPackageRemoved(packageName);
        }
    }

    public void deleteNotificationHistoryItem(String pkg, int uid, long postedTime) {
        synchronized (mLock) {
            int userId = UserHandle.getUserId(uid);
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            // TODO: it shouldn't be possible to delete a notification entry while the user is
            // locked but we should handle it
            if (userHistory == null) {
                Slog.w(TAG, "Attempted to remove notif for locked/gone/disabled user "
                        + userId);
                return;
            }
            userHistory.deleteNotificationHistoryItem(pkg, postedTime);
        }
    }

    public void deleteConversations(String pkg, int uid, Set<String> conversationIds) {
        synchronized (mLock) {
            int userId = UserHandle.getUserId(uid);
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            // TODO: it shouldn't be possible to delete a notification entry while the user is
            // locked but we should handle it
            if (userHistory == null) {
                Slog.w(TAG, "Attempted to remove conversation for locked/gone/disabled user "
                        + userId);
                return;
            }
            userHistory.deleteConversations(pkg, conversationIds);
        }
    }

    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        synchronized (mLock) {
            int userId = UserHandle.getUserId(uid);
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            // TODO: it shouldn't be possible to delete a notification entry while the user is
            // locked but we should handle it
            if (userHistory == null) {
                Slog.w(TAG, "Attempted to remove channel for locked/gone/disabled user "
                        + userId);
                return;
            }
            userHistory.deleteNotificationChannel(pkg, channelId);
        }
    }

    public void triggerWriteToDisk() {
        synchronized (mLock) {
            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                final int userId = mUserState.keyAt(i);
                if (!mUserUnlockedStates.get(userId)) {
                    continue;
                }
                NotificationHistoryDatabase userHistory = mUserState.get(userId);
                if (userHistory != null) {
                    userHistory.forceWriteToDisk();
                }
            }
        }
    }

    public void addNotification(@NonNull final HistoricalNotification notification) {
        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                final NotificationHistoryDatabase userHistory =
                        getUserHistoryAndInitializeIfNeededLocked(notification.getUserId());
                if (userHistory == null) {
                    Slog.w(TAG, "Attempted to add notif for locked/gone/disabled user "
                            + notification.getUserId());
                    return;
                }
                userHistory.addNotification(notification);
            }
        });
    }

    public @NonNull NotificationHistory readNotificationHistory(@UserIdInt int[] userIds) {
        synchronized (mLock) {
            NotificationHistory mergedHistory = new NotificationHistory();
            if (userIds == null) {
                return mergedHistory;
            }
            for (int userId : userIds) {
                final NotificationHistoryDatabase userHistory =
                        getUserHistoryAndInitializeIfNeededLocked(userId);
                if (userHistory == null) {
                    Slog.i(TAG, "Attempted to read history for locked/gone/disabled user " +userId);
                    continue;
                }
                mergedHistory.addNotificationsToWrite(userHistory.readNotificationHistory());
            }
            return mergedHistory;
        }
    }

    public @NonNull android.app.NotificationHistory readFilteredNotificationHistory(
            @UserIdInt int userId, String packageName, String channelId, int maxNotifications) {
        synchronized (mLock) {
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            if (userHistory == null) {
                Slog.i(TAG, "Attempted to read history for locked/gone/disabled user " +userId);
                return new android.app.NotificationHistory();
            }

            return userHistory.readNotificationHistory(packageName, channelId, maxNotifications);
        }
    }

    boolean isHistoryEnabled(@UserIdInt int userId) {
        synchronized (mLock) {
            return mHistoryEnabled.get(userId);
        }
    }

    void onHistoryEnabledChanged(@UserIdInt int userId, boolean historyEnabled) {
        synchronized (mLock) {
            if (historyEnabled) {
                mHistoryEnabled.put(userId, historyEnabled);
            }
            final NotificationHistoryDatabase userHistory =
                    getUserHistoryAndInitializeIfNeededLocked(userId);
            if (userHistory != null) {
                if (!historyEnabled) {
                    disableHistory(userHistory, userId);
                }
            } else {
                mUserPendingHistoryDisables.put(userId, !historyEnabled);
            }
        }
    }

    private void disableHistory(NotificationHistoryDatabase userHistory, @UserIdInt int userId) {
        userHistory.disableHistory();

        mUserPendingHistoryDisables.put(userId, false);
        mHistoryEnabled.put(userId, false);
        mUserState.put(userId, null);
    }

    @GuardedBy("mLock")
    private @Nullable NotificationHistoryDatabase getUserHistoryAndInitializeIfNeededLocked(
            int userId) {
        if (!mHistoryEnabled.get(userId)) {
            if (DEBUG) {
                Slog.i(TAG, "History disabled for user " + userId);
            }
            mUserState.put(userId, null);
            return null;
        }
        NotificationHistoryDatabase userHistory = mUserState.get(userId);
        if (userHistory == null) {
            final File historyDir = new File(Environment.getDataSystemCeDirectory(userId),
                    DIRECTORY_PER_USER);
            userHistory = NotificationHistoryDatabaseFactory.create(mContext, IoThread.getHandler(),
                    historyDir);
            if (mUserUnlockedStates.get(userId)) {
                try {
                    userHistory.init();
                } catch (Exception e) {
                    if (mUserManager.isUserUnlocked(userId)) {
                        throw e; // rethrow exception - user is unlocked
                    } else {
                        Slog.w(TAG, "Attempted to initialize service for "
                                + "stopped or removed user " + userId);
                        return null;
                    }
                }
            } else {
                // locked! data unavailable
                Slog.w(TAG, "Attempted to initialize service for "
                        + "stopped or removed user " + userId);
                return null;
            }
            mUserState.put(userId, userHistory);
        }
        return userHistory;
    }

    @VisibleForTesting
    boolean isUserUnlocked(@UserIdInt int userId) {
        synchronized (mLock) {
            return mUserUnlockedStates.get(userId);
        }
    }

    @VisibleForTesting
    boolean doesHistoryExistForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return mUserState.get(userId) != null;
        }
    }

    @VisibleForTesting
    void replaceNotificationHistoryDatabase(@UserIdInt int userId,
            NotificationHistoryDatabase replacement) {
        synchronized (mLock) {
            if (mUserState.get(userId) != null) {
                mUserState.put(userId, replacement);
            }
        }
    }

    @VisibleForTesting
    List<String> getPendingPackageRemovalsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return mUserPendingPackageRemovals.get(userId);
        }
    }

    final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_HISTORY_URI
                = Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_HISTORY_ENABLED);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_HISTORY_URI,
                    false, this, UserHandle.USER_ALL);
            synchronized (mLock) {
                for (UserInfo userInfo : mUserManager.getUsers()) {
                    if (!userInfo.isProfile()) {
                        update(null, userInfo.id);
                    }
                }
            }
        }

        void stopObserving() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            update(uri, userId);
        }

        public void update(Uri uri, int userId) {
            ContentResolver resolver = mContext.getContentResolver();
            if (uri == null || NOTIFICATION_HISTORY_URI.equals(uri)) {
                boolean historyEnabled = Settings.Secure.getIntForUser(resolver,
                        Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, userId)
                        != 0;
                int[] profiles = mUserManager.getProfileIds(userId, true);
                for (int profileId : profiles) {
                    onHistoryEnabledChanged(profileId, historyEnabled);
                }
            }
        }
    }
}
