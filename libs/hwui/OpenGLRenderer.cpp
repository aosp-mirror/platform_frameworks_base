/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "OpenGLRenderer.h"

#include "DeferredDisplayList.h"
#include "DisplayListRenderer.h"
#include "GammaFontRenderer.h"
#include "Patch.h"
#include "PathTessellator.h"
#include "Properties.h"
#include "RenderNode.h"
#include "renderstate/RenderState.h"
#include "ShadowTessellator.h"
#include "SkiaShader.h"
#include "Vector.h"
#include "VertexBuffer.h"
#include "utils/GLUtils.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <SkCanvas.h>
#include <SkColor.h>
#include <SkShader.h>
#include <SkTypeface.h>

#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <private/hwui/DrawGlInfo.h>

#include <ui/Rect.h>

#if DEBUG_DETAILED_EVENTS
    #define EVENT_LOGD(...) eventMarkDEBUG(__VA_ARGS__)
#else
    #define EVENT_LOGD(...)
#endif

namespace android {
namespace uirenderer {

static GLenum getFilter(const SkPaint* paint) {
    if (!paint || paint->getFilterLevel() != SkPaint::kNone_FilterLevel) {
        return GL_LINEAR;
    }
    return GL_NEAREST;
}

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

/**
 * Structure mapping Skia xfermodes to OpenGL blending factors.
 */
struct Blender {
    SkXfermode::Mode mode;
    GLenum src;
    GLenum dst;
}; // struct Blender

// In this array, the index of each Blender equals the value of the first
// entry. For instance, gBlends[1] == gBlends[SkXfermode::kSrc_Mode]
static const Blender gBlends[] = {
    { SkXfermode::kClear_Mode,    GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrc_Mode,      GL_ONE,                 GL_ZERO },
    { SkXfermode::kDst_Mode,      GL_ZERO,                GL_ONE },
    { SkXfermode::kSrcOver_Mode,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstOver_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkXfermode::kSrcIn_Mode,    GL_DST_ALPHA,           GL_ZERO },
    { SkXfermode::kDstIn_Mode,    GL_ZERO,                GL_SRC_ALPHA },
    { SkXfermode::kSrcOut_Mode,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kDstOut_Mode,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrcATop_Mode,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstATop_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkXfermode::kXor_Mode,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kPlus_Mode,     GL_ONE,                 GL_ONE },
    { SkXfermode::kModulate_Mode, GL_ZERO,                GL_SRC_COLOR },
    { SkXfermode::kScreen_Mode,   GL_ONE,                 GL_ONE_MINUS_SRC_COLOR }
};

// This array contains the swapped version of each SkXfermode. For instance
// this array's SrcOver blending mode is actually DstOver. You can refer to
// createLayer() for more information on the purpose of this array.
static const Blender gBlendsSwap[] = {
    { SkXfermode::kClear_Mode,    GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kSrc_Mode,      GL_ZERO,                GL_ONE },
    { SkXfermode::kDst_Mode,      GL_ONE,                 GL_ZERO },
    { SkXfermode::kSrcOver_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkXfermode::kDstOver_Mode,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrcIn_Mode,    GL_ZERO,                GL_SRC_ALPHA },
    { SkXfermode::kDstIn_Mode,    GL_DST_ALPHA,           GL_ZERO },
    { SkXfermode::kSrcOut_Mode,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstOut_Mode,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kSrcATop_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkXfermode::kDstATop_Mode,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kXor_Mode,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kPlus_Mode,     GL_ONE,                 GL_ONE },
    { SkXfermode::kModulate_Mode, GL_DST_COLOR,           GL_ZERO },
    { SkXfermode::kScreen_Mode,   GL_ONE_MINUS_DST_COLOR, GL_ONE }
};

///////////////////////////////////////////////////////////////////////////////
// Functions
///////////////////////////////////////////////////////////////////////////////

template<typename T>
static inline T min(T a, T b) {
    return a < b ? a : b;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer(RenderState& renderState)
        : mState(*this)
        , mCaches(Caches::getInstance())
        , mExtensions(Extensions::getInstance())
        , mRenderState(renderState)
        , mFrameStarted(false)
        , mScissorOptimizationDisabled(false)
        , mSuppressTiling(false)
        , mFirstFrameAfterResize(true)
        , mDirty(false)
        , mLightCenter((Vector3){FLT_MIN, FLT_MIN, FLT_MIN})
        , mLightRadius(FLT_MIN)
        , mAmbientShadowAlpha(0)
        , mSpotShadowAlpha(0) {
    // *set* draw modifiers to be 0
    memset(&mDrawModifiers, 0, sizeof(mDrawModifiers));
    mDrawModifiers.mOverrideLayerAlpha = 1.0f;

    memcpy(mMeshVertices, kMeshVertices, sizeof(kMeshVertices));
}

OpenGLRenderer::~OpenGLRenderer() {
    // The context has already been destroyed at this point, do not call
    // GL APIs. All GL state should be kept in Caches.h
}

void OpenGLRenderer::initProperties() {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DISABLE_SCISSOR_OPTIMIZATION, property, "false")) {
        mScissorOptimizationDisabled = !strcasecmp(property, "true");
        INIT_LOGD("  Scissor optimization %s",
                mScissorOptimizationDisabled ? "disabled" : "enabled");
    } else {
        INIT_LOGD("  Scissor optimization enabled");
    }
}

void OpenGLRenderer::initLight(const Vector3& lightCenter, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    mLightCenter = lightCenter;
    mLightRadius = lightRadius;
    mAmbientShadowAlpha = ambientShadowAlpha;
    mSpotShadowAlpha = spotShadowAlpha;
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::onViewportInitialized() {
    glDisable(GL_DITHER);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    glEnableVertexAttribArray(Program::kBindingPosition);
    mFirstFrameAfterResize = true;
}

void OpenGLRenderer::setupFrameState(float left, float top,
        float right, float bottom, bool opaque) {
    mCaches.clearGarbage();
    mState.initializeSaveStack(left, top, right, bottom, mLightCenter);
    mOpaque = opaque;
    mTilingClip.set(left, top, right, bottom);
}

void OpenGLRenderer::startFrame() {
    if (mFrameStarted) return;
    mFrameStarted = true;

    mState.setDirtyClip(true);

    discardFramebuffer(mTilingClip.left, mTilingClip.top, mTilingClip.right, mTilingClip.bottom);

    mRenderState.setViewport(mState.getWidth(), mState.getHeight());

    // Functors break the tiling extension in pretty spectacular ways
    // This ensures we don't use tiling when a functor is going to be
    // invoked during the frame
    mSuppressTiling = mCaches.hasRegisteredFunctors()
            || mFirstFrameAfterResize;
    mFirstFrameAfterResize = false;

    startTilingCurrentClip(true);

    debugOverdraw(true, true);

    clear(mTilingClip.left, mTilingClip.top,
            mTilingClip.right, mTilingClip.bottom, mOpaque);
}

void OpenGLRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {

    setupFrameState(left, top, right, bottom, opaque);

    // Layer renderers will start the frame immediately
    // The framebuffer renderer will first defer the display list
    // for each layer and wait until the first drawing command
    // to start the frame
    if (currentSnapshot()->fbo == 0) {
        syncState();
        updateLayers();
    } else {
        startFrame();
    }
}

void OpenGLRenderer::discardFramebuffer(float left, float top, float right, float bottom) {
    // If we know that we are going to redraw the entire framebuffer,
    // perform a discard to let the driver know we don't need to preserve
    // the back buffer for this frame.
    if (mExtensions.hasDiscardFramebuffer() &&
            left <= 0.0f && top <= 0.0f && right >= mState.getWidth() && bottom >= mState.getHeight()) {
        const bool isFbo = onGetTargetFbo() == 0;
        const GLenum attachments[] = {
                isFbo ? (const GLenum) GL_COLOR_EXT : (const GLenum) GL_COLOR_ATTACHMENT0,
                isFbo ? (const GLenum) GL_STENCIL_EXT : (const GLenum) GL_STENCIL_ATTACHMENT };
        glDiscardFramebufferEXT(GL_FRAMEBUFFER, 1, attachments);
    }
}

void OpenGLRenderer::clear(float left, float top, float right, float bottom, bool opaque) {
    if (!opaque) {
        mRenderState.scissor().setEnabled(true);
        mRenderState.scissor().set(left, getViewportHeight() - bottom, right - left, bottom - top);
        glClear(GL_COLOR_BUFFER_BIT);
        mDirty = true;
        return;
    }

    mRenderState.scissor().reset();
}

void OpenGLRenderer::syncState() {
    if (mCaches.blend) {
        glEnable(GL_BLEND);
    } else {
        glDisable(GL_BLEND);
    }
}

void OpenGLRenderer::startTilingCurrentClip(bool opaque, bool expand) {
    if (!mSuppressTiling) {
        const Snapshot* snapshot = currentSnapshot();

        const Rect* clip = &mTilingClip;
        if (snapshot->flags & Snapshot::kFlagFboTarget) {
            clip = &(snapshot->layer->clipRect);
        }

        startTiling(*clip, getViewportHeight(), opaque, expand);
    }
}

void OpenGLRenderer::startTiling(const Rect& clip, int windowHeight, bool opaque, bool expand) {
    if (!mSuppressTiling) {
        if(expand) {
            // Expand the startTiling region by 1
            int leftNotZero = (clip.left > 0) ? 1 : 0;
            int topNotZero = (windowHeight - clip.bottom > 0) ? 1 : 0;

            mCaches.startTiling(
                clip.left - leftNotZero,
                windowHeight - clip.bottom - topNotZero,
                clip.right - clip.left + leftNotZero + 1,
                clip.bottom - clip.top + topNotZero + 1,
                opaque);
        } else {
            mCaches.startTiling(clip.left, windowHeight - clip.bottom,
                clip.right - clip.left, clip.bottom - clip.top, opaque);
        }
    }
}

void OpenGLRenderer::endTiling() {
    if (!mSuppressTiling) mCaches.endTiling();
}

bool OpenGLRenderer::finish() {
    renderOverdraw();
    endTiling();
    mTempPaths.clear();

    // When finish() is invoked on FBO 0 we've reached the end
    // of the current frame
    if (onGetTargetFbo() == 0) {
        mCaches.pathCache.trim();
        mCaches.tessellationCache.trim();
    }

    if (!suppressErrorChecks()) {
#if DEBUG_OPENGL
        GLUtils::dumpGLErrors();
#endif

#if DEBUG_MEMORY_USAGE
        mCaches.dumpMemoryUsage();
#else
        if (mCaches.getDebugLevel() & kDebugMemory) {
            mCaches.dumpMemoryUsage();
        }
#endif
    }

    mFrameStarted = false;

    return reportAndClearDirty();
}

void OpenGLRenderer::resumeAfterLayer() {
    mRenderState.setViewport(getViewportWidth(), getViewportHeight());
    mRenderState.bindFramebuffer(currentSnapshot()->fbo);
    debugOverdraw(true, false);

    mRenderState.scissor().reset();
    dirtyClip();
}

void OpenGLRenderer::callDrawGLFunction(Functor* functor, Rect& dirty) {
    if (mState.currentlyIgnored()) return;

    Rect clip(mState.currentClipRect());
    clip.snapToPixelBoundaries();

    // Since we don't know what the functor will draw, let's dirty
    // the entire clip region
    if (hasLayer()) {
        dirtyLayerUnchecked(clip, getRegion());
    }

    DrawGlInfo info;
    info.clipLeft = clip.left;
    info.clipTop = clip.top;
    info.clipRight = clip.right;
    info.clipBottom = clip.bottom;
    info.isLayer = hasLayer();
    info.width = getViewportWidth();
    info.height = getViewportHeight();
    currentTransform()->copyTo(&info.transform[0]);

    bool prevDirtyClip = mState.getDirtyClip();
    // setup GL state for functor
    if (mState.getDirtyClip()) {
        setStencilFromClip(); // can issue draws, so must precede enableScissor()/interrupt()
    }
    if (mRenderState.scissor().setEnabled(true) || prevDirtyClip) {
        setScissorFromClip();
    }

    mRenderState.invokeFunctor(functor, DrawGlInfo::kModeDraw, &info);
    // Scissor may have been modified, reset dirty clip
    dirtyClip();

    mDirty = true;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::eventMarkDEBUG(const char* fmt, ...) const {
#if DEBUG_DETAILED_EVENTS
    const int BUFFER_SIZE = 256;
    va_list ap;
    char buf[BUFFER_SIZE];

    va_start(ap, fmt);
    vsnprintf(buf, BUFFER_SIZE, fmt, ap);
    va_end(ap);

    eventMark(buf);
#endif
}


void OpenGLRenderer::eventMark(const char* name) const {
    mCaches.eventMark(0, name);
}

void OpenGLRenderer::startMark(const char* name) const {
    mCaches.startMark(0, name);
}

void OpenGLRenderer::endMark() const {
    mCaches.endMark();
}

void OpenGLRenderer::debugOverdraw(bool enable, bool clear) {
    mRenderState.debugOverdraw(enable, clear);
}

void OpenGLRenderer::renderOverdraw() {
    if (mCaches.debugOverdraw && onGetTargetFbo() == 0) {
        const Rect* clip = &mTilingClip;

        mRenderState.scissor().setEnabled(true);
        mRenderState.scissor().set(clip->left,
                mState.firstSnapshot()->getViewportHeight() - clip->bottom,
                clip->right - clip->left,
                clip->bottom - clip->top);

        // 1x overdraw
        mRenderState.stencil().enableDebugTest(2);
        drawColor(mCaches.getOverdrawColor(1), SkXfermode::kSrcOver_Mode);

        // 2x overdraw
        mRenderState.stencil().enableDebugTest(3);
        drawColor(mCaches.getOverdrawColor(2), SkXfermode::kSrcOver_Mode);

        // 3x overdraw
        mRenderState.stencil().enableDebugTest(4);
        drawColor(mCaches.getOverdrawColor(3), SkXfermode::kSrcOver_Mode);

        // 4x overdraw and higher
        mRenderState.stencil().enableDebugTest(4, true);
        drawColor(mCaches.getOverdrawColor(4), SkXfermode::kSrcOver_Mode);

        mRenderState.stencil().disable();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::updateLayer(Layer* layer, bool inFrame) {
    if (layer->deferredUpdateScheduled && layer->renderer
            && layer->renderNode.get() && layer->renderNode->isRenderable()) {

        if (inFrame) {
            endTiling();
            debugOverdraw(false, false);
        }

        if (CC_UNLIKELY(inFrame || mCaches.drawDeferDisabled)) {
            layer->render(*this);
        } else {
            layer->defer(*this);
        }

        if (inFrame) {
            resumeAfterLayer();
            startTilingCurrentClip();
        }

        layer->debugDrawUpdate = mCaches.debugLayersUpdates;
        layer->hasDrawnSinceUpdate = false;

        return true;
    }

    return false;
}

void OpenGLRenderer::updateLayers() {
    // If draw deferring is enabled this method will simply defer
    // the display list of each individual layer. The layers remain
    // in the layer updates list which will be cleared by flushLayers().
    int count = mLayerUpdates.size();
    if (count > 0) {
        if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
            startMark("Layer Updates");
        } else {
            startMark("Defer Layer Updates");
        }

        // Note: it is very important to update the layers in order
        for (int i = 0; i < count; i++) {
            Layer* layer = mLayerUpdates.itemAt(i).get();
            updateLayer(layer, false);
        }

        if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
            mLayerUpdates.clear();
            mRenderState.bindFramebuffer(onGetTargetFbo());
        }
        endMark();
    }
}

void OpenGLRenderer::flushLayers() {
    int count = mLayerUpdates.size();
    if (count > 0) {
        startMark("Apply Layer Updates");

        // Note: it is very important to update the layers in order
        for (int i = 0; i < count; i++) {
            mLayerUpdates.itemAt(i)->flush();
        }

        mLayerUpdates.clear();
        mRenderState.bindFramebuffer(onGetTargetFbo());

        endMark();
    }
}

void OpenGLRenderer::pushLayerUpdate(Layer* layer) {
    if (layer) {
        // Make sure we don't introduce duplicates.
        // SortedVector would do this automatically but we need to respect
        // the insertion order. The linear search is not an issue since
        // this list is usually very short (typically one item, at most a few)
        for (int i = mLayerUpdates.size() - 1; i >= 0; i--) {
            if (mLayerUpdates.itemAt(i) == layer) {
                return;
            }
        }
        mLayerUpdates.push_back(layer);
    }
}

void OpenGLRenderer::cancelLayerUpdate(Layer* layer) {
    if (layer) {
        for (int i = mLayerUpdates.size() - 1; i >= 0; i--) {
            if (mLayerUpdates.itemAt(i) == layer) {
                mLayerUpdates.removeAt(i);
                break;
            }
        }
    }
}

void OpenGLRenderer::flushLayerUpdates() {
    ATRACE_NAME("Update HW Layers");
    syncState();
    updateLayers();
    flushLayers();
    // Wait for all the layer updates to be executed
    glFinish();
}

void OpenGLRenderer::markLayersAsBuildLayers() {
    for (size_t i = 0; i < mLayerUpdates.size(); i++) {
        mLayerUpdates[i]->wasBuildLayered = true;
    }
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {
    bool restoreViewport = removed.flags & Snapshot::kFlagIsFboLayer;
    bool restoreClip = removed.flags & Snapshot::kFlagClipSet;
    bool restoreLayer = removed.flags & Snapshot::kFlagIsLayer;

    if (restoreViewport) {
        mRenderState.setViewport(getViewportWidth(), getViewportHeight());
    }

    if (restoreClip) {
        dirtyClip();
    }

    if (restoreLayer) {
        endMark(); // Savelayer
        ATRACE_END(); // SaveLayer
        startMark("ComposeLayer");
        composeLayer(removed, restored);
        endMark();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, int flags, const SkPath* convexMask) {
    // force matrix/clip isolation for layer
    flags |= SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag;

    const int count = mState.saveSnapshot(flags);

    if (!mState.currentlyIgnored()) {
        createLayer(left, top, right, bottom, paint, flags, convexMask);
    }

    return count;
}

void OpenGLRenderer::calculateLayerBoundsAndClip(Rect& bounds, Rect& clip, bool fboLayer) {
    const Rect untransformedBounds(bounds);

    currentTransform()->mapRect(bounds);

    // Layers only make sense if they are in the framebuffer's bounds
    if (bounds.intersect(mState.currentClipRect())) {
        // We cannot work with sub-pixels in this case
        bounds.snapToPixelBoundaries();

        // When the layer is not an FBO, we may use glCopyTexImage so we
        // need to make sure the layer does not extend outside the bounds
        // of the framebuffer
        const Snapshot& previous = *(currentSnapshot()->previous);
        Rect previousViewport(0, 0, previous.getViewportWidth(), previous.getViewportHeight());
        if (!bounds.intersect(previousViewport)) {
            bounds.setEmpty();
        } else if (fboLayer) {
            clip.set(bounds);
            mat4 inverse;
            inverse.loadInverse(*currentTransform());
            inverse.mapRect(clip);
            clip.snapToPixelBoundaries();
            if (clip.intersect(untransformedBounds)) {
                clip.translate(-untransformedBounds.left, -untransformedBounds.top);
                bounds.set(untransformedBounds);
            } else {
                clip.setEmpty();
            }
        }
    } else {
        bounds.setEmpty();
    }
}

void OpenGLRenderer::updateSnapshotIgnoreForLayer(const Rect& bounds, const Rect& clip,
        bool fboLayer, int alpha) {
    if (bounds.isEmpty() || bounds.getWidth() > mCaches.maxTextureSize ||
            bounds.getHeight() > mCaches.maxTextureSize ||
            (fboLayer && clip.isEmpty())) {
        writableSnapshot()->empty = fboLayer;
    } else {
        writableSnapshot()->invisible = writableSnapshot()->invisible || (alpha <= 0 && fboLayer);
    }
}

int OpenGLRenderer::saveLayerDeferred(float left, float top, float right, float bottom,
        const SkPaint* paint, int flags) {
    const int count = mState.saveSnapshot(flags);

    if (!mState.currentlyIgnored() && (flags & SkCanvas::kClipToLayer_SaveFlag)) {
        // initialize the snapshot as though it almost represents an FBO layer so deferred draw
        // operations will be able to store and restore the current clip and transform info, and
        // quick rejection will be correct (for display lists)

        Rect bounds(left, top, right, bottom);
        Rect clip;
        calculateLayerBoundsAndClip(bounds, clip, true);
        updateSnapshotIgnoreForLayer(bounds, clip, true, getAlphaDirect(paint));

        if (!mState.currentlyIgnored()) {
            writableSnapshot()->resetTransform(-bounds.left, -bounds.top, 0.0f);
            writableSnapshot()->resetClip(clip.left, clip.top, clip.right, clip.bottom);
            writableSnapshot()->initializeViewport(bounds.getWidth(), bounds.getHeight());
            writableSnapshot()->roundRectClipState = nullptr;
        }
    }

    return count;
}

/**
 * Layers are viewed by Skia are slightly different than layers in image editing
 * programs (for instance.) When a layer is created, previously created layers
 * and the frame buffer still receive every drawing command. For instance, if a
 * layer is created and a shape intersecting the bounds of the layers and the
 * framebuffer is draw, the shape will be drawn on both (unless the layer was
 * created with the SkCanvas::kClipToLayer_SaveFlag flag.)
 *
 * A way to implement layers is to create an FBO for each layer, backed by an RGBA
 * texture. Unfortunately, this is inefficient as it requires every primitive to
 * be drawn n + 1 times, where n is the number of active layers. In practice this
 * means, for every primitive:
 *   - Switch active frame buffer
 *   - Change viewport, clip and projection matrix
 *   - Issue the drawing
 *
 * Switching rendering target n + 1 times per drawn primitive is extremely costly.
 * To avoid this, layers are implemented in a different way here, at least in the
 * general case. FBOs are used, as an optimization, when the "clip to layer" flag
 * is set. When this flag is set we can redirect all drawing operations into a
 * single FBO.
 *
 * This implementation relies on the frame buffer being at least RGBA 8888. When
 * a layer is created, only a texture is created, not an FBO. The content of the
 * frame buffer contained within the layer's bounds is copied into this texture
 * using glCopyTexImage2D(). The layer's region is then cleared(1) in the frame
 * buffer and drawing continues as normal. This technique therefore treats the
 * frame buffer as a scratch buffer for the layers.
 *
 * To compose the layers back onto the frame buffer, each layer texture
 * (containing the original frame buffer data) is drawn as a simple quad over
 * the frame buffer. The trick is that the quad is set as the composition
 * destination in the blending equation, and the frame buffer becomes the source
 * of the composition.
 *
 * Drawing layers with an alpha value requires an extra step before composition.
 * An empty quad is drawn over the layer's region in the frame buffer. This quad
 * is drawn with the rgba color (0,0,0,alpha). The alpha value offered by the
 * quad is used to multiply the colors in the frame buffer. This is achieved by
 * changing the GL blend functions for the GL_FUNC_ADD blend equation to
 * GL_ZERO, GL_SRC_ALPHA.
 *
 * Because glCopyTexImage2D() can be slow, an alternative implementation might
 * be use to draw a single clipped layer. The implementation described above
 * is correct in every case.
 *
 * (1) The frame buffer is actually not cleared right away. To allow the GPU
 *     to potentially optimize series of calls to glCopyTexImage2D, the frame
 *     buffer is left untouched until the first drawing operation. Only when
 *     something actually gets drawn are the layers regions cleared.
 */
bool OpenGLRenderer::createLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, int flags, const SkPath* convexMask) {
    LAYER_LOGD("Requesting layer %.2fx%.2f", right - left, bottom - top);
    LAYER_LOGD("Layer cache size = %d", mCaches.layerCache.getSize());

    const bool fboLayer = flags & SkCanvas::kClipToLayer_SaveFlag;

    // Window coordinates of the layer
    Rect clip;
    Rect bounds(left, top, right, bottom);
    calculateLayerBoundsAndClip(bounds, clip, fboLayer);
    updateSnapshotIgnoreForLayer(bounds, clip, fboLayer, getAlphaDirect(paint));

    // Bail out if we won't draw in this snapshot
    if (mState.currentlyIgnored()) {
        return false;
    }

    mCaches.activeTexture(0);
    Layer* layer = mCaches.layerCache.get(mRenderState, bounds.getWidth(), bounds.getHeight());
    if (!layer) {
        return false;
    }

    layer->setPaint(paint);
    layer->layer.set(bounds);
    layer->texCoords.set(0.0f, bounds.getHeight() / float(layer->getHeight()),
            bounds.getWidth() / float(layer->getWidth()), 0.0f);

    layer->setBlend(true);
    layer->setDirty(false);
    layer->setConvexMask(convexMask); // note: the mask must be cleared before returning to the cache

    // Save the layer in the snapshot
    writableSnapshot()->flags |= Snapshot::kFlagIsLayer;
    writableSnapshot()->layer = layer;

    ATRACE_FORMAT_BEGIN("%ssaveLayer %ux%u",
            fboLayer ? "" : "unclipped ",
            layer->getWidth(), layer->getHeight());
    startMark("SaveLayer");
    if (fboLayer) {
        return createFboLayer(layer, bounds, clip);
    } else {
        // Copy the framebuffer into the layer
        layer->bindTexture();
        if (!bounds.isEmpty()) {
            if (layer->isEmpty()) {
                // Workaround for some GL drivers. When reading pixels lying outside
                // of the window we should get undefined values for those pixels.
                // Unfortunately some drivers will turn the entire target texture black
                // when reading outside of the window.
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, layer->getWidth(), layer->getHeight(),
                        0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
                layer->setEmpty(false);
            }

            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                    bounds.left, getViewportHeight() - bounds.bottom,
                    bounds.getWidth(), bounds.getHeight());

            // Enqueue the buffer coordinates to clear the corresponding region later
            mLayers.push_back(Rect(bounds));
        }
    }

    return true;
}

bool OpenGLRenderer::createFboLayer(Layer* layer, Rect& bounds, Rect& clip) {
    layer->clipRect.set(clip);
    layer->setFbo(mCaches.fboCache.get());

    writableSnapshot()->region = &writableSnapshot()->layer->region;
    writableSnapshot()->flags |= Snapshot::kFlagFboTarget | Snapshot::kFlagIsFboLayer;
    writableSnapshot()->fbo = layer->getFbo();
    writableSnapshot()->resetTransform(-bounds.left, -bounds.top, 0.0f);
    writableSnapshot()->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    writableSnapshot()->initializeViewport(bounds.getWidth(), bounds.getHeight());
    writableSnapshot()->roundRectClipState = nullptr;

    endTiling();
    debugOverdraw(false, false);
    // Bind texture to FBO
    mRenderState.bindFramebuffer(layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->allocateTexture();
        layer->setEmpty(false);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

    // Expand the startTiling region by 1
    startTilingCurrentClip(true, true);

    // Clear the FBO, expand the clear region by 1 to get nice bilinear filtering
    mRenderState.scissor().setEnabled(true);
    mRenderState.scissor().set(clip.left - 1.0f, bounds.getHeight() - clip.bottom - 1.0f,
            clip.getWidth() + 2.0f, clip.getHeight() + 2.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    dirtyClip();

    // Change the ortho projection
    mRenderState.setViewport(bounds.getWidth(), bounds.getHeight());
    return true;
}

/**
 * Read the documentation of createLayer() before doing anything in this method.
 */
void OpenGLRenderer::composeLayer(const Snapshot& removed, const Snapshot& restored) {
    if (!removed.layer) {
        ALOGE("Attempting to compose a layer that does not exist");
        return;
    }

    Layer* layer = removed.layer;
    const Rect& rect = layer->layer;
    const bool fboLayer = removed.flags & Snapshot::kFlagIsFboLayer;

    bool clipRequired = false;
    mState.calculateQuickRejectForScissor(rect.left, rect.top, rect.right, rect.bottom,
            &clipRequired, nullptr, false); // safely ignore return, should never be rejected
    mRenderState.scissor().setEnabled(mScissorOptimizationDisabled || clipRequired);

    if (fboLayer) {
        endTiling();

        // Detach the texture from the FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);

        layer->removeFbo(false);

        // Unbind current FBO and restore previous one
        mRenderState.bindFramebuffer(restored.fbo);
        debugOverdraw(true, false);

        startTilingCurrentClip();
    }

    if (!fboLayer && layer->getAlpha() < 255) {
        SkPaint layerPaint;
        layerPaint.setAlpha(layer->getAlpha());
        layerPaint.setXfermodeMode(SkXfermode::kDstIn_Mode);
        layerPaint.setColorFilter(layer->getColorFilter());

        drawColorRect(rect.left, rect.top, rect.right, rect.bottom, &layerPaint, true);
        // Required below, composeLayerRect() will divide by 255
        layer->setAlpha(255);
    }

    mRenderState.meshState().unbindMeshBuffer();

    mCaches.activeTexture(0);

    // When the layer is stored in an FBO, we can save a bit of fillrate by
    // drawing only the dirty region
    if (fboLayer) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom, *restored.transform);
        composeLayerRegion(layer, rect);
    } else if (!rect.isEmpty()) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom);

        save(0);
        // the layer contains screen buffer content that shouldn't be alpha modulated
        // (and any necessary alpha modulation was handled drawing into the layer)
        writableSnapshot()->alpha = 1.0f;
        composeLayerRect(layer, rect, true);
        restore();
    }

    dirtyClip();

    // Failing to add the layer to the cache should happen only if the layer is too large
    layer->setConvexMask(nullptr);
    if (!mCaches.layerCache.put(layer)) {
        LAYER_LOGD("Deleting layer");
        layer->decStrong(nullptr);
    }
}

void OpenGLRenderer::drawTextureLayer(Layer* layer, const Rect& rect) {
    float alpha = getLayerAlpha(layer);

    setupDraw();
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawWithTexture();
    } else {
        setupDrawWithExternalTexture();
    }
    setupDrawTextureTransform();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter(layer->getColorFilter());
    setupDrawBlending(layer);
    setupDrawProgram();
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(layer->getColorFilter());
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawTexture(layer->getTexture());
    } else {
        setupDrawExternalTexture(layer->getTexture());
    }
    if (currentTransform()->isPureTranslate() &&
            !layer->getForceFilter() &&
            layer->getWidth() == (uint32_t) rect.getWidth() &&
            layer->getHeight() == (uint32_t) rect.getHeight()) {
        const float x = (int) floorf(rect.left + currentTransform()->getTranslateX() + 0.5f);
        const float y = (int) floorf(rect.top + currentTransform()->getTranslateY() + 0.5f);

        layer->setFilter(GL_NEAREST);
        setupDrawModelView(kModelViewMode_TranslateAndScale, false,
                x, y, x + rect.getWidth(), y + rect.getHeight(), true);
    } else {
        layer->setFilter(GL_LINEAR);
        setupDrawModelView(kModelViewMode_TranslateAndScale, false,
                rect.left, rect.top, rect.right, rect.bottom);
    }
    setupDrawTextureTransformUniforms(layer->getTexTransform());
    setupDrawMesh(&mMeshVertices[0].x, &mMeshVertices[0].u);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, kMeshCount);
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect, bool swap) {
    if (layer->isTextureLayer()) {
        EVENT_LOGD("composeTextureLayerRect");
        resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);
        drawTextureLayer(layer, rect);
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    } else {
        EVENT_LOGD("composeHardwareLayerRect");
        const Rect& texCoords = layer->texCoords;
        resetDrawTextureTexCoords(texCoords.left, texCoords.top,
                texCoords.right, texCoords.bottom);

        float x = rect.left;
        float y = rect.top;
        bool simpleTransform = currentTransform()->isPureTranslate() &&
                layer->getWidth() == (uint32_t) rect.getWidth() &&
                layer->getHeight() == (uint32_t) rect.getHeight();

        if (simpleTransform) {
            // When we're swapping, the layer is already in screen coordinates
            if (!swap) {
                x = (int) floorf(rect.left + currentTransform()->getTranslateX() + 0.5f);
                y = (int) floorf(rect.top + currentTransform()->getTranslateY() + 0.5f);
            }

            layer->setFilter(GL_NEAREST, true);
        } else {
            layer->setFilter(GL_LINEAR, true);
        }

        SkPaint layerPaint;
        layerPaint.setAlpha(getLayerAlpha(layer) * 255);
        layerPaint.setXfermodeMode(layer->getMode());
        layerPaint.setColorFilter(layer->getColorFilter());

        bool blend = layer->isBlend() || getLayerAlpha(layer) < 1.0f;
        drawTextureMesh(x, y, x + rect.getWidth(), y + rect.getHeight(),
                layer->getTexture(), &layerPaint, blend,
                &mMeshVertices[0].x, &mMeshVertices[0].u,
                GL_TRIANGLE_STRIP, kMeshCount, swap, swap || simpleTransform);

        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    }
}

