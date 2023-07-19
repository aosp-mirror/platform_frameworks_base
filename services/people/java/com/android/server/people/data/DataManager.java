/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.usage.UsageEvents;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.ContactsContract.Contacts;
import android.provider.Telephony.MmsSms;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.telephony.SmsApplication;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.notification.ShortcutHelper;
import com.android.server.people.PeopleService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A class manages the lifecycle of the conversations and associated data, and exposes the methods
 * to access the data in People Service and other system services.
 */
public class DataManager {

    private static final String TAG = "DataManager";
    private static final boolean DEBUG = false;

    private static final long RECENT_NOTIFICATIONS_MAX_AGE_MS = 10 * DateUtils.DAY_IN_MILLIS;
    private static final long QUERY_EVENTS_MAX_AGE_MS = 5L * DateUtils.MINUTE_IN_MILLIS;
    private static final long USAGE_STATS_QUERY_INTERVAL_SEC = 120L;
    @VisibleForTesting
    static final int MAX_CACHED_RECENT_SHORTCUTS = 30;

    private final Context mContext;
    private final Injector mInjector;
    private final ScheduledExecutorService mScheduledExecutor;
    private final Object mLock = new Object();

    private final SparseArray<UserData> mUserDataArray = new SparseArray<>();
    private final SparseArray<BroadcastReceiver> mBroadcastReceivers = new SparseArray<>();
    private final SparseArray<ContentObserver> mContactsContentObservers = new SparseArray<>();
    private final SparseArray<ScheduledFuture<?>> mUsageStatsQueryFutures = new SparseArray<>();
    private final SparseArray<NotificationListener> mNotificationListeners = new SparseArray<>();
    private final SparseArray<PackageMonitor> mPackageMonitors = new SparseArray<>();
    @GuardedBy("mLock")
    private final List<PeopleService.ConversationsListener> mConversationsListeners =
            new ArrayList<>(1);
    private final Handler mHandler;

    private ContentObserver mCallLogContentObserver;
    private ContentObserver mMmsSmsContentObserver;

    private ShortcutServiceInternal mShortcutServiceInternal;
    private PackageManagerInternal mPackageManagerInternal;
    private NotificationManagerInternal mNotificationManagerInternal;
    private UserManager mUserManager;
    private ConversationStatusExpirationBroadcastReceiver mStatusExpReceiver;

    public DataManager(Context context) {
        this(context, new Injector(), BackgroundThread.get().getLooper());
    }

    DataManager(Context context, Injector injector, Looper looper) {
        mContext = context;
        mInjector = injector;
        mScheduledExecutor = mInjector.createScheduledExecutor();
        mHandler = new Handler(looper);
    }

