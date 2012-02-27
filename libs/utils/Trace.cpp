/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/Trace.h>

namespace android {

volatile int32_t Tracer::sIsReady = 0;
int Tracer::sTraceFD = -1;
uint64_t Tracer::sEnabledTags = 0;
Mutex Tracer::sMutex;

void Tracer::init() {
    Mutex::Autolock lock(sMutex);

    if (!sIsReady) {
        const char* const traceFileName =
                "/sys/kernel/debug/tracing/trace_marker";
        sTraceFD = open(traceFileName, O_WRONLY);
        if (sTraceFD == -1) {
            ALOGE("error opening trace file: %s (%d)", strerror(errno), errno);
        } else {
            char value[PROPERTY_VALUE_MAX];
            property_get("atrace.tags.enableflags", value, "0");
            sEnabledTags = strtoll(value, NULL, 0) | ATRACE_TAG_ALWAYS;
        }

        android_atomic_release_store(1, &sIsReady);
    }
}

} // namespace andoid
