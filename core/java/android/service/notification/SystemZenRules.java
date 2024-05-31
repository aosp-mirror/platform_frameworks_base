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

package android.service.notification;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AutomaticZenRule;
import android.app.Flags;
import android.content.Context;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeConfig.ZenRule;
import android.text.format.DateFormat;
import android.util.Log;

import com.android.internal.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;

/**
 * Helper methods for schedule-type (system-owned) rules.
 * @hide
 */
public final class SystemZenRules {

    private static final String TAG = "SystemZenRules";

    public static final String PACKAGE_ANDROID = "android";

    /** Updates existing system-owned rules to use the new Modes fields (type, etc). */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static void maybeUpgradeRules(Context context, ZenModeConfig config) {
        for (ZenRule rule : config.automaticRules.values()) {
            if (isSystemOwnedRule(rule)) {
                if (rule.type == AutomaticZenRule.TYPE_UNKNOWN) {
                    upgradeSystemProviderRule(context, rule);
                }
                if (Flags.modesUi()) {
                    rule.allowManualInvocation = true;
                }
            }
        }
    }

    /**
     * Returns whether the rule corresponds to a system ConditionProviderService (i.e. it is owned
     * by the "android" package).
     */
    public static boolean isSystemOwnedRule(ZenRule rule) {
        return PACKAGE_ANDROID.equals(rule.pkg);
    }

    @FlaggedApi(Flags.FLAG_MODES_API)
    private static void upgradeSystemProviderRule(Context context, ZenRule rule) {
        ScheduleInfo scheduleInfo = ZenModeConfig.tryParseScheduleConditionId(rule.conditionId);
        if (scheduleInfo != null) {
            rule.type = AutomaticZenRule.TYPE_SCHEDULE_TIME;
            rule.triggerDescription = getTriggerDescriptionForScheduleTime(context, scheduleInfo);
            return;
        }
        EventInfo eventInfo = ZenModeConfig.tryParseEventConditionId(rule.conditionId);
        if (eventInfo != null) {
            rule.type = AutomaticZenRule.TYPE_SCHEDULE_CALENDAR;
            rule.triggerDescription = getTriggerDescriptionForScheduleEvent(context, eventInfo);
            return;
        }
        Log.wtf(TAG, "Couldn't determine type of system-owned ZenRule " + rule);
    }

    /**
     * Updates the {@link ZenRule#triggerDescription} of the system-owned rule based on the schedule
     * or event condition encoded in its {@link ZenRule#conditionId}.
     *
     * @return {@code true} if the trigger description was updated.
     */
    public static boolean updateTriggerDescription(Context context, ZenRule rule) {
        ScheduleInfo scheduleInfo = ZenModeConfig.tryParseScheduleConditionId(rule.conditionId);
        if (scheduleInfo != null) {
            return updateTriggerDescription(rule,
                    getTriggerDescriptionForScheduleTime(context, scheduleInfo));
        }
        EventInfo eventInfo = ZenModeConfig.tryParseEventConditionId(rule.conditionId);
        if (eventInfo != null) {
            return updateTriggerDescription(rule,
                    getTriggerDescriptionForScheduleEvent(context, eventInfo));
        }
        Log.wtf(TAG, "Couldn't determine type of system-owned ZenRule " + rule);
        return false;
    }

    private static boolean updateTriggerDescription(ZenRule rule, String triggerDescription) {
        if (!Objects.equals(rule.triggerDescription, triggerDescription)) {
            rule.triggerDescription = triggerDescription;
            return true;
        }
        return false;
    }

    /**
     * Returns a suitable trigger description for a time-schedule rule (e.g. "Mon-Fri, 8:00-10:00"),
     * using the Context's current locale.
     */
    @Nullable
    public static String getTriggerDescriptionForScheduleTime(Context context,
            @NonNull ScheduleInfo schedule) {
        final StringBuilder sb = new StringBuilder();
        String daysSummary = getShortDaysSummary(context, schedule);
        if (daysSummary == null) {
            // no use outputting times without dates
            return null;
        }
        sb.append(daysSummary);
        sb.append(context.getString(R.string.zen_mode_trigger_summary_divider_text));
        sb.append(context.getString(
                R.string.zen_mode_trigger_summary_range_symbol_combination,
                timeString(context, schedule.startHour, schedule.startMinute),
                timeString(context, schedule.endHour, schedule.endMinute)));

        return sb.toString();
    }

