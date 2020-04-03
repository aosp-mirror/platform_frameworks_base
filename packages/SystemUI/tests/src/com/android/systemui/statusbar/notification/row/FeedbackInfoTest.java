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
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_DEMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_PROMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_UNCHANGED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
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
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class FeedbackInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;

    private FeedbackInfo mFeedbackInfo;
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private final NotificationGuts mGutsParent = mock(NotificationGuts.class);
    private StatusBarNotification mSbn;

    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private IStatusBarService mStatusBarService;

    @Before
    public void setUp() throws Exception {

        mDependency.injectTestDependency(NotificationEntryManager.class, mNotificationEntryManager);
        mDependency.injectTestDependency(IStatusBarService.class, mStatusBarService);

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
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(),
                mock(AssistantFeedbackController.class));
        final TextView textView = mFeedbackInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    public void testBindNotification_SetsPackageIcon() {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(),
                mock(AssistantFeedbackController.class));
        final ImageView iconView = mFeedbackInfo.findViewById(R.id.pkg_icon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testOk() {
        final CountDownLatch latch = new CountDownLatch(1);
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(),
                mock(AssistantFeedbackController.class));

        final View okButton = mFeedbackInfo.findViewById(R.id.ok);
        okButton.performClick();
        assertEquals(1, latch.getCount());
        verify(mGutsParent, times(1)).closeControls(any(), anyBoolean());
    }

    @Test
    public void testPrompt_silenced() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(IMPORTANCE_DEFAULT,
                IMPORTANCE_LOW, RANKING_UNCHANGED), mock(AssistantFeedbackController.class));
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was silenced by the system. Was this correct?",
                prompt.getText());
    }

    @Test
    public void testPrompt_promoted_importance() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(IMPORTANCE_DEFAULT,
                IMPORTANCE_HIGH, RANKING_UNCHANGED), mock(AssistantFeedbackController.class));
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was promoted by the system. Was this correct?",
                prompt.getText());
    }

    @Test
    public void testPrompt_promoted_ranking() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(IMPORTANCE_DEFAULT,
                IMPORTANCE_DEFAULT, RANKING_PROMOTED), mock(AssistantFeedbackController.class));
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was promoted by the system. Was this correct?",
                prompt.getText());
    }

    @Test
    public void testPrompt_demoted_importance() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(IMPORTANCE_LOW,
                IMPORTANCE_MIN, RANKING_UNCHANGED), mock(AssistantFeedbackController.class));
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was demoted by the system. Was this correct?",
                prompt.getText());
    }

    @Test
    public void testPrompt_demoted_ranking() {
        mFeedbackInfo.bindGuts(mMockPackageManager, mSbn, getEntry(IMPORTANCE_DEFAULT,
                IMPORTANCE_DEFAULT, RANKING_DEMOTED), mock(AssistantFeedbackController.class));
        TextView prompt = mFeedbackInfo.findViewById(R.id.prompt);
        assertEquals("This notification was demoted by the system. Was this correct?",
                prompt.getText());
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