/**
 * Issues the command X, and if we're composing a save layer to the fbo or drawing a newly updated
 * hardware layer with overdraw debug on, draws again to the stencil only, so that these draw
 * operations are correctly counted twice for overdraw. NOTE: assumes composeLayerRegion only used
 * by saveLayer's restore
 */
#define DRAW_DOUBLE_STENCIL_IF(COND, DRAW_COMMAND) {                               \
        DRAW_COMMAND;                                                              \
        if (CC_UNLIKELY(mCaches.debugOverdraw && onGetTargetFbo() == 0 && COND)) { \
            glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);                   \
            DRAW_COMMAND;                                                          \
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);                       \
        }                                                                          \
    }

#define DRAW_DOUBLE_STENCIL(DRAW_COMMAND) DRAW_DOUBLE_STENCIL_IF(true, DRAW_COMMAND)

// This class is purely for inspection. It inherits from SkShader, but Skia does not know how to
// use it. The OpenGLRenderer will look at it to find its Layer and whether it is opaque.
class LayerShader : public SkShader {
public:
    LayerShader(Layer* layer, const SkMatrix* localMatrix)
    : INHERITED(localMatrix)
    , mLayer(layer) {
    }

    virtual bool asACustomShader(void** data) const override {
        if (data) {
            *data = static_cast<void*>(mLayer);
        }
        return true;
    }

