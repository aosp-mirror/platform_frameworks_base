/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_FENCE_H
#define ANDROID_FENCE_H

#include <utils/String8.h>
#include <utils/RefBase.h>

typedef int64_t nsecs_t;

namespace android {

class Fence : public LightRefBase<Fence> {
public:
    Fence() { }
    Fence(int) { }
    static const sp<Fence> NO_FENCE;
    static constexpr nsecs_t SIGNAL_TIME_PENDING = INT64_MAX;
    static constexpr nsecs_t SIGNAL_TIME_INVALID = -1;
    static sp<Fence> merge(const char* name, const sp<Fence>& f1, const sp<Fence>& f2) {
        return NO_FENCE;
    }

    static sp<Fence> merge(const String8& name, const sp<Fence>& f1, const sp<Fence>& f2) {
        return NO_FENCE;
    }

    enum class Status {
        Invalid,     // Fence is invalid
        Unsignaled,  // Fence is valid but has not yet signaled
        Signaled,    // Fence is valid and has signaled
    };

    status_t wait(int timeout) { return OK; }

    status_t waitForever(const char* logname) { return OK; }

    int dup() const { return 0; }

    inline Status getStatus() {
        // The sync_wait call underlying wait() has been measured to be
        // significantly faster than the sync_fence_info call underlying
        // getSignalTime(), which might otherwise appear to be the more obvious
        // way to check whether a fence has signaled.
        switch (wait(0)) {
            case NO_ERROR:
                return Status::Signaled;
            case -ETIME:
                return Status::Unsignaled;
            default:
                return Status::Invalid;
        }
    }
};

} // namespace android

#endif // ANDROID_FENCE_H
