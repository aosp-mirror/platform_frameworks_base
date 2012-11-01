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

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <SkCanvas.h>
#include <SkPathMeasure.h>
#include <SkTypeface.h>

#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <private/hwui/DrawGlInfo.h>

#include <ui/Rect.h>

#include "OpenGLRenderer.h"
#include "DisplayListRenderer.h"
#include "PathRenderer.h"
#include "Properties.h"
#include "Vector.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define RAD_TO_DEG (180.0f / 3.14159265f)
#define MIN_ANGLE 0.001f

#define ALPHA_THRESHOLD 0

#define FILTER(paint) (!paint || paint->isFilterBitmap() ? GL_LINEAR : GL_NEAREST)

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
    { SkXfermode::kMultiply_Mode, GL_ZERO,                GL_SRC_COLOR },
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
    { SkXfermode::kMultiply_Mode, GL_DST_COLOR,           GL_ZERO },
    { SkXfermode::kScreen_Mode,   GL_ONE_MINUS_DST_COLOR, GL_ONE }
};

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer(): mCaches(Caches::getInstance()) {
    mShader = NULL;
    mColorFilter = NULL;
    mHasShadow = false;
    mHasDrawFilter = false;

    memcpy(mMeshVertices, gMeshVertices, sizeof(gMeshVertices));

    mFirstSnapshot = new Snapshot;

    mScissorOptimizationDisabled = false;
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

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::isDeferred() {
    return false;
}

void OpenGLRenderer::setViewport(int width, int height) {
    initViewport(width, height);

    glDisable(GL_DITHER);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    glEnableVertexAttribArray(Program::kBindingPosition);
}

void OpenGLRenderer::initViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;

    mFirstSnapshot->height = height;
    mFirstSnapshot->viewport.set(0, 0, width, height);
}

status_t OpenGLRenderer::prepare(bool opaque) {
    return prepareDirty(0.0f, 0.0f, mWidth, mHeight, opaque);
}

status_t OpenGLRenderer::prepareDirty(float left, float top, float right, float bottom,
        bool opaque) {
    mCaches.clearGarbage();

    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->fbo = getTargetFbo();
    mSaveCount = 1;

    mSnapshot->setClip(left, top, right, bottom);
    mDirtyClip = true;

    updateLayers();

    // If we know that we are going to redraw the entire framebuffer,
    // perform a discard to let the driver know we don't need to preserve
    // the back buffer for this frame.
    if (mCaches.extensions.hasDiscardFramebuffer() &&
            left <= 0.0f && top <= 0.0f && right >= mWidth && bottom >= mHeight) {
        const GLenum attachments[] = { getTargetFbo() == 0 ? GL_COLOR_EXT : GL_COLOR_ATTACHMENT0 };
        glDiscardFramebufferEXT(GL_FRAMEBUFFER, 1, attachments);
    }

    syncState();

    // Functors break the tiling extension in pretty spectacular ways
    // This ensures we don't use tiling when a functor is going to be
    // invoked during the frame
    mSuppressTiling = mCaches.hasRegisteredFunctors();

    mTilingSnapshot = mSnapshot;
    startTiling(mTilingSnapshot, true);

    debugOverdraw(true, true);

    return clear(left, top, right, bottom, opaque);
}

status_t OpenGLRenderer::clear(float left, float top, float right, float bottom, bool opaque) {
    if (!opaque) {
        mCaches.enableScissor();
        mCaches.setScissor(left, mSnapshot->height - bottom, right - left, bottom - top);
        glClear(GL_COLOR_BUFFER_BIT);
        return DrawGlInfo::kStatusDrew;
    }

    mCaches.resetScissor();
    return DrawGlInfo::kStatusDone;
}

void OpenGLRenderer::syncState() {
    glViewport(0, 0, mWidth, mHeight);

    if (mCaches.blend) {
        glEnable(GL_BLEND);
    } else {
        glDisable(GL_BLEND);
    }
}

void OpenGLRenderer::startTiling(const sp<Snapshot>& s, bool opaque) {
    if (!mSuppressTiling) {
        Rect* clip = mTilingSnapshot->clipRect;
        if (s->flags & Snapshot::kFlagIsFboLayer) {
            clip = s->clipRect;
        }

        mCaches.startTiling(clip->left, s->height - clip->bottom,
                clip->right - clip->left, clip->bottom - clip->top, opaque);
    }
}

void OpenGLRenderer::endTiling() {
    if (!mSuppressTiling) mCaches.endTiling();
}

void OpenGLRenderer::finish() {
    renderOverdraw();
    endTiling();

    if (!suppressErrorChecks()) {
#if DEBUG_OPENGL
        GLenum status = GL_NO_ERROR;
        while ((status = glGetError()) != GL_NO_ERROR) {
            ALOGD("GL error from OpenGLRenderer: 0x%x", status);
            switch (status) {
                case GL_INVALID_ENUM:
                    ALOGE("  GL_INVALID_ENUM");
                    break;
                case GL_INVALID_VALUE:
                    ALOGE("  GL_INVALID_VALUE");
                    break;
                case GL_INVALID_OPERATION:
                    ALOGE("  GL_INVALID_OPERATION");
                    break;
                case GL_OUT_OF_MEMORY:
                    ALOGE("  Out of memory!");
                    break;
            }
        }
#endif

#if DEBUG_MEMORY_USAGE
        mCaches.dumpMemoryUsage();
#else
        if (mCaches.getDebugLevel() & kDebugMemory) {
            mCaches.dumpMemoryUsage();
        }
#endif
    }
}

void OpenGLRenderer::interrupt() {
    if (mCaches.currentProgram) {
        if (mCaches.currentProgram->isInUse()) {
            mCaches.currentProgram->remove();
            mCaches.currentProgram = NULL;
        }
    }
    mCaches.unbindMeshBuffer();
    mCaches.unbindIndicesBuffer();
    mCaches.resetVertexPointers();
    mCaches.disbaleTexCoordsVertexArray();
    debugOverdraw(false, false);
}

void OpenGLRenderer::resume() {
    sp<Snapshot> snapshot = (mSnapshot != NULL) ? mSnapshot : mFirstSnapshot;
    glViewport(0, 0, snapshot->viewport.getWidth(), snapshot->viewport.getHeight());
    glBindFramebuffer(GL_FRAMEBUFFER, snapshot->fbo);
    debugOverdraw(true, false);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    mCaches.scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    mCaches.enableScissor();
    mCaches.resetScissor();
    dirtyClip();

    mCaches.activeTexture(0);

    mCaches.blend = true;
    glEnable(GL_BLEND);
    glBlendFunc(mCaches.lastSrcMode, mCaches.lastDstMode);
    glBlendEquation(GL_FUNC_ADD);
}

void OpenGLRenderer::resumeAfterLayer() {
    sp<Snapshot> snapshot = (mSnapshot != NULL) ? mSnapshot : mFirstSnapshot;
    glViewport(0, 0, snapshot->viewport.getWidth(), snapshot->viewport.getHeight());
    glBindFramebuffer(GL_FRAMEBUFFER, snapshot->fbo);
    debugOverdraw(true, false);

    mCaches.resetScissor();
    dirtyClip();
}

void OpenGLRenderer::detachFunctor(Functor* functor) {
    mFunctors.remove(functor);
}

void OpenGLRenderer::attachFunctor(Functor* functor) {
    mFunctors.add(functor);
}

status_t OpenGLRenderer::invokeFunctors(Rect& dirty) {
    status_t result = DrawGlInfo::kStatusDone;
    size_t count = mFunctors.size();

    if (count > 0) {
        SortedVector<Functor*> functors(mFunctors);
        mFunctors.clear();

        DrawGlInfo info;
        info.clipLeft = 0;
        info.clipTop = 0;
        info.clipRight = 0;
        info.clipBottom = 0;
        info.isLayer = false;
        info.width = 0;
        info.height = 0;
        memset(info.transform, 0, sizeof(float) * 16);

        for (size_t i = 0; i < count; i++) {
            Functor* f = functors.itemAt(i);
            result |= (*f)(DrawGlInfo::kModeProcess, &info);

            if (result & DrawGlInfo::kStatusDraw) {
                Rect localDirty(info.dirtyLeft, info.dirtyTop, info.dirtyRight, info.dirtyBottom);
                dirty.unionWith(localDirty);
            }

            if (result & DrawGlInfo::kStatusInvoke) {
                mFunctors.add(f);
            }
        }
        // protect against functors binding to other buffers
        mCaches.unbindMeshBuffer();
        mCaches.unbindIndicesBuffer();
        mCaches.activeTexture(0);
    }

    return result;
}

