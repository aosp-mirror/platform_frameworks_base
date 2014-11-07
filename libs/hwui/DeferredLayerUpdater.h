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
#ifndef DEFERREDLAYERUPDATE_H_
#define DEFERREDLAYERUPDATE_H_

#include <cutils/compiler.h>
#include <gui/GLConsumer.h>
#include <SkColorFilter.h>
#include <SkMatrix.h>
#include <utils/StrongPointer.h>

#include "Layer.h"
#include "Rect.h"
#include "RenderNode.h"
#include "renderthread/RenderThread.h"

namespace android {
namespace uirenderer {

// Container to hold the properties a layer should be set to at the start
// of a render pass
class DeferredLayerUpdater : public VirtualLightRefBase {
public:
    // Note that DeferredLayerUpdater assumes it is taking ownership of the layer
    // and will not call incrementRef on it as a result.
    ANDROID_API DeferredLayerUpdater(renderthread::RenderThread& thread, Layer* layer);
    ANDROID_API ~DeferredLayerUpdater();

    ANDROID_API bool setSize(uint32_t width, uint32_t height) {
        if (mWidth != width || mHeight != height) {
            mWidth = width;
            mHeight = height;
            return true;
        }
        return false;
    }

    ANDROID_API bool setBlend(bool blend) {
        if (blend != mBlend) {
            mBlend = blend;
            return true;
        }
        return false;
    }

    ANDROID_API void setSurfaceTexture(const sp<GLConsumer>& texture, bool needsAttach) {
        if (texture.get() != mSurfaceTexture.get()) {
            mNeedsGLContextAttach = needsAttach;
            mSurfaceTexture = texture;
        }
    }

    ANDROID_API void updateTexImage() {
        mUpdateTexImage = true;
    }

    ANDROID_API void setTransform(const SkMatrix* matrix) {
        delete mTransform;
        mTransform = matrix ? new SkMatrix(*matrix) : 0;
    }

    ANDROID_API void setPaint(const SkPaint* paint);

    ANDROID_API bool apply();

    Layer* backingLayer() {
        return mLayer;
    }

    ANDROID_API void detachSurfaceTexture();

private:
    // Generic properties
    uint32_t mWidth;
    uint32_t mHeight;
    bool mBlend;
    SkColorFilter* mColorFilter;
    int mAlpha;
    SkXfermode::Mode mMode;

    sp<GLConsumer> mSurfaceTexture;
    SkMatrix* mTransform;
    bool mNeedsGLContextAttach;
    bool mUpdateTexImage;

    Layer* mLayer;
    Caches& mCaches;
    renderthread::RenderThread& mRenderThread;

    void doUpdateTexImage();
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEFERREDLAYERUPDATE_H_ */
