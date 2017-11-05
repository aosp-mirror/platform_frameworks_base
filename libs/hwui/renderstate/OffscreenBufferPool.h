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

#ifndef ANDROID_HWUI_OFFSCREEN_BUFFER_POOL_H
#define ANDROID_HWUI_OFFSCREEN_BUFFER_POOL_H

#include <GpuMemoryTracker.h>
#include <ui/Region.h>
#include "Caches.h"
#include "Texture.h"
#include "utils/Macros.h"

#include <set>

namespace android {
namespace uirenderer {

class RenderState;

/**
 * Lightweight alternative to Layer. Owns the persistent state of an offscreen render target, and
 * encompasses enough information to draw it back on screen (minus paint properties, which are held
 * by LayerOp).
 *
 * Has two distinct sizes - viewportWidth/viewportHeight describe content area,
 * texture.width/.height are actual allocated texture size. Texture will tend to be larger than the
 * viewport bounds, since textures are always allocated with width / height as a multiple of 64, for
 * the purpose of improving reuse.
 */
class OffscreenBuffer : GpuMemoryTracker {
public:
    OffscreenBuffer(RenderState& renderState, Caches& caches, uint32_t viewportWidth,
                    uint32_t viewportHeight, bool wideColorGamut = false);
    ~OffscreenBuffer();

    Rect getTextureCoordinates();

    void dirty(Rect dirtyArea);

    // must be called prior to rendering, to construct/update vertex buffer
    void updateMeshFromRegion();

    // Set by RenderNode for HW layers, TODO for clipped saveLayers
    void setWindowTransform(const Matrix4& transform) {
        inverseTransformInWindow.loadInverse(transform);
    }

    static uint32_t computeIdealDimension(uint32_t dimension);

    uint32_t getSizeInBytes() { return texture.objectSize(); }

    RenderState& renderState;

    uint32_t viewportWidth;
    uint32_t viewportHeight;
    Texture texture;

    bool wideColorGamut = false;

    // Portion of layer that has been drawn to. Used to minimize drawing area when
    // drawing back to screen / parent FBO.
    Region region;

    Matrix4 inverseTransformInWindow;

    // vbo / size of mesh
    GLsizei elementCount = 0;
    GLuint vbo = 0;

    bool hasRenderedSinceRepaint;
};

/**
 * Pool of OffscreenBuffers allocated, but not currently in use.
 */
class OffscreenBufferPool {
public:
    OffscreenBufferPool();
    ~OffscreenBufferPool();

    WARN_UNUSED_RESULT OffscreenBuffer* get(RenderState& renderState, const uint32_t width,
                                            const uint32_t height, bool wideColorGamut = false);

    WARN_UNUSED_RESULT OffscreenBuffer* resize(OffscreenBuffer* layer, const uint32_t width,
                                               const uint32_t height);

    void putOrDelete(OffscreenBuffer* layer);

    /**
     * Clears the pool. This causes all layers to be deleted.
     */
    void clear();

    /**
     * Returns the maximum size of the pool in bytes.
     */
    uint32_t getMaxSize() { return mMaxSize; }

    /**
     * Returns the current size of the pool in bytes.
     */
    uint32_t getSize() { return mSize; }

    size_t getCount() { return mPool.size(); }

    /**
     * Prints out the content of the pool.
     */
    void dump();

private:
    struct Entry {
        Entry() {}

        Entry(const uint32_t layerWidth, const uint32_t layerHeight, bool wideColorGamut)
                : width(OffscreenBuffer::computeIdealDimension(layerWidth))
                , height(OffscreenBuffer::computeIdealDimension(layerHeight))
                , wideColorGamut(wideColorGamut) {}

        explicit Entry(OffscreenBuffer* layer)
                : layer(layer)
                , width(layer->texture.width())
                , height(layer->texture.height())
                , wideColorGamut(layer->wideColorGamut) {}

        static int compare(const Entry& lhs, const Entry& rhs);

        bool operator==(const Entry& other) const { return compare(*this, other) == 0; }

        bool operator!=(const Entry& other) const { return compare(*this, other) != 0; }

        bool operator<(const Entry& other) const { return Entry::compare(*this, other) < 0; }

        OffscreenBuffer* layer = nullptr;
        uint32_t width = 0;
        uint32_t height = 0;
        bool wideColorGamut = false;
    };  // struct Entry

    std::multiset<Entry> mPool;

    uint32_t mSize = 0;
    uint32_t mMaxSize;
};  // class OffscreenBufferCache

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_OFFSCREEN_BUFFER_POOL_H