    /** Initialization. Called when the system services are up running. */
    public void initialize() {
        mShortcutServiceInternal = LocalServices.getService(ShortcutServiceInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mNotificationManagerInternal = LocalServices.getService(NotificationManagerInternal.class);
        mUserManager = mContext.getSystemService(UserManager.class);

        mShortcutServiceInternal.addShortcutChangeCallback(new ShortcutServiceCallback());

        mStatusExpReceiver = new ConversationStatusExpirationBroadcastReceiver();
        mContext.registerReceiver(mStatusExpReceiver,
                ConversationStatusExpirationBroadcastReceiver.getFilter(),
                Context.RECEIVER_NOT_EXPORTED);

        IntentFilter shutdownIntentFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        BroadcastReceiver shutdownBroadcastReceiver = new ShutdownBroadcastReceiver();
        mContext.registerReceiver(shutdownBroadcastReceiver, shutdownIntentFilter);
    }

    /** This method is called when a user is unlocked. */
    public void onUserUnlocked(int userId) {
        synchronized (mLock) {
            UserData userData = mUserDataArray.get(userId);
            if (userData == null) {
                userData = new UserData(userId, mScheduledExecutor);
                mUserDataArray.put(userId, userData);
            }
            userData.setUserUnlocked();
        }
        mScheduledExecutor.execute(() -> setupUser(userId));
    }

    /** This method is called when a user is stopping. */
    public void onUserStopping(int userId) {
        synchronized (mLock) {
            UserData userData = mUserDataArray.get(userId);
            if (userData != null) {
                userData.setUserStopped();
            }
        }
        mScheduledExecutor.execute(() -> cleanupUser(userId));
    }

    /**
     * Iterates through all the {@link PackageData}s owned by the unlocked users who are in the
     * same profile group as the calling user.
     */
    void forPackagesInProfile(@UserIdInt int callingUserId, Consumer<PackageData> consumer) {
        List<UserInfo> users = mUserManager.getEnabledProfiles(callingUserId);
        for (UserInfo userInfo : users) {
            UserData userData = getUnlockedUserData(userInfo.id);
            if (userData != null) {
                userData.forAllPackages(consumer);
            }
        }
    }

    /** Gets the {@link PackageData} for the given package and user. */
    @Nullable
    public PackageData getPackage(@NonNull String packageName, @UserIdInt int userId) {
        UserData userData = getUnlockedUserData(userId);
        return userData != null ? userData.getPackageData(packageName) : null;
    }

    /** Gets the {@link ShortcutInfo} for the given shortcut ID. */
    @Nullable
    public ShortcutInfo getShortcut(@NonNull String packageName, @UserIdInt int userId,
            @NonNull String shortcutId) {
        List<ShortcutInfo> shortcuts = getShortcuts(packageName, userId,
                Collections.singletonList(shortcutId));
        if (shortcuts != null && !shortcuts.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Found shortcut for " + shortcuts.get(0).getLabel());
            return shortcuts.get(0);
        }
        return null;
    }

    /**
     * Gets the {@link ShareShortcutInfo}s from all packages owned by the calling user that match
     * the specified {@link IntentFilter}.
     */
    public List<ShareShortcutInfo> getShareShortcuts(@NonNull IntentFilter intentFilter,
            @UserIdInt int callingUserId) {
        return mShortcutServiceInternal.getShareTargets(
                mContext.getPackageName(), intentFilter, callingUserId);
    }

    /**
     * Returns a {@link ConversationChannel} with the associated {@code shortcutId} if existent.
     * Otherwise, returns null.
     */
    @Nullable
    public ConversationChannel getConversation(String packageName, int userId, String shortcutId) {
        UserData userData = getUnlockedUserData(userId);
        if (userData != null) {
            PackageData packageData = userData.getPackageData(packageName);
            // App may have been uninstalled.
            if (packageData != null) {
                ConversationInfo conversationInfo = packageData.getConversationInfo(shortcutId);
                return getConversationChannel(packageName, userId, shortcutId, conversationInfo);
            }
        }
        return null;
    }

    ConversationInfo getConversationInfo(String packageName, int userId, String shortcutId) {
        UserData userData = getUnlockedUserData(userId);
        if (userData != null) {
            PackageData packageData = userData.getPackageData(packageName);
            // App may have been uninstalled.
            if (packageData != null) {
                return packageData.getConversationInfo(shortcutId);
            }
        }
        return null;
    }

    @Nullable
    private ConversationChannel getConversationChannel(String packageName, int userId,
            String shortcutId, ConversationInfo conversationInfo) {
        ShortcutInfo shortcutInfo = getShortcut(packageName, userId, shortcutId);
        return getConversationChannel(shortcutInfo, conversationInfo);
    }

    @Nullable
    private ConversationChannel getConversationChannel(ShortcutInfo shortcutInfo,
            ConversationInfo conversationInfo) {
        if (conversationInfo == null || conversationInfo.isDemoted()) {
            return null;
        }
        if (shortcutInfo == null) {
            Slog.e(TAG, " Shortcut no longer found");
            return null;
        }
        String packageName = shortcutInfo.getPackage();
        String shortcutId = shortcutInfo.getId();
        int userId = shortcutInfo.getUserId();
        int uid = mPackageManagerInternal.getPackageUid(packageName, 0, userId);
        NotificationChannel parentChannel =
                mNotificationManagerInternal.getNotificationChannel(packageName, uid,
                        conversationInfo.getNotificationChannelId());
        NotificationChannelGroup parentChannelGroup = null;
        if (parentChannel != null) {
            parentChannelGroup =
                    mNotificationManagerInternal.getNotificationChannelGroup(packageName,
                            uid, parentChannel.getId());
        }
        return new ConversationChannel(shortcutInfo, uid, parentChannel,
                parentChannelGroup,
                conversationInfo.getLastEventTimestamp(),
                hasActiveNotifications(packageName, userId, shortcutId), false,
                getStatuses(conversationInfo));
    }

    /** Returns the cached non-customized recent conversations. */
    public List<ConversationChannel> getRecentConversations(@UserIdInt int callingUserId) {
        List<ConversationChannel> conversationChannels = new ArrayList<>();
        forPackagesInProfile(callingUserId, packageData -> {
            packageData.forAllConversations(conversationInfo -> {
                if (!isCachedRecentConversation(conversationInfo)) {
                    return;
                }
                String shortcutId = conversationInfo.getShortcutId();
                ConversationChannel channel = getConversationChannel(packageData.getPackageName(),
                        packageData.getUserId(), shortcutId, conversationInfo);
                if (channel == null || channel.getNotificationChannel() == null) {
                    return;
                }
                conversationChannels.add(channel);
            });
        });
        return conversationChannels;
    }

    /**
     * Uncaches the shortcut that's associated with the specified conversation so this conversation
     * will not show up in the recent conversations list.
     */
    public void removeRecentConversation(String packageName, int userId, String shortcutId,
            @UserIdInt int callingUserId) {
        if (!hasActiveNotifications(packageName, userId, shortcutId)) {
            mShortcutServiceInternal.uncacheShortcuts(callingUserId, mContext.getPackageName(),
                    packageName, Collections.singletonList(shortcutId), userId,
                    ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        }
    }

    /**
     * Uncaches the shortcuts for all the recent conversations that they don't have active
     * notifications.
     */
    public void removeAllRecentConversations(@UserIdInt int callingUserId) {
        pruneOldRecentConversations(callingUserId, Long.MAX_VALUE);
    }

    /**
     * Uncaches the shortcuts for all the recent conversations that haven't been interacted with
     * recently.
     */
    public void pruneOldRecentConversations(@UserIdInt int callingUserId, long currentTimeMs) {
        forPackagesInProfile(callingUserId, packageData -> {
            String packageName = packageData.getPackageName();
            int userId = packageData.getUserId();
            List<String> idsToUncache = new ArrayList<>();
            packageData.forAllConversations(conversationInfo -> {
                String shortcutId = conversationInfo.getShortcutId();
                if (isCachedRecentConversation(conversationInfo)
                        && (currentTimeMs - conversationInfo.getLastEventTimestamp()
                        > RECENT_NOTIFICATIONS_MAX_AGE_MS)
                        && !hasActiveNotifications(packageName, userId, shortcutId)) {
                    idsToUncache.add(shortcutId);
                }
            });
            if (!idsToUncache.isEmpty()) {
                mShortcutServiceInternal.uncacheShortcuts(callingUserId, mContext.getPackageName(),
                        packageName, idsToUncache, userId, ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
            }
        });
    }

    /**
     * Removes any status with an expiration time in the past.
     */
    public void pruneExpiredConversationStatuses(@UserIdInt int callingUserId, long currentTimeMs) {
        forPackagesInProfile(callingUserId, packageData -> {
            final ConversationStore cs = packageData.getConversationStore();
            packageData.forAllConversations(conversationInfo -> {
                ConversationInfo.Builder builder = new ConversationInfo.Builder(conversationInfo);
                List<ConversationStatus> newStatuses = new ArrayList<>();
                for (ConversationStatus status : conversationInfo.getStatuses()) {
                    if (status.getEndTimeMillis() < 0
                            || currentTimeMs < status.getEndTimeMillis()) {
                        newStatuses.add(status);
                    }
                }
                builder.setStatuses(newStatuses);
                updateConversationStoreThenNotifyListeners(cs, builder.build(),
                        packageData.getPackageName(),
                        packageData.getUserId());
            });
        });
    }

    /** Returns whether {@code shortcutId} is backed by Conversation. */
    public boolean isConversation(String packageName, int userId, String shortcutId) {
        ConversationChannel channel = getConversation(packageName, userId, shortcutId);
        return channel != null
                && channel.getShortcutInfo() != null
                && !TextUtils.isEmpty(channel.getShortcutInfo().getLabel());
    }

    /**
     * Returns the last notification interaction with the specified conversation. If the
     * conversation can't be found or no interactions have been recorded, returns 0L.
     */
    public long getLastInteraction(String packageName, int userId, String shortcutId) {
        final PackageData packageData = getPackage(packageName, userId);
        if (packageData != null) {
            final ConversationInfo conversationInfo = packageData.getConversationInfo(shortcutId);
            if (conversationInfo != null) {
                return conversationInfo.getLastEventTimestamp();
            }
        }
        return 0L;
    }

    public void addOrUpdateStatus(String packageName, int userId, String conversationId,
            ConversationStatus status) {
        ConversationStore cs = getConversationStoreOrThrow(packageName, userId);
        ConversationInfo convToModify = getConversationInfoOrThrow(cs, conversationId);
        ConversationInfo.Builder builder = new ConversationInfo.Builder(convToModify);
        builder.addOrUpdateStatus(status);
        updateConversationStoreThenNotifyListeners(cs, builder.build(), packageName, userId);

        if (status.getEndTimeMillis() >= 0) {
            mStatusExpReceiver.scheduleExpiration(
                    mContext, userId, packageName, conversationId, status);
        }

    }

    public void clearStatus(String packageName, int userId, String conversationId,
            String statusId) {
        ConversationStore cs = getConversationStoreOrThrow(packageName, userId);
        ConversationInfo convToModify = getConversationInfoOrThrow(cs, conversationId);
        ConversationInfo.Builder builder = new ConversationInfo.Builder(convToModify);
        builder.clearStatus(statusId);
        updateConversationStoreThenNotifyListeners(cs, builder.build(), packageName, userId);
    }

    public void clearStatuses(String packageName, int userId, String conversationId) {
        ConversationStore cs = getConversationStoreOrThrow(packageName, userId);
        ConversationInfo convToModify = getConversationInfoOrThrow(cs, conversationId);
        ConversationInfo.Builder builder = new ConversationInfo.Builder(convToModify);
        builder.setStatuses(null);
        updateConversationStoreThenNotifyListeners(cs, builder.build(), packageName, userId);
    }

    public @NonNull List<ConversationStatus> getStatuses(String packageName, int userId,
            String conversationId) {
        ConversationStore cs = getConversationStoreOrThrow(packageName, userId);
        ConversationInfo conversationInfo = getConversationInfoOrThrow(cs, conversationId);
        return getStatuses(conversationInfo);
    }

    private @NonNull List<ConversationStatus> getStatuses(ConversationInfo conversationInfo) {
        Collection<ConversationStatus> statuses = conversationInfo.getStatuses();
        if (statuses != null) {
            final ArrayList<ConversationStatus> list = new ArrayList<>(statuses.size());
            list.addAll(statuses);
            return list;
        }
        return new ArrayList<>();
    }

    /**
     * Returns a conversation store for a package, if it exists.
     */
    private @NonNull ConversationStore getConversationStoreOrThrow(String packageName, int userId) {
        final PackageData packageData = getPackage(packageName, userId);
        if (packageData == null) {
            throw new IllegalArgumentException("No settings exist for package " + packageName);
        }
        ConversationStore cs = packageData.getConversationStore();
        if (cs == null) {
            throw new IllegalArgumentException("No conversations exist for package " + packageName);
        }
        return cs;
    }

    /**
     * Returns a conversation store for a package, if it exists.
     */
    private @NonNull ConversationInfo getConversationInfoOrThrow(ConversationStore cs,
            String conversationId) {
        ConversationInfo ci = cs.getConversation(conversationId);

        if (ci == null) {
            throw new IllegalArgumentException("Conversation does not exist");
        }
        return ci;
    }

    /** Reports the sharing related {@link AppTargetEvent} from App Prediction Manager. */
    public void reportShareTargetEvent(@NonNull AppTargetEvent event,
            @NonNull IntentFilter intentFilter) {
        AppTarget appTarget = event.getTarget();
        if (appTarget == null || event.getAction() != AppTargetEvent.ACTION_LAUNCH) {
            return;
        }
        UserData userData = getUnlockedUserData(appTarget.getUser().getIdentifier());
        if (userData == null) {
            return;
        }
        PackageData packageData = userData.getOrCreatePackageData(appTarget.getPackageName());
        @Event.EventType int eventType = mimeTypeToShareEventType(intentFilter.getDataType(0));
        EventHistoryImpl eventHistory;
        if (ChooserActivity.LAUNCH_LOCATION_DIRECT_SHARE.equals(event.getLaunchLocation())) {
            // Direct share event
            if (appTarget.getShortcutInfo() == null) {
                return;
            }
            String shortcutId = appTarget.getShortcutInfo().getId();
            // Skip storing chooserTargets sharing events
            if (ChooserActivity.CHOOSER_TARGET.equals(shortcutId)) {
                return;
            }
            if (packageData.getConversationStore().getConversation(shortcutId) == null) {
                addOrUpdateConversationInfo(appTarget.getShortcutInfo());
            }
            eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                    EventStore.CATEGORY_SHORTCUT_BASED, shortcutId);
        } else {
            // App share event
            eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                    EventStore.CATEGORY_CLASS_BASED, appTarget.getClassName());
        }
        eventHistory.addEvent(new Event(System.currentTimeMillis(), eventType));
    }

    /**
     * Queries events for moving app to foreground between {@code startTime} and {@code endTime}.
     */
    @NonNull
    public List<UsageEvents.Event> queryAppMovingToForegroundEvents(@UserIdInt int callingUserId,
            long startTime, long endTime) {
        return UsageStatsQueryHelper.queryAppMovingToForegroundEvents(callingUserId, startTime,
                endTime);
    }

    /**
     * Queries usage stats of apps within {@code packageNameFilter} between {@code startTime} and
     * {@code endTime}.
     *
     * @return a map which keys are package names and values are {@link AppUsageStatsData}.
     */
    @NonNull
    public Map<String, AppUsageStatsData> queryAppUsageStats(
            @UserIdInt int callingUserId, long startTime,
            long endTime, Set<String> packageNameFilter) {
        return UsageStatsQueryHelper.queryAppUsageStats(callingUserId, startTime, endTime,
                packageNameFilter);
    }

    /** Prunes the data for the specified user. */
    public void pruneDataForUser(@UserIdInt int userId, @NonNull CancellationSignal signal) {
        UserData userData = getUnlockedUserData(userId);
        if (userData == null || signal.isCanceled()) {
            return;
        }
        pruneUninstalledPackageData(userData);

        userData.forAllPackages(packageData -> {
            if (signal.isCanceled()) {
                return;
            }
            packageData.getEventStore().pruneOldEvents();
            if (!packageData.isDefaultDialer()) {
                packageData.getEventStore().deleteEventHistories(EventStore.CATEGORY_CALL);
            }
            if (!packageData.isDefaultSmsApp()) {
                packageData.getEventStore().deleteEventHistories(EventStore.CATEGORY_SMS);
            }
            packageData.pruneOrphanEvents();
            pruneExpiredConversationStatuses(userId, System.currentTimeMillis());
            pruneOldRecentConversations(userId, System.currentTimeMillis());
            cleanupCachedShortcuts(userId, MAX_CACHED_RECENT_SHORTCUTS);
        });
    }

    /** Retrieves a backup payload blob for specified user id. */
    @Nullable
    public byte[] getBackupPayload(@UserIdInt int userId) {
        UserData userData = getUnlockedUserData(userId);
        if (userData == null) {
            return null;
        }
        return userData.getBackupPayload();
    }

    /** Attempts to restore data for the specified user id. */
    public void restore(@UserIdInt int userId, @NonNull byte[] payload) {
        UserData userData = getUnlockedUserData(userId);
        if (userData == null) {
            return;
        }
        userData.restore(payload);
    }

    private void setupUser(@UserIdInt int userId) {
        synchronized (mLock) {
            UserData userData = getUnlockedUserData(userId);
            if (userData == null) {
                return;
            }
            userData.loadUserData();

            updateDefaultDialer(userData);
            updateDefaultSmsApp(userData);

            ScheduledFuture<?> scheduledFuture = mScheduledExecutor.scheduleAtFixedRate(
                    new UsageStatsQueryRunnable(userId), 1L, USAGE_STATS_QUERY_INTERVAL_SEC,
                    TimeUnit.SECONDS);
            mUsageStatsQueryFutures.put(userId, scheduledFuture);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED);
            intentFilter.addAction(SmsApplication.ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL);

            if (mBroadcastReceivers.get(userId) == null) {
                BroadcastReceiver broadcastReceiver = new PerUserBroadcastReceiver(userId);
                mBroadcastReceivers.put(userId, broadcastReceiver);
                mContext.registerReceiverAsUser(
                        broadcastReceiver, UserHandle.of(userId), intentFilter, null, null);
            } else {
                // Stopped was not called on this user before setup is called again. This
                // could happen during consecutive rapid user switching.
                if (DEBUG) Log.d(TAG, "PerUserBroadcastReceiver was registered for: " + userId);
            }

            ContentObserver contactsContentObserver = new ContactsContentObserver(
                    BackgroundThread.getHandler());
            mContactsContentObservers.put(userId, contactsContentObserver);
            mContext.getContentResolver().registerContentObserver(
                    Contacts.CONTENT_URI, /* notifyForDescendants= */ true,
                    contactsContentObserver, userId);

            NotificationListener notificationListener = new NotificationListener(userId);
            mNotificationListeners.put(userId, notificationListener);
            try {
                notificationListener.registerAsSystemService(mContext,
                        new ComponentName(mContext, getClass()), userId);
            } catch (RemoteException e) {
                // Should never occur for local calls.
            }

            if (mPackageMonitors.get(userId) == null) {
                PackageMonitor packageMonitor = new PerUserPackageMonitor();
                packageMonitor.register(mContext, null, UserHandle.of(userId), true);
                mPackageMonitors.put(userId, packageMonitor);
            } else {
                // Stopped was not called on this user before setup is called again. This
                // could happen during consecutive rapid user switching.
                if (DEBUG) Log.d(TAG, "PerUserPackageMonitor was registered for: " + userId);
            }

            if (userId == UserHandle.USER_SYSTEM) {
                // The call log and MMS/SMS messages are shared across user profiles. So only need
                // to register the content observers once for the primary user.
                mCallLogContentObserver = new CallLogContentObserver(BackgroundThread.getHandler());
                mContext.getContentResolver().registerContentObserver(
                        CallLog.CONTENT_URI, /* notifyForDescendants= */ true,
                        mCallLogContentObserver, UserHandle.USER_SYSTEM);

                mMmsSmsContentObserver = new MmsSmsContentObserver(BackgroundThread.getHandler());
                mContext.getContentResolver().registerContentObserver(
                        MmsSms.CONTENT_URI, /* notifyForDescendants= */ false,
                        mMmsSmsContentObserver, UserHandle.USER_SYSTEM);
            }

            DataMaintenanceService.scheduleJob(mContext, userId);
        }
    }

