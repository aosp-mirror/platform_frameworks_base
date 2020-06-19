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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AssistantFeedbackControllerTest extends SysuiTestCase {
    private static final int ON = 1;
    private static final int OFF = 0;
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;

    private AssistantFeedbackController mAssistantFeedbackController;
    private StatusBarNotification mSbn;

    @Before
    public void setUp() {
        switchSetting(ON);
        mAssistantFeedbackController = new AssistantFeedbackController(mContext);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, TEST_UID, 0, new Notification(),
                UserHandle.CURRENT, null, 0);
    }

    @Test
    public void testUserControls_settingDisabled() {
        switchSetting(OFF);
        assertFalse(mAssistantFeedbackController.isFeedbackEnabled());
    }

    @Test
    public void testUserControls_settingEnabled() {
        switchSetting(ON);
        assertTrue(mAssistantFeedbackController.isFeedbackEnabled());
    }

    @Test
    public void testShowFeedbackIndicator_settingDisabled() {
        switchSetting(OFF);
        assertFalse(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_UNCHANGED)));
    }

    @Test
    public void testShowFeedbackIndicator_changedImportance() {
        assertTrue(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, RANKING_UNCHANGED)));
        assertTrue(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_LOW, RANKING_UNCHANGED)));
        assertTrue(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_LOW, IMPORTANCE_MIN, RANKING_UNCHANGED)));
    }

    @Test
    public void testShowFeedbackIndicator_changedRanking() {
        assertTrue(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_PROMOTED)));
        assertTrue(mAssistantFeedbackController.showFeedbackIndicator(
                getEntry(IMPORTANCE_DEFAULT, IMPORTANCE_DEFAULT, RANKING_DEMOTED)));
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

    private void switchSetting(int setting) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.NOTIFICATION_FEEDBACK_ENABLED, setting, UserHandle.USER_CURRENT);
    }
}
