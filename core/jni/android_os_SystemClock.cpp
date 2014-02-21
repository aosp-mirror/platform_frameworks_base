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

namespace android {

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

