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

#include <utils/RefBase.h>
#include <utils/Log.h>

#include "BufferPool.h"
#include "FrameInfo.h"
#include "FrameMetricsObserver.h"

#include <string.h>
#include <vector>

namespace android {
namespace uirenderer {

class FrameMetricsReporter {
public:
    FrameMetricsReporter() {
        mBufferPool = new BufferPool(kBufferSize, kBufferCount);
        LOG_ALWAYS_FATAL_IF(mBufferPool.get() == nullptr, "OOM: unable to allocate buffer pool");
    }

    void addObserver(FrameMetricsObserver* observer) {
        mObservers.push_back(observer);
    }

    bool removeObserver(FrameMetricsObserver* observer) {
        for (size_t i = 0; i < mObservers.size(); i++) {
            if (mObservers[i].get() == observer) {
                mObservers.erase(mObservers.begin() + i);
                return true;
            }
        }
        return false;
    }

    bool hasObservers() {
        return mObservers.size() > 0;
    }

    void reportFrameMetrics(const int64_t* stats) {
        BufferPool::Buffer* statsBuffer = mBufferPool->acquire();

        if (statsBuffer != nullptr) {
            // copy in frame stats
            memcpy(statsBuffer->getBuffer(), stats, kBufferSize * sizeof(*stats));

            // notify on requested threads
            for (size_t i = 0; i < mObservers.size(); i++) {
                mObservers[i]->notify(statsBuffer, mDroppedReports);
            }

            // drop our reference
            statsBuffer->release();
            mDroppedReports = 0;
        } else {
            mDroppedReports++;
        }
    }

    int getDroppedReports() { return mDroppedReports; }

private:
    static const size_t kBufferCount = 3;
    static const size_t kBufferSize = static_cast<size_t>(FrameInfoIndex::NumIndexes);

    std::vector< sp<FrameMetricsObserver> > mObservers;

    sp<BufferPool> mBufferPool;

    int mDroppedReports = 0;
};

}; // namespace uirenderer
}; // namespace android

