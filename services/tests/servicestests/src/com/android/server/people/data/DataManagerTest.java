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

import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.job.JobScheduler;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
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
import android.content.pm.LauncherApps.ShortcutQuery;
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
import android.os.test.TestLooper;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Range;

import com.android.internal.app.ChooserActivity;
import com.android.internal.content.PackageMonitor;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.people.PeopleService;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import com.google.common.collect.Iterables;

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
import java.util.function.Function;

@RunWith(JUnit4.class)
public final class DataManagerTest {

    private static final int USER_ID_PRIMARY = 0;
    private static final int USER_ID_PRIMARY_MANAGED = 10;
    private static final int USER_ID_SECONDARY = 11;
    private static final String TEST_PKG_NAME = "pkg";
    private static final String TEST_CLASS_NAME = "class";
    private static final String TEST_SHORTCUT_ID = "sc";
    private static final String TEST_SHORTCUT_ID_2 = "sc2";
    private static final int TEST_PKG_UID = 35;
    private static final String CONTACT_URI = "content://com.android.contacts/contacts/lookup/123";
    private static final String PHONE_NUMBER = "+1234567890";
    private static final String NOTIFICATION_CHANNEL_ID = "test : sc";
    private static final String PARENT_NOTIFICATION_CHANNEL_ID = "test";
    private static final long MILLIS_PER_MINUTE = 1000L * 60L;
    private static final String GENERIC_KEY = "key";
    private static final String CUSTOM_KEY = "custom";

    @Mock
    private Context mContext;
    @Mock
    private ShortcutServiceInternal mShortcutServiceInternal;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private NotificationManagerInternal mNotificationManagerInternal;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private JobScheduler mJobScheduler;
    @Mock
    private StatusBarNotification mGenericSbn;
    @Mock
    private StatusBarNotification mConvoSbn;
    @Mock
    private NotificationListenerService.RankingMap mRankingMap;
    @Mock
    private Notification mNotification;
    @Mock
    private AlarmManager mAlarmManager;

    @Captor
    private ArgumentCaptor<ShortcutChangeCallback> mShortcutChangeCallbackCaptor;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;
    @Captor
    private ArgumentCaptor<Integer> mQueryFlagsCaptor;

