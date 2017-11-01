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

#include <utils/RefBase.h>
#include <GpuMemoryTracker.h>

#include <SkPaint.h>
#include <SkBlendMode.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

class RenderState;

/**
 * A layer has dimensions and is backed by a backend specific texture or framebuffer.
 */
class Layer : public VirtualLightRefBase, GpuMemoryTracker {
public:
    enum class Api {
        OpenGL = 0,
        Vulkan = 1,
    };

    Api getApi() const {
        return mApi;
    }

    ~Layer();

    virtual uint32_t getWidth() const = 0;

    virtual uint32_t getHeight() const = 0;

    virtual void setSize(uint32_t width, uint32_t height) = 0;

    virtual void setBlend(bool blend) = 0;

    virtual bool isBlend() const = 0;

    inline void setForceFilter(bool forceFilter) {
        this->forceFilter = forceFilter;
    }

    inline bool getForceFilter() const {
        return forceFilter;
    }

    inline void setAlpha(int alpha) {
        this->alpha = alpha;
    }

    inline void setAlpha(int alpha, SkBlendMode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() const {
        return alpha;
    }

    inline SkBlendMode getMode() const {
        return mode;
    }

    inline SkColorFilter* getColorFilter() const {
        return colorFilter;
    }

    void setColorFilter(SkColorFilter* filter);

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

protected:
    Layer(RenderState& renderState, Api api, SkColorFilter* colorFilter, int alpha,
            SkBlendMode mode);

    RenderState& mRenderState;

private:
    Api mApi;

    /**
     * Color filter used to draw this layer. Optional.
     */
    SkColorFilter* colorFilter;

    /**
     * Indicates raster data backing the layer is scaled, requiring filtration.
     */
    bool forceFilter = false;

    /**
     * Opacity of the layer.
     */
    int alpha;

    /**
     * Blending mode of the layer.
     */
    SkBlendMode mode;

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
