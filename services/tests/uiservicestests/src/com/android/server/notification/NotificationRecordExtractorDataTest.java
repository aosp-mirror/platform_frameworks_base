/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;

import com.android.server.UiServiceTestCase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Objects;

public class NotificationRecordExtractorDataTest extends UiServiceTestCase {

    @Test
    public void testHasDiffs_noDiffs() {
        NotificationRecord r = generateRecord();

        NotificationRecordExtractorData extractorData = new NotificationRecordExtractorData(
                1,
                r.getPackageVisibilityOverride(),
                r.canShowBadge(),
                r.canBubble(),
                r.getNotification().isBubbleNotification(),
                r.getChannel(),
                r.getGroupKey(),
                r.getPeopleOverride(),
                r.getSnoozeCriteria(),
                r.getUserSentiment(),
                r.getSuppressedVisualEffects(),
                r.getSystemGeneratedSmartActions(),
                r.getSmartReplies(),
                r.getImportance(),
                r.getRankingScore(),
                r.isConversation(),
                r.getProposedImportance());

        assertFalse(extractorData.hasDiffForRankingLocked(r, 1));
        assertFalse(extractorData.hasDiffForLoggingLocked(r, 1));
    }

    @Test
    public void testHasDiffs_proposedImportanceChange() {
        NotificationRecord r = generateRecord();

        NotificationRecordExtractorData extractorData = new NotificationRecordExtractorData(
                1,
                r.getPackageVisibilityOverride(),
                r.canShowBadge(),
                r.canBubble(),
                r.getNotification().isBubbleNotification(),
                r.getChannel(),
                r.getGroupKey(),
                r.getPeopleOverride(),
                r.getSnoozeCriteria(),
                r.getUserSentiment(),
                r.getSuppressedVisualEffects(),
                r.getSystemGeneratedSmartActions(),
                r.getSmartReplies(),
                r.getImportance(),
                r.getRankingScore(),
                r.isConversation(),
                r.getProposedImportance());

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_IMPORTANCE_PROPOSAL, IMPORTANCE_HIGH);
        Adjustment adjustment = new Adjustment("pkg", r.getKey(), signals, "", 0);
        r.addAdjustment(adjustment);
        r.applyAdjustments();

        assertTrue(extractorData.hasDiffForRankingLocked(r, 1));
        assertTrue(extractorData.hasDiffForLoggingLocked(r, 1));
    }

    private NotificationRecord generateRecord() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
       return new NotificationRecord(getContext(), sbn, channel);
    }
}
