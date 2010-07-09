/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_PATCH_CACHE_H
#define ANDROID_UI_PATCH_CACHE_H

#include "Patch.h"
#include "GenerationCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#define DEBUG_PATCHES 0

// Debug
#if DEBUG_PATCHES
    #define PATCH_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define PATCH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

class PatchCache: public OnEntryRemoved<PatchDescription, Patch*> {
public:
    PatchCache(uint32_t maxCapacity);
    ~PatchCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(PatchDescription& description, Patch*& mesh);

    Patch* get(const Res_png_9patch* patch);
    void clear();

private:
    GenerationCache<PatchDescription, Patch*> mCache;
}; // class PatchCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_PATCH_CACHE_H
