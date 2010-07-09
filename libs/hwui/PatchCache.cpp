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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>
#include <utils/ResourceTypes.h>

#include "PatchCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

PatchCache::PatchCache(uint32_t maxEntries): mCache(maxEntries) {
}

PatchCache::~PatchCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void PatchCache::operator()(PatchDescription& description, Patch*& mesh) {
    if (mesh) delete mesh;
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void PatchCache::clear() {
    mCache.setOnEntryRemovedListener(this);
    mCache.clear();
    mCache.setOnEntryRemovedListener(NULL);
}

Patch* PatchCache::get(const Res_png_9patch* patch) {
    const uint32_t width = patch->numXDivs;
    const uint32_t height = patch->numYDivs;
    const PatchDescription description(width, height);

    Patch* mesh = mCache.get(description);
    if (!mesh) {
        PATCH_LOGD("Creating new patch mesh, w=%d h=%d", width, height);
        mesh = new Patch(width, height);
        mCache.put(description, mesh);
    }

    return mesh;
}

}; // namespace uirenderer
}; // namespace android
