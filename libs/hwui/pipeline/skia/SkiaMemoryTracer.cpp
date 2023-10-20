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

#include "SkiaMemoryTracer.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaMemoryTracer::SkiaMemoryTracer(std::vector<ResourcePair> resourceMap, bool itemizeType)
        : mResourceMap(resourceMap)
        , mItemizeType(itemizeType)
        , mTotalSize("bytes", 0)
        , mPurgeableSize("bytes", 0) {}

SkiaMemoryTracer::SkiaMemoryTracer(const char* categoryKey, bool itemizeType)
        : mCategoryKey(categoryKey)
        , mItemizeType(itemizeType)
        , mTotalSize("bytes", 0)
        , mPurgeableSize("bytes", 0) {}

std::optional<std::string> SkiaMemoryTracer::mapName(const std::string& resourceName) {
    for (auto& resource : mResourceMap) {
        if (resourceName.find(resource.first) != std::string::npos) {
            return resource.second;
        }
    }
    return std::nullopt;
}

void SkiaMemoryTracer::processElement() {
    if (!mCurrentElement.empty()) {
        // Only count elements that contain "size", other values just provide metadata.
        auto sizeResult = mCurrentValues.find("size");
        if (sizeResult != mCurrentValues.end()) {
            mTotalSize.value += sizeResult->second.value;
            mTotalSize.count++;
        } else {
            mCurrentElement.clear();
            mCurrentValues.clear();
            return;
        }

        // find the purgeable size if one exists
        auto purgeableResult = mCurrentValues.find("purgeable_size");
        if (purgeableResult != mCurrentValues.end()) {
            mPurgeableSize.value += purgeableResult->second.value;
            mPurgeableSize.count++;
        }

        // find the type if one exists
        std::string type;
        auto typeResult = mCurrentValues.find("type");
        if (typeResult != mCurrentValues.end()) {
            type = typeResult->second.units;
        } else if (mItemizeType) {
            type = "Other";
        }

        // compute the type if we are itemizing or use the default "size" if we are not
        std::string key = (mItemizeType) ? type : sizeResult->first;

        // compute the top level element name using either the map or category key
        std::optional<std::string> resourceName = mapName(mCurrentElement);
        if (mCategoryKey) {
            // find the category if one exists
            auto categoryResult = mCurrentValues.find(*mCategoryKey);
            if (categoryResult != mCurrentValues.end()) {
                resourceName = categoryResult->second.units;
            } else if (mItemizeType) {
                resourceName = "Other";
            }
        }

        // if we don't have a pretty name then use the dumpName
        if (!resourceName) {
            resourceName = mCurrentElement;
        }

        auto result = mResults.find(*resourceName);
        if (result != mResults.end()) {
            auto& resourceValues = result->second;
            typeResult = resourceValues.find(key);
            if (typeResult != resourceValues.end()) {
                SkASSERT(sizeResult->second.units == typeResult->second.units);
                typeResult->second.value += sizeResult->second.value;
                typeResult->second.count++;
            } else {
                resourceValues.insert({key, sizeResult->second});
            }
        } else {
            TraceValue sizeValue = sizeResult->second;
            mCurrentValues.clear();
            mCurrentValues.insert({key, sizeValue});
            mResults.insert({*resourceName, mCurrentValues});
        }
    }

    mCurrentElement.clear();
    mCurrentValues.clear();
}

void SkiaMemoryTracer::dumpNumericValue(const char* dumpName, const char* valueName,
                                        const char* units, uint64_t value) {
    if (mCurrentElement != dumpName) {
        processElement();
        mCurrentElement = dumpName;
    }
    mCurrentValues.insert({valueName, {units, value}});
}

bool SkiaMemoryTracer::hasOutput() {
    // process any remaining elements
    processElement();
    return mResults.size() > 0;
}

void SkiaMemoryTracer::logOutput(String8& log) {
    // process any remaining elements
    processElement();

    for (const auto& namedItem : mResults) {
        if (mItemizeType) {
            log.appendFormat("  %s:\n", namedItem.first.c_str());
            for (const auto& typedValue : namedItem.second) {
                TraceValue traceValue = convertUnits(typedValue.second);
                const char* entry = (traceValue.count > 1) ? "entries" : "entry";
                log.appendFormat("    %s: %.2f %s (%d %s)\n", typedValue.first.c_str(),
                                 traceValue.value, traceValue.units.c_str(), traceValue.count,
                                 entry);
            }
        } else {
            auto result = namedItem.second.find("size");
            if (result != namedItem.second.end()) {
                TraceValue traceValue = convertUnits(result->second);
                const char* entry = (traceValue.count > 1) ? "entries" : "entry";
                log.appendFormat("  %s: %.2f %s (%d %s)\n", namedItem.first.c_str(),
                                 traceValue.value, traceValue.units.c_str(), traceValue.count,
                                 entry);
            }
        }
    }
}

size_t SkiaMemoryTracer::total() {
    processElement();
    if ("bytes" == mTotalSize.units) {
        return mTotalSize.value;
    }
    return 0;
}

void SkiaMemoryTracer::logTotals(String8& log) {
    TraceValue total = convertUnits(mTotalSize);
    TraceValue purgeable = convertUnits(mPurgeableSize);
    log.appendFormat("  %.0f bytes, %.2f %s (%.2f %s is purgeable)\n", mTotalSize.value,
                     total.value, total.units.c_str(), purgeable.value, purgeable.units.c_str());
}

SkiaMemoryTracer::TraceValue SkiaMemoryTracer::convertUnits(const TraceValue& value) {
    TraceValue output(value);
    if ("bytes" == output.units && output.value >= 1024) {
        output.value = output.value / 1024.0f;
        output.units = "KB";
    }
    if ("KB" == output.units && output.value >= 1024) {
        output.value = output.value / 1024.0f;
        output.units = "MB";
    }
    return output;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
