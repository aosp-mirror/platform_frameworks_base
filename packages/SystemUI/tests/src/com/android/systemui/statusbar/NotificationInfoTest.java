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

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import com.android.internal.util.CharSequences;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.Mockito;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private NotificationInfo mNotificationInfo;
    private final INotificationManager mMockINotificationManager = mock(INotificationManager.class);
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private NotificationChannel mNotificationChannel;
    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        // Inflate the layout
        final LayoutInflater layoutInflater =
                LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationInfo) layoutInflater.inflate(R.layout.notification_info,
                null);

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = 1;  // non-zero
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
                applicationInfo);

        // mMockStatusBarNotification with a test channel.
        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        when(mMockStatusBarNotification.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mMockPackageManager.getText(eq(TEST_PACKAGE_NAME),
                eq(R.string.notification_menu_accessibility), anyObject())).thenReturn(
                        getContext().getString(R.string.notification_menu_accessibility));

        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), anyInt(), anyBoolean())).thenReturn(1);
    }

    private CharSequence getStringById(int resId) {
        return mContext.getString(resId);
    }

    private CharSequence getNumChannelsString(int numChannels) {
        return String.format(
                mContext.getResources().getQuantityString(
                        R.plurals.notification_num_channels_desc, numChannels),
                numChannels);
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsTextApplicationName() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.pkgname);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsPackageIcon() throws Exception {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final ImageView iconView = (ImageView) mNotificationInfo.findViewById(R.id.pkgicon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_GroupNameHiddenIfNoGroup() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView groupNameView = (TextView) mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.GONE, groupNameView.getVisibility());
        final TextView groupDividerView =
                (TextView) mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.GONE, groupDividerView.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsGroupNameIfNonNull() throws Exception {
        mNotificationChannel.setGroup("test_group_id");
        final NotificationChannelGroup notificationChannelGroup =
                new NotificationChannelGroup("test_group_id", "Test Group Name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
                eq("test_group_id"), eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(notificationChannelGroup);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView groupNameView = (TextView) mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.VISIBLE, groupNameView.getVisibility());
        assertEquals("Test Group Name", groupNameView.getText());
        final TextView groupDividerView =
                (TextView) mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.VISIBLE, groupDividerView.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsTextChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(TEST_CHANNEL_NAME, textView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsTextChannelName_resId() throws Exception {
        NotificationChannel notificationChannelResId = new NotificationChannel(
                TEST_CHANNEL, R.string.notification_menu_accessibility,
                NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, notificationChannelResId, null, null, null);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(getContext().getString(R.string.notification_menu_accessibility),
                textView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel,
                (View v, int appUid) -> { latch.countDown(); }, null, null);

        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SettingsTextWithOneChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, (View v, int appUid) -> {}, null,
                null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(getStringById(R.string.notification_more_settings), settingsButton.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SettingsTextWithMultipleChannels() throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), anyInt(), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, (View v, int appUid) -> {}, null,
                null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(getStringById(R.string.notification_all_categories), settingsButton.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_SetsOnClickListenerForDone() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null,
                (View v) -> { latch.countDown(); },
                null);

        final TextView doneButton = (TextView) mNotificationInfo.findViewById(R.id.done);
        doneButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextHiddenWhenDefaultChannel() throws Exception {
        final NotificationChannel defaultChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, defaultChannel, null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertTrue(numChannelsView.getVisibility() != View.VISIBLE);
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextDisplaysWhenNotDefaultChannel()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(numChannelsView.getVisibility(), View.VISIBLE);
        assertEquals(getNumChannelsString(1), numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextScalesWithNumberOfChannels()
            throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), anyInt(), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(getNumChannelsString(2), numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testbindNotification_ChannelDisabledTextGoneWhenNotDisabled() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView channelDisabledView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_disabled);
        assertEquals(channelDisabledView.getVisibility(), View.GONE);
    }

    @Test
    @UiThreadTest
    public void testbindNotification_ChannelDisabledTextVisibleWhenDisabled() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_NONE);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        final TextView channelDisabledView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_disabled);
        assertEquals(channelDisabledView.getVisibility(), View.VISIBLE);
        // Replaces the numChannelsView
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(numChannelsView.getVisibility(), View.GONE);
    }

    @Test
    @UiThreadTest
    public void testHasImportanceChanged_DefaultsToFalse() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        assertFalse(mNotificationInfo.hasImportanceChanged());
    }

    @Test
    @UiThreadTest
    public void testHasImportanceChanged_ReturnsTrueAfterChannelDisabled() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        // Find the high button and check it.
        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        assertTrue(mNotificationInfo.hasImportanceChanged());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_DoesNotUpdateNotificationChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    @UiThreadTest
    public void testDoesNotUpdateNotificationChannelAfterImportanceChanged() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    @UiThreadTest
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnchanged()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        mNotificationInfo.handleCloseControls(true);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    @UiThreadTest
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        mNotificationInfo.handleCloseControls(true);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    @UiThreadTest
    public void testEnabledSwitchOnByDefault() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertTrue(enabledSwitch.isChecked());
    }

    @Test
    @UiThreadTest
    public void testEnabledButtonOffWhenAlreadyBanned() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_NONE);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertFalse(enabledSwitch.isChecked());
    }

    @Test
    @UiThreadTest
    public void testEnabledSwitchVisibleByDefault() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.VISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testEnabledSwitchInvisibleIfNonBlockable() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.INVISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testEnabledSwitchChangedCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(true);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), anyInt(), eq(mNotificationChannel));
    }

    @Test
    @UiThreadTest
    public void testCloseControlsDoesNotUpdateIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                mMockStatusBarNotification, mNotificationChannel, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), anyInt(), eq(mNotificationChannel));
    }
}
