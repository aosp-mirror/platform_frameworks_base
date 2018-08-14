/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.NotificationManager.IMPORTANCE_LOW;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;

import com.android.server.UiServiceTestCase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Objects;

public class NotificationAdjustmentExtractorTest extends UiServiceTestCase {

    @Test
    public void testExtractsAdjustment() {
        NotificationAdjustmentExtractor extractor = new NotificationAdjustmentExtractor();

        NotificationRecord r = generateRecord();

        Bundle signals = new Bundle();
        signals.putString(Adjustment.KEY_GROUP_KEY, GroupHelper.AUTOGROUP_KEY);
        ArrayList<SnoozeCriterion> snoozeCriteria = new ArrayList<>();
        snoozeCriteria.add(new SnoozeCriterion("n", "n", "n"));
        signals.putParcelableArrayList(Adjustment.KEY_SNOOZE_CRITERIA, snoozeCriteria);
        ArrayList<String> people = new ArrayList<>();
        people.add("you");
        signals.putStringArrayList(Adjustment.KEY_PEOPLE, people);
        Adjustment adjustment = new Adjustment("pkg", r.getKey(), signals, "", 0);
        r.addAdjustment(adjustment);

        assertFalse(r.getGroupKey().contains(GroupHelper.AUTOGROUP_KEY));
        assertFalse(Objects.equals(people, r.getPeopleOverride()));
        assertFalse(Objects.equals(snoozeCriteria, r.getSnoozeCriteria()));

        assertNull(extractor.process(r));

        assertTrue(r.getGroupKey().contains(GroupHelper.AUTOGROUP_KEY));
        assertEquals(people, r.getPeopleOverride());
        assertEquals(snoozeCriteria, r.getSnoozeCriteria());
    }

    @Test
    public void testExtractsAdjustments() {
        NotificationAdjustmentExtractor extractor = new NotificationAdjustmentExtractor();

        NotificationRecord r = generateRecord();

        Bundle pSignals = new Bundle();
        ArrayList<String> people = new ArrayList<>();
        people.add("you");
        pSignals.putStringArrayList(Adjustment.KEY_PEOPLE, people);
        Adjustment pAdjustment = new Adjustment("pkg", r.getKey(), pSignals, "", 0);
        r.addAdjustment(pAdjustment);

        Bundle sSignals = new Bundle();
        ArrayList<SnoozeCriterion> snoozeCriteria = new ArrayList<>();
        snoozeCriteria.add(new SnoozeCriterion("n", "n", "n"));
        sSignals.putParcelableArrayList(Adjustment.KEY_SNOOZE_CRITERIA, snoozeCriteria);
        Adjustment sAdjustment = new Adjustment("pkg", r.getKey(), sSignals, "", 0);
        r.addAdjustment(sAdjustment);

        Bundle gSignals = new Bundle();
        gSignals.putString(Adjustment.KEY_GROUP_KEY, GroupHelper.AUTOGROUP_KEY);
        Adjustment gAdjustment = new Adjustment("pkg", r.getKey(), gSignals, "", 0);
        r.addAdjustment(gAdjustment);

        assertFalse(r.getGroupKey().contains(GroupHelper.AUTOGROUP_KEY));
        assertFalse(Objects.equals(people, r.getPeopleOverride()));
        assertFalse(Objects.equals(snoozeCriteria, r.getSnoozeCriteria()));

        assertNull(extractor.process(r));

        assertTrue(r.getGroupKey().contains(GroupHelper.AUTOGROUP_KEY));
        assertEquals(people, r.getPeopleOverride());
        assertEquals(snoozeCriteria, r.getSnoozeCriteria());
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
