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

#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "core_jni_helpers.h"

#include <utils/SystemClock.h>
#include <utils/Timers.h>

namespace android {

static_assert(std::is_same<int64_t, jlong>::value, "jlong isn't an int64_t");
static_assert(std::is_same<decltype(uptimeMillis()), int64_t>::value,
        "uptimeMillis signature change, expected int64_t return value");
static_assert(std::is_same<decltype(uptimeNanos()), int64_t>::value,
        "uptimeNanos signature change, expected int64_t return value");
static_assert(std::is_same<decltype(elapsedRealtime()), int64_t>::value,
        "elapsedRealtime signature change, expected int64_t return value");
static_assert(std::is_same<decltype(elapsedRealtimeNano()), int64_t>::value,
        "elapsedRealtimeNano signature change, expected int64_t return value");

/*
 * native public static long currentThreadTimeMillis();
 */
static jlong android_os_SystemClock_currentThreadTimeMillis()
{
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_THREAD));
}

/*
 * native public static long currentThreadTimeMicro();
 */
static jlong android_os_SystemClock_currentThreadTimeMicro()
{
    return nanoseconds_to_microseconds(systemTime(SYSTEM_TIME_THREAD));
}

/*
 * native public static long currentTimeMicro();
 */
static jlong android_os_SystemClock_currentTimeMicro()
{
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    // All of these are @CriticalNative, so we can defer directly to SystemClock.h for
    // some of these
    { "uptimeMillis", "()J", (void*) uptimeMillis },
    { "uptimeNanos", "()J", (void*) uptimeNanos },
    { "elapsedRealtime", "()J", (void*) elapsedRealtime },
    { "elapsedRealtimeNanos", "()J", (void*) elapsedRealtimeNano },

    // SystemClock doesn't have an implementation for these that we can directly call
    { "currentThreadTimeMillis", "()J",
            (void*) android_os_SystemClock_currentThreadTimeMillis },
    { "currentThreadTimeMicro", "()J",
            (void*) android_os_SystemClock_currentThreadTimeMicro },
    { "currentTimeMicro", "()J",
            (void*) android_os_SystemClock_currentTimeMicro },
};
int register_android_os_SystemClock(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/os/SystemClock", gMethods, NELEM(gMethods));
}

}; // namespace android

