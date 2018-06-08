/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "OffscreenBufferPool.h"

#include "Caches.h"
#include "renderstate/RenderState.h"
#include "utils/FatVector.h"
#include "utils/TraceUtils.h"

#include <utils/Color.h>
#include <utils/Log.h>

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

////////////////////////////////////////////////////////////////////////////////
// OffscreenBuffer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer::OffscreenBuffer(RenderState& renderState, Caches& caches, uint32_t viewportWidth,
                                 uint32_t viewportHeight, bool wideColorGamut)
        : GpuMemoryTracker(GpuObjectType::OffscreenBuffer)
        , renderState(renderState)
        , viewportWidth(viewportWidth)
        , viewportHeight(viewportHeight)
        , texture(caches)
        , wideColorGamut(wideColorGamut) {
    uint32_t width = computeIdealDimension(viewportWidth);
    uint32_t height = computeIdealDimension(viewportHeight);
    ATRACE_FORMAT("Allocate %ux%u HW Layer", width, height);
    caches.textureState().activateTexture(0);
    texture.resize(width, height, wideColorGamut ? GL_RGBA16F : caches.rgbaInternalFormat(),
                   GL_RGBA);
    texture.blend = true;
    texture.setWrap(GL_CLAMP_TO_EDGE);
    // not setting filter on texture, since it's set when drawing, based on transform
}

Rect OffscreenBuffer::getTextureCoordinates() {
    const float texX = 1.0f / static_cast<float>(texture.width());
    const float texY = 1.0f / static_cast<float>(texture.height());
    return Rect(0, viewportHeight * texY, viewportWidth * texX, 0);
}

void OffscreenBuffer::dirty(Rect dirtyArea) {
    dirtyArea.doIntersect(0, 0, viewportWidth, viewportHeight);
    if (!dirtyArea.isEmpty()) {
        region.orSelf(
                android::Rect(dirtyArea.left, dirtyArea.top, dirtyArea.right, dirtyArea.bottom));
    }
}

void OffscreenBuffer::updateMeshFromRegion() {
    // avoid T-junctions as they cause artifacts in between the resultant
    // geometry when complex transforms occur.
    // TODO: generate the safeRegion only if necessary based on drawing transform
    Region safeRegion = Region::createTJunctionFreeRegion(region);

    size_t count;
    const android::Rect* rects = safeRegion.getArray(&count);

    const float texX = 1.0f / float(texture.width());
    const float texY = 1.0f / float(texture.height());

    FatVector<TextureVertex, 64> meshVector(count *
                                            4);  // uses heap if more than 64 vertices needed
    TextureVertex* mesh = &meshVector[0];
    for (size_t i = 0; i < count; i++) {
        const android::Rect* r = &rects[i];

        const float u1 = r->left * texX;
        const float v1 = (viewportHeight - r->top) * texY;
        const float u2 = r->right * texX;
        const float v2 = (viewportHeight - r->bottom) * texY;

        TextureVertex::set(mesh++, r->left, r->top, u1, v1);
        TextureVertex::set(mesh++, r->right, r->top, u2, v1);
        TextureVertex::set(mesh++, r->left, r->bottom, u1, v2);
        TextureVertex::set(mesh++, r->right, r->bottom, u2, v2);
    }
    elementCount = count * 6;
    renderState.meshState().genOrUpdateMeshBuffer(
            &vbo, sizeof(TextureVertex) * count * 4, &meshVector[0],
            GL_DYNAMIC_DRAW);  // TODO: GL_STATIC_DRAW if savelayer
}

uint32_t OffscreenBuffer::computeIdealDimension(uint32_t dimension) {
    return uint32_t(ceilf(dimension / float(LAYER_SIZE)) * LAYER_SIZE);
}

OffscreenBuffer::~OffscreenBuffer() {
    ATRACE_FORMAT("Destroy %ux%u HW Layer", texture.width(), texture.height());
    texture.deleteTexture();
    renderState.meshState().deleteMeshBuffer(vbo);
    elementCount = 0;
    vbo = 0;
}

///////////////////////////////////////////////////////////////////////////////
// OffscreenBufferPool
///////////////////////////////////////////////////////////////////////////////

OffscreenBufferPool::OffscreenBufferPool()
        // 4 screen-sized RGBA_8888 textures
        : mMaxSize(DeviceInfo::multiplyByResolution(4 * 4)) {}

OffscreenBufferPool::~OffscreenBufferPool() {
    clear();  // TODO: unique_ptr?
}

int OffscreenBufferPool::Entry::compare(const Entry& lhs, const Entry& rhs) {
    int deltaInt = int(lhs.width) - int(rhs.width);
    if (deltaInt != 0) return deltaInt;

    deltaInt = int(lhs.height) - int(rhs.height);
    if (deltaInt != 0) return deltaInt;

    return int(lhs.wideColorGamut) - int(rhs.wideColorGamut);
}

void OffscreenBufferPool::clear() {
    for (auto& entry : mPool) {
        delete entry.layer;
    }
    mPool.clear();
    mSize = 0;
}

OffscreenBuffer* OffscreenBufferPool::get(RenderState& renderState, const uint32_t width,
                                          const uint32_t height, bool wideColorGamut) {
    OffscreenBuffer* layer = nullptr;

    Entry entry(width, height, wideColorGamut);
    auto iter = mPool.find(entry);

    if (iter != mPool.end()) {
        entry = *iter;
        mPool.erase(iter);

        layer = entry.layer;
        layer->viewportWidth = width;
        layer->viewportHeight = height;
        mSize -= layer->getSizeInBytes();
    } else {
        layer = new OffscreenBuffer(renderState, Caches::getInstance(), width, height,
                                    wideColorGamut);
    }

    return layer;
}

OffscreenBuffer* OffscreenBufferPool::resize(OffscreenBuffer* layer, const uint32_t width,
                                             const uint32_t height) {
    RenderState& renderState = layer->renderState;
    if (layer->texture.width() == OffscreenBuffer::computeIdealDimension(width) &&
        layer->texture.height() == OffscreenBuffer::computeIdealDimension(height)) {
        // resize in place
        layer->viewportWidth = width;
        layer->viewportHeight = height;

        // entire area will be repainted (and may be smaller) so clear usage region
        layer->region.clear();
        return layer;
    }
    bool wideColorGamut = layer->wideColorGamut;
    putOrDelete(layer);
    return get(renderState, width, height, wideColorGamut);
}

void OffscreenBufferPool::dump() {
    for (auto entry : mPool) {
        ALOGD("  Layer size %dx%d", entry.width, entry.height);
    }
}

void OffscreenBufferPool::putOrDelete(OffscreenBuffer* layer) {
    const uint32_t size = layer->getSizeInBytes();
    // Don't even try to cache a layer that's bigger than the cache
    if (size < mMaxSize) {
        // TODO: Use an LRU
        while (mSize + size > mMaxSize) {
            OffscreenBuffer* victim = mPool.begin()->layer;
            mSize -= victim->getSizeInBytes();
            delete victim;
            mPool.erase(mPool.begin());
        }

        // clear region, since it's no longer valid
        layer->region.clear();

        Entry entry(layer);

        mPool.insert(entry);
        mSize += size;
    } else {
        delete layer;
    }
}

};  // namespace uirenderer
};  // namespace android
