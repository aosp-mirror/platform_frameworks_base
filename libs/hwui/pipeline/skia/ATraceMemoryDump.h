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

#pragma once

#include <SkString.h>
#include <SkTraceMemoryDump.h>
#include <include/gpu/ganesh/GrDirectContext.h>

#include <string>
#include <unordered_map>
#include <utility>

namespace android {
namespace uirenderer {
namespace skiapipeline {

class ATraceMemoryDump : public SkTraceMemoryDump {
public:
    ATraceMemoryDump();
    ~ATraceMemoryDump() override {}

    void dumpNumericValue(const char* dumpName, const char* valueName, const char* units,
                          uint64_t value) override;

    void dumpStringValue(const char* dumpName, const char* valueName, const char* value) override;

    LevelOfDetail getRequestedDetails() const override {
        return SkTraceMemoryDump::kLight_LevelOfDetail;
    }

    bool shouldDumpWrappedObjects() const override { return false; }

    void setMemoryBacking(const char* dumpName, const char* backingType,
                          const char* backingObjectId) override;

    void setDiscardableMemoryBacking(const char*, const SkDiscardableMemory&) override {}

    void startFrame();

    void logTraces(bool gpuMemoryIsAlreadyInDump, GrDirectContext* grContext);

private:
    std::string mLastDumpName;

    uint64_t mLastDumpValue;

    uint64_t mLastPurgeableDumpValue;

    std::string mCategory;

    struct TraceValue {
        uint64_t memory;
        uint64_t purgeableMemory;
    };

    // keys are define in sResourceMap
    std::unordered_map<std::string, TraceValue> mCurrentValues;

    void recordAndResetCountersIfNeeded(const char* dumpName);

    void resetCurrentCounter(const char* dumpName);
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */