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
#define DEBUG false
#include "Log.h"

#include "Throttler.h"

#include <inttypes.h>
#include <utils/SystemClock.h>

namespace android {
namespace os {
namespace incidentd {

Throttler::Throttler(size_t limit, int64_t refractoryPeriodMs)
    : mSizeLimit(limit),
      mRefractoryPeriodMs(refractoryPeriodMs),
      mAccumulatedSize(0),
      mLastRefractoryMs(android::elapsedRealtime()) {}

Throttler::~Throttler() {}

bool Throttler::shouldThrottle() {
    int64_t now = android::elapsedRealtime();
    if (now > mRefractoryPeriodMs + mLastRefractoryMs) {
        mLastRefractoryMs = now;
        mAccumulatedSize = 0;
    }
    return mAccumulatedSize > mSizeLimit;
}

void Throttler::addReportSize(size_t reportByteSize) {
    VLOG("The current request took %zu bytes to dropbox", reportByteSize);
    mAccumulatedSize += reportByteSize;
}

void Throttler::dump(FILE* out) {
    fprintf(out, "mSizeLimit=%zu\n", mSizeLimit);
    fprintf(out, "mAccumulatedSize=%zu\n", mAccumulatedSize);
    fprintf(out, "mRefractoryPeriodMs=%" PRIi64 "\n", mRefractoryPeriodMs);
    fprintf(out, "mLastRefractoryMs=%" PRIi64 "\n", mLastRefractoryMs);
}

}  // namespace incidentd
}  // namespace os
}  // namespace android