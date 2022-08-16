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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
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
import android.app.PendingIntent;
import android.app.Person;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.UiThreadTest;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final int MULTIPLE_CHANNEL_COUNT = 2;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private TestableLooper mTestableLooper;
    private NotificationInfo mNotificationInfo;
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mDefaultNotificationChannel;
    private Set<NotificationChannel> mNotificationChannelSet = new HashSet<>();
    private Set<NotificationChannel> mDefaultNotificationChannelSet = new HashSet<>();
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;
    private UiEventLoggerFake mUiEventLogger = new UiEventLoggerFake();

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private INotificationManager mMockINotificationManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private OnUserInteractionCallback mOnUserInteractionCallback;
    @Mock
    private ChannelEditorDialogController mChannelEditorDialogController;
    @Mock
    private AssistantFeedbackController mAssistantFeedbackController;
    @Mock
    private TelecomManager mTelecomManager;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);

        mContext.addMockSystemService(TelecomManager.class, mTelecomManager);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationInfo) layoutInflater.inflate(R.layout.notification_info,
                null);
        mNotificationInfo.setGutsParent(mock(NotificationGuts.class));
        // Our view is never attached to a window so the View#post methods in NotificationInfo never
        // get called. Setting this will skip the post and do the action immediately.
        mNotificationInfo.mSkipPost = true;

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

        // Some test channels.
        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        mNotificationChannelSet.add(mNotificationChannel);
        mDefaultNotificationChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                IMPORTANCE_LOW);
        mDefaultNotificationChannelSet.add(mDefaultNotificationChannel);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.getUserHandleForUid(TEST_UID), null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).build();
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(false);
        when(mAssistantFeedbackController.getInlineDescriptionResource(any()))
                .thenReturn(R.string.notification_channel_summary_automatic);
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testBindNotification_SetsPackageIcon() throws Exception {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final ImageView iconView = mNotificationInfo.findViewById(R.id.pkg_icon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_noDelegate() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
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
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                entry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(VISIBLE, nameView.getVisibility());
        assertTrue(nameView.getText().toString().contains("Proxied"));
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(GONE, groupNameView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsGroupNameIfNonNull() throws Exception {
        mNotificationChannel.setGroup("test_group_id");
        final NotificationChannelGroup notificationChannelGroup =
                new NotificationChannelGroup("test_group_id", "Test Group Name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
                eq("test_group_id"), eq(TEST_PACKAGE_NAME), eq(TEST_UID)))
                .thenReturn(notificationChannelGroup);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.VISIBLE, groupNameView.getVisibility());
        assertEquals("Test Group Name", groupNameView.getText());
    }

    @Test
    public void testBindNotification_SetsTextChannelName() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(TEST_CHANNEL_NAME, textView.getText());
    }

    @Test
    public void testBindNotification_DefaultChannelDoesNotUseChannelName() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mDefaultNotificationChannel,
                mDefaultNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_DefaultChannelUsesChannelNameIfMoreChannelsExist()
            throws Exception {
        // Package has more than one channel by default.
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(10);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mDefaultNotificationChannel,
                mDefaultNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_UnblockablePackageUsesChannelName() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                true,
                true,
                mAssistantFeedbackController);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned()
            throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                },
                null,
                mUiEventLogger,
                false,
                false,
                true,
                mAssistantFeedbackController);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonReappearsAfterSecondBind() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> { },
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertEquals(View.VISIBLE, settingsButton.getVisibility());
    }

    @Test
    public void testOnClickListenerPassesNullChannelForBundle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME, mNotificationChannel,
                createMultipleChannelSet(MULTIPLE_CHANNEL_COUNT),
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(null, c);
                    latch.countDown();
                },
                null,
                mUiEventLogger,
                true,
                true,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.info).performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_ChannelNameInvisibleWhenBundleFromDifferentChannels()
            throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                createMultipleChannelSet(MULTIPLE_CHANNEL_COUNT),
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView channelNameView =
                mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, channelNameView.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testStopInvisibleIfBundleFromDifferentChannels() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                createMultipleChannelSet(MULTIPLE_CHANNEL_COUNT),
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertEquals(GONE, mNotificationInfo.findViewById(
                R.id.interruptiveness_settings).getVisibility());
        assertEquals(VISIBLE, mNotificationInfo.findViewById(
                R.id.non_configurable_multichannel_text).getVisibility());
    }

    @Test
    public void testBindNotification_whenAppUnblockable() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                true,
                true,
                mAssistantFeedbackController);
        final TextView view = mNotificationInfo.findViewById(R.id.non_configurable_text);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.notification_unblockable_desc),
                view.getText());
        assertEquals(GONE,
                mNotificationInfo.findViewById(R.id.interruptiveness_settings).getVisibility());
    }

    @Test
    public void testBindNotification_whenCurrentlyInCall() throws Exception {
        when(mMockINotificationManager.isInCall(anyString(), anyInt())).thenReturn(true);

        Person person = new Person.Builder()
                .setName("caller")
                .build();
        Notification.Builder nb = new Notification.Builder(
                mContext, mNotificationChannel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setStyle(Notification.CallStyle.forOngoingCall(
                        person, mock(PendingIntent.class)))
                .setFullScreenIntent(mock(PendingIntent.class), true)
                .addAction(new Notification.Action.Builder(null, "test", null).build());

        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                nb.build(), UserHandle.getUserHandleForUid(TEST_UID), null, 0);
        mEntry.setSbn(mSbn);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        final TextView view = mNotificationInfo.findViewById(R.id.non_configurable_call_text);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.notification_unblockable_call_desc),
                view.getText());
        assertEquals(GONE,
                mNotificationInfo.findViewById(R.id.interruptiveness_settings).getVisibility());
        assertEquals(GONE,
                mNotificationInfo.findViewById(R.id.non_configurable_text).getVisibility());
    }

    @Test
    public void testBindNotification_whenCurrentlyInCall_notCall() throws Exception {
        when(mMockINotificationManager.isInCall(anyString(), anyInt())).thenReturn(true);

        Person person = new Person.Builder()
                .setName("caller")
                .build();
        Notification.Builder nb = new Notification.Builder(
                mContext, mNotificationChannel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setFullScreenIntent(mock(PendingIntent.class), true)
                .addAction(new Notification.Action.Builder(null, "test", null).build());

        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                nb.build(), UserHandle.getUserHandleForUid(TEST_UID), null, 0);
        mEntry.setSbn(mSbn);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertEquals(GONE,
                mNotificationInfo.findViewById(R.id.non_configurable_call_text).getVisibility());
        assertEquals(VISIBLE,
                mNotificationInfo.findViewById(R.id.interruptiveness_settings).getVisibility());
        assertEquals(GONE,
                mNotificationInfo.findViewById(R.id.non_configurable_text).getVisibility());
    }

    @Test
    public void testBindNotification_automaticIsVisible() throws Exception {
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(true);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.automatic).getVisibility());
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.automatic_summary).getVisibility());
    }

    @Test
    public void testBindNotification_automaticIsGone() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertEquals(GONE, mNotificationInfo.findViewById(R.id.automatic).getVisibility());
        assertEquals(GONE, mNotificationInfo.findViewById(R.id.automatic_summary).getVisibility());
    }

    @Test
    public void testBindNotification_automaticIsSelected() throws Exception {
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(true);
        mNotificationChannel.unlockFields(USER_LOCKED_IMPORTANCE);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertTrue(mNotificationInfo.findViewById(R.id.automatic).isSelected());
    }

    @Test
    public void testBindNotification_alertIsSelected() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertTrue(mNotificationInfo.findViewById(R.id.alert).isSelected());
    }

    @Test
    public void testBindNotification_silenceIsSelected() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);
        assertTrue(mNotificationInfo.findViewById(R.id.silence).isSelected());
    }

    @Test
    public void testBindNotification_DoesNotUpdateNotificationChannel() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testBindNotification_LogsOpen() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN.getId(),
                mUiEventLogger.eventId(0));
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChanged() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChangedSilenced()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.silence).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChangedAutomatic()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.automatic).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_persistAutomatic()
            throws Exception {
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(true);
        mNotificationChannel.unlockFields(USER_LOCKED_IMPORTANCE);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.handleCloseControls(true, false);
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).unlockNotificationChannel(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnchanged()
            throws Exception {
        int originalImportance = mNotificationChannel.getImportance();
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.handleCloseControls(true, false);
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
        assertEquals(originalImportance, mNotificationChannel.getImportance());

        assertEquals(2, mUiEventLogger.numLogs());
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN.getId(),
                mUiEventLogger.eventId(0));
        // The SAVE_IMPORTANCE event is logged whenever importance is saved, even if unchanged.
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_SAVE_IMPORTANCE.getId(),
                mUiEventLogger.eventId(1));
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
        assertEquals(IMPORTANCE_UNSPECIFIED, mNotificationChannel.getImportance());
    }

    @Test
    public void testSilenceCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.silence).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_LOW, updated.getValue().getImportance());

        assertEquals(2, mUiEventLogger.numLogs());
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN.getId(),
                mUiEventLogger.eventId(0));
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_SAVE_IMPORTANCE.getId(),
                mUiEventLogger.eventId(1));
    }

    @Test
    public void testUnSilenceCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_DEFAULT, updated.getValue().getImportance());
    }

    @Test
    public void testAutomaticUnlocksUserImportance() throws Exception {
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(true);
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationChannel.lockFields(USER_LOCKED_IMPORTANCE);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.automatic).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).unlockNotificationChannel(
                anyString(), eq(TEST_UID), any());
        assertEquals(IMPORTANCE_DEFAULT, mNotificationChannel.getImportance());
    }

    @Test
    public void testSilenceCallsUpdateNotificationChannel_channelImportanceUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.silence).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_LOW, updated.getValue().getImportance());
    }

    @Test
    public void testSilenceCallsUpdateNotificationChannel_channelImportanceMin()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_MIN);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        assertEquals(mContext.getString(R.string.inline_done_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.silence).performClick();
        assertEquals(mContext.getString(R.string.inline_done_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_MIN, updated.getValue().getImportance());
    }

    @Test
    public void testAlertCallsUpdateNotificationChannel_channelImportanceMin()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_MIN);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        assertEquals(mContext.getString(R.string.inline_done_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.alert).performClick();
        assertEquals(mContext.getString(R.string.inline_ok_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_DEFAULT, updated.getValue().getImportance());
    }

    @Test
    public void testAdjustImportanceTemporarilyAllowsReordering() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.silence).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        verify(mOnUserInteractionCallback).onImportanceChanged(mEntry);
    }

    @Test
    public void testDoneText()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        assertEquals(mContext.getString(R.string.inline_done_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.alert).performClick();
        assertEquals(mContext.getString(R.string.inline_ok_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
        mNotificationInfo.findViewById(R.id.silence).performClick();
        assertEquals(mContext.getString(R.string.inline_done_button),
                ((TextView) mNotificationInfo.findViewById(R.id.done)).getText());
    }

    @Test
    public void testUnSilenceCallsUpdateNotificationChannel_channelImportanceUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_DEFAULT, updated.getValue().getImportance());
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mNotificationInfo.handleCloseControls(false, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));

        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN.getId(),
                mUiEventLogger.eventId(0));
    }

    @Test
    public void testCloseControlsUpdatesWhenCheckSaveListenerUsesCallback() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));

        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControls_withoutHittingApply() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        mNotificationInfo.findViewById(R.id.alert).performClick();

        assertFalse(mNotificationInfo.shouldBeSaved());
    }

    @Test
    public void testWillBeRemovedReturnsFalse() throws Exception {
        assertFalse(mNotificationInfo.willBeRemoved());

        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mNotificationChannelSet,
                mEntry,
                null,
                null,
                mUiEventLogger,
                true,
                false,
                false,
                mAssistantFeedbackController);

        assertFalse(mNotificationInfo.willBeRemoved());
    }

    private Set<NotificationChannel> createMultipleChannelSet(int howMany) {
        Set<NotificationChannel> multiChannelSet = new HashSet<>();
        for (int i = 0; i < howMany; i++) {
            if (i == 0) {
                multiChannelSet.add(mNotificationChannel);
                continue;
            }

            NotificationChannel channel = new NotificationChannel(
                    TEST_CHANNEL, TEST_CHANNEL_NAME + i, IMPORTANCE_LOW);

            multiChannelSet.add(channel);
        }

        return multiChannelSet;
    }
}
