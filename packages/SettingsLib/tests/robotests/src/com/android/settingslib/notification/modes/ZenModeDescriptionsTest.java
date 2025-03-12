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

package com.android.settingslib.notification.modes;

import static android.app.AutomaticZenRule.TYPE_SCHEDULE_TIME;
import static android.service.notification.SystemZenRules.PACKAGE_ANDROID;

import static com.google.common.truth.Truth.assertThat;

import android.service.notification.ZenModeConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
public class ZenModeDescriptionsTest {

    private final ZenModeDescriptions mDescriptions = new ZenModeDescriptions(
            RuntimeEnvironment.getApplication());

    @Test
    public void getTriggerDescriptionForAccessibility_scheduleTime_usesFullDays() {
        ZenModeConfig.ScheduleInfo scheduleInfo = new ZenModeConfig.ScheduleInfo();
        scheduleInfo.days = new int[] { Calendar.MONDAY };
        scheduleInfo.startHour = 11;
        scheduleInfo.endHour = 15;
        ZenMode mode = new TestModeBuilder()
                .setPackage(PACKAGE_ANDROID)
                .setType(TYPE_SCHEDULE_TIME)
                .setConditionId(ZenModeConfig.toScheduleConditionId(scheduleInfo))
                .build();

        assertThat(mDescriptions.getTriggerDescriptionForAccessibility(mode))
                .isEqualTo("Monday, 11:00 AM - 3:00 PM");
    }

    @Test
    public void getTriggerDescriptionForAccessibility_otherMode_isNull() {
        ZenMode mode = new TestModeBuilder().setTriggerDescription("When December ends").build();
        assertThat(mDescriptions.getTriggerDescriptionForAccessibility(mode)).isNull();
    }
}
