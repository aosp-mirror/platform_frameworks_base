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

import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.job.JobScheduler;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps.ShortcutChangeCallback;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Range;

import com.android.internal.app.ChooserActivity;
import com.android.internal.content.PackageMonitor;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public final class DataManagerTest {

    private static final int USER_ID_PRIMARY = 0;
    private static final int USER_ID_PRIMARY_MANAGED = 10;
    private static final int USER_ID_SECONDARY = 11;
    private static final String TEST_PKG_NAME = "pkg";
    private static final String TEST_CLASS_NAME = "class";
    private static final String TEST_SHORTCUT_ID = "sc";
    private static final int TEST_PKG_UID = 35;
    private static final String CONTACT_URI = "content://com.android.contacts/contacts/lookup/123";
    private static final String PHONE_NUMBER = "+1234567890";
    private static final String NOTIFICATION_CHANNEL_ID = "test : sc";
    private static final long MILLIS_PER_MINUTE = 1000L * 60L;

    @Mock private Context mContext;
    @Mock private ShortcutServiceInternal mShortcutServiceInternal;
    @Mock private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock private PackageManagerInternal mPackageManagerInternal;
    @Mock private NotificationManagerInternal mNotificationManagerInternal;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private TelecomManager mTelecomManager;
    @Mock private ContentResolver mContentResolver;
    @Mock private JobScheduler mJobScheduler;
    @Mock private StatusBarNotification mStatusBarNotification;
    @Mock private Notification mNotification;

    @Captor private ArgumentCaptor<ShortcutChangeCallback> mShortcutChangeCallbackCaptor;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;

    private ScheduledExecutorService mExecutorService;
    private NotificationChannel mNotificationChannel;
    private DataManager mDataManager;
    private CancellationSignal mCancellationSignal;
    private ShortcutChangeCallback mShortcutChangeCallback;
    private BroadcastReceiver mShutdownBroadcastReceiver;
    private TestInjector mInjector;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        addLocalServiceMock(ShortcutServiceInternal.class, mShortcutServiceInternal);

        addLocalServiceMock(UsageStatsManagerInternal.class, mUsageStatsManagerInternal);

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternal);
        AndroidPackage androidPackage = mock(AndroidPackage.class);
        when(androidPackage.getPackageName()).thenReturn(TEST_PKG_NAME);
        doAnswer(ans -> {
            Consumer<AndroidPackage> callback = (Consumer<AndroidPackage>) ans.getArguments()[0];
            callback.accept(androidPackage);
            return null;
        }).when(mPackageManagerInternal).forEachInstalledPackage(any(Consumer.class), anyInt());

        addLocalServiceMock(NotificationManagerInternal.class, mNotificationManagerInternal);

        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getPackageName()).thenReturn("android");
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        Context originalContext = getInstrumentation().getTargetContext();
        when(mContext.getApplicationInfo()).thenReturn(originalContext.getApplicationInfo());

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemServiceName(UserManager.class)).thenReturn(
                Context.USER_SERVICE);

        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);

        when(mContext.getSystemService(Context.TELECOM_SERVICE)).thenReturn(mTelecomManager);
        when(mContext.getSystemServiceName(TelecomManager.class)).thenReturn(
                Context.TELECOM_SERVICE);
        when(mTelecomManager.getDefaultDialerPackage(any(UserHandle.class)))
                .thenReturn(TEST_PKG_NAME);

        when(mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE)).thenReturn(mJobScheduler);
        when(mContext.getSystemServiceName(JobScheduler.class)).thenReturn(
                Context.JOB_SCHEDULER_SERVICE);

        mExecutorService = new MockScheduledExecutorService();

        when(mUserManager.getEnabledProfiles(USER_ID_PRIMARY))
                .thenReturn(Arrays.asList(
                        buildUserInfo(USER_ID_PRIMARY),
                        buildUserInfo(USER_ID_PRIMARY_MANAGED)));
        when(mUserManager.getEnabledProfiles(USER_ID_SECONDARY))
                .thenReturn(Collections.singletonList(buildUserInfo(USER_ID_SECONDARY)));

        when(mPackageManager.getPackageUidAsUser(TEST_PKG_NAME, USER_ID_PRIMARY))
                .thenReturn(TEST_PKG_UID);

        when(mStatusBarNotification.getNotification()).thenReturn(mNotification);
        when(mStatusBarNotification.getPackageName()).thenReturn(TEST_PKG_NAME);
        when(mStatusBarNotification.getUser()).thenReturn(UserHandle.of(USER_ID_PRIMARY));
        when(mStatusBarNotification.getPostTime()).thenReturn(System.currentTimeMillis());
        when(mNotification.getShortcutId()).thenReturn(TEST_SHORTCUT_ID);

        mNotificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "test channel", NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationChannel.setConversationId("test", TEST_SHORTCUT_ID);

        mCancellationSignal = new CancellationSignal();

        mInjector = new TestInjector();
        mDataManager = new DataManager(mContext, mInjector);
        mDataManager.initialize();

        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);
        verify(mShortcutServiceInternal).addShortcutChangeCallback(
                mShortcutChangeCallbackCaptor.capture());
        mShortcutChangeCallback = mShortcutChangeCallbackCaptor.getValue();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(), any());
        mShutdownBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Test
    public void testAccessConversationFromTheSameProfileGroup() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_PRIMARY_MANAGED);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        mDataManager.addOrUpdateConversationInfo(
                buildShortcutInfo("pkg_1", USER_ID_PRIMARY, "sc_1",
                        buildPerson(true, false)));
        mDataManager.addOrUpdateConversationInfo(
                buildShortcutInfo("pkg_2", USER_ID_PRIMARY_MANAGED, "sc_2",
                        buildPerson(false, true)));
        mDataManager.addOrUpdateConversationInfo(
                buildShortcutInfo("pkg_3", USER_ID_SECONDARY, "sc_3", buildPerson()));

        List<ConversationInfo> conversations = getConversationsInPrimary();

        // USER_ID_SECONDARY is not in the same profile group as USER_ID_PRIMARY.
        assertEquals(2, conversations.size());

        assertEquals("sc_1", conversations.get(0).getShortcutId());
        assertTrue(conversations.get(0).isPersonImportant());
        assertFalse(conversations.get(0).isPersonBot());
        assertFalse(conversations.get(0).isContactStarred());
        assertEquals(PHONE_NUMBER, conversations.get(0).getContactPhoneNumber());

        assertEquals("sc_2", conversations.get(1).getShortcutId());
        assertFalse(conversations.get(1).isPersonImportant());
        assertTrue(conversations.get(1).isPersonBot());
        assertFalse(conversations.get(0).isContactStarred());
        assertEquals(PHONE_NUMBER, conversations.get(0).getContactPhoneNumber());
    }

    @Test
    public void testAccessConversationForUnlockedUsersOnly() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.addOrUpdateConversationInfo(
                buildShortcutInfo("pkg_1", USER_ID_PRIMARY, "sc_1", buildPerson()));
        mDataManager.addOrUpdateConversationInfo(
                buildShortcutInfo("pkg_2", USER_ID_PRIMARY_MANAGED, "sc_2", buildPerson()));

        List<ConversationInfo> conversations = getConversationsInPrimary();

        // USER_ID_PRIMARY_MANAGED is not locked, so only USER_ID_PRIMARY's conversation is stored.
        assertEquals(1, conversations.size());
        assertEquals("sc_1", conversations.get(0).getShortcutId());

        mDataManager.onUserStopping(USER_ID_PRIMARY);
        conversations = getConversationsInPrimary();
        assertTrue(conversations.isEmpty());
    }

    @Test
    public void testGetShortcut() {
        mDataManager.getShortcut(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID);
        verify(mShortcutServiceInternal).getShortcuts(anyInt(), anyString(), anyLong(),
                eq(TEST_PKG_NAME), eq(Collections.singletonList(TEST_SHORTCUT_ID)),
                eq(null), eq(null), anyInt(), eq(USER_ID_PRIMARY), anyInt(), anyInt());
    }

    @Test
    public void testReportAppTargetEvent_directSharing()
            throws IntentFilter.MalformedMimeTypeException {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        AppTarget appTarget = new AppTarget.Builder(new AppTargetId(TEST_SHORTCUT_ID), shortcut)
                .build();
        AppTargetEvent appTargetEvent =
                new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(ChooserActivity.LAUNCH_LOCATION_DIRECT_SHARE)
                        .build();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SEND, "image/jpg");
        mDataManager.reportShareTargetEvent(appTargetEvent, intentFilter);

        List<Range<Long>> activeShareTimeSlots = getActiveSlotsForTestShortcut(
                Event.SHARE_EVENT_TYPES);
        assertEquals(1, activeShareTimeSlots.size());
    }

    @Test
    public void testReportAppTargetEvent_directSharing_createConversation()
            throws IntentFilter.MalformedMimeTypeException {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                null);
        AppTarget appTarget = new AppTarget.Builder(new AppTargetId(TEST_SHORTCUT_ID), shortcut)
                .build();
        AppTargetEvent appTargetEvent =
                new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(ChooserActivity.LAUNCH_LOCATION_DIRECT_SHARE)
                        .build();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SEND, "image/jpg");

        mDataManager.reportShareTargetEvent(appTargetEvent, intentFilter);

        List<Range<Long>> activeShareTimeSlots = getActiveSlotsForTestShortcut(
                Event.SHARE_EVENT_TYPES);
        assertEquals(1, activeShareTimeSlots.size());
        ConversationInfo conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertNotNull(conversationInfo);
        assertEquals(conversationInfo.getShortcutId(), TEST_SHORTCUT_ID);
    }

    @Test
    public void testReportAppTargetEvent_appSharing()
            throws IntentFilter.MalformedMimeTypeException {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        AppTarget appTarget = new AppTarget.Builder(
                new AppTargetId(TEST_SHORTCUT_ID),
                TEST_PKG_NAME,
                UserHandle.of(USER_ID_PRIMARY))
                .setClassName(TEST_CLASS_NAME)
                .build();
        AppTargetEvent appTargetEvent =
                new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .build();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SEND, "image/jpg");

        mDataManager.reportShareTargetEvent(appTargetEvent, intentFilter);

        List<Range<Long>> activeShareTimeSlots = getActiveSlotsForAppShares();
        assertEquals(1, activeShareTimeSlots.size());
    }

    @Test
    public void testContactsChanged() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        final String newPhoneNumber = "+1000000000";
        mInjector.mContactsQueryHelper.mIsStarred = true;
        mInjector.mContactsQueryHelper.mPhoneNumber = newPhoneNumber;

        ContentObserver contentObserver = mDataManager.getContactsContentObserverForTesting(
                USER_ID_PRIMARY);
        contentObserver.onChange(false, ContactsContract.Contacts.CONTENT_URI, USER_ID_PRIMARY);

        List<ConversationInfo> conversations = getConversationsInPrimary();
        assertEquals(1, conversations.size());

        assertEquals(TEST_SHORTCUT_ID, conversations.get(0).getShortcutId());
        assertTrue(conversations.get(0).isContactStarred());
        assertEquals(newPhoneNumber, conversations.get(0).getContactPhoneNumber());
    }

    @Test
    public void testNotificationPosted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationPosted(mStatusBarNotification);

        List<Range<Long>> activeNotificationOpenTimeSlots = getActiveSlotsForTestShortcut(
                Event.NOTIFICATION_EVENT_TYPES);
        assertEquals(1, activeNotificationOpenTimeSlots.size());
    }

    @Test
    public void testNotificationOpened() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationRemoved(mStatusBarNotification, null,
                NotificationListenerService.REASON_CLICK);

        List<Range<Long>> activeNotificationOpenTimeSlots = getActiveSlotsForTestShortcut(
                Event.NOTIFICATION_EVENT_TYPES);
        assertEquals(1, activeNotificationOpenTimeSlots.size());
        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testNotificationDismissed() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        // Post one notification.
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);
        listenerService.onNotificationPosted(mStatusBarNotification);

        // Post another notification.
        listenerService.onNotificationPosted(mStatusBarNotification);

        // Removing one of the two notifications does not un-cache the shortcut.
        listenerService.onNotificationRemoved(mStatusBarNotification, null,
                NotificationListenerService.REASON_CANCEL);
        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), any(), anyString(), any(), anyInt());

        // Removing the second notification un-caches the shortcut.
        listenerService.onNotificationRemoved(mStatusBarNotification, null,
                NotificationListenerService.REASON_CANCEL_ALL);
        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testShortcutNotUncachedIfNotificationChannelCreated() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationPosted(mStatusBarNotification);
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);

        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        listenerService.onNotificationRemoved(mStatusBarNotification, null,
                NotificationListenerService.REASON_CANCEL_ALL);
        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testNotificationChannelCreated() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_ADDED);

        ConversationInfo conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertEquals(NOTIFICATION_CHANNEL_ID, conversationInfo.getNotificationChannelId());
        assertFalse(conversationInfo.isImportant());
        assertFalse(conversationInfo.isNotificationSilenced());
        assertFalse(conversationInfo.isDemoted());
    }

    @Test
    public void testNotificationChannelModified() {
        mNotificationChannel.setImportantConversation(true);

        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        ConversationInfo conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertEquals(NOTIFICATION_CHANNEL_ID, conversationInfo.getNotificationChannelId());
        assertTrue(conversationInfo.isImportant());
        assertFalse(conversationInfo.isNotificationSilenced());
        assertFalse(conversationInfo.isDemoted());
    }

    @Test
    public void testNotificationChannelDeleted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
        ConversationInfo conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertEquals(NOTIFICATION_CHANNEL_ID, conversationInfo.getNotificationChannelId());

        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
        conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertNull(conversationInfo.getNotificationChannelId());
        assertFalse(conversationInfo.isImportant());
        assertFalse(conversationInfo.isNotificationSilenced());
        assertFalse(conversationInfo.isDemoted());
    }

    @Test
    public void testUncacheShortcutWhenShutdown() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationPosted(mStatusBarNotification);
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);

        mShutdownBroadcastReceiver.onReceive(mContext, new Intent());
        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testDoNotUncacheShortcutWhenShutdownIfNotificationChannelCreated() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationPosted(mStatusBarNotification);
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);

        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        mShutdownBroadcastReceiver.onReceive(mContext, new Intent());
        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testShortcutAddedOrUpdated() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mShortcutChangeCallback.onShortcutsAddedOrUpdated(TEST_PKG_NAME,
                Collections.singletonList(shortcut), UserHandle.of(USER_ID_PRIMARY));

        List<ConversationInfo> conversations = getConversationsInPrimary();

        assertEquals(1, conversations.size());
        assertEquals(TEST_SHORTCUT_ID, conversations.get(0).getShortcutId());
    }

    @Test
    public void testShortcutDeleted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut1 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "sc1",
                buildPerson());
        ShortcutInfo shortcut2 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "sc2",
                buildPerson());
        mShortcutChangeCallback.onShortcutsAddedOrUpdated(TEST_PKG_NAME,
                Arrays.asList(shortcut1, shortcut2), UserHandle.of(USER_ID_PRIMARY));
        mShortcutChangeCallback.onShortcutsRemoved(TEST_PKG_NAME,
                Collections.singletonList(shortcut1), UserHandle.of(USER_ID_PRIMARY));

        List<ConversationInfo> conversations = getConversationsInPrimary();

        assertEquals(1, conversations.size());
        assertEquals("sc2", conversations.get(0).getShortcutId());

        verify(mNotificationManagerInternal)
                .onConversationRemoved(TEST_PKG_NAME, TEST_PKG_UID, "sc1");
    }

    @Test
    public void testCallLogContentObserver() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ContentObserver contentObserver = mDataManager.getCallLogContentObserverForTesting();
        contentObserver.onChange(false);
        long currentTimestamp = System.currentTimeMillis();
        mInjector.mCallLogQueryHelper.mEventConsumer.accept(PHONE_NUMBER,
                new Event(currentTimestamp - MILLIS_PER_MINUTE * 15L, Event.TYPE_CALL_OUTGOING));
        mInjector.mCallLogQueryHelper.mEventConsumer.accept(PHONE_NUMBER,
                new Event(currentTimestamp - MILLIS_PER_MINUTE * 10L, Event.TYPE_CALL_INCOMING));
        mInjector.mCallLogQueryHelper.mEventConsumer.accept(PHONE_NUMBER,
                new Event(currentTimestamp - MILLIS_PER_MINUTE * 5L, Event.TYPE_CALL_MISSED));

        List<Range<Long>> activeTimeSlots = getActiveSlotsForTestShortcut(Event.CALL_EVENT_TYPES);
        assertEquals(3, activeTimeSlots.size());
    }

    @Test
    public void testMmsSmsContentObserver() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        mDataManager.getUserDataForTesting(USER_ID_PRIMARY).setDefaultSmsApp(TEST_PKG_NAME);

        ContentObserver contentObserver = mDataManager.getMmsSmsContentObserverForTesting();
        contentObserver.onChange(false);
        long currentTimestamp = System.currentTimeMillis();
        Event outgoingSmsEvent =
                new Event(currentTimestamp - MILLIS_PER_MINUTE * 10L, Event.TYPE_SMS_OUTGOING);
        Event incomingSmsEvent =
                new Event(currentTimestamp - MILLIS_PER_MINUTE * 5L, Event.TYPE_SMS_INCOMING);
        mInjector.mMmsQueryHelper.mEventConsumer.accept(PHONE_NUMBER, outgoingSmsEvent);
        mInjector.mSmsQueryHelper.mEventConsumer.accept(PHONE_NUMBER, incomingSmsEvent);

        List<Range<Long>> activeTimeSlots = getActiveSlotsForTestShortcut(Event.SMS_EVENT_TYPES);
        assertEquals(2, activeTimeSlots.size());
    }

    @Test
    public void testDeleteUninstalledPackageDataOnPackageRemoved() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        assertNotNull(mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY));

        PackageMonitor packageMonitor = mDataManager.getPackageMonitorForTesting(USER_ID_PRIMARY);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_PRIMARY);
        intent.setData(Uri.parse("package:" + TEST_PKG_NAME));
        packageMonitor.onReceive(mContext, intent);
        assertNull(mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY));
    }

    @Test
    public void testPruneUninstalledPackageData() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        assertNotNull(mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY));

        doAnswer(ans -> null).when(mPackageManagerInternal)
                .forEachInstalledPackage(any(Consumer.class), anyInt());
        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);
        assertNull(mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY));
    }

    @Test
    public void testPruneCallEventsFromNonDialer() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        long currentTimestamp = System.currentTimeMillis();
        mInjector.mCallLogQueryHelper.mEventConsumer.accept(PHONE_NUMBER,
                new Event(currentTimestamp - MILLIS_PER_MINUTE, Event.TYPE_CALL_OUTGOING));

        List<Range<Long>> activeTimeSlots = getActiveSlotsForTestShortcut(Event.CALL_EVENT_TYPES);
        assertEquals(1, activeTimeSlots.size());

        mDataManager.getUserDataForTesting(USER_ID_PRIMARY).setDefaultDialer(null);
        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);
        activeTimeSlots = getActiveSlotsForTestShortcut(Event.CALL_EVENT_TYPES);
        assertTrue(activeTimeSlots.isEmpty());
    }

    @Test
    public void testPruneSmsEventsFromNonDefaultSmsApp() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        mDataManager.getUserDataForTesting(USER_ID_PRIMARY).setDefaultSmsApp(TEST_PKG_NAME);

        long currentTimestamp = System.currentTimeMillis();
        mInjector.mMmsQueryHelper.mEventConsumer.accept(PHONE_NUMBER,
                new Event(currentTimestamp - MILLIS_PER_MINUTE, Event.TYPE_SMS_OUTGOING));

        List<Range<Long>> activeTimeSlots = getActiveSlotsForTestShortcut(Event.SMS_EVENT_TYPES);
        assertEquals(1, activeTimeSlots.size());

        mDataManager.getUserDataForTesting(USER_ID_PRIMARY).setDefaultSmsApp(null);
        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);
        activeTimeSlots = getActiveSlotsForTestShortcut(Event.SMS_EVENT_TYPES);
        assertTrue(activeTimeSlots.isEmpty());
    }

    @Test
    public void testPruneInactiveCachedShortcuts() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached();
        mDataManager.addOrUpdateConversationInfo(shortcut);

        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);

        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID)), eq(USER_ID_PRIMARY));
    }

    @Test
    public void testBackupAndRestoration()
            throws IntentFilter.MalformedMimeTypeException {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                null);
        AppTarget appTarget = new AppTarget.Builder(new AppTargetId(TEST_SHORTCUT_ID), shortcut)
                .build();
        AppTargetEvent appTargetEvent =
                new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(ChooserActivity.LAUNCH_LOCATION_DIRECT_SHARE)
                        .build();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SEND, "image/jpg");

        mDataManager.reportShareTargetEvent(appTargetEvent, intentFilter);
        byte[] payload = mDataManager.getBackupPayload(USER_ID_PRIMARY);

        DataManager dataManager = new DataManager(mContext, mInjector);
        dataManager.onUserUnlocked(USER_ID_PRIMARY);
        dataManager.restore(USER_ID_PRIMARY, payload);
        ConversationInfo conversationInfo = dataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertNotNull(conversationInfo);
        assertEquals(conversationInfo.getShortcutId(), TEST_SHORTCUT_ID);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private List<ConversationInfo> getConversationsInPrimary() {
        List<ConversationInfo> conversations = new ArrayList<>();
        mDataManager.forPackagesInProfile(USER_ID_PRIMARY,
                packageData -> packageData.forAllConversations(conversations::add));
        return conversations;
    }

    private List<Range<Long>> getActiveSlotsForTestShortcut(Set<Integer> eventTypes) {
        List<Range<Long>> activeSlots = new ArrayList<>();
        mDataManager.forPackagesInProfile(USER_ID_PRIMARY, packageData ->
                activeSlots.addAll(
                        packageData.getEventHistory(TEST_SHORTCUT_ID)
                                .getEventIndex(eventTypes)
                                .getActiveTimeSlots()));
        return activeSlots;
    }

    private List<Range<Long>> getActiveSlotsForAppShares() {
        List<Range<Long>> activeSlots = new ArrayList<>();
        mDataManager.forPackagesInProfile(USER_ID_PRIMARY, packageData ->
                activeSlots.addAll(
                        packageData.getClassLevelEventHistory(TEST_CLASS_NAME)
                                .getEventIndex(Event.SHARE_EVENT_TYPES)
                                .getActiveTimeSlots()));
        return activeSlots;
    }

    private ShortcutInfo buildShortcutInfo(String packageName, int userId, String id,
            @Nullable Person person) {
        Context mockContext = mock(Context.class);
        when(mockContext.getPackageName()).thenReturn(packageName);
        when(mockContext.getUserId()).thenReturn(userId);
        when(mockContext.getUser()).thenReturn(UserHandle.of(userId));
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(mockContext, id)
                .setShortLabel(id)
                .setLongLived(true)
                .setIntent(new Intent("TestIntent"));
        if (person != null) {
            builder.setPersons(new Person[] {person});
        }
        return builder.build();
    }

    private Person buildPerson() {
        return buildPerson(true, false);
    }

    private Person buildPerson(boolean isImportant, boolean isBot) {
        return new Person.Builder()
                .setImportant(isImportant)
                .setBot(isBot)
                .setUri(CONTACT_URI)
                .build();
    }

    private UserInfo buildUserInfo(int userId) {
        return new UserInfo(userId, "", 0);
    }

    private class TestContactsQueryHelper extends ContactsQueryHelper {

        private Uri mContactUri;
        private boolean mIsStarred;
        private String mPhoneNumber;

        TestContactsQueryHelper(Context context) {
            super(context);
            mContactUri = Uri.parse(CONTACT_URI);
            mIsStarred = false;
            mPhoneNumber = PHONE_NUMBER;
        }

        @Override
        boolean query(@NonNull String contactUri) {
            return true;
        }

        @Override
        boolean querySince(long sinceTime) {
            return true;
        }

        @Override
        @Nullable
        Uri getContactUri() {
            return mContactUri;
        }

        @Override
        boolean isStarred() {
            return mIsStarred;
        }

        @Override
        @Nullable
        String getPhoneNumber() {
            return mPhoneNumber;
        }
    }

    private class TestCallLogQueryHelper extends CallLogQueryHelper {

        private final BiConsumer<String, Event> mEventConsumer;

        TestCallLogQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
            super(context, eventConsumer);
            mEventConsumer = eventConsumer;
        }

        @Override
        boolean querySince(long sinceTime) {
            return true;
        }

        @Override
        long getLastCallTimestamp() {
            return 100L;
        }
    }

    private class TestSmsQueryHelper extends SmsQueryHelper {

        private final BiConsumer<String, Event> mEventConsumer;

        TestSmsQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
            super(context, eventConsumer);
            mEventConsumer = eventConsumer;
        }

        @Override
        boolean querySince(long sinceTime) {
            return true;
        }

        @Override
        long getLastMessageTimestamp() {
            return 100L;
        }
    }

    private class TestMmsQueryHelper extends MmsQueryHelper {

        private final BiConsumer<String, Event> mEventConsumer;

        TestMmsQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
            super(context, eventConsumer);
            mEventConsumer = eventConsumer;
        }

        @Override
        boolean querySince(long sinceTime) {
            return true;
        }

        @Override
        long getLastMessageTimestamp() {
            return 100L;
        }
    }

    private class TestInjector extends DataManager.Injector {

        private final TestContactsQueryHelper mContactsQueryHelper =
                new TestContactsQueryHelper(mContext);
        private TestCallLogQueryHelper mCallLogQueryHelper;
        private TestMmsQueryHelper mMmsQueryHelper;
        private TestSmsQueryHelper mSmsQueryHelper;

        @Override
        ScheduledExecutorService createScheduledExecutor() {
            return mExecutorService;
        }

        @Override
        Executor getBackgroundExecutor() {
            return Runnable::run;
        }

        @Override
        ContactsQueryHelper createContactsQueryHelper(Context context) {
            return mContactsQueryHelper;
        }

        @Override
        CallLogQueryHelper createCallLogQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            mCallLogQueryHelper = new TestCallLogQueryHelper(context, eventConsumer);
            return mCallLogQueryHelper;
        }

        @Override
        MmsQueryHelper createMmsQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            mMmsQueryHelper = new TestMmsQueryHelper(context, eventConsumer);
            return mMmsQueryHelper;
        }

        @Override
        SmsQueryHelper createSmsQueryHelper(Context context,
                BiConsumer<String, Event> eventConsumer) {
            mSmsQueryHelper = new TestSmsQueryHelper(context, eventConsumer);
            return mSmsQueryHelper;
        }
    }
}
