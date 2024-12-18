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
import static android.service.notification.ZenModeConfig.tryParseCountdownConditionId;

import android.content.Context;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenModeConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;

public final class ZenModeDescriptions {

    private final Context mContext;

    public ZenModeDescriptions(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Returns a version of the mode's trigger description that might be "dynamic".
     *
     * <p>For some modes (such as manual Do Not Disturb) when activated, we know when (and if) the
     * mode is expected to end on its own; this description reflects that. In other cases,
     * returns {@link ZenMode#getTriggerDescription}.
     */
    @Nullable
    public String getTriggerDescription(@NonNull ZenMode mode) {
        if (mode.isManualDnd() && mode.isActive()) {
            long countdownEndTime = tryParseCountdownConditionId(mode.getRule().getConditionId());
            if (countdownEndTime > 0) {
                CharSequence formattedTime = ZenModeConfig.getFormattedTime(mContext,
                        countdownEndTime, ZenModeConfig.isToday(countdownEndTime),
                        mContext.getUserId());
                return mContext.getString(com.android.internal.R.string.zen_mode_until,
                        formattedTime);
            }
        }

        return Strings.emptyToNull(mode.getTriggerDescription());
    }

    /**
     * Returns a version of the {@link ZenMode} trigger description that is suitable for
     * accessibility (for example, where abbreviations are expanded to full words).
     *
     * <p>Returns {@code null} If the standard trigger description (returned by
     * {@link #getTriggerDescription}) is sufficient.
     */
    @Nullable
    public String getTriggerDescriptionForAccessibility(@NonNull ZenMode mode) {
        // Only one special case: time-based schedules, where we want to use full day names.
        if (mode.isSystemOwned() && mode.getType() == TYPE_SCHEDULE_TIME) {
            ZenModeConfig.ScheduleInfo schedule = ZenModeConfig.tryParseScheduleConditionId(
                    mode.getRule().getConditionId());
            if (schedule != null) {
                String fullDaysSummary = SystemZenRules.getDaysOfWeekFull(mContext, schedule);
                if (fullDaysSummary != null) {
                    return fullDaysSummary + ", " + SystemZenRules.getTimeSummary(mContext,
                            schedule);
                }
            }
        }

        return null;
    }
}