    private void cleanupUser(@UserIdInt int userId) {
        synchronized (mLock) {
            UserData userData = mUserDataArray.get(userId);
            if (userData == null || userData.isUnlocked()) {
                return;
            }
            ContentResolver contentResolver = mContext.getContentResolver();
            if (mUsageStatsQueryFutures.indexOfKey(userId) >= 0) {
                mUsageStatsQueryFutures.get(userId).cancel(true);
            }
            if (mBroadcastReceivers.indexOfKey(userId) >= 0) {
                mContext.unregisterReceiver(mBroadcastReceivers.get(userId));
            }
            if (mContactsContentObservers.indexOfKey(userId) >= 0) {
                contentResolver.unregisterContentObserver(mContactsContentObservers.get(userId));
            }
            if (mNotificationListeners.indexOfKey(userId) >= 0) {
                try {
                    mNotificationListeners.get(userId).unregisterAsSystemService();
                } catch (RemoteException e) {
                    // Should never occur for local calls.
                }
            }
            if (mPackageMonitors.indexOfKey(userId) >= 0) {
                mPackageMonitors.get(userId).unregister();
            }
            if (userId == UserHandle.USER_SYSTEM) {
                if (mCallLogContentObserver != null) {
                    contentResolver.unregisterContentObserver(mCallLogContentObserver);
                    mCallLogContentObserver = null;
                }
                if (mMmsSmsContentObserver != null) {
                    contentResolver.unregisterContentObserver(mMmsSmsContentObserver);
                    mCallLogContentObserver = null;
                }
            }

            DataMaintenanceService.cancelJob(mContext, userId);
        }
    }

