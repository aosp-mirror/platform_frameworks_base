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

#include "JNIHelp.h"
#include "jni.h"
#include "android_runtime/AndroidRuntime.h"

#include <sys/time.h>
#include <time.h>

#include <utils/SystemClock.h>

#if HAVE_QC_TIME_SERVICES
extern "C" {
#include <private/time_genoff.h>
}
#endif


namespace android {

#if HAVE_QC_TIME_SERVICES
int setTimeServicesTime(time_bases_type base, int64_t millis)
{
    int rc = 0;
    time_genoff_info_type time_set;
    uint64_t value = millis;
    time_set.base = base;
    time_set.unit = TIME_MSEC;
    time_set.operation = T_SET;
    time_set.ts_val = &value;
    rc = time_genoff_operation(&time_set);
    if (rc) {
        ALOGE("Error setting generic offset: %d. Still setting system time\n", rc);
        rc = -1;
    }
    return rc;
}
#endif

/*
 * Set the current time.  This only works when running as root.
 */
static int setCurrentTimeMillis(int64_t millis)
{
    struct timeval tv;
    struct timespec ts;
    int fd;
    int res;
    int ret = 0;

#if HAVE_QC_TIME_SERVICES
    int rc;
    rc = setTimeServicesTime(ATS_USER, millis);
    if (rc) {
        ALOGE("Error setting generic offset: %d. Still setting system time\n", rc);
    }
#endif

    if (millis <= 0 || millis / 1000LL >= INT_MAX) {
        return -1;
    }

    tv.tv_sec = (time_t) (millis / 1000LL);
    tv.tv_usec = (suseconds_t) ((millis % 1000LL) * 1000LL);

    ALOGD("Setting time of day to sec=%d\n", (int) tv.tv_sec);

    fd = open("/dev/alarm", O_RDWR);
    if(fd < 0) {
        ALOGW("Unable to open alarm driver: %s\n", strerror(errno));
        return -1;
    }
    ts.tv_sec = tv.tv_sec;
    ts.tv_nsec = tv.tv_usec * 1000;
    res = ioctl(fd, ANDROID_ALARM_SET_RTC, &ts);
    if(res < 0) {
        ALOGW("Unable to set rtc to %ld: %s\n", tv.tv_sec, strerror(errno));
        ret = -1;
    }
    close(fd);
    return ret;
}

/*
 * native public static void setCurrentTimeMillis(long millis)
 *
 * Set the current time.  This only works when running as root.
 */
static jboolean android_os_SystemClock_setCurrentTimeMillis(JNIEnv* env,
    jobject clazz, jlong millis)
{
    return (setCurrentTimeMillis(millis) == 0);
}

/*
 * native public static long uptimeMillis();
 */
static jlong android_os_SystemClock_uptimeMillis(JNIEnv* env,
        jobject clazz)
{
    return (jlong)uptimeMillis();
}

/*
 * native public static long elapsedRealtime();
 */
static jlong android_os_SystemClock_elapsedRealtime(JNIEnv* env,
        jobject clazz)
{
    return (jlong)elapsedRealtime();
}

/*
 * native public static long currentThreadTimeMillis();
 */
static jlong android_os_SystemClock_currentThreadTimeMillis(JNIEnv* env,
        jobject clazz)
{
#if defined(HAVE_POSIX_CLOCKS)
    struct timespec tm;

    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &tm);

    return tm.tv_sec * 1000LL + tm.tv_nsec / 1000000;
#else
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000LL + tv.tv_usec / 1000;
#endif
}

/*
 * native public static long currentThreadTimeMicro();
 */
static jlong android_os_SystemClock_currentThreadTimeMicro(JNIEnv* env,
        jobject clazz)
{
#if defined(HAVE_POSIX_CLOCKS)
    struct timespec tm;

    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &tm);

    return tm.tv_sec * 1000000LL + tm.tv_nsec / 1000;
#else
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000000LL + tv.tv_nsec / 1000;
#endif
}

/*
 * native public static long currentTimeMicro();
 */
static jlong android_os_SystemClock_currentTimeMicro(JNIEnv* env,
        jobject clazz)
{
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

/*
 * public static native long elapsedRealtimeNano();
 */
static jlong android_os_SystemClock_elapsedRealtimeNano(JNIEnv* env,
        jobject clazz)
{
    return (jlong)elapsedRealtimeNano();
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "setCurrentTimeMillis",      "(J)Z",
            (void*) android_os_SystemClock_setCurrentTimeMillis },
    { "uptimeMillis",      "()J",
            (void*) android_os_SystemClock_uptimeMillis },
    { "elapsedRealtime",      "()J",
            (void*) android_os_SystemClock_elapsedRealtime },
    { "currentThreadTimeMillis",      "()J",
            (void*) android_os_SystemClock_currentThreadTimeMillis },
    { "currentThreadTimeMicro",       "()J",
            (void*) android_os_SystemClock_currentThreadTimeMicro },
    { "currentTimeMicro",             "()J",
            (void*) android_os_SystemClock_currentTimeMicro },
    { "elapsedRealtimeNanos",      "()J",
            (void*) android_os_SystemClock_elapsedRealtimeNano },
};
int register_android_os_SystemClock(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "android/os/SystemClock", gMethods, NELEM(gMethods));
}

}; // namespace android

