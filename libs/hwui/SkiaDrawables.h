/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Layer.h"
#include "RenderNode.h"

#include <SkCanvas.h>
#include <SkDrawable.h>
#include <SkMatrix.h>

#include <utils/RefBase.h>
#include <utils/FatVector.h>
#include <utils/Functor.h>

namespace android {

class Functor;

namespace uirenderer {


class RenderProperties;
class OffscreenBuffer;
class GlFunctorLifecycleListener;
class SkiaDisplayList;

/**
 * This drawable wraps a RenderNode and enables it to be recorded into a list
 * of Skia drawing commands.
 */
class RenderNodeDrawable : public SkDrawable {
public:
    explicit RenderNodeDrawable(RenderNode* node, SkCanvas* canvas)
            : mRenderNode(node)
            , mRecordedTransform(canvas->getTotalMatrix()) {}

    /**
     * The renderNode (and its properties) that is to be drawn
     */
    RenderNode* getRenderNode() const { return mRenderNode.get(); }

    /**
     * Returns the transform on the canvas at time of recording and is used for
     * computing total transform without rerunning DL contents.
     */
    const SkMatrix& getRecordedMatrix() const { return mRecordedTransform; }

protected:
    virtual SkRect onGetBounds() override {
        // We don't want to enable a record time quick reject because the properties
        // of the RenderNode may be updated on subsequent frames.
        return SkRect::MakeLargest();
    }
    virtual void onDraw(SkCanvas* canvas) override { /* TODO */ }

private:
    sp<RenderNode> mRenderNode;
    const SkMatrix mRecordedTransform;
};

/**
 * This drawable wraps a OpenGL functor enabling it to be recorded into a list
 * of Skia drawing commands.
 */
class GLFunctorDrawable : public SkDrawable {
public:
    GLFunctorDrawable(Functor* functor, GlFunctorLifecycleListener* listener, SkCanvas* canvas)
            : mFunctor(functor)
            , mListener(listener) {
        canvas->getClipBounds(&mBounds);
    }
    virtual ~GLFunctorDrawable() {}

    void syncFunctor() const { (*mFunctor)(DrawGlInfo::kModeSync, nullptr); }

 protected:
    virtual SkRect onGetBounds() override { return mBounds; }
    virtual void onDraw(SkCanvas* canvas) override { /* TODO */ }

 private:
     Functor* mFunctor;
     sp<GlFunctorLifecycleListener> mListener;
     SkRect mBounds;
};

}; // namespace uirenderer
}; // namespace android