    virtual bool isOpaque() const override {
        return !mLayer->isBlend();
    }

protected:
    virtual void shadeSpan(int x, int y, SkPMColor[], int count) {
        LOG_ALWAYS_FATAL("LayerShader should never be drawn with raster backend.");
    }

    virtual void flatten(SkWriteBuffer&) const override {
        LOG_ALWAYS_FATAL("LayerShader should never be flattened.");
    }

    virtual Factory getFactory() const override {
        LOG_ALWAYS_FATAL("LayerShader should never be created from a stream.");
        return nullptr;
    }
private:
    // Unowned.
    Layer* mLayer;
    typedef SkShader INHERITED;
};

void OpenGLRenderer::composeLayerRegion(Layer* layer, const Rect& rect) {
    if (CC_UNLIKELY(layer->region.isEmpty())) return; // nothing to draw

    if (layer->getConvexMask()) {
        save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);

        // clip to the area of the layer the mask can be larger
        clipRect(rect.left, rect.top, rect.right, rect.bottom, SkRegion::kIntersect_Op);

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(SkColorSetARGB(int(getLayerAlpha(layer) * 255), 0, 0, 0));

        // create LayerShader to map SaveLayer content into subsequent draw
        SkMatrix shaderMatrix;
        shaderMatrix.setTranslate(rect.left, rect.bottom);
        shaderMatrix.preScale(1, -1);
        LayerShader layerShader(layer, &shaderMatrix);
        paint.setShader(&layerShader);

        // Since the drawing primitive is defined in local drawing space,
        // we don't need to modify the draw matrix
        const SkPath* maskPath = layer->getConvexMask();
        DRAW_DOUBLE_STENCIL(drawConvexPath(*maskPath, &paint));

        paint.setShader(nullptr);
        restore();

        return;
    }

    if (layer->region.isRect()) {
        layer->setRegionAsRect();

        DRAW_DOUBLE_STENCIL(composeLayerRect(layer, layer->regionRect));

        layer->region.clear();
        return;
    }

    EVENT_LOGD("composeLayerRegion");
    // standard Region based draw
    size_t count;
    const android::Rect* rects;
    Region safeRegion;
    if (CC_LIKELY(hasRectToRectTransform())) {
        rects = layer->region.getArray(&count);
    } else {
        safeRegion = Region::createTJunctionFreeRegion(layer->region);
        rects = safeRegion.getArray(&count);
    }

    const float alpha = getLayerAlpha(layer);
    const float texX = 1.0f / float(layer->getWidth());
    const float texY = 1.0f / float(layer->getHeight());
    const float height = rect.getHeight();

    setupDraw();

    // We must get (and therefore bind) the region mesh buffer
    // after we setup drawing in case we need to mess with the
    // stencil buffer in setupDraw()
    TextureVertex* mesh = mCaches.getRegionMesh();
    uint32_t numQuads = 0;

    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter(layer->getColorFilter());
    setupDrawBlending(layer);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(layer->getColorFilter());
    setupDrawTexture(layer->getTexture());
    if (currentTransform()->isPureTranslate()) {
        const float x = (int) floorf(rect.left + currentTransform()->getTranslateX() + 0.5f);
        const float y = (int) floorf(rect.top + currentTransform()->getTranslateY() + 0.5f);

        layer->setFilter(GL_NEAREST);
        setupDrawModelView(kModelViewMode_Translate, false,
                x, y, x + rect.getWidth(), y + rect.getHeight(), true);
    } else {
        layer->setFilter(GL_LINEAR);
        setupDrawModelView(kModelViewMode_Translate, false,
                rect.left, rect.top, rect.right, rect.bottom);
    }
    setupDrawMeshIndices(&mesh[0].x, &mesh[0].u);

    for (size_t i = 0; i < count; i++) {
        const android::Rect* r = &rects[i];

        const float u1 = r->left * texX;
        const float v1 = (height - r->top) * texY;
        const float u2 = r->right * texX;
        const float v2 = (height - r->bottom) * texY;

        // TODO: Reject quads outside of the clip
        TextureVertex::set(mesh++, r->left, r->top, u1, v1);
        TextureVertex::set(mesh++, r->right, r->top, u2, v1);
        TextureVertex::set(mesh++, r->left, r->bottom, u1, v2);
        TextureVertex::set(mesh++, r->right, r->bottom, u2, v2);

        numQuads++;

        if (numQuads >= kMaxNumberOfQuads) {
            DRAW_DOUBLE_STENCIL(glDrawElements(GL_TRIANGLES, numQuads * 6,
                    GL_UNSIGNED_SHORT, nullptr));
            numQuads = 0;
            mesh = mCaches.getRegionMesh();
        }
    }

    if (numQuads > 0) {
        DRAW_DOUBLE_STENCIL(glDrawElements(GL_TRIANGLES, numQuads * 6,
                GL_UNSIGNED_SHORT, nullptr));
    }

#if DEBUG_LAYERS_AS_REGIONS
    drawRegionRectsDebug(layer->region);
#endif

    layer->region.clear();
}

#if DEBUG_LAYERS_AS_REGIONS
void OpenGLRenderer::drawRegionRectsDebug(const Region& region) {
    size_t count;
    const android::Rect* rects = region.getArray(&count);

    uint32_t colors[] = {
            0x7fff0000, 0x7f00ff00,
            0x7f0000ff, 0x7fff00ff,
    };

    int offset = 0;
    int32_t top = rects[0].top;

    for (size_t i = 0; i < count; i++) {
        if (top != rects[i].top) {
            offset ^= 0x2;
            top = rects[i].top;
        }

        SkPaint paint;
        paint.setColor(colors[offset + (i & 0x1)]);
        Rect r(rects[i].left, rects[i].top, rects[i].right, rects[i].bottom);
        drawColorRect(r.left, r.top, r.right, r.bottom, paint);
    }
}
#endif

void OpenGLRenderer::drawRegionRects(const SkRegion& region, const SkPaint& paint, bool dirty) {
    Vector<float> rects;

    SkRegion::Iterator it(region);
    while (!it.done()) {
        const SkIRect& r = it.rect();
        rects.push(r.fLeft);
        rects.push(r.fTop);
        rects.push(r.fRight);
        rects.push(r.fBottom);
        it.next();
    }

    drawColorRects(rects.array(), rects.size(), &paint, true, dirty, false);
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom, const mat4 transform) {
    if (hasLayer()) {
        Rect bounds(left, top, right, bottom);
        transform.mapRect(bounds);
        dirtyLayerUnchecked(bounds, getRegion());
    }
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom) {
    if (hasLayer()) {
        Rect bounds(left, top, right, bottom);
        dirtyLayerUnchecked(bounds, getRegion());
    }
}

void OpenGLRenderer::dirtyLayerUnchecked(Rect& bounds, Region* region) {
    if (bounds.intersect(mState.currentClipRect())) {
        bounds.snapToPixelBoundaries();
        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            region->orSelf(dirty);
        }
    }
}

void OpenGLRenderer::issueIndexedQuadDraw(Vertex* mesh, GLsizei quadsCount) {
    GLsizei elementsCount = quadsCount * 6;
    while (elementsCount > 0) {
        GLsizei drawCount = min(elementsCount, (GLsizei) kMaxNumberOfQuads * 6);

        setupDrawIndexedVertices(&mesh[0].x);
        glDrawElements(GL_TRIANGLES, drawCount, GL_UNSIGNED_SHORT, nullptr);

        elementsCount -= drawCount;
        // Though there are 4 vertices in a quad, we use 6 indices per
        // quad to draw with GL_TRIANGLES
        mesh += (drawCount / 6) * 4;
    }
}

