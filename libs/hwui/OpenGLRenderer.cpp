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

#include <GpuMemoryTracker.h>
#include "OpenGLRenderer.h"

#include "DeferredDisplayList.h"
#include "GammaFontRenderer.h"
#include "Glop.h"
#include "GlopBuilder.h"
#include "Patch.h"
#include "PathTessellator.h"
#include "Properties.h"
#include "RenderNode.h"
#include "renderstate/MeshState.h"
#include "renderstate/RenderState.h"
#include "ShadowTessellator.h"
#include "SkiaShader.h"
#include "Vector.h"
#include "VertexBuffer.h"
#include "hwui/Canvas.h"
#include "utils/GLUtils.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <SkColor.h>
#include <SkPaintDefaults.h>
#include <SkPathOps.h>
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

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer(RenderState& renderState)
        : mState(*this)
        , mCaches(Caches::getInstance())
        , mRenderState(renderState)
        , mFrameStarted(false)
        , mScissorOptimizationDisabled(false)
        , mDirty(false)
        , mLightCenter((Vector3){FLT_MIN, FLT_MIN, FLT_MIN})
        , mLightRadius(FLT_MIN)
        , mAmbientShadowAlpha(0)
        , mSpotShadowAlpha(0) {
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

void OpenGLRenderer::initLight(float lightRadius, uint8_t ambientShadowAlpha,
        uint8_t spotShadowAlpha) {
    mLightRadius = lightRadius;
    mAmbientShadowAlpha = ambientShadowAlpha;
    mSpotShadowAlpha = spotShadowAlpha;
}

void OpenGLRenderer::setLightCenter(const Vector3& lightCenter) {
    mLightCenter = lightCenter;
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::onViewportInitialized() {
    glDisable(GL_DITHER);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
}

void OpenGLRenderer::setupFrameState(int viewportWidth, int viewportHeight,
        float left, float top, float right, float bottom, bool opaque) {
    mCaches.clearGarbage();
    mState.initializeSaveStack(viewportWidth, viewportHeight,
            left, top, right, bottom, mLightCenter);
    mOpaque = opaque;
    mTilingClip.set(left, top, right, bottom);
}

void OpenGLRenderer::startFrame() {
    if (mFrameStarted) return;
    mFrameStarted = true;

    mState.setDirtyClip(true);

    discardFramebuffer(mTilingClip.left, mTilingClip.top, mTilingClip.right, mTilingClip.bottom);

    mRenderState.setViewport(mState.getWidth(), mState.getHeight());

    debugOverdraw(true, true);

    clear(mTilingClip.left, mTilingClip.top,
            mTilingClip.right, mTilingClip.bottom, mOpaque);
}

void OpenGLRenderer::prepareDirty(int viewportWidth, int viewportHeight,
        float left, float top, float right, float bottom, bool opaque) {

    setupFrameState(viewportWidth, viewportHeight, left, top, right, bottom, opaque);

    // Layer renderers will start the frame immediately
    // The framebuffer renderer will first defer the display list
    // for each layer and wait until the first drawing command
    // to start the frame
    if (currentSnapshot()->fbo == 0) {
        mRenderState.blend().syncEnabled();
        updateLayers();
    } else {
        startFrame();
    }
}

void OpenGLRenderer::discardFramebuffer(float left, float top, float right, float bottom) {
    // If we know that we are going to redraw the entire framebuffer,
    // perform a discard to let the driver know we don't need to preserve
    // the back buffer for this frame.
    if (mCaches.extensions().hasDiscardFramebuffer() &&
            left <= 0.0f && top <= 0.0f && right >= mState.getWidth() && bottom >= mState.getHeight()) {
        const bool isFbo = getTargetFbo() == 0;
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

bool OpenGLRenderer::finish() {
    renderOverdraw();
    mTempPaths.clear();

    // When finish() is invoked on FBO 0 we've reached the end
    // of the current frame
    if (getTargetFbo() == 0) {
        mCaches.pathCache.trim();
        mCaches.tessellationCache.trim();
    }

    if (!suppressErrorChecks()) {
        GL_CHECKPOINT(MODERATE);

#if DEBUG_MEMORY_USAGE
        mCaches.dumpMemoryUsage();
        GPUMemoryTracker::dump();
#else
        if (Properties::debugLevel & kDebugMemory) {
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

    Rect clip(mState.currentRenderTargetClip());
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
    if (Properties::debugOverdraw && getTargetFbo() == 0) {
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
            debugOverdraw(false, false);
        }

        if (CC_UNLIKELY(inFrame || Properties::drawDeferDisabled)) {
            layer->render(*this);
        } else {
            layer->defer(*this);
        }

        if (inFrame) {
            resumeAfterLayer();
        }

        layer->debugDrawUpdate = Properties::debugLayersUpdates;
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
        if (CC_UNLIKELY(Properties::drawDeferDisabled)) {
            startMark("Layer Updates");
        } else {
            startMark("Defer Layer Updates");
        }

        // Note: it is very important to update the layers in order
        for (int i = 0; i < count; i++) {
            Layer* layer = mLayerUpdates[i].get();
            updateLayer(layer, false);
        }

        if (CC_UNLIKELY(Properties::drawDeferDisabled)) {
            mLayerUpdates.clear();
            mRenderState.bindFramebuffer(getTargetFbo());
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
            mLayerUpdates[i]->flush();
        }

        mLayerUpdates.clear();
        mRenderState.bindFramebuffer(getTargetFbo());

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
            if (mLayerUpdates[i] == layer) {
                return;
            }
        }
        mLayerUpdates.push_back(layer);
    }
}

void OpenGLRenderer::cancelLayerUpdate(Layer* layer) {
    if (layer) {
        for (int i = mLayerUpdates.size() - 1; i >= 0; i--) {
            if (mLayerUpdates[i] == layer) {
                mLayerUpdates.erase(mLayerUpdates.begin() + i);
                break;
            }
        }
    }
}

void OpenGLRenderer::flushLayerUpdates() {
    ATRACE_NAME("Update HW Layers");
    mRenderState.blend().syncEnabled();
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
    flags |= SaveFlags::MatrixClip;

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
    bounds.doIntersect(mState.currentRenderTargetClip());
    if (!bounds.isEmpty()) {
        // We cannot work with sub-pixels in this case
        bounds.snapToPixelBoundaries();

        // When the layer is not an FBO, we may use glCopyTexImage so we
        // need to make sure the layer does not extend outside the bounds
        // of the framebuffer
        const Snapshot& previous = *(currentSnapshot()->previous);
        Rect previousViewport(0, 0, previous.getViewportWidth(), previous.getViewportHeight());

        bounds.doIntersect(previousViewport);
        if (!bounds.isEmpty() && fboLayer) {
            clip.set(bounds);
            mat4 inverse;
            inverse.loadInverse(*currentTransform());
            inverse.mapRect(clip);
            clip.snapToPixelBoundaries();
            clip.doIntersect(untransformedBounds);
            if (!clip.isEmpty()) {
                clip.translate(-untransformedBounds.left, -untransformedBounds.top);
                bounds.set(untransformedBounds);
            }
        }
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

    if (!mState.currentlyIgnored() && (flags & SaveFlags::ClipToLayer)) {
        // initialize the snapshot as though it almost represents an FBO layer so deferred draw
        // operations will be able to store and restore the current clip and transform info, and
        // quick rejection will be correct (for display lists)

        Rect bounds(left, top, right, bottom);
        Rect clip;
        calculateLayerBoundsAndClip(bounds, clip, true);
        updateSnapshotIgnoreForLayer(bounds, clip, true, PaintUtils::getAlphaDirect(paint));

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
 * created with the SaveFlags::ClipToLayer flag.)
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

    const bool fboLayer = flags & SaveFlags::ClipToLayer;

    // Window coordinates of the layer
    Rect clip;
    Rect bounds(left, top, right, bottom);
    calculateLayerBoundsAndClip(bounds, clip, fboLayer);
    updateSnapshotIgnoreForLayer(bounds, clip, fboLayer, PaintUtils::getAlphaDirect(paint));

    // Bail out if we won't draw in this snapshot
    if (mState.currentlyIgnored()) {
        return false;
    }

    mCaches.textureState().activateTexture(0);
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
    layer->setFbo(mRenderState.createFramebuffer());

    writableSnapshot()->region = &writableSnapshot()->layer->region;
    writableSnapshot()->flags |= Snapshot::kFlagFboTarget | Snapshot::kFlagIsFboLayer;
    writableSnapshot()->fbo = layer->getFbo();
    writableSnapshot()->resetTransform(-bounds.left, -bounds.top, 0.0f);
    writableSnapshot()->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    writableSnapshot()->initializeViewport(bounds.getWidth(), bounds.getHeight());
    writableSnapshot()->roundRectClipState = nullptr;

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
            layer->getTextureId(), 0);

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
        // Detach the texture from the FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);

        layer->removeFbo(false);

        // Unbind current FBO and restore previous one
        mRenderState.bindFramebuffer(restored.fbo);
        debugOverdraw(true, false);
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

    mCaches.textureState().activateTexture(0);

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
        composeLayerRectSwapped(layer, rect);
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
    const bool tryToSnap = !layer->getForceFilter()
            && layer->getWidth() == (uint32_t) rect.getWidth()
            && layer->getHeight() == (uint32_t) rect.getHeight();
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUvQuad(nullptr, Rect(0, 1, 1, 0)) // TODO: simplify with VBO
            .setFillTextureLayer(*layer, getLayerAlpha(layer))
            .setTransform(*currentSnapshot(), TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap, rect)
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::composeLayerRectSwapped(Layer* layer, const Rect& rect) {
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUvQuad(nullptr, layer->texCoords)
            .setFillLayer(layer->getTexture(), layer->getColorFilter(),
                    getLayerAlpha(layer), layer->getMode(), Blend::ModeOrderSwap::Swap)
            .setTransform(*currentSnapshot(), TransformFlags::MeshIgnoresCanvasTransform)
            .setModelViewMapUnitToRect(rect)
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect) {
    if (layer->isTextureLayer()) {
        EVENT_LOGD("composeTextureLayerRect");
        drawTextureLayer(layer, rect);
    } else {
        EVENT_LOGD("composeHardwareLayerRect");

        const bool tryToSnap = layer->getWidth() == static_cast<uint32_t>(rect.getWidth())
                && layer->getHeight() == static_cast<uint32_t>(rect.getHeight());
        Glop glop;
        GlopBuilder(mRenderState, mCaches, &glop)
                .setRoundRectClipState(currentSnapshot()->roundRectClipState)
                .setMeshTexturedUvQuad(nullptr, layer->texCoords)
                .setFillLayer(layer->getTexture(), layer->getColorFilter(), getLayerAlpha(layer), layer->getMode(), Blend::ModeOrderSwap::NoSwap)
                .setTransform(*currentSnapshot(), TransformFlags::None)
                .setModelViewMapUnitToRectOptionalSnap(tryToSnap, rect)
                .build();
        renderGlop(glop);
    }
}

/**
 * Issues the command X, and if we're composing a save layer to the fbo or drawing a newly updated
 * hardware layer with overdraw debug on, draws again to the stencil only, so that these draw
 * operations are correctly counted twice for overdraw. NOTE: assumes composeLayerRegion only used
 * by saveLayer's restore
 */
#define DRAW_DOUBLE_STENCIL_IF(COND, DRAW_COMMAND) { \
        DRAW_COMMAND; \
        if (CC_UNLIKELY(Properties::debugOverdraw && getTargetFbo() == 0 && COND)) { \
            glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE); \
            DRAW_COMMAND; \
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE); \
        } \
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
        save(SaveFlags::MatrixClip);

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

    const float texX = 1.0f / float(layer->getWidth());
    const float texY = 1.0f / float(layer->getHeight());
    const float height = rect.getHeight();

    TextureVertex quadVertices[count * 4];
    TextureVertex* mesh = &quadVertices[0];
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
    }
    Rect modelRect = Rect(rect.getWidth(), rect.getHeight());
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedIndexedQuads(&quadVertices[0], count * 6)
            .setFillLayer(layer->getTexture(), layer->getColorFilter(), getLayerAlpha(layer), layer->getMode(), Blend::ModeOrderSwap::NoSwap)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewOffsetRectSnap(rect.left, rect.top, modelRect)
            .build();
    DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate, renderGlop(glop));

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
        const float right, const float bottom, const Matrix4& transform) {
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
    bounds.doIntersect(mState.currentRenderTargetClip());
    if (!bounds.isEmpty()) {
        bounds.snapToPixelBoundaries();
        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            region->orSelf(dirty);
        }
    }
}