    /**
     * Converts {@code mimeType} to {@link Event.EventType}.
     */
    public int mimeTypeToShareEventType(String mimeType) {
        if (mimeType == null) {
            return Event.TYPE_SHARE_OTHER;
        }
        if (mimeType.startsWith("text/")) {
            return Event.TYPE_SHARE_TEXT;
        } else if (mimeType.startsWith("image/")) {
            return Event.TYPE_SHARE_IMAGE;
        } else if (mimeType.startsWith("video/")) {
            return Event.TYPE_SHARE_VIDEO;
        }
        return Event.TYPE_SHARE_OTHER;
    }

    private void pruneUninstalledPackageData(@NonNull UserData userData) {
        Set<String> installApps = new ArraySet<>();
        mPackageManagerInternal.forEachInstalledPackage(
                pkg -> installApps.add(pkg.getPackageName()), userData.getUserId());
        List<String> packagesToDelete = new ArrayList<>();
        userData.forAllPackages(packageData -> {
            if (!installApps.contains(packageData.getPackageName())) {
                packagesToDelete.add(packageData.getPackageName());
            }
        });
        for (String packageName : packagesToDelete) {
            if (DEBUG) Log.d(TAG, "Deleting packages data for: " + packageName);
            userData.deletePackageData(packageName);
        }
    }