void OpenGLRenderer::clearLayerRegions() {
    const size_t count = mLayers.size();
    if (count == 0) return;

    if (!mState.currentlyIgnored()) {
        EVENT_LOGD("clearLayerRegions");
        // Doing several glScissor/glClear here can negatively impact
        // GPUs with a tiler architecture, instead we draw quads with
        // the Clear blending mode

        // The list contains bounds that have already been clipped
        // against their initial clip rect, and the current clip
        // is likely different so we need to disable clipping here
        bool scissorChanged = mRenderState.scissor().setEnabled(false);

        Vertex mesh[count * 4];
        Vertex* vertex = mesh;

        for (uint32_t i = 0; i < count; i++) {
            const Rect& bounds = mLayers[i];

            Vertex::set(vertex++, bounds.left, bounds.top);
            Vertex::set(vertex++, bounds.right, bounds.top);
            Vertex::set(vertex++, bounds.left, bounds.bottom);
            Vertex::set(vertex++, bounds.right, bounds.bottom);
        }
        // We must clear the list of dirty rects before we
        // call setupDraw() to prevent stencil setup to do
        // the same thing again
        mLayers.clear();

        SkPaint clearPaint;
        clearPaint.setXfermodeMode(SkXfermode::kClear_Mode);

        setupDraw(false);
        setupDrawColor(0.0f, 0.0f, 0.0f, 1.0f);
        setupDrawBlending(&clearPaint, true);
        setupDrawProgram();
        setupDrawPureColorUniforms();
        setupDrawModelView(kModelViewMode_Translate, false,
                0.0f, 0.0f, 0.0f, 0.0f, true);

        issueIndexedQuadDraw(&mesh[0], count);

        if (scissorChanged) mRenderState.scissor().setEnabled(true);
    } else {
        mLayers.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// State Deferral
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::storeDisplayState(DeferredDisplayState& state, int stateDeferFlags) {
    const Rect& currentClip = mState.currentClipRect();
    const mat4* currentMatrix = currentTransform();

    if (stateDeferFlags & kStateDeferFlag_Draw) {
        // state has bounds initialized in local coordinates
        if (!state.mBounds.isEmpty()) {
            currentMatrix->mapRect(state.mBounds);
            Rect clippedBounds(state.mBounds);
            // NOTE: if we ever want to use this clipping info to drive whether the scissor
            // is used, it should more closely duplicate the quickReject logic (in how it uses
            // snapToPixelBoundaries)

            if (!clippedBounds.intersect(currentClip)) {
                // quick rejected
                return true;
            }

            state.mClipSideFlags = kClipSide_None;
            if (!currentClip.contains(state.mBounds)) {
                int& flags = state.mClipSideFlags;
                // op partially clipped, so record which sides are clipped for clip-aware merging
                if (currentClip.left > state.mBounds.left) flags |= kClipSide_Left;
                if (currentClip.top > state.mBounds.top) flags |= kClipSide_Top;
                if (currentClip.right < state.mBounds.right) flags |= kClipSide_Right;
                if (currentClip.bottom < state.mBounds.bottom) flags |= kClipSide_Bottom;
            }
            state.mBounds.set(clippedBounds);
        } else {
            // Empty bounds implies size unknown. Label op as conservatively clipped to disable
            // overdraw avoidance (since we don't know what it overlaps)
            state.mClipSideFlags = kClipSide_ConservativeFull;
            state.mBounds.set(currentClip);
        }
    }

    state.mClipValid = (stateDeferFlags & kStateDeferFlag_Clip);
    if (state.mClipValid) {
        state.mClip.set(currentClip);
    }

    // Transform, drawModifiers, and alpha always deferred, since they are used by state operations
    // (Note: saveLayer/restore use colorFilter and alpha, so we just save restore everything)
    state.mMatrix.load(*currentMatrix);
    state.mDrawModifiers = mDrawModifiers;
    state.mAlpha = currentSnapshot()->alpha;

    // always store/restore, since it's just a pointer
    state.mRoundRectClipState = currentSnapshot()->roundRectClipState;
    return false;
}

void OpenGLRenderer::restoreDisplayState(const DeferredDisplayState& state, bool skipClipRestore) {
    setMatrix(state.mMatrix);
    writableSnapshot()->alpha = state.mAlpha;
    mDrawModifiers = state.mDrawModifiers;
    writableSnapshot()->roundRectClipState = state.mRoundRectClipState;

    if (state.mClipValid && !skipClipRestore) {
        writableSnapshot()->setClip(state.mClip.left, state.mClip.top,
                state.mClip.right, state.mClip.bottom);
        dirtyClip();
    }
}

/**
 * Merged multidraw (such as in drawText and drawBitmaps rely on the fact that no clipping is done
 * in the draw path. Instead, clipping is done ahead of time - either as a single clip rect (when at
 * least one op is clipped), or disabled entirely (because no merged op is clipped)
 *
 * This method should be called when restoreDisplayState() won't be restoring the clip
 */
void OpenGLRenderer::setupMergedMultiDraw(const Rect* clipRect) {
    if (clipRect != nullptr) {
        writableSnapshot()->setClip(clipRect->left, clipRect->top, clipRect->right, clipRect->bottom);
    } else {
        writableSnapshot()->setClip(0, 0, mState.getWidth(), mState.getHeight());
    }
    dirtyClip();
    bool enableScissor = (clipRect != nullptr) || mScissorOptimizationDisabled;
    mRenderState.scissor().setEnabled(enableScissor);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    Rect clip(mState.currentClipRect());
    clip.snapToPixelBoundaries();

    if (mRenderState.scissor().set(clip.left, getViewportHeight() - clip.bottom,
            clip.getWidth(), clip.getHeight())) {
        mState.setDirtyClip(false);
    }
}

void OpenGLRenderer::ensureStencilBuffer() {
    // Thanks to the mismatch between EGL and OpenGL ES FBO we
    // cannot attach a stencil buffer to fbo0 dynamically. Let's
    // just hope we have one when hasLayer() returns false.
    if (hasLayer()) {
        attachStencilBufferToLayer(currentSnapshot()->layer);
    }
}

void OpenGLRenderer::attachStencilBufferToLayer(Layer* layer) {
    // The layer's FBO is already bound when we reach this stage
    if (!layer->getStencilRenderBuffer()) {
        // GL_QCOM_tiled_rendering doesn't like it if a renderbuffer
        // is attached after we initiated tiling. We must turn it off,
        // attach the new render buffer then turn tiling back on
        endTiling();

        RenderBuffer* buffer = mCaches.renderBufferCache.get(
                Stencil::getSmallestStencilFormat(), layer->getWidth(), layer->getHeight());
        layer->setStencilRenderBuffer(buffer);

        startTiling(layer->clipRect, layer->layer.getHeight());
    }
}

static void handlePoint(std::vector<Vertex>& rectangleVertices, const Matrix4& transform,
        float x, float y) {
    Vertex v;
    v.x = x;
    v.y = y;
    transform.mapPoint(v.x, v.y);
    rectangleVertices.push_back(v);
}

static void handlePointNoTransform(std::vector<Vertex>& rectangleVertices, float x, float y) {
    Vertex v;
    v.x = x;
    v.y = y;
    rectangleVertices.push_back(v);
}

void OpenGLRenderer::drawRectangleList(const RectangleList& rectangleList) {
    int count = rectangleList.getTransformedRectanglesCount();
    std::vector<Vertex> rectangleVertices(count * 4);
    Rect scissorBox = rectangleList.calculateBounds();
    scissorBox.snapToPixelBoundaries();
    for (int i = 0; i < count; ++i) {
        const TransformedRectangle& tr(rectangleList.getTransformedRectangle(i));
        const Matrix4& transform = tr.getTransform();
        Rect bounds = tr.getBounds();
        if (transform.rectToRect()) {
            transform.mapRect(bounds);
            if (!bounds.intersect(scissorBox)) {
                bounds.setEmpty();
            } else {
                handlePointNoTransform(rectangleVertices, bounds.left, bounds.top);
                handlePointNoTransform(rectangleVertices, bounds.right, bounds.top);
                handlePointNoTransform(rectangleVertices, bounds.left, bounds.bottom);
                handlePointNoTransform(rectangleVertices, bounds.right, bounds.bottom);
            }
        } else {
            handlePoint(rectangleVertices, transform, bounds.left, bounds.top);
            handlePoint(rectangleVertices, transform, bounds.right, bounds.top);
            handlePoint(rectangleVertices, transform, bounds.left, bounds.bottom);
            handlePoint(rectangleVertices, transform, bounds.right, bounds.bottom);
        }
    }

    mRenderState.scissor().set(scissorBox.left, getViewportHeight() - scissorBox.bottom,
            scissorBox.getWidth(), scissorBox.getHeight());

    const SkPaint* paint = nullptr;
    setupDraw();
    setupDrawNoTexture();
    setupDrawColor(0, 0xff * currentSnapshot()->alpha);
    setupDrawShader(getShader(paint));
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawModelView(kModelViewMode_Translate, false,
            0.0f, 0.0f, 0.0f, 0.0f, true);
    setupDrawColorUniforms(getShader(paint));
    setupDrawShaderUniforms(getShader(paint));
    setupDrawColorFilterUniforms(getColorFilter(paint));

    issueIndexedQuadDraw(&rectangleVertices[0], rectangleVertices.size() / 4);
}

void OpenGLRenderer::setStencilFromClip() {
    if (!mCaches.debugOverdraw) {
        if (!currentSnapshot()->clipIsSimple()) {
            int incrementThreshold;
            EVENT_LOGD("setStencilFromClip - enabling");

            // NOTE: The order here is important, we must set dirtyClip to false
            //       before any draw call to avoid calling back into this method
            mState.setDirtyClip(false);

            ensureStencilBuffer();

            const ClipArea& clipArea = currentSnapshot()->getClipArea();

            bool isRectangleList = clipArea.isRectangleList();
            if (isRectangleList) {
                incrementThreshold = clipArea.getRectangleList().getTransformedRectanglesCount();
            } else {
                incrementThreshold = 0;
            }

            mRenderState.stencil().enableWrite(incrementThreshold);

            // Clean and update the stencil, but first make sure we restrict drawing
            // to the region's bounds
            bool resetScissor = mRenderState.scissor().setEnabled(true);
            if (resetScissor) {
                // The scissor was not set so we now need to update it
                setScissorFromClip();
            }

            mRenderState.stencil().clear();

            // stash and disable the outline clip state, since stencil doesn't account for outline
            bool storedSkipOutlineClip = mSkipOutlineClip;
            mSkipOutlineClip = true;

            SkPaint paint;
            paint.setColor(SK_ColorBLACK);
            paint.setXfermodeMode(SkXfermode::kSrc_Mode);

            if (isRectangleList) {
                drawRectangleList(clipArea.getRectangleList());
            } else {
                // NOTE: We could use the region contour path to generate a smaller mesh
                //       Since we are using the stencil we could use the red book path
                //       drawing technique. It might increase bandwidth usage though.

                // The last parameter is important: we are not drawing in the color buffer
                // so we don't want to dirty the current layer, if any
                drawRegionRects(clipArea.getClipRegion(), paint, false);
            }
            if (resetScissor) mRenderState.scissor().setEnabled(false);
            mSkipOutlineClip = storedSkipOutlineClip;

            mRenderState.stencil().enableTest(incrementThreshold);

            // Draw the region used to generate the stencil if the appropriate debug
            // mode is enabled
            // TODO: Implement for rectangle list clip areas
            if (mCaches.debugStencilClip == Caches::kStencilShowRegion &&
                    !clipArea.isRectangleList()) {
                paint.setColor(0x7f0000ff);
                paint.setXfermodeMode(SkXfermode::kSrcOver_Mode);
                drawRegionRects(currentSnapshot()->getClipRegion(), paint);
            }
        } else {
            EVENT_LOGD("setStencilFromClip - disabling");
            mRenderState.stencil().disable();
        }
    }
}

/**
 * Returns false and sets scissor enable based upon bounds if drawing won't be clipped out.
 *
 * @param paint if not null, the bounds will be expanded to account for stroke depending on paint
 *         style, and tessellated AA ramp
 */
bool OpenGLRenderer::quickRejectSetupScissor(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    bool snapOut = paint && paint->isAntiAlias();

    if (paint && paint->getStyle() != SkPaint::kFill_Style) {
        float outset = paint->getStrokeWidth() * 0.5f;
        left -= outset;
        top -= outset;
        right += outset;
        bottom += outset;
    }

    bool clipRequired = false;
    bool roundRectClipRequired = false;
    if (mState.calculateQuickRejectForScissor(left, top, right, bottom,
            &clipRequired, &roundRectClipRequired, snapOut)) {
        return true;
    }

    // not quick rejected, so enable the scissor if clipRequired
    mRenderState.scissor().setEnabled(mScissorOptimizationDisabled || clipRequired);
    mSkipOutlineClip = !roundRectClipRequired;
    return false;
}

void OpenGLRenderer::debugClip() {
#if DEBUG_CLIP_REGIONS
    if (!currentSnapshot()->clipRegion->isEmpty()) {
        SkPaint paint;
        paint.setColor(0x7f00ff00);
        drawRegionRects(*(currentSnapshot()->clipRegion, paint);

    }
#endif
}

///////////////////////////////////////////////////////////////////////////////
// Drawing commands
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setupDraw(bool clearLayer) {
    // TODO: It would be best if we could do this before quickRejectSetupScissor()
    //       changes the scissor test state
    if (clearLayer) clearLayerRegions();
    // Make sure setScissor & setStencil happen at the beginning of
    // this method
    if (mState.getDirtyClip()) {
        if (mRenderState.scissor().isEnabled()) {
            setScissorFromClip();
        }

        setStencilFromClip();
    }

    mDescription.reset();

    mSetShaderColor = false;
    mColorSet = false;
    mColorA = mColorR = mColorG = mColorB = 0.0f;
    mTextureUnit = 0;
    mTrackDirtyRegions = true;

    // Enable debug highlight when what we're about to draw is tested against
    // the stencil buffer and if stencil highlight debugging is on
    mDescription.hasDebugHighlight = !mCaches.debugOverdraw &&
            mCaches.debugStencilClip == Caches::kStencilShowHighlight &&
            mRenderState.stencil().isTestEnabled();
}

void OpenGLRenderer::setupDrawWithTexture(bool isAlpha8) {
    mDescription.hasTexture = true;
    mDescription.hasAlpha8Texture = isAlpha8;
}

void OpenGLRenderer::setupDrawWithTextureAndColor(bool isAlpha8) {
    mDescription.hasTexture = true;
    mDescription.hasColors = true;
    mDescription.hasAlpha8Texture = isAlpha8;
}

void OpenGLRenderer::setupDrawWithExternalTexture() {
    mDescription.hasExternalTexture = true;
}

void OpenGLRenderer::setupDrawNoTexture() {
    mRenderState.meshState().disableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawVertexAlpha(bool useShadowAlphaInterp) {
    mDescription.hasVertexAlpha = true;
    mDescription.useShadowAlphaInterp = useShadowAlphaInterp;
}

void OpenGLRenderer::setupDrawColor(int color, int alpha) {
    mColorA = alpha / 255.0f;
    mColorR = mColorA * ((color >> 16) & 0xFF) / 255.0f;
    mColorG = mColorA * ((color >>  8) & 0xFF) / 255.0f;
    mColorB = mColorA * ((color      ) & 0xFF) / 255.0f;
    mColorSet = true;
    mSetShaderColor = mDescription.setColorModulate(mColorA);
}

void OpenGLRenderer::setupDrawAlpha8Color(int color, int alpha) {
    mColorA = alpha / 255.0f;
    mColorR = mColorA * ((color >> 16) & 0xFF) / 255.0f;
    mColorG = mColorA * ((color >>  8) & 0xFF) / 255.0f;
    mColorB = mColorA * ((color      ) & 0xFF) / 255.0f;
    mColorSet = true;
    mSetShaderColor = mDescription.setAlpha8ColorModulate(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawTextGamma(const SkPaint* paint) {
    mCaches.fontRenderer->describe(mDescription, paint);
}

void OpenGLRenderer::setupDrawColor(float r, float g, float b, float a) {
    mColorA = a;
    mColorR = r;
    mColorG = g;
    mColorB = b;
    mColorSet = true;
    mSetShaderColor = mDescription.setColorModulate(a);
}

void OpenGLRenderer::setupDrawShader(const SkShader* shader) {
    if (shader != nullptr) {
        SkiaShader::describe(&mCaches, mDescription, mExtensions, *shader);
    }
}

void OpenGLRenderer::setupDrawColorFilter(const SkColorFilter* filter) {
    if (filter == nullptr) {
        return;
    }

    SkXfermode::Mode mode;
    if (filter->asColorMode(nullptr, &mode)) {
        mDescription.colorOp = ProgramDescription::kColorBlend;
        mDescription.colorMode = mode;
    } else if (filter->asColorMatrix(nullptr)) {
        mDescription.colorOp = ProgramDescription::kColorMatrix;
    }
}

void OpenGLRenderer::accountForClear(SkXfermode::Mode mode) {
    if (mColorSet && mode == SkXfermode::kClear_Mode) {
        mColorA = 1.0f;
        mColorR = mColorG = mColorB = 0.0f;
        mSetShaderColor = mDescription.modulate = true;
    }
}

void OpenGLRenderer::setupDrawBlending(const Layer* layer, bool swapSrcDst) {
    SkXfermode::Mode mode = layer->getMode();
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    // TODO: check shader blending, once we have shader drawing support for layers.
    bool blend = layer->isBlend()
            || getLayerAlpha(layer) < 1.0f
            || (mColorSet && mColorA < 1.0f)
            || PaintUtils::isBlendedColorFilter(layer->getColorFilter());
    chooseBlending(blend, mode, mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawBlending(const SkPaint* paint, bool blend, bool swapSrcDst) {
    SkXfermode::Mode mode = getXfermodeDirect(paint);
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    blend |= (mColorSet && mColorA < 1.0f) ||
            (getShader(paint) && !getShader(paint)->isOpaque()) ||
            PaintUtils::isBlendedColorFilter(getColorFilter(paint));
    chooseBlending(blend, mode, mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawProgram() {
    useProgram(mCaches.programCache.get(mDescription));
    if (mDescription.hasRoundRectClip) {
        // TODO: avoid doing this repeatedly, stashing state pointer in program
        const RoundRectClipState* state = writableSnapshot()->roundRectClipState;
        const Rect& innerRect = state->innerRect;
        glUniform4f(mCaches.currentProgram->getUniform("roundRectInnerRectLTRB"),
                innerRect.left, innerRect.top,
                innerRect.right, innerRect.bottom);
        glUniformMatrix4fv(mCaches.currentProgram->getUniform("roundRectInvTransform"),
                1, GL_FALSE, &state->matrix.data[0]);

        // add half pixel to round out integer rect space to cover pixel centers
        float roundedOutRadius = state->radius + 0.5f;
        glUniform1f(mCaches.currentProgram->getUniform("roundRectRadius"),
                roundedOutRadius);
    }
}

void OpenGLRenderer::setupDrawDirtyRegionsDisabled() {
    mTrackDirtyRegions = false;
}

void OpenGLRenderer::setupDrawModelView(ModelViewMode mode, bool offset,
        float left, float top, float right, float bottom, bool ignoreTransform) {
    mModelViewMatrix.loadTranslate(left, top, 0.0f);
    if (mode == kModelViewMode_TranslateAndScale) {
        mModelViewMatrix.scale(right - left, bottom - top, 1.0f);
    }

    bool dirty = right - left > 0.0f && bottom - top > 0.0f;
    const Matrix4& transformMatrix = ignoreTransform ? Matrix4::identity() : *currentTransform();
    mCaches.currentProgram->set(writableSnapshot()->getOrthoMatrix(),
            mModelViewMatrix, transformMatrix, offset);
    if (dirty && mTrackDirtyRegions) {
        if (!ignoreTransform) {
            dirtyLayer(left, top, right, bottom, *currentTransform());
        } else {
            dirtyLayer(left, top, right, bottom);
        }
    }
}

void OpenGLRenderer::setupDrawColorUniforms(bool hasShader) {
    if ((mColorSet && !hasShader) || (hasShader && mSetShaderColor)) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawPureColorUniforms() {
    if (mSetShaderColor) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawShaderUniforms(const SkShader* shader, bool ignoreTransform) {
    if (shader == nullptr) {
        return;
    }

    if (ignoreTransform) {
        // if ignoreTransform=true was passed to setupDrawModelView, undo currentTransform()
        // because it was built into modelView / the geometry, and the description needs to
        // compensate.
        mat4 modelViewWithoutTransform;
        modelViewWithoutTransform.loadInverse(*currentTransform());
        modelViewWithoutTransform.multiply(mModelViewMatrix);
        mModelViewMatrix.load(modelViewWithoutTransform);
    }

    SkiaShader::setupProgram(&mCaches, mModelViewMatrix, &mTextureUnit, mExtensions, *shader);
}

void OpenGLRenderer::setupDrawColorFilterUniforms(const SkColorFilter* filter) {
    if (nullptr == filter) {
        return;
    }

    SkColor color;
    SkXfermode::Mode mode;
    if (filter->asColorMode(&color, &mode)) {
        const int alpha = SkColorGetA(color);
        const GLfloat a = alpha / 255.0f;
        const GLfloat r = a * SkColorGetR(color) / 255.0f;
        const GLfloat g = a * SkColorGetG(color) / 255.0f;
        const GLfloat b = a * SkColorGetB(color) / 255.0f;
        glUniform4f(mCaches.currentProgram->getUniform("colorBlend"), r, g, b, a);
        return;
    }

    SkScalar srcColorMatrix[20];
    if (filter->asColorMatrix(srcColorMatrix)) {

        float colorMatrix[16];
        memcpy(colorMatrix, srcColorMatrix, 4 * sizeof(float));
        memcpy(&colorMatrix[4], &srcColorMatrix[5], 4 * sizeof(float));
        memcpy(&colorMatrix[8], &srcColorMatrix[10], 4 * sizeof(float));
        memcpy(&colorMatrix[12], &srcColorMatrix[15], 4 * sizeof(float));

        // Skia uses the range [0..255] for the addition vector, but we need
        // the [0..1] range to apply the vector in GLSL
        float colorVector[4];
        colorVector[0] = srcColorMatrix[4] / 255.0f;
        colorVector[1] = srcColorMatrix[9] / 255.0f;
        colorVector[2] = srcColorMatrix[14] / 255.0f;
        colorVector[3] = srcColorMatrix[19] / 255.0f;

        glUniformMatrix4fv(mCaches.currentProgram->getUniform("colorMatrix"), 1,
                GL_FALSE, colorMatrix);
        glUniform4fv(mCaches.currentProgram->getUniform("colorMatrixVector"), 1, colorVector);
        return;
    }

    // it is an error if we ever get here
}

void OpenGLRenderer::setupDrawTextGammaUniforms() {
    mCaches.fontRenderer->setupProgram(mDescription, mCaches.currentProgram);
}

void OpenGLRenderer::setupDrawSimpleMesh() {
    bool force = mRenderState.meshState().bindMeshBuffer();
    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram, force, nullptr);
    mRenderState.meshState().unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawTexture(GLuint texture) {
    if (texture) bindTexture(texture);
    mTextureUnit++;
    mRenderState.meshState().enableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawExternalTexture(GLuint texture) {
    bindExternalTexture(texture);
    mTextureUnit++;
    mRenderState.meshState().enableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawTextureTransform() {
    mDescription.hasTextureTransform = true;
}

void OpenGLRenderer::setupDrawTextureTransformUniforms(mat4& transform) {
    glUniformMatrix4fv(mCaches.currentProgram->getUniform("mainTextureTransform"), 1,
            GL_FALSE, &transform.data[0]);
}

void OpenGLRenderer::setupDrawMesh(const GLvoid* vertices,
        const GLvoid* texCoords, GLuint vbo) {
    bool force = false;
    if (!vertices || vbo) {
        force = mRenderState.meshState().bindMeshBuffer(vbo);
    } else {
        force = mRenderState.meshState().unbindMeshBuffer();
    }

    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram, force, vertices);
    if (mCaches.currentProgram->texCoords >= 0) {
        mRenderState.meshState().bindTexCoordsVertexPointer(mCaches.currentProgram, force, texCoords);
    }

    mRenderState.meshState().unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawMesh(const GLvoid* vertices,
        const GLvoid* texCoords, const GLvoid* colors) {
    bool force = mRenderState.meshState().unbindMeshBuffer();
    GLsizei stride = sizeof(ColorTextureVertex);

    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram, force,
            vertices, stride);
    if (mCaches.currentProgram->texCoords >= 0) {
        mRenderState.meshState().bindTexCoordsVertexPointer(mCaches.currentProgram, force,
                texCoords, stride);
    }
    int slot = mCaches.currentProgram->getAttrib("colors");
    if (slot >= 0) {
        glEnableVertexAttribArray(slot);
        glVertexAttribPointer(slot, 4, GL_FLOAT, GL_FALSE, stride, colors);
    }

    mRenderState.meshState().unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawMeshIndices(const GLvoid* vertices,
        const GLvoid* texCoords, GLuint vbo) {
    bool force = false;
    // If vbo is != 0 we want to treat the vertices parameter as an offset inside
    // a VBO. However, if vertices is set to NULL and vbo == 0 then we want to
    // use the default VBO found in RenderState
    if (!vertices || vbo) {
        force = mRenderState.meshState().bindMeshBuffer(vbo);
    } else {
        force = mRenderState.meshState().unbindMeshBuffer();
    }
    mRenderState.meshState().bindQuadIndicesBuffer();

    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram, force, vertices);
    if (mCaches.currentProgram->texCoords >= 0) {
        mRenderState.meshState().bindTexCoordsVertexPointer(mCaches.currentProgram,
                force, texCoords);
    }
}

void OpenGLRenderer::setupDrawIndexedVertices(GLvoid* vertices) {
    bool force = mRenderState.meshState().unbindMeshBuffer();
    mRenderState.meshState().bindQuadIndicesBuffer();
    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram, force,
            vertices, kVertexStride);
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t replayFlags) {
    // All the usual checks and setup operations (quickReject, setupDraw, etc.)
    // will be performed by the display list itself
    if (renderNode && renderNode->isRenderable()) {
        // compute 3d ordering
        renderNode->computeOrdering();
        if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
            startFrame();
            ReplayStateStruct replayStruct(*this, dirty, replayFlags);
            renderNode->replay(replayStruct, 0);
            return;
        }

        // Don't avoid overdraw when visualizing, since that makes it harder to
        // debug where it's coming from, and when the problem occurs.
        bool avoidOverdraw = !mCaches.debugOverdraw;
        DeferredDisplayList deferredList(mState.currentClipRect(), avoidOverdraw);
        DeferStateStruct deferStruct(deferredList, *this, replayFlags);
        renderNode->defer(deferStruct, 0);

        flushLayers();
        startFrame();

        deferredList.flush(*this, dirty);
    } else {
        // Even if there is no drawing command(Ex: invisible),
        // it still needs startFrame to clear buffer and start tiling.
        startFrame();
    }
}

void OpenGLRenderer::drawAlphaBitmap(Texture* texture, float left, float top,
        const SkPaint* paint) {
    float x = left;
    float y = top;

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    bool ignoreTransform = false;
    if (currentTransform()->isPureTranslate()) {
        x = (int) floorf(left + currentTransform()->getTranslateX() + 0.5f);
        y = (int) floorf(top + currentTransform()->getTranslateY() + 0.5f);
        ignoreTransform = true;

        texture->setFilter(GL_NEAREST, true);
    } else {
        texture->setFilter(getFilter(paint), true);
    }

    // No need to check for a UV mapper on the texture object, only ARGB_8888
    // bitmaps get packed in the atlas
    drawAlpha8TextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
            paint, (GLvoid*) nullptr, (GLvoid*) kMeshTextureOffset,
            GL_TRIANGLE_STRIP, kMeshCount, ignoreTransform);
}

/**
 * Important note: this method is intended to draw batches of bitmaps and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
void OpenGLRenderer::drawBitmaps(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
        int bitmapCount, TextureVertex* vertices, bool pureTranslate,
        const Rect& bounds, const SkPaint* paint) {
    mCaches.activeTexture(0);
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return;

    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(pureTranslate ? GL_NEAREST : getFilter(paint), true);

    const float x = (int) floorf(bounds.left + 0.5f);
    const float y = (int) floorf(bounds.top + 0.5f);
    if (CC_UNLIKELY(bitmap->colorType() == kAlpha_8_SkColorType)) {
        drawAlpha8TextureMesh(x, y, x + bounds.getWidth(), y + bounds.getHeight(),
                texture->id, paint, &vertices[0].x, &vertices[0].u,
                GL_TRIANGLES, bitmapCount * 6, true,
                kModelViewMode_Translate, false);
    } else {
        drawTextureMesh(x, y, x + bounds.getWidth(), y + bounds.getHeight(),
                texture->id, paint, texture->blend, &vertices[0].x, &vertices[0].u,
                GL_TRIANGLES, bitmapCount * 6, false, true, 0,
                kModelViewMode_Translate, false);
    }

    mDirty = true;
}

void OpenGLRenderer::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    if (quickRejectSetupScissor(0, 0, bitmap->width(), bitmap->height())) {
        return;
    }

    mCaches.activeTexture(0);
    Texture* texture = getTexture(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    if (CC_UNLIKELY(bitmap->colorType() == kAlpha_8_SkColorType)) {
        drawAlphaBitmap(texture, 0, 0, paint);
    } else {
        drawTextureRect(0, 0, bitmap->width(), bitmap->height(), texture, paint);
    }

    mDirty = true;
}

void OpenGLRenderer::drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {
    if (!vertices || mState.currentlyIgnored()) {
        return;
    }

    // TODO: use quickReject on bounds from vertices
    mRenderState.scissor().setEnabled(true);

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

    const uint32_t count = meshWidth * meshHeight * 6;

    std::unique_ptr<ColorTextureVertex[]> mesh(new ColorTextureVertex[count]);
    ColorTextureVertex* vertex = &mesh[0];

    std::unique_ptr<int[]> tempColors;
    if (!colors) {
        uint32_t colorsCount = (meshWidth + 1) * (meshHeight + 1);
        tempColors.reset(new int[colorsCount]);
        memset(tempColors.get(), 0xff, colorsCount * sizeof(int));
        colors = tempColors.get();
    }

    mCaches.activeTexture(0);
    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap);
    const UvMapper& mapper(getMapper(texture));

    for (int32_t y = 0; y < meshHeight; y++) {
        for (int32_t x = 0; x < meshWidth; x++) {
            uint32_t i = (y * (meshWidth + 1) + x) * 2;

            float u1 = float(x) / meshWidth;
            float u2 = float(x + 1) / meshWidth;
            float v1 = float(y) / meshHeight;
            float v2 = float(y + 1) / meshHeight;

            mapper.map(u1, v1, u2, v2);

            int ax = i + (meshWidth + 1) * 2;
            int ay = ax + 1;
            int bx = i;
            int by = bx + 1;
            int cx = i + 2;
            int cy = cx + 1;
            int dx = i + (meshWidth + 1) * 2 + 2;
            int dy = dx + 1;

            ColorTextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2, colors[dx / 2]);
            ColorTextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2, colors[ax / 2]);
            ColorTextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1, colors[bx / 2]);

            ColorTextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2, colors[dx / 2]);
            ColorTextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1, colors[bx / 2]);
            ColorTextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1, colors[cx / 2]);

            left = fminf(left, fminf(vertices[ax], fminf(vertices[bx], vertices[cx])));
            top = fminf(top, fminf(vertices[ay], fminf(vertices[by], vertices[cy])));
            right = fmaxf(right, fmaxf(vertices[ax], fmaxf(vertices[bx], vertices[cx])));
            bottom = fmaxf(bottom, fmaxf(vertices[ay], fmaxf(vertices[by], vertices[cy])));
        }
    }

    if (quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    if (!texture) {
        texture = mCaches.textureCache.get(bitmap);
        if (!texture) {
            return;
        }
    }
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(getFilter(paint), true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    float a = alpha / 255.0f;

    if (hasLayer()) {
        dirtyLayer(left, top, right, bottom, *currentTransform());
    }

    setupDraw();
    setupDrawWithTextureAndColor();
    setupDrawColor(a, a, a, a);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint, true);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawModelView(kModelViewMode_TranslateAndScale, false, 0.0f, 0.0f, 1.0f, 1.0f);
    setupDrawTexture(texture->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawMesh(&mesh[0].x, &mesh[0].u, &mesh[0].r);

    glDrawArrays(GL_TRIANGLES, 0, count);

    int slot = mCaches.currentProgram->getAttrib("colors");
    if (slot >= 0) {
        glDisableVertexAttribArray(slot);
    }

    mDirty = true;
}

void OpenGLRenderer::drawBitmap(const SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         const SkPaint* paint) {
    if (quickRejectSetupScissor(dstLeft, dstTop, dstRight, dstBottom)) {
        return;
    }

    mCaches.activeTexture(0);
    Texture* texture = getTexture(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    float u1 = fmax(0.0f, srcLeft / width);
    float v1 = fmax(0.0f, srcTop / height);
    float u2 = fmin(1.0f, srcRight / width);
    float v2 = fmin(1.0f, srcBottom / height);

    getMapper(texture).map(u1, v1, u2, v2);

    mRenderState.meshState().unbindMeshBuffer();
    resetDrawTextureTexCoords(u1, v1, u2, v2);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    float scaleX = (dstRight - dstLeft) / (srcRight - srcLeft);
    float scaleY = (dstBottom - dstTop) / (srcBottom - srcTop);

    bool scaled = scaleX != 1.0f || scaleY != 1.0f;
    // Apply a scale transform on the canvas only when a shader is in use
    // Skia handles the ratio between the dst and src rects as a scale factor
    // when a shader is set
    bool useScaleTransform = getShader(paint) && scaled;
    bool ignoreTransform = false;

    if (CC_LIKELY(currentTransform()->isPureTranslate() && !useScaleTransform)) {
        float x = (int) floorf(dstLeft + currentTransform()->getTranslateX() + 0.5f);
        float y = (int) floorf(dstTop + currentTransform()->getTranslateY() + 0.5f);

        dstRight = x + (dstRight - dstLeft);
        dstBottom = y + (dstBottom - dstTop);

        dstLeft = x;
        dstTop = y;

        texture->setFilter(scaled ? getFilter(paint) : GL_NEAREST, true);
        ignoreTransform = true;
    } else {
        texture->setFilter(getFilter(paint), true);
    }

    if (CC_UNLIKELY(useScaleTransform)) {
        save(SkCanvas::kMatrix_SaveFlag);
        translate(dstLeft, dstTop);
        scale(scaleX, scaleY);

        dstLeft = 0.0f;
        dstTop = 0.0f;

        dstRight = srcRight - srcLeft;
        dstBottom = srcBottom - srcTop;
    }

    if (CC_UNLIKELY(bitmap->colorType() == kAlpha_8_SkColorType)) {
        drawAlpha8TextureMesh(dstLeft, dstTop, dstRight, dstBottom,
                texture->id, paint,
                &mMeshVertices[0].x, &mMeshVertices[0].u,
                GL_TRIANGLE_STRIP, kMeshCount, ignoreTransform);
    } else {
        drawTextureMesh(dstLeft, dstTop, dstRight, dstBottom,
                texture->id, paint, texture->blend,
                &mMeshVertices[0].x, &mMeshVertices[0].u,
                GL_TRIANGLE_STRIP, kMeshCount, false, ignoreTransform);
    }

    if (CC_UNLIKELY(useScaleTransform)) {
        restore();
    }

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);

    mDirty = true;
}

void OpenGLRenderer::drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
        float left, float top, float right, float bottom, const SkPaint* paint) {
    if (quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    AssetAtlas::Entry* entry = mRenderState.assetAtlas().getEntry(bitmap);
    const Patch* mesh = mCaches.patchCache.get(entry, bitmap->width(), bitmap->height(),
            right - left, bottom - top, patch);

    drawPatch(bitmap, mesh, entry, left, top, right, bottom, paint);
}

void OpenGLRenderer::drawPatch(const SkBitmap* bitmap, const Patch* mesh,
        AssetAtlas::Entry* entry, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    if (quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    if (CC_LIKELY(mesh && mesh->verticesCount > 0)) {
        mCaches.activeTexture(0);
        Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
        if (!texture) return;
        const AutoTexture autoCleanup(texture);

        texture->setWrap(GL_CLAMP_TO_EDGE, true);
        texture->setFilter(GL_LINEAR, true);

        const bool pureTranslate = currentTransform()->isPureTranslate();
        // Mark the current layer dirty where we are going to draw the patch
        if (hasLayer() && mesh->hasEmptyQuads) {
            const float offsetX = left + currentTransform()->getTranslateX();
            const float offsetY = top + currentTransform()->getTranslateY();
            const size_t count = mesh->quads.size();
            for (size_t i = 0; i < count; i++) {
                const Rect& bounds = mesh->quads.itemAt(i);
                if (CC_LIKELY(pureTranslate)) {
                    const float x = (int) floorf(bounds.left + offsetX + 0.5f);
                    const float y = (int) floorf(bounds.top + offsetY + 0.5f);
                    dirtyLayer(x, y, x + bounds.getWidth(), y + bounds.getHeight());
                } else {
                    dirtyLayer(left + bounds.left, top + bounds.top,
                            left + bounds.right, top + bounds.bottom, *currentTransform());
                }
            }
        }

        bool ignoreTransform = false;
        if (CC_LIKELY(pureTranslate)) {
            const float x = (int) floorf(left + currentTransform()->getTranslateX() + 0.5f);
            const float y = (int) floorf(top + currentTransform()->getTranslateY() + 0.5f);

            right = x + right - left;
            bottom = y + bottom - top;
            left = x;
            top = y;
            ignoreTransform = true;
        }
        drawIndexedTextureMesh(left, top, right, bottom, texture->id, paint,
                texture->blend, (GLvoid*) mesh->offset, (GLvoid*) mesh->textureOffset,
                GL_TRIANGLES, mesh->indexCount, false, ignoreTransform,
                mCaches.patchCache.getMeshBuffer(), kModelViewMode_Translate, !mesh->hasEmptyQuads);
    }

    mDirty = true;
}

/**
 * Important note: this method is intended to draw batches of 9-patch objects and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
void OpenGLRenderer::drawPatches(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
        TextureVertex* vertices, uint32_t indexCount, const SkPaint* paint) {
    mCaches.activeTexture(0);
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(GL_LINEAR, true);

    drawIndexedTextureMesh(0.0f, 0.0f, 1.0f, 1.0f, texture->id, paint,
            texture->blend, &vertices[0].x, &vertices[0].u,
            GL_TRIANGLES, indexCount, false, true, 0, kModelViewMode_Translate, false);

    mDirty = true;
}

void OpenGLRenderer::drawVertexBuffer(float translateX, float translateY,
        const VertexBuffer& vertexBuffer, const SkPaint* paint, int displayFlags) {
    // not missing call to quickReject/dirtyLayer, always done at a higher level
    if (!vertexBuffer.getVertexCount()) {
        // no vertices to draw
        return;
    }

    Rect bounds(vertexBuffer.getBounds());
    bounds.translate(translateX, translateY);
    dirtyLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, *currentTransform());

    int color = paint->getColor();
    bool isAA = paint->isAntiAlias();

    setupDraw();
    setupDrawNoTexture();
    if (isAA) setupDrawVertexAlpha((displayFlags & kVertexBuffer_ShadowInterp));
    setupDrawColor(color, ((color >> 24) & 0xFF) * writableSnapshot()->alpha);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawShader(getShader(paint));
    setupDrawBlending(paint, isAA);
    setupDrawProgram();
    setupDrawModelView(kModelViewMode_Translate, (displayFlags & kVertexBuffer_Offset),
            translateX, translateY, 0, 0);
    setupDrawColorUniforms(getShader(paint));
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawShaderUniforms(getShader(paint));

    const void* vertices = vertexBuffer.getBuffer();
    mRenderState.meshState().unbindMeshBuffer();
    mRenderState.meshState().bindPositionVertexPointer(mCaches.currentProgram,
            true, vertices, isAA ? kAlphaVertexStride : kVertexStride);
    mRenderState.meshState().resetTexCoordsVertexPointer();

    int alphaSlot = -1;
    if (isAA) {
        void* alphaCoords = ((GLbyte*) vertices) + kVertexAlphaOffset;
        alphaSlot = mCaches.currentProgram->getAttrib("vtxAlpha");
        // TODO: avoid enable/disable in back to back uses of the alpha attribute
        glEnableVertexAttribArray(alphaSlot);
        glVertexAttribPointer(alphaSlot, 1, GL_FLOAT, GL_FALSE, kAlphaVertexStride, alphaCoords);
    }

    const VertexBuffer::Mode mode = vertexBuffer.getMode();
    if (mode == VertexBuffer::kStandard) {
        mRenderState.meshState().unbindIndicesBuffer();
        glDrawArrays(GL_TRIANGLE_STRIP, 0, vertexBuffer.getVertexCount());
    } else if (mode == VertexBuffer::kOnePolyRingShadow) {
        mRenderState.meshState().bindShadowIndicesBuffer();
        glDrawElements(GL_TRIANGLE_STRIP, ONE_POLY_RING_SHADOW_INDEX_COUNT,
                GL_UNSIGNED_SHORT, nullptr);
    } else if (mode == VertexBuffer::kTwoPolyRingShadow) {
        mRenderState.meshState().bindShadowIndicesBuffer();
        glDrawElements(GL_TRIANGLE_STRIP, TWO_POLY_RING_SHADOW_INDEX_COUNT,
                GL_UNSIGNED_SHORT, nullptr);
    } else if (mode == VertexBuffer::kIndices) {
        mRenderState.meshState().unbindIndicesBuffer();
        glDrawElements(GL_TRIANGLE_STRIP, vertexBuffer.getIndexCount(),
                GL_UNSIGNED_SHORT, vertexBuffer.getIndices());
    }

    if (isAA) {
        glDisableVertexAttribArray(alphaSlot);
    }

    mDirty = true;
}

/**
 * Renders a convex path via tessellation. For AA paths, this function uses a similar approach to
 * that of AA lines in the drawLines() function.  We expand the convex path by a half pixel in
 * screen space in all directions. However, instead of using a fragment shader to compute the
 * translucency of the color from its position, we simply use a varying parameter to define how far
 * a given pixel is from the edge. For non-AA paths, the expansion and alpha varying are not used.
 *
 * Doesn't yet support joins, caps, or path effects.
 */
void OpenGLRenderer::drawConvexPath(const SkPath& path, const SkPaint* paint) {
    VertexBuffer vertexBuffer;
    // TODO: try clipping large paths to viewport
    PathTessellator::tessellatePath(path, paint, *currentTransform(), vertexBuffer);
    drawVertexBuffer(vertexBuffer, paint);
}

/**
 * We create tristrips for the lines much like shape stroke tessellation, using a per-vertex alpha
 * and additional geometry for defining an alpha slope perimeter.
 *
 * Using GL_LINES can be difficult because the rasterization rules for those lines produces some
 * unexpected results, and may vary between hardware devices. Previously we used a varying-base
 * in-shader alpha region, but found it to be taxing on some GPUs.
 *
 * TODO: try using a fixed input buffer for non-capped lines as in text rendering. this may reduce
 * memory transfer by removing need for degenerate vertices.
 */
void OpenGLRenderer::drawLines(const float* points, int count, const SkPaint* paint) {
    if (mState.currentlyIgnored() || count < 4) return;

    count &= ~0x3; // round down to nearest four

    VertexBuffer buffer;
    PathTessellator::tessellateLines(points, count, paint, *currentTransform(), buffer);
    const Rect& bounds = buffer.getBounds();

    if (quickRejectSetupScissor(bounds.left, bounds.top, bounds.right, bounds.bottom)) {
        return;
    }

    int displayFlags = paint->isAntiAlias() ? 0 : kVertexBuffer_Offset;
    drawVertexBuffer(buffer, paint, displayFlags);
}

void OpenGLRenderer::drawPoints(const float* points, int count, const SkPaint* paint) {
    if (mState.currentlyIgnored() || count < 2) return;

    count &= ~0x1; // round down to nearest two

    VertexBuffer buffer;
    PathTessellator::tessellatePoints(points, count, paint, *currentTransform(), buffer);

    const Rect& bounds = buffer.getBounds();
    if (quickRejectSetupScissor(bounds.left, bounds.top, bounds.right, bounds.bottom)) {
        return;
    }

    int displayFlags = paint->isAntiAlias() ? 0 : kVertexBuffer_Offset;
    drawVertexBuffer(buffer, paint, displayFlags);

    mDirty = true;
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    // No need to check against the clip, we fill the clip region
    if (mState.currentlyIgnored()) return;

    Rect clip(mState.currentClipRect());
    clip.snapToPixelBoundaries();

    SkPaint paint;
    paint.setColor(color);
    paint.setXfermodeMode(mode);

    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, &paint, true);

    mDirty = true;
}

void OpenGLRenderer::drawShape(float left, float top, const PathTexture* texture,
        const SkPaint* paint) {
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float x = left + texture->left - texture->offset;
    const float y = top + texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);

    mDirty = true;
}

void OpenGLRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    if (p->getPathEffect() != nullptr) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getRoundRect(
                right - left, bottom - top, rx, ry, p);
        drawShape(left, top, texture, p);
    } else {
        const VertexBuffer* vertexBuffer = mCaches.tessellationCache.getRoundRect(
                *currentTransform(), *p, right - left, bottom - top, rx, ry);
        drawVertexBuffer(left, top, *vertexBuffer, p);
    }
}

void OpenGLRenderer::drawCircle(float x, float y, float radius, const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(x - radius, y - radius, x + radius, y + radius, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }
    if (p->getPathEffect() != nullptr) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getCircle(radius, p);
        drawShape(x - radius, y - radius, texture, p);
    } else {
        SkPath path;
        if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
            path.addCircle(x, y, radius + p->getStrokeWidth() / 2);
        } else {
            path.addCircle(x, y, radius);
        }
        drawConvexPath(path, p);
    }
}

void OpenGLRenderer::drawOval(float left, float top, float right, float bottom,
        const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    if (p->getPathEffect() != nullptr) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getOval(right - left, bottom - top, p);
        drawShape(left, top, texture, p);
    } else {
        SkPath path;
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
            rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
        }
        path.addOval(rect);
        drawConvexPath(path, p);
    }
}

void OpenGLRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    // TODO: support fills (accounting for concavity if useCenter && sweepAngle > 180)
    if (p->getStyle() != SkPaint::kStroke_Style || p->getPathEffect() != nullptr || useCenter) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getArc(right - left, bottom - top,
                startAngle, sweepAngle, useCenter, p);
        drawShape(left, top, texture, p);
        return;
    }
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
    }

    SkPath path;
    if (useCenter) {
        path.moveTo(rect.centerX(), rect.centerY());
    }
    path.arcTo(rect, startAngle, sweepAngle, !useCenter);
    if (useCenter) {
        path.close();
    }
    drawConvexPath(path, p);
}

