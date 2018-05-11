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

#include "GLFunctorDrawable.h"
#include <GrContext.h>
#include <private/hwui/DrawGlInfo.h>
#include "GlFunctorLifecycleListener.h"
#include "Properties.h"
#include "RenderNode.h"
#include "SkAndroidFrameworkUtils.h"
#include "SkClipStack.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

GLFunctorDrawable::~GLFunctorDrawable() {
    if (mListener.get() != nullptr) {
        mListener->onGlFunctorReleased(mFunctor);
    }
}

void GLFunctorDrawable::syncFunctor() const {
    (*mFunctor)(DrawGlInfo::kModeSync, nullptr);
}

static void setScissor(int viewportHeight, const SkIRect& clip) {
    SkASSERT(!clip.isEmpty());
    // transform to Y-flipped GL space, and prevent negatives
    GLint y = viewportHeight - clip.fBottom;
    GLint height = (viewportHeight - clip.fTop) - y;
    glScissor(clip.fLeft, y, clip.width(), height);
}

void GLFunctorDrawable::onDraw(SkCanvas* canvas) {
    if (canvas->getGrContext() == nullptr) {
        SkDEBUGF(("Attempting to draw GLFunctor into an unsupported surface"));
        return;
    }

    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        canvas->clear(SK_ColorRED);
        return;
    }

    SkImageInfo canvasInfo = canvas->imageInfo();
    SkMatrix44 mat4(canvas->getTotalMatrix());

    SkIRect ibounds = canvas->getDeviceClipBounds();

    DrawGlInfo info;
    info.clipLeft = ibounds.fLeft;
    info.clipTop = ibounds.fTop;
    info.clipRight = ibounds.fRight;
    info.clipBottom = ibounds.fBottom;
    //   info.isLayer = hasLayer();
    info.isLayer = false;
    info.width = canvasInfo.width();
    info.height = canvasInfo.height();
    mat4.asColMajorf(&info.transform[0]);

    bool clearStencilAfterFunctor = false;

    // apply a simple clip with a scissor or a complex clip with a stencil
    SkRegion clipRegion;
    canvas->temporary_internal_getRgnClip(&clipRegion);
    if (CC_UNLIKELY(clipRegion.isComplex())) {
        glDisable(GL_SCISSOR_TEST);
        glStencilMask(0x1);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
        bool stencilWritten = SkAndroidFrameworkUtils::clipWithStencil(canvas);
        canvas->flush();
        if (stencilWritten) {
            glStencilMask(0x1);
            glStencilFunc(GL_EQUAL, 0x1, 0x1);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            clearStencilAfterFunctor = true;
            glEnable(GL_STENCIL_TEST);
        } else {
            glDisable(GL_STENCIL_TEST);
        }
    } else if (clipRegion.isEmpty()) {
        canvas->flush();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
    } else {
        canvas->flush();
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_SCISSOR_TEST);
        setScissor(info.height, clipRegion.getBounds());
    }

    (*mFunctor)(DrawGlInfo::kModeDraw, &info);

    if (clearStencilAfterFunctor) {
        // clear stencil buffer as it may be used by Skia
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glStencilMask(0x1);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
    }

    canvas->getGrContext()->resetContext();
}

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
