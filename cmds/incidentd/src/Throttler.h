/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef THROTTLER_H
#define THROTTLER_H

#include <utils/RefBase.h>

#include <unistd.h>

namespace android {
namespace os {
namespace incidentd {
/**
 * This is a size-based throttler which prevents incidentd to take more data.
 */
class Throttler : public virtual android::RefBase {
public:
    Throttler(size_t limit, int64_t refractoryPeriodMs);
    ~Throttler();

    /**
     * Asserts this before starting taking report.
     */
    bool shouldThrottle();

    void addReportSize(size_t reportByteSize);

    void dump(FILE* out);

private:
    const size_t mSizeLimit;
    const int64_t mRefractoryPeriodMs;

    size_t mAccumulatedSize;
    int64_t mLastRefractoryMs;
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // THROTTLER_H