// See SkPaintDefaults.h
#define SkPaintDefaults_MiterLimit SkIntToScalar(4)

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    if (p->getStyle() != SkPaint::kFill_Style) {
        // only fill style is supported by drawConvexPath, since others have to handle joins
        if (p->getPathEffect() != nullptr || p->getStrokeJoin() != SkPaint::kMiter_Join ||
                p->getStrokeMiter() != SkPaintDefaults_MiterLimit) {
            mCaches.activeTexture(0);
            const PathTexture* texture =
                    mCaches.pathCache.getRect(right - left, bottom - top, p);
            drawShape(left, top, texture, p);
        } else {
            SkPath path;
            SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
            if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
                rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
            }
            path.addRect(rect);
            drawConvexPath(path, p);
        }
    } else {
        if (p->isAntiAlias() && !currentTransform()->isSimple()) {
            SkPath path;
            path.addRect(left, top, right, bottom);
            drawConvexPath(path, p);
        } else {
            drawColorRect(left, top, right, bottom, p);

            mDirty = true;
        }
    }
}

void OpenGLRenderer::drawTextShadow(const SkPaint* paint, const char* text,
        int bytesCount, int count, const float* positions,
        FontRenderer& fontRenderer, int alpha, float x, float y) {
    mCaches.activeTexture(0);

    TextShadow textShadow;
    if (!getTextShadow(paint, &textShadow)) {
        LOG_ALWAYS_FATAL("failed to query shadow attributes");
    }

    // NOTE: The drop shadow will not perform gamma correction
    //       if shader-based correction is enabled
    mCaches.dropShadowCache.setFontRenderer(fontRenderer);
    const ShadowTexture* shadow = mCaches.dropShadowCache.get(
            paint, text, bytesCount, count, textShadow.radius, positions);
    // If the drop shadow exceeds the max texture size or couldn't be
    // allocated, skip drawing
    if (!shadow) return;
    const AutoTexture autoCleanup(shadow);

    const float sx = x - shadow->left + textShadow.dx;
    const float sy = y - shadow->top + textShadow.dy;

    const int shadowAlpha = ((textShadow.color >> 24) & 0xFF) * writableSnapshot()->alpha;
    if (getShader(paint)) {
        textShadow.color = SK_ColorWHITE;
    }

    setupDraw();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(textShadow.color, shadowAlpha < 255 ? shadowAlpha : alpha);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawShader(getShader(paint));
    setupDrawBlending(paint, true);
    setupDrawProgram();
    setupDrawModelView(kModelViewMode_TranslateAndScale, false,
            sx, sy, sx + shadow->width, sy + shadow->height);
    setupDrawTexture(shadow->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawShaderUniforms(getShader(paint));
    setupDrawMesh(nullptr, (GLvoid*) kMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, kMeshCount);
}

bool OpenGLRenderer::canSkipText(const SkPaint* paint) const {
    float alpha = (hasTextShadow(paint) ? 1.0f : paint->getAlpha()) * currentSnapshot()->alpha;
    return MathUtils::isZero(alpha)
            && PaintUtils::getXfermode(paint->getXfermode()) == SkXfermode::kSrcOver_Mode;
}

void OpenGLRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, const SkPaint* paint) {
    if (text == nullptr || count == 0 || mState.currentlyIgnored() || canSkipText(paint)) {
        return;
    }

    // NOTE: Skia does not support perspective transform on drawPosText yet
    if (!currentTransform()->isSimple()) {
        return;
    }

    mRenderState.scissor().setEnabled(true);

    float x = 0.0f;
    float y = 0.0f;
    const bool pureTranslate = currentTransform()->isPureTranslate();
    if (pureTranslate) {
        x = (int) floorf(x + currentTransform()->getTranslateX() + 0.5f);
        y = (int) floorf(y + currentTransform()->getTranslateY() + 0.5f);
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkMatrix::I());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (CC_UNLIKELY(hasTextShadow(paint))) {
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer,
                alpha, 0.0f, 0.0f);
    }

    // Pick the appropriate texture filtering
    bool linearFilter = currentTransform()->changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }
    fontRenderer.setTextureFiltering(linearFilter);

    const Rect& clip(pureTranslate ? writableSnapshot()->getClipRect() : writableSnapshot()->getLocalClip());
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    TextSetupFunctor functor(this, x, y, pureTranslate, alpha, mode, paint);
    if (fontRenderer.renderPosText(paint, &clip, text, 0, bytesCount, count, x, y,
            positions, hasActiveLayer ? &bounds : nullptr, &functor)) {
        if (hasActiveLayer) {
            if (!pureTranslate) {
                currentTransform()->mapRect(bounds);
            }
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    mDirty = true;
}

bool OpenGLRenderer::findBestFontTransform(const mat4& transform, SkMatrix* outMatrix) const {
    if (CC_LIKELY(transform.isPureTranslate())) {
        outMatrix->setIdentity();
        return false;
    } else if (CC_UNLIKELY(transform.isPerspective())) {
        outMatrix->setIdentity();
        return true;
    }

    /**
     * Input is a non-perspective, scaling transform. Generate a scale-only transform,
     * with values rounded to the nearest int.
     */
    float sx, sy;
    transform.decomposeScale(sx, sy);
    outMatrix->setScale(
            roundf(fmaxf(1.0f, sx)),
            roundf(fmaxf(1.0f, sy)));
    return true;
}

int OpenGLRenderer::getSaveCount() const {
    return mState.getSaveCount();
}

int OpenGLRenderer::save(int flags) {
    return mState.save(flags);
}

void OpenGLRenderer::restore() {
    return mState.restore();
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    return mState.restoreToCount(saveCount);
}

void OpenGLRenderer::translate(float dx, float dy, float dz) {
    return mState.translate(dx, dy, dz);
}

void OpenGLRenderer::rotate(float degrees) {
    return mState.rotate(degrees);
}

void OpenGLRenderer::scale(float sx, float sy) {
    return mState.scale(sx, sy);
}

void OpenGLRenderer::skew(float sx, float sy) {
    return mState.skew(sx, sy);
}

void OpenGLRenderer::setMatrix(const Matrix4& matrix) {
    mState.setMatrix(matrix);
}

void OpenGLRenderer::concatMatrix(const Matrix4& matrix) {
    mState.concatMatrix(matrix);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    return mState.clipRect(left, top, right, bottom, op);
}

bool OpenGLRenderer::clipPath(const SkPath* path, SkRegion::Op op) {
    return mState.clipPath(path, op);
}

bool OpenGLRenderer::clipRegion(const SkRegion* region, SkRegion::Op op) {
    return mState.clipRegion(region, op);
}

void OpenGLRenderer::setClippingOutline(LinearAllocator& allocator, const Outline* outline) {
    mState.setClippingOutline(allocator, outline);
}

void OpenGLRenderer::setClippingRoundRect(LinearAllocator& allocator,
        const Rect& rect, float radius, bool highPriority) {
    mState.setClippingRoundRect(allocator, rect, radius, highPriority);
}

void OpenGLRenderer::drawText(const char* text, int bytesCount, int count, float x, float y,
        const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
        DrawOpMode drawOpMode) {

    if (drawOpMode == kDrawOpMode_Immediate) {
        // The checks for corner-case ignorable text and quick rejection is only done for immediate
        // drawing as ops from DeferredDisplayList are already filtered for these
        if (text == nullptr || count == 0 || mState.currentlyIgnored() || canSkipText(paint) ||
                quickRejectSetupScissor(bounds)) {
            return;
        }
    }

    const float oldX = x;
    const float oldY = y;

    const mat4& transform = *currentTransform();
    const bool pureTranslate = transform.isPureTranslate();

    if (CC_LIKELY(pureTranslate)) {
        x = (int) floorf(x + transform.getTranslateX() + 0.5f);
        y = (int) floorf(y + transform.getTranslateY() + 0.5f);
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);

    if (CC_UNLIKELY(hasTextShadow(paint))) {
        fontRenderer.setFont(paint, SkMatrix::I());
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer,
                alpha, oldX, oldY);
    }

    const bool hasActiveLayer = hasLayer();

    // We only pass a partial transform to the font renderer. That partial
    // matrix defines how glyphs are rasterized. Typically we want glyphs
    // to be rasterized at their final size on screen, which means the partial
    // matrix needs to take the scale factor into account.
    // When a partial matrix is used to transform glyphs during rasterization,
    // the mesh is generated with the inverse transform (in the case of scale,
    // the mesh is generated at 1.0 / scale for instance.) This allows us to
    // apply the full transform matrix at draw time in the vertex shader.
    // Applying the full matrix in the shader is the easiest way to handle
    // rotation and perspective and allows us to always generated quads in the
    // font renderer which greatly simplifies the code, clipping in particular.
    SkMatrix fontTransform;
    bool linearFilter = findBestFontTransform(transform, &fontTransform)
            || fabs(y - (int) y) > 0.0f
            || fabs(x - (int) x) > 0.0f;
    fontRenderer.setFont(paint, fontTransform);
    fontRenderer.setTextureFiltering(linearFilter);

    // TODO: Implement better clipping for scaled/rotated text
    const Rect* clip = !pureTranslate ? nullptr : &mState.currentClipRect();
    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    bool status;
    TextSetupFunctor functor(this, x, y, pureTranslate, alpha, mode, paint);

    // don't call issuedrawcommand, do it at end of batch
    bool forceFinish = (drawOpMode != kDrawOpMode_Defer);
    if (CC_UNLIKELY(paint->getTextAlign() != SkPaint::kLeft_Align)) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        status = fontRenderer.renderPosText(&paintCopy, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &layerBounds : nullptr, &functor, forceFinish);
    } else {
        status = fontRenderer.renderPosText(paint, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &layerBounds : nullptr, &functor, forceFinish);
    }

    if ((status || drawOpMode != kDrawOpMode_Immediate) && hasActiveLayer) {
        if (!pureTranslate) {
            transform.mapRect(layerBounds);
        }
        dirtyLayerUnchecked(layerBounds, getRegion());
    }

    drawTextDecorations(totalAdvance, oldX, oldY, paint);

    mDirty = true;
}

void OpenGLRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        const SkPath* path, float hOffset, float vOffset, const SkPaint* paint) {
    if (text == nullptr || count == 0 || mState.currentlyIgnored() || canSkipText(paint)) {
        return;
    }

    // TODO: avoid scissor by calculating maximum bounds using path bounds + font metrics
    mRenderState.scissor().setEnabled(true);

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkMatrix::I());
    fontRenderer.setTextureFiltering(true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);
    TextSetupFunctor functor(this, 0.0f, 0.0f, false, alpha, mode, paint);

    const Rect* clip = &writableSnapshot()->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    if (fontRenderer.renderTextOnPath(paint, clip, text, 0, bytesCount, count, path,
            hOffset, vOffset, hasActiveLayer ? &bounds : nullptr, &functor)) {
        if (hasActiveLayer) {
            currentTransform()->mapRect(bounds);
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    mDirty = true;
}

void OpenGLRenderer::drawPath(const SkPath* path, const SkPaint* paint) {
    if (mState.currentlyIgnored()) return;

    mCaches.activeTexture(0);

    const PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);
    mDirty = true;
}

void OpenGLRenderer::drawLayer(Layer* layer, float x, float y) {
    if (!layer) {
        return;
    }

    mat4* transform = nullptr;
    if (layer->isTextureLayer()) {
        transform = &layer->getTransform();
        if (!transform->isIdentity()) {
            save(SkCanvas::kMatrix_SaveFlag);
            concatMatrix(*transform);
        }
    }

    bool clipRequired = false;
    const bool rejected = mState.calculateQuickRejectForScissor(
            x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight(),
            &clipRequired, nullptr, false);

    if (rejected) {
        if (transform && !transform->isIdentity()) {
            restore();
        }
        return;
    }

    EVENT_LOGD("drawLayer," RECT_STRING ", clipRequired %d", x, y,
            x + layer->layer.getWidth(), y + layer->layer.getHeight(), clipRequired);

    updateLayer(layer, true);

    mRenderState.scissor().setEnabled(mScissorOptimizationDisabled || clipRequired);
    mCaches.activeTexture(0);

    if (CC_LIKELY(!layer->region.isEmpty())) {
        if (layer->region.isRect()) {
            DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate,
                    composeLayerRect(layer, layer->regionRect));
        } else if (layer->mesh) {

            const float a = getLayerAlpha(layer);
            setupDraw();
            setupDrawWithTexture();
            setupDrawColor(a, a, a, a);
            setupDrawColorFilter(layer->getColorFilter());
            setupDrawBlending(layer);
            setupDrawProgram();
            setupDrawPureColorUniforms();
            setupDrawColorFilterUniforms(layer->getColorFilter());
            setupDrawTexture(layer->getTexture());
            if (CC_LIKELY(currentTransform()->isPureTranslate())) {
                int tx = (int) floorf(x + currentTransform()->getTranslateX() + 0.5f);
                int ty = (int) floorf(y + currentTransform()->getTranslateY() + 0.5f);

                layer->setFilter(GL_NEAREST);
                setupDrawModelView(kModelViewMode_Translate, false, tx, ty,
                        tx + layer->layer.getWidth(), ty + layer->layer.getHeight(), true);
            } else {
                layer->setFilter(GL_LINEAR);
                setupDrawModelView(kModelViewMode_Translate, false, x, y,
                        x + layer->layer.getWidth(), y + layer->layer.getHeight());
            }

            TextureVertex* mesh = &layer->mesh[0];
            GLsizei elementsCount = layer->meshElementCount;

            while (elementsCount > 0) {
                GLsizei drawCount = min(elementsCount, (GLsizei) kMaxNumberOfQuads * 6);

                setupDrawMeshIndices(&mesh[0].x, &mesh[0].u);
                DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate,
                        glDrawElements(GL_TRIANGLES, drawCount, GL_UNSIGNED_SHORT, nullptr));

                elementsCount -= drawCount;
                // Though there are 4 vertices in a quad, we use 6 indices per
                // quad to draw with GL_TRIANGLES
                mesh += (drawCount / 6) * 4;
            }

#if DEBUG_LAYERS_AS_REGIONS
            drawRegionRectsDebug(layer->region);
#endif
        }

        if (layer->debugDrawUpdate) {
            layer->debugDrawUpdate = false;

            SkPaint paint;
            paint.setColor(0x7f00ff00);
            drawColorRect(x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight(), &paint);
        }
    }
    layer->hasDrawnSinceUpdate = true;

    if (transform && !transform->isIdentity()) {
        restore();
    }

    mDirty = true;
}