    /**
     * Returns an ordered summarized list of the days on which this schedule applies, with
     * adjacent days grouped together ("Sun-Wed" instead of "Sun,Mon,Tue,Wed").
     */
    @Nullable
    private static String getShortDaysSummary(Context context, @NonNull ScheduleInfo schedule) {
        // Compute a list of days with contiguous days grouped together, for example: "Sun-Thu" or
        // "Sun-Mon,Wed,Fri"
        final int[] days = schedule.days;
        if (days != null && days.length > 0) {
            final StringBuilder sb = new StringBuilder();
            final Calendar cStart = Calendar.getInstance(getLocale(context));
            final Calendar cEnd = Calendar.getInstance(getLocale(context));
            int[] daysOfWeek = getDaysOfWeekForLocale(cStart);
            // the i for loop goes through days in order as determined by locale. as we walk through
            // the days of the week, keep track of "start" and "last seen"  as indicators for
            // what's contiguous, and initialize them to something not near actual indices
            int startDay = Integer.MIN_VALUE;
            int lastSeenDay = Integer.MIN_VALUE;
            for (int i = 0; i < daysOfWeek.length; i++) {
                final int day = daysOfWeek[i];

                // by default, output if this day is *not* included in the schedule, and thus
                // ends a previously existing block. if this day is included in the schedule
                // after all (as will be determined in the inner for loop), then output will be set
                // to false.
                boolean output = (i == lastSeenDay + 1);
                for (int j = 0; j < days.length; j++) {
                    if (day == days[j]) {
                        // match for this day in the schedule (indicated by counter i)
                        if (i == lastSeenDay + 1) {
                            // contiguous to the block we're walking through right now, record it
                            // (specifically, i, the day index) and move on to the next day
                            lastSeenDay = i;
                            output = false;
                        } else {
                            // it's a match, but not 1 past the last match, we are starting a new
                            // block
                            startDay = i;
                            lastSeenDay = i;
                        }

                        // if there is a match on the last day, also make sure to output at the end
                        // of this loop, and mark the day as the last day we'll have seen in the
                        // scheduled days.
                        if (i == daysOfWeek.length - 1) {
                            output = true;
                        }
                        break;
                    }
                }

                // output in either of 2 cases: this day is not a match, so has ended any previous
                // block, or this day *is* a match but is the last day of the week, so we need to
                // summarize
                if (output) {
                    // either describe just the single day if startDay == lastSeenDay, or
                    // output "startDay - lastSeenDay" as a group
                    if (sb.length() > 0) {
                        sb.append(
                                context.getString(R.string.zen_mode_trigger_summary_divider_text));
                    }

                    SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", getLocale(context));
                    if (startDay == lastSeenDay) {
                        // last group was only one day
                        cStart.set(Calendar.DAY_OF_WEEK, daysOfWeek[startDay]);
                        sb.append(dayFormat.format(cStart.getTime()));
                    } else {
                        // last group was a contiguous group of days, so group them together
                        cStart.set(Calendar.DAY_OF_WEEK, daysOfWeek[startDay]);
                        cEnd.set(Calendar.DAY_OF_WEEK, daysOfWeek[lastSeenDay]);
                        sb.append(context.getString(
                                R.string.zen_mode_trigger_summary_range_symbol_combination,
                                dayFormat.format(cStart.getTime()),
                                dayFormat.format(cEnd.getTime())));
                    }
                }
            }

            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return null;
    }

    /**
     * Convenience method for representing the specified time in string format.
     */
    private static String timeString(Context context, int hour, int minute) {
        final Calendar c = Calendar.getInstance(getLocale(context));
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return DateFormat.getTimeFormat(context).format(c.getTime());
    }

    private static int[] getDaysOfWeekForLocale(Calendar c) {
        int[] daysOfWeek = new int[7];
        int currentDay = c.getFirstDayOfWeek();
        for (int i = 0; i < daysOfWeek.length; i++) {
            if (currentDay > 7) currentDay = 1;
            daysOfWeek[i] = currentDay;
            currentDay++;
        }
        return daysOfWeek;
    }

    private static Locale getLocale(Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /**
     * Returns a suitable trigger description for a calendar-schedule rule (either the name of the
     * calendar, or a message indicating all calendars are included).
     */
    public static String getTriggerDescriptionForScheduleEvent(Context context,
            @NonNull EventInfo event) {
        if (event.calName != null) {
            return event.calName;
        } else {
            return context.getResources().getString(
                    R.string.zen_mode_trigger_event_calendar_any);
        }
    }

    private SystemZenRules() {}
}
