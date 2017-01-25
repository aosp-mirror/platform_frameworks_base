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
#include "GlFunctorLifecycleListener.h"
#include "RenderNode.h"
#include "SkClipStack.h"
#include <private/hwui/DrawGlInfo.h>
#include <GrContext.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

GLFunctorDrawable::~GLFunctorDrawable() {
    if(mListener.get() != nullptr) {
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

    canvas->flush();

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

    //apply a simple clip with a scissor or a complex clip with a stencil
    SkRegion clipRegion;
    canvas->temporary_internal_getRgnClip(&clipRegion);
    if (CC_UNLIKELY(clipRegion.isComplex())) {
        //It is only a temporary solution to use a scissor to draw the stencil.
        //There is a bug 31489986 to implement efficiently non-rectangular clips.
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glStencilMask(0xff);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        SkRegion::Cliperator it(clipRegion, ibounds);
        while (!it.done()) {
            setScissor(info.height, it.rect());
            glClearStencil(0x1);
            glClear(GL_STENCIL_BUFFER_BIT);
            it.next();
        }
        glDisable(GL_SCISSOR_TEST);
        glStencilFunc(GL_EQUAL, 0x1, 0xff);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glEnable(GL_STENCIL_TEST);
    } else if (clipRegion.isEmpty()) {
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
    } else {
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_SCISSOR_TEST);
        setScissor(info.height, clipRegion.getBounds());
    }

    (*mFunctor)(DrawGlInfo::kModeDraw, &info);

    canvas->getGrContext()->resetContext();
 }

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