///////////////////////////////////////////////////////////////////////////////
// Draw filters
///////////////////////////////////////////////////////////////////////////////
void OpenGLRenderer::setDrawFilter(SkDrawFilter* filter) {
    // We should never get here since we apply the draw filter when stashing
    // the paints in the DisplayList.
    LOG_ALWAYS_FATAL("OpenGLRenderer does not directly support DrawFilters");
}

///////////////////////////////////////////////////////////////////////////////
// Drawing implementation
///////////////////////////////////////////////////////////////////////////////

Texture* OpenGLRenderer::getTexture(const SkBitmap* bitmap) {
    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap);
    if (!texture) {
        return mCaches.textureCache.get(bitmap);
    }
    return texture;
}

void OpenGLRenderer::drawPathTexture(const PathTexture* texture,
        float x, float y, const SkPaint* paint) {
    if (quickRejectSetupScissor(x, y, x + texture->width, y + texture->height)) {
        return;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    setupDraw();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawShader(getShader(paint));
    setupDrawBlending(paint, true);
    setupDrawProgram();
    setupDrawModelView(kModelViewMode_TranslateAndScale, false,
            x, y, x + texture->width, y + texture->height);
    setupDrawTexture(texture->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawShaderUniforms(getShader(paint));
    setupDrawMesh(nullptr, (GLvoid*) kMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, kMeshCount);
}

// Same values used by Skia
#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

void OpenGLRenderer::drawTextDecorations(float underlineWidth, float x, float y,
        const SkPaint* paint) {
    // Handle underline and strike-through
    uint32_t flags = paint->getFlags();
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        SkPaint paintCopy(*paint);

        if (CC_LIKELY(underlineWidth > 0.0f)) {
            const float textSize = paintCopy.getTextSize();
            const float strokeWidth = fmax(textSize * kStdUnderline_Thickness, 1.0f);

            const float left = x;
            float top = 0.0f;

            int linesCount = 0;
            if (flags & SkPaint::kUnderlineText_Flag) linesCount++;
            if (flags & SkPaint::kStrikeThruText_Flag) linesCount++;

            const int pointsCount = 4 * linesCount;
            float points[pointsCount];
            int currentPoint = 0;

            if (flags & SkPaint::kUnderlineText_Flag) {
                top = y + textSize * kStdUnderline_Offset;
                points[currentPoint++] = left;
                points[currentPoint++] = top;
                points[currentPoint++] = left + underlineWidth;
                points[currentPoint++] = top;
            }

            if (flags & SkPaint::kStrikeThruText_Flag) {
                top = y + textSize * kStdStrikeThru_Offset;
                points[currentPoint++] = left;
                points[currentPoint++] = top;
                points[currentPoint++] = left + underlineWidth;
                points[currentPoint++] = top;
            }

            paintCopy.setStrokeWidth(strokeWidth);

            drawLines(&points[0], pointsCount, &paintCopy);
        }
    }
}

void OpenGLRenderer::drawRects(const float* rects, int count, const SkPaint* paint) {
    if (mState.currentlyIgnored()) {
        return;
    }

    drawColorRects(rects, count, paint, false, true, true);
}

void OpenGLRenderer::drawShadow(float casterAlpha,
        const VertexBuffer* ambientShadowVertexBuffer, const VertexBuffer* spotShadowVertexBuffer) {
    if (mState.currentlyIgnored()) return;

    // TODO: use quickRejectWithScissor. For now, always force enable scissor.
    mRenderState.scissor().setEnabled(true);

    SkPaint paint;
    paint.setAntiAlias(true); // want to use AlphaVertex

    // The caller has made sure casterAlpha > 0.
    float ambientShadowAlpha = mAmbientShadowAlpha;
    if (CC_UNLIKELY(mCaches.propertyAmbientShadowStrength >= 0)) {
        ambientShadowAlpha = mCaches.propertyAmbientShadowStrength;
    }
    if (ambientShadowVertexBuffer && ambientShadowAlpha > 0) {
        paint.setARGB(casterAlpha * ambientShadowAlpha, 0, 0, 0);
        drawVertexBuffer(*ambientShadowVertexBuffer, &paint, kVertexBuffer_ShadowInterp);
    }

    float spotShadowAlpha = mSpotShadowAlpha;
    if (CC_UNLIKELY(mCaches.propertySpotShadowStrength >= 0)) {
        spotShadowAlpha = mCaches.propertySpotShadowStrength;
    }
    if (spotShadowVertexBuffer && spotShadowAlpha > 0) {
        paint.setARGB(casterAlpha * spotShadowAlpha, 0, 0, 0);
        drawVertexBuffer(*spotShadowVertexBuffer, &paint, kVertexBuffer_ShadowInterp);
    }

    mDirty=true;
}

void OpenGLRenderer::drawColorRects(const float* rects, int count, const SkPaint* paint,
        bool ignoreTransform, bool dirty, bool clip) {
    if (count == 0) {
        return;
    }

    int color = paint->getColor();
    // If a shader is set, preserve only the alpha
    if (getShader(paint)) {
        color |= 0x00ffffff;
    }

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

    Vertex mesh[count];
    Vertex* vertex = mesh;

    for (int index = 0; index < count; index += 4) {
        float l = rects[index + 0];
        float t = rects[index + 1];
        float r = rects[index + 2];
        float b = rects[index + 3];

        Vertex::set(vertex++, l, t);
        Vertex::set(vertex++, r, t);
        Vertex::set(vertex++, l, b);
        Vertex::set(vertex++, r, b);

        left = fminf(left, l);
        top = fminf(top, t);
        right = fmaxf(right, r);
        bottom = fmaxf(bottom, b);
    }

    if (clip && quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    setupDraw();
    setupDrawNoTexture();
    setupDrawColor(color, ((color >> 24) & 0xFF) * currentSnapshot()->alpha);
    setupDrawShader(getShader(paint));
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawModelView(kModelViewMode_Translate, false,
            0.0f, 0.0f, 0.0f, 0.0f, ignoreTransform);
    setupDrawColorUniforms(getShader(paint));
    setupDrawShaderUniforms(getShader(paint));
    setupDrawColorFilterUniforms(getColorFilter(paint));

    if (dirty && hasLayer()) {
        dirtyLayer(left, top, right, bottom, *currentTransform());
    }

    issueIndexedQuadDraw(&mesh[0], count / 4);

    mDirty = true;
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        const SkPaint* paint, bool ignoreTransform) {
    int color = paint->getColor();
    // If a shader is set, preserve only the alpha
    if (getShader(paint)) {
        color |= 0x00ffffff;
    }

    setupDraw();
    setupDrawNoTexture();
    setupDrawColor(color, ((color >> 24) & 0xFF) * currentSnapshot()->alpha);
    setupDrawShader(getShader(paint));
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint);
    setupDrawProgram();
    setupDrawModelView(kModelViewMode_TranslateAndScale, false,
            left, top, right, bottom, ignoreTransform);
    setupDrawColorUniforms(getShader(paint));
    setupDrawShaderUniforms(getShader(paint), ignoreTransform);
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawSimpleMesh();

    glDrawArrays(GL_TRIANGLE_STRIP, 0, kMeshCount);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        Texture* texture, const SkPaint* paint) {
    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    GLvoid* vertices = (GLvoid*) nullptr;
    GLvoid* texCoords = (GLvoid*) kMeshTextureOffset;

    if (texture->uvMapper) {
        vertices = &mMeshVertices[0].x;
        texCoords = &mMeshVertices[0].u;

        Rect uvs(0.0f, 0.0f, 1.0f, 1.0f);
        texture->uvMapper->map(uvs);

        resetDrawTextureTexCoords(uvs.left, uvs.top, uvs.right, uvs.bottom);
    }

    if (CC_LIKELY(currentTransform()->isPureTranslate())) {
        const float x = (int) floorf(left + currentTransform()->getTranslateX() + 0.5f);
        const float y = (int) floorf(top + currentTransform()->getTranslateY() + 0.5f);

        texture->setFilter(GL_NEAREST, true);
        drawTextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
                paint, texture->blend, vertices, texCoords,
                GL_TRIANGLE_STRIP, kMeshCount, false, true);
    } else {
        texture->setFilter(getFilter(paint), true);
        drawTextureMesh(left, top, right, bottom, texture->id, paint,
                texture->blend, vertices, texCoords, GL_TRIANGLE_STRIP, kMeshCount);
    }

    if (texture->uvMapper) {
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    }
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, const SkPaint* paint, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform, GLuint vbo,
        ModelViewMode modelViewMode, bool dirty) {

    int a;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &a, &mode);
    const float alpha = a / 255.0f;

    setupDraw();
    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint, blend, swapSrcDst);
    setupDrawProgram();
    if (!dirty) setupDrawDirtyRegionsDisabled();
    setupDrawModelView(modelViewMode, false, left, top, right, bottom, ignoreTransform);
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawMesh(vertices, texCoords, vbo);

    glDrawArrays(drawMode, 0, elementsCount);
}