status_t OpenGLRenderer::callDrawGLFunction(Functor* functor, Rect& dirty) {
    interrupt();
    detachFunctor(functor);

    mCaches.enableScissor();
    if (mDirtyClip) {
        setScissorFromClip();
    }

    Rect clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    // Since we don't know what the functor will draw, let's dirty
    // tne entire clip region
    if (hasLayer()) {
        dirtyLayerUnchecked(clip, getRegion());
    }

    DrawGlInfo info;
    info.clipLeft = clip.left;
    info.clipTop = clip.top;
    info.clipRight = clip.right;
    info.clipBottom = clip.bottom;
    info.isLayer = hasLayer();
    info.width = getSnapshot()->viewport.getWidth();
    info.height = getSnapshot()->height;
    getSnapshot()->transform->copyTo(&info.transform[0]);

    status_t result = (*functor)(DrawGlInfo::kModeDraw, &info) | DrawGlInfo::kStatusDrew;

    if (result != DrawGlInfo::kStatusDone) {
        Rect localDirty(info.dirtyLeft, info.dirtyTop, info.dirtyRight, info.dirtyBottom);
        dirty.unionWith(localDirty);

        if (result & DrawGlInfo::kStatusInvoke) {
            mFunctors.add(functor);
        }
    }

    resume();
    return result;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::startMark(const char* name) const {
    mCaches.startMark(0, name);
}

void OpenGLRenderer::endMark() const {
    mCaches.endMark();
}

void OpenGLRenderer::debugOverdraw(bool enable, bool clear) {
    if (mCaches.debugOverdraw && getTargetFbo() == 0) {
        if (clear) {
            mCaches.disableScissor();
            mCaches.stencil.clear();
        }
        if (enable) {
            mCaches.stencil.enableDebugWrite();
        } else {
            mCaches.stencil.disable();
        }
    }
}

void OpenGLRenderer::renderOverdraw() {
    if (mCaches.debugOverdraw && getTargetFbo() == 0) {
        const Rect* clip = mTilingSnapshot->clipRect;

        mCaches.enableScissor();
        mCaches.setScissor(clip->left, mTilingSnapshot->height - clip->bottom,
                clip->right - clip->left, clip->bottom - clip->top);

        mCaches.stencil.enableDebugTest(2);
        drawColor(0x2f0000ff, SkXfermode::kSrcOver_Mode);
        mCaches.stencil.enableDebugTest(3);
        drawColor(0x2f00ff00, SkXfermode::kSrcOver_Mode);
        mCaches.stencil.enableDebugTest(4);
        drawColor(0x3fff0000, SkXfermode::kSrcOver_Mode);
        mCaches.stencil.enableDebugTest(4, true);
        drawColor(0x7fff0000, SkXfermode::kSrcOver_Mode);
        mCaches.stencil.disable();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::updateLayer(Layer* layer, bool inFrame) {
    if (layer->deferredUpdateScheduled && layer->renderer && layer->displayList) {
        OpenGLRenderer* renderer = layer->renderer;
        Rect& dirty = layer->dirtyRect;

        if (inFrame) {
            endTiling();
            debugOverdraw(false, false);
        }

        renderer->setViewport(layer->layer.getWidth(), layer->layer.getHeight());
        renderer->prepareDirty(dirty.left, dirty.top, dirty.right, dirty.bottom, !layer->isBlend());
        renderer->drawDisplayList(layer->displayList, dirty, DisplayList::kReplayFlag_ClipChildren);
        renderer->finish();

        if (inFrame) {
            resumeAfterLayer();
            startTiling(mSnapshot);
        }

        dirty.setEmpty();
        layer->deferredUpdateScheduled = false;
        layer->renderer = NULL;
        layer->displayList = NULL;

        return true;
    }

    return false;
}

void OpenGLRenderer::updateLayers() {
    int count = mLayerUpdates.size();
    if (count > 0) {
        startMark("Layer Updates");

        // Note: it is very important to update the layers in reverse order
        for (int i = count - 1; i >= 0; i--) {
            Layer* layer = mLayerUpdates.itemAt(i);
            updateLayer(layer, false);
            mCaches.resourceCache.decrementRefcount(layer);
        }
        mLayerUpdates.clear();

        glBindFramebuffer(GL_FRAMEBUFFER, getTargetFbo());
        endMark();
    }
}

void OpenGLRenderer::pushLayerUpdate(Layer* layer) {
    if (layer) {
        mLayerUpdates.push_back(layer);
        mCaches.resourceCache.incrementRefcount(layer);
    }
}

void OpenGLRenderer::clearLayerUpdates() {
    size_t count = mLayerUpdates.size();
    if (count > 0) {
        mCaches.resourceCache.lock();
        for (size_t i = 0; i < count; i++) {
            mCaches.resourceCache.decrementRefcountLocked(mLayerUpdates.itemAt(i));
        }
        mCaches.resourceCache.unlock();
        mLayerUpdates.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::getSaveCount() const {
    return mSaveCount;
}

int OpenGLRenderer::save(int flags) {
    return saveSnapshot(flags);
}

void OpenGLRenderer::restore() {
    if (mSaveCount > 1) {
        restoreSnapshot();
    }
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    if (saveCount < 1) saveCount = 1;

    while (mSaveCount > saveCount) {
        restoreSnapshot();
    }
}

int OpenGLRenderer::saveSnapshot(int flags) {
    mSnapshot = new Snapshot(mSnapshot, flags);
    return mSaveCount++;
}

bool OpenGLRenderer::restoreSnapshot() {
    bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;
    bool restoreLayer = mSnapshot->flags & Snapshot::kFlagIsLayer;
    bool restoreOrtho = mSnapshot->flags & Snapshot::kFlagDirtyOrtho;

    sp<Snapshot> current = mSnapshot;
    sp<Snapshot> previous = mSnapshot->previous;

    if (restoreOrtho) {
        Rect& r = previous->viewport;
        glViewport(r.left, r.top, r.right, r.bottom);
        mOrthoMatrix.load(current->orthoMatrix);
    }

    mSaveCount--;
    mSnapshot = previous;

    if (restoreClip) {
        dirtyClip();
    }

    if (restoreLayer) {
        composeLayer(current, previous);
    }

    return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        SkPaint* p, int flags) {
    const GLuint previousFbo = mSnapshot->fbo;
    const int count = saveSnapshot(flags);

    if (!mSnapshot->isIgnored()) {
        int alpha = 255;
        SkXfermode::Mode mode;

        if (p) {
            alpha = p->getAlpha();
            mode = getXfermode(p->getXfermode());
        } else {
            mode = SkXfermode::kSrcOver_Mode;
        }

        createLayer(left, top, right, bottom, alpha, mode, flags, previousFbo);
    }

    return count;
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    if (alpha >= 255) {
        return saveLayer(left, top, right, bottom, NULL, flags);
    } else {
        SkPaint paint;
        paint.setAlpha(alpha);
        return saveLayer(left, top, right, bottom, &paint, flags);
    }
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
        int alpha, SkXfermode::Mode mode, int flags, GLuint previousFbo) {
    LAYER_LOGD("Requesting layer %.2fx%.2f", right - left, bottom - top);
    LAYER_LOGD("Layer cache size = %d", mCaches.layerCache.getSize());

    const bool fboLayer = flags & SkCanvas::kClipToLayer_SaveFlag;

    // Window coordinates of the layer
    Rect clip;
    Rect bounds(left, top, right, bottom);
    Rect untransformedBounds(bounds);
    mSnapshot->transform->mapRect(bounds);

    // Layers only make sense if they are in the framebuffer's bounds
    if (bounds.intersect(*mSnapshot->clipRect)) {
        // We cannot work with sub-pixels in this case
        bounds.snapToPixelBoundaries();

        // When the layer is not an FBO, we may use glCopyTexImage so we
        // need to make sure the layer does not extend outside the bounds
        // of the framebuffer
        if (!bounds.intersect(mSnapshot->previous->viewport)) {
            bounds.setEmpty();
        } else if (fboLayer) {
            clip.set(bounds);
            mat4 inverse;
            inverse.loadInverse(*mSnapshot->transform);
            inverse.mapRect(clip);
            clip.snapToPixelBoundaries();
            if (clip.intersect(untransformedBounds)) {
                clip.translate(-left, -top);
                bounds.set(untransformedBounds);
            } else {
                clip.setEmpty();
            }
        }
    } else {
        bounds.setEmpty();
    }

    if (bounds.isEmpty() || bounds.getWidth() > mCaches.maxTextureSize ||
            bounds.getHeight() > mCaches.maxTextureSize ||
            (fboLayer && clip.isEmpty())) {
        mSnapshot->empty = fboLayer;
    } else {
        mSnapshot->invisible = mSnapshot->invisible || (alpha <= ALPHA_THRESHOLD && fboLayer);
    }

    // Bail out if we won't draw in this snapshot
    if (mSnapshot->invisible || mSnapshot->empty) {
        return false;
    }

    mCaches.activeTexture(0);
    Layer* layer = mCaches.layerCache.get(bounds.getWidth(), bounds.getHeight());
    if (!layer) {
        return false;
    }

    layer->setAlpha(alpha, mode);
    layer->layer.set(bounds);
    layer->texCoords.set(0.0f, bounds.getHeight() / float(layer->getHeight()),
            bounds.getWidth() / float(layer->getWidth()), 0.0f);
    layer->setColorFilter(mColorFilter);
    layer->setBlend(true);
    layer->setDirty(false);

    // Save the layer in the snapshot
    mSnapshot->flags |= Snapshot::kFlagIsLayer;
    mSnapshot->layer = layer;

    if (fboLayer) {
        return createFboLayer(layer, bounds, clip, previousFbo);
    } else {
        // Copy the framebuffer into the layer
        layer->bindTexture();
        if (!bounds.isEmpty()) {
            if (layer->isEmpty()) {
                glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                        bounds.left, mSnapshot->height - bounds.bottom,
                        layer->getWidth(), layer->getHeight(), 0);
                layer->setEmpty(false);
            } else {
                glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bounds.left,
                        mSnapshot->height - bounds.bottom, bounds.getWidth(), bounds.getHeight());
            }

            // Enqueue the buffer coordinates to clear the corresponding region later
            mLayers.push(new Rect(bounds));
        }
    }

    return true;
}

bool OpenGLRenderer::createFboLayer(Layer* layer, Rect& bounds, Rect& clip, GLuint previousFbo) {
    layer->setFbo(mCaches.fboCache.get());

    mSnapshot->region = &mSnapshot->layer->region;
    mSnapshot->flags |= Snapshot::kFlagFboTarget;

    mSnapshot->flags |= Snapshot::kFlagIsFboLayer;
    mSnapshot->fbo = layer->getFbo();
    mSnapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
    mSnapshot->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    mSnapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
    mSnapshot->height = bounds.getHeight();
    mSnapshot->flags |= Snapshot::kFlagDirtyOrtho;
    mSnapshot->orthoMatrix.load(mOrthoMatrix);

    endTiling();
    debugOverdraw(false, false);
    // Bind texture to FBO
    glBindFramebuffer(GL_FRAMEBUFFER, layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->allocateTexture(GL_RGBA, GL_UNSIGNED_BYTE);
        layer->setEmpty(false);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

    startTiling(mSnapshot);

    // Clear the FBO, expand the clear region by 1 to get nice bilinear filtering
    mCaches.enableScissor();
    mCaches.setScissor(clip.left - 1.0f, bounds.getHeight() - clip.bottom - 1.0f,
            clip.getWidth() + 2.0f, clip.getHeight() + 2.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    dirtyClip();

    // Change the ortho projection
    glViewport(0, 0, bounds.getWidth(), bounds.getHeight());
    mOrthoMatrix.loadOrtho(0.0f, bounds.getWidth(), bounds.getHeight(), 0.0f, -1.0f, 1.0f);

    return true;
}

/**
 * Read the documentation of createLayer() before doing anything in this method.
 */
void OpenGLRenderer::composeLayer(sp<Snapshot> current, sp<Snapshot> previous) {
    if (!current->layer) {
        ALOGE("Attempting to compose a layer that does not exist");
        return;
    }

    const bool fboLayer = current->flags & Snapshot::kFlagIsFboLayer;

    if (fboLayer) {
        endTiling();

        // Detach the texture from the FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        // Unbind current FBO and restore previous one
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);
        debugOverdraw(true, false);

        startTiling(previous);
    }

    Layer* layer = current->layer;
    const Rect& rect = layer->layer;

    if (!fboLayer && layer->getAlpha() < 255) {
        drawColorRect(rect.left, rect.top, rect.right, rect.bottom,
                layer->getAlpha() << 24, SkXfermode::kDstIn_Mode, true);
        // Required below, composeLayerRect() will divide by 255
        layer->setAlpha(255);
    }

    mCaches.unbindMeshBuffer();

    mCaches.activeTexture(0);

    // When the layer is stored in an FBO, we can save a bit of fillrate by
    // drawing only the dirty region
    if (fboLayer) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom, *previous->transform);
        if (layer->getColorFilter()) {
            setupColorFilter(layer->getColorFilter());
        }
        composeLayerRegion(layer, rect);
        if (layer->getColorFilter()) {
            resetColorFilter();
        }
    } else if (!rect.isEmpty()) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom);
        composeLayerRect(layer, rect, true);
    }

    if (fboLayer) {
        // Note: No need to use glDiscardFramebufferEXT() since we never
        //       create/compose layers that are not on screen with this
        //       code path
        // See LayerRenderer::destroyLayer(Layer*)

        // Put the FBO name back in the cache, if it doesn't fit, it will be destroyed
        mCaches.fboCache.put(current->fbo);
        layer->setFbo(0);
    }

    dirtyClip();

    // Failing to add the layer to the cache should happen only if the layer is too large
    if (!mCaches.layerCache.put(layer)) {
        LAYER_LOGD("Deleting layer");
        Caches::getInstance().resourceCache.decrementRefcount(layer);
    }
}

