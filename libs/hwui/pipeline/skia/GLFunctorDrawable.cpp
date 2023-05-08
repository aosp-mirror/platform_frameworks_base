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
#include <GrDirectContext.h>
#include <private/hwui/DrawGlInfo.h>
#include "FunctorDrawable.h"
#include "GrBackendSurface.h"
#include "RenderNode.h"
#include "SkAndroidFrameworkUtils.h"
#include "SkClipStack.h"
#include "SkRect.h"
#include "SkM44.h"
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include "include/gpu/GpuTypes.h" // from Skia
#include "utils/GLUtils.h"
#include <effects/GainmapRenderer.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

static void setScissor(int viewportHeight, const SkIRect& clip) {
    SkASSERT(!clip.isEmpty());
    // transform to Y-flipped GL space, and prevent negatives
    GLint y = viewportHeight - clip.fBottom;
    GLint height = (viewportHeight - clip.fTop) - y;
    glScissor(clip.fLeft, y, clip.width(), height);
}

static void GetFboDetails(SkCanvas* canvas, GLuint* outFboID, SkISize* outFboSize) {
    GrBackendRenderTarget renderTarget = canvas->topLayerBackendRenderTarget();
    GrGLFramebufferInfo fboInfo;
    LOG_ALWAYS_FATAL_IF(!renderTarget.getGLFramebufferInfo(&fboInfo),
        "getGLFrameBufferInfo failed");

    *outFboID = fboInfo.fFBOID;
    *outFboSize = renderTarget.dimensions();
}

void GLFunctorDrawable::onDraw(SkCanvas* canvas) {
    GrDirectContext* directContext = GrAsDirectContext(canvas->recordingContext());
    if (directContext == nullptr) {
        // We're dumping a picture, render a light-blue rectangle instead
        // TODO: Draw the WebView text on top? Seemingly complicated as SkPaint doesn't
        // seem to have a default typeface that works. We only ever use drawGlyphs, which
        // requires going through minikin & hwui's canvas which we don't have here.
        SkPaint paint;
        paint.setColor(0xFF81D4FA);
        canvas->drawRect(mBounds, paint);
        return;
    }

    // canvas may be an AlphaFilterCanvas, which is intended to draw with a
    // modified alpha. We do not have a way to do this without drawing into an
    // extra layer, which would have a performance cost. Draw directly into the
    // underlying gpu canvas. This matches prior behavior and the behavior in
    // Vulkan.
    {
        auto* gpuCanvas = SkAndroidFrameworkUtils::getBaseWrappedCanvas(canvas);
        LOG_ALWAYS_FATAL_IF(!gpuCanvas, "GLFunctorDrawable::onDraw is using an invalid canvas!");
        canvas = gpuCanvas;
    }

    // flush will create a GrRenderTarget if not already present.
    canvas->flush();

    GLuint fboID = 0;
    SkISize fboSize;
    GetFboDetails(canvas, &fboID, &fboSize);

    SkIRect surfaceBounds = canvas->topLayerBounds();
    SkIRect clipBounds = canvas->getDeviceClipBounds();
    SkM44 mat4(canvas->getLocalToDevice());
    SkRegion clipRegion;
    canvas->temporary_internal_getRgnClip(&clipRegion);

    sk_sp<SkSurface> tmpSurface;
    // we are in a state where there is an unclipped saveLayer
    if (fboID != 0 && !surfaceBounds.contains(clipBounds)) {
        // create an offscreen layer and clear it
        SkImageInfo surfaceInfo =
                canvas->imageInfo().makeWH(clipBounds.width(), clipBounds.height());
        tmpSurface =
                SkSurface::MakeRenderTarget(directContext, skgpu::Budgeted::kYes, surfaceInfo);
        tmpSurface->getCanvas()->clear(SK_ColorTRANSPARENT);

        GrGLFramebufferInfo fboInfo;
        if (!SkSurfaces::GetBackendRenderTarget(tmpSurface.get(),
                                                SkSurfaces::BackendHandleAccess::kFlushWrite)
                     .getGLFramebufferInfo(&fboInfo)) {
            ALOGW("Unable to extract renderTarget info from offscreen canvas; aborting GLFunctor");
            return;
        }

        fboSize = SkISize::Make(surfaceInfo.width(), surfaceInfo.height());
        fboID = fboInfo.fFBOID;

        // update the matrix and clip that we pass to the WebView to match the coordinates of
        // the offscreen layer
        mat4.preTranslate(-clipBounds.fLeft, -clipBounds.fTop);
        clipBounds.offsetTo(0, 0);
        clipRegion.translate(-surfaceBounds.fLeft, -surfaceBounds.fTop);

    } else if (fboID != 0) {
        // we are drawing into a (clipped) offscreen layer so we must update the clip and matrix
        // from device coordinates to the layer's coordinates
        clipBounds.offset(-surfaceBounds.fLeft, -surfaceBounds.fTop);
        mat4.preTranslate(-surfaceBounds.fLeft, -surfaceBounds.fTop);
    }

    DrawGlInfo info;
    info.clipLeft = clipBounds.fLeft;
    info.clipTop = clipBounds.fTop;
    info.clipRight = clipBounds.fRight;
    info.clipBottom = clipBounds.fBottom;
    info.isLayer = fboID != 0;
    info.width = fboSize.width();
    info.height = fboSize.height();
    mat4.getColMajor(&info.transform[0]);
    info.color_space_ptr = canvas->imageInfo().colorSpace();
    info.currentHdrSdrRatio = getTargetHdrSdrRatio(info.color_space_ptr);

    // ensure that the framebuffer that the webview will render into is bound before we clear
    // the stencil and/or draw the functor.
    glViewport(0, 0, info.width, info.height);
    glBindFramebuffer(GL_FRAMEBUFFER, fboID);

    // apply a simple clip with a scissor or a complex clip with a stencil
    bool clearStencilAfterFunctor = false;
    if (CC_UNLIKELY(clipRegion.isComplex())) {
        // clear the stencil
        // TODO: move stencil clear and canvas flush to SkAndroidFrameworkUtils::clipWithStencil
        glDisable(GL_SCISSOR_TEST);
        glStencilMask(0x1);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);

        // notify Skia that we just updated the FBO and stencil
        const uint32_t grState = kStencil_GrGLBackendState | kRenderTarget_GrGLBackendState;
        directContext->resetContext(grState);

        SkCanvas* tmpCanvas = canvas;
        if (tmpSurface) {
            tmpCanvas = tmpSurface->getCanvas();
            // set the clip on the new canvas
            tmpCanvas->clipRegion(clipRegion);
        }

        // GL ops get inserted here if previous flush is missing, which could dirty the stencil
        bool stencilWritten = SkAndroidFrameworkUtils::clipWithStencil(tmpCanvas);
        tmpCanvas->flush();  // need this flush for the single op that draws into the stencil

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

    // WebView may swallow GL errors, so catch them here
    GL_CHECKPOINT(LOW);
    mWebViewHandle->drawGl(info);

    if (clearStencilAfterFunctor) {
        // clear stencil buffer as it may be used by Skia
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glStencilMask(0x1);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
    }

    directContext->resetContext();

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
        tmpSurface->draw(canvas, deviceBounds.fLeft, deviceBounds.fTop);
    }
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