    private ScheduledExecutorService mExecutorService;
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mParentNotificationChannel;
    private DataManager mDataManager;
    private CancellationSignal mCancellationSignal;
    private ShortcutChangeCallback mShortcutChangeCallback;
    private ShortcutInfo mShortcutInfo;
    private TestInjector mInjector;
    private TestLooper mLooper;

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
        mParentNotificationChannel = new NotificationChannel(
                PARENT_NOTIFICATION_CHANNEL_ID, "test channel",
                NotificationManager.IMPORTANCE_DEFAULT);

        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getPackageName()).thenReturn("android");
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        Context originalContext = getInstrumentation().getTargetContext();
        when(mContext.getApplicationInfo()).thenReturn(originalContext.getApplicationInfo());
        when(mContext.getUser()).thenReturn(originalContext.getUser());
        when(mContext.getPackageName()).thenReturn(originalContext.getPackageName());

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemServiceName(UserManager.class)).thenReturn(
                Context.USER_SERVICE);
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        when(mContext.getSystemServiceName(AlarmManager.class)).thenReturn(
                Context.ALARM_SERVICE);

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

        mNotificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "test channel", NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationChannel.setConversationId(PARENT_NOTIFICATION_CHANNEL_ID, TEST_SHORTCUT_ID);
        when(mNotificationManagerInternal.getNotificationChannel(anyString(), anyInt(),
                eq(mNotificationChannel.getId()))).thenReturn(mNotificationChannel);
        when(mNotificationManagerInternal.getNotificationChannel(anyString(), anyInt(),
                eq(mParentNotificationChannel.getId()))).thenReturn(mParentNotificationChannel);

        when(mGenericSbn.getKey()).thenReturn(GENERIC_KEY);
        when(mGenericSbn.getNotification()).thenReturn(mNotification);
        when(mGenericSbn.getPackageName()).thenReturn(TEST_PKG_NAME);
        when(mGenericSbn.getUser()).thenReturn(UserHandle.of(USER_ID_PRIMARY));
        when(mGenericSbn.getPostTime()).thenReturn(System.currentTimeMillis());
        when(mConvoSbn.getKey()).thenReturn(CUSTOM_KEY);
        when(mConvoSbn.getNotification()).thenReturn(mNotification);
        when(mConvoSbn.getPackageName()).thenReturn(TEST_PKG_NAME);
        when(mConvoSbn.getUser()).thenReturn(UserHandle.of(USER_ID_PRIMARY));
        when(mConvoSbn.getPostTime()).thenReturn(System.currentTimeMillis());

        when(mNotification.getShortcutId()).thenReturn(TEST_SHORTCUT_ID);

        mCancellationSignal = new CancellationSignal();

        mInjector = new TestInjector();
        mLooper = new TestLooper();
        mDataManager = new DataManager(mContext, mInjector, mLooper.getLooper());
        mDataManager.initialize();

        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);

        mShortcutInfo = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        when(mShortcutServiceInternal.getShortcuts(
                anyInt(), anyString(), anyLong(), anyString(), anyList(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(mShortcutInfo));
        verify(mShortcutServiceInternal).addShortcutChangeCallback(
                mShortcutChangeCallbackCaptor.capture());
        mShortcutChangeCallback = mShortcutChangeCallbackCaptor.getValue();

        verify(mContext, times(2)).registerReceiver(any(), any());
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

        sendGenericNotification();

        List<Range<Long>> activeNotificationOpenTimeSlots = getActiveSlotsForTestShortcut(
                Event.NOTIFICATION_EVENT_TYPES);
        assertEquals(1, activeNotificationOpenTimeSlots.size());
    }

    @Test
    public void testNotificationOpened() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CLICK);

        List<Range<Long>> activeNotificationOpenTimeSlots = getActiveSlotsForTestShortcut(
                Event.NOTIFICATION_EVENT_TYPES);
        assertEquals(1, activeNotificationOpenTimeSlots.size());
    }

    @Test
    public void testUncacheShortcutsWhenNotificationsDismissed() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        // The cached conversations are above the limit because every conversation has active
        // notifications. To uncache one of them, the notifications for that conversation need to
        // be dismissed.
        for (int i = 0; i < DataManager.MAX_CACHED_RECENT_SHORTCUTS + 1; i++) {
            String shortcutId = TEST_SHORTCUT_ID + i;
            ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, shortcutId,
                    buildPerson());
            shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
            mDataManager.addOrUpdateConversationInfo(shortcut);
            when(mNotification.getShortcutId()).thenReturn(shortcutId);
            sendGenericNotification();
        }

        // Post another notification for the last conversation.
        sendGenericNotification();

        // Removing one of the two notifications does not un-cache the shortcut.
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CANCEL);
        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), any(), anyString(), any(), anyInt(), anyInt());

        // Removing the second notification un-caches the shortcut.
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CANCEL_ALL);
        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME), anyList(), eq(USER_ID_PRIMARY),
                eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
    }

    @Test
    public void testConversationIsNotRecentIfCustomized() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        sendGenericNotification();
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        assertEquals(1, mDataManager.getRecentConversations(USER_ID_PRIMARY).size());

        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        assertTrue(mDataManager.getRecentConversations(USER_ID_PRIMARY).isEmpty());
    }

    @Test
    public void testAddConversationsListener() throws Exception {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        ConversationChannel conversationChannel = mDataManager.getConversation(TEST_PKG_NAME,
                USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);

        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);

        List<ConversationChannel> changedConversations = Arrays.asList(conversationChannel);
        verify(listener, times(0)).onConversationsUpdate(eq(changedConversations));
        mDataManager.notifyConversationsListeners(changedConversations);
        mLooper.dispatchAll();

        verify(listener, times(1)).onConversationsUpdate(eq(changedConversations));
    }

    @Test
    public void testAddConversationListenersNotifiesMultipleConversations() throws Exception {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        ConversationChannel conversationChannel = mDataManager.getConversation(TEST_PKG_NAME,
                USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);
        ShortcutInfo shortcut2 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID_2,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut2);
        mLooper.dispatchAll();
        ConversationChannel conversationChannel2 = mDataManager.getConversation(TEST_PKG_NAME,
                USER_ID_PRIMARY,
                TEST_SHORTCUT_ID_2);
        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);

        List<ConversationChannel> changedConversations = Arrays.asList(conversationChannel,
                conversationChannel2);
        verify(listener, times(0)).onConversationsUpdate(eq(changedConversations));
        mDataManager.notifyConversationsListeners(changedConversations);
        mLooper.dispatchAll();

        verify(listener, times(1)).onConversationsUpdate(eq(changedConversations));
        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        assertThat(capturedConversation.getValue()).containsExactly(conversationChannel,
                conversationChannel2);
    }

    @Test
    public void testAddOrUpdateStatusNotifiesConversationsListeners() throws Exception {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);
        mLooper.dispatchAll();
        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);

        ConversationStatus status = new ConversationStatus.Builder("cs1", ACTIVITY_GAME).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, status);
        mLooper.dispatchAll();

        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        ConversationChannel result = Iterables.getOnlyElement(capturedConversation.getValue());
        assertThat(result.getStatuses()).containsExactly(status);
        assertEquals(result.getShortcutInfo().getId(), TEST_SHORTCUT_ID);
    }

    @Test
    public void testGetConversationReturnsCustomizedConversation() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendConvoNotification();
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNotNull();

        ConversationChannel result = mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);
        assertThat(result).isNotNull();
        assertThat(result.hasBirthdayToday()).isFalse();
        assertThat(result.getStatuses()).isEmpty();
    }

    @Test
    public void testGetConversation() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNull();

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNotNull();
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID + "1")).isNull();

        sendConvoNotification();
        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);

        ConversationChannel result = mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);
        assertThat(result).isNotNull();
        assertEquals(shortcut.getId(), result.getShortcutInfo().getId());
        assertEquals(1, result.getShortcutInfo().getPersons().length);
        assertEquals(CONTACT_URI, result.getShortcutInfo().getPersons()[0].getUri());
        assertEquals(mNotificationChannel.getId(), result.getNotificationChannel().getId());
        assertEquals(mParentNotificationChannel.getId(),
                result.getNotificationChannel().getParentChannelId());
        assertEquals(mConvoSbn.getPostTime(), result.getLastEventTimestamp());
        assertTrue(result.hasActiveNotifications());
        assertFalse(result.hasBirthdayToday());
        assertThat(result.getStatuses()).containsExactly(cs);
    }

    @Test
    public void testOnNotificationChannelModified() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNull();

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendConvoNotification();

        ConversationChannel result = mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);
        assertFalse(result.getNotificationChannel().canBubble());

        NotificationChannel updated = new NotificationChannel(mNotificationChannel.getId(),
                mNotificationChannel.getDescription(), mNotificationChannel.getImportance());
        updated.setConversationId(mNotificationChannel.getParentChannelId(),
                mNotificationChannel.getConversationId());
        updated.setAllowBubbles(true);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                updated, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        ConversationInfo ci = mDataManager.getConversationInfo(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID);
        assertThat(ci).isNotNull();
        assertEquals(mNotificationChannel.getId(), ci.getNotificationChannelId());
        assertEquals(mParentNotificationChannel.getId(), ci.getParentNotificationChannelId());
        assertTrue(ci.isBubbled());
    }

    @Test
    public void testGetConversation_demoted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNull();

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);
        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNotNull();

        mNotificationChannel.setDemoted(true);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        assertThat(mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isNull();
    }

    @Test
    public void testGetConversationGetsPersonsData() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendGenericNotification();
        mDataManager.getConversation(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID);

        verify(mShortcutServiceInternal).getShortcuts(
                anyInt(), anyString(), anyLong(), anyString(), anyList(), any(), any(),
                mQueryFlagsCaptor.capture(), anyInt(), anyInt(), anyInt());
        Integer queryFlags = mQueryFlagsCaptor.getValue();
        assertThat(hasFlag(queryFlags, ShortcutQuery.FLAG_GET_PERSONS_DATA)).isTrue();
    }

    @Test
    public void testIsConversation() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        assertThat(mDataManager.isConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isFalse();

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);
        assertThat(mDataManager.isConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isTrue();
        assertThat(mDataManager.isConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID + "1")).isFalse();
    }

    @Test
    public void testIsConversation_demoted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        assertThat(mDataManager.isConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isFalse();

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_PINNED);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        mNotificationChannel.setDemoted(true);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        assertThat(mDataManager.isConversation(TEST_PKG_NAME, USER_ID_PRIMARY,
                TEST_SHORTCUT_ID)).isFalse();
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
    public void testShortcutAddedOrUpdated() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mShortcutChangeCallback.onShortcutsAddedOrUpdated(TEST_PKG_NAME,
                Collections.singletonList(shortcut), UserHandle.of(USER_ID_PRIMARY));
        mLooper.dispatchAll();

        List<ConversationInfo> conversations = getConversationsInPrimary();

        assertEquals(1, conversations.size());
        assertEquals(TEST_SHORTCUT_ID, conversations.get(0).getShortcutId());
        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        ConversationChannel result = Iterables.getOnlyElement(capturedConversation.getValue());
        assertEquals(result.getShortcutInfo().getId(), TEST_SHORTCUT_ID);
    }

    @Test
    public void testShortcutsDeleted() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut1 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "sc1",
                buildPerson());
        ShortcutInfo shortcut2 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "sc2",
                buildPerson());
        ShortcutInfo shortcut3 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "sc3",
                buildPerson());
        mShortcutChangeCallback.onShortcutsAddedOrUpdated(TEST_PKG_NAME,
                Arrays.asList(shortcut1, shortcut2, shortcut3), UserHandle.of(USER_ID_PRIMARY));
        mShortcutChangeCallback.onShortcutsRemoved(TEST_PKG_NAME,
                List.of(shortcut1, shortcut3), UserHandle.of(USER_ID_PRIMARY));

        List<ConversationInfo> conversations = getConversationsInPrimary();

        assertEquals(1, conversations.size());
        assertEquals("sc2", conversations.get(0).getShortcutId());

        verify(mNotificationManagerInternal)
                .onConversationRemoved(TEST_PKG_NAME, TEST_PKG_UID, Set.of("sc1", "sc3"));
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
    public void testConversationLastEventTimestampUpdate() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        PackageData packageData = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY);
        ConversationInfo conversationInfo =
                packageData.getConversationStore().getConversation(TEST_SHORTCUT_ID);
        Event event = new Event(123L, Event.TYPE_IN_APP_CONVERSATION);

        mInjector.mUsageStatsQueryHelper.mEventListener.onEvent(packageData, conversationInfo,
                event);
        ConversationInfo newConversationInfo =
                packageData.getConversationStore().getConversation(TEST_SHORTCUT_ID);
        assertEquals(123L, newConversationInfo.getLastEventTimestamp());
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
    public void testPruneExpiredConversationStatuses() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ConversationStatus cs1 = new ConversationStatus.Builder("cs1", 9)
                .setEndTimeMillis(System.currentTimeMillis())
                .build();
        ConversationStatus cs2 = new ConversationStatus.Builder("cs2", 10)
                .build();
        ConversationStatus cs3 = new ConversationStatus.Builder("cs3", 1)
                .setEndTimeMillis(Long.MAX_VALUE)
                .build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs1);
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2);
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs3);
        mLooper.dispatchAll();

        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);
        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);
        mLooper.dispatchAll();

        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .doesNotContain(cs1);
        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs2);
        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs3);
        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        List<ConversationChannel> results = capturedConversation.getValue();
        ConversationChannel result = Iterables.getOnlyElement(capturedConversation.getValue());
        // CHeck cs1 has been removed and only cs2 and cs3 remain.
        assertThat(result.getStatuses()).containsExactly(cs2, cs3);
    }

    @Test
    public void testDoNotUncacheShortcutWithActiveNotifications() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        for (int i = 0; i < DataManager.MAX_CACHED_RECENT_SHORTCUTS + 1; i++) {
            String shortcutId = TEST_SHORTCUT_ID + i;
            ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, shortcutId,
                    buildPerson());
            shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
            mDataManager.addOrUpdateConversationInfo(shortcut);
            when(mNotification.getShortcutId()).thenReturn(shortcutId);
            sendGenericNotification();
        }

        mDataManager.pruneDataForUser(USER_ID_PRIMARY, mCancellationSignal);

        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), anyString(), anyString(), anyList(), anyInt(), anyInt());
    }

    @Test
    public void testUncacheOldestCachedShortcut() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        for (int i = 0; i < DataManager.MAX_CACHED_RECENT_SHORTCUTS + 1; i++) {
            String shortcutId = TEST_SHORTCUT_ID + i;
            ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, shortcutId,
                    buildPerson());
            shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
            mDataManager.addOrUpdateConversationInfo(shortcut);
            when(mNotification.getShortcutId()).thenReturn(shortcutId);
            when(mGenericSbn.getPostTime()).thenReturn(100L + i);
            sendGenericNotification();
            listenerService.onNotificationRemoved(mGenericSbn, null,
                    NotificationListenerService.REASON_CANCEL);
        }

        // Only the shortcut #0 is uncached, all the others are not.
        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME),
                eq(Collections.singletonList(TEST_SHORTCUT_ID + 0)), eq(USER_ID_PRIMARY),
                eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
        for (int i = 1; i < DataManager.MAX_CACHED_RECENT_SHORTCUTS + 1; i++) {
            verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                    anyInt(), anyString(), anyString(),
                    eq(Collections.singletonList(TEST_SHORTCUT_ID + i)), anyInt(),
                    eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
        }
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

        DataManager dataManager = new DataManager(mContext, mInjector, mLooper.getLooper());
        dataManager.onUserUnlocked(USER_ID_PRIMARY);
        dataManager.restore(USER_ID_PRIMARY, payload);
        ConversationInfo conversationInfo = dataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertNotNull(conversationInfo);
        assertEquals(conversationInfo.getShortcutId(), TEST_SHORTCUT_ID);
    }

    @Test
    public void testGetRecentConversations() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendGenericNotification();

        List<ConversationChannel> result = mDataManager.getRecentConversations(USER_ID_PRIMARY);
        assertEquals(1, result.size());
        assertEquals(shortcut.getId(), result.get(0).getShortcutInfo().getId());
        assertEquals(1, result.get(0).getShortcutInfo().getPersons().length);
        assertEquals(CONTACT_URI, result.get(0).getShortcutInfo().getPersons()[0].getUri());
        assertEquals(mParentNotificationChannel.getId(),
                result.get(0).getNotificationChannel().getId());
        assertEquals(null, result.get(0).getNotificationChannel().getParentChannelId());
        assertEquals(mGenericSbn.getPostTime(), result.get(0).getLastEventTimestamp());
        assertTrue(result.get(0).hasActiveNotifications());
    }

    @Test
    public void testGetRecentConversationsGetsPersonsData() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendGenericNotification();

       mDataManager.getRecentConversations(USER_ID_PRIMARY);

        verify(mShortcutServiceInternal).getShortcuts(
                anyInt(), anyString(), anyLong(), anyString(), anyList(), any(), any(),
                mQueryFlagsCaptor.capture(), anyInt(), anyInt(), anyInt());
        Integer queryFlags = mQueryFlagsCaptor.getValue();
        assertThat(hasFlag(queryFlags, ShortcutQuery.FLAG_GET_PERSONS_DATA)).isTrue();
    }

    @Test
    public void testPruneOldRecentConversations() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        when(mNotification.getShortcutId()).thenReturn(TEST_SHORTCUT_ID);
        sendGenericNotification();
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CLICK);

        mDataManager.pruneOldRecentConversations(USER_ID_PRIMARY,
                System.currentTimeMillis() + (10 * DateUtils.DAY_IN_MILLIS) + 1);

        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME), eq(Collections.singletonList(TEST_SHORTCUT_ID)),
                eq(USER_ID_PRIMARY), eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
    }

    @Test
    public void testGetLastInteraction() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendGenericNotification();

        assertEquals(mGenericSbn.getPostTime(),
                mDataManager.getLastInteraction(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID));
        assertEquals(0L,
                mDataManager.getLastInteraction("not_test_pkg", USER_ID_PRIMARY, TEST_SHORTCUT_ID));
        assertEquals(0L,
                mDataManager.getLastInteraction(TEST_PKG_NAME, USER_ID_PRIMARY_MANAGED,
                        TEST_SHORTCUT_ID));
        assertEquals(0L,
                mDataManager.getLastInteraction(TEST_PKG_NAME, USER_ID_SECONDARY,
                        TEST_SHORTCUT_ID));
    }

    @Test
    public void testAddOrUpdateStatus_noCachedShortcut() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();

        try {
            mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);
            fail("Updated a conversation info that didn't previously exist");
        } catch (IllegalArgumentException e) {
            // good
        }
    }

    @Test
    public void testAddOrUpdateStatus() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);

        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs);

        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2);

        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs);
        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs2);

        verify(mAlarmManager, never()).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testAddOrUpdateStatus_schedulesJob() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY)
                .setEndTimeMillis(1000)
                .build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);

        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME)
                .setEndTimeMillis(3000)
                .build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2);

        verify(mAlarmManager, times(2)).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testClearStatus() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2);
        mLooper.dispatchAll();

        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);
        mDataManager.clearStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2.getId());
        mLooper.dispatchAll();

        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .contains(cs);
        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .doesNotContain(cs2);
        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        ConversationChannel result = Iterables.getOnlyElement(capturedConversation.getValue());
        assertThat(result.getStatuses()).containsExactly(cs);
    }

    @Test
    public void testClearStatuses() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME).build();
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs);
        mDataManager.addOrUpdateStatus(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID, cs2);
        mLooper.dispatchAll();

        PeopleService.ConversationsListener listener = mock(
                PeopleService.ConversationsListener.class);
        mDataManager.addConversationsListener(listener);
        mDataManager.clearStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID);
        mLooper.dispatchAll();

        assertThat(mDataManager.getStatuses(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID))
                .isEmpty();
        ArgumentCaptor<List<ConversationChannel>> capturedConversation = ArgumentCaptor.forClass(
                List.class);
        verify(listener, times(1)).onConversationsUpdate(capturedConversation.capture());
        ConversationChannel result = Iterables.getOnlyElement(capturedConversation.getValue());
        assertThat(result.getStatuses()).isEmpty();
    }

    @Test
    public void testNonCachedShortcutNotInRecentList() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY_MANAGED,
                TEST_SHORTCUT_ID, buildPerson());
        mDataManager.addOrUpdateConversationInfo(shortcut);

        sendGenericNotification();

        List<ConversationChannel> result = mDataManager.getRecentConversations(USER_ID_PRIMARY);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testCustomizedConversationNotInRecentList() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        // Post a notification and customize the notification settings.
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        sendGenericNotification();
        listenerService.onNotificationChannelModified(TEST_PKG_NAME, UserHandle.of(USER_ID_PRIMARY),
                mNotificationChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        List<ConversationChannel> result = mDataManager.getRecentConversations(USER_ID_PRIMARY);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNotificationRemoved() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        sendGenericNotification();
        // posting updates the last interaction time, so delay before deletion
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        long approxDeletionTime = System.currentTimeMillis();
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CANCEL);

        ConversationInfo conversationInfo = mDataManager.getPackage(TEST_PKG_NAME, USER_ID_PRIMARY)
                .getConversationStore()
                .getConversation(TEST_SHORTCUT_ID);
        assertTrue(conversationInfo.getLastEventTimestamp() - approxDeletionTime < 100);
    }

    @Test
    public void testRemoveRecentConversation() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        shortcut.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        sendGenericNotification();
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CANCEL);
        mDataManager.removeRecentConversation(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                USER_ID_PRIMARY);

        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME), eq(Collections.singletonList(TEST_SHORTCUT_ID)),
                eq(USER_ID_PRIMARY), eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
    }

    @Test
    public void testRemoveAllRecentConversations() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut1 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "1",
                buildPerson());
        shortcut1.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut1);

        ShortcutInfo shortcut2 = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, "2",
                buildPerson());
        shortcut2.setCached(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        mDataManager.addOrUpdateConversationInfo(shortcut2);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        // Post a notification and then dismiss it for conversation #1.
        when(mNotification.getShortcutId()).thenReturn("1");
        sendGenericNotification();
        listenerService.onNotificationRemoved(mGenericSbn, null,
                NotificationListenerService.REASON_CANCEL);

        // Post a notification for conversation #2, but don't dismiss it. Its shortcut won't be
        // uncached when removeAllRecentConversations() is called.
        when(mNotification.getShortcutId()).thenReturn("2");
        sendGenericNotification();

        mDataManager.removeAllRecentConversations(USER_ID_PRIMARY);

        verify(mShortcutServiceInternal).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME), eq(Collections.singletonList("1")),
                eq(USER_ID_PRIMARY), eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
        verify(mShortcutServiceInternal, never()).uncacheShortcuts(
                anyInt(), any(), eq(TEST_PKG_NAME), eq(Collections.singletonList("2")),
                eq(USER_ID_PRIMARY), eq(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS));
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
            builder.setPersons(new Person[]{person});
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

    /**
     * Returns {@code true} iff {@link ShortcutQuery}'s {@code queryFlags} has {@code flag} set.
     */
    private static boolean hasFlag(int queryFlags, int flag) {
        return (queryFlags & flag) != 0;
    }

    // "Sends" a notification to a non-customized notification channel - the notification channel
    // is something generic like "messages" and the notification has a  shortcut id
    private void sendGenericNotification() {
        when(mNotification.getChannelId()).thenReturn(PARENT_NOTIFICATION_CHANNEL_ID);
        doAnswer(invocationOnMock -> {
            NotificationListenerService.Ranking ranking = (NotificationListenerService.Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    (String) invocationOnMock.getArguments()[0],
                    0,
                    false,
                    0,
                    0,
                    mParentNotificationChannel.getImportance(),
                    null, null,
                    mParentNotificationChannel, null, null, true, 0, false, -1, false, null, null,
                    false, false, false, null, 0, false);
            return true;
        }).when(mRankingMap).getRanking(eq(GENERIC_KEY),
                any(NotificationListenerService.Ranking.class));
        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationPosted(mGenericSbn, mRankingMap);
    }

    // "Sends" a notification to a customized notification channel - the notification channel
    // is specific to a person, and the channel has a convo id matching the notification's shortcut
    // and the channel has a parent channel id
    private void sendConvoNotification() {
        when(mNotification.getChannelId()).thenReturn(NOTIFICATION_CHANNEL_ID);
        doAnswer(invocationOnMock -> {
            NotificationListenerService.Ranking ranking = (NotificationListenerService.Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    (String) invocationOnMock.getArguments()[0],
                    0,
                    false,
                    0,
                    0,
                    mNotificationChannel.getImportance(),
                    null, null,
                    mNotificationChannel, null, null, true, 0, false, -1, false, null, null, false,
                    false, false, null, 0, false);
            return true;
        }).when(mRankingMap).getRanking(eq(CUSTOM_KEY),
                any(NotificationListenerService.Ranking.class));

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);
        listenerService.onNotificationPosted(mConvoSbn, mRankingMap);
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

    private class TestUsageStatsQueryHelper extends UsageStatsQueryHelper {

        private final EventListener mEventListener;

        TestUsageStatsQueryHelper(int userId,
                Function<String, PackageData> packageDataGetter,
                EventListener eventListener) {
            super(userId, packageDataGetter, eventListener);
            mEventListener = eventListener;
        }
    }

    private class TestInjector extends DataManager.Injector {

        private final TestContactsQueryHelper mContactsQueryHelper =
                new TestContactsQueryHelper(mContext);
        private TestCallLogQueryHelper mCallLogQueryHelper;
        private TestMmsQueryHelper mMmsQueryHelper;
        private TestSmsQueryHelper mSmsQueryHelper;
        private TestUsageStatsQueryHelper mUsageStatsQueryHelper;

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

        @Override
        UsageStatsQueryHelper createUsageStatsQueryHelper(@UserIdInt int userId,
                Function<String, PackageData> packageDataGetter,
                UsageStatsQueryHelper.EventListener eventListener) {
            mUsageStatsQueryHelper =
                    new TestUsageStatsQueryHelper(userId, packageDataGetter, eventListener);
            return mUsageStatsQueryHelper;
        }
    }
}
