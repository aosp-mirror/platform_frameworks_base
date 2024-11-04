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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;
import android.text.SpannableString;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class PartialConversationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private TestableLooper mTestableLooper;
    private PartialConversationInfo mInfo;
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mDefaultNotificationChannel;
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private INotificationManager mMockINotificationManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private ChannelEditorDialogController mChannelEditorDialogController;

    @Mock
    private Icon mIcon;
    @Mock
    private Drawable mDrawable;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mInfo = (PartialConversationInfo) layoutInflater.inflate(R.layout.partial_conversation_info,
                null);
        mInfo.setGutsParent(mock(NotificationGuts.class));
        // Our view is never attached to a window so the View#post methods in NotificationInfo never
        // get called. Setting this will skip the post and do the action immediately.
        mInfo.mSkipPost = true;

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq(TEST_PACKAGE_NAME), anyInt())).thenReturn(
                applicationInfo);
        final PackageInfo systemPackageInfo = new PackageInfo();
        systemPackageInfo.packageName = TEST_SYSTEM_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_SYSTEM_PACKAGE_NAME), anyInt()))
                .thenReturn(systemPackageInfo);
        when(mMockPackageManager.getPackageInfo(eq("android"), anyInt()))
                .thenReturn(packageInfo);

        // Package has one channel by default.
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(1);

        when(mIcon.loadDrawable(any())).thenReturn(mDrawable);

        // Some test channels.
        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        mDefaultNotificationChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setContentTitle(new SpannableString("title"))
                .build();
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                n, UserHandle.CURRENT, null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).build();
    }

    @Test
    public void testBindNotification_SetsName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("Package");
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                true,
                false);
        final TextView textView = mInfo.findViewById(R.id.name);
        assertTrue(textView.getText().toString().equals("Package"));
    }


    @Test
    public void testBindNotification_setsIcon() {
        when(mMockPackageManager.getApplicationIcon((ApplicationInfo) any())).thenReturn(mDrawable);
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                true,
                false);
        final ImageView iconView = mInfo.findViewById(R.id.icon);
        assertEquals(mDrawable.hashCode() + "", mDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_noDelegate() {
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                true,
                false);
        final TextView nameView = mInfo.findViewById(R.id.delegate_name);
        assertEquals(GONE, nameView.getVisibility());
    }

    @Test
    public void testBindNotification_delegate() throws Exception {
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, "other", 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = 7;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq("other"), anyInt())).thenReturn(
                applicationInfo);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("Other");

        NotificationEntry entry = new NotificationEntryBuilder().setSbn(mSbn).build();
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                entry,
                null,
                true,
                false);
        final TextView nameView = mInfo.findViewById(R.id.delegate_name);
        assertEquals(VISIBLE, nameView.getVisibility());
        assertTrue(nameView.getText().toString().contains("Proxied"));
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() {
        final CountDownLatch latch = new CountDownLatch(1);
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                true,
                false);

        final View settingsButton = mInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings_mainText() {
        final CountDownLatch latch = new CountDownLatch(1);
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                true,
                false);

        final View settingsButton = mInfo.findViewById(R.id.settings_link);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() {
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                true,
                false);
        final View settingsButton = mInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned() {
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                },
                false,
                false);
        final View settingsButton = mInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_whenAppUnblockable() {
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                true,
                true);

        assertEquals(GONE,
                mInfo.findViewById(R.id.turn_off_notifications).getVisibility());
    }
}
