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

#include <SkBlendMode.h>
#include <SkColorFilter.h>
#include <SkColorSpace.h>
#include <SkCanvas.h>
#include <SkPaint.h>
#include <SkImage.h>
#include <SkMatrix.h>
#include <system/graphics.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

class RenderState;

/**
 * A layer has dimensions and is backed by a backend specific texture or framebuffer.
 */
class Layer : public VirtualLightRefBase {
public:
    Layer(RenderState& renderState, sk_sp<SkColorFilter>, int alpha, SkBlendMode mode);

    ~Layer();

    uint32_t getWidth() const { return mWidth; }

    uint32_t getHeight() const { return mHeight; }

    void setSize(uint32_t width, uint32_t height) { mWidth = width; mHeight = height; }

    void setBlend(bool blend) { mBlend = blend; }

    bool isBlend() const { return mBlend; }

    inline void setForceFilter(bool forceFilter) { this->forceFilter = forceFilter; }

    inline bool getForceFilter() const { return forceFilter; }

    inline void setAlpha(int alpha) { this->alpha = alpha; }

    inline void setAlpha(int alpha, SkBlendMode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() const { return alpha; }

    SkBlendMode getMode() const;

    inline sk_sp<SkColorFilter> getColorFilter() const { return mColorFilter; }

    void setColorFilter(sk_sp<SkColorFilter> filter) { mColorFilter = filter; };

    inline SkMatrix& getTexTransform() { return texTransform; }

    inline SkMatrix& getTransform() { return transform; }

    /**
     * Posts a decStrong call to the appropriate thread.
     * Thread-safe.
     */
    void postDecStrong();

    inline void setImage(const sk_sp<SkImage>& image) { this->layerImage = image; }

    inline sk_sp<SkImage> getImage() const { return this->layerImage; }

    void draw(SkCanvas* canvas);

protected:

    RenderState& mRenderState;

private:
    /**
     * Color filter used to draw this layer. Optional.
     */
    sk_sp<SkColorFilter> mColorFilter;

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
    SkMatrix texTransform;

    /**
     * Optional transform.
     */
    SkMatrix transform;

    /**
     * An image backing the layer.
     */
    sk_sp<SkImage> layerImage;

    /**
     * layer width.
     */
    uint32_t mWidth = 0;

    /**
     * layer height.
     */
    uint32_t mHeight = 0;

    /**
     * enable blending
     */
    bool mBlend = false;

};  // struct Layer

}  // namespace uirenderer
}  // namespace android
