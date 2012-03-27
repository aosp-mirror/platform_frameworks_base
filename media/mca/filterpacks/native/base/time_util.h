/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_FILTERFW_FILTERPACKS_BASE_TIME_UTIL_H
#define ANDROID_FILTERFW_FILTERPACKS_BASE_TIME_UTIL_H

#include <string>
#include <utils/RefBase.h>

#define LOG_MFF_RUNNING_TIMES 0

namespace android {
namespace filterfw {

uint64_t getTimeUs();

class NamedStopWatch : public RefBase {
  public:
    static const uint64_t kDefaultLoggingPeriodInFrames;

    explicit NamedStopWatch(const std::string& name);
    void Start();
    void Stop();

    void SetName(const std::string& name) { mName = name; }
    void SetLoggingPeriodInFrames(uint64_t numFrames) {
        mLoggingPeriodInFrames = numFrames;
    }

    const std::string& Name() const { return mName; }
    uint64_t NumCalls() const { return mNumCalls; }
    uint64_t TotalUSec() const { return mTotalUSec; }

  private:
    std::string mName;
    uint64_t mLoggingPeriodInFrames;
    uint64_t mStartUSec;
    uint64_t mNumCalls;
    uint64_t mTotalUSec;
};

class ScopedTimer {
  public:
    explicit ScopedTimer(const std::string& stop_watch_name);
    explicit ScopedTimer(NamedStopWatch* watch)
        : mWatch(watch) { mWatch->Start(); }
    ~ScopedTimer() { mWatch->Stop(); }

  private:
    NamedStopWatch* mWatch;
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_FILTERPACKS_BASE_TIME_UTIL_H
