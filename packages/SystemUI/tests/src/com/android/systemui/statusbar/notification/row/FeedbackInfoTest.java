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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_UNCHANGED;

import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_ALERTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_DEMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_PROMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_SILENCED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Locale;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class FeedbackInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;

    private FeedbackInfo mFeedbackInfo;
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private final NotificationGuts mGutsParent = mock(NotificationGuts.class);
    private final ExpandableNotificationRow mMockNotificationRow =
            mock(ExpandableNotificationRow.class);
    private final AssistantFeedbackController mAssistantFeedbackController =
            mock(AssistantFeedbackController.class);
    private StatusBarNotification mSbn;

    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private NotificationGutsManager mNotificationGutsManager;

    private Configuration mConfig;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(IStatusBarService.class, mStatusBarService);
        mDependency.injectTestDependency(NotificationGutsManager.class, mNotificationGutsManager);

        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mFeedbackInfo = (FeedbackInfo) layoutInflater.inflate(R.layout.feedback_info, null);
        mFeedbackInfo.setGutsParent(mGutsParent);

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
                applicationInfo);

        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
        mConfig = new Configuration(mContext.getResources().getConfiguration());
        Configuration c2 = new Configuration(mConfig);
        c2.setLocale(Locale.US);
        mContext.getResources().updateConfiguration(c2, null);
    }

    @After
    public void tearDown() {
        mContext.getResources().updateConfiguration(mConfig, null);
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(),
                mMockNotificationRow, mAssistantFeedbackController, mStatusBarService,
                mNotificationGutsManager);
        final TextView textView = mFeedbackInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    public void testBindNotification_SetsPackageIcon() {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(),
                mMockNotificationRow, mAssistantFeedbackController, mStatusBarService,
                mNotificationGutsManager);
        final ImageView iconView = mFeedbackInfo.findViewById(R.id.pkg_icon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testPrompt_silenced() {
        when(mAssistantFeedbackController.getFeedbackStatus(any(NotificationEntry.class)))
                .thenReturn(STATUS_SILENCED);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was automatically demoted to Silent by the system. "
                        + "Let the developer know your feedback. Was this correct?",
                        prompt.getText().toString());
    }

    @Test
    public void testPrompt_promoted() {
        when(mAssistantFeedbackController.getFeedbackStatus(any(NotificationEntry.class)))
                .thenReturn(STATUS_PROMOTED);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was automatically ranked higher in your shade. "
                        + "Let the developer know your feedback. Was this correct?",
                        prompt.getText().toString());
    }

    @Test
    public void testPrompt_alerted() {
        when(mAssistantFeedbackController.getFeedbackStatus(any(NotificationEntry.class)))
                .thenReturn(STATUS_ALERTED);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was automatically promoted to Default by the system. "
                        + "Let the developer know your feedback. Was this correct?",
                prompt.getText().toString());
    }

    @Test
    public void testPrompt_demoted() {
        when(mAssistantFeedbackController.getFeedbackStatus(any(NotificationEntry.class)))
                .thenReturn(STATUS_DEMOTED);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was automatically ranked lower in your shade. "
                        + "Let the developer know your feedback. Was this correct?",
                        prompt.getText().toString());
    }

    @Test
    public void testPositiveFeedback() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);

        final View yes = mFeedbackInfo.findViewById(R.id.yes);
        yes.performClick();
        verify(mGutsParent, times(1)).closeControls(yes, false);
    }

    @Test
    public void testNegativeFeedback() {
        when(mNotificationGutsManager.openGuts(
                any(View.class),
                anyInt(),
                anyInt(),
                any(NotificationMenuRowPlugin.MenuItem.class)))
                .thenReturn(true);

        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(), mMockNotificationRow,
                mAssistantFeedbackController, mStatusBarService, mNotificationGutsManager);

        final View no = mFeedbackInfo.findViewById(R.id.no);
        no.performClick();
        verify(mGutsParent, times(1)).closeControls(no, false);
        verify(mNotificationGutsManager, times(1)).openGuts(
                eq(mMockNotificationRow), eq(0), eq(0),
                any());
    }

    private NotificationEntry getEntry(int oldImportance, int newImportance,
            int rankingAdjustment) {
        NotificationChannel channel = new NotificationChannel("id", "name", oldImportance);
        return new NotificationEntryBuilder()
                .setSbn(mSbn)
                .setChannel(channel)
                .setImportance(newImportance)
                .setRankingAdjustment(rankingAdjustment)
                .build();
    }

    private NotificationEntry getEntry() {
        return getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED);
    }
}
