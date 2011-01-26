/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpUtils"

#include <stdio.h>
#include <time.h>

#include <cutils/tztime.h>
#include "MtpUtils.h"

namespace android {

/*
DateTime strings follow a compatible subset of the definition found in ISO 8601, and
take the form of a Unicode string formatted as: "YYYYMMDDThhmmss.s". In this
representation, YYYY shall be replaced by the year, MM replaced by the month (01-12),
DD replaced by the day (01-31), T is a constant character 'T' delimiting time from date,
hh is replaced by the hour (00-23), mm is replaced by the minute (00-59), and ss by the
second (00-59). The ".s" is optional, and represents tenths of a second.
*/

bool parseDateTime(const char* dateTime, time_t& outSeconds) {
    int year, month, day, hour, minute, second;
    struct tm tm;

    if (sscanf(dateTime, "%04d%02d%02dT%02d%02d%02d",
            &year, &month, &day, &hour, &minute, &second) != 6)
        return false;
    const char* tail = dateTime + 15;
    // skip optional tenth of second
    if (tail[0] == '.' && tail[1])
        tail += 2;
    //FIXME - support +/-hhmm
    bool useUTC = (tail[0] == 'Z');

    // hack to compute timezone
    time_t dummy;
    localtime_r(&dummy, &tm);

    tm.tm_sec = second;
    tm.tm_min = minute;
    tm.tm_hour = hour;
    tm.tm_mday = day;
    tm.tm_mon = month - 1;  // mktime uses months in 0 - 11 range
    tm.tm_year = year - 1900;
    tm.tm_wday = 0;
    tm.tm_isdst = -1;
    if (useUTC)
        outSeconds = mktime(&tm);
    else
        outSeconds = mktime_tz(&tm, tm.tm_zone);

    return true;
}

void formatDateTime(time_t seconds, char* buffer, int bufferLength) {
    struct tm tm;

    localtime_r(&seconds, &tm);
    snprintf(buffer, bufferLength, "%04d%02d%02dT%02d%02d%02d",
        tm.tm_year + 1900, 
        tm.tm_mon + 1, // localtime_r uses months in 0 - 11 range
        tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);
}

}  // namespace android