void OpenGLRenderer::drawIndexedTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, const SkPaint* paint, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform, GLuint vbo,
        ModelViewMode modelViewMode, bool dirty) {

    int a;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &a, &mode);
    const float alpha = a / 255.0f;

    setupDraw();
    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawBlending(paint, blend, swapSrcDst);
    setupDrawProgram();
    if (!dirty) setupDrawDirtyRegionsDisabled();
    setupDrawModelView(modelViewMode, false, left, top, right, bottom, ignoreTransform);
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawMeshIndices(vertices, texCoords, vbo);

    glDrawElements(drawMode, elementsCount, GL_UNSIGNED_SHORT, nullptr);
}

void OpenGLRenderer::drawAlpha8TextureMesh(float left, float top, float right, float bottom,
        GLuint texture, const SkPaint* paint,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool ignoreTransform, ModelViewMode modelViewMode, bool dirty) {

    int color = paint != nullptr ? paint->getColor() : 0;
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    setupDraw();
    setupDrawWithTexture(true);
    if (paint != nullptr) {
        setupDrawAlpha8Color(color, alpha);
    }
    setupDrawColorFilter(getColorFilter(paint));
    setupDrawShader(getShader(paint));
    setupDrawBlending(paint, true);
    setupDrawProgram();
    if (!dirty) setupDrawDirtyRegionsDisabled();
    setupDrawModelView(modelViewMode, false, left, top, right, bottom, ignoreTransform);
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms(getColorFilter(paint));
    setupDrawShaderUniforms(getShader(paint), ignoreTransform);
    setupDrawMesh(vertices, texCoords);

    glDrawArrays(drawMode, 0, elementsCount);
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode,
        ProgramDescription& description, bool swapSrcDst) {

    if (writableSnapshot()->roundRectClipState != nullptr /*&& !mSkipOutlineClip*/) {
        blend = true;
        mDescription.hasRoundRectClip = true;
    }
    mSkipOutlineClip = true;

    blend = blend || mode != SkXfermode::kSrcOver_Mode;

    if (blend) {
        // These blend modes are not supported by OpenGL directly and have
        // to be implemented using shaders. Since the shader will perform
        // the blending, turn blending off here
        // If the blend mode cannot be implemented using shaders, fall
        // back to the default SrcOver blend mode instead
        if (CC_UNLIKELY(mode > SkXfermode::kScreen_Mode)) {
            if (CC_UNLIKELY(mExtensions.hasFramebufferFetch())) {
                description.framebufferMode = mode;
                description.swapSrcDst = swapSrcDst;

                if (mCaches.blend) {
                    glDisable(GL_BLEND);
                    mCaches.blend = false;
                }

                return;
            } else {
                mode = SkXfermode::kSrcOver_Mode;
            }
        }

        if (!mCaches.blend) {
            glEnable(GL_BLEND);
        }

        GLenum sourceMode = swapSrcDst ? gBlendsSwap[mode].src : gBlends[mode].src;
        GLenum destMode = swapSrcDst ? gBlendsSwap[mode].dst : gBlends[mode].dst;

        if (sourceMode != mCaches.lastSrcMode || destMode != mCaches.lastDstMode) {
            glBlendFunc(sourceMode, destMode);
            mCaches.lastSrcMode = sourceMode;
            mCaches.lastDstMode = destMode;
        }
    } else if (mCaches.blend) {
        glDisable(GL_BLEND);
    }
    mCaches.blend = blend;
}

bool OpenGLRenderer::useProgram(Program* program) {
    if (!program->isInUse()) {
        if (mCaches.currentProgram != nullptr) mCaches.currentProgram->remove();
        program->use();
        mCaches.currentProgram = program;
        return false;
    }
    return true;
}

void OpenGLRenderer::resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    TextureVertex* v = &mMeshVertices[0];
    TextureVertex::setUV(v++, u1, v1);
    TextureVertex::setUV(v++, u2, v1);
    TextureVertex::setUV(v++, u1, v2);
    TextureVertex::setUV(v++, u2, v2);
}

void OpenGLRenderer::getAlphaAndMode(const SkPaint* paint, int* alpha,
        SkXfermode::Mode* mode) const {
    getAlphaAndModeDirect(paint, alpha,  mode);
    if (mDrawModifiers.mOverrideLayerAlpha < 1.0f) {
        // if drawing a layer, ignore the paint's alpha
        *alpha = mDrawModifiers.mOverrideLayerAlpha * 255;
    }
    *alpha *= currentSnapshot()->alpha;
}

float OpenGLRenderer::getLayerAlpha(const Layer* layer) const {
    float alpha;
    if (mDrawModifiers.mOverrideLayerAlpha < 1.0f) {
        alpha = mDrawModifiers.mOverrideLayerAlpha;
    } else {
        alpha = layer->getAlpha() / 255.0f;
    }
    return alpha * currentSnapshot()->alpha;
}

}; // namespace uirenderer
}; // namespace android