    /** Gets a list of {@link ShortcutInfo}s with the given shortcut IDs. */
    private List<ShortcutInfo> getShortcuts(
            @NonNull String packageName, @UserIdInt int userId,
            @Nullable List<String> shortcutIds) {
        @ShortcutQuery.QueryFlags int queryFlags = ShortcutQuery.FLAG_MATCH_DYNAMIC
                | ShortcutQuery.FLAG_MATCH_PINNED | ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
                | ShortcutQuery.FLAG_MATCH_CACHED | ShortcutQuery.FLAG_GET_PERSONS_DATA;
        if (DEBUG) Log.d(TAG, " Get shortcuts with IDs: " + shortcutIds);
        return mShortcutServiceInternal.getShortcuts(
                UserHandle.USER_SYSTEM, mContext.getPackageName(),
                /*changedSince=*/ 0, packageName, shortcutIds, /*locusIds=*/ null,
                /*componentName=*/ null, queryFlags, userId, Process.myPid(), Process.myUid());
    }

    private void forAllUnlockedUsers(Consumer<UserData> consumer) {
        for (int i = 0; i < mUserDataArray.size(); i++) {
            int userId = mUserDataArray.keyAt(i);
            UserData userData = mUserDataArray.get(userId);
            if (userData.isUnlocked()) {
                consumer.accept(userData);
            }
        }
    }

    @Nullable
    private UserData getUnlockedUserData(int userId) {
        UserData userData = mUserDataArray.get(userId);
        return userData != null && userData.isUnlocked() ? userData : null;
    }

    private void updateDefaultDialer(@NonNull UserData userData) {
        TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        String defaultDialer = telecomManager != null
                ? telecomManager.getDefaultDialerPackage(
                new UserHandle(userData.getUserId())) : null;
        userData.setDefaultDialer(defaultDialer);
    }

    private void updateDefaultSmsApp(@NonNull UserData userData) {
        ComponentName component = SmsApplication.getDefaultSmsApplicationAsUser(
                mContext, /* updateIfNeeded= */ false, userData.getUserId());
        String defaultSmsApp = component != null ? component.getPackageName() : null;
        userData.setDefaultSmsApp(defaultSmsApp);
    }

    @Nullable
    private PackageData getPackageIfConversationExists(StatusBarNotification sbn,
            Consumer<ConversationInfo> conversationConsumer) {
        Notification notification = sbn.getNotification();
        String shortcutId = notification.getShortcutId();
        if (shortcutId == null) {
            return null;
        }
        PackageData packageData = getPackage(sbn.getPackageName(),
                sbn.getUser().getIdentifier());
        if (packageData == null) {
            return null;
        }
        ConversationInfo conversationInfo =
                packageData.getConversationStore().getConversation(shortcutId);
        if (conversationInfo == null) {
            return null;
        }
        conversationConsumer.accept(conversationInfo);
        return packageData;
    }

    private boolean isCachedRecentConversation(ConversationInfo conversationInfo) {
        return isEligibleForCleanUp(conversationInfo)
                && conversationInfo.getLastEventTimestamp() > 0L;
    }

    /**
     * Conversations that are cached and not customized are eligible for clean-up, even if they
     * don't have an associated notification event with them.
     */
    private boolean isEligibleForCleanUp(ConversationInfo conversationInfo) {
        return conversationInfo.isShortcutCachedForNotification()
                && Objects.equals(conversationInfo.getNotificationChannelId(),
                conversationInfo.getParentNotificationChannelId());
    }

    private boolean hasActiveNotifications(String packageName, @UserIdInt int userId,
            String shortcutId) {
        NotificationListener notificationListener = mNotificationListeners.get(userId);
        return notificationListener != null
                && notificationListener.hasActiveNotifications(packageName, shortcutId);
    }

