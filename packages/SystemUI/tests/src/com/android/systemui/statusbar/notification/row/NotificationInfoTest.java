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
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.PollingCheck;
import android.testing.TestableLooper;
import android.testing.UiThreadTest;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

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
    private StatusBarNotification mSbn;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private INotificationManager mMockINotificationManager;
    @Mock private PackageManager mMockPackageManager;
    @Mock private NotificationBlockingHelperManager mBlockingHelperManager;

    @Before
    public void setUp() throws Exception {
        mDependency.injectTestDependency(
                NotificationBlockingHelperManager.class,
                mBlockingHelperManager);
        mTestableLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationInfo) layoutInflater.inflate(R.layout.notification_info,
                null);
        mNotificationInfo.setGutsParent(mock(NotificationGuts.class));

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
        mDefaultNotificationChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                IMPORTANCE_LOW);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);

        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);
    }

    @After
    public void tearDown() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 0);
    }

    // TODO: if tests are taking too long replace this with something that makes the animation
    // finish instantly.
    private void waitForUndoButton() {
        PollingCheck.waitFor(1000,
                () -> VISIBLE == mNotificationInfo.findViewById(R.id.confirmation).getVisibility());
    }
    private void ensureNoUndoButton() {
        PollingCheck.waitFor(1000,
                () -> GONE == mNotificationInfo.findViewById(R.id.confirmation).getVisibility()
                        && !mNotificationInfo.isAnimating());
    }
    private void waitForStopButton() {
        PollingCheck.waitFor(1000,
                () -> VISIBLE == mNotificationInfo.findViewById(R.id.prompt).getVisibility());
    }

    class ImportanceChangeLogMaker implements ArgumentMatcher<LogMaker> {
        private static final int CATEGORY = MetricsProto.MetricsEvent.ACTION_SAVE_IMPORTANCE;
        private int mType, mSubtype;

        ImportanceChangeLogMaker(int type, int subtype) {
            mType = type;
            mSubtype = subtype;
        }
        public boolean matches(LogMaker l) {
            return (l.getCategory() == CATEGORY)
                    && (l.getType() == mType)
                    && (l.getSubtype() == mSubtype);
        }

        public String toString() {
            return String.format("LogMaker(%d, %d, %d)", CATEGORY, mType, mSubtype);
        }
    }

    private LogMaker importanceChangeLog(int type, int subtype) {
        return argThat(new ImportanceChangeLogMaker(type, subtype));
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkgname);
        assertTrue(textView.getText().toString().contains("App Name"));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testBindNotification_SetsPackageIcon() throws Exception {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final ImageView iconView = mNotificationInfo.findViewById(R.id.pkgicon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_noDelegate() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(GONE, nameView.getVisibility());
        final TextView dividerView = mNotificationInfo.findViewById(R.id.pkg_divider);
        assertEquals(GONE, dividerView.getVisibility());
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

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(VISIBLE, nameView.getVisibility());
        assertTrue(nameView.getText().toString().contains("Other"));
        final TextView dividerView = mNotificationInfo.findViewById(R.id.pkg_divider);
        assertEquals(VISIBLE, dividerView.getVisibility());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(GONE, groupNameView.getVisibility());
        final TextView groupDividerView = mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(GONE, groupDividerView.getVisibility());
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
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.VISIBLE, groupNameView.getVisibility());
        assertEquals("Test Group Name", groupNameView.getText());
        final TextView groupDividerView = mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.VISIBLE, groupDividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(TEST_CHANNEL_NAME, textView.getText());
    }

    @Test
    public void testBindNotification_DefaultChannelDoesNotUseChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mDefaultNotificationChannel, 1, mSbn, null, null, null, true,
                false, IMPORTANCE_DEFAULT);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_DefaultChannelUsesChannelNameIfMoreChannelsExist()
            throws Exception {
        // Package has one channel by default.
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(10);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mDefaultNotificationChannel, 1, mSbn, null, null, null, true,
                false, IMPORTANCE_DEFAULT);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_UnblockablePackageUsesChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_BlockButton() throws Exception {
       mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
               TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
               IMPORTANCE_DEFAULT);
        final View block = mNotificationInfo.findViewById(R.id.int_block);
        final View minimize = mNotificationInfo.findViewById(R.id.block_or_minimize);
        assertEquals(VISIBLE, block.getVisibility());
        assertEquals(GONE, minimize.getVisibility());
    }

    @Test
    public void testBindNotification_BlockButton_BlockHelper() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                true /* isBlockingHelper */, false, IMPORTANCE_DEFAULT);
        final View block = mNotificationInfo.findViewById(R.id.block);
        final View interruptivenessSettings = mNotificationInfo.findViewById(
                R.id.interruptiveness_settings);
        assertEquals(VISIBLE, block.getVisibility());
        assertEquals(GONE, interruptivenessSettings.getVisibility());
    }

    @Test
    public void testBindNotification_SilenceButton_CurrentlyAlerting() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView silent = mNotificationInfo.findViewById(R.id.int_silent);
        assertEquals(VISIBLE, silent.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_silent), silent.getText());
    }

    @Test
    public void testBindNotification_SilenceButton_CurrentlySilent() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_LOW);
        final TextView silent = mNotificationInfo.findViewById(R.id.int_silent);
        assertEquals(VISIBLE, silent.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_stay_silent),
                silent.getText());
    }

    @Test
    public void testBindNotification_AlertButton_CurrentlySilent() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_LOW);
        final TextView alert = mNotificationInfo.findViewById(R.id.int_alert);
        assertEquals(VISIBLE, alert.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_alert), alert.getText());
    }

    @Test
    public void testBindNotification_UnSilenceButton_currentlyAlerting() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView alert = mNotificationInfo.findViewById(R.id.int_alert);
        assertEquals(VISIBLE, alert.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_keep_alerting), alert.getText());
    }

    @Test
    public void testBindNotification_ChannelImportanceUnspecified_NotifAlerting() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final TextView silent = mNotificationInfo.findViewById(R.id.int_silent);
        final TextView alert = mNotificationInfo.findViewById(R.id.int_alert);
        assertEquals(VISIBLE, silent.getVisibility());
        assertEquals(VISIBLE, alert.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_silent), silent.getText());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_keep_alerting), alert.getText());
    }

    @Test
    public void testBindNotification_ChannelImportanceUnspecified_NotifSilent() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_LOW);
        final TextView silent = mNotificationInfo.findViewById(R.id.int_silent);
        final TextView alert = mNotificationInfo.findViewById(R.id.int_alert);
        assertEquals(VISIBLE, silent.getVisibility());
        assertEquals(VISIBLE, alert.getVisibility());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_stay_silent), silent.getText());
        assertEquals(
                mContext.getString(R.string.inline_silent_button_alert), alert.getText());
    }

    @Test
    public void testBindNotification_MinButton() throws Exception {
        mSbn.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final View block = mNotificationInfo.findViewById(R.id.block);
        final View interruptivenessSettings = mNotificationInfo.findViewById(
                R.id.interruptiveness_settings);
        final View minimize = mNotificationInfo.findViewById(R.id.minimize);
        assertEquals(GONE, block.getVisibility());
        assertEquals(GONE, interruptivenessSettings.getVisibility());
        assertEquals(VISIBLE, minimize.getVisibility());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                }, null, true, false, IMPORTANCE_DEFAULT);

        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                }, null, false, false, IMPORTANCE_DEFAULT);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonReappearsAfterSecondBind() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                }, null, true, false, IMPORTANCE_DEFAULT);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertEquals(View.VISIBLE, settingsButton.getVisibility());
    }

    @Test
    public void testLogBlockingHelperCounter_logGutsViewDisplayed() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        mNotificationInfo.logBlockingHelperCounter("HowCanNotifsBeRealIfAppsArent");
        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getType() == MetricsEvent.NOTIFICATION_BLOCKING_HELPER
                        && logMaker.getSubtype() == MetricsEvent.BLOCKING_HELPER_DISPLAY
        ));
    }

    @Test
    public void testLogBlockingHelperCounter_logsForBlockingHelper() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, false, true,
                true, true, IMPORTANCE_DEFAULT);
        mNotificationInfo.logBlockingHelperCounter("HowCanNotifsBeRealIfAppsArent");
        verify(mMetricsLogger).count(eq("HowCanNotifsBeRealIfAppsArent"), eq(1));
    }

    @Test
    public void testOnClickListenerPassesNullChannelForBundle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, MULTIPLE_CHANNEL_COUNT, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(null, c);
                    latch.countDown();
                }, null, true, true, IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.info).performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_ChannelNameInvisibleWhenBundleFromDifferentChannels()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, MULTIPLE_CHANNEL_COUNT, mSbn, null, null,
                null, true, true, IMPORTANCE_DEFAULT);
        final TextView channelNameView =
                mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, channelNameView.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testStopInvisibleIfBundleFromDifferentChannels() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, MULTIPLE_CHANNEL_COUNT, mSbn, null, null,
                null, true, true, IMPORTANCE_DEFAULT);
        final TextView blockView = mNotificationInfo.findViewById(R.id.block);
        assertEquals(GONE, blockView.getVisibility());
    }

    @Test
    public void testbindNotification_BlockingHelper() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, false, false,
                true, true, IMPORTANCE_DEFAULT);
        final TextView view = mNotificationInfo.findViewById(R.id.block_prompt);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.inline_blocking_helper), view.getText());
    }

    @Test
    public void testbindNotification_UnblockableTextVisibleWhenAppUnblockable() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);
        final TextView view = mNotificationInfo.findViewById(R.id.block_prompt);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.notification_unblockable_desc),
                view.getText());
    }

    @Test
    public void testBindNotification_DoesNotUpdateNotificationChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChanged() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChangedMin()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.minimize).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChangedSilenced()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_silent).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChangedUnSilenced()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_alert).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnchanged()
            throws Exception {
        int originalImportance = mNotificationChannel.getImportance();
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.handleCloseControls(true, false);
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
        assertEquals(originalImportance, mNotificationChannel.getImportance());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
        assertEquals(IMPORTANCE_UNSPECIFIED, mNotificationChannel.getImportance());
    }

    @Test
    public void testHandleCloseControls_setsNotificationsDisabledForMultipleChannelNotifications()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, null /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */ ,
                true, false /* isNonblockable */, IMPORTANCE_DEFAULT
        );

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1))
                .setNotificationsEnabledWithImportanceLockForPackage(
                        anyString(), eq(TEST_UID), eq(false));
    }


    @Test
    public void testHandleCloseControls_keepsNotificationsEnabledForMultipleChannelNotifications()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, null /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */,
                true, false /* isNonblockable */, IMPORTANCE_DEFAULT
        );

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1))
                .setNotificationsEnabledWithImportanceLockForPackage(
                        anyString(), eq(TEST_UID), eq(false));
    }

    @Test
    public void testCloseControls_blockingHelperSavesImportanceForMultipleChannelNotifications()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, null /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */ ,
                true /* provisioned */,
                false /* isNonblockable */, true /* isForBlockingHelper */,
                true /* isUserSentimentNegative */, IMPORTANCE_DEFAULT);

        NotificationGuts guts = spy(new NotificationGuts(mContext, null));
        when(guts.getWindowToken()).thenReturn(mock(IBinder.class));
        doNothing().when(guts).animateClose(anyInt(), anyInt(), anyBoolean());
        doNothing().when(guts).setExposed(anyBoolean(), anyBoolean());
        guts.setGutsContent(mNotificationInfo);
        mNotificationInfo.setGutsParent(guts);

        mNotificationInfo.findViewById(R.id.keep).performClick();

        verify(mBlockingHelperManager).dismissCurrentBlockingHelper();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1))
                .setNotificationsEnabledWithImportanceLockForPackage(
                        anyString(), eq(TEST_UID), eq(true));
    }

    @Test
    public void testCloseControls_nonNullCheckSaveListenerDoesntDelayKeepShowing()
            throws Exception {
        NotificationInfo.CheckSaveListener listener =
                mock(NotificationInfo.CheckSaveListener.class);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, listener /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */ , true /* provisioned */,
                false /* isNonblockable */, true /* isForBlockingHelper */,
                true /* isUserSentimentNegative */, IMPORTANCE_DEFAULT);

        NotificationGuts guts = spy(new NotificationGuts(mContext, null));
        when(guts.getWindowToken()).thenReturn(mock(IBinder.class));
        doNothing().when(guts).animateClose(anyInt(), anyInt(), anyBoolean());
        doNothing().when(guts).setExposed(anyBoolean(), anyBoolean());
        guts.setGutsContent(mNotificationInfo);
        mNotificationInfo.setGutsParent(guts);

        mNotificationInfo.findViewById(R.id.keep).performClick();

        verify(mBlockingHelperManager).dismissCurrentBlockingHelper();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1))
                .setNotificationsEnabledWithImportanceLockForPackage(
                        anyString(), eq(TEST_UID), eq(true));
    }

    @Test
    public void testCloseControls_nonNullCheckSaveListenerDoesntDelayDismiss()
            throws Exception {
        NotificationInfo.CheckSaveListener listener =
                mock(NotificationInfo.CheckSaveListener.class);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, listener /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */ ,
                false /* isNonblockable */, true /* isForBlockingHelper */,
                true, true /* isUserSentimentNegative */,  /* isNoisy */
                IMPORTANCE_DEFAULT);

        mNotificationInfo.handleCloseControls(true /* save */, false /* force */);

        mTestableLooper.processAllMessages();
        verify(listener, times(0)).checkSave(any(Runnable.class), eq(mSbn));
    }

    @Test
    public void testCloseControls_checkSaveListenerDelaysStopNotifications()
            throws Exception {
        NotificationInfo.CheckSaveListener listener =
                mock(NotificationInfo.CheckSaveListener.class);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel /* notificationChannel */,
                10 /* numUniqueChannelsInRow */, mSbn, listener /* checkSaveListener */,
                null /* onSettingsClick */, null /* onAppSettingsClick */,
                true /* provisioned */,
                false /* isNonblockable */, true /* isForBlockingHelper */,
                true /* isUserSentimentNegative */, IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.block).performClick();
        mTestableLooper.processAllMessages();
        verify(listener).checkSave(any(Runnable.class), eq(mSbn));
    }

    @Test
    public void testCloseControls_blockingHelperDismissedIfShown() throws Exception {
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                1 /* numChannels */,
                mSbn,
                null /* checkSaveListener */,
                null /* onSettingsClick */,
                null /* onAppSettingsClick */,
                false /* isNonblockable */,
                true /* isForBlockingHelper */,
                true,
                false /* isUserSentimentNegative */,
                IMPORTANCE_DEFAULT);
        NotificationGuts guts = mock(NotificationGuts.class);
        doCallRealMethod().when(guts).closeControls(anyInt(), anyInt(), anyBoolean(), anyBoolean());
        mNotificationInfo.setGutsParent(guts);

        mNotificationInfo.closeControls(mNotificationInfo);

        verify(mBlockingHelperManager).dismissCurrentBlockingHelper();
    }

    @Test
    public void testNonBlockableAppDoesNotBecomeBlocked() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);
        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testBlockChangedCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        verify(mMetricsLogger).write(importanceChangeLog(
                MetricsProto.MetricsEvent.TYPE_ACTION, IMPORTANCE_NONE - IMPORTANCE_LOW));

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_NONE, updated.getValue().getImportance());
    }

    @Test
    public void testBlockChangedCallsUpdateNotificationChannel_blockingHelper() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(
                mMockPackageManager,
                mMockINotificationManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                1 /* numChannels */,
                mSbn,
                null /* checkSaveListener */,
                null /* onSettingsClick */,
                null /* onAppSettingsClick */,
                true /*provisioned */,
                false /* isNonblockable */,
                true /* isForBlockingHelper */,
                true /* isUserSentimentNegative */,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        verify(mMetricsLogger).write(importanceChangeLog(
                MetricsProto.MetricsEvent.TYPE_ACTION, IMPORTANCE_NONE - IMPORTANCE_LOW));

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_NONE, updated.getValue().getImportance());
    }


    @Test
    public void testNonBlockableAppDoesNotBecomeMin() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);
        mNotificationInfo.findViewById(R.id.minimize).performClick();
        waitForUndoButton();

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testMinChangedCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mSbn.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.minimize).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_MIN, updated.getValue().getImportance());
    }

    @Test
    public void testKeepUpdatesNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue(0 != (mNotificationChannel.getUserLockedFields() & USER_LOCKED_IMPORTANCE));
        assertEquals(IMPORTANCE_LOW, mNotificationChannel.getImportance());
    }

    @Test
    public void testBlockUndoDoesNotBlockNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.findViewById(R.id.undo).performClick();
        waitForStopButton();
        // mNotificationInfo.handleCloseControls doesn't get called by this interaction.

        verify(mMetricsLogger).write(importanceChangeLog(
                MetricsProto.MetricsEvent.TYPE_DISMISS, IMPORTANCE_NONE - IMPORTANCE_LOW));

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testMinUndoDoesNotMinNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.minimize).performClick();
        waitForUndoButton();
        mNotificationInfo.findViewById(R.id.undo).performClick();
        waitForStopButton();
        // mNotificationInfo.handleCloseControls doesn't get called by this code path

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(0)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testSilenceCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_silent).performClick();
        waitForUndoButton();
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
    public void testUnSilenceCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_alert).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_HIGH, updated.getValue().getImportance());
    }

    @Test
    public void testSilenceCallsUpdateNotificationChannel_channelImportanceUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_silent).performClick();
        waitForUndoButton();
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
    public void testUnSilenceCallsUpdateNotificationChannel_channelImportanceUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_LOW);

        mNotificationInfo.findViewById(R.id.int_alert).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) != 0);
        assertEquals(IMPORTANCE_HIGH, updated.getValue().getImportance());
    }

    @Test
    public void testCloseControlsDoesNotUpdateMinIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.minimize).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(false, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(false, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testBlockDoesNothingIfCheckSaveListenerIsNoOp() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn,
                (Runnable saveImportance, StatusBarNotification sbn) -> {
                }, null, null, true, true, IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        mTestableLooper.processAllMessages();
        ensureNoUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsUpdatesWhenCheckSaveListenerUsesCallback() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn,
                (Runnable saveImportance, StatusBarNotification sbn) -> {
                    saveImportance.run();
                }, null, null, true, false, IMPORTANCE_DEFAULT
        );

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));

        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        mTestableLooper.processAllMessages();
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testWillBeRemovedReturnsFalseBeforeBind() throws Exception {
        assertFalse(mNotificationInfo.willBeRemoved());
    }

    @Test
    public void testUndoText_min() throws Exception {
        mSbn.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, true,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.minimize).performClick();
        waitForUndoButton();
        TextView confirmationText = mNotificationInfo.findViewById(R.id.confirmation_text);
        assertTrue(confirmationText.getText().toString().contains("minimized"));
    }

    @Test
    public void testUndoText_block() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        TextView confirmationText = mNotificationInfo.findViewById(R.id.confirmation_text);
        assertTrue(confirmationText.getText().toString().contains("won't see"));
    }

    @Test
    public void testUndoText_silence() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_silent).performClick();
        waitForUndoButton();
        TextView confirmationText = mNotificationInfo.findViewById(R.id.confirmation_text);
        assertEquals(mContext.getString(R.string.notification_channel_silenced),
                confirmationText.getText());
    }

    @Test
    public void testUndoText_unsilence() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_alert).performClick();
        waitForUndoButton();
        TextView confirmationText = mNotificationInfo.findViewById(R.id.confirmation_text);
        assertEquals(mContext.getString(R.string.notification_channel_unsilenced),
                confirmationText.getText());
    }

    @Test
    public void testNoHeaderOnConfirmation() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        assertEquals(GONE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testHeaderOnUndo() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, true, false,
                IMPORTANCE_DEFAULT);

        mNotificationInfo.findViewById(R.id.int_block).performClick();
        waitForUndoButton();
        mNotificationInfo.findViewById(R.id.undo).performClick();
        waitForStopButton();
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }
}
