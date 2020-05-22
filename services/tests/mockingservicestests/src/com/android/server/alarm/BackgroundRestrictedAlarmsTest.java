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
package com.android.server.alarm;

import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ObjectUtils;
import com.android.server.alarm.AlarmManagerService.Alarm;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BackgroundRestrictedAlarmsTest {
    private SparseArray<ArrayList<Alarm>> addPendingAlarm(
            SparseArray<ArrayList<Alarm>> all, int uid, String name, boolean removeIt) {
        ArrayList<Alarm> uidAlarms = all.get(uid);
        if (uidAlarms == null) {
            all.put(uid, uidAlarms = new ArrayList<>());
        }
        uidAlarms.add(new Alarm(
                removeIt ? RTC : RTC_WAKEUP,
                0, 0, 0, 0, 0, null, null, null, null, 0, null, uid, name));
        return all;
    }

    private static String toString(SparseArray<ArrayList<Alarm>> pendingAlarms) {
        final StringBuilder sb = new StringBuilder();

        String sep = "";
        for (int i = 0; i < pendingAlarms.size(); i++) {
            sb.append(sep);
            sep = ", ";
            sb.append("[");
            sb.append(pendingAlarms.keyAt(i));
            sb.append(": ");
            sb.append(toString(pendingAlarms.valueAt(i)));
            sb.append("]");
        }
        return sb.toString();
    }

    private static String toString(ArrayList<Alarm> alarms) {
        final StringBuilder sb = new StringBuilder();

        alarms.sort((a, b) -> ObjectUtils.compare(a.packageName, b.packageName));

        String sep = "";
        for (Alarm a : alarms) {
            sb.append(sep);
            sep = ", ";
            sb.append(a.packageName);
        }
        return sb.toString();
    }

    private void runCheckAllPendingAlarms(
            SparseArray<ArrayList<Alarm>> pending, ArrayList<Alarm> alarmsToDeliver) {
        // RTC_WAKEUP alarms are restricted.
        AlarmManagerService.findAllUnrestrictedPendingBackgroundAlarmsLockedInner(pending,
                alarmsToDeliver, alarm -> alarm.type == RTC_WAKEUP);
    }

    @Test
    public void findAllUnrestrictedPendingBackgroundAlarmsLockedInner_empty() {
        SparseArray<ArrayList<Alarm>> pending = new SparseArray<>();

        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();

        runCheckAllPendingAlarms(pending, alarmsToDeliver);

        assertEquals("", toString(pending));
        assertEquals("", toString(alarmsToDeliver));
    }

    @Test
    public void findAllUnrestrictedPendingBackgroundAlarmsLockedInner_single_remove() {
        SparseArray<ArrayList<Alarm>> pending = new SparseArray<>();

        addPendingAlarm(pending, 100001, "a1", false);

        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();

        runCheckAllPendingAlarms(pending, alarmsToDeliver);

        assertEquals("[100001: a1]", toString(pending));
        assertEquals("", toString(alarmsToDeliver));
    }

    @Test
    public void findAllUnrestrictedPendingBackgroundAlarmsLockedInner_single_nonremove() {
        SparseArray<ArrayList<Alarm>> pending = new SparseArray<>();

        addPendingAlarm(pending, 100001, "a1", true);

        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();
        runCheckAllPendingAlarms(pending, alarmsToDeliver);


        assertEquals("", toString(pending));
        assertEquals("a1", toString(alarmsToDeliver));
    }

    @Test
    public void findAllUnrestrictedPendingBackgroundAlarmsLockedInner_complex() {
        SparseArray<ArrayList<Alarm>> pending = new SparseArray<>();

        addPendingAlarm(pending, 100001, "a11", false);
        addPendingAlarm(pending, 100001, "a12", true);
        addPendingAlarm(pending, 100001, "a13", false);
        addPendingAlarm(pending, 100001, "a14", true);

        addPendingAlarm(pending, 100002, "a21", false);

        addPendingAlarm(pending, 100003, "a31", true);

        addPendingAlarm(pending, 100004, "a41", false);
        addPendingAlarm(pending, 100004, "a42", false);

        addPendingAlarm(pending, 100005, "a51", true);
        addPendingAlarm(pending, 100005, "a52", true);

        addPendingAlarm(pending, 100006, "a61", true);
        addPendingAlarm(pending, 100006, "a62", false);
        addPendingAlarm(pending, 100006, "a63", true);
        addPendingAlarm(pending, 100006, "a64", false);

        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();
        runCheckAllPendingAlarms(pending, alarmsToDeliver);


        assertEquals("[100001: a11, a13], [100002: a21], [100004: a41, a42], [100006: a62, a64]",
                toString(pending));
        assertEquals("a12, a14, a31, a51, a52, a61, a63", toString(alarmsToDeliver));
    }

    @Test
    public void findAllUnrestrictedPendingBackgroundAlarmsLockedInner_complex_allRemove() {
        SparseArray<ArrayList<Alarm>> pending = new SparseArray<>();

        addPendingAlarm(pending, 100001, "a11", true);
        addPendingAlarm(pending, 100001, "a12", true);
        addPendingAlarm(pending, 100001, "a13", true);
        addPendingAlarm(pending, 100001, "a14", true);

        addPendingAlarm(pending, 100002, "a21", true);

        addPendingAlarm(pending, 100003, "a31", true);

        addPendingAlarm(pending, 100004, "a41", true);
        addPendingAlarm(pending, 100004, "a42", true);

        addPendingAlarm(pending, 100005, "a51", true);
        addPendingAlarm(pending, 100005, "a52", true);

        addPendingAlarm(pending, 100006, "a61", true);
        addPendingAlarm(pending, 100006, "a62", true);
        addPendingAlarm(pending, 100006, "a63", true);
        addPendingAlarm(pending, 100006, "a64", true);

        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();
        runCheckAllPendingAlarms(pending, alarmsToDeliver);


        assertEquals("", toString(pending));
        assertEquals("a11, a12, a13, a14, a21, a31, a41, a42, a51, a52, a61, a62, a63, a64",
                toString(alarmsToDeliver));
    }
}
