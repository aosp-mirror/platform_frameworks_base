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

#include "renderstate/RenderState.h"
#include "utils/PaintUtils.h"

namespace android {
namespace uirenderer {

DeferredLayerUpdater::DeferredLayerUpdater(RenderState& renderState)
        : mRenderState(renderState)
        , mBlend(false)
        , mSurfaceTexture(nullptr)
        , mTransform(nullptr)
        , mGLContextAttached(false)
        , mUpdateTexImage(false)
        , mLayer(nullptr) {
    renderState.registerContextCallback(this);
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    setTransform(nullptr);
    mRenderState.removeContextCallback(this);
    destroyLayer();
}

void DeferredLayerUpdater::onContextDestroyed() {
    destroyLayer();
}

void DeferredLayerUpdater::destroyLayer() {
    if (!mLayer) {
        return;
    }

    if (mSurfaceTexture.get() && mGLContextAttached) {
        mSurfaceTexture->detachFromView();
        mGLContextAttached = false;
    }

    mLayer->postDecStrong();

    mLayer = nullptr;
}

void DeferredLayerUpdater::setPaint(const SkPaint* paint) {
    mAlpha = PaintUtils::getAlphaDirect(paint);
    mMode = PaintUtils::getBlendModeDirect(paint);
    if (paint) {
        mColorFilter = paint->refColorFilter();
    } else {
        mColorFilter.reset();
    }
}

void DeferredLayerUpdater::apply() {
    if (!mLayer) {
        mLayer = new Layer(mRenderState, mColorFilter, mAlpha, mMode);
    }

    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mSurfaceTexture.get()) {
        if (!mGLContextAttached) {
            mGLContextAttached = true;
            mUpdateTexImage = true;
            mSurfaceTexture->attachToView();
        }
        if (mUpdateTexImage) {
            mUpdateTexImage = false;
            sk_sp<SkImage> layerImage;
            SkMatrix textureTransform;
            bool queueEmpty = true;
            // If the SurfaceTexture queue is in synchronous mode, need to discard all
            // but latest frame. Since we can't tell which mode it is in,
            // do this unconditionally.
            do {
                layerImage = mSurfaceTexture->dequeueImage(textureTransform, &queueEmpty,
                        mRenderState);
            } while (layerImage.get() && (!queueEmpty));
            if (layerImage.get()) {
                // force filtration if buffer size != layer size
                bool forceFilter = mWidth != layerImage->width() || mHeight != layerImage->height();
                updateLayer(forceFilter, textureTransform, layerImage);
            }
        }

        if (mTransform) {
            mLayer->getTransform() = *mTransform;
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::updateLayer(bool forceFilter, const SkMatrix& textureTransform,
        const sk_sp<SkImage>& layerImage) {
    mLayer->setBlend(mBlend);
    mLayer->setForceFilter(forceFilter);
    mLayer->setSize(mWidth, mHeight);
    mLayer->getTexTransform() = textureTransform;
    mLayer->setImage(layerImage);
}

void DeferredLayerUpdater::detachSurfaceTexture() {
    if (mSurfaceTexture.get()) {
        destroyLayer();
        mSurfaceTexture = nullptr;
    }
}

} /* namespace uirenderer */
} /* namespace android */
