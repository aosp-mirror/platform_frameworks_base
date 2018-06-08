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

#pragma once

#include <SkString.h>
#include <SkTraceMemoryDump.h>
#include <utils/String8.h>
#include <unordered_map>
#include <vector>

namespace android {
namespace uirenderer {
namespace skiapipeline {

typedef std::pair<const char*, const char*> ResourcePair;

class SkiaMemoryTracer : public SkTraceMemoryDump {
public:
    SkiaMemoryTracer(std::vector<ResourcePair> resourceMap, bool itemizeType);
    SkiaMemoryTracer(const char* categoryKey, bool itemizeType);
    ~SkiaMemoryTracer() override {}

    void logOutput(String8& log);
    void logTotals(String8& log);

    void dumpNumericValue(const char* dumpName, const char* valueName, const char* units,
                          uint64_t value) override;

    void dumpStringValue(const char* dumpName, const char* valueName, const char* value) override {
        // for convenience we just store this in the same format as numerical values
        dumpNumericValue(dumpName, valueName, value, 0);
    }

    LevelOfDetail getRequestedDetails() const override {
        return SkTraceMemoryDump::kLight_LevelOfDetail;
    }

    bool shouldDumpWrappedObjects() const override { return true; }
    void setMemoryBacking(const char*, const char*, const char*) override { }
    void setDiscardableMemoryBacking(const char*, const SkDiscardableMemory&) override { }

private:
    struct TraceValue {
        TraceValue(const char* units, uint64_t value) : units(units), value(value), count(1) {}
        TraceValue(const TraceValue& v) : units(v.units), value(v.value), count(v.count) {}

        const char* units;
        float value;
        int count;
    };

    const char* mapName(const char* resourceName);
    void processElement();
    TraceValue convertUnits(const TraceValue& value);

    const std::vector<ResourcePair> mResourceMap;
    const char* mCategoryKey = nullptr;
    const bool mItemizeType;

    // variables storing the size of all elements being dumped
    TraceValue mTotalSize;
    TraceValue mPurgeableSize;

    // variables storing information on the current node being dumped
    std::string mCurrentElement;
    std::unordered_map<const char*, TraceValue> mCurrentValues;

    // variable that stores the final format of the data after the individual elements are processed
    std::unordered_map<std::string, std::unordered_map<const char*, TraceValue>> mResults;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */