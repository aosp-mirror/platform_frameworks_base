/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "ATraceMemoryDump.h"

#include <utils/Trace.h>

#include <cstring>

namespace android {
namespace uirenderer {
namespace skiapipeline {

// When purgeable is INVALID_MEMORY_SIZE it won't be logged at all.
#define INVALID_MEMORY_SIZE -1

/**
 * Skia invokes the following SkTraceMemoryDump functions:
 * 1. dumpNumericValue (dumpName, units="bytes", valueName="size")
 * 2. dumpStringValue (dumpName, valueName="type") [optional -> for example CPU memory does not
 * invoke dumpStringValue]
 * 3. dumpNumericValue (dumpName, units="bytes", valueName="purgeable_size") [optional]
 * 4. setMemoryBacking(dumpName, backingType) [optional -> for example Vulkan GPU resources do not
 * invoke setMemoryBacking]
 *
 * ATraceMemoryDump calculates memory category first by looking at the "type" string passed to
 * dumpStringValue and then by looking at "backingType" passed to setMemoryBacking.
 * Only GPU Texture memory is tracked separately and everything else is grouped as one
 * "Misc Memory" category.
 */
static std::unordered_map<const char*, const char*> sResourceMap = {
        {"malloc", "HWUI CPU Memory"},          // taken from setMemoryBacking(backingType)
        {"gl_texture", "HWUI Texture Memory"},  // taken from setMemoryBacking(backingType)
        {"Texture", "HWUI Texture Memory"},  // taken from dumpStringValue(value, valueName="type")
        // Uncomment categories below to split "Misc Memory" into more brackets for debugging.
        /*{"vk_buffer", "vk_buffer"},
        {"gl_renderbuffer", "gl_renderbuffer"},
        {"gl_buffer", "gl_buffer"},
        {"RenderTarget", "RenderTarget"},
        {"Stencil", "Stencil"},
        {"Path Data", "Path Data"},
        {"Buffer Object", "Buffer Object"},
        {"Surface", "Surface"},*/
};

ATraceMemoryDump::ATraceMemoryDump() {
    mLastDumpName.reserve(100);
    mCategory.reserve(100);
}

void ATraceMemoryDump::dumpNumericValue(const char* dumpName, const char* valueName,
                                        const char* units, uint64_t value) {
    if (!strcmp(units, "bytes")) {
        recordAndResetCountersIfNeeded(dumpName);
        if (!strcmp(valueName, "size")) {
            mLastDumpValue = value;
        } else if (!strcmp(valueName, "purgeable_size")) {
            mLastPurgeableDumpValue = value;
        }
    }
}

void ATraceMemoryDump::dumpStringValue(const char* dumpName, const char* valueName,
                                       const char* value) {
    if (!strcmp(valueName, "type")) {
        recordAndResetCountersIfNeeded(dumpName);
        auto categoryIt = sResourceMap.find(value);
        if (categoryIt != sResourceMap.end()) {
            mCategory = categoryIt->second;
        }
    }
}

void ATraceMemoryDump::setMemoryBacking(const char* dumpName, const char* backingType,
                                        const char* backingObjectId) {
    recordAndResetCountersIfNeeded(dumpName);
    auto categoryIt = sResourceMap.find(backingType);
    if (categoryIt != sResourceMap.end()) {
        mCategory = categoryIt->second;
    }
}

/**
 * startFrame is invoked before dumping anything. It resets counters from the previous frame.
 * This is important, because if there is no new data for a given category trace would assume
 * usage has not changed (instead of reporting 0).
 */
void ATraceMemoryDump::startFrame() {
    resetCurrentCounter("");
    for (auto& it : mCurrentValues) {
        // Once a category is observed in at least one frame, it is always reported in subsequent
        // frames (even if it is 0). Not logging a category to ATRACE would mean its value has not
        // changed since the previous frame, which is not what we want.
        it.second.memory = 0;
        // If purgeableMemory is INVALID_MEMORY_SIZE, then logTraces won't log it at all.
        if (it.second.purgeableMemory != INVALID_MEMORY_SIZE) {
            it.second.purgeableMemory = 0;
        }
    }
}

/**
 * logTraces reads from mCurrentValues and logs the counters with ATRACE.
 */
void ATraceMemoryDump::logTraces() {
    // Accumulate data from last dumpName
    recordAndResetCountersIfNeeded("");
    uint64_t hwui_all_frame_memory = 0;
    for (auto& it : mCurrentValues) {
        hwui_all_frame_memory += it.second.memory;
        ATRACE_INT64(it.first.c_str(), it.second.memory);
        if (it.second.purgeableMemory != INVALID_MEMORY_SIZE) {
            ATRACE_INT64((std::string("Purgeable ") + it.first).c_str(), it.second.purgeableMemory);
        }
    }
    ATRACE_INT64("HWUI All Memory", hwui_all_frame_memory);
}

/**
 * recordAndResetCountersIfNeeded reads memory usage from mLastDumpValue/mLastPurgeableDumpValue and
 * accumulates in mCurrentValues[category]. It makes provision to create a new category and track
 * purgeable memory only if there is at least one observation.
 * recordAndResetCountersIfNeeded won't do anything until all the information for a given dumpName
 * is received.
 */
void ATraceMemoryDump::recordAndResetCountersIfNeeded(const char* dumpName) {
    if (!mLastDumpName.compare(dumpName)) {
        // Still waiting for more data for current dumpName.
        return;
    }

    // First invocation will have an empty mLastDumpName.
    if (!mLastDumpName.empty()) {
        // A new dumpName observed -> store the data already collected.
        auto memoryCounter = mCurrentValues.find(mCategory);
        if (memoryCounter != mCurrentValues.end()) {
            memoryCounter->second.memory += mLastDumpValue;
            if (mLastPurgeableDumpValue != INVALID_MEMORY_SIZE) {
                if (memoryCounter->second.purgeableMemory == INVALID_MEMORY_SIZE) {
                    memoryCounter->second.purgeableMemory = mLastPurgeableDumpValue;
                } else {
                    memoryCounter->second.purgeableMemory += mLastPurgeableDumpValue;
                }
            }
        } else {
            mCurrentValues[mCategory] = {mLastDumpValue, mLastPurgeableDumpValue};
        }
    }

    // Reset counters and default category for the newly observed "dumpName".
    resetCurrentCounter(dumpName);
}

void ATraceMemoryDump::resetCurrentCounter(const char* dumpName) {
    mLastDumpValue = 0;
    mLastPurgeableDumpValue = INVALID_MEMORY_SIZE;
    mLastDumpName = dumpName;
    // Categories not listed in sResourceMap are reported as "Misc Memory"
    mCategory = "HWUI Misc Memory";
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
