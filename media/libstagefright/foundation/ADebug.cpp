/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "ADebug.h"

#include <stdio.h>
#include <stdlib.h>

#ifdef ANDROID
#include <cutils/log.h>
#endif

namespace android {

Logger::Logger(LogType type)
    : mLogType(type) {
    switch (mLogType) {
        case VERBOSE:
            mMessage = "V ";
            break;
        case INFO:
            mMessage = "I ";
            break;
        case WARNING:
            mMessage = "W ";
            break;
        case ERROR:
            mMessage = "E ";
            break;
        case FATAL:
            mMessage = "F ";
            break;

        default:
            break;
    }
}

Logger::~Logger() {
    if (mLogType == VERBOSE) {
        return;
    }

    mMessage.append("\n");

#if defined(ANDROID) && 1
    LOG_PRI(ANDROID_LOG_INFO, "ADebug", "%s", mMessage.c_str());
#else
    fprintf(stderr, mMessage.c_str());
    fflush(stderr);
#endif

    if (mLogType == FATAL) {
        abort();
    }
}

const char *LeafName(const char *s) {
    const char *lastSlash = strrchr(s, '/');
    return lastSlash != NULL ? lastSlash + 1 : s;
}

}  // namespace android
