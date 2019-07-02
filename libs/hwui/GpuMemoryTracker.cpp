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

#include "utils/StringUtils.h"

#include <GpuMemoryTracker.h>
#include <cutils/compiler.h>
#include <utils/Trace.h>
#include <array>
#include <sstream>
#include <unordered_set>
#include <vector>

namespace android {
namespace uirenderer {

pthread_t gGpuThread = 0;

#define NUM_TYPES static_cast<int>(GpuObjectType::TypeCount)

const char* TYPE_NAMES[] = {
        "Texture", "OffscreenBuffer", "Layer",
};

struct TypeStats {
    int totalSize = 0;
    int count = 0;
};

static std::array<TypeStats, NUM_TYPES> gObjectStats;
static std::unordered_set<GpuMemoryTracker*> gObjectSet;

void GpuMemoryTracker::notifySizeChanged(int newSize) {
    int delta = newSize - mSize;
    mSize = newSize;
    gObjectStats[static_cast<int>(mType)].totalSize += delta;
}

void GpuMemoryTracker::startTrackingObject() {
    auto result = gObjectSet.insert(this);
    LOG_ALWAYS_FATAL_IF(!result.second,
                        "startTrackingObject() on %p failed, already being tracked!", this);
    gObjectStats[static_cast<int>(mType)].count++;
}

void GpuMemoryTracker::stopTrackingObject() {
    size_t removed = gObjectSet.erase(this);
    LOG_ALWAYS_FATAL_IF(removed != 1, "stopTrackingObject removed %zd, is %p not being tracked?",
                        removed, this);
    gObjectStats[static_cast<int>(mType)].count--;
}

void GpuMemoryTracker::onGpuContextCreated() {
    LOG_ALWAYS_FATAL_IF(gGpuThread != 0,
                        "We already have a gpu thread? "
                        "current = %lu, gpu thread = %lu",
                        pthread_self(), gGpuThread);
    gGpuThread = pthread_self();
}

void GpuMemoryTracker::onGpuContextDestroyed() {
    gGpuThread = 0;
    if (CC_UNLIKELY(gObjectSet.size() > 0)) {
        std::stringstream os;
        dump(os);
        ALOGE("%s", os.str().c_str());
        LOG_ALWAYS_FATAL("Leaked %zd GPU objects!", gObjectSet.size());
    }
}

void GpuMemoryTracker::dump() {
    std::stringstream strout;
    dump(strout);
    ALOGD("%s", strout.str().c_str());
}

void GpuMemoryTracker::dump(std::ostream& stream) {
    for (int type = 0; type < NUM_TYPES; type++) {
        const TypeStats& stats = gObjectStats[type];
        stream << TYPE_NAMES[type];
        stream << " is using " << SizePrinter{stats.totalSize};
        stream << ", count = " << stats.count;
        stream << std::endl;
    }
}

int GpuMemoryTracker::getInstanceCount(GpuObjectType type) {
    return gObjectStats[static_cast<int>(type)].count;
}

int GpuMemoryTracker::getTotalSize(GpuObjectType type) {
    return gObjectStats[static_cast<int>(type)].totalSize;
}

void GpuMemoryTracker::onFrameCompleted() {
    if (ATRACE_ENABLED()) {
        char buf[128];
        for (int type = 0; type < NUM_TYPES; type++) {
            snprintf(buf, 128, "hwui_%s", TYPE_NAMES[type]);
            const TypeStats& stats = gObjectStats[type];
            ATRACE_INT(buf, stats.totalSize);
            snprintf(buf, 128, "hwui_%s_count", TYPE_NAMES[type]);
            ATRACE_INT(buf, stats.count);
        }
    }
}

}  // namespace uirenderer
}  // namespace android;
