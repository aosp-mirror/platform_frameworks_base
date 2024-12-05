/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static android.app.Notification.EXTRA_BUILDER_APPLICATION_INFO;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.service.notification.NotificationAssistantService.ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class BundleNotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private TestableLooper mTestableLooper;
    private BundleNotificationInfo mInfo;
    private NotificationChannel mNotificationChannel;
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
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mInfo = (BundleNotificationInfo) layoutInflater.inflate(R.layout.bundle_notification_info,
                null);
        mInfo.setGutsParent(mock(NotificationGuts.class));
        // Our view is never attached to a window so the View#post methods in
        // BundleNotificationInfo never get called. Setting this will skip the post and do the
        // action immediately.
        mInfo.mSkipPost = true;

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
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
        Notification notification = new Notification();
        notification.extras.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, applicationInfo);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                notification, UserHandle.getUserHandleForUid(TEST_UID), null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).build();
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(false);
        when(mAssistantFeedbackController.getInlineDescriptionResource(any()))
                .thenReturn(R.string.notification_channel_summary_automatic);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testBindNotification_setsOnClickListenerForFeedback() throws Exception {
        // When Notification Assistant is available,
        when(mMockINotificationManager.getAllowedNotificationAssistant()).thenReturn(
                new ComponentName("assistantPkg", "assistantCls"));

        // ...and Package manager has an intent that matches.
        ArrayList<ResolveInfo> resolveInfos = new ArrayList<>();
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "assistantPkg";
        info.activityInfo.name = "assistantCls";
        resolveInfos.add(info);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(resolveInfos);

        // And we attempt to bind the notification to the Info object
        final CountDownLatch latch = new CountDownLatch(1);
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                (View v, Intent intent) -> {
                    // Assert that the intent action and package match.
                    assertEquals(intent.getAction(),
                            ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS);
                    assertEquals(intent.getPackage(), "assistantPkg");
                    latch.countDown();
                },
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController,
                mMetricsLogger);
        // and the feedback button is clicked,
        final View feedbackButton = mInfo.findViewById(R.id.notification_guts_bundle_feedback);
        feedbackButton.performClick();

        // then of the intents queried for is the feedback intent,
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockPackageManager, atLeastOnce()).queryIntentActivities(captor.capture(),
                anyInt());
        List<Intent> capturedIntents = captor.getAllValues();
        Intent feedbackIntent = null;
        for (int i = 0; i < capturedIntents.size(); i++) {
            final Intent capturedIntent = capturedIntents.get(i);
            if (capturedIntent.getAction() == ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS
                    && capturedIntent.getPackage().equals("assistantPkg")) {
                feedbackIntent = capturedIntent;
            }
        }
        assertNotNull("feedbackIntent should be not null", feedbackIntent);
        assertEquals(mSbn.getKey(),
                feedbackIntent.getExtra(NotificationAssistantService.EXTRA_NOTIFICATION_KEY));

        // and verify that listener was triggered.
        assertEquals(0, latch.getCount());
        assertEquals(View.VISIBLE, feedbackButton.getVisibility());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testBindNotification_hidesFeedbackButtonWhenNoNAS() throws Exception {
        // When the Notification Assistant is not available
        when(mMockINotificationManager.getAllowedNotificationAssistant()).thenReturn(null);
        final CountDownLatch latch = new CountDownLatch(1);

        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                (View v, Intent intent) -> {
                    // Assert that the intent action and package match.
                    assertEquals(intent.getAction(),
                            ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS);
                    assertEquals(intent.getPackage(), "assistantPkg");
                    latch.countDown();
                },
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController,
                mMetricsLogger);

        final View feedbackButton = mInfo.findViewById(R.id.notification_guts_bundle_feedback);
        feedbackButton.performClick();
        // Listener was not triggered
        assertEquals(1, latch.getCount());
        assertEquals(View.GONE, feedbackButton.getVisibility());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testBindNotification_hidesFeedbackButtonWhenNoIntent() throws Exception {
        // When the Notification Assistant is available,
        when(mMockINotificationManager.getAllowedNotificationAssistant()).thenReturn(
                new ComponentName("assistantPkg", "assistantCls"));

        // But the intent activity is null
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(null);

        final CountDownLatch latch = new CountDownLatch(1);
        mInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                (View v, Intent intent) -> {
                    // Assert that the intent action and package match.
                    assertEquals(intent.getAction(),
                            ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS);
                    assertEquals(intent.getPackage(), "assistantPkg");
                    latch.countDown();
                },
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController,
                mMetricsLogger);

        final View feedbackButton = mInfo.findViewById(R.id.notification_guts_bundle_feedback);
        feedbackButton.performClick();
        // Listener was not triggered
        assertEquals(1, latch.getCount());
        assertEquals(View.GONE, feedbackButton.getVisibility());
    }
}
