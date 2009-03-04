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

#include "JNIHelp.h"
#include "jni.h"
#include "android_runtime/AndroidRuntime.h"

#include "utils/SystemClock.h"

#include <sys/time.h>
#include <time.h>

namespace android {

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
};
int register_android_os_SystemClock(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "android/os/SystemClock", gMethods, NELEM(gMethods));
}

}; // namespace android

