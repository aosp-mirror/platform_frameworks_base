/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.text.format;

import com.google.caliper.Benchmark;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class AndroidTimeVsOthersBenchmark {

    private static final String[] TIMEZONE_IDS = {
            "Europe/London",
            "America/Los_Angeles",
            "Asia/Shanghai",
    };

    @Benchmark
    public void toMillis_androidTime(int reps) {
        long answer = 0;
        for (int i = 0; i < reps; i++) {
            String timezoneId = TIMEZONE_IDS[i % TIMEZONE_IDS.length];
            Time time = new Time(timezoneId);
            time.set(1, 2, 3, 4, 5, 2010);
            answer = time.toMillis(false);
        }
        // System.out.println(answer);
    }

    @Benchmark
    public void toMillis_javaTime(int reps) {
        long answer = 0;
        for (int i = 0; i < reps; i++) {
            String timezoneId = TIMEZONE_IDS[i % TIMEZONE_IDS.length];
            LocalDateTime time = LocalDateTime.of(2010, 5 + 1, 4, 3, 2, 1);
            ZoneOffset offset = ZoneId.of(timezoneId).getRules().getOffset(time);
            answer = time.toInstant(offset).toEpochMilli();
        }
        // System.out.println(answer);
    }

    @Benchmark
    public void toMillis_javaUtil(int reps) {
        long answer = 0;
        for (int i = 0; i < reps; i++) {
            String timezoneId = TIMEZONE_IDS[i % TIMEZONE_IDS.length];
            java.util.TimeZone timeZone = java.util.TimeZone.getTimeZone(timezoneId);
            java.util.Calendar calendar = new java.util.GregorianCalendar(timeZone);
            calendar.set(2010, 5, 4, 3, 2, 1);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            answer = calendar.getTimeInMillis();
        }
        // System.out.println(answer);
    }

    @Benchmark
    public void toMillis_androidIucUtil(int reps) {
        long answer = 0;
        for (int i = 0; i < reps; i++) {
            String timezoneId = TIMEZONE_IDS[i % TIMEZONE_IDS.length];
            android.icu.util.TimeZone timeZone =
                    android.icu.util.TimeZone.getTimeZone(timezoneId);
            android.icu.util.Calendar calendar = new android.icu.util.GregorianCalendar(timeZone);
            calendar.set(2010, 5, 4, 3, 2, 1);
            calendar.set(android.icu.util.Calendar.MILLISECOND, 0);
            answer = calendar.getTimeInMillis();
        }
        // System.out.println(answer);
    }
}
