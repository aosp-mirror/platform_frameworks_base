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
 *  \brief Time routines
 *
 *
 */

#ifndef __RS_TIME_RSH__
#define __RS_TIME_RSH__

typedef int rs_time_t;

typedef struct {
    int tm_sec;
    int tm_min;
    int tm_hour;
    int tm_mday;
    int tm_mon;
    int tm_year;
    int tm_wday;
    int tm_yday;
    int tm_isdst;
} rs_tm;

extern rs_time_t __attribute__((overloadable))
    rsTime(rs_time_t *timer);

extern rs_tm * __attribute__((overloadable))
    rsLocaltime(rs_tm *local, const rs_time_t *timer);

// Return the current system clock in milliseconds
extern int64_t __attribute__((overloadable))
    rsUptimeMillis(void);

// Return the current system clock in nanoseconds
extern int64_t __attribute__((overloadable))
    rsUptimeNanos(void);

// Return the time in seconds since function was last called in this script.
extern float __attribute__((overloadable))
    rsGetDt(void);

#endif
