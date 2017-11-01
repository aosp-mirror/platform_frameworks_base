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

import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class NotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private NotificationInfo mNotificationInfo;
    private final INotificationManager mMockINotificationManager = mock(INotificationManager.class);
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mDefaultNotificationChannel;
    private StatusBarNotification mSbn;

    @Before
    public void setUp() throws Exception {
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationInfo) layoutInflater.inflate(R.layout.notification_info,
                null);

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
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

        // Some test channels.
        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        mDefaultNotificationChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, 0, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
    }

    private CharSequence getStringById(int resId) {
        return mContext.getString(resId);
    }

    private CharSequence getNumChannelsDescString(int numChannels) {
        return String.format(
                mContext.getResources().getQuantityString(
                        R.plurals.notification_num_channels_desc, numChannels),
                numChannels);
    }

    private CharSequence getChannelsListDescString(NotificationChannel... channels) {
        if (channels.length == 2) {
            return mContext.getString(R.string.notification_channels_list_desc_2,
                    channels[0].getName(), channels[1].getName());
        } else {
            final int numOthers = channels.length - 2;
            return String.format(
                    mContext.getResources().getQuantityString(
                            R.plurals.notification_channels_list_desc_2_and_others, numOthers),
                    channels[0].getName(), channels[1].getName(), numOthers);
        }
    }

    private CharSequence getNumChannelsString(int numChannels) {
        return mContext.getString(R.string.notification_num_channels, numChannels);
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.pkgname);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    public void testBindNotification_SetsPackageIcon() throws Exception {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final ImageView iconView = (ImageView) mNotificationInfo.findViewById(R.id.pkgicon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView groupNameView = (TextView) mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.GONE, groupNameView.getVisibility());
        final TextView groupDividerView =
                (TextView) mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.GONE, groupDividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsGroupNameIfNonNull() throws Exception {
        mNotificationChannel.setGroup("test_group_id");
        final NotificationChannelGroup notificationChannelGroup =
                new NotificationChannelGroup("test_group_id", "Test Group Name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
                eq("test_group_id"), eq(TEST_PACKAGE_NAME), eq(TEST_UID)))
                .thenReturn(notificationChannelGroup);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView groupNameView = (TextView) mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.VISIBLE, groupNameView.getVisibility());
        assertEquals("Test Group Name", groupNameView.getText());
        final TextView groupDividerView =
                (TextView) mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.VISIBLE, groupDividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(TEST_CHANNEL_NAME, textView.getText());
    }

    @Test
    public void testBindNotification_DefaultChannelDoesNotUseChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(mContext.getString(R.string.notification_header_default_channel),
                textView.getText());
    }

    @Test
    public void testBindNotification_UnblockablePackageDoesNotUseChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(mContext.getString(R.string.notification_header_default_channel),
                textView.getText());
    }

    @Test
    public void testBindNotification_DefaultChannelUsesNameWhenMoreThanOneChannelExists()
            throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView textView = (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(mDefaultNotificationChannel.getName(), textView.getText());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                }, null, null, null, null);

        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonReappersAfterSecondBind() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {}, null, null, null, null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(View.VISIBLE, settingsButton.getVisibility());
    }

    @Test
    public void testOnClickListenerPassesNullChannelForBundle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME,
                Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(null, c);
                    latch.countDown();
                }, null, null, null, null);

        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsTextWithOneChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {
                }, null, null, null, null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(getStringById(R.string.notification_more_settings), settingsButton.getText());
    }

    @Test
    public void testBindNotification_SettingsTextWithMultipleChannels() throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {
                }, null, null, null, null);
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(getStringById(R.string.notification_all_categories), settingsButton.getText());
    }

    @Test
    public void testBindNotification_SettingsTextWithMultipleChannelsForUnblockableApp()
            throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn,
                (View v, NotificationChannel c, int appUid) -> {
                }, null, null, null, Collections.singleton(TEST_PACKAGE_NAME));
        final TextView settingsButton =
                (TextView) mNotificationInfo.findViewById(R.id.more_settings);
        assertEquals(getStringById(R.string.notification_more_settings), settingsButton.getText());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForDone() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null,
                null, (View v) -> {
                    latch.countDown();
                },
                null, null);

        final TextView doneButton = (TextView) mNotificationInfo.findViewById(R.id.done);
        doneButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_NumChannelsTextHiddenWhenDefaultChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null,
                null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(View.INVISIBLE, numChannelsView.getVisibility());
    }

    @Test
    public void testBindNotification_NumChannelsTextDisplaysWhenMoreThanOneChannelExists()
            throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null,
                null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(numChannelsView.getVisibility(), View.VISIBLE);
        assertEquals(getNumChannelsDescString(2), numChannelsView.getText());
    }

    @Test
    public void testBindNotification_NumChannelsTextDisplaysWhenNotDefaultChannel()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(numChannelsView.getVisibility(), View.VISIBLE);
        assertEquals(getNumChannelsDescString(1), numChannelsView.getText());
    }

    @Test
    public void testBindNotification_NumChannelsTextScalesWithNumberOfChannels()
            throws Exception {
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(2);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(getNumChannelsDescString(2), numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextListsChannelsWhenTwoInBundle()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(getChannelsListDescString(mNotificationChannel, mDefaultNotificationChannel),
                numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextListsChannelsWhenThreeInBundle()
            throws Exception {
        NotificationChannel thirdChannel = new NotificationChannel(
                "third_channel", "third_channel", NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME,
                Arrays.asList(mNotificationChannel, mDefaultNotificationChannel, thirdChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(
                getChannelsListDescString(mNotificationChannel, mDefaultNotificationChannel,
                        thirdChannel),
                numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_NumChannelsTextListsChannelsWhenFourInBundle()
            throws Exception {
        NotificationChannel thirdChannel = new NotificationChannel(
                "third_channel", "third_channel", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel fourthChannel = new NotificationChannel(
                "fourth_channel", "fourth_channel", NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME,
                Arrays.asList(mNotificationChannel, mDefaultNotificationChannel, thirdChannel,
                        fourthChannel), mNotificationChannel.getImportance(), mSbn, null, null,
                null, null, null);
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(
                getChannelsListDescString(mNotificationChannel, mDefaultNotificationChannel,
                        thirdChannel, fourthChannel),
                numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_ChannelNameChangesWhenBundleFromDifferentChannels()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        final TextView channelNameView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(getNumChannelsString(2), channelNameView.getText());
    }

    @Test
    @UiThreadTest
    public void testEnabledSwitchInvisibleIfBundleFromDifferentChannels() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.INVISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testbindNotification_ChannelDisabledTextGoneWhenNotDisabled() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null, null, null);
        final TextView channelDisabledView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_disabled);
        assertEquals(channelDisabledView.getVisibility(), View.GONE);
    }

    @Test
    public void testbindNotification_ChannelDisabledTextVisibleWhenDisabled() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_NONE);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        final TextView channelDisabledView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_disabled);
        assertEquals(channelDisabledView.getVisibility(), View.VISIBLE);
        // Replaces the numChannelsView
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(numChannelsView.getVisibility(), View.GONE);
    }

    @Test
    public void testbindNotification_UnblockableTextVisibleWhenAppUnblockable() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));
        final TextView numChannelsView =
                (TextView) mNotificationInfo.findViewById(R.id.num_channels_desc);
        assertEquals(View.VISIBLE, numChannelsView.getVisibility());
        assertEquals(mContext.getString(R.string.notification_unblockable_desc),
                numChannelsView.getText());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_ChannelDisabledTextShowsForDefaultChannel()
            throws Exception {
        mDefaultNotificationChannel.setImportance(NotificationManager.IMPORTANCE_NONE);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mDefaultNotificationChannel),
                mDefaultNotificationChannel.getImportance(), mSbn, null, null,
                null, null, null);
        final TextView channelDisabledView =
                (TextView) mNotificationInfo.findViewById(R.id.channel_disabled);
        assertEquals(View.VISIBLE, channelDisabledView.getVisibility());
    }

    @Test
    public void testBindNotification_DoesNotUpdateNotificationChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChanged() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnchanged()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testEnabledSwitchOnByDefault() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertTrue(enabledSwitch.isChecked());
    }

    @Test
    public void testEnabledButtonOffWhenAlreadyBanned() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_NONE);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertFalse(enabledSwitch.isChecked());
    }

    @Test
    public void testEnabledSwitchVisibleByDefault() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.VISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testEnabledSwitchInvisibleIfNonBlockable() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.INVISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testEnabledSwitchInvisibleIfNonBlockableSystemChannel() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationChannel.setBlockableSystem(false);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_SYSTEM_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.INVISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testEnabledSwitchVisibleIfBlockableSystemChannel() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationChannel.setBlockableSystem(true);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_SYSTEM_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, null);

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.VISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testEnabledSwitchInvisibleIfMultiChannelSummary() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationChannel.setBlockableSystem(true);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        assertEquals(View.INVISIBLE, enabledSwitch.getVisibility());
    }

    @Test
    public void testNonBlockableAppDoesNotBecomeBlocked() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));
        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testEnabledSwitchChangedCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(true, false);

        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_IMPORTANCE) != 0);
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(false, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfCheckSaveListenerIsNoOp() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                (Runnable saveImportance) -> {
                },
                Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsUpdatesWhenCheckSaveListenerUsesCallback() throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), mSbn, null, null, null,
                (Runnable saveImportance) -> {
                    saveImportance.run();
                },
                Collections.singleton(TEST_PACKAGE_NAME));

        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);
        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testDisplaySettingsLink() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String settingsText = "work chats";
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), sbn, null,
                (View v, Intent intent) -> {
                    latch.countDown();
                }, null, null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());
        assertTrue(settingsLink.getText().toString().length() > settingsText.length());
        assertTrue(settingsLink.getText().toString().contains(settingsText));
        settingsLink.performClick();
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testDisplaySettingsLink_multipleChannels() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String settingsText = "work chats";
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel, mDefaultNotificationChannel),
                mNotificationChannel.getImportance(), sbn, null, (View v, Intent intent) -> {
                    latch.countDown();
                }, null, null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());
        settingsLink.performClick();
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testNoSettingsLink_noHandlingActivity() throws Exception {
        final String settingsText = "work chats";
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(null);
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), sbn, null, null, null,
                null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.GONE, settingsLink.getVisibility());
    }

    @Test
    public void testNoSettingsLink_noLinkText() throws Exception {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId()).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), sbn, null, null, null,
                null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.GONE, settingsLink.getVisibility());
    }

    @Test
    public void testNoSettingsLink_afterBlockingChannel() throws Exception {
        final String settingsText = "work chats";
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, Arrays.asList(mNotificationChannel),
                mNotificationChannel.getImportance(), sbn, null, null, null,
                null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());

        // Block channel
        Switch enabledSwitch = (Switch) mNotificationInfo.findViewById(R.id.channel_enabled_switch);
        enabledSwitch.setChecked(false);

        assertEquals(View.GONE, settingsLink.getVisibility());

        //unblock
        enabledSwitch.setChecked(true);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());
    }

    @Test
    public void testWillBeRemovedReturnsFalseBeforeBind() throws Exception {
        assertFalse(mNotificationInfo.willBeRemoved());
    }
}
