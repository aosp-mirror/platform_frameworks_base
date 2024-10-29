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
package com.android.internal.widget.remotecompose.core;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** This generates the standard system variables for time. */
public class TimeVariables {
    /**
     * This class populates all time variables in the system
     *
     * @param context
     */
    public void updateTime(RemoteContext context) {
        LocalDateTime dateTime =
                LocalDateTime.now(ZoneId.systemDefault()); // TODO, pass in a timezone explicitly?
        // This define the time in the format
        // seconds run from Midnight=0 quantized to seconds hour 0..3599
        // minutes run from Midnight=0 quantized to minutes 0..1439
        // hours run from Midnight=0 quantized to Hours 0-23
        // CONTINUOUS_SEC is seconds from midnight looping every hour 0-3600
        // CONTINUOUS_SEC is accurate to milliseconds due to float precession
        // ID_OFFSET_TO_UTC is the offset from UTC in sec (typically / 3600f)
        int month = dateTime.getMonth().getValue();
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int seconds = dateTime.getSecond();
        int currentMinute = hour * 60 + minute;
        int currentSeconds = minute * 60 + seconds;
        float sec = currentSeconds + dateTime.getNano() * 1E-9f;
        int day_week = dateTime.getDayOfWeek().getValue();

        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime offsetDateTime = dateTime.atZone(zone).toOffsetDateTime();
        ZoneOffset offset = offsetDateTime.getOffset();

        context.loadFloat(RemoteContext.ID_OFFSET_TO_UTC, offset.getTotalSeconds());
        context.loadFloat(RemoteContext.ID_CONTINUOUS_SEC, sec);
        context.loadFloat(RemoteContext.ID_TIME_IN_SEC, currentSeconds);
        context.loadFloat(RemoteContext.ID_TIME_IN_MIN, currentMinute);
        context.loadFloat(RemoteContext.ID_TIME_IN_HR, hour);
        context.loadFloat(RemoteContext.ID_CALENDAR_MONTH, month);
        context.loadFloat(RemoteContext.ID_DAY_OF_MONTH, month);
        context.loadFloat(RemoteContext.ID_WEEK_DAY, day_week);
    }
}
