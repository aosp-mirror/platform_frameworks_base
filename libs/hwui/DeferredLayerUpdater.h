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
#include <cutils/compiler.h>
#include <map>
#include <system/graphics.h>
#include <utils/StrongPointer.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "renderstate/RenderState.h"
#include "surfacetexture/SurfaceTexture.h"
#include "Layer.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

class RenderState;

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

    ANDROID_API void setSurfaceTexture(const sp<SurfaceTexture>& consumer) {
        if (consumer.get() != mSurfaceTexture.get()) {
            mSurfaceTexture = consumer;

            GLenum target = consumer->getCurrentTextureTarget();
            LOG_ALWAYS_FATAL_IF(target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES,
                                "set unsupported SurfaceTexture with target %x", target);
        }
    }

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
    RenderState& mRenderState;

    // Generic properties
    int mWidth = 0;
    int mHeight = 0;
    bool mBlend = false;
    sk_sp<SkColorFilter> mColorFilter;
    int mAlpha = 255;
    SkBlendMode mMode = SkBlendMode::kSrcOver;
    sp<SurfaceTexture> mSurfaceTexture;
    SkMatrix* mTransform;
    bool mGLContextAttached;
    bool mUpdateTexImage;

    Layer* mLayer;
};

} /* namespace uirenderer */
} /* namespace android */
