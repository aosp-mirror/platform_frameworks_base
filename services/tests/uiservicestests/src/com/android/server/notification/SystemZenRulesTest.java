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

package com.android.server.notification;

import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static android.service.notification.SystemZenRules.getTriggerDescriptionForScheduleTime;

import static com.google.common.truth.Truth.assertThat;

import android.app.AutomaticZenRule;
import android.app.Flags;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeConfig.ZenRule;

import com.android.internal.R;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

public class SystemZenRulesTest extends UiServiceTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);
    private static final ScheduleInfo SCHEDULE_INFO;
    private static final EventInfo EVENT_INFO;

    static {
        SCHEDULE_INFO = new ScheduleInfo();
        SCHEDULE_INFO.days = new int[] { Calendar.WEDNESDAY };
        SCHEDULE_INFO.startHour = 8;
        SCHEDULE_INFO.endHour = 9;
        EVENT_INFO = new EventInfo();
        EVENT_INFO.calendarId = 1L;
        EVENT_INFO.calName = "myCalendar";
    }

    @Before
    public void setUp() {
        Configuration config = new Configuration();
        config.setLocales(new LocaleList(Locale.US));
        mContext.getOrCreateTestableResources().overrideConfiguration(config);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.zen_mode_trigger_summary_range_symbol_combination, "%1$s-%2$s");
        mContext.getOrCreateTestableResources().addOverride(
                R.string.zen_mode_trigger_summary_divider_text, ",");
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    public void maybeUpgradeRules_oldSystemRules_upgraded() {
        ZenModeConfig config = new ZenModeConfig();
        ZenRule timeRule = new ZenRule();
        timeRule.pkg = SystemZenRules.PACKAGE_ANDROID;
        timeRule.conditionId = ZenModeConfig.toScheduleConditionId(SCHEDULE_INFO);
        config.automaticRules.put("time", timeRule);
        ZenRule calendarRule = new ZenRule();
        calendarRule.pkg = SystemZenRules.PACKAGE_ANDROID;
        calendarRule.conditionId = ZenModeConfig.toEventConditionId(EVENT_INFO);
        config.automaticRules.put("calendar", calendarRule);

        SystemZenRules.maybeUpgradeRules(mContext, config);

        assertThat(timeRule.type).isEqualTo(AutomaticZenRule.TYPE_SCHEDULE_TIME);
        assertThat(timeRule.triggerDescription).isNotEmpty();
        assertThat(calendarRule.type).isEqualTo(AutomaticZenRule.TYPE_SCHEDULE_CALENDAR);
        assertThat(timeRule.triggerDescription).isNotEmpty();
        assertThat(timeRule.allowManualInvocation).isTrue();
        assertThat(calendarRule.allowManualInvocation).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    public void maybeUpgradeRules_newSystemRules_untouched() {
        ZenModeConfig config = new ZenModeConfig();
        ZenRule timeRule = new ZenRule();
        timeRule.pkg = SystemZenRules.PACKAGE_ANDROID;
        timeRule.type = AutomaticZenRule.TYPE_SCHEDULE_TIME;
        timeRule.conditionId = ZenModeConfig.toScheduleConditionId(SCHEDULE_INFO);
        config.automaticRules.put("time", timeRule);
        ZenRule original = timeRule.copy();

        SystemZenRules.maybeUpgradeRules(mContext, config);

        assertThat(timeRule.triggerDescription).isEqualTo(original.triggerDescription);
        assertThat(timeRule.allowManualInvocation).isTrue();
    }

    @Test
    public void maybeUpgradeRules_appOwnedRules_untouched() {
        ZenModeConfig config = new ZenModeConfig();
        ZenRule timeRule = new ZenRule();
        timeRule.pkg = "some_other_package";
        timeRule.type = AutomaticZenRule.TYPE_SCHEDULE_TIME;
        timeRule.conditionId = ZenModeConfig.toScheduleConditionId(SCHEDULE_INFO);
        config.automaticRules.put("time", timeRule);
        ZenRule original = timeRule.copy();

        SystemZenRules.maybeUpgradeRules(mContext, config);

        assertThat(timeRule).isEqualTo(original);
    }

    @Test
    public void getTriggerDescriptionForScheduleTime_noOrSingleDays() {
        // Test various cases for grouping and not-grouping of days.
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.startHour = 10;
        scheduleInfo.endHour = 16;

        // No days
        scheduleInfo.days = new int[]{};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo)).isNull();

        // A single day at the beginning of the week
        scheduleInfo.days = new int[]{Calendar.SUNDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sun,10:00 AM-4:00 PM");

        // A single day in the middle of the week
        scheduleInfo.days = new int[]{Calendar.THURSDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Thu,10:00 AM-4:00 PM");

        // A single day at the end of the week
        scheduleInfo.days = new int[]{Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sat,10:00 AM-4:00 PM");
    }

    @Test
    public void getTriggerDescriptionForScheduleTime_oneGroup() {
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.startHour = 10;
        scheduleInfo.endHour = 16;

        // The whole week
        scheduleInfo.days = new int[] {Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sun-Sat,10:00 AM-4:00 PM");

        // Various cases of one big group
        // Sunday through Thursday
        scheduleInfo.days = new int[] {Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sun-Thu,10:00 AM-4:00 PM");

        // Wednesday through Saturday
        scheduleInfo.days = new int[] {Calendar.WEDNESDAY, Calendar.THURSDAY,
                Calendar.FRIDAY, Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Wed-Sat,10:00 AM-4:00 PM");

        // Monday through Friday
        scheduleInfo.days = new int[] {Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Mon-Fri,10:00 AM-4:00 PM");
    }

    @Test
    public void getTriggerDescriptionForScheduleTime_mixedDays() {
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.startHour = 10;
        scheduleInfo.endHour = 16;

        // cases combining groups and single days scattered around
        scheduleInfo.days = new int[] {Calendar.SUNDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sun,Tue-Thu,Sat,10:00 AM-4:00 PM");

        scheduleInfo.days = new int[] {Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.FRIDAY, Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Sun-Wed,Fri-Sat,10:00 AM-4:00 PM");

        scheduleInfo.days = new int[] {Calendar.MONDAY, Calendar.WEDNESDAY,
                Calendar.FRIDAY, Calendar.SATURDAY};
        assertThat(getTriggerDescriptionForScheduleTime(mContext, scheduleInfo))
                .isEqualTo("Mon,Wed,Fri-Sat,10:00 AM-4:00 PM");
    }

    @Test
    public void getShortDaysSummary_onlyDays() {
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.startHour = 10;
        scheduleInfo.endHour = 16;
        scheduleInfo.days = new int[] {Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY};

        assertThat(SystemZenRules.getShortDaysSummary(mContext, scheduleInfo))
                .isEqualTo("Mon-Fri");
    }

    @Test
    public void getTimeSummary_onlyTime() {
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.startHour = 11;
        scheduleInfo.endHour = 15;
        scheduleInfo.days = new int[] {Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY};

        assertThat(SystemZenRules.getTimeSummary(mContext, scheduleInfo))
                .isEqualTo("11:00 AM-3:00 PM");
    }
}
