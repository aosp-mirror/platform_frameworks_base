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

#include <GpuMemoryTracker.h>
#include <utils/RefBase.h>

#include <SkColorFilter.h>
#include <SkColorSpace.h>
#include <SkBlendMode.h>
#include <SkPaint.h>

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

    Api getApi() const { return mApi; }

    ~Layer();

    virtual uint32_t getWidth() const = 0;

    virtual uint32_t getHeight() const = 0;

    virtual void setSize(uint32_t width, uint32_t height) = 0;

    virtual void setBlend(bool blend) = 0;

    virtual bool isBlend() const = 0;

    inline void setForceFilter(bool forceFilter) { this->forceFilter = forceFilter; }

    inline bool getForceFilter() const { return forceFilter; }

    inline void setAlpha(int alpha) { this->alpha = alpha; }

    inline void setAlpha(int alpha, SkBlendMode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() const { return alpha; }

    virtual SkBlendMode getMode() const { return mode; }

    inline SkColorFilter* getColorFilter() const { return mColorFilter.get(); }

    void setColorFilter(sk_sp<SkColorFilter> filter);

    void setDataSpace(android_dataspace dataspace);

    void setColorSpace(sk_sp<SkColorSpace> colorSpace);

    inline sk_sp<SkColorFilter> getColorSpaceWithFilter() const { return mColorSpaceWithFilter; }

    inline mat4& getTexTransform() { return texTransform; }

    inline mat4& getTransform() { return transform; }

    /**
     * Posts a decStrong call to the appropriate thread.
     * Thread-safe.
     */
    void postDecStrong();

    inline void setBufferSize(uint32_t width, uint32_t height) {
        mBufferWidth = width;
        mBufferHeight = height;
    }

    inline uint32_t getBufferWidth() const { return mBufferWidth; }

    inline uint32_t getBufferHeight() const { return mBufferHeight; }

protected:
    Layer(RenderState& renderState, Api api, sk_sp<SkColorFilter>, int alpha,
          SkBlendMode mode);

    RenderState& mRenderState;

    /**
     * Blending mode of the layer.
     */
    SkBlendMode mode;

private:
    void buildColorSpaceWithFilter();

    Api mApi;

    /**
     * Color filter used to draw this layer. Optional.
     */
    sk_sp<SkColorFilter> mColorFilter;

    /**
     * Colorspace of the contents of the layer. Optional.
     */
    android_dataspace mCurrentDataspace = HAL_DATASPACE_UNKNOWN;

    /**
     * A color filter that is the combination of the mColorFilter and mColorSpace. Optional.
     */
    sk_sp<SkColorFilter> mColorSpaceWithFilter;

    /**
     * Indicates raster data backing the layer is scaled, requiring filtration.
     */
    bool forceFilter = false;

    /**
     * Opacity of the layer.
     */
    int alpha;

    /**
     * Optional texture coordinates transform.
     */
    mat4 texTransform;

    /**
     * Optional transform.
     */
    mat4 transform;

    uint32_t mBufferWidth = 0;

    uint32_t mBufferHeight = 0;
};  // struct Layer

};  // namespace uirenderer
};  // namespace android
