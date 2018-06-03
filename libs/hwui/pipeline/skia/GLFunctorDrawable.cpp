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
#include "RenderNode.h"
#include "SkAndroidFrameworkUtils.h"
#include "SkClipStack.h"
#include "SkRect.h"
#include "GrBackendSurface.h"
#include "GrRenderTarget.h"
#include "GrRenderTargetContext.h"
#include "GrGLTypes.h"

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

static bool GetFboDetails(SkCanvas* canvas, GLuint* outFboID, SkISize* outFboSize) {
    GrRenderTargetContext *renderTargetContext =
            canvas->internal_private_accessTopLayerRenderTargetContext();
    if (!renderTargetContext) {
        ALOGW("Unable to extract renderTarget info from canvas; aborting GLFunctor draw");
        return false;
    }

    GrRenderTarget *renderTarget = renderTargetContext->accessRenderTarget();
    if (!renderTarget) {
        ALOGW("Unable to extract renderTarget info from canvas; aborting GLFunctor draw");
        return false;
    }

    GrBackendRenderTarget backendTarget = renderTarget->getBackendRenderTarget();
    const GrGLFramebufferInfo* fboInfo = backendTarget.getGLFramebufferInfo();

    if (!fboInfo) {
        ALOGW("Unable to extract renderTarget info from canvas; aborting GLFunctor draw");
        return false;
    }

    *outFboID = fboInfo->fFBOID;
    *outFboSize = SkISize::Make(renderTargetContext->width(), renderTargetContext->height());
    return true;
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

    GLuint fboID = 0;
    SkISize fboSize;
    if (!GetFboDetails(canvas, &fboID, &fboSize)) {
        return;
    }

    SkIRect surfaceBounds = canvas->internal_private_getTopLayerBounds();
    SkIRect clipBounds = canvas->getDeviceClipBounds();
    SkMatrix44 mat4(canvas->getTotalMatrix());
    SkRegion clipRegion;
    canvas->temporary_internal_getRgnClip(&clipRegion);

    sk_sp<SkSurface> tmpSurface;
    // we are in a state where there is an unclipped saveLayer
    if (fboID != 0 && !surfaceBounds.contains(clipBounds)) {

        // create an offscreen layer and clear it
        SkImageInfo surfaceInfo = canvas->imageInfo().makeWH(clipBounds.width(), clipBounds.height());
        tmpSurface = SkSurface::MakeRenderTarget(canvas->getGrContext(), SkBudgeted::kYes,
                                                 surfaceInfo);
        tmpSurface->getCanvas()->clear(SK_ColorTRANSPARENT);

        GrBackendObject backendObject;
        if (!tmpSurface->getRenderTargetHandle(&backendObject, SkSurface::kFlushWrite_BackendHandleAccess)) {
            ALOGW("Unable to extract renderTarget info from offscreen canvas; aborting GLFunctor");
            return;
        }

        fboSize = SkISize::Make(surfaceInfo.width(), surfaceInfo.height());
        fboID = (GLuint)backendObject;

        // update the matrix and clip that we pass to the WebView to match the coordinates of
        // the offscreen layer
        mat4.preTranslate(-clipBounds.fLeft, -clipBounds.fTop, 0);
        clipBounds.offsetTo(0, 0);
        clipRegion.translate(-surfaceBounds.fLeft, -surfaceBounds.fTop);

    } else if (fboID != 0) {
        // we are drawing into a (clipped) offscreen layer so we must update the clip and matrix
        // from device coordinates to the layer's coordinates
        clipBounds.offset(-surfaceBounds.fLeft, -surfaceBounds.fTop);
        mat4.preTranslate(-surfaceBounds.fLeft, -surfaceBounds.fTop, 0);
    }

    DrawGlInfo info;
    info.clipLeft = clipBounds.fLeft;
    info.clipTop = clipBounds.fTop;
    info.clipRight = clipBounds.fRight;
    info.clipBottom = clipBounds.fBottom;
    info.isLayer = fboID != 0;
    info.width = fboSize.width();
    info.height = fboSize.height();
    mat4.asColMajorf(&info.transform[0]);

    // ensure that the framebuffer that the webview will render into is bound before we clear
    // the stencil and/or draw the functor.
    canvas->flush();
    glViewport(0, 0, info.width, info.height);
    glBindFramebuffer(GL_FRAMEBUFFER, fboID);

    // apply a simple clip with a scissor or a complex clip with a stencil
    bool clearStencilAfterFunctor = false;
    if (CC_UNLIKELY(clipRegion.isComplex())) {
        // clear the stencil
        //TODO: move stencil clear and canvas flush to SkAndroidFrameworkUtils::clipWithStencil
        glDisable(GL_SCISSOR_TEST);
        glStencilMask(0x1);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);

        // notify Skia that we just updated the FBO and stencil
        const uint32_t grState = kStencil_GrGLBackendState | kRenderTarget_GrGLBackendState;
        canvas->getGrContext()->resetContext(grState);

        SkCanvas* tmpCanvas = canvas;
        if (tmpSurface) {
            tmpCanvas = tmpSurface->getCanvas();
            // set the clip on the new canvas
            tmpCanvas->clipRegion(clipRegion);
        }

        // GL ops get inserted here if previous flush is missing, which could dirty the stencil
        bool stencilWritten = SkAndroidFrameworkUtils::clipWithStencil(tmpCanvas);
        tmpCanvas->flush(); //need this flush for the single op that draws into the stencil

        // ensure that the framebuffer that the webview will render into is bound before after we
        // draw into the stencil
        glViewport(0, 0, info.width, info.height);
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);

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
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
    } else {
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

    // if there were unclipped save layers involved we draw our offscreen surface to the canvas
    if (tmpSurface) {
        SkAutoCanvasRestore acr(canvas, true);
        SkMatrix invertedMatrix;
        if (!canvas->getTotalMatrix().invert(&invertedMatrix)) {
            ALOGW("Unable to extract invert canvas matrix; aborting GLFunctor draw");
            return;
        }
        canvas->concat(invertedMatrix);

        const SkIRect deviceBounds = canvas->getDeviceClipBounds();
        tmpSurface->draw(canvas, deviceBounds.fLeft, deviceBounds.fTop, nullptr);
    }
}

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
