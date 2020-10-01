/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <SkColorFilter.h>
#include <SkImage.h>
#include <SkMatrix.h>
#include <android/hardware_buffer.h>
#include <cutils/compiler.h>
#include <android/surface_texture.h>

#include <map>
#include <memory>

#include "Layer.h"
#include "Rect.h"
#include "renderstate/RenderState.h"

namespace android {
namespace uirenderer {

class AutoBackendTextureRelease;
class RenderState;

typedef std::unique_ptr<ASurfaceTexture, decltype(&ASurfaceTexture_release)> AutoTextureRelease;

// Container to hold the properties a layer should be set to at the start
// of a render pass
class DeferredLayerUpdater : public VirtualLightRefBase, public IGpuContextCallback {
public:
    // Note that DeferredLayerUpdater assumes it is taking ownership of the layer
    // and will not call incrementRef on it as a result.
    ANDROID_API explicit DeferredLayerUpdater(RenderState& renderState);

    ANDROID_API ~DeferredLayerUpdater();

    ANDROID_API bool setSize(int width, int height) {
        if (mWidth != width || mHeight != height) {
            mWidth = width;
            mHeight = height;
            return true;
        }
        return false;
    }

    int getWidth() { return mWidth; }
    int getHeight() { return mHeight; }

    ANDROID_API bool setBlend(bool blend) {
        if (blend != mBlend) {
            mBlend = blend;
            return true;
        }
        return false;
    }

    ANDROID_API void setSurfaceTexture(AutoTextureRelease&& consumer);

    ANDROID_API void updateTexImage() { mUpdateTexImage = true; }

    ANDROID_API void setTransform(const SkMatrix* matrix) {
        delete mTransform;
        mTransform = matrix ? new SkMatrix(*matrix) : nullptr;
    }

    SkMatrix* getTransform() { return mTransform; }

    ANDROID_API void setPaint(const SkPaint* paint);

    void apply();

    Layer* backingLayer() { return mLayer; }

    void detachSurfaceTexture();

    void updateLayer(bool forceFilter, const SkMatrix& textureTransform,
                     const sk_sp<SkImage>& layerImage);

    void destroyLayer();

protected:
    void onContextDestroyed() override;

private:
    /**
     * ImageSlot contains the information and object references that
     * DeferredLayerUpdater maintains about a slot. Slot id comes from
     * ASurfaceTexture_dequeueBuffer. Usually there are at most 3 slots active at a time.
     */
    class ImageSlot {
    public:
        ~ImageSlot() { clear(); }

        sk_sp<SkImage> createIfNeeded(AHardwareBuffer* buffer, android_dataspace dataspace,
                                      bool forceCreate, GrContext* context);

    private:
        void clear();

        // the dataspace associated with the current image
        android_dataspace mDataspace = HAL_DATASPACE_UNKNOWN;

        AHardwareBuffer* mBuffer = nullptr;

        /**
         * mTextureRelease may outlive DeferredLayerUpdater, if the last ref is held by an SkImage.
         * DeferredLayerUpdater holds one ref to mTextureRelease, which is decremented by "clear".
         */
        AutoBackendTextureRelease* mTextureRelease = nullptr;
    };

    /**
     * DeferredLayerUpdater stores the SkImages that have been allocated by the BufferQueue
     * for each buffer slot.
     */
    std::map<int, ImageSlot> mImageSlots;

    RenderState& mRenderState;

    // Generic properties
    int mWidth = 0;
    int mHeight = 0;
    bool mBlend = false;
    sk_sp<SkColorFilter> mColorFilter;
    int mAlpha = 255;
    SkBlendMode mMode = SkBlendMode::kSrcOver;
    AutoTextureRelease mSurfaceTexture;
    SkMatrix* mTransform;
    bool mGLContextAttached;
    bool mUpdateTexImage;

    Layer* mLayer;
};

} /* namespace uirenderer */
} /* namespace android */