void OpenGLRenderer::clearLayerRegions() {
    const size_t quadCount = mLayers.size();
    if (quadCount == 0) return;

    if (!mState.currentlyIgnored()) {
        EVENT_LOGD("clearLayerRegions");
        // Doing several glScissor/glClear here can negatively impact
        // GPUs with a tiler architecture, instead we draw quads with
        // the Clear blending mode

        // The list contains bounds that have already been clipped
        // against their initial clip rect, and the current clip
        // is likely different so we need to disable clipping here
        bool scissorChanged = mRenderState.scissor().setEnabled(false);

        Vertex mesh[quadCount * 4];
        Vertex* vertex = mesh;

        for (uint32_t i = 0; i < quadCount; i++) {
            const Rect& bounds = mLayers[i];

            Vertex::set(vertex++, bounds.left, bounds.top);
            Vertex::set(vertex++, bounds.right, bounds.top);
            Vertex::set(vertex++, bounds.left, bounds.bottom);
            Vertex::set(vertex++, bounds.right, bounds.bottom);
        }
        // We must clear the list of dirty rects before we
        // call clearLayerRegions() in renderGlop to prevent
        // stencil setup from doing the same thing again
        mLayers.clear();

        const int transformFlags = TransformFlags::MeshIgnoresCanvasTransform;
        Glop glop;
        GlopBuilder(mRenderState, mCaches, &glop)
                .setRoundRectClipState(nullptr) // clear ignores clip state
                .setMeshIndexedQuads(&mesh[0], quadCount)
                .setFillClear()
                .setTransform(*currentSnapshot(), transformFlags)
                .setModelViewOffsetRect(0, 0, Rect(currentSnapshot()->getRenderTargetClip()))
                .build();
        renderGlop(glop, GlopRenderType::LayerClear);

        if (scissorChanged) mRenderState.scissor().setEnabled(true);
    } else {
        mLayers.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// State Deferral
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::storeDisplayState(DeferredDisplayState& state, int stateDeferFlags) {
    const Rect& currentClip = mState.currentRenderTargetClip();
    const mat4* currentMatrix = currentTransform();

    if (stateDeferFlags & kStateDeferFlag_Draw) {
        // state has bounds initialized in local coordinates
        if (!state.mBounds.isEmpty()) {
            currentMatrix->mapRect(state.mBounds);
            Rect clippedBounds(state.mBounds);
            // NOTE: if we ever want to use this clipping info to drive whether the scissor
            // is used, it should more closely duplicate the quickReject logic (in how it uses
            // snapToPixelBoundaries)

            clippedBounds.doIntersect(currentClip);
            if (clippedBounds.isEmpty()) {
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

    // Transform and alpha always deferred, since they are used by state operations
    // (Note: saveLayer/restore use colorFilter and alpha, so we just save restore everything)
    state.mMatrix = *currentMatrix;
    state.mAlpha = currentSnapshot()->alpha;

    // always store/restore, since these are just pointers
    state.mRoundRectClipState = currentSnapshot()->roundRectClipState;
#if !HWUI_NEW_OPS
    state.mProjectionPathMask = currentSnapshot()->projectionPathMask;
#endif
    return false;
}

void OpenGLRenderer::restoreDisplayState(const DeferredDisplayState& state, bool skipClipRestore) {
    setGlobalMatrix(state.mMatrix);
    writableSnapshot()->alpha = state.mAlpha;
    writableSnapshot()->roundRectClipState = state.mRoundRectClipState;
#if !HWUI_NEW_OPS
    writableSnapshot()->projectionPathMask = state.mProjectionPathMask;
#endif

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
    Rect clip(mState.currentRenderTargetClip());
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
        RenderBuffer* buffer = mCaches.renderBufferCache.get(
                Stencil::getLayerStencilFormat(),
                layer->getWidth(), layer->getHeight());
        layer->setStencilRenderBuffer(buffer);
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
    int quadCount = rectangleList.getTransformedRectanglesCount();
    std::vector<Vertex> rectangleVertices(quadCount * 4);
    Rect scissorBox = rectangleList.calculateBounds();
    scissorBox.snapToPixelBoundaries();
    for (int i = 0; i < quadCount; ++i) {
        const TransformedRectangle& tr(rectangleList.getTransformedRectangle(i));
        const Matrix4& transform = tr.getTransform();
        Rect bounds = tr.getBounds();
        if (transform.rectToRect()) {
            transform.mapRect(bounds);
            bounds.doIntersect(scissorBox);
            if (!bounds.isEmpty()) {
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
    const int transformFlags = TransformFlags::MeshIgnoresCanvasTransform;
    Glop glop;
    Vertex* vertices = &rectangleVertices[0];
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshIndexedQuads(vertices, rectangleVertices.size() / 4)
            .setFillBlack()
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewOffsetRect(0, 0, scissorBox)
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::setStencilFromClip() {
    if (!Properties::debugOverdraw) {
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
            if (Properties::debugStencilClip == StencilClipDebug::ShowRegion
                    && !clipArea.isRectangleList()) {
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

void OpenGLRenderer::renderGlop(const Glop& glop, GlopRenderType type) {
    // TODO: It would be best if we could do this before quickRejectSetupScissor()
    //       changes the scissor test state
    if (type != GlopRenderType::LayerClear) {
        // Regular draws need to clear the dirty area on the layer before they start drawing on top
        // of it. If this draw *is* a layer clear, it skips the clear step (since it would
        // infinitely recurse)
        clearLayerRegions();
    }

    if (mState.getDirtyClip()) {
        if (mRenderState.scissor().isEnabled()) {
            setScissorFromClip();
        }

        setStencilFromClip();
    }
    mRenderState.render(glop, currentSnapshot()->getOrthoMatrix());
    if (type == GlopRenderType::Standard && !mRenderState.stencil().isWriteEnabled()) {
        // TODO: specify more clearly when a draw should dirty the layer.
        // is writing to the stencil the only time we should ignore this?
#if !HWUI_NEW_OPS
        dirtyLayer(glop.bounds.left, glop.bounds.top, glop.bounds.right, glop.bounds.bottom);
#endif
        mDirty = true;
    }
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
        if (CC_UNLIKELY(Properties::drawDeferDisabled)) {
            startFrame();
            ReplayStateStruct replayStruct(*this, dirty, replayFlags);
            renderNode->replay(replayStruct, 0);
            return;
        }

        DeferredDisplayList deferredList(mState.currentRenderTargetClip());
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

/**
 * Important note: this method is intended to draw batches of bitmaps and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
void OpenGLRenderer::drawBitmaps(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
        int bitmapCount, TextureVertex* vertices, bool pureTranslate,
        const Rect& bounds, const SkPaint* paint) {
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return;

    const AutoTexture autoCleanup(texture);

    // TODO: remove layer dirty in multi-draw callers
    // TODO: snap doesn't need to touch transform, only texture filter.
    bool snap = pureTranslate;
    const float x = floorf(bounds.left + 0.5f);
    const float y = floorf(bounds.top + 0.5f);

    const int textureFillFlags = (bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    const int transformFlags = TransformFlags::MeshIgnoresCanvasTransform;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedMesh(vertices, bitmapCount * 6)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewOffsetRectOptionalSnap(snap, x, y, Rect(bounds.getWidth(), bounds.getHeight()))
            .build();
    renderGlop(glop, GlopRenderType::Multi);
}

void OpenGLRenderer::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    if (quickRejectSetupScissor(0, 0, bitmap->width(), bitmap->height())) {
        return;
    }

    mCaches.textureState().activateTexture(0);
    Texture* texture = getTexture(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const int textureFillFlags = (bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUnitQuad(texture->uvMapper)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewMapUnitToRectSnap(Rect(texture->width(), texture->height()))
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {
    if (!vertices || mState.currentlyIgnored()) {
        return;
    }

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

    const uint32_t elementCount = meshWidth * meshHeight * 6;

    std::unique_ptr<ColorTextureVertex[]> mesh(new ColorTextureVertex[elementCount]);
    ColorTextureVertex* vertex = &mesh[0];

    std::unique_ptr<int[]> tempColors;
    if (!colors) {
        uint32_t colorsCount = (meshWidth + 1) * (meshHeight + 1);
        tempColors.reset(new int[colorsCount]);
        memset(tempColors.get(), 0xff, colorsCount * sizeof(int));
        colors = tempColors.get();
    }

    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap->pixelRef());
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

            left = std::min(left, std::min(vertices[ax], std::min(vertices[bx], vertices[cx])));
            top = std::min(top, std::min(vertices[ay], std::min(vertices[by], vertices[cy])));
            right = std::max(right, std::max(vertices[ax], std::max(vertices[bx], vertices[cx])));
            bottom = std::max(bottom, std::max(vertices[ay], std::max(vertices[by], vertices[cy])));
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

    /*
     * TODO: handle alpha_8 textures correctly by applying paint color, but *not*
     * shader in that case to mimic the behavior in SkiaCanvas::drawBitmapMesh.
     */
    const int textureFillFlags = TextureFillFlags::None;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshColoredTexturedMesh(mesh.get(), elementCount)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewOffsetRect(0, 0, Rect(left, top, right, bottom))
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::drawBitmap(const SkBitmap* bitmap, Rect src, Rect dst, const SkPaint* paint) {
    if (quickRejectSetupScissor(dst)) {
        return;
    }

    Texture* texture = getTexture(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    Rect uv(std::max(0.0f, src.left / texture->width()),
            std::max(0.0f, src.top / texture->height()),
            std::min(1.0f, src.right / texture->width()),
            std::min(1.0f, src.bottom / texture->height()));

    const int textureFillFlags = (bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    const bool tryToSnap = MathUtils::areEqual(src.getWidth(), dst.getWidth())
            && MathUtils::areEqual(src.getHeight(), dst.getHeight());
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUvQuad(texture->uvMapper, uv)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap, dst)
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::drawPatch(const SkBitmap* bitmap, const Patch* mesh,
        AssetAtlas::Entry* entry, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    if (!mesh || !mesh->verticesCount || quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    // 9 patches are built for stretching - always filter
    int textureFillFlags = TextureFillFlags::ForceFilter;
    if (bitmap->colorType() == kAlpha_8_SkColorType) {
        textureFillFlags |= TextureFillFlags::IsAlphaMaskTexture;
    }
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshPatchQuads(*mesh)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewOffsetRectSnap(left, top, Rect(right - left, bottom - top)) // TODO: get minimal bounds from patch
            .build();
    renderGlop(glop);
}

/**
 * Important note: this method is intended to draw batches of 9-patch objects and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
void OpenGLRenderer::drawPatches(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
        TextureVertex* vertices, uint32_t elementCount, const SkPaint* paint) {
    mCaches.textureState().activateTexture(0);
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    // TODO: get correct bounds from caller
    const int transformFlags = TransformFlags::MeshIgnoresCanvasTransform;
    // 9 patches are built for stretching - always filter
    int textureFillFlags = TextureFillFlags::ForceFilter;
    if (bitmap->colorType() == kAlpha_8_SkColorType) {
        textureFillFlags |= TextureFillFlags::IsAlphaMaskTexture;
    }
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedIndexedQuads(vertices, elementCount)
            .setFillTexturePaint(*texture, textureFillFlags, paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewOffsetRect(0, 0, Rect())
            .build();
    renderGlop(glop, GlopRenderType::Multi);
}

void OpenGLRenderer::drawVertexBuffer(float translateX, float translateY,
        const VertexBuffer& vertexBuffer, const SkPaint* paint, int displayFlags) {
    // not missing call to quickReject/dirtyLayer, always done at a higher level
    if (!vertexBuffer.getVertexCount()) {
        // no vertices to draw
        return;
    }

    bool shadowInterp = displayFlags & kVertexBuffer_ShadowInterp;
    const int transformFlags = TransformFlags::OffsetByFudgeFactor;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshVertexBuffer(vertexBuffer)
            .setFillPaint(*paint, currentSnapshot()->alpha, shadowInterp)
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewOffsetRect(translateX, translateY, vertexBuffer.getBounds())
            .build();
    renderGlop(glop);
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

    Rect clip(mState.currentRenderTargetClip());
    clip.snapToPixelBoundaries();

    SkPaint paint;
    paint.setColor(color);
    paint.setXfermodeMode(mode);

    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, &paint, true);

    mDirty = true;
}

void OpenGLRenderer::drawShape(float left, float top, PathTexture* texture,
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
        mCaches.textureState().activateTexture(0);
        PathTexture* texture = mCaches.pathCache.getRoundRect(
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
        mCaches.textureState().activateTexture(0);
        PathTexture* texture = mCaches.pathCache.getCircle(radius, p);
        drawShape(x - radius, y - radius, texture, p);
        return;
    }

    SkPath path;
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        path.addCircle(x, y, radius + p->getStrokeWidth() / 2);
    } else {
        path.addCircle(x, y, radius);
    }

#if !HWUI_NEW_OPS
    if (CC_UNLIKELY(currentSnapshot()->projectionPathMask != nullptr)) {
        // mask ripples with projection mask
        SkPath maskPath = *(currentSnapshot()->projectionPathMask->projectionMask);

        Matrix4 screenSpaceTransform;
        currentSnapshot()->buildScreenSpaceTransform(&screenSpaceTransform);

        Matrix4 totalTransform;
        totalTransform.loadInverse(screenSpaceTransform);
        totalTransform.multiply(currentSnapshot()->projectionPathMask->projectionMaskTransform);

        SkMatrix skTotalTransform;
        totalTransform.copyTo(skTotalTransform);
        maskPath.transform(skTotalTransform);

        // Mask the ripple path by the projection mask, now that it's
        // in local space. Note that this can create CCW paths.
        Op(path, maskPath, kIntersect_SkPathOp, &path);
    }
#endif
    drawConvexPath(path, p);
}

void OpenGLRenderer::drawOval(float left, float top, float right, float bottom,
        const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    if (p->getPathEffect() != nullptr) {
        mCaches.textureState().activateTexture(0);
        PathTexture* texture = mCaches.pathCache.getOval(right - left, bottom - top, p);
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
        mCaches.textureState().activateTexture(0);
        PathTexture* texture = mCaches.pathCache.getArc(right - left, bottom - top,
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

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* p) {
    if (mState.currentlyIgnored()
            || quickRejectSetupScissor(left, top, right, bottom, p)
            || PaintUtils::paintWillNotDraw(*p)) {
        return;
    }

    if (p->getStyle() != SkPaint::kFill_Style) {
        // only fill style is supported by drawConvexPath, since others have to handle joins
        static_assert(SkPaintDefaults_MiterLimit == 4.0f, "Miter limit has changed");
        if (p->getPathEffect() != nullptr || p->getStrokeJoin() != SkPaint::kMiter_Join ||
                p->getStrokeMiter() != SkPaintDefaults_MiterLimit) {
            mCaches.textureState().activateTexture(0);
            PathTexture* texture =
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

void OpenGLRenderer::drawTextShadow(const SkPaint* paint, const glyph_t* glyphs,
        int count, const float* positions,
        FontRenderer& fontRenderer, int alpha, float x, float y) {
    mCaches.textureState().activateTexture(0);

    PaintUtils::TextShadow textShadow;
    if (!PaintUtils::getTextShadow(paint, &textShadow)) {
        LOG_ALWAYS_FATAL("failed to query shadow attributes");
    }

    // NOTE: The drop shadow will not perform gamma correction
    //       if shader-based correction is enabled
    mCaches.dropShadowCache.setFontRenderer(fontRenderer);
    ShadowTexture* texture = mCaches.dropShadowCache.get(
            paint, glyphs, count, textShadow.radius, positions);
    // If the drop shadow exceeds the max texture size or couldn't be
    // allocated, skip drawing
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float sx = x - texture->left + textShadow.dx;
    const float sy = y - texture->top + textShadow.dy;

    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillShadowTexturePaint(*texture, textShadow.color, *paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewMapUnitToRect(Rect(sx, sy, sx + texture->width(), sy + texture->height()))
            .build();
    renderGlop(glop);
}

// TODO: remove this, once mState.currentlyIgnored captures snapshot alpha
bool OpenGLRenderer::canSkipText(const SkPaint* paint) const {
    float alpha = (PaintUtils::hasTextShadow(paint)
            ? 1.0f : paint->getAlpha()) * currentSnapshot()->alpha;
    return MathUtils::isZero(alpha)
            && PaintUtils::getXfermode(paint->getXfermode()) == SkXfermode::kSrcOver_Mode;
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
            roundf(std::max(1.0f, sx)),
            roundf(std::max(1.0f, sy)));
    return true;
}

int OpenGLRenderer::getSaveCount() const {
    return mState.getSaveCount();
}

int OpenGLRenderer::save(int flags) {
    return mState.save(flags);
}

void OpenGLRenderer::restore() {
    mState.restore();
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    mState.restoreToCount(saveCount);
}


void OpenGLRenderer::translate(float dx, float dy, float dz) {
    mState.translate(dx, dy, dz);
}

void OpenGLRenderer::rotate(float degrees) {
    mState.rotate(degrees);
}

void OpenGLRenderer::scale(float sx, float sy) {
    mState.scale(sx, sy);
}

void OpenGLRenderer::skew(float sx, float sy) {
    mState.skew(sx, sy);
}

void OpenGLRenderer::setLocalMatrix(const Matrix4& matrix) {
    mState.setMatrix(mBaseTransform);
    mState.concatMatrix(matrix);
}

void OpenGLRenderer::setLocalMatrix(const SkMatrix& matrix) {
    mState.setMatrix(mBaseTransform);
    mState.concatMatrix(matrix);
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

void OpenGLRenderer::setProjectionPathMask(LinearAllocator& allocator, const SkPath* path) {
    mState.setProjectionPathMask(allocator, path);
}

void OpenGLRenderer::drawText(const glyph_t* glyphs, int bytesCount, int count, float x, float y,
        const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
        DrawOpMode drawOpMode) {

    if (drawOpMode == DrawOpMode::kImmediate) {
        // The checks for corner-case ignorable text and quick rejection is only done for immediate
        // drawing as ops from DeferredDisplayList are already filtered for these
        if (glyphs == nullptr || count == 0 || mState.currentlyIgnored() || canSkipText(paint) ||
                quickRejectSetupScissor(bounds)) {
            return;
        }
    }

    const float oldX = x;
    const float oldY = y;

    const mat4& transform = *currentTransform();
    const bool pureTranslate = transform.isPureTranslate();

    if (CC_LIKELY(pureTranslate)) {
        x = floorf(x + transform.getTranslateX() + 0.5f);
        y = floorf(y + transform.getTranslateY() + 0.5f);
    }

    int alpha = PaintUtils::getAlphaDirect(paint) * currentSnapshot()->alpha;
    SkXfermode::Mode mode = PaintUtils::getXfermodeDirect(paint);

    FontRenderer& fontRenderer = mCaches.fontRenderer.getFontRenderer();

    if (CC_UNLIKELY(PaintUtils::hasTextShadow(paint))) {
        fontRenderer.setFont(paint, SkMatrix::I());
        drawTextShadow(paint, glyphs, count, positions, fontRenderer,
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
    const Rect* clip = !pureTranslate ? nullptr : &mState.currentRenderTargetClip();
    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    bool status;
#if HWUI_NEW_OPS
    LOG_ALWAYS_FATAL("unsupported");
    TextDrawFunctor functor(nullptr, nullptr, nullptr, x, y, pureTranslate, alpha, mode, paint);
#else
    TextDrawFunctor functor(this, x, y, pureTranslate, alpha, mode, paint);
#endif

    // don't call issuedrawcommand, do it at end of batch
    bool forceFinish = (drawOpMode != DrawOpMode::kDefer);
    if (CC_UNLIKELY(paint->getTextAlign() != SkPaint::kLeft_Align)) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        status = fontRenderer.renderPosText(&paintCopy, clip, glyphs, count, x, y,
                positions, hasActiveLayer ? &layerBounds : nullptr, &functor, forceFinish);
    } else {
        status = fontRenderer.renderPosText(paint, clip, glyphs, count, x, y,
                positions, hasActiveLayer ? &layerBounds : nullptr, &functor, forceFinish);
    }

    if ((status || drawOpMode != DrawOpMode::kImmediate) && hasActiveLayer) {
        if (!pureTranslate) {
            transform.mapRect(layerBounds);
        }
        dirtyLayerUnchecked(layerBounds, getRegion());
    }

    mDirty = true;
}

void OpenGLRenderer::drawTextOnPath(const glyph_t* glyphs, int bytesCount, int count,
        const SkPath* path, float hOffset, float vOffset, const SkPaint* paint) {
    if (glyphs == nullptr || count == 0 || mState.currentlyIgnored() || canSkipText(paint)) {
        return;
    }

    // TODO: avoid scissor by calculating maximum bounds using path bounds + font metrics
    mRenderState.scissor().setEnabled(true);

    FontRenderer& fontRenderer = mCaches.fontRenderer.getFontRenderer();
    fontRenderer.setFont(paint, SkMatrix::I());
    fontRenderer.setTextureFiltering(true);

    int alpha = PaintUtils::getAlphaDirect(paint) * currentSnapshot()->alpha;
    SkXfermode::Mode mode = PaintUtils::getXfermodeDirect(paint);
#if HWUI_NEW_OPS
    LOG_ALWAYS_FATAL("unsupported");
    TextDrawFunctor functor(nullptr, nullptr, nullptr, 0.0f, 0.0f, false, alpha, mode, paint);
#else
    TextDrawFunctor functor(this, 0.0f, 0.0f, false, alpha, mode, paint);
#endif

    const Rect* clip = &writableSnapshot()->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    if (fontRenderer.renderTextOnPath(paint, clip, glyphs, count, path,
            hOffset, vOffset, hasLayer() ? &bounds : nullptr, &functor)) {
        dirtyLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, *currentTransform());
        mDirty = true;
    }
}

void OpenGLRenderer::drawPath(const SkPath* path, const SkPaint* paint) {
    if (mState.currentlyIgnored()) return;

    mCaches.textureState().activateTexture(0);

    PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return;

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);

    if (texture->cleanup) {
        mCaches.pathCache.remove(path, paint);
    }
    mDirty = true;
}

void OpenGLRenderer::drawLayer(Layer* layer) {
    if (!layer) {
        return;
    }

    mat4* transform = nullptr;
    if (layer->isTextureLayer()) {
        transform = &layer->getTransform();
        if (!transform->isIdentity()) {
            save(SaveFlags::Matrix);
            concatMatrix(*transform);
        }
    }

    bool clipRequired = false;
    const bool rejected = mState.calculateQuickRejectForScissor(
            0, 0, layer->layer.getWidth(), layer->layer.getHeight(),
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
    mCaches.textureState().activateTexture(0);

    if (CC_LIKELY(!layer->region.isEmpty())) {
        if (layer->region.isRect()) {
            DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate,
                    composeLayerRect(layer, layer->regionRect));
        } else if (layer->mesh) {
            Glop glop;
            GlopBuilder(mRenderState, mCaches, &glop)
                    .setRoundRectClipState(currentSnapshot()->roundRectClipState)
                    .setMeshTexturedIndexedQuads(layer->mesh, layer->meshElementCount)
                    .setFillLayer(layer->getTexture(), layer->getColorFilter(), getLayerAlpha(layer), layer->getMode(), Blend::ModeOrderSwap::NoSwap)
                    .setTransform(*currentSnapshot(),  TransformFlags::None)
                    .setModelViewOffsetRectSnap(0, 0, Rect(layer->layer.getWidth(), layer->layer.getHeight()))
                    .build();
            DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate, renderGlop(glop));
#if DEBUG_LAYERS_AS_REGIONS
            drawRegionRectsDebug(layer->region);
#endif
        }

        if (layer->debugDrawUpdate) {
            layer->debugDrawUpdate = false;

            SkPaint paint;
            paint.setColor(0x7f00ff00);
            drawColorRect(0, 0, layer->layer.getWidth(), layer->layer.getHeight(), &paint);
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
    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap->pixelRef());
    if (!texture) {
        return mCaches.textureCache.get(bitmap);
    }
    return texture;
}

void OpenGLRenderer::drawPathTexture(PathTexture* texture, float x, float y,
        const SkPaint* paint) {
    if (quickRejectSetupScissor(x, y, x + texture->width(), y + texture->height())) {
        return;
    }

    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillPathTexturePaint(*texture, *paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(),  TransformFlags::None)
            .setModelViewMapUnitToRect(Rect(x, y, x + texture->width(), y + texture->height()))
            .build();
    renderGlop(glop);
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
    if (CC_UNLIKELY(Properties::overrideAmbientShadowStrength >= 0)) {
        ambientShadowAlpha = Properties::overrideAmbientShadowStrength;
    }
    if (ambientShadowVertexBuffer && ambientShadowAlpha > 0) {
        paint.setARGB(casterAlpha * ambientShadowAlpha, 0, 0, 0);
        drawVertexBuffer(*ambientShadowVertexBuffer, &paint, kVertexBuffer_ShadowInterp);
    }

    float spotShadowAlpha = mSpotShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideSpotShadowStrength >= 0)) {
        spotShadowAlpha = Properties::overrideSpotShadowStrength;
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

        left = std::min(left, l);
        top = std::min(top, t);
        right = std::max(right, r);
        bottom = std::max(bottom, b);
    }

    if (clip && quickRejectSetupScissor(left, top, right, bottom)) {
        return;
    }

    const int transformFlags = ignoreTransform
            ? TransformFlags::MeshIgnoresCanvasTransform : TransformFlags::None;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshIndexedQuads(&mesh[0], count / 4)
            .setFillPaint(*paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewOffsetRect(0, 0, Rect(left, top, right, bottom))
            .build();
    renderGlop(glop);
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        const SkPaint* paint, bool ignoreTransform) {
    const int transformFlags = ignoreTransform
            ? TransformFlags::MeshIgnoresCanvasTransform : TransformFlags::None;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(currentSnapshot()->roundRectClipState)
            .setMeshUnitQuad()
            .setFillPaint(*paint, currentSnapshot()->alpha)
            .setTransform(*currentSnapshot(), transformFlags)
            .setModelViewMapUnitToRect(Rect(left, top, right, bottom))
            .build();
    renderGlop(glop);
}

float OpenGLRenderer::getLayerAlpha(const Layer* layer) const {
    return (layer->getAlpha() / 255.0f) * currentSnapshot()->alpha;
}

}; // namespace uirenderer
}; // namespace android