void OpenGLRenderer::drawTextureLayer(Layer* layer, const Rect& rect) {
    float alpha = layer->getAlpha() / 255.0f;

    setupDraw();
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawWithTexture();
    } else {
        setupDrawWithExternalTexture();
    }
    setupDrawTextureTransform();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter();
    setupDrawBlending(layer->isBlend() || alpha < 1.0f, layer->getMode());
    setupDrawProgram();
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawTexture(layer->getTexture());
    } else {
        setupDrawExternalTexture(layer->getTexture());
    }
    if (mSnapshot->transform->isPureTranslate() &&
            layer->getWidth() == (uint32_t) rect.getWidth() &&
            layer->getHeight() == (uint32_t) rect.getHeight()) {
        const float x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);

        layer->setFilter(GL_NEAREST);
        setupDrawModelView(x, y, x + rect.getWidth(), y + rect.getHeight(), true);
    } else {
        layer->setFilter(GL_LINEAR);
        setupDrawModelView(rect.left, rect.top, rect.right, rect.bottom);
    }
    setupDrawTextureTransformUniforms(layer->getTexTransform());
    setupDrawMesh(&mMeshVertices[0].position[0], &mMeshVertices[0].texture[0]);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect, bool swap) {
    if (!layer->isTextureLayer()) {
        const Rect& texCoords = layer->texCoords;
        resetDrawTextureTexCoords(texCoords.left, texCoords.top,
                texCoords.right, texCoords.bottom);

        float x = rect.left;
        float y = rect.top;
        bool simpleTransform = mSnapshot->transform->isPureTranslate() &&
                layer->getWidth() == (uint32_t) rect.getWidth() &&
                layer->getHeight() == (uint32_t) rect.getHeight();

        if (simpleTransform) {
            // When we're swapping, the layer is already in screen coordinates
            if (!swap) {
                x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
                y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);
            }

            layer->setFilter(GL_NEAREST, true);
        } else {
            layer->setFilter(GL_LINEAR, true);
        }

        drawTextureMesh(x, y, x + rect.getWidth(), y + rect.getHeight(),
                layer->getTexture(), layer->getAlpha() / 255.0f,
                layer->getMode(), layer->isBlend(),
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, swap, swap || simpleTransform);

        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    } else {
        resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);
        drawTextureLayer(layer, rect);
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    }
}

void OpenGLRenderer::composeLayerRegion(Layer* layer, const Rect& rect) {
    if (layer->region.isRect()) {
        layer->setRegionAsRect();

        composeLayerRect(layer, layer->regionRect);

        layer->region.clear();
        return;
    }

    // TODO: See LayerRenderer.cpp::generateMesh() for important
    //       information about this implementation
    if (CC_LIKELY(!layer->region.isEmpty())) {
        size_t count;
        const android::Rect* rects = layer->region.getArray(&count);

        const float alpha = layer->getAlpha() / 255.0f;
        const float texX = 1.0f / float(layer->getWidth());
        const float texY = 1.0f / float(layer->getHeight());
        const float height = rect.getHeight();

        TextureVertex* mesh = mCaches.getRegionMesh();
        GLsizei numQuads = 0;

        setupDraw();
        setupDrawWithTexture();
        setupDrawColor(alpha, alpha, alpha, alpha);
        setupDrawColorFilter();
        setupDrawBlending(layer->isBlend() || alpha < 1.0f, layer->getMode(), false);
        setupDrawProgram();
        setupDrawDirtyRegionsDisabled();
        setupDrawPureColorUniforms();
        setupDrawColorFilterUniforms();
        setupDrawTexture(layer->getTexture());
        if (mSnapshot->transform->isPureTranslate()) {
            const float x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
            const float y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);

            layer->setFilter(GL_NEAREST);
            setupDrawModelViewTranslate(x, y, x + rect.getWidth(), y + rect.getHeight(), true);
        } else {
            layer->setFilter(GL_LINEAR);
            setupDrawModelViewTranslate(rect.left, rect.top, rect.right, rect.bottom);
        }
        setupDrawMeshIndices(&mesh[0].position[0], &mesh[0].texture[0]);

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

            if (numQuads >= REGION_MESH_QUAD_COUNT) {
                glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, NULL);
                numQuads = 0;
                mesh = mCaches.getRegionMesh();
            }
        }

        if (numQuads > 0) {
            glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, NULL);
        }

        finishDrawTexture();

#if DEBUG_LAYERS_AS_REGIONS
        drawRegionRects(layer->region);
#endif

        layer->region.clear();
    }
}

void OpenGLRenderer::drawRegionRects(const Region& region) {
#if DEBUG_LAYERS_AS_REGIONS
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

        Rect r(rects[i].left, rects[i].top, rects[i].right, rects[i].bottom);
        drawColorRect(r.left, r.top, r.right, r.bottom, colors[offset + (i & 0x1)],
                SkXfermode::kSrcOver_Mode);
    }
#endif
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
    if (bounds.intersect(*mSnapshot->clipRect)) {
        bounds.snapToPixelBoundaries();
        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            region->orSelf(dirty);
        }
    }
}

void OpenGLRenderer::clearLayerRegions() {
    const size_t count = mLayers.size();
    if (count == 0) return;

    if (!mSnapshot->isIgnored()) {
        // Doing several glScissor/glClear here can negatively impact
        // GPUs with a tiler architecture, instead we draw quads with
        // the Clear blending mode

        // The list contains bounds that have already been clipped
        // against their initial clip rect, and the current clip
        // is likely different so we need to disable clipping here
        bool scissorChanged = mCaches.disableScissor();

        Vertex mesh[count * 6];
        Vertex* vertex = mesh;

        for (uint32_t i = 0; i < count; i++) {
            Rect* bounds = mLayers.itemAt(i);

            Vertex::set(vertex++, bounds->left, bounds->bottom);
            Vertex::set(vertex++, bounds->left, bounds->top);
            Vertex::set(vertex++, bounds->right, bounds->top);
            Vertex::set(vertex++, bounds->left, bounds->bottom);
            Vertex::set(vertex++, bounds->right, bounds->top);
            Vertex::set(vertex++, bounds->right, bounds->bottom);

            delete bounds;
        }

        setupDraw(false);
        setupDrawColor(0.0f, 0.0f, 0.0f, 1.0f);
        setupDrawBlending(true, SkXfermode::kClear_Mode);
        setupDrawProgram();
        setupDrawPureColorUniforms();
        setupDrawModelViewTranslate(0.0f, 0.0f, 0.0f, 0.0f, true);
        setupDrawVertices(&mesh[0].position[0]);

        glDrawArrays(GL_TRIANGLES, 0, count * 6);

        if (scissorChanged) mCaches.enableScissor();
    } else {
        for (uint32_t i = 0; i < count; i++) {
            delete mLayers.itemAt(i);
        }
    }

    mLayers.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    mSnapshot->transform->translate(dx, dy, 0.0f);
}

