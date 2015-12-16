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
#include "DeferredLayerUpdater.h"

#include "OpenGLRenderer.h"

#include "LayerRenderer.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderTask.h"

namespace android {
namespace uirenderer {

DeferredLayerUpdater::DeferredLayerUpdater(Layer* layer)
        : mSurfaceTexture(nullptr)
        , mTransform(nullptr)
        , mNeedsGLContextAttach(false)
        , mUpdateTexImage(false)
        , mLayer(layer)
        , mCaches(Caches::getInstance()) {
    mWidth = mLayer->layer.getWidth();
    mHeight = mLayer->layer.getHeight();
    mBlend = mLayer->isBlend();
    mColorFilter = SkSafeRef(mLayer->getColorFilter());
    mAlpha = mLayer->getAlpha();
    mMode = mLayer->getMode();
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    SkSafeUnref(mColorFilter);
    setTransform(nullptr);
    mLayer->postDecStrong();
    mLayer = nullptr;
}

void DeferredLayerUpdater::setPaint(const SkPaint* paint) {
    mAlpha = PaintUtils::getAlphaDirect(paint);
    mMode = PaintUtils::getXfermodeDirect(paint);
    SkColorFilter* colorFilter = (paint) ? paint->getColorFilter() : nullptr;
    SkRefCnt_SafeAssign(mColorFilter, colorFilter);
}

void DeferredLayerUpdater::apply() {
    // These properties are applied the same to both layer types
    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mSurfaceTexture.get()) {
        if (mNeedsGLContextAttach) {
            mNeedsGLContextAttach = false;
            mSurfaceTexture->attachToContext(mLayer->getTextureId());
        }
        if (mUpdateTexImage) {
            mUpdateTexImage = false;
            doUpdateTexImage();
        }
        if (mTransform) {
            mLayer->getTransform().load(*mTransform);
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::doUpdateTexImage() {
    if (mSurfaceTexture->updateTexImage() == NO_ERROR) {
        float transform[16];

        int64_t frameNumber = mSurfaceTexture->getFrameNumber();
        // If the GLConsumer queue is in synchronous mode, need to discard all
        // but latest frame, using the frame number to tell when we no longer
        // have newer frames to target. Since we can't tell which mode it is in,
        // do this unconditionally.
        int dropCounter = 0;
        while (mSurfaceTexture->updateTexImage() == NO_ERROR) {
            int64_t newFrameNumber = mSurfaceTexture->getFrameNumber();
            if (newFrameNumber == frameNumber) break;
            frameNumber = newFrameNumber;
            dropCounter++;
        }

        bool forceFilter = false;
        sp<GraphicBuffer> buffer = mSurfaceTexture->getCurrentBuffer();
        if (buffer != nullptr) {
            // force filtration if buffer size != layer size
            forceFilter = mWidth != static_cast<int>(buffer->getWidth())
                    || mHeight != static_cast<int>(buffer->getHeight());
        }

        #if DEBUG_RENDERER
        if (dropCounter > 0) {
            RENDERER_LOGD("Dropped %d frames on texture layer update", dropCounter);
        }
        #endif
        mSurfaceTexture->getTransformMatrix(transform);
        GLenum renderTarget = mSurfaceTexture->getCurrentTextureTarget();

        LOG_ALWAYS_FATAL_IF(renderTarget != GL_TEXTURE_2D && renderTarget != GL_TEXTURE_EXTERNAL_OES,
                "doUpdateTexImage target %x, 2d %x, EXT %x",
                renderTarget, GL_TEXTURE_2D, GL_TEXTURE_EXTERNAL_OES);
        LayerRenderer::updateTextureLayer(mLayer, mWidth, mHeight,
                !mBlend, forceFilter, renderTarget, transform);
    }
}

void DeferredLayerUpdater::detachSurfaceTexture() {
    if (mSurfaceTexture.get()) {
        status_t err = mSurfaceTexture->detachFromContext();
        if (err != 0) {
            // TODO: Elevate to fatal exception
            ALOGE("Failed to detach SurfaceTexture from context %d", err);
        }
        mSurfaceTexture = nullptr;
        mLayer->clearTexture();
    }
}

} /* namespace uirenderer */
} /* namespace android */