    /**
     * Cleans up the oldest cached shortcuts that don't have active notifications for the recent
     * conversations. After the cleanup, normally, the total number of cached shortcuts will be
     * less than or equal to the target count. However, there are exception cases: e.g. when all
     * the existing cached shortcuts have active notifications.
     */
    private void cleanupCachedShortcuts(@UserIdInt int userId, int targetCachedCount) {
        UserData userData = getUnlockedUserData(userId);
        if (userData == null) {
            return;
        }
        // pair of <package name, conversation info>
        List<Pair<String, ConversationInfo>> cachedConvos = new ArrayList<>();
        userData.forAllPackages(packageData -> {
                packageData.forAllConversations(conversationInfo -> {
                    if (isEligibleForCleanUp(conversationInfo)) {
                        cachedConvos.add(
                                Pair.create(packageData.getPackageName(), conversationInfo));
                    }
                });
        });
        if (cachedConvos.size() <= targetCachedCount) {
            return;
        }
        int numToUncache = cachedConvos.size() - targetCachedCount;
        // Max heap keeps the oldest cached conversations.
        PriorityQueue<Pair<String, ConversationInfo>> maxHeap = new PriorityQueue<>(
                numToUncache + 1,
                Comparator.comparingLong((Pair<String, ConversationInfo> pair) ->
                        Math.max(
                            pair.second.getLastEventTimestamp(),
                            pair.second.getCreationTimestamp())).reversed());
        for (Pair<String, ConversationInfo> cached : cachedConvos) {
            if (hasActiveNotifications(cached.first, userId, cached.second.getShortcutId())) {
                continue;
            }
            maxHeap.offer(cached);
            if (maxHeap.size() > numToUncache) {
                maxHeap.poll();
            }
        }
        while (!maxHeap.isEmpty()) {
            Pair<String, ConversationInfo> toUncache = maxHeap.poll();
            mShortcutServiceInternal.uncacheShortcuts(userId,
                    mContext.getPackageName(), toUncache.first,
                    Collections.singletonList(toUncache.second.getShortcutId()),
                    userId, ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        }
    }

    @VisibleForTesting
    @WorkerThread
    void addOrUpdateConversationInfo(@NonNull ShortcutInfo shortcutInfo) {
        UserData userData = getUnlockedUserData(shortcutInfo.getUserId());
        if (userData == null) {
            return;
        }
        PackageData packageData = userData.getOrCreatePackageData(shortcutInfo.getPackage());
        ConversationStore conversationStore = packageData.getConversationStore();
        ConversationInfo oldConversationInfo =
                conversationStore.getConversation(shortcutInfo.getId());
        if (oldConversationInfo == null) {
            if (DEBUG) Log.d(TAG, "Nothing previously stored about conversation.");
        }
        ConversationInfo.Builder builder = oldConversationInfo != null
                ? new ConversationInfo.Builder(oldConversationInfo)
                : new ConversationInfo.Builder().setCreationTimestamp(System.currentTimeMillis());

        builder.setShortcutId(shortcutInfo.getId());
        builder.setLocusId(shortcutInfo.getLocusId());
        builder.setShortcutFlags(shortcutInfo.getFlags());
        builder.setContactUri(null);
        builder.setContactPhoneNumber(null);
        builder.setContactStarred(false);

        if (shortcutInfo.getPersons() != null && shortcutInfo.getPersons().length != 0) {
            Person person = shortcutInfo.getPersons()[0];
            builder.setPersonImportant(person.isImportant());
            builder.setPersonBot(person.isBot());
            String contactUri = person.getUri();
            if (contactUri != null) {
                ContactsQueryHelper helper = mInjector.createContactsQueryHelper(mContext);
                if (helper.query(contactUri)) {
                    builder.setContactUri(helper.getContactUri());
                    builder.setContactStarred(helper.isStarred());
                    builder.setContactPhoneNumber(helper.getPhoneNumber());
                }
            }
        }
        updateConversationStoreThenNotifyListeners(conversationStore, builder.build(),
                shortcutInfo);
    }

    @VisibleForTesting
    ContentObserver getContactsContentObserverForTesting(@UserIdInt int userId) {
        return mContactsContentObservers.get(userId);
    }

    @VisibleForTesting
    ContentObserver getCallLogContentObserverForTesting() {
        return mCallLogContentObserver;
    }

    @VisibleForTesting
    ContentObserver getMmsSmsContentObserverForTesting() {
        return mMmsSmsContentObserver;
    }

    @VisibleForTesting
    NotificationListener getNotificationListenerServiceForTesting(@UserIdInt int userId) {
        return mNotificationListeners.get(userId);
    }

    @VisibleForTesting
    PackageMonitor getPackageMonitorForTesting(@UserIdInt int userId) {
        return mPackageMonitors.get(userId);
    }

    @VisibleForTesting
    UserData getUserDataForTesting(@UserIdInt int userId) {
        return mUserDataArray.get(userId);
    }

    /** Observer that observes the changes in the Contacts database. */
    private class ContactsContentObserver extends ContentObserver {

        private long mLastUpdatedTimestamp;

        private ContactsContentObserver(Handler handler) {
            super(handler);
            mLastUpdatedTimestamp = System.currentTimeMillis();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            ContactsQueryHelper helper = mInjector.createContactsQueryHelper(mContext);
            if (!helper.querySince(mLastUpdatedTimestamp)) {
                return;
            }
            Uri contactUri = helper.getContactUri();

            final ConversationSelector conversationSelector = new ConversationSelector();
            UserData userData = getUnlockedUserData(userId);
            if (userData == null) {
                return;
            }
            userData.forAllPackages(packageData -> {
                ConversationInfo ci =
                        packageData.getConversationStore().getConversationByContactUri(contactUri);
                if (ci != null) {
                    conversationSelector.mConversationStore =
                            packageData.getConversationStore();
                    conversationSelector.mConversationInfo = ci;
                    conversationSelector.mPackageName = packageData.getPackageName();
                }
            });
            if (conversationSelector.mConversationInfo == null) {
                return;
            }

            ConversationInfo.Builder builder =
                    new ConversationInfo.Builder(conversationSelector.mConversationInfo);
            builder.setContactStarred(helper.isStarred());
            builder.setContactPhoneNumber(helper.getPhoneNumber());
            updateConversationStoreThenNotifyListeners(conversationSelector.mConversationStore,
                    builder.build(),
                    conversationSelector.mPackageName, userId);
            mLastUpdatedTimestamp = helper.getLastUpdatedTimestamp();
        }

        private class ConversationSelector {
            private ConversationStore mConversationStore = null;
            private ConversationInfo mConversationInfo = null;
            private String mPackageName = null;
        }
    }

    /** Observer that observes the changes in the call log database. */
    private class CallLogContentObserver extends ContentObserver implements
            BiConsumer<String, Event> {

        private final CallLogQueryHelper mCallLogQueryHelper;
        private long mLastCallTimestamp;

        private CallLogContentObserver(Handler handler) {
            super(handler);
            mCallLogQueryHelper = mInjector.createCallLogQueryHelper(mContext, this);
            mLastCallTimestamp = System.currentTimeMillis() - QUERY_EVENTS_MAX_AGE_MS;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mCallLogQueryHelper.querySince(mLastCallTimestamp)) {
                mLastCallTimestamp = mCallLogQueryHelper.getLastCallTimestamp();
            }
        }

        @Override
        public void accept(String phoneNumber, Event event) {
            forAllUnlockedUsers(userData -> {
                PackageData defaultDialer = userData.getDefaultDialer();
                if (defaultDialer == null) {
                    return;
                }
                ConversationStore conversationStore = defaultDialer.getConversationStore();
                if (conversationStore.getConversationByPhoneNumber(phoneNumber) == null) {
                    return;
                }
                EventStore eventStore = defaultDialer.getEventStore();
                eventStore.getOrCreateEventHistory(
                        EventStore.CATEGORY_CALL, phoneNumber).addEvent(event);
            });
        }
    }

    /** Observer that observes the changes in the MMS & SMS database. */
    private class MmsSmsContentObserver extends ContentObserver implements
            BiConsumer<String, Event> {

        private final MmsQueryHelper mMmsQueryHelper;
        private long mLastMmsTimestamp;

        private final SmsQueryHelper mSmsQueryHelper;
        private long mLastSmsTimestamp;

        private MmsSmsContentObserver(Handler handler) {
            super(handler);
            mMmsQueryHelper = mInjector.createMmsQueryHelper(mContext, this);
            mSmsQueryHelper = mInjector.createSmsQueryHelper(mContext, this);
            mLastSmsTimestamp = mLastMmsTimestamp =
                    System.currentTimeMillis() - QUERY_EVENTS_MAX_AGE_MS;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mMmsQueryHelper.querySince(mLastMmsTimestamp)) {
                mLastMmsTimestamp = mMmsQueryHelper.getLastMessageTimestamp();
            }
            if (mSmsQueryHelper.querySince(mLastSmsTimestamp)) {
                mLastSmsTimestamp = mSmsQueryHelper.getLastMessageTimestamp();
            }
        }

