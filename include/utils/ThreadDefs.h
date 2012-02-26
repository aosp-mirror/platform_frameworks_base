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

#ifndef _LIBS_UTILS_THREAD_DEFS_H
#define _LIBS_UTILS_THREAD_DEFS_H

#include <stdint.h>
#include <sys/types.h>
#include <system/graphics.h>

// ---------------------------------------------------------------------------
// C API

#ifdef __cplusplus
extern "C" {
#endif

typedef void* android_thread_id_t;

typedef int (*android_thread_func_t)(void*);

enum {
    /*
     * ***********************************************
     * ** Keep in sync with android.os.Process.java **
     * ***********************************************
     * 
     * This maps directly to the "nice" priorities we use in Android.
     * A thread priority should be chosen inverse-proportionally to
     * the amount of work the thread is expected to do. The more work
     * a thread will do, the less favorable priority it should get so that 
     * it doesn't starve the system. Threads not behaving properly might
     * be "punished" by the kernel.
     * Use the levels below when appropriate. Intermediate values are
     * acceptable, preferably use the {MORE|LESS}_FAVORABLE constants below.
     */
    ANDROID_PRIORITY_LOWEST         =  19,

    /* use for background tasks */
    ANDROID_PRIORITY_BACKGROUND     =  10,
    
    /* most threads run at normal priority */
    ANDROID_PRIORITY_NORMAL         =   0,
    
    /* threads currently running a UI that the user is interacting with */
    ANDROID_PRIORITY_FOREGROUND     =  -2,

    /* the main UI thread has a slightly more favorable priority */
    ANDROID_PRIORITY_DISPLAY        =  -4,
    
    /* ui service treads might want to run at a urgent display (uncommon) */
    ANDROID_PRIORITY_URGENT_DISPLAY =  HAL_PRIORITY_URGENT_DISPLAY,
    
    /* all normal audio threads */
    ANDROID_PRIORITY_AUDIO          = -16,
    
    /* service audio threads (uncommon) */
    ANDROID_PRIORITY_URGENT_AUDIO   = -19,

    /* should never be used in practice. regular process might not 
     * be allowed to use this level */
    ANDROID_PRIORITY_HIGHEST        = -20,

    ANDROID_PRIORITY_DEFAULT        = ANDROID_PRIORITY_NORMAL,
    ANDROID_PRIORITY_MORE_FAVORABLE = -1,
    ANDROID_PRIORITY_LESS_FAVORABLE = +1,
};

enum {
    ANDROID_TGROUP_DEFAULT          = 0,
    ANDROID_TGROUP_BG_NONINTERACT   = 1,
    ANDROID_TGROUP_FG_BOOST         = 2,
    ANDROID_TGROUP_MAX              = ANDROID_TGROUP_FG_BOOST,
};

#ifdef __cplusplus
} // extern "C"
#endif

// ---------------------------------------------------------------------------
// C++ API
#ifdef __cplusplus
namespace android {
// ---------------------------------------------------------------------------

typedef android_thread_id_t thread_id_t;
typedef android_thread_func_t thread_func_t;

enum {
    PRIORITY_LOWEST         = ANDROID_PRIORITY_LOWEST,
    PRIORITY_BACKGROUND     = ANDROID_PRIORITY_BACKGROUND,
    PRIORITY_NORMAL         = ANDROID_PRIORITY_NORMAL,
    PRIORITY_FOREGROUND     = ANDROID_PRIORITY_FOREGROUND,
    PRIORITY_DISPLAY        = ANDROID_PRIORITY_DISPLAY,
    PRIORITY_URGENT_DISPLAY = ANDROID_PRIORITY_URGENT_DISPLAY,
    PRIORITY_AUDIO          = ANDROID_PRIORITY_AUDIO,
    PRIORITY_URGENT_AUDIO   = ANDROID_PRIORITY_URGENT_AUDIO,
    PRIORITY_HIGHEST        = ANDROID_PRIORITY_HIGHEST,
    PRIORITY_DEFAULT        = ANDROID_PRIORITY_DEFAULT,
    PRIORITY_MORE_FAVORABLE = ANDROID_PRIORITY_MORE_FAVORABLE,
    PRIORITY_LESS_FAVORABLE = ANDROID_PRIORITY_LESS_FAVORABLE,
};

// ---------------------------------------------------------------------------
}; // namespace android
#endif  // __cplusplus
// ---------------------------------------------------------------------------


#endif // _LIBS_UTILS_THREAD_DEFS_H
