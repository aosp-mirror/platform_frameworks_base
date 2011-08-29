/*
 * Copyright (C) 2011 The Android Open Source Project
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

/** @file rs_time.rsh
 *  \brief Renderscript time routines
 *
 *  This file contains Renderscript functions relating to time and date
 *  manipulation.
 */

#ifndef __RS_TIME_RSH__
#define __RS_TIME_RSH__

/**
 * Calendar time interpreted as seconds elapsed since the Epoch (00:00:00 on
 * January 1, 1970, Coordinated Universal Time (UTC)).
 */
typedef int rs_time_t;

/**
 * Data structure for broken-down time components.
 *
 * tm_sec   - Seconds after the minute. This ranges from 0 to 59, but possibly
 *            up to 60 for leap seconds.
 * tm_min   - Minutes after the hour. This ranges from 0 to 59.
 * tm_hour  - Hours past midnight. This ranges from 0 to 23.
 * tm_mday  - Day of the month. This ranges from 1 to 31.
 * tm_mon   - Months since January. This ranges from 0 to 11.
 * tm_year  - Years since 1900.
 * tm_wday  - Days since Sunday. This ranges from 0 to 6.
 * tm_yday  - Days since January 1. This ranges from 0 to 365.
 * tm_isdst - Flag to indicate whether daylight saving time is in effect. The
 *            value is positive if it is in effect, zero if it is not, and
 *            negative if the information is not available.
 */
typedef struct {
    int tm_sec;     ///< seconds
    int tm_min;     ///< minutes
    int tm_hour;    ///< hours
    int tm_mday;    ///< day of the month
    int tm_mon;     ///< month
    int tm_year;    ///< year
    int tm_wday;    ///< day of the week
    int tm_yday;    ///< day of the year
    int tm_isdst;   ///< daylight savings time
} rs_tm;

/**
 * Returns the number of seconds since the Epoch (00:00:00 UTC, January 1,
 * 1970). If @p timer is non-NULL, the result is also stored in the memory
 * pointed to by this variable. If an error occurs, a value of -1 is returned.
 *
 * @param timer Location to also store the returned calendar time.
 *
 * @return Seconds since the Epoch.
 */
extern rs_time_t __attribute__((overloadable))
    rsTime(rs_time_t *timer);

/**
 * Converts the time specified by @p timer into broken-down time and stores it
 * in @p local. This function also returns a pointer to @p local. If @p local
 * is NULL, this function does nothing and returns NULL.
 *
 * @param local Broken-down time.
 * @param timer Input time as calendar time.
 *
 * @return Pointer to broken-down time (same as input @p local).
 */
extern rs_tm * __attribute__((overloadable))
    rsLocaltime(rs_tm *local, const rs_time_t *timer);

/**
 * Returns the current system clock (uptime) in milliseconds.
 *
 * @return Uptime in milliseconds.
 */
extern int64_t __attribute__((overloadable))
    rsUptimeMillis(void);

/**
 * Returns the current system clock (uptime) in nanoseconds.
 *
 * @return Uptime in nanoseconds.
 */
extern int64_t __attribute__((overloadable))
    rsUptimeNanos(void);

/**
 * Returns the time in seconds since this function was last called in this
 * script.
 *
 * @return Time in seconds.
 */
extern float __attribute__((overloadable))
    rsGetDt(void);

#endif
