/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TIMING_H
#define ANDROID_HWUI_TIMING_H

#include <sys/time.h>

#define TIME_METHOD() MethodTimer __method_timer(__func__)
class MethodTimer {
public:
    MethodTimer(const char* name)
            : mMethodName(name) {
        gettimeofday(&mStart, NULL);
    }

    ~MethodTimer() {
        struct timeval stop;
        gettimeofday(&stop, NULL);
        long long elapsed = (stop.tv_sec * 1000000) - (mStart.tv_sec * 1000000)
                + (stop.tv_usec - mStart.tv_usec);
        ALOGD("%s took %.2fms", mMethodName, elapsed / 1000.0);
    }
private:
    const char* mMethodName;
    struct timeval mStart;
};

#endif
