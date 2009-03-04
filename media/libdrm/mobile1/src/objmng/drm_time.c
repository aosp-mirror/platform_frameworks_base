/*
 * Copyright (C) 2007 The Android Open Source Project
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

/**
 * @file
 * DRM 1.0 Reference Port: linux implementation of drm_time.c.
 */

#include <objmng/drm_time.h>
#include <unistd.h>

/* See drm_time.h */
uint32_t DRM_time_getElapsedSecondsFrom1970(void)
{
    return time(NULL);
}

/* See drm_time.h */
void DRM_time_sleep(uint32_t ms)
{
    usleep(ms * 1000);
}

/* See drm_time.h */
void DRM_time_getSysTime(T_DB_TIME_SysTime *time_ptr)
{
    time_t t;
    struct tm *tm_t;

    time(&t);
    tm_t = gmtime(&t);

    time_ptr->year = tm_t->tm_year + 1900;
    time_ptr->month = tm_t->tm_mon + 1;
    time_ptr->day = tm_t->tm_mday;
    time_ptr->hour = tm_t->tm_hour;
    time_ptr->min = tm_t->tm_min;
    time_ptr->sec = tm_t->tm_sec;
}
