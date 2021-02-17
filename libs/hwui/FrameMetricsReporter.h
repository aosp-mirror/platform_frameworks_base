/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include <utils/Mutex.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <ui/FatVector.h>

#include "FrameInfo.h"
#include "FrameMetricsObserver.h"

#include <string.h>

namespace android {
namespace uirenderer {

class FrameMetricsReporter {
public:
    FrameMetricsReporter() {}

    void addObserver(FrameMetricsObserver* observer) {
        std::lock_guard lock(mObserversLock);
        mObservers.push_back(observer);
    }

    bool removeObserver(FrameMetricsObserver* observer) {
        std::lock_guard lock(mObserversLock);
        for (size_t i = 0; i < mObservers.size(); i++) {
            if (mObservers[i].get() == observer) {
                mObservers.erase(mObservers.begin() + i);
                return true;
            }
        }
        return false;
    }

    bool hasObservers() {
        std::lock_guard lock(mObserversLock);
        return mObservers.size() > 0;
    }

    void reportFrameMetrics(const int64_t* stats) {
        FatVector<sp<FrameMetricsObserver>, 10> copy;
        {
            std::lock_guard lock(mObserversLock);
            copy.reserve(mObservers.size());
            for (size_t i = 0; i < mObservers.size(); i++) {
                copy.push_back(mObservers[i]);
            }
        }
        for (size_t i = 0; i < copy.size(); i++) {
            copy[i]->notify(stats);
        }
    }

private:
    FatVector<sp<FrameMetricsObserver>, 10> mObservers GUARDED_BY(mObserversLock);
    std::mutex mObserversLock;
};

}  // namespace uirenderer
}  // namespace android
