/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.NotificationManager.IMPORTANCE_LOW;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.companion.ICompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Color;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.InstrumentationRegistry;
import android.testing.TestableLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

public class NotificationManagerServiceTest {
    private static final long WAIT_FOR_IDLE_TIMEOUT = 2;
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";
    private final int uid = Binder.getCallingUid();
    private NotificationManagerService mNotificationManagerService;
    private INotificationManager mBinderService;
    private NotificationManagerInternal mInternalService;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private PackageManager mPackageManagerClient;
    private Context mContext = InstrumentationRegistry.getTargetContext();
    private final String PKG = mContext.getPackageName();
    private TestableLooper mTestableLooper;
    @Mock
    private RankingHelper mRankingHelper;
    private NotificationChannel mTestNotificationChannel = new NotificationChannel(
            TEST_CHANNEL_ID, TEST_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);
    @Mock
    private NotificationManagerService.NotificationListeners mNotificationListeners;
    private ManagedServices.ManagedServiceInfo mListener;
    @Mock private ICompanionDeviceManager mCompanionMgr;

    // Use a Testable subclass so we can simulate calls from the system without failing.
    private static class TestableNotificationManagerService extends NotificationManagerService {
        public TestableNotificationManagerService(Context context) { super(context); }

        @Override
        protected boolean isCallerSystem() {
            return true;
        }

        @Override
        protected ICompanionDeviceManager getCompanionManager() {
            return null;
        }
    }

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mNotificationManagerService = new TestableNotificationManagerService(mContext);

        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManagerClient.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        final LightsManager mockLightsManager = mock(LightsManager.class);
        when(mockLightsManager.getLight(anyInt())).thenReturn(mock(Light.class));
        // Use this testable looper.
        mTestableLooper = new TestableLooper(false);

