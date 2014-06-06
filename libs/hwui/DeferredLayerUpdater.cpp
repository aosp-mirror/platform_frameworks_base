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

namespace android {
namespace uirenderer {

static void defaultLayerDestroyer(Layer* layer) {
    Caches::getInstance().resourceCache.decrementRefcount(layer);
}

DeferredLayerUpdater::DeferredLayerUpdater(Layer* layer, LayerDestroyer destroyer)
        : mDisplayList(0)
        , mSurfaceTexture(0)
        , mTransform(0)
        , mNeedsGLContextAttach(false)
        , mUpdateTexImage(false)
        , mLayer(layer)
        , mCaches(Caches::getInstance())
        , mDestroyer(destroyer) {
    mWidth = mLayer->layer.getWidth();
    mHeight = mLayer->layer.getHeight();
    mBlend = mLayer->isBlend();
    mColorFilter = SkSafeRef(mLayer->getColorFilter());
    mAlpha = mLayer->getAlpha();
    mMode = mLayer->getMode();
    mDirtyRect.setEmpty();

    if (!mDestroyer) {
        mDestroyer = defaultLayerDestroyer;
    }
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    SkSafeUnref(mColorFilter);
    setTransform(0);
    mDestroyer(mLayer);
}

void DeferredLayerUpdater::setPaint(const SkPaint* paint) {
    OpenGLRenderer::getAlphaAndModeDirect(paint, &mAlpha, &mMode);
    SkColorFilter* colorFilter = (paint) ? paint->getColorFilter() : NULL;
    SkRefCnt_SafeAssign(mColorFilter, colorFilter);
}

void DeferredLayerUpdater::setDisplayList(RenderNode* displayList,
        int left, int top, int right, int bottom) {
    mDisplayList = displayList;
    if (mDirtyRect.isEmpty()) {
        mDirtyRect.set(left, top, right, bottom);
    } else {
        mDirtyRect.unionWith(Rect(left, top, right, bottom));
    }
}

bool DeferredLayerUpdater::apply(TreeInfo& info) {
    bool success = true;
    // These properties are applied the same to both layer types
    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mDisplayList.get()) {
        if (mWidth != mLayer->layer.getWidth() || mHeight != mLayer->layer.getHeight()) {
            success = LayerRenderer::resizeLayer(mLayer, mWidth, mHeight);
        }
        mLayer->setBlend(mBlend);
        // TODO: Use DamageAccumulator to get the damage area for the layer's
        // subtree to only update that part of the layer. Do this as part of
        // reworking layers to be a RenderProperty instead of a View-managed object
        mDirtyRect.set(0, 0, mWidth, mHeight);
        mDisplayList->prepareTree(info);
        mLayer->updateDeferred(mDisplayList.get(),
                mDirtyRect.left, mDirtyRect.top, mDirtyRect.right, mDirtyRect.bottom);
        mDirtyRect.setEmpty();
        mDisplayList = 0;
    } else if (mSurfaceTexture.get()) {
        if (mNeedsGLContextAttach) {
            mNeedsGLContextAttach = false;
            mSurfaceTexture->attachToContext(mLayer->getTexture());
        }
        if (mUpdateTexImage) {
            mUpdateTexImage = false;
            doUpdateTexImage();
        }
        if (mTransform) {
            mLayer->getTransform().load(*mTransform);
            setTransform(0);
        }
    }
    return success;
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
        if (buffer != NULL) {
            // force filtration if buffer size != layer size
            forceFilter = mWidth != buffer->getWidth()
                    || mHeight != buffer->getHeight();
        }

        #if DEBUG_RENDERER
        if (dropCounter > 0) {
            RENDERER_LOGD("Dropped %d frames on texture layer update", dropCounter);
        }
        #endif
        mSurfaceTexture->getTransformMatrix(transform);
        GLenum renderTarget = mSurfaceTexture->getCurrentTextureTarget();

        LayerRenderer::updateTextureLayer(mLayer, mWidth, mHeight,
                !mBlend, forceFilter, renderTarget, transform);
    }
}

} /* namespace uirenderer */
} /* namespace android */
