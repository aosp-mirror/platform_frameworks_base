/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;

import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoRule;
import org.mockito.junit.MockitoJUnit;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for {@link NotificationGutsManager}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGutsManagerTest extends SysuiTestCase {
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";

    private NotificationChannel mTestNotificationChannel = new NotificationChannel(
            TEST_CHANNEL_ID, TEST_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private NotificationTestHelper mHelper;
    private NotificationGutsManager mGutsManager;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private NotificationInfo.CheckSaveListener mCheckSaveListener;
    @Mock private NotificationGutsManager.OnSettingsClickListener mOnSettingsClickListener;

    @Before
    public void setUp() {
        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());

        mHelper = new NotificationTestHelper(mContext);

        mGutsManager = new NotificationGutsManager(mContext);
        mGutsManager.setUpWithPresenter(mPresenter, mEntryManager, mStackScroller,
                mCheckSaveListener, mOnSettingsClickListener);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Test methods:

    @Test
    public void testOpenAndCloseGuts() {
        NotificationGuts guts = spy(new NotificationGuts(mContext));
        when(guts.post(any())).thenAnswer(invocation -> {
            mHandler.post(((Runnable) invocation.getArguments()[0]));
            return null;
        });

        // Test doesn't support animation since the guts view is not attached.
        doNothing().when(guts).openControls(
                eq(true) /* shouldDoCircularReveal */,
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        ExpandableNotificationRow realRow = createTestNotificationRow();
        NotificationMenuRowPlugin.MenuItem menuItem = createTestMenuItem(realRow);

        ExpandableNotificationRow row = spy(realRow);
        when(row.getWindowToken()).thenReturn(new Binder());
        when(row.getGuts()).thenReturn(guts);

        mGutsManager.openGuts(row, 0, 0, menuItem);
        assertEquals(View.INVISIBLE, guts.getVisibility());
        mTestableLooper.processAllMessages();
        verify(guts).openControls(
                eq(true),
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        assertEquals(View.VISIBLE, guts.getVisibility());
        mGutsManager.closeAndSaveGuts(false, false, false, 0, 0, false);

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
        verify(row, times(1)).setGutsView(any());
    }

    @Test
    public void testChangeDensityOrFontScale() {
        NotificationGuts guts = spy(new NotificationGuts(mContext));
        when(guts.post(any())).thenAnswer(invocation -> {
            mHandler.post(((Runnable) invocation.getArguments()[0]));
            return null;
        });

        // Test doesn't support animation since the guts view is not attached.
        doNothing().when(guts).openControls(
                eq(true) /* shouldDoCircularReveal */,
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        ExpandableNotificationRow realRow = createTestNotificationRow();
        NotificationMenuRowPlugin.MenuItem menuItem = createTestMenuItem(realRow);

        ExpandableNotificationRow row = spy(realRow);
        when(row.getWindowToken()).thenReturn(new Binder());
        when(row.getGuts()).thenReturn(guts);
        doNothing().when(row).inflateGuts();

        mGutsManager.openGuts(row, 0, 0, menuItem);
        mTestableLooper.processAllMessages();
        verify(guts).openControls(
                eq(true),
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        row.onDensityOrFontScaleChanged();
        mGutsManager.onDensityOrFontScaleChanged(row);
        mTestableLooper.processAllMessages();

        mGutsManager.closeAndSaveGuts(false, false, false, 0, 0, false);

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
        verify(row, times(2)).setGutsView(any());
    }

    @Test
    public void testAppOpsSettingsIntent_camera() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_mic() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_RECORD_AUDIO);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_mic() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_RECORD_AUDIO);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_mic_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_RECORD_AUDIO);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_mic_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_RECORD_AUDIO);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mPresenter, times(1)).startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testInitializeNotificationInfoView_showBlockingHelper() throws Exception {
        NotificationInfo notificationInfoView = mock(NotificationInfo.class);
        ExpandableNotificationRow row = spy(mHelper.createRow());
        row.setBlockingHelperShowing(true);
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        when(row.getIsNonblockable()).thenReturn(false);
        StatusBarNotification statusBarNotification = row.getStatusBarNotification();

        mGutsManager.initializeNotificationInfo(row, notificationInfoView);

        verify(notificationInfoView).bindNotification(
                any(PackageManager.class),
                any(INotificationManager.class),
                eq(statusBarNotification.getPackageName()),
                isNull(),
                anyInt(),
                eq(statusBarNotification),
                any(NotificationInfo.CheckSaveListener.class),
                any(NotificationInfo.OnSettingsClickListener.class),
                any(NotificationInfo.OnAppSettingsClickListener.class),
                eq(false),
                eq(true) /* isForBlockingHelper */,
                eq(true) /* isUserSentimentNegative */);
    }

    @Test
    public void testInitializeNotificationInfoView_dontShowBlockingHelper() throws Exception {
        NotificationInfo notificationInfoView = mock(NotificationInfo.class);
        ExpandableNotificationRow row = spy(mHelper.createRow());
        row.setBlockingHelperShowing(false);
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        when(row.getIsNonblockable()).thenReturn(false);
        StatusBarNotification statusBarNotification = row.getStatusBarNotification();

        mGutsManager.initializeNotificationInfo(row, notificationInfoView);

        verify(notificationInfoView).bindNotification(
                any(PackageManager.class),
                any(INotificationManager.class),
                eq(statusBarNotification.getPackageName()),
                isNull(),
                anyInt(),
                eq(statusBarNotification),
                any(NotificationInfo.CheckSaveListener.class),
                any(NotificationInfo.OnSettingsClickListener.class),
                any(NotificationInfo.OnAppSettingsClickListener.class),
                eq(false),
                eq(false) /* isForBlockingHelper */,
                eq(true) /* isUserSentimentNegative */);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Utility methods:

    private ExpandableNotificationRow createTestNotificationRow() {
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                                        .setContentTitle("foo")
                                        .setColorized(true)
                                        .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                                        .setSmallIcon(android.R.drawable.sym_def_app_icon);

        try {
            ExpandableNotificationRow row = mHelper.createRow(nb.build());
            row.getEntry().channel = mTestNotificationChannel;
            return row;
        } catch (Exception e) {
            fail();
            return null;
        }
    }

    private NotificationMenuRowPlugin.MenuItem createTestMenuItem(ExpandableNotificationRow row) {
        NotificationMenuRowPlugin menuRow = new NotificationMenuRow(mContext);
        menuRow.createMenu(row, row.getStatusBarNotification());

        NotificationMenuRowPlugin.MenuItem menuItem = menuRow.getLongpressMenuItem(mContext);
        assertNotNull(menuItem);
        return menuItem;
    }
}
