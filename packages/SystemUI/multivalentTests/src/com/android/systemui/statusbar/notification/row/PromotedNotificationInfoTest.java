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

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class PromotedNotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private TestableLooper mTestableLooper;
    private PromotedNotificationInfo mInfo;
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
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero

        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        Notification notification = new Notification();
        notification.extras.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, applicationInfo);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                notification, UserHandle.getUserHandleForUid(TEST_UID), null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).build();
        when(mAssistantFeedbackController.isFeedbackEnabled()).thenReturn(false);

        mTestableLooper = TestableLooper.get(this);

        mContext.addMockSystemService(TelecomManager.class, mTelecomManager);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mInfo = (PromotedNotificationInfo) layoutInflater.inflate(
                R.layout.promoted_notification_info, null);
        mInfo.setGutsParent(mock(NotificationGuts.class));
        // Our view is never attached to a window so the View#post methods in
        // BundleNotificationInfo never get called. Setting this will skip the post and do the
        // action immediately.
        mInfo.mSkipPost = true;
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testBindNotification_setsOnClickListenerForFeedback() throws Exception {

        // Bind the notification to the Info object
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
                null,
                null,
                mUiEventLogger,
                true,
                false,
                true,
                mAssistantFeedbackController,
                mMetricsLogger,
                null);
        // Click demote button
        final View demoteButton = mInfo.findViewById(R.id.promoted_demote);
        demoteButton.performClick();
        // verify that notiManager tried to demote
        verify(mMockINotificationManager, atLeastOnce()).setCanBePromoted(TEST_PACKAGE_NAME,
                mSbn.getUid(), false, true);

    }
}
