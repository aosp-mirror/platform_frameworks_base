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
import android.app.NotificationManager;
import android.app.Person;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
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
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.telephony.SmsApplication;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static final long QUERY_EVENTS_MAX_AGE_MS = 5L * DateUtils.MINUTE_IN_MILLIS;
    private static final long USAGE_STATS_QUERY_INTERVAL_SEC = 120L;

    private final Context mContext;
    private final Injector mInjector;
    private final ScheduledExecutorService mScheduledExecutor;
    private final Object mLock = new Object();

    private final SparseArray<UserData> mUserDataArray = new SparseArray<>();
    private final SparseArray<BroadcastReceiver> mBroadcastReceivers = new SparseArray<>();
    private final SparseArray<ContentObserver> mContactsContentObservers = new SparseArray<>();
    private final SparseArray<ScheduledFuture<?>> mUsageStatsQueryFutures = new SparseArray<>();
    private final SparseArray<NotificationListenerService> mNotificationListeners =
            new SparseArray<>();
    private final SparseArray<PackageMonitor> mPackageMonitors = new SparseArray<>();
    private ContentObserver mCallLogContentObserver;
    private ContentObserver mMmsSmsContentObserver;

    private ShortcutServiceInternal mShortcutServiceInternal;
    private PackageManagerInternal mPackageManagerInternal;
    private NotificationManagerInternal mNotificationManagerInternal;
    private UserManager mUserManager;

    public DataManager(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    DataManager(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mScheduledExecutor = mInjector.createScheduledExecutor();
    }

    /** Initialization. Called when the system services are up running. */
    public void initialize() {
        mShortcutServiceInternal = LocalServices.getService(ShortcutServiceInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mNotificationManagerInternal = LocalServices.getService(NotificationManagerInternal.class);
        mUserManager = mContext.getSystemService(UserManager.class);

        mShortcutServiceInternal.addShortcutChangeCallback(new ShortcutServiceCallback());

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
            ContentResolver contentResolver = mContext.getContentResolver();
            if (mUserDataArray.indexOfKey(userId) >= 0) {
                mUserDataArray.get(userId).setUserStopped();
            }
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

    /** Reports the sharing related {@link AppTargetEvent} from App Prediction Manager. */
    public void reportShareTargetEvent(@NonNull AppTargetEvent event,
            @Nullable IntentFilter intentFilter) {
        AppTarget appTarget = event.getTarget();
        if (appTarget == null || event.getAction() != AppTargetEvent.ACTION_LAUNCH) {
            return;
        }
        UserData userData = getUnlockedUserData(appTarget.getUser().getIdentifier());
        if (userData == null) {
            return;
        }
        PackageData packageData = userData.getOrCreatePackageData(appTarget.getPackageName());
        String mimeType = intentFilter != null ? intentFilter.getDataType(0) : null;
        @Event.EventType int eventType = mimeTypeToShareEventType(mimeType);
        EventHistoryImpl eventHistory;
        if (ChooserActivity.LAUNCH_LOCATON_DIRECT_SHARE.equals(event.getLaunchLocation())) {
            // Direct share event
            if (appTarget.getShortcutInfo() == null) {
                return;
            }
            String shortcutId = appTarget.getShortcutInfo().getId();
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
            BroadcastReceiver broadcastReceiver = new PerUserBroadcastReceiver(userId);
            mBroadcastReceivers.put(userId, broadcastReceiver);
            mContext.registerReceiverAsUser(
                    broadcastReceiver, UserHandle.of(userId), intentFilter, null, null);

            ContentObserver contactsContentObserver = new ContactsContentObserver(
                    BackgroundThread.getHandler());
            mContactsContentObservers.put(userId, contactsContentObserver);
            mContext.getContentResolver().registerContentObserver(
                    Contacts.CONTENT_URI, /* notifyForDescendants= */ true,
                    contactsContentObserver, userId);

            NotificationListener notificationListener = new NotificationListener();
            mNotificationListeners.put(userId, notificationListener);
            try {
                notificationListener.registerAsSystemService(mContext,
                        new ComponentName(mContext, getClass()), userId);
            } catch (RemoteException e) {
                // Should never occur for local calls.
            }

            PackageMonitor packageMonitor = new PerUserPackageMonitor();
            packageMonitor.register(mContext, null, UserHandle.of(userId), true);
            mPackageMonitors.put(userId, packageMonitor);

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

    private int mimeTypeToShareEventType(String mimeType) {
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
            userData.deletePackageData(packageName);
        }
    }

    /** Gets a list of {@link ShortcutInfo}s with the given shortcut IDs. */
    private List<ShortcutInfo> getShortcuts(
            @NonNull String packageName, @UserIdInt int userId,
            @Nullable List<String> shortcutIds) {
        @ShortcutQuery.QueryFlags int queryFlags = ShortcutQuery.FLAG_MATCH_DYNAMIC
                | ShortcutQuery.FLAG_MATCH_PINNED | ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER;
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
    private EventHistoryImpl getEventHistoryIfEligible(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String shortcutId = notification.getShortcutId();
        if (shortcutId == null) {
            return null;
        }
        PackageData packageData = getPackage(sbn.getPackageName(),
                sbn.getUser().getIdentifier());
        if (packageData == null
                || packageData.getConversationStore().getConversation(shortcutId) == null) {
            return null;
        }
        return packageData.getEventStore().getOrCreateEventHistory(
                EventStore.CATEGORY_SHORTCUT_BASED, shortcutId);
    }

    private boolean isPersonShortcut(@NonNull ShortcutInfo shortcutInfo) {
        return shortcutInfo.getPersons() != null && shortcutInfo.getPersons().length != 0;
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
        ConversationInfo.Builder builder = oldConversationInfo != null
                ? new ConversationInfo.Builder(oldConversationInfo)
                : new ConversationInfo.Builder();

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
        conversationStore.addOrUpdate(builder.build());
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
    NotificationListenerService getNotificationListenerServiceForTesting(@UserIdInt int userId) {
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
                }
            });
            if (conversationSelector.mConversationInfo == null) {
                return;
            }

            ConversationInfo.Builder builder =
                    new ConversationInfo.Builder(conversationSelector.mConversationInfo);
            builder.setContactStarred(helper.isStarred());
            builder.setContactPhoneNumber(helper.getPhoneNumber());
            conversationSelector.mConversationStore.addOrUpdate(builder.build());
            mLastUpdatedTimestamp = helper.getLastUpdatedTimestamp();
        }

        private class ConversationSelector {
            private ConversationStore mConversationStore = null;
            private ConversationInfo mConversationInfo = null;
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
                for (ShortcutInfo shortcut : shortcuts) {
                    if (isPersonShortcut(shortcut)) {
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
                for (ShortcutInfo shortcutInfo : shortcuts) {
                    if (packageData != null) {
                        packageData.deleteDataForConversation(shortcutInfo.getId());
                    }
                    if (uid != Process.INVALID_UID) {
                        mNotificationManagerInternal.onConversationRemoved(
                                shortcutInfo.getPackage(), uid, shortcutInfo.getId());
                    }
                }
            });
        }
    }

    /** Listener for the notifications and their settings changes. */
    private class NotificationListener extends NotificationListenerService {

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            EventHistoryImpl eventHistory = getEventHistoryIfEligible(sbn);
            if (eventHistory != null) {
                eventHistory.addEvent(new Event(sbn.getPostTime(), Event.TYPE_NOTIFICATION_POSTED));
            }
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
                int reason) {
            if (reason != REASON_CLICK) {
                return;
            }
            EventHistoryImpl eventHistory = getEventHistoryIfEligible(sbn);
            if (eventHistory == null) {
                return;
            }
            long currentTime = System.currentTimeMillis();
            eventHistory.addEvent(new Event(currentTime, Event.TYPE_NOTIFICATION_OPENED));
        }

        @Override
        public void onNotificationChannelModified(String pkg, UserHandle user,
                NotificationChannel channel, int modificationType) {
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
            conversationStore.addOrUpdate(builder.build());

            if (modificationType == NOTIFICATION_CHANNEL_OR_GROUP_UPDATED
                    && conversationInfo.isShortcutLongLived()
                    && !conversationInfo.isShortcutCached()) {
                mShortcutServiceInternal.cacheShortcuts(user.getIdentifier(),
                        mContext.getPackageName(), pkg,
                        Collections.singletonList(conversationInfo.getShortcutId()),
                        user.getIdentifier());
            }
        }
    }

    /**
     * A {@link Runnable} that queries the Usage Stats Service for recent events for a specified
     * user.
     */
    private class UsageStatsQueryRunnable implements Runnable {

        private final UsageStatsQueryHelper mUsageStatsQueryHelper;
        private long mLastEventTimestamp;

        private UsageStatsQueryRunnable(int userId) {
            mUsageStatsQueryHelper = mInjector.createUsageStatsQueryHelper(userId,
                    (packageName) -> getPackage(packageName, userId));
            mLastEventTimestamp = System.currentTimeMillis() - QUERY_EVENTS_MAX_AGE_MS;
        }

        @Override
        public void run() {
            if (mUsageStatsQueryHelper.querySince(mLastEventTimestamp)) {
                mLastEventTimestamp = mUsageStatsQueryHelper.getLastEventTimestamp();
            }
        }
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
                Function<String, PackageData> packageDataGetter) {
            return new UsageStatsQueryHelper(userId, packageDataGetter);
        }
    }
}
