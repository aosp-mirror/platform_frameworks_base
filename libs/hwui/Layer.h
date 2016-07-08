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

#pragma once

#include <cutils/compiler.h>
#include <sys/types.h>
#include <utils/StrongPointer.h>
#include <utils/RefBase.h>
#include <memory>

#include <GLES2/gl2.h>
#include <GpuMemoryTracker.h>

#include <ui/Region.h>

#include <SkPaint.h>
#include <SkXfermode.h>

#include "Matrix.h"
#include "Rect.h"
#include "RenderBuffer.h"
#include "Texture.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

// Forward declarations
class Caches;
class RenderState;

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
class Layer : public VirtualLightRefBase, GpuMemoryTracker {
public:
    // layer lifecycle, controlled from outside
    enum class State {
        Uncached = 0,
        InCache = 1,
        FailedToCache = 2,
        RemovedFromCache = 3,
        DeletedFromCache = 4,
        InGarbageList = 5,
    };
    State state; // public for logging/debugging purposes

    Layer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight);
    ~Layer();

    inline uint32_t getWidth() const {
        return texture.mWidth;
    }

    inline uint32_t getHeight() const {
        return texture.mHeight;
    }

    void setSize(uint32_t width, uint32_t height) {
        texture.updateSize(width, height, texture.format());
    }

    inline void setBlend(bool blend) {
        texture.blend = blend;
    }

    inline bool isBlend() const {
        return texture.blend;
    }

    inline void setForceFilter(bool forceFilter) {
        this->forceFilter = forceFilter;
    }

    inline bool getForceFilter() const {
        return forceFilter;
    }

    inline void setAlpha(int alpha) {
        this->alpha = alpha;
    }

    inline void setAlpha(int alpha, SkXfermode::Mode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() const {
        return alpha;
    }

    inline SkXfermode::Mode getMode() const {
        return mode;
    }

    inline GLuint getTextureId() const {
        return texture.id();
    }

    inline Texture& getTexture() {
        return texture;
    }

    inline GLenum getRenderTarget() const {
        return renderTarget;
    }

    inline void setRenderTarget(GLenum renderTarget) {
        this->renderTarget = renderTarget;
    }

    inline bool isRenderable() const {
        return renderTarget != GL_NONE;
    }

    void setWrap(GLenum wrap, bool bindTexture = false, bool force = false) {
        texture.setWrap(wrap, bindTexture, force, renderTarget);
    }

    void setFilter(GLenum filter, bool bindTexture = false, bool force = false) {
        texture.setFilter(filter, bindTexture, force, renderTarget);
    }

    inline SkColorFilter* getColorFilter() const {
        return colorFilter;
    }

    void setColorFilter(SkColorFilter* filter);

    void bindTexture() const;
    void generateTexture();

    /**
     * When the caller frees the texture itself, the caller
     * must call this method to tell this layer that it lost
     * the texture.
     */
    void clearTexture();

    inline mat4& getTexTransform() {
        return texTransform;
    }

    inline mat4& getTransform() {
        return transform;
    }

    /**
     * Posts a decStrong call to the appropriate thread.
     * Thread-safe.
     */
    void postDecStrong();

    /**
     * Lost the GL context but the layer is still around, mark it invalid internally
     * so the dtor knows not to do any GL work
     */
    void onGlContextLost();

    /**
     * Bounds of the layer.
     */
    Rect layer;
    /**
     * Texture coordinates of the layer.
     */
    Rect texCoords;
    /**
     * Clipping rectangle.
     */
    Rect clipRect;

private:
    Caches& caches;

    RenderState& renderState;

    /**
     * The texture backing this layer.
     */
    Texture texture;

    /**
     * Indicates the render target.
     */
    GLenum renderTarget = GL_TEXTURE_2D;

    /**
     * Color filter used to draw this layer. Optional.
     */
    SkColorFilter* colorFilter = nullptr;

    /**
     * Indicates raster data backing the layer is scaled, requiring filtration.
     */
    bool forceFilter = false;

    /**
     * Opacity of the layer.
     */
    int alpha = 255;

    /**
     * Blending mode of the layer.
     */
    SkXfermode::Mode mode = SkXfermode::kSrcOver_Mode;

    /**
     * Optional texture coordinates transform.
     */
    mat4 texTransform;

    /**
     * Optional transform.
     */
    mat4 transform;

}; // struct Layer

}; // namespace uirenderer
}; // namespace android