void OpenGLRenderer::rotate(float degrees) {
    mSnapshot->transform->rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void OpenGLRenderer::scale(float sx, float sy) {
    mSnapshot->transform->scale(sx, sy, 1.0f);
}

void OpenGLRenderer::skew(float sx, float sy) {
    mSnapshot->transform->skew(sx, sy);
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    if (matrix) {
        mSnapshot->transform->load(*matrix);
    } else {
        mSnapshot->transform->loadIdentity();
    }
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform->copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    SkMatrix transform;
    mSnapshot->transform->copyTo(transform);
    transform.preConcat(*matrix);
    mSnapshot->transform->load(transform);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    Rect clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    if (mCaches.setScissor(clip.left, mSnapshot->height - clip.bottom,
            clip.getWidth(), clip.getHeight())) {
        mDirtyClip = false;
    }
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->getLocalClip();
}

bool OpenGLRenderer::quickRejectNoScissor(float left, float top, float right, float bottom) {
    if (mSnapshot->isIgnored()) {
        return true;
    }

    Rect r(left, top, right, bottom);
    mSnapshot->transform->mapRect(r);
    r.snapToPixelBoundaries();

    Rect clipRect(*mSnapshot->clipRect);
    clipRect.snapToPixelBoundaries();

    return !clipRect.intersects(r);
}

bool OpenGLRenderer::quickRejectNoScissor(float left, float top, float right, float bottom,
        Rect& transformed, Rect& clip) {
    if (mSnapshot->isIgnored()) {
        return true;
    }

    transformed.set(left, top, right, bottom);
    mSnapshot->transform->mapRect(transformed);
    transformed.snapToPixelBoundaries();

    clip.set(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    return !clip.intersects(transformed);
}

bool OpenGLRenderer::quickRejectPreStroke(float left, float top, float right, float bottom, SkPaint* paint) {
    if (paint->getStyle() != SkPaint::kFill_Style) {
        float outset = paint->getStrokeWidth() * 0.5f;
        return quickReject(left - outset, top - outset, right + outset, bottom + outset);
    } else {
        return quickReject(left, top, right, bottom);
    }
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    mSnapshot->transform->mapRect(r);
    r.snapToPixelBoundaries();

    Rect clipRect(*mSnapshot->clipRect);
    clipRect.snapToPixelBoundaries();

    bool rejected = !clipRect.intersects(r);
    if (!isDeferred() && !rejected) {
        mCaches.setScissorEnabled(mScissorOptimizationDisabled || !clipRect.contains(r));
    }

    return rejected;
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    bool clipped = mSnapshot->clip(left, top, right, bottom, op);
    if (clipped) {
        dirtyClip();
    }
    return !mSnapshot->clipRect->isEmpty();
}

Rect* OpenGLRenderer::getClipRect() {
    return mSnapshot->clipRect;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing commands
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setupDraw(bool clear) {
    // TODO: It would be best if we could do this before quickReject()
    //       changes the scissor test state
    if (clear) clearLayerRegions();
    if (mDirtyClip) {
        setScissorFromClip();
    }
    mDescription.reset();
    mSetShaderColor = false;
    mColorSet = false;
    mColorA = mColorR = mColorG = mColorB = 0.0f;
    mTextureUnit = 0;
    mTrackDirtyRegions = true;
}

void OpenGLRenderer::setupDrawWithTexture(bool isAlpha8) {
    mDescription.hasTexture = true;
    mDescription.hasAlpha8Texture = isAlpha8;
}

void OpenGLRenderer::setupDrawWithExternalTexture() {
    mDescription.hasExternalTexture = true;
}

void OpenGLRenderer::setupDrawNoTexture() {
    mCaches.disbaleTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawAA() {
    mDescription.isAA = true;
}

void OpenGLRenderer::setupDrawVertexShape() {
    mDescription.isVertexShape = true;
}

void OpenGLRenderer::setupDrawPoint(float pointSize) {
    mDescription.isPoint = true;
    mDescription.pointSize = pointSize;
}

void OpenGLRenderer::setupDrawColor(int color) {
    setupDrawColor(color, (color >> 24) & 0xFF);
}

void OpenGLRenderer::setupDrawColor(int color, int alpha) {
    mColorA = alpha / 255.0f;
    // Second divide of a by 255 is an optimization, allowing us to simply multiply
    // the rgb values by a instead of also dividing by 255
    const float a = mColorA / 255.0f;
    mColorR = a * ((color >> 16) & 0xFF);
    mColorG = a * ((color >>  8) & 0xFF);
    mColorB = a * ((color      ) & 0xFF);
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawAlpha8Color(int color, int alpha) {
    mColorA = alpha / 255.0f;
    // Double-divide of a by 255 is an optimization, allowing us to simply multiply
    // the rgb values by a instead of also dividing by 255
    const float a = mColorA / 255.0f;
    mColorR = a * ((color >> 16) & 0xFF);
    mColorG = a * ((color >>  8) & 0xFF);
    mColorB = a * ((color      ) & 0xFF);
    mColorSet = true;
    mSetShaderColor = mDescription.setAlpha8Color(mColorR, mColorG, mColorB, mColorA);
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
    mSetShaderColor = mDescription.setColor(r, g, b, a);
}

void OpenGLRenderer::setupDrawShader() {
    if (mShader) {
        mShader->describe(mDescription, mCaches.extensions);
    }
}

void OpenGLRenderer::setupDrawColorFilter() {
    if (mColorFilter) {
        mColorFilter->describe(mDescription, mCaches.extensions);
    }
}

void OpenGLRenderer::accountForClear(SkXfermode::Mode mode) {
    if (mColorSet && mode == SkXfermode::kClear_Mode) {
        mColorA = 1.0f;
        mColorR = mColorG = mColorB = 0.0f;
        mSetShaderColor = mDescription.modulate = true;
    }
}

void OpenGLRenderer::setupDrawBlending(SkXfermode::Mode mode, bool swapSrcDst) {
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    chooseBlending((mColorSet && mColorA < 1.0f) || (mShader && mShader->blend()), mode,
            mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawBlending(bool blend, SkXfermode::Mode mode, bool swapSrcDst) {
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    chooseBlending(blend || (mColorSet && mColorA < 1.0f) || (mShader && mShader->blend()) ||
            (mColorFilter && mColorFilter->blend()), mode, mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawProgram() {
    useProgram(mCaches.programCache.get(mDescription));
}

void OpenGLRenderer::setupDrawDirtyRegionsDisabled() {
    mTrackDirtyRegions = false;
}

void OpenGLRenderer::setupDrawModelViewTranslate(float left, float top, float right, float bottom,
        bool ignoreTransform) {
    mModelView.loadTranslate(left, top, 0.0f);
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mIdentity);
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawModelViewIdentity(bool offset) {
    mCaches.currentProgram->set(mOrthoMatrix, mIdentity, *mSnapshot->transform, offset);
}

void OpenGLRenderer::setupDrawModelView(float left, float top, float right, float bottom,
        bool ignoreTransform, bool ignoreModelView) {
    if (!ignoreModelView) {
        mModelView.loadTranslate(left, top, 0.0f);
        mModelView.scale(right - left, bottom - top, 1.0f);
    } else {
        mModelView.loadIdentity();
    }
    bool dirty = right - left > 0.0f && bottom - top > 0.0f;
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
        if (mTrackDirtyRegions && dirty) {
            dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
        }
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mIdentity);
        if (mTrackDirtyRegions && dirty) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawPointUniforms() {
    int slot = mCaches.currentProgram->getUniform("pointSize");
    glUniform1f(slot, mDescription.pointSize);
}

void OpenGLRenderer::setupDrawColorUniforms() {
    if ((mColorSet && !mShader) || (mShader && mSetShaderColor)) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawPureColorUniforms() {
    if (mSetShaderColor) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawShaderUniforms(bool ignoreTransform) {
    if (mShader) {
        if (ignoreTransform) {
            mModelView.loadInverse(*mSnapshot->transform);
        }
        mShader->setupProgram(mCaches.currentProgram, mModelView, *mSnapshot, &mTextureUnit);
    }
}

void OpenGLRenderer::setupDrawShaderIdentityUniforms() {
    if (mShader) {
        mShader->setupProgram(mCaches.currentProgram, mIdentity, *mSnapshot, &mTextureUnit);
    }
}

void OpenGLRenderer::setupDrawColorFilterUniforms() {
    if (mColorFilter) {
        mColorFilter->setupProgram(mCaches.currentProgram);
    }
}

void OpenGLRenderer::setupDrawTextGammaUniforms() {
    mCaches.fontRenderer->setupProgram(mDescription, mCaches.currentProgram);
}

void OpenGLRenderer::setupDrawSimpleMesh() {
    bool force = mCaches.bindMeshBuffer();
    mCaches.bindPositionVertexPointer(force, 0);
    mCaches.unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawTexture(GLuint texture) {
    bindTexture(texture);
    mTextureUnit++;
    mCaches.enableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawExternalTexture(GLuint texture) {
    bindExternalTexture(texture);
    mTextureUnit++;
    mCaches.enableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawTextureTransform() {
    mDescription.hasTextureTransform = true;
}

void OpenGLRenderer::setupDrawTextureTransformUniforms(mat4& transform) {
    glUniformMatrix4fv(mCaches.currentProgram->getUniform("mainTextureTransform"), 1,
            GL_FALSE, &transform.data[0]);
}

void OpenGLRenderer::setupDrawMesh(GLvoid* vertices, GLvoid* texCoords, GLuint vbo) {
    bool force = false;
    if (!vertices) {
        force = mCaches.bindMeshBuffer(vbo == 0 ? mCaches.meshBuffer : vbo);
    } else {
        force = mCaches.unbindMeshBuffer();
    }

    mCaches.bindPositionVertexPointer(force, vertices);
    if (mCaches.currentProgram->texCoords >= 0) {
        mCaches.bindTexCoordsVertexPointer(force, texCoords);
    }

    mCaches.unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawMeshIndices(GLvoid* vertices, GLvoid* texCoords) {
    bool force = mCaches.unbindMeshBuffer();
    mCaches.bindPositionVertexPointer(force, vertices);
    if (mCaches.currentProgram->texCoords >= 0) {
        mCaches.bindTexCoordsVertexPointer(force, texCoords);
    }
}

void OpenGLRenderer::setupDrawVertices(GLvoid* vertices) {
    bool force = mCaches.unbindMeshBuffer();
    mCaches.bindPositionVertexPointer(force, vertices, gVertexStride);
    mCaches.unbindIndicesBuffer();
}

/**
 * Sets up the shader to draw an AA line. We draw AA lines with quads, where there is an
 * outer boundary that fades out to 0. The variables set in the shader define the proportion of
 * the width and length of the primitive occupied by the AA region. The vtxWidth and vtxLength
 * attributes (one per vertex) are values from zero to one that tells the fragment
 * shader where the fragment is in relation to the line width/length overall; these values are
 * then used to compute the proper color, based on whether the fragment lies in the fading AA
 * region of the line.
 * Note that we only pass down the width values in this setup function. The length coordinates
 * are set up for each individual segment.
 */
void OpenGLRenderer::setupDrawAALine(GLvoid* vertices, GLvoid* widthCoords,
        GLvoid* lengthCoords, float boundaryWidthProportion, int& widthSlot, int& lengthSlot) {
    bool force = mCaches.unbindMeshBuffer();
    mCaches.bindPositionVertexPointer(force, vertices, gAAVertexStride);
    mCaches.resetTexCoordsVertexPointer();
    mCaches.unbindIndicesBuffer();

    widthSlot = mCaches.currentProgram->getAttrib("vtxWidth");
    glEnableVertexAttribArray(widthSlot);
    glVertexAttribPointer(widthSlot, 1, GL_FLOAT, GL_FALSE, gAAVertexStride, widthCoords);

    lengthSlot = mCaches.currentProgram->getAttrib("vtxLength");
    glEnableVertexAttribArray(lengthSlot);
    glVertexAttribPointer(lengthSlot, 1, GL_FLOAT, GL_FALSE, gAAVertexStride, lengthCoords);

    int boundaryWidthSlot = mCaches.currentProgram->getUniform("boundaryWidth");
    glUniform1f(boundaryWidthSlot, boundaryWidthProportion);
}

void OpenGLRenderer::finishDrawAALine(const int widthSlot, const int lengthSlot) {
    glDisableVertexAttribArray(widthSlot);
    glDisableVertexAttribArray(lengthSlot);
}

void OpenGLRenderer::finishDrawTexture() {
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

status_t OpenGLRenderer::drawDisplayList(DisplayList* displayList,
        Rect& dirty, int32_t flags, uint32_t level) {

    // All the usual checks and setup operations (quickReject, setupDraw, etc.)
    // will be performed by the display list itself
    if (displayList && displayList->isRenderable()) {
        return displayList->replay(*this, dirty, flags, level);
    }

    return DrawGlInfo::kStatusDone;
}

void OpenGLRenderer::outputDisplayList(DisplayList* displayList, uint32_t level) {
    if (displayList) {
        displayList->output(*this, level);
    }
}

void OpenGLRenderer::drawAlphaBitmap(Texture* texture, float left, float top, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    float x = left;
    float y = top;

    GLenum filter = GL_LINEAR;
    bool ignoreTransform = false;
    if (mSnapshot->transform->isPureTranslate()) {
        x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);
        ignoreTransform = true;
        filter = GL_NEAREST;
    } else {
        filter = FILTER(paint);
    }

    setupDraw();
    setupDrawWithTexture(true);
    if (paint) {
        setupDrawAlpha8Color(paint->getColor(), alpha);
    }
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x + texture->width, y + texture->height, ignoreTransform);

    setupDrawTexture(texture->id);
    texture->setWrap(GL_CLAMP_TO_EDGE);
    texture->setFilter(filter);

    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
}

status_t OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    if (CC_UNLIKELY(bitmap->getConfig() == SkBitmap::kA8_Config)) {
        drawAlphaBitmap(texture, left, top, paint);
    } else {
        drawTextureRect(left, top, right, bottom, texture, paint);
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    if (quickReject(r.left, r.top, r.right, r.bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    // This could be done in a cheaper way, all we need is pass the matrix
    // to the vertex shader. The save/restore is a bit overkill.
    save(SkCanvas::kMatrix_SaveFlag);
    concatMatrix(matrix);
    drawTextureRect(0.0f, 0.0f, bitmap->width(), bitmap->height(), texture, paint);
    restore();

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmapData(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.getTransient(bitmap);
    const AutoTexture autoCleanup(texture);

    drawTextureRect(left, top, right, bottom, texture, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    if (!vertices || mSnapshot->isIgnored()) {
        return DrawGlInfo::kStatusDone;
    }

    // TODO: We should compute the bounding box when recording the display list
    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

    const uint32_t count = meshWidth * meshHeight * 6;

    // TODO: Support the colors array
    TextureVertex mesh[count];
    TextureVertex* vertex = mesh;

    for (int32_t y = 0; y < meshHeight; y++) {
        for (int32_t x = 0; x < meshWidth; x++) {
            uint32_t i = (y * (meshWidth + 1) + x) * 2;

            float u1 = float(x) / meshWidth;
            float u2 = float(x + 1) / meshWidth;
            float v1 = float(y) / meshHeight;
            float v2 = float(y + 1) / meshHeight;

            int ax = i + (meshWidth + 1) * 2;
            int ay = ax + 1;
            int bx = i;
            int by = bx + 1;
            int cx = i + 2;
            int cy = cx + 1;
            int dx = i + (meshWidth + 1) * 2 + 2;
            int dy = dx + 1;

            TextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2);
            TextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1);
            TextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1);

            TextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2);
            TextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1);
            TextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2);

            // TODO: This could be optimized to avoid unnecessary ops
            left = fminf(left, fminf(vertices[ax], fminf(vertices[bx], vertices[cx])));
            top = fminf(top, fminf(vertices[ay], fminf(vertices[by], vertices[cy])));
            right = fmaxf(right, fmaxf(vertices[ax], fmaxf(vertices[bx], vertices[cx])));
            bottom = fmaxf(bottom, fmaxf(vertices[ay], fmaxf(vertices[by], vertices[cy])));
        }
    }

    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(FILTER(paint), true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (hasLayer()) {
        dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    }

    drawTextureMesh(0.0f, 0.0f, 1.0f, 1.0f, texture->id, alpha / 255.0f,
            mode, texture->blend, &mesh[0].position[0], &mesh[0].texture[0],
            GL_TRIANGLES, count, false, false, 0, false, false);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmap(SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         SkPaint* paint) {
    if (quickReject(dstLeft, dstTop, dstRight, dstBottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    const float u1 = fmax(0.0f, srcLeft / width);
    const float v1 = fmax(0.0f, srcTop / height);
    const float u2 = fmin(1.0f, srcRight / width);
    const float v2 = fmin(1.0f, srcBottom / height);

    mCaches.unbindMeshBuffer();
    resetDrawTextureTexCoords(u1, v1, u2, v2);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    if (CC_LIKELY(mSnapshot->transform->isPureTranslate())) {
        const float x = (int) floorf(dstLeft + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(dstTop + mSnapshot->transform->getTranslateY() + 0.5f);

        GLenum filter = GL_NEAREST;
        // Enable linear filtering if the source rectangle is scaled
        if (srcRight - srcLeft != dstRight - dstLeft || srcBottom - srcTop != dstBottom - dstTop) {
            filter = FILTER(paint);
        }

        texture->setFilter(filter, true);
        drawTextureMesh(x, y, x + (dstRight - dstLeft), y + (dstBottom - dstTop),
                texture->id, alpha / 255.0f, mode, texture->blend,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        texture->setFilter(FILTER(paint), true);
        drawTextureMesh(dstLeft, dstTop, dstRight, dstBottom, texture->id, alpha / 255.0f,
                mode, texture->blend, &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount);
    }

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
        float left, float top, float right, float bottom, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndModeDirect(paint, &alpha, &mode);

    return drawPatch(bitmap, xDivs, yDivs, colors, width, height, numColors,
            left, top, right, bottom, alpha, mode);
}

status_t OpenGLRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
        float left, float top, float right, float bottom, int alpha, SkXfermode::Mode mode) {
    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    alpha *= mSnapshot->alpha;

    mCaches.activeTexture(0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);
    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(GL_LINEAR, true);

    const Patch* mesh = mCaches.patchCache.get(bitmap->width(), bitmap->height(),
            right - left, bottom - top, xDivs, yDivs, colors, width, height, numColors);

    if (CC_LIKELY(mesh && mesh->verticesCount > 0)) {
        const bool pureTranslate = mSnapshot->transform->isPureTranslate();
        // Mark the current layer dirty where we are going to draw the patch
        if (hasLayer() && mesh->hasEmptyQuads) {
            const float offsetX = left + mSnapshot->transform->getTranslateX();
            const float offsetY = top + mSnapshot->transform->getTranslateY();
            const size_t count = mesh->quads.size();
            for (size_t i = 0; i < count; i++) {
                const Rect& bounds = mesh->quads.itemAt(i);
                if (CC_LIKELY(pureTranslate)) {
                    const float x = (int) floorf(bounds.left + offsetX + 0.5f);
                    const float y = (int) floorf(bounds.top + offsetY + 0.5f);
                    dirtyLayer(x, y, x + bounds.getWidth(), y + bounds.getHeight());
                } else {
                    dirtyLayer(left + bounds.left, top + bounds.top,
                            left + bounds.right, top + bounds.bottom, *mSnapshot->transform);
                }
            }
        }

        if (CC_LIKELY(pureTranslate)) {
            const float x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
            const float y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);

            drawTextureMesh(x, y, x + right - left, y + bottom - top, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) 0, (GLvoid*) gMeshTextureOffset,
                    GL_TRIANGLES, mesh->verticesCount, false, true, mesh->meshBuffer,
                    true, !mesh->hasEmptyQuads);
        } else {
            drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) 0, (GLvoid*) gMeshTextureOffset,
                    GL_TRIANGLES, mesh->verticesCount, false, false, mesh->meshBuffer,
                    true, !mesh->hasEmptyQuads);
        }
    }

    return DrawGlInfo::kStatusDrew;
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
void OpenGLRenderer::drawConvexPath(const SkPath& path, SkPaint* paint) {
    int color = paint->getColor();
    SkPaint::Style style = paint->getStyle();
    SkXfermode::Mode mode = getXfermode(paint->getXfermode());
    bool isAA = paint->isAntiAlias();

    VertexBuffer vertexBuffer;
    // TODO: try clipping large paths to viewport
    PathRenderer::convexPathVertices(path, paint, mSnapshot->transform, vertexBuffer);

    if (!vertexBuffer.getSize()) {
        // no vertices to draw
        return;
    }

    setupDraw();
    setupDrawNoTexture();
    if (isAA) setupDrawAA();
    setupDrawVertexShape();
    setupDrawColor(color, ((color >> 24) & 0xFF) * mSnapshot->alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(isAA, mode);
    setupDrawProgram();
    setupDrawModelViewIdentity();
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderIdentityUniforms();

    void* vertices = vertexBuffer.getBuffer();
    bool force = mCaches.unbindMeshBuffer();
    mCaches.bindPositionVertexPointer(true, vertices, isAA ? gAlphaVertexStride : gVertexStride);
    mCaches.resetTexCoordsVertexPointer();
    mCaches.unbindIndicesBuffer();

    int alphaSlot = -1;
    if (isAA) {
        void* alphaCoords = ((GLbyte*) vertices) + gVertexAlphaOffset;
        alphaSlot = mCaches.currentProgram->getAttrib("vtxAlpha");

        // TODO: avoid enable/disable in back to back uses of the alpha attribute
        glEnableVertexAttribArray(alphaSlot);
        glVertexAttribPointer(alphaSlot, 1, GL_FLOAT, GL_FALSE, gAlphaVertexStride, alphaCoords);
    }

    SkRect bounds = PathRenderer::computePathBounds(path, paint);
    dirtyLayer(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, *mSnapshot->transform);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, vertexBuffer.getSize());

    if (isAA) {
        glDisableVertexAttribArray(alphaSlot);
    }
}

/**
 * We draw lines as quads (tristrips). Using GL_LINES can be difficult because the rasterization
 * rules for those lines produces some unexpected results, and may vary between hardware devices.
 * The basics of lines-as-quads is easy; we simply find the normal to the line and position the
 * corners of the quads on either side of each line endpoint, separated by the strokeWidth
 * of the line. Hairlines are more involved because we need to account for transform scaling
 * to end up with a one-pixel-wide line in screen space..
 * Anti-aliased lines add another factor to the approach. We use a specialized fragment shader
 * in combination with values that we calculate and pass down in this method. The basic approach
 * is that the quad we create contains both the core line area plus a bounding area in which
 * the translucent/AA pixels are drawn. The values we calculate tell the shader what
 * proportion of the width and the length of a given segment is represented by the boundary
 * region. The quad ends up being exactly .5 pixel larger in all directions than the non-AA quad.
 * The bounding region is actually 1 pixel wide on all sides (half pixel on the outside, half pixel
 * on the inside). This ends up giving the result we want, with pixels that are completely
 * 'inside' the line area being filled opaquely and the other pixels being filled according to
 * how far into the boundary region they are, which is determined by shader interpolation.
 */
status_t OpenGLRenderer::drawLines(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    const bool isAA = paint->isAntiAlias();
    // We use half the stroke width here because we're going to position the quad
    // corner vertices half of the width away from the line endpoints
    float halfStrokeWidth = paint->getStrokeWidth() * 0.5f;
    // A stroke width of 0 has a special meaning in Skia:
    // it draws a line 1 px wide regardless of current transform
    bool isHairLine = paint->getStrokeWidth() == 0.0f;

    float inverseScaleX = 1.0f;
    float inverseScaleY = 1.0f;
    bool scaled = false;

    int alpha;
    SkXfermode::Mode mode;

    int generatedVerticesCount = 0;
    int verticesCount = count;
    if (count > 4) {
        // Polyline: account for extra vertices needed for continuous tri-strip
        verticesCount += (count - 4);
    }

    if (isHairLine || isAA) {
        // The quad that we use for AA and hairlines needs to account for scaling. For hairlines
        // the line on the screen should always be one pixel wide regardless of scale. For
        // AA lines, we only want one pixel of translucent boundary around the quad.
        if (CC_UNLIKELY(!mSnapshot->transform->isPureTranslate())) {
            Matrix4 *mat = mSnapshot->transform;
            float m00 = mat->data[Matrix4::kScaleX];
            float m01 = mat->data[Matrix4::kSkewY];
            float m10 = mat->data[Matrix4::kSkewX];
            float m11 = mat->data[Matrix4::kScaleY];

            float scaleX = sqrtf(m00 * m00 + m01 * m01);
            float scaleY = sqrtf(m10 * m10 + m11 * m11);

            inverseScaleX = (scaleX != 0) ? (inverseScaleX / scaleX) : 0;
            inverseScaleY = (scaleY != 0) ? (inverseScaleY / scaleY) : 0;

            if (inverseScaleX != 1.0f || inverseScaleY != 1.0f) {
                scaled = true;
            }
        }
    }

    getAlphaAndMode(paint, &alpha, &mode);

    mCaches.enableScissor();

    setupDraw();
    setupDrawNoTexture();
    if (isAA) {
        setupDrawAA();
    }
    setupDrawColor(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(isAA, mode);
    setupDrawProgram();
    setupDrawModelViewIdentity(true);
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderIdentityUniforms();

    if (isHairLine) {
        // Set a real stroke width to be used in quad construction
        halfStrokeWidth = isAA? 1 : .5;
    } else if (isAA && !scaled) {
        // Expand boundary to enable AA calculations on the quad border
        halfStrokeWidth += .5f;
    }

    int widthSlot;
    int lengthSlot;

    Vertex lines[verticesCount];
    Vertex* vertices = &lines[0];

    AAVertex wLines[verticesCount];
    AAVertex* aaVertices = &wLines[0];

    if (CC_UNLIKELY(!isAA)) {
        setupDrawVertices(vertices);
    } else {
        void* widthCoords = ((GLbyte*) aaVertices) + gVertexAAWidthOffset;
        void* lengthCoords = ((GLbyte*) aaVertices) + gVertexAALengthOffset;
        // innerProportion is the ratio of the inner (non-AA) part of the line to the total
        // AA stroke width (the base stroke width expanded by a half pixel on either side).
        // This value is used in the fragment shader to determine how to fill fragments.
        // We will need to calculate the actual width proportion on each segment for
        // scaled non-hairlines, since the boundary proportion may differ per-axis when scaled.
        float boundaryWidthProportion = .5 - 1 / (2 * halfStrokeWidth);
        setupDrawAALine((void*) aaVertices, widthCoords, lengthCoords,
                boundaryWidthProportion, widthSlot, lengthSlot);
    }

    AAVertex* prevAAVertex = NULL;
    Vertex* prevVertex = NULL;

    int boundaryLengthSlot = -1;
    int boundaryWidthSlot = -1;

    for (int i = 0; i < count; i += 4) {
        // a = start point, b = end point
        vec2 a(points[i], points[i + 1]);
        vec2 b(points[i + 2], points[i + 3]);

        float length = 0;
        float boundaryLengthProportion = 0;
        float boundaryWidthProportion = 0;

        // Find the normal to the line
        vec2 n = (b - a).copyNormalized() * halfStrokeWidth;
        float x = n.x;
        n.x = -n.y;
        n.y = x;

        if (isHairLine) {
            if (isAA) {
                float wideningFactor;
                if (fabs(n.x) >= fabs(n.y)) {
                    wideningFactor = fabs(1.0f / n.x);
                } else {
                    wideningFactor = fabs(1.0f / n.y);
                }
                n *= wideningFactor;
            }

            if (scaled) {
                n.x *= inverseScaleX;
                n.y *= inverseScaleY;
            }
        } else if (scaled) {
            // Extend n by .5 pixel on each side, post-transform
            vec2 extendedN = n.copyNormalized();
            extendedN /= 2;
            extendedN.x *= inverseScaleX;
            extendedN.y *= inverseScaleY;

            float extendedNLength = extendedN.length();
            // We need to set this value on the shader prior to drawing
            boundaryWidthProportion = .5 - extendedNLength / (halfStrokeWidth + extendedNLength);
            n += extendedN;
        }

        // aa lines expand the endpoint vertices to encompass the AA boundary
        if (isAA) {
            vec2 abVector = (b - a);
            length = abVector.length();
            abVector.normalize();

            if (scaled) {
                abVector.x *= inverseScaleX;
                abVector.y *= inverseScaleY;
                float abLength = abVector.length();
                boundaryLengthProportion = .5 - abLength / (length + abLength);
            } else {
                boundaryLengthProportion = .5 - .5 / (length + 1);
            }

            abVector /= 2;
            a -= abVector;
            b += abVector;
        }

        // Four corners of the rectangle defining a thick line
        vec2 p1 = a - n;
        vec2 p2 = a + n;
        vec2 p3 = b + n;
        vec2 p4 = b - n;


        const float left = fmin(p1.x, fmin(p2.x, fmin(p3.x, p4.x)));
        const float right = fmax(p1.x, fmax(p2.x, fmax(p3.x, p4.x)));
        const float top = fmin(p1.y, fmin(p2.y, fmin(p3.y, p4.y)));
        const float bottom = fmax(p1.y, fmax(p2.y, fmax(p3.y, p4.y)));

        if (!quickRejectNoScissor(left, top, right, bottom)) {
            if (!isAA) {
                if (prevVertex != NULL) {
                    // Issue two repeat vertices to create degenerate triangles to bridge
                    // between the previous line and the new one. This is necessary because
                    // we are creating a single triangle_strip which will contain
                    // potentially discontinuous line segments.
                    Vertex::set(vertices++, prevVertex->position[0], prevVertex->position[1]);
                    Vertex::set(vertices++, p1.x, p1.y);
                    generatedVerticesCount += 2;
                }

                Vertex::set(vertices++, p1.x, p1.y);
                Vertex::set(vertices++, p2.x, p2.y);
                Vertex::set(vertices++, p4.x, p4.y);
                Vertex::set(vertices++, p3.x, p3.y);

                prevVertex = vertices - 1;
                generatedVerticesCount += 4;
            } else {
                if (!isHairLine && scaled) {
                    // Must set width proportions per-segment for scaled non-hairlines to use the
                    // correct AA boundary dimensions
                    if (boundaryWidthSlot < 0) {
                        boundaryWidthSlot =
                                mCaches.currentProgram->getUniform("boundaryWidth");
                    }

                    glUniform1f(boundaryWidthSlot, boundaryWidthProportion);
                }

                if (boundaryLengthSlot < 0) {
                    boundaryLengthSlot = mCaches.currentProgram->getUniform("boundaryLength");
                }

                glUniform1f(boundaryLengthSlot, boundaryLengthProportion);

                if (prevAAVertex != NULL) {
                    // Issue two repeat vertices to create degenerate triangles to bridge
                    // between the previous line and the new one. This is necessary because
                    // we are creating a single triangle_strip which will contain
                    // potentially discontinuous line segments.
                    AAVertex::set(aaVertices++,prevAAVertex->position[0],
                            prevAAVertex->position[1], prevAAVertex->width, prevAAVertex->length);
                    AAVertex::set(aaVertices++, p4.x, p4.y, 1, 1);
                    generatedVerticesCount += 2;
                }

                AAVertex::set(aaVertices++, p4.x, p4.y, 1, 1);
                AAVertex::set(aaVertices++, p1.x, p1.y, 1, 0);
                AAVertex::set(aaVertices++, p3.x, p3.y, 0, 1);
                AAVertex::set(aaVertices++, p2.x, p2.y, 0, 0);

                prevAAVertex = aaVertices - 1;
                generatedVerticesCount += 4;
            }

            dirtyLayer(a.x == b.x ? left - 1 : left, a.y == b.y ? top - 1 : top,
                    a.x == b.x ? right: right, a.y == b.y ? bottom: bottom,
                    *mSnapshot->transform);
        }
    }

    if (generatedVerticesCount > 0) {
       glDrawArrays(GL_TRIANGLE_STRIP, 0, generatedVerticesCount);
    }

    if (isAA) {
        finishDrawAALine(widthSlot, lengthSlot);
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    // TODO: The paint's cap style defines whether the points are square or circular
    // TODO: Handle AA for round points

    // A stroke width of 0 has a special meaning in Skia:
    // it draws an unscaled 1px point
    float strokeWidth = paint->getStrokeWidth();
    const bool isHairLine = paint->getStrokeWidth() == 0.0f;
    if (isHairLine) {
        // Now that we know it's hairline, we can set the effective width, to be used later
        strokeWidth = 1.0f;
    }
    const float halfWidth = strokeWidth / 2;
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    int verticesCount = count >> 1;
    int generatedVerticesCount = 0;

    TextureVertex pointsData[verticesCount];
    TextureVertex* vertex = &pointsData[0];

    // TODO: We should optimize this method to not generate vertices for points
    // that lie outside of the clip.
    mCaches.enableScissor();

    setupDraw();
    setupDrawNoTexture();
    setupDrawPoint(strokeWidth);
    setupDrawColor(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(mode);
    setupDrawProgram();
    setupDrawModelViewIdentity(true);
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawPointUniforms();
    setupDrawShaderIdentityUniforms();
    setupDrawMesh(vertex);

    for (int i = 0; i < count; i += 2) {
        TextureVertex::set(vertex++, points[i], points[i + 1], 0.0f, 0.0f);
        generatedVerticesCount++;

        float left = points[i] - halfWidth;
        float right = points[i] + halfWidth;
        float top = points[i + 1] - halfWidth;
        float bottom = points [i + 1] + halfWidth;

        dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    }

    glDrawArrays(GL_POINTS, 0, generatedVerticesCount);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    // No need to check against the clip, we fill the clip region
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    Rect& clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode, true);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawShape(float left, float top, const PathTexture* texture,
        SkPaint* paint) {
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    const float x = left + texture->left - texture->offset;
    const float y = top + texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.roundRectShapeCache.getRoundRect(
                right - left, bottom - top, rx, ry, p);
        return drawShape(left, top, texture, p);
    }

    SkPath path;
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        float outset = p->getStrokeWidth() / 2;
        rect.outset(outset, outset);
        rx += outset;
        ry += outset;
    }
    path.addRoundRect(rect, rx, ry);
    drawConvexPath(path, p);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawCircle(float x, float y, float radius, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(x - radius, y - radius,
            x + radius, y + radius, p)) {
        return DrawGlInfo::kStatusDone;
    }
    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.circleShapeCache.getCircle(radius, p);
        return drawShape(x - radius, y - radius, texture, p);
    }

    SkPath path;
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        path.addCircle(x, y, radius + p->getStrokeWidth() / 2);
    } else {
        path.addCircle(x, y, radius);
    }
    drawConvexPath(path, p);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.ovalShapeCache.getOval(right - left, bottom - top, p);
        return drawShape(left, top, texture, p);
    }

    SkPath path;
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
    }
    path.addOval(rect);
    drawConvexPath(path, p);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p)) {
        return DrawGlInfo::kStatusDone;
    }

    if (fabs(sweepAngle) >= 360.0f) {
        return drawOval(left, top, right, bottom, p);
    }

    // TODO: support fills (accounting for concavity if useCenter && sweepAngle > 180)
    if (p->getStyle() != SkPaint::kStroke_Style || p->getPathEffect() != 0 || p->getStrokeCap() != SkPaint::kButt_Cap || useCenter) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.arcShapeCache.getArc(right - left, bottom - top,
                startAngle, sweepAngle, useCenter, p);
        return drawShape(left, top, texture, p);
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

    return DrawGlInfo::kStatusDrew;
}

// See SkPaintDefaults.h
#define SkPaintDefaults_MiterLimit SkIntToScalar(4)

status_t OpenGLRenderer::drawRect(float left, float top, float right, float bottom, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getStyle() != SkPaint::kFill_Style) {
        // only fill style is supported by drawConvexPath, since others have to handle joins
        if (p->getPathEffect() != 0 || p->getStrokeJoin() != SkPaint::kMiter_Join ||
                p->getStrokeMiter() != SkPaintDefaults_MiterLimit) {
            mCaches.activeTexture(0);
            const PathTexture* texture =
                    mCaches.rectShapeCache.getRect(right - left, bottom - top, p);
            return drawShape(left, top, texture, p);
        }

        SkPath path;
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
            rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
        }
        path.addRect(rect);
        drawConvexPath(path, p);

        return DrawGlInfo::kStatusDrew;
    }

    if (p->isAntiAlias() && !mSnapshot->transform->isSimple()) {
        SkPath path;
        path.addRect(left, top, right, bottom);
        drawConvexPath(path, p);
    } else {
        drawColorRect(left, top, right, bottom, p->getColor(), getXfermode(p->getXfermode()));
    }

    return DrawGlInfo::kStatusDrew;
}

void OpenGLRenderer::drawTextShadow(SkPaint* paint, const char* text, int bytesCount, int count,
        const float* positions, FontRenderer& fontRenderer, int alpha, SkXfermode::Mode mode,
        float x, float y) {
    mCaches.activeTexture(0);

    // NOTE: The drop shadow will not perform gamma correction
    //       if shader-based correction is enabled
    mCaches.dropShadowCache.setFontRenderer(fontRenderer);
    const ShadowTexture* shadow = mCaches.dropShadowCache.get(
            paint, text, bytesCount, count, mShadowRadius, positions);
    const AutoTexture autoCleanup(shadow);

    const float sx = x - shadow->left + mShadowDx;
    const float sy = y - shadow->top + mShadowDy;

    const int shadowAlpha = ((mShadowColor >> 24) & 0xFF) * mSnapshot->alpha;
    int shadowColor = mShadowColor;
    if (mShader) {
        shadowColor = 0xffffffff;
    }

    setupDraw();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(shadowColor, shadowAlpha < 255 ? shadowAlpha : alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(sx, sy, sx + shadow->width, sy + shadow->height);
    setupDrawTexture(shadow->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

status_t OpenGLRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, SkPaint* paint) {
    if (text == NULL || count == 0 || mSnapshot->isIgnored() ||
            (paint->getAlpha() * mSnapshot->alpha == 0 && paint->getXfermode() == NULL)) {
        return DrawGlInfo::kStatusDone;
    }

    // NOTE: Skia does not support perspective transform on drawPosText yet
    if (!mSnapshot->transform->isSimple()) {
        return DrawGlInfo::kStatusDone;
    }

    float x = 0.0f;
    float y = 0.0f;
    const bool pureTranslate = mSnapshot->transform->isPureTranslate();
    if (pureTranslate) {
        x = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()),
            paint->getTextSize());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (CC_UNLIKELY(mHasShadow)) {
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer, alpha, mode,
                0.0f, 0.0f);
    }

    // Pick the appropriate texture filtering
    bool linearFilter = mSnapshot->transform->changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }

    mCaches.activeTexture(0);
    setupDraw();
    setupDrawTextGamma(paint);
    setupDrawDirtyRegionsDisabled();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x, y, pureTranslate, true);
    setupDrawTexture(fontRenderer.getTexture(linearFilter));
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms(pureTranslate);
    setupDrawTextGammaUniforms();

    const Rect* clip = pureTranslate ? mSnapshot->clipRect : &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    if (fontRenderer.renderPosText(paint, clip, text, 0, bytesCount, count, x, y,
            positions, hasActiveLayer ? &bounds : NULL)) {
        if (hasActiveLayer) {
            if (!pureTranslate) {
                mSnapshot->transform->mapRect(bounds);
            }
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, const float* positions, SkPaint* paint, float length) {
    if (text == NULL || count == 0 || mSnapshot->isIgnored() ||
            (paint->getAlpha() * mSnapshot->alpha == 0 && paint->getXfermode() == NULL)) {
        return DrawGlInfo::kStatusDone;
    }

    if (length < 0.0f) length = paint->measureText(text, bytesCount);
    switch (paint->getTextAlign()) {
        case SkPaint::kCenter_Align:
            x -= length / 2.0f;
            break;
        case SkPaint::kRight_Align:
            x -= length;
            break;
        default:
            break;
    }

    SkPaint::FontMetrics metrics;
    paint->getFontMetrics(&metrics, 0.0f);
    if (quickReject(x, y + metrics.fTop, x + length, y + metrics.fBottom)) {
        return DrawGlInfo::kStatusDone;
    }

    const float oldX = x;
    const float oldY = y;
    const bool pureTranslate = mSnapshot->transform->isPureTranslate();
    if (CC_LIKELY(pureTranslate)) {
        x = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);
    }

#if DEBUG_GLYPHS
    ALOGD("OpenGLRenderer drawText() with FontID=%d",
            SkTypeface::UniqueID(paint->getTypeface()));
#endif

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()),
            paint->getTextSize());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (CC_UNLIKELY(mHasShadow)) {
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer, alpha, mode,
                oldX, oldY);
    }

    // Pick the appropriate texture filtering
    bool linearFilter = mSnapshot->transform->changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }

    // The font renderer will always use texture unit 0
    mCaches.activeTexture(0);
    setupDraw();
    setupDrawTextGamma(paint);
    setupDrawDirtyRegionsDisabled();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x, y, pureTranslate, true);
    // See comment above; the font renderer must use texture unit 0
    // assert(mTextureUnit == 0)
    setupDrawTexture(fontRenderer.getTexture(linearFilter));
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms(pureTranslate);
    setupDrawTextGammaUniforms();

    const Rect* clip = pureTranslate ? mSnapshot->clipRect :
            (mSnapshot->hasPerspectiveTransform() ? NULL : &mSnapshot->getLocalClip());
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    bool status;
    if (CC_UNLIKELY(paint->getTextAlign() != SkPaint::kLeft_Align)) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        status = fontRenderer.renderPosText(&paintCopy, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &bounds : NULL);
    } else {
        status = fontRenderer.renderPosText(paint, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &bounds : NULL);
    }

    if (status && hasActiveLayer) {
        if (!pureTranslate) {
            mSnapshot->transform->mapRect(bounds);
        }
        dirtyLayerUnchecked(bounds, getRegion());
    }

    drawTextDecorations(text, bytesCount, length, oldX, oldY, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawTextOnPath(const char* text, int bytesCount, int count, SkPath* path,
        float hOffset, float vOffset, SkPaint* paint) {
    if (text == NULL || count == 0 || mSnapshot->isIgnored() ||
            (paint->getAlpha() == 0 && paint->getXfermode() == NULL)) {
        return DrawGlInfo::kStatusDone;
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()),
            paint->getTextSize());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    mCaches.activeTexture(0);
    setupDraw();
    setupDrawTextGamma(paint);
    setupDrawDirtyRegionsDisabled();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(0.0f, 0.0f, 0.0f, 0.0f, false, true);
    setupDrawTexture(fontRenderer.getTexture(true));
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms(false);
    setupDrawTextGammaUniforms();

    const Rect* clip = &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    if (fontRenderer.renderTextOnPath(paint, clip, text, 0, bytesCount, count, path,
            hOffset, vOffset, hasActiveLayer ? &bounds : NULL)) {
        if (hasActiveLayer) {
            mSnapshot->transform->mapRect(bounds);
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    mCaches.activeTexture(0);

    // TODO: Perform early clip test before we rasterize the path
    const PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawLayer(Layer* layer, float x, float y, SkPaint* paint) {
    if (!layer) {
        return DrawGlInfo::kStatusDone;
    }

    mat4* transform = NULL;
    if (layer->isTextureLayer()) {
        transform = &layer->getTransform();
        if (!transform->isIdentity()) {
            save(0);
            mSnapshot->transform->multiply(*transform);
        }
    }

    Rect transformed;
    Rect clip;
    const bool rejected = quickRejectNoScissor(x, y,
            x + layer->layer.getWidth(), y + layer->layer.getHeight(), transformed, clip);

    if (rejected) {
        if (transform && !transform->isIdentity()) {
            restore();
        }
        return DrawGlInfo::kStatusDone;
    }

    bool debugLayerUpdate = false;
    if (updateLayer(layer, true)) {
        debugLayerUpdate = mCaches.debugLayersUpdates;
    }

    mCaches.setScissorEnabled(mScissorOptimizationDisabled || !clip.contains(transformed));
    mCaches.activeTexture(0);

    if (CC_LIKELY(!layer->region.isEmpty())) {
        SkiaColorFilter* oldFilter = mColorFilter;
        mColorFilter = layer->getColorFilter();

        if (layer->region.isRect()) {
            composeLayerRect(layer, layer->regionRect);
        } else if (layer->mesh) {
            const float a = layer->getAlpha() / 255.0f;
            setupDraw();
            setupDrawWithTexture();
            setupDrawColor(a, a, a, a);
            setupDrawColorFilter();
            setupDrawBlending(layer->isBlend() || a < 1.0f, layer->getMode(), false);
            setupDrawProgram();
            setupDrawPureColorUniforms();
            setupDrawColorFilterUniforms();
            setupDrawTexture(layer->getTexture());
            if (CC_LIKELY(mSnapshot->transform->isPureTranslate())) {
                int tx = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
                int ty = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);

                layer->setFilter(GL_NEAREST);
                setupDrawModelViewTranslate(tx, ty,
                        tx + layer->layer.getWidth(), ty + layer->layer.getHeight(), true);
            } else {
                layer->setFilter(GL_LINEAR);
                setupDrawModelViewTranslate(x, y,
                        x + layer->layer.getWidth(), y + layer->layer.getHeight());
            }
            setupDrawMesh(&layer->mesh[0].position[0], &layer->mesh[0].texture[0]);

            glDrawElements(GL_TRIANGLES, layer->meshElementCount,
                    GL_UNSIGNED_SHORT, layer->meshIndices);

            finishDrawTexture();

#if DEBUG_LAYERS_AS_REGIONS
            drawRegionRects(layer->region);
#endif
        }

        mColorFilter = oldFilter;

        if (debugLayerUpdate) {
            drawColorRect(x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight(),
                    0x7f00ff00, SkXfermode::kSrcOver_Mode);
        }
    }

    if (transform && !transform->isIdentity()) {
        restore();
    }

    return DrawGlInfo::kStatusDrew;
}

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShader() {
    mShader = NULL;
}

void OpenGLRenderer::setupShader(SkiaShader* shader) {
    mShader = shader;
    if (mShader) {
        mShader->set(&mCaches.textureCache, &mCaches.gradientCache);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Color filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetColorFilter() {
    mColorFilter = NULL;
}

void OpenGLRenderer::setupColorFilter(SkiaColorFilter* filter) {
    mColorFilter = filter;
}

///////////////////////////////////////////////////////////////////////////////
// Drop shadow
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShadow() {
    mHasShadow = false;
}

void OpenGLRenderer::setupShadow(float radius, float dx, float dy, int color) {
    mHasShadow = true;
    mShadowRadius = radius;
    mShadowDx = dx;
    mShadowDy = dy;
    mShadowColor = color;
}

///////////////////////////////////////////////////////////////////////////////
// Draw filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetPaintFilter() {
    mHasDrawFilter = false;
}

void OpenGLRenderer::setupPaintFilter(int clearBits, int setBits) {
    mHasDrawFilter = true;
    mPaintFilterClearBits = clearBits & SkPaint::kAllFlags;
    mPaintFilterSetBits = setBits & SkPaint::kAllFlags;
}

SkPaint* OpenGLRenderer::filterPaint(SkPaint* paint) {
    if (CC_LIKELY(!mHasDrawFilter || !paint)) return paint;

    uint32_t flags = paint->getFlags();

    mFilteredPaint = *paint;
    mFilteredPaint.setFlags((flags & ~mPaintFilterClearBits) | mPaintFilterSetBits);

    return &mFilteredPaint;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing implementation
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawPathTexture(const PathTexture* texture,
        float x, float y, SkPaint* paint) {
    if (quickReject(x, y, x + texture->width, y + texture->height)) {
        return;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    setupDraw();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x + texture->width, y + texture->height);
    setupDrawTexture(texture->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
}

// Same values used by Skia
#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

void OpenGLRenderer::drawTextDecorations(const char* text, int bytesCount, float length,
        float x, float y, SkPaint* paint) {
    // Handle underline and strike-through
    uint32_t flags = paint->getFlags();
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        SkPaint paintCopy(*paint);
        float underlineWidth = length;
        // If length is > 0.0f, we already measured the text for the text alignment
        if (length <= 0.0f) {
            underlineWidth = paintCopy.measureText(text, bytesCount);
        }

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

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode, bool ignoreTransform) {
    // If a shader is set, preserve only the alpha
    if (mShader) {
        color |= 0x00ffffff;
    }

    setupDraw();
    setupDrawNoTexture();
    setupDrawColor(color, ((color >> 24) & 0xFF) * mSnapshot->alpha);
    setupDrawShader();
    setupDrawColorFilter();
    setupDrawBlending(mode);
    setupDrawProgram();
    setupDrawModelView(left, top, right, bottom, ignoreTransform);
    setupDrawColorUniforms();
    setupDrawShaderUniforms(ignoreTransform);
    setupDrawColorFilterUniforms();
    setupDrawSimpleMesh();

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        Texture* texture, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    if (CC_LIKELY(mSnapshot->transform->isPureTranslate())) {
        const float x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);

        texture->setFilter(GL_NEAREST, true);
        drawTextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
                alpha / 255.0f, mode, texture->blend, (GLvoid*) NULL,
                (GLvoid*) gMeshTextureOffset, GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        texture->setFilter(FILTER(paint), true);
        drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode,
                texture->blend, (GLvoid*) NULL, (GLvoid*) gMeshTextureOffset,
                GL_TRIANGLE_STRIP, gMeshCount);
    }
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend) {
    drawTextureMesh(left, top, right, bottom, texture, alpha, mode, blend,
            (GLvoid*) NULL, (GLvoid*) gMeshTextureOffset, GL_TRIANGLE_STRIP, gMeshCount);
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform, GLuint vbo, bool ignoreScale, bool dirty) {

    setupDraw();
    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter();
    setupDrawBlending(blend, mode, swapSrcDst);
    setupDrawProgram();
    if (!dirty) {
        setupDrawDirtyRegionsDisabled();
    }
    if (!ignoreScale) {
        setupDrawModelView(left, top, right, bottom, ignoreTransform);
    } else {
        setupDrawModelViewTranslate(left, top, right, bottom, ignoreTransform);
    }
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawTexture(texture);
    setupDrawMesh(vertices, texCoords, vbo);

    glDrawArrays(drawMode, 0, elementsCount);

    finishDrawTexture();
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode,
        ProgramDescription& description, bool swapSrcDst) {
    blend = blend || mode != SkXfermode::kSrcOver_Mode;

    if (blend) {
        // These blend modes are not supported by OpenGL directly and have
        // to be implemented using shaders. Since the shader will perform
        // the blending, turn blending off here
        // If the blend mode cannot be implemented using shaders, fall
        // back to the default SrcOver blend mode instead
        if (CC_UNLIKELY(mode > SkXfermode::kScreen_Mode)) {
            if (CC_UNLIKELY(mCaches.extensions.hasFramebufferFetch())) {
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
        if (mCaches.currentProgram != NULL) mCaches.currentProgram->remove();
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

void OpenGLRenderer::getAlphaAndMode(SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
    getAlphaAndModeDirect(paint, alpha,  mode);
    *alpha *= mSnapshot->alpha;
}

}; // namespace uirenderer
}; // namespace android