        mListener = mNotificationListeners.new ManagedServiceInfo(
                null, new ComponentName(PKG, "test_class"), uid, true, null, 0);
        when(mNotificationListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        mNotificationManagerService.init(mTestableLooper.getLooper(), mPackageManager,
                mPackageManagerClient, mockLightsManager, mNotificationListeners, mCompanionMgr);

        // Tests call directly into the Binder.
        mBinderService = mNotificationManagerService.getBinderService();
        mInternalService = mNotificationManagerService.getInternalService();

        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(mTestNotificationChannel)));
    }

    public void waitForIdle() throws Exception {
        mTestableLooper.processAllMessages();
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel) {
        return generateNotificationRecord(channel, null);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel,
            Notification.TvExtender extender) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        if (extender != null) {
            nb.extend(extender);
        }
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", uid, 0,
                nb.build(), new UserHandle(uid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_SingleChannel() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertTrue(createdChannel != null);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_NullChannelThrowsException() throws Exception {
        try {
            mBinderService.createNotificationChannels("test_pkg",
                    new ParceledListSlice(Arrays.asList(null)));
            fail("Exception should be thrown immediately.");
        } catch (NullPointerException e) {
            // pass
        }
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_TwoChannels() throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id1", "name", NotificationManager.IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        assertTrue(mBinderService.getNotificationChannel("test_pkg", "id1") != null);
        assertTrue(mBinderService.getNotificationChannel("test_pkg", "id2") != null);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_SecondCreateDoesNotChangeImportance()
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel)));

        // Recreating the channel doesn't throw, but ignores importance.
        final NotificationChannel dupeChannel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(dupeChannel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_IdenticalChannelsInListIgnoresSecond()
            throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_suspended() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerSuspendedByAdmin(eq(r));
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_blockedChannel() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_blockedApp() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        r.setUserImportance(NotificationManager.IMPORTANCE_NONE);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    @UiThreadTest
    public void testEnqueueNotificationWithTag_PopulatesGetActiveNotifications() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationImmediatelyAfterEnqueue() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationWhilePostedAndEnqueued() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationsFromListenerImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotificationsImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_IgnoreForegroundService() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_IgnoreOtherPackages() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications("other_pkg_name", sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_NullPkgRemovesAll() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_NullPkgIgnoresUserAllNotifications() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), UserHandle.USER_ALL);
        // Null pkg is how we signal a user switch.
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testRemoveForegroundServiceFlag_ImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", null,
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mInternalService.removeForegroundServiceFlagFromNotification(PKG, sbn.getId(),
                sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs[0].getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE);
    }

    @Test
    @UiThreadTest
    public void testTvExtenderChannelOverride_onTv() throws Exception {
        mNotificationManagerService.setIsTelevision(true);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean())).thenReturn(
                        new NotificationChannel("foo", "foo", NotificationManager.IMPORTANCE_HIGH));

        Notification.TvExtender tv = new Notification.TvExtender().setChannelId("foo");
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mRankingHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testTvExtenderChannelOverride_notOnTv() throws Exception {
        mNotificationManagerService.setIsTelevision(false);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(
                mTestNotificationChannel);

        Notification.TvExtender tv = new Notification.TvExtender().setChannelId("foo");
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mRankingHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq(mTestNotificationChannel.getId()), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testCreateChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);
        NotificationChannel channel2 = new NotificationChannel("a", "b", IMPORTANCE_LOW);
        when(mRankingHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(channel2.getId()), anyBoolean()))
                .thenReturn(channel2);

        reset(mNotificationListeners);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(mTestNotificationChannel, channel2)));
        verify(mNotificationListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
        verify(mNotificationListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(channel2),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
    }

    @Test
    @UiThreadTest
    public void testCreateChannelGroupNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        NotificationChannelGroup group1 = new NotificationChannelGroup("a", "b");
        NotificationChannelGroup group2 = new NotificationChannelGroup("n", "m");

        reset(mNotificationListeners);
        mBinderService.createNotificationChannelGroups(PKG,
                new ParceledListSlice(Arrays.asList(group1, group2)));
        verify(mNotificationListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(group1),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
        verify(mNotificationListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(group2),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
    }

    @Test
    @UiThreadTest
    public void testUpdateChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        mTestNotificationChannel.setLightColor(Color.CYAN);
        when(mRankingHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);

        reset(mNotificationListeners);
        mBinderService.updateNotificationChannelForPackage(PKG, 0, mTestNotificationChannel);
        verify(mNotificationListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    @UiThreadTest
    public void testDeleteChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);
        reset(mNotificationListeners);
        mBinderService.deleteNotificationChannel(PKG, mTestNotificationChannel.getId());
        verify(mNotificationListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED));
    }

    @Test
    @UiThreadTest
    public void testDeleteChannelGroupNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        NotificationChannelGroup ncg = new NotificationChannelGroup("a", "b/c");
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannelGroup(eq(ncg.getId()), eq(PKG), anyInt()))
                .thenReturn(ncg);
        reset(mNotificationListeners);
        mBinderService.deleteNotificationChannelGroup(PKG, ncg.getId());
        verify(mNotificationListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(ncg),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED));
    }

    @Test
    @UiThreadTest
    public void testUpdateNotificationChannelFromPrivilegedListener_success() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        mBinderService.updateNotificationChannelFromPrivilegedListener(
                null, PKG, Process.myUserHandle(), mTestNotificationChannel);

        verify(mRankingHelper, times(1)).updateNotificationChannel(anyString(), anyInt(), any());

        verify(mNotificationListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    @UiThreadTest
    public void testUpdateNotificationChannelFromPrivilegedListener_noAccess() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        try {
            mBinderService.updateNotificationChannelFromPrivilegedListener(
                    null, PKG, Process.myUserHandle(), mTestNotificationChannel);
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).updateNotificationChannel(anyString(), anyInt(), any());

        verify(mNotificationListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    @UiThreadTest
    public void testUpdateNotificationChannelFromPrivilegedListener_badUser() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mNotificationListeners.checkServiceTokenLocked(any())).thenReturn(mListener);

        try {
            mBinderService.updateNotificationChannelFromPrivilegedListener(
                    null, PKG, UserHandle.ALL, mTestNotificationChannel);
            fail("incorrectly allowed a change to a user listener cannot see");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).updateNotificationChannel(anyString(), anyInt(), any());

        verify(mNotificationListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelFromPrivilegedListener_success() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        mBinderService.getNotificationChannelsFromPrivilegedListener(
                null, PKG, Process.myUserHandle());

        verify(mRankingHelper, times(1)).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelFromPrivilegedListener_noAccess() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        try {
            mBinderService.getNotificationChannelsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelFromPrivilegedListener_badUser() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mNotificationListeners.checkServiceTokenLocked(any())).thenReturn(mListener);

        try {
            mBinderService.getNotificationChannelsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listener getting channels from a user they cannot see");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelGroupsFromPrivilegedListener_success() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                null, PKG, Process.myUserHandle());

        verify(mRankingHelper, times(1)).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelGroupsFromPrivilegedListener_noAccess() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);

        try {
            mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    @UiThreadTest
    public void testGetNotificationChannelGroupsFromPrivilegedListener_badUser() throws Exception {
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, uid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mNotificationListeners.checkServiceTokenLocked(any())).thenReturn(mListener);

        try {
            mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mRankingHelper, never()).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    @UiThreadTest
    public void testHasCompanionDevice_failure() throws Exception {
        when(mCompanionMgr.getAssociations(anyString(), anyInt())).thenThrow(
                new IllegalArgumentException());
        mNotificationManagerService.hasCompanionDevice(mListener);
    }

    @Test
    @UiThreadTest
    public void testHasCompanionDevice_noService() throws Exception {
        mNotificationManagerService = new TestableNotificationManagerService(mContext);

        assertFalse(mNotificationManagerService.hasCompanionDevice(mListener));
    }

}
