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

package com.android.systemui.statusbar.notification;


import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_DEMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_PROMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_UNCHANGED;

import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_DEMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_PROMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_SILENCED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_UNCHANGED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class AssistantFeedbackControllerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;

    private AssistantFeedbackController mAssistantFeedbackController;
    private DeviceConfigProxyFake mProxyFake;
    private TestableLooper mTestableLooper;

    private StatusBarNotification mSbn;

    @Before
    public void setUp() {
        mProxyFake = new DeviceConfigProxyFake();
        mTestableLooper = TestableLooper.get(this);
        mAssistantFeedbackController = new AssistantFeedbackController(
                new Handler(mTestableLooper.getLooper()),
                mContext, mProxyFake);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, TEST_UID, 0, new Notification(),
                UserHandle.CURRENT, null, 0);
    }

    @Test
    public void testFlagDisabled() {
        switchFlag("false");
        assertFalse(mAssistantFeedbackController.isFeedbackEnabled());
    }

    @Test
    public void testFlagEnabled() {
        switchFlag("true");
        assertTrue(mAssistantFeedbackController.isFeedbackEnabled());
    }

    @Test
    public void testFeedback_flagDisabled() {
        switchFlag("false");
        // test flag disables logic with default values
        assertEquals(STATUS_UNCHANGED, mAssistantFeedbackController.getFeedbackStatus(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED)));
        assertNull(mAssistantFeedbackController.getFeedbackIcon(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED)));
        // test that the flag disables logic with values that otherwise would return a value
        assertEquals(STATUS_UNCHANGED, mAssistantFeedbackController.getFeedbackStatus(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, RANKING_PROMOTED)));
        assertNull(mAssistantFeedbackController.getFeedbackIcon(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, RANKING_PROMOTED)));
    }

    @Test
    public void testFeedback_noChange() {
        switchFlag("true");
        assertEquals(STATUS_UNCHANGED, mAssistantFeedbackController.getFeedbackStatus(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED)));
        assertNull(mAssistantFeedbackController.getFeedbackIcon(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED)));
    }

    @Test
    public void testFeedback_changedImportance() {
        switchFlag("true");
        NotificationEntry entry = getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, RANKING_UNCHANGED);
        assertEquals(STATUS_PROMOTED, mAssistantFeedbackController.getFeedbackStatus(entry));
        assertNotNull(mAssistantFeedbackController.getFeedbackIcon(entry));

        entry = getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_LOW, RANKING_UNCHANGED);
        assertEquals(STATUS_SILENCED, mAssistantFeedbackController.getFeedbackStatus(entry));
        assertNotNull(mAssistantFeedbackController.getFeedbackIcon(entry));

        entry = getEntry(IMPORTANCE_LOW, IMPORTANCE_MIN, RANKING_UNCHANGED);
        assertEquals(STATUS_DEMOTED, mAssistantFeedbackController.getFeedbackStatus(entry));
        assertNotNull(mAssistantFeedbackController.getFeedbackIcon(entry));
    }

    @Test
    public void testFeedback_changedRanking() {
        switchFlag("true");
        NotificationEntry entry =
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_PROMOTED);
        assertEquals(STATUS_PROMOTED, mAssistantFeedbackController.getFeedbackStatus(entry));
        assertNotNull(mAssistantFeedbackController.getFeedbackIcon(entry));

        entry = getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_DEMOTED);
        assertEquals(STATUS_DEMOTED, mAssistantFeedbackController.getFeedbackStatus(entry));
        assertNotNull(mAssistantFeedbackController.getFeedbackIcon(entry));
    }

    @Test
    public void testGetFeedbackIcon_whenPromoted() {
        switchFlag("true");
        FeedbackIcon expected = new FeedbackIcon(com.android.internal.R.drawable.ic_feedback_uprank,
                com.android.internal.R.string.notification_feedback_indicator_promoted);
        assertEquals(expected, mAssistantFeedbackController.getFeedbackIcon(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, RANKING_PROMOTED)));
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

    private void switchFlag(String enabled) {
        mProxyFake.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI, SystemUiDeviceConfigFlags.ENABLE_NAS_FEEDBACK,
                enabled, false);
        mTestableLooper.processAllMessages();
    }
}