        @Override
        public void accept(String phoneNumber, Event event) {
            forAllUnlockedUsers(userData -> {
                PackageData defaultSmsApp = userData.getDefaultSmsApp();
                if (defaultSmsApp == null) {
                    return;
                }
                ConversationStore conversationStore = defaultSmsApp.getConversationStore();
                if (conversationStore.getConversationByPhoneNumber(phoneNumber) == null) {
                    return;
                }
                EventStore eventStore = defaultSmsApp.getEventStore();
                eventStore.getOrCreateEventHistory(
                        EventStore.CATEGORY_SMS, phoneNumber).addEvent(event);
            });
        }
    }

    /** Listener for the shortcut data changes. */
    private class ShortcutServiceCallback implements LauncherApps.ShortcutChangeCallback {

        @Override
        public void onShortcutsAddedOrUpdated(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
            mInjector.getBackgroundExecutor().execute(() -> {
                PackageData packageData = getPackage(packageName, user.getIdentifier());
                for (ShortcutInfo shortcut : shortcuts) {
                    if (ShortcutHelper.isConversationShortcut(
                            shortcut, mShortcutServiceInternal, user.getIdentifier())) {
                        if (shortcut.isCached()) {
                            ConversationInfo conversationInfo = packageData != null
                                    ? packageData.getConversationInfo(shortcut.getId()) : null;
                            if (conversationInfo == null
                                    || !conversationInfo.isShortcutCachedForNotification()) {
                                // This is a newly cached shortcut. Clean up the existing cached
                                // shortcuts to ensure the cache size is under the limit.
                                cleanupCachedShortcuts(user.getIdentifier(),
                                        MAX_CACHED_RECENT_SHORTCUTS - 1);
                            }
                        }
                        addOrUpdateConversationInfo(shortcut);
                    }
                }
            });
        }

        @Override
        public void onShortcutsRemoved(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
            mInjector.getBackgroundExecutor().execute(() -> {
                int uid = Process.INVALID_UID;
                try {
                    uid = mContext.getPackageManager().getPackageUidAsUser(
                            packageName, user.getIdentifier());
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Package not found: " + packageName, e);
                }
                PackageData packageData = getPackage(packageName, user.getIdentifier());
                Set<String> shortcutIds = new HashSet<>();
                for (ShortcutInfo shortcutInfo : shortcuts) {
                    if (packageData != null) {
                        if (DEBUG) Log.d(TAG, "Deleting shortcut: " + shortcutInfo.getId());
                        packageData.deleteDataForConversation(shortcutInfo.getId());
                    }
                    shortcutIds.add(shortcutInfo.getId());
                }
                if (uid != Process.INVALID_UID) {
                    mNotificationManagerInternal.onConversationRemoved(
                            packageName, uid, shortcutIds);
                }
            });
        }
    }

    /** Listener for the notifications and their settings changes. */
    private class NotificationListener extends NotificationListenerService {

        private final int mUserId;

        // Conversation package name + shortcut ID -> Number of active notifications
        @GuardedBy("this")
        private final Map<Pair<String, String>, Integer> mActiveNotifCounts = new ArrayMap<>();

        private NotificationListener(int userId) {
            mUserId = userId;
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap map) {
            if (sbn.getUser().getIdentifier() != mUserId) {
                return;
            }
            String shortcutId = sbn.getNotification().getShortcutId();
            PackageData packageData = getPackageIfConversationExists(sbn, conversationInfo -> {
                synchronized (this) {
                    mActiveNotifCounts.merge(
                            Pair.create(sbn.getPackageName(), shortcutId), 1, Integer::sum);
                }
            });

            if (packageData != null) {
                Ranking rank = new Ranking();
                map.getRanking(sbn.getKey(), rank);
                ConversationInfo conversationInfo = packageData.getConversationInfo(shortcutId);
                if (conversationInfo == null) {
                    return;
                }
                if (DEBUG) Log.d(TAG, "Last event from notification: " + sbn.getPostTime());
                ConversationInfo.Builder updated = new ConversationInfo.Builder(conversationInfo)
                        .setLastEventTimestamp(sbn.getPostTime())
                        .setNotificationChannelId(rank.getChannel().getId());
                if (!TextUtils.isEmpty(rank.getChannel().getParentChannelId())) {
                    updated.setParentNotificationChannelId(rank.getChannel().getParentChannelId());
                } else {
                    updated.setParentNotificationChannelId(sbn.getNotification().getChannelId());
                }
                packageData.getConversationStore().addOrUpdate(updated.build());

                EventHistoryImpl eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                        EventStore.CATEGORY_SHORTCUT_BASED, shortcutId);
                eventHistory.addEvent(new Event(sbn.getPostTime(), Event.TYPE_NOTIFICATION_POSTED));
            }
        }

        @Override
        public synchronized void onNotificationRemoved(StatusBarNotification sbn,
                RankingMap rankingMap, int reason) {
            if (sbn.getUser().getIdentifier() != mUserId) {
                return;
            }
            String shortcutId = sbn.getNotification().getShortcutId();
            PackageData packageData = getPackageIfConversationExists(sbn, conversationInfo -> {
                Pair<String, String> conversationKey =
                        Pair.create(sbn.getPackageName(), shortcutId);
                synchronized (this) {
                    int count = mActiveNotifCounts.getOrDefault(conversationKey, 0) - 1;
                    if (count <= 0) {
                        mActiveNotifCounts.remove(conversationKey);
                        cleanupCachedShortcuts(mUserId, MAX_CACHED_RECENT_SHORTCUTS);
                    } else {
                        mActiveNotifCounts.put(conversationKey, count);
                    }
                }
            });

            if (reason != REASON_CLICK || packageData == null) {
                return;
            }
            long currentTime = System.currentTimeMillis();
            ConversationInfo conversationInfo = packageData.getConversationInfo(shortcutId);
            if (conversationInfo == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "Last event from notification removed: " + currentTime);
            ConversationInfo updated = new ConversationInfo.Builder(conversationInfo)
                    .setLastEventTimestamp(currentTime)
                    .build();
            packageData.getConversationStore().addOrUpdate(updated);

            EventHistoryImpl eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                    EventStore.CATEGORY_SHORTCUT_BASED, shortcutId);
            eventHistory.addEvent(new Event(currentTime, Event.TYPE_NOTIFICATION_OPENED));
        }

        @Override
        public void onNotificationChannelModified(String pkg, UserHandle user,
                NotificationChannel channel, int modificationType) {
            if (user.getIdentifier() != mUserId) {
                return;
            }
            PackageData packageData = getPackage(pkg, user.getIdentifier());
            String shortcutId = channel.getConversationId();
            if (packageData == null || shortcutId == null) {
                return;
            }
            ConversationStore conversationStore = packageData.getConversationStore();
            ConversationInfo conversationInfo = conversationStore.getConversation(shortcutId);
            if (conversationInfo == null) {
                return;
            }
            ConversationInfo.Builder builder = new ConversationInfo.Builder(conversationInfo);
            switch (modificationType) {
                case NOTIFICATION_CHANNEL_OR_GROUP_ADDED:
                case NOTIFICATION_CHANNEL_OR_GROUP_UPDATED:
                    builder.setNotificationChannelId(channel.getId());
                    builder.setImportant(channel.isImportantConversation());
                    builder.setDemoted(channel.isDemoted());
                    builder.setNotificationSilenced(
                            channel.getImportance() <= NotificationManager.IMPORTANCE_LOW);
                    builder.setBubbled(channel.canBubble());
                    break;
                case NOTIFICATION_CHANNEL_OR_GROUP_DELETED:
                    // If the notification channel is deleted, revert all the notification settings
                    // to the default value.
                    builder.setNotificationChannelId(null);
                    builder.setImportant(false);
                    builder.setDemoted(false);
                    builder.setNotificationSilenced(false);
                    builder.setBubbled(false);
                    break;
            }
            updateConversationStoreThenNotifyListeners(conversationStore, builder.build(), pkg,
                    packageData.getUserId());
        }

        synchronized boolean hasActiveNotifications(String packageName, String shortcutId) {
            return mActiveNotifCounts.containsKey(Pair.create(packageName, shortcutId));
        }
    }

    /**
     * A {@link Runnable} that queries the Usage Stats Service for recent events for a specified
     * user.
     */
    private class UsageStatsQueryRunnable implements Runnable,
            UsageStatsQueryHelper.EventListener {

        private final UsageStatsQueryHelper mUsageStatsQueryHelper;
        private long mLastEventTimestamp;

        private UsageStatsQueryRunnable(int userId) {
            mUsageStatsQueryHelper = mInjector.createUsageStatsQueryHelper(userId,
                    (packageName) -> getPackage(packageName, userId), this);
            mLastEventTimestamp = System.currentTimeMillis() - QUERY_EVENTS_MAX_AGE_MS;
        }

        @Override
        public void run() {
            if (mUsageStatsQueryHelper.querySince(mLastEventTimestamp)) {
                mLastEventTimestamp = mUsageStatsQueryHelper.getLastEventTimestamp();
            }
        }

        @Override
        public void onEvent(PackageData packageData, ConversationInfo conversationInfo,
                Event event) {
            if (event.getType() == Event.TYPE_IN_APP_CONVERSATION) {
                if (DEBUG) Log.d(TAG, "Last event from in-app: " + event.getTimestamp());
                ConversationInfo updated = new ConversationInfo.Builder(conversationInfo)
                        .setLastEventTimestamp(event.getTimestamp())
                        .build();
                updateConversationStoreThenNotifyListeners(packageData.getConversationStore(),
                        updated,
                        packageData.getPackageName(), packageData.getUserId());
            }
        }
    }

    /** Adds {@code listener} to be notified on conversation changes. */
    public void addConversationsListener(
            @NonNull PeopleService.ConversationsListener listener) {
        synchronized (mConversationsListeners) {
            mConversationsListeners.add(Objects.requireNonNull(listener));
        }
    }

    @VisibleForTesting
    void updateConversationStoreThenNotifyListeners(ConversationStore cs,
            ConversationInfo modifiedConv,
            String packageName, int userId) {
        cs.addOrUpdate(modifiedConv);
        ConversationChannel channel = getConversationChannel(packageName, userId,
                modifiedConv.getShortcutId(), modifiedConv);
        if (channel != null) {
            notifyConversationsListeners(Arrays.asList(channel));
        }
    }

    private void updateConversationStoreThenNotifyListeners(ConversationStore cs,
            ConversationInfo modifiedConv, ShortcutInfo shortcutInfo) {
        cs.addOrUpdate(modifiedConv);
        ConversationChannel channel = getConversationChannel(shortcutInfo, modifiedConv);
        if (channel != null) {
            notifyConversationsListeners(Arrays.asList(channel));
        }
    }


    @VisibleForTesting
    void notifyConversationsListeners(
            @Nullable final List<ConversationChannel> changedConversations) {
        mHandler.post(() -> {
            try {
                final List<PeopleService.ConversationsListener> copy;
                synchronized (mLock) {
                    copy = new ArrayList<>(mConversationsListeners);
                }
                for (PeopleService.ConversationsListener listener : copy) {
                    listener.onConversationsUpdate(changedConversations);
                }
            } catch (Exception e) {
            }
        });
    }

    /** A {@link BroadcastReceiver} that receives the intents for a specified user. */
    private class PerUserBroadcastReceiver extends BroadcastReceiver {

        private final int mUserId;

        private PerUserBroadcastReceiver(int userId) {
            mUserId = userId;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            UserData userData = getUnlockedUserData(mUserId);
            if (userData == null) {
                return;
            }
            if (TelecomManager.ACTION_DEFAULT_DIALER_CHANGED.equals(intent.getAction())) {
                String defaultDialer = intent.getStringExtra(
                        TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME);
                userData.setDefaultDialer(defaultDialer);
            } else if (SmsApplication.ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL.equals(
                    intent.getAction())) {
                updateDefaultSmsApp(userData);
            }
        }
    }

    private class PerUserPackageMonitor extends PackageMonitor {

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            super.onPackageRemoved(packageName, uid);

            int userId = getChangingUserId();
            UserData userData = getUnlockedUserData(userId);
            if (userData != null) {
                if (DEBUG) Log.d(TAG, "Delete package data for: " + packageName);
                userData.deletePackageData(packageName);
            }
        }
    }

    private class ShutdownBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            forAllUnlockedUsers(userData -> userData.forAllPackages(PackageData::saveToDisk));
        }
    }

    @VisibleForTesting
    static class Injector {

        ScheduledExecutorService createScheduledExecutor() {
            return Executors.newSingleThreadScheduledExecutor();
        }

        Executor getBackgroundExecutor() {
            return BackgroundThread.getExecutor();
        }

        ContactsQueryHelper createContactsQueryHelper(Context context) {
            return new ContactsQueryHelper(context);
        }

        CallLogQueryHelper createCallLogQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            return new CallLogQueryHelper(context, eventConsumer);
        }

        MmsQueryHelper createMmsQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            return new MmsQueryHelper(context, eventConsumer);
        }

        SmsQueryHelper createSmsQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            return new SmsQueryHelper(context, eventConsumer);
        }

        UsageStatsQueryHelper createUsageStatsQueryHelper(@UserIdInt int userId,
                Function<String, PackageData> packageDataGetter,
                UsageStatsQueryHelper.EventListener eventListener) {
            return new UsageStatsQueryHelper(userId, packageDataGetter, eventListener);
        }
    }
}
