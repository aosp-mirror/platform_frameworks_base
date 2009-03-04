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
 * Time Porting Layer
 *
 * Basic support functions that are needed by time.
 *
 * <!-- #interface list begin -->
 * \section drm_time Interface
 * - DRM_time_getElapsedSecondsFrom1970()
 * - DRM_time_sleep()
 * - DRM_time_getSysTime()
 * <!-- #interface list end -->
 */

#ifndef __DRM_TIME_H__
#define __DRM_TIME_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <time.h>
#include <drm_common_types.h>

/** the time format */
typedef struct __db_system_time_
{
    uint16_t year;
    uint16_t month;
    uint16_t day;
    uint16_t hour;
    uint16_t min;
    uint16_t sec;
} T_DB_TIME_SysTime;

/**
 * Get the system time.it's up to UTC
 * \return Return the time in elapsed seconds.
 */
uint32_t DRM_time_getElapsedSecondsFrom1970(void);

/**
 * Suspend the execution of the current thread for a specified interval
 * \param ms suspended time by millisecond
 */
void DRM_time_sleep(uint32_t ms);

/**
 * function: get current system time
 * \param  time_ptr[OUT]  the system time got
 * \attention
 *    time_ptr must not be NULL
 */
void DRM_time_getSysTime(T_DB_TIME_SysTime *time_ptr);

#ifdef __cplusplus
}
#endif

#endif /* __DRM_TIME_H__ */
