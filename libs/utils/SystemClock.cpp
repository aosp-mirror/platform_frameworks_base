/*
 * Copyright (C) 2008 The Android Open Source Project
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


/*
 * System clock functions.
 */

#ifdef HAVE_ANDROID_OS
#include <linux/ioctl.h>
#include <linux/rtc.h>
#include <utils/Atomic.h>
#include <linux/android_alarm.h>
#endif

#include <sys/time.h>
#include <limits.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

#include <utils/SystemClock.h>
#include <utils/Timers.h>

#define LOG_TAG "SystemClock"
#include "utils/Log.h"

namespace android {

/*
 * Set the current time.  This only works when running as root.
 */
int setCurrentTimeMillis(int64_t millis)
{
#if WIN32
    // not implemented
    return -1;
#else
    struct timeval tv;
#ifdef HAVE_ANDROID_OS
    struct timespec ts;
    int fd;
    int res;
#endif
    int ret = 0;

    if (millis <= 0 || millis / 1000LL >= INT_MAX) {
        return -1;
    }

    tv.tv_sec = (time_t) (millis / 1000LL);
    tv.tv_usec = (suseconds_t) ((millis % 1000LL) * 1000LL);

    ALOGD("Setting time of day to sec=%d\n", (int) tv.tv_sec);

#ifdef HAVE_ANDROID_OS
    fd = open("/dev/alarm", O_RDWR);
    if(fd < 0) {
        LOGW("Unable to open alarm driver: %s\n", strerror(errno));
        return -1;
    }
    ts.tv_sec = tv.tv_sec;
    ts.tv_nsec = tv.tv_usec * 1000;
    res = ioctl(fd, ANDROID_ALARM_SET_RTC, &ts);
    if(res < 0) {
        LOGW("Unable to set rtc to %ld: %s\n", tv.tv_sec, strerror(errno));
        ret = -1;
    }
    close(fd);
#else
    if (settimeofday(&tv, NULL) != 0) {
        LOGW("Unable to set clock to %d.%d: %s\n",
            (int) tv.tv_sec, (int) tv.tv_usec, strerror(errno));
        ret = -1;
    }
#endif

    return ret;
#endif // WIN32
}

/*
 * native public static long uptimeMillis();
 */
int64_t uptimeMillis()
{
    int64_t when = systemTime(SYSTEM_TIME_MONOTONIC);
    return (int64_t) nanoseconds_to_milliseconds(when);
}

/*
 * native public static long elapsedRealtime();
 */
int64_t elapsedRealtime()
{
#ifdef HAVE_ANDROID_OS
    static int s_fd = -1;

    if (s_fd == -1) {
        int fd = open("/dev/alarm", O_RDONLY);
        if (android_atomic_cmpxchg(-1, fd, &s_fd)) {
            close(fd);
        }
    }

    struct timespec ts;
    int result = ioctl(s_fd,
            ANDROID_ALARM_GET_TIME(ANDROID_ALARM_ELAPSED_REALTIME), &ts);

    if (result == 0) {
        int64_t when = seconds_to_nanoseconds(ts.tv_sec) + ts.tv_nsec;
        return (int64_t) nanoseconds_to_milliseconds(when);
    } else {
        // XXX: there was an error, probably because the driver didn't
        // exist ... this should return
        // a real error, like an exception!
        int64_t when = systemTime(SYSTEM_TIME_MONOTONIC);
        return (int64_t) nanoseconds_to_milliseconds(when);
    }
#else
    int64_t when = systemTime(SYSTEM_TIME_MONOTONIC);
    return (int64_t) nanoseconds_to_milliseconds(when);
#endif
}

}; // namespace android
