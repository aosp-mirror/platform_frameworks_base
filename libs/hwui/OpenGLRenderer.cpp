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
#include <SkTypeface.h>

#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <private/hwui/DrawGlInfo.h>

#include <ui/Rect.h>

#include "OpenGLRenderer.h"
#include "DeferredDisplayList.h"
#include "DisplayListRenderer.h"
#include "Fence.h"
#include "PathTessellator.h"
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

OpenGLRenderer::OpenGLRenderer():
        mCaches(Caches::getInstance()), mExtensions(Extensions::getInstance()) {
    // *set* draw modifiers to be 0
    memset(&mDrawModifiers, 0, sizeof(mDrawModifiers));
    mDrawModifiers.mOverrideLayerAlpha = 1.0f;

    memcpy(mMeshVertices, gMeshVertices, sizeof(gMeshVertices));

    mFirstSnapshot = new Snapshot;
    mFrameStarted = false;
    mCountOverdraw = false;

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

void OpenGLRenderer::setName(const char* name) {
    if (name) {
        mName.setTo(name);
    } else {
        mName.clear();
    }
}

const char* OpenGLRenderer::getName() const {
    return mName.string();
}

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

void OpenGLRenderer::setupFrameState(float left, float top,
        float right, float bottom, bool opaque) {
    mCaches.clearGarbage();

    mOpaque = opaque;
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->fbo = getTargetFbo();
    mSaveCount = 1;

    mSnapshot->setClip(left, top, right, bottom);
    mTilingClip.set(left, top, right, bottom);
}

status_t OpenGLRenderer::startFrame() {
    if (mFrameStarted) return DrawGlInfo::kStatusDone;
    mFrameStarted = true;

    mDirtyClip = true;

    discardFramebuffer(mTilingClip.left, mTilingClip.top, mTilingClip.right, mTilingClip.bottom);

    glViewport(0, 0, mWidth, mHeight);

    // Functors break the tiling extension in pretty spectacular ways
    // This ensures we don't use tiling when a functor is going to be
    // invoked during the frame
    mSuppressTiling = mCaches.hasRegisteredFunctors();

    startTiling(mSnapshot, true);

    debugOverdraw(true, true);

    return clear(mTilingClip.left, mTilingClip.top,
            mTilingClip.right, mTilingClip.bottom, mOpaque);
}

status_t OpenGLRenderer::prepare(bool opaque) {
    return prepareDirty(0.0f, 0.0f, mWidth, mHeight, opaque);
}

status_t OpenGLRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {

    setupFrameState(left, top, right, bottom, opaque);

    // Layer renderers will start the frame immediately
    // The framebuffer renderer will first defer the display list
    // for each layer and wait until the first drawing command
    // to start the frame
    if (mSnapshot->fbo == 0) {
        syncState();
        updateLayers();
    } else {
        return startFrame();
    }

    return DrawGlInfo::kStatusDone;
}

void OpenGLRenderer::discardFramebuffer(float left, float top, float right, float bottom) {
    // If we know that we are going to redraw the entire framebuffer,
    // perform a discard to let the driver know we don't need to preserve
    // the back buffer for this frame.
    if (mExtensions.hasDiscardFramebuffer() &&
            left <= 0.0f && top <= 0.0f && right >= mWidth && bottom >= mHeight) {
        const bool isFbo = getTargetFbo() == 0;
        const GLenum attachments[] = {
                isFbo ? (const GLenum) GL_COLOR_EXT : (const GLenum) GL_COLOR_ATTACHMENT0,
                isFbo ? (const GLenum) GL_STENCIL_EXT : (const GLenum) GL_STENCIL_ATTACHMENT };
        glDiscardFramebufferEXT(GL_FRAMEBUFFER, 1, attachments);
    }
}

status_t OpenGLRenderer::clear(float left, float top, float right, float bottom, bool opaque) {
#ifdef QCOM_HARDWARE
    mCaches.enableScissor();
    mCaches.setScissor(left, mSnapshot->height - bottom, right - left, bottom - top);
    glClear(GL_COLOR_BUFFER_BIT);
    if (opaque && !mCountOverdraw) {
        mCaches.resetScissor();
        return DrawGlInfo::kStatusDone;
    }
    return DrawGlInfo::kStatusDrew;  
#else
    if (!opaque || mCountOverdraw) {
        mCaches.enableScissor();
        mCaches.setScissor(left, mSnapshot->height - bottom, right - left, bottom - top);
        glClear(GL_COLOR_BUFFER_BIT);

        return DrawGlInfo::kStatusDrew;
    }
    mCaches.resetScissor();
    return DrawGlInfo::kStatusDone;
#endif
}

void OpenGLRenderer::syncState() {
    if (mCaches.blend) {
        glEnable(GL_BLEND);
    } else {
        glDisable(GL_BLEND);
    }
}

void OpenGLRenderer::startTiling(const sp<Snapshot>& s, bool opaque) {
    if (!mSuppressTiling) {
        Rect* clip = &mTilingClip;
        if (s->flags & Snapshot::kFlagFboTarget) {
            clip = &(s->layer->clipRect);
        }

        startTiling(*clip, s->height, opaque);
    }
}

void OpenGLRenderer::startTiling(const Rect& clip, int windowHeight, bool opaque) {
    if (!mSuppressTiling) {
        mCaches.startTiling(clip.left, windowHeight - clip.bottom,
                clip.right - clip.left, clip.bottom - clip.top, opaque);
    }
}

void OpenGLRenderer::endTiling() {
    if (!mSuppressTiling) mCaches.endTiling();
}

void OpenGLRenderer::finish() {
    renderOverdraw();
    endTiling();

    // When finish() is invoked on FBO 0 we've reached the end
    // of the current frame
    if (getTargetFbo() == 0) {
        mCaches.pathCache.trim();
    }

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

    if (mCountOverdraw) {
        countOverdraw();
    }

    mFrameStarted = false;
}

void OpenGLRenderer::interrupt() {
    if (mCaches.currentProgram) {
        if (mCaches.currentProgram->isInUse()) {
            mCaches.currentProgram->remove();
            mCaches.currentProgram = NULL;
        }
    }
    mCaches.resetActiveTexture();
    mCaches.unbindMeshBuffer();
    mCaches.unbindIndicesBuffer();
    mCaches.resetVertexPointers();
    mCaches.disableTexCoordsVertexArray();
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
    mCaches.resetBoundTextures();

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
        interrupt();
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
        resume();
    }

    return result;
}

status_t OpenGLRenderer::callDrawGLFunction(Functor* functor, Rect& dirty) {
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    detachFunctor(functor);


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

    bool dirtyClip = mDirtyClip;
    // setup GL state for functor
    if (mDirtyClip) {
        setStencilFromClip(); // can issue draws, so must precede enableScissor()/interrupt()
    }
    if (mCaches.enableScissor() || dirtyClip) {
        setScissorFromClip();
    }
    interrupt();

    // call functor immediately after GL state setup
    status_t result = (*functor)(DrawGlInfo::kModeDraw, &info);

    if (result != DrawGlInfo::kStatusDone) {
        Rect localDirty(info.dirtyLeft, info.dirtyTop, info.dirtyRight, info.dirtyBottom);
        dirty.unionWith(localDirty);

        if (result & DrawGlInfo::kStatusInvoke) {
            mFunctors.add(functor);
        }
    }

    resume();
    return result | DrawGlInfo::kStatusDrew;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

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
        const Rect* clip = &mTilingClip;

        mCaches.enableScissor();
        mCaches.setScissor(clip->left, mFirstSnapshot->height - clip->bottom,
                clip->right - clip->left, clip->bottom - clip->top);

        // 1x overdraw
        mCaches.stencil.enableDebugTest(2);
        drawColor(mCaches.getOverdrawColor(1), SkXfermode::kSrcOver_Mode);

        // 2x overdraw
        mCaches.stencil.enableDebugTest(3);
        drawColor(mCaches.getOverdrawColor(2), SkXfermode::kSrcOver_Mode);

        // 3x overdraw
        mCaches.stencil.enableDebugTest(4);
        drawColor(mCaches.getOverdrawColor(3), SkXfermode::kSrcOver_Mode);

        // 4x overdraw and higher
        mCaches.stencil.enableDebugTest(4, true);
        drawColor(mCaches.getOverdrawColor(4), SkXfermode::kSrcOver_Mode);

        mCaches.stencil.disable();
    }
}

void OpenGLRenderer::countOverdraw() {
    size_t count = mWidth * mHeight;
    uint32_t* buffer = new uint32_t[count];
    glReadPixels(0, 0, mWidth, mHeight, GL_RGBA, GL_UNSIGNED_BYTE, &buffer[0]);

    size_t total = 0;
    for (size_t i = 0; i < count; i++) {
        total += buffer[i] & 0xff;
    }

    mOverdraw = total / float(count);

    delete[] buffer;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::updateLayer(Layer* layer, bool inFrame) {
    if (layer->deferredUpdateScheduled && layer->renderer &&
            layer->displayList && layer->displayList->isRenderable()) {
        ATRACE_CALL();

        Rect& dirty = layer->dirtyRect;

        if (inFrame) {
            endTiling();
            debugOverdraw(false, false);
        }

        if (CC_UNLIKELY(inFrame || mCaches.drawDeferDisabled)) {
            layer->render();
        } else {
            layer->defer();
        }

        if (inFrame) {
            resumeAfterLayer();
            startTiling(mSnapshot);
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
            Layer* layer = mLayerUpdates.itemAt(i);
            updateLayer(layer, false);
            if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
                mCaches.resourceCache.decrementRefcount(layer);
            }
        }

        if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
            mLayerUpdates.clear();
            glBindFramebuffer(GL_FRAMEBUFFER, getTargetFbo());
        }
        endMark();
    }
}

void OpenGLRenderer::flushLayers() {
    int count = mLayerUpdates.size();
    if (count > 0) {
        startMark("Apply Layer Updates");
        char layerName[12];

        // Note: it is very important to update the layers in order
        for (int i = 0; i < count; i++) {
            sprintf(layerName, "Layer #%d", i);
            startMark(layerName);

            ATRACE_BEGIN("flushLayer");
            Layer* layer = mLayerUpdates.itemAt(i);
            layer->flush();
            ATRACE_END();

            mCaches.resourceCache.decrementRefcount(layer);

            endMark();
        }

        mLayerUpdates.clear();
        glBindFramebuffer(GL_FRAMEBUFFER, getTargetFbo());

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
        mCaches.resourceCache.incrementRefcount(layer);
    }
}

void OpenGLRenderer::cancelLayerUpdate(Layer* layer) {
    if (layer) {
        for (int i = mLayerUpdates.size() - 1; i >= 0; i--) {
            if (mLayerUpdates.itemAt(i) == layer) {
                mLayerUpdates.removeAt(i);
                mCaches.resourceCache.decrementRefcount(layer);
                break;
            }
        }
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

void OpenGLRenderer::flushLayerUpdates() {
    syncState();
    updateLayers();
    flushLayers();
    // Wait for all the layer updates to be executed
    AutoFence fence;
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
        endMark(); // Savelayer
        startMark("ComposeLayer");
        composeLayer(current, previous);
        endMark();
    }

    return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        int alpha, SkXfermode::Mode mode, int flags) {
    const GLuint previousFbo = mSnapshot->fbo;
    const int count = saveSnapshot(flags);

    if (!mSnapshot->isIgnored()) {
        createLayer(left, top, right, bottom, alpha, mode, flags, previousFbo);
    }

    return count;
}

void OpenGLRenderer::calculateLayerBoundsAndClip(Rect& bounds, Rect& clip, bool fboLayer) {
    const Rect untransformedBounds(bounds);

    currentTransform().mapRect(bounds);

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
            inverse.loadInverse(currentTransform());
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
        mSnapshot->empty = fboLayer;
    } else {
        mSnapshot->invisible = mSnapshot->invisible || (alpha <= ALPHA_THRESHOLD && fboLayer);
    }
}

int OpenGLRenderer::saveLayerDeferred(float left, float top, float right, float bottom,
        int alpha, SkXfermode::Mode mode, int flags) {
    const GLuint previousFbo = mSnapshot->fbo;
    const int count = saveSnapshot(flags);

    if (!mSnapshot->isIgnored() && (flags & SkCanvas::kClipToLayer_SaveFlag)) {
        // initialize the snapshot as though it almost represents an FBO layer so deferred draw
        // operations will be able to store and restore the current clip and transform info, and
        // quick rejection will be correct (for display lists)

        Rect bounds(left, top, right, bottom);
        Rect clip;
        calculateLayerBoundsAndClip(bounds, clip, true);
        updateSnapshotIgnoreForLayer(bounds, clip, true, alpha);

        if (!mSnapshot->isIgnored()) {
            mSnapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
            mSnapshot->resetClip(clip.left, clip.top, clip.right, clip.bottom);
            mSnapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
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
        int alpha, SkXfermode::Mode mode, int flags, GLuint previousFbo) {
    LAYER_LOGD("Requesting layer %.2fx%.2f", right - left, bottom - top);
    LAYER_LOGD("Layer cache size = %d", mCaches.layerCache.getSize());

    const bool fboLayer = flags & SkCanvas::kClipToLayer_SaveFlag;

    // Window coordinates of the layer
    Rect clip;
    Rect bounds(left, top, right, bottom);
    calculateLayerBoundsAndClip(bounds, clip, fboLayer);
    updateSnapshotIgnoreForLayer(bounds, clip, fboLayer, alpha);

    // Bail out if we won't draw in this snapshot
    if (mSnapshot->isIgnored()) {
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
    layer->setColorFilter(mDrawModifiers.mColorFilter);
    layer->setBlend(true);
    layer->setDirty(false);

    // Save the layer in the snapshot
    mSnapshot->flags |= Snapshot::kFlagIsLayer;
    mSnapshot->layer = layer;

    startMark("SaveLayer");
    if (fboLayer) {
        return createFboLayer(layer, bounds, clip, previousFbo);
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
                        0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
                layer->setEmpty(false);
            }

            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bounds.left,
                    mSnapshot->height - bounds.bottom, bounds.getWidth(), bounds.getHeight());

            // Enqueue the buffer coordinates to clear the corresponding region later
            mLayers.push(new Rect(bounds));
        }
    }

    return true;
}

bool OpenGLRenderer::createFboLayer(Layer* layer, Rect& bounds, Rect& clip, GLuint previousFbo) {
    layer->clipRect.set(clip);
    layer->setFbo(mCaches.fboCache.get());

    mSnapshot->region = &mSnapshot->layer->region;
    mSnapshot->flags |= Snapshot::kFlagFboTarget | Snapshot::kFlagIsFboLayer |
            Snapshot::kFlagDirtyOrtho;
    mSnapshot->fbo = layer->getFbo();
    mSnapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
    mSnapshot->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    mSnapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
    mSnapshot->height = bounds.getHeight();
    mSnapshot->orthoMatrix.load(mOrthoMatrix);

    endTiling();
    debugOverdraw(false, false);
    // Bind texture to FBO
    glBindFramebuffer(GL_FRAMEBUFFER, layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->allocateTexture();
        layer->setEmpty(false);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

    startTiling(mSnapshot, true);

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

    Layer* layer = current->layer;
    const Rect& rect = layer->layer;
    const bool fboLayer = current->flags & Snapshot::kFlagIsFboLayer;

    bool clipRequired = false;
    quickRejectNoScissor(rect, &clipRequired); // safely ignore return, should never be rejected
    mCaches.setScissorEnabled(mScissorOptimizationDisabled || clipRequired);

    if (fboLayer) {
        endTiling();

        // Detach the texture from the FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);

        layer->removeFbo(false);

        // Unbind current FBO and restore previous one
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);
        debugOverdraw(true, false);

        startTiling(previous);
    }

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

#ifdef QCOM_HARDWARE
        save(0);
        // the layer contains screen buffer content that shouldn't be alpha modulated
        // (and any necessary alpha modulation was handled drawing into the layer)
        mSnapshot->alpha = 1.0f;
#endif
        composeLayerRect(layer, rect, true);
#ifdef QCOM_HARDWARE
        restore();
#endif
    }

    dirtyClip();

    // Failing to add the layer to the cache should happen only if the layer is too large
    if (!mCaches.layerCache.put(layer)) {
        LAYER_LOGD("Deleting layer");
        Caches::getInstance().resourceCache.decrementRefcount(layer);
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
    if (currentTransform().isPureTranslate() &&
            layer->getWidth() == (uint32_t) rect.getWidth() &&
            layer->getHeight() == (uint32_t) rect.getHeight()) {
        const float x = (int) floorf(rect.left + currentTransform().getTranslateX() + 0.5f);
        const float y = (int) floorf(rect.top + currentTransform().getTranslateY() + 0.5f);

        layer->setFilter(GL_NEAREST);
        setupDrawModelView(x, y, x + rect.getWidth(), y + rect.getHeight(), true);
    } else {
        layer->setFilter(GL_LINEAR);
        setupDrawModelView(rect.left, rect.top, rect.right, rect.bottom);
    }
    setupDrawTextureTransformUniforms(layer->getTexTransform());
    setupDrawMesh(&mMeshVertices[0].position[0], &mMeshVertices[0].texture[0]);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect, bool swap) {
    if (!layer->isTextureLayer()) {
        const Rect& texCoords = layer->texCoords;
        resetDrawTextureTexCoords(texCoords.left, texCoords.top,
                texCoords.right, texCoords.bottom);

        float x = rect.left;
        float y = rect.top;
        bool simpleTransform = currentTransform().isPureTranslate() &&
                layer->getWidth() == (uint32_t) rect.getWidth() &&
                layer->getHeight() == (uint32_t) rect.getHeight();

        if (simpleTransform) {
            // When we're swapping, the layer is already in screen coordinates
            if (!swap) {
                x = (int) floorf(rect.left + currentTransform().getTranslateX() + 0.5f);
                y = (int) floorf(rect.top + currentTransform().getTranslateY() + 0.5f);
            }

            layer->setFilter(GL_NEAREST, true);
        } else {
            layer->setFilter(GL_LINEAR, true);
        }

        float alpha = getLayerAlpha(layer);
        bool blend = layer->isBlend() || alpha < 1.0f;
        drawTextureMesh(x, y, x + rect.getWidth(), y + rect.getHeight(),
                layer->getTexture(), alpha, layer->getMode(), blend,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, swap, swap || simpleTransform);

        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    } else {
        resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);
        drawTextureLayer(layer, rect);
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    }
}

/**
 * Issues the command X, and if we're composing a save layer to the fbo or drawing a newly updated
 * hardware layer with overdraw debug on, draws again to the stencil only, so that these draw
 * operations are correctly counted twice for overdraw. NOTE: assumes composeLayerRegion only used
 * by saveLayer's restore
 */
#define DRAW_DOUBLE_STENCIL_IF(COND, DRAW_COMMAND) {                             \
        DRAW_COMMAND;                                                            \
        if (CC_UNLIKELY(mCaches.debugOverdraw && getTargetFbo() == 0 && COND)) { \
            glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);                 \
            DRAW_COMMAND;                                                        \
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);                     \
        }                                                                        \
    }

#define DRAW_DOUBLE_STENCIL(DRAW_COMMAND) DRAW_DOUBLE_STENCIL_IF(true, DRAW_COMMAND)

void OpenGLRenderer::composeLayerRegion(Layer* layer, const Rect& rect) {
    if (layer->region.isRect()) {
        layer->setRegionAsRect();

        DRAW_DOUBLE_STENCIL(composeLayerRect(layer, layer->regionRect));

        layer->region.clear();
        return;
    }

    if (CC_LIKELY(!layer->region.isEmpty())) {
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
        setupDrawColorFilter();
        setupDrawBlending(layer->isBlend() || alpha < 1.0f, layer->getMode(), false);
        setupDrawProgram();
        setupDrawDirtyRegionsDisabled();
        setupDrawPureColorUniforms();
        setupDrawColorFilterUniforms();
        setupDrawTexture(layer->getTexture());
        if (currentTransform().isPureTranslate()) {
            const float x = (int) floorf(rect.left + currentTransform().getTranslateX() + 0.5f);
            const float y = (int) floorf(rect.top + currentTransform().getTranslateY() + 0.5f);

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

            if (numQuads >= gMaxNumberOfQuads) {
                DRAW_DOUBLE_STENCIL(glDrawElements(GL_TRIANGLES, numQuads * 6,
                                GL_UNSIGNED_SHORT, NULL));
                numQuads = 0;
                mesh = mCaches.getRegionMesh();
            }
        }

        if (numQuads > 0) {
            DRAW_DOUBLE_STENCIL(glDrawElements(GL_TRIANGLES, numQuads * 6,
                            GL_UNSIGNED_SHORT, NULL));
        }

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

void OpenGLRenderer::drawRegionRects(const SkRegion& region, int color,
        SkXfermode::Mode mode, bool dirty) {
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

    drawColorRects(rects.array(), rects.size(), color, mode, true, dirty, false);
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

void OpenGLRenderer::drawIndexedQuads(Vertex* mesh, GLsizei quadsCount) {
    GLsizei elementsCount = quadsCount * 6;
    while (elementsCount > 0) {
        GLsizei drawCount = min(elementsCount, (GLsizei) gMaxNumberOfQuads * 6);

        setupDrawIndexedVertices(&mesh[0].position[0]);
        glDrawElements(GL_TRIANGLES, drawCount, GL_UNSIGNED_SHORT, NULL);

        elementsCount -= drawCount;
        // Though there are 4 vertices in a quad, we use 6 indices per
        // quad to draw with GL_TRIANGLES
        mesh += (drawCount / 6) * 4;
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

        Vertex mesh[count * 4];
        Vertex* vertex = mesh;

        for (uint32_t i = 0; i < count; i++) {
            Rect* bounds = mLayers.itemAt(i);

            Vertex::set(vertex++, bounds->left, bounds->top);
            Vertex::set(vertex++, bounds->right, bounds->top);
            Vertex::set(vertex++, bounds->left, bounds->bottom);
            Vertex::set(vertex++, bounds->right, bounds->bottom);

            delete bounds;
        }
        // We must clear the list of dirty rects before we
        // call setupDraw() to prevent stencil setup to do
        // the same thing again
        mLayers.clear();

        setupDraw(false);
        setupDrawColor(0.0f, 0.0f, 0.0f, 1.0f);
        setupDrawBlending(true, SkXfermode::kClear_Mode);
        setupDrawProgram();
        setupDrawPureColorUniforms();
        setupDrawModelViewTranslate(0.0f, 0.0f, 0.0f, 0.0f, true);

        drawIndexedQuads(&mesh[0], count);

        if (scissorChanged) mCaches.enableScissor();
    } else {
        for (uint32_t i = 0; i < count; i++) {
            delete mLayers.itemAt(i);
        }
        mLayers.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// State Deferral
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::storeDisplayState(DeferredDisplayState& state, int stateDeferFlags) {
    const Rect& currentClip = *(mSnapshot->clipRect);
    const mat4& currentMatrix = *(mSnapshot->transform);

    if (stateDeferFlags & kStateDeferFlag_Draw) {
        // state has bounds initialized in local coordinates
        if (!state.mBounds.isEmpty()) {
            currentMatrix.mapRect(state.mBounds);
            Rect clippedBounds(state.mBounds);
            // NOTE: if we ever want to use this clipping info to drive whether the scissor
            // is used, it should more closely duplicate the quickReject logic (in how it uses
            // snapToPixelBoundaries)

            if(!clippedBounds.intersect(currentClip)) {
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
    state.mMatrix.load(currentMatrix);
    state.mDrawModifiers = mDrawModifiers;
    state.mAlpha = mSnapshot->alpha;
    return false;
}

void OpenGLRenderer::restoreDisplayState(const DeferredDisplayState& state, bool skipClipRestore) {
    currentTransform().load(state.mMatrix);
    mDrawModifiers = state.mDrawModifiers;
    mSnapshot->alpha = state.mAlpha;

    if (state.mClipValid && !skipClipRestore) {
        mSnapshot->setClip(state.mClip.left, state.mClip.top,
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
    if (clipRect != NULL) {
        mSnapshot->setClip(clipRect->left, clipRect->top, clipRect->right, clipRect->bottom);
    } else {
        mSnapshot->setClip(0, 0, mWidth, mHeight);
    }
    dirtyClip();
    mCaches.setScissorEnabled(clipRect != NULL || mScissorOptimizationDisabled);
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    currentTransform().translate(dx, dy);
}

void OpenGLRenderer::rotate(float degrees) {
    currentTransform().rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void OpenGLRenderer::scale(float sx, float sy) {
    currentTransform().scale(sx, sy, 1.0f);
}

void OpenGLRenderer::skew(float sx, float sy) {
    currentTransform().skew(sx, sy);
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    if (matrix) {
        currentTransform().load(*matrix);
    } else {
        currentTransform().loadIdentity();
    }
}

bool OpenGLRenderer::hasRectToRectTransform() {
    return CC_LIKELY(currentTransform().rectToRect());
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    currentTransform().copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    SkMatrix transform;
    currentTransform().copyTo(transform);
    transform.preConcat(*matrix);
    currentTransform().load(transform);
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

void OpenGLRenderer::ensureStencilBuffer() {
    // Thanks to the mismatch between EGL and OpenGL ES FBO we
    // cannot attach a stencil buffer to fbo0 dynamically. Let's
    // just hope we have one when hasLayer() returns false.
    if (hasLayer()) {
        attachStencilBufferToLayer(mSnapshot->layer);
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

void OpenGLRenderer::setStencilFromClip() {
    if (!mCaches.debugOverdraw) {
        if (!mSnapshot->clipRegion->isEmpty()) {
            // NOTE: The order here is important, we must set dirtyClip to false
            //       before any draw call to avoid calling back into this method
            mDirtyClip = false;

            ensureStencilBuffer();

            mCaches.stencil.enableWrite();

            // Clear the stencil but first make sure we restrict drawing
            // to the region's bounds
            bool resetScissor = mCaches.enableScissor();
            if (resetScissor) {
                // The scissor was not set so we now need to update it
                setScissorFromClip();
            }
            mCaches.stencil.clear();
            if (resetScissor) mCaches.disableScissor();

            // NOTE: We could use the region contour path to generate a smaller mesh
            //       Since we are using the stencil we could use the red book path
            //       drawing technique. It might increase bandwidth usage though.

            // The last parameter is important: we are not drawing in the color buffer
            // so we don't want to dirty the current layer, if any
            drawRegionRects(*mSnapshot->clipRegion, 0xff000000, SkXfermode::kSrc_Mode, false);

            mCaches.stencil.enableTest();

            // Draw the region used to generate the stencil if the appropriate debug
            // mode is enabled
            if (mCaches.debugStencilClip == Caches::kStencilShowRegion) {
                drawRegionRects(*mSnapshot->clipRegion, 0x7f0000ff, SkXfermode::kSrcOver_Mode);
            }
        } else {
            mCaches.stencil.disable();
        }
    }
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->getLocalClip();
}

bool OpenGLRenderer::quickRejectNoScissor(float left, float top, float right, float bottom,
        bool snapOut, bool* clipRequired) {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform().mapRect(r);
    r.snapGeometryToPixelBoundaries(snapOut);

    Rect clipRect(*mSnapshot->clipRect);
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    if (clipRequired) *clipRequired = !clipRect.contains(r);
    return false;
}

bool OpenGLRenderer::quickRejectPreStroke(float left, float top, float right, float bottom,
        SkPaint* paint) {
    // AA geometry will likely have a ramp around it (not accounted for in local bounds). Snap out
    // the final mapped rect to ensure correct clipping behavior for the ramp.
    bool snapOut = paint->isAntiAlias();

    if (paint->getStyle() != SkPaint::kFill_Style) {
        float outset = paint->getStrokeWidth() * 0.5f;
        return quickReject(left - outset, top - outset, right + outset, bottom + outset, snapOut);
    } else {
        return quickReject(left, top, right, bottom, snapOut);
    }
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom, bool snapOut) {
    bool clipRequired = false;
    if (quickRejectNoScissor(left, top, right, bottom, snapOut, &clipRequired)) {
        return true;
    }

    if (!isDeferred()) {
        mCaches.setScissorEnabled(mScissorOptimizationDisabled || clipRequired);
    }
    return false;
}

void OpenGLRenderer::debugClip() {
#if DEBUG_CLIP_REGIONS
    if (!isDeferred() && !mSnapshot->clipRegion->isEmpty()) {
        drawRegionRects(*mSnapshot->clipRegion, 0x7f00ff00, SkXfermode::kSrcOver_Mode);
    }
#endif
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    if (CC_LIKELY(currentTransform().rectToRect())) {
        bool clipped = mSnapshot->clip(left, top, right, bottom, op);
        if (clipped) {
            dirtyClip();
        }
        return !mSnapshot->clipRect->isEmpty();
    }

    SkPath path;
    path.addRect(left, top, right, bottom);

    return OpenGLRenderer::clipPath(&path, op);
}

bool OpenGLRenderer::clipPath(SkPath* path, SkRegion::Op op) {
    SkMatrix transform;
    currentTransform().copyTo(transform);

    SkPath transformed;
    path->transform(transform, &transformed);

    SkRegion clip;
    if (!mSnapshot->previous->clipRegion->isEmpty()) {
        clip.setRegion(*mSnapshot->previous->clipRegion);
    } else {
        if (mSnapshot->previous == mFirstSnapshot) {
            clip.setRect(0, 0, mWidth, mHeight);
        } else {
            Rect* bounds = mSnapshot->previous->clipRect;
            clip.setRect(bounds->left, bounds->top, bounds->right, bounds->bottom);
        }
    }

    SkRegion region;
    region.setPath(transformed, clip);

    bool clipped = mSnapshot->clipRegionTransformed(region, op);
    if (clipped) {
        dirtyClip();
    }
    return !mSnapshot->clipRect->isEmpty();
}

bool OpenGLRenderer::clipRegion(SkRegion* region, SkRegion::Op op) {
    bool clipped = mSnapshot->clipRegionTransformed(*region, op);
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
    // Make sure setScissor & setStencil happen at the beginning of
    // this method
    if (mDirtyClip) {
        if (mCaches.scissorEnabled) {
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
            mCaches.stencil.isTestEnabled();

    mDescription.emulateStencil = mCountOverdraw;
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
    mCaches.disableTexCoordsVertexArray();
}

void OpenGLRenderer::setupDrawAA() {
    mDescription.isAA = true;
}

void OpenGLRenderer::setupDrawColor(int color, int alpha) {
    mColorA = alpha / 255.0f;
    mColorR = mColorA * ((color >> 16) & 0xFF) / 255.0f;
    mColorG = mColorA * ((color >>  8) & 0xFF) / 255.0f;
    mColorB = mColorA * ((color      ) & 0xFF) / 255.0f;
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawAlpha8Color(int color, int alpha) {
    mColorA = alpha / 255.0f;
    mColorR = mColorA * ((color >> 16) & 0xFF) / 255.0f;
    mColorG = mColorA * ((color >>  8) & 0xFF) / 255.0f;
    mColorB = mColorA * ((color      ) & 0xFF) / 255.0f;
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
    if (mDrawModifiers.mShader) {
        mDrawModifiers.mShader->describe(mDescription, mExtensions);
    }
}

void OpenGLRenderer::setupDrawColorFilter() {
    if (mDrawModifiers.mColorFilter) {
        mDrawModifiers.mColorFilter->describe(mDescription, mExtensions);
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
    bool blend = (mColorSet && mColorA < 1.0f) ||
            (mDrawModifiers.mShader && mDrawModifiers.mShader->blend());
    chooseBlending(blend, mode, mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawBlending(bool blend, SkXfermode::Mode mode, bool swapSrcDst) {
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    blend |= (mColorSet && mColorA < 1.0f) ||
            (mDrawModifiers.mShader && mDrawModifiers.mShader->blend()) ||
            (mDrawModifiers.mColorFilter && mDrawModifiers.mColorFilter->blend());
    chooseBlending(blend, mode, mDescription, swapSrcDst);
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
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, currentTransform());
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom, currentTransform());
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mat4::identity());
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawModelViewIdentity(bool offset) {
    mCaches.currentProgram->set(mOrthoMatrix, mat4::identity(), currentTransform(), offset);
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
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, currentTransform());
        if (mTrackDirtyRegions && dirty) {
            dirtyLayer(left, top, right, bottom, currentTransform());
        }
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mat4::identity());
        if (mTrackDirtyRegions && dirty) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawColorUniforms() {
    if ((mColorSet && !mDrawModifiers.mShader) || (mDrawModifiers.mShader && mSetShaderColor)) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawPureColorUniforms() {
    if (mSetShaderColor) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawShaderUniforms(bool ignoreTransform) {
    if (mDrawModifiers.mShader) {
        if (ignoreTransform) {
            mModelView.loadInverse(currentTransform());
        }
        mDrawModifiers.mShader->setupProgram(mCaches.currentProgram,
                mModelView, *mSnapshot, &mTextureUnit);
    }
}

void OpenGLRenderer::setupDrawShaderIdentityUniforms() {
    if (mDrawModifiers.mShader) {
        mDrawModifiers.mShader->setupProgram(mCaches.currentProgram,
                mat4::identity(), *mSnapshot, &mTextureUnit);
    }
}

void OpenGLRenderer::setupDrawColorFilterUniforms() {
    if (mDrawModifiers.mColorFilter) {
        mDrawModifiers.mColorFilter->setupProgram(mCaches.currentProgram);
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
    if (texture) bindTexture(texture);
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
    if (!vertices || vbo) {
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

void OpenGLRenderer::setupDrawMesh(GLvoid* vertices, GLvoid* texCoords, GLvoid* colors) {
    bool force = mCaches.unbindMeshBuffer();
    GLsizei stride = sizeof(ColorTextureVertex);

    mCaches.bindPositionVertexPointer(force, vertices, stride);
    if (mCaches.currentProgram->texCoords >= 0) {
        mCaches.bindTexCoordsVertexPointer(force, texCoords, stride);
    }
    int slot = mCaches.currentProgram->getAttrib("colors");
    if (slot >= 0) {
        glEnableVertexAttribArray(slot);
        glVertexAttribPointer(slot, 4, GL_FLOAT, GL_FALSE, stride, colors);
    }

    mCaches.unbindIndicesBuffer();
}

void OpenGLRenderer::setupDrawMeshIndices(GLvoid* vertices, GLvoid* texCoords, GLuint vbo) {
    bool force = false;
    // If vbo is != 0 we want to treat the vertices parameter as an offset inside
    // a VBO. However, if vertices is set to NULL and vbo == 0 then we want to
    // use the default VBO found in Caches
    if (!vertices || vbo) {
        force = mCaches.bindMeshBuffer(vbo == 0 ? mCaches.meshBuffer : vbo);
    } else {
        force = mCaches.unbindMeshBuffer();
    }
    mCaches.bindIndicesBuffer();

    mCaches.bindPositionVertexPointer(force, vertices);
    if (mCaches.currentProgram->texCoords >= 0) {
        mCaches.bindTexCoordsVertexPointer(force, texCoords);
    }
}

void OpenGLRenderer::setupDrawIndexedVertices(GLvoid* vertices) {
    bool force = mCaches.unbindMeshBuffer();
    mCaches.bindIndicesBuffer();
    mCaches.bindPositionVertexPointer(force, vertices, gVertexStride);
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

status_t OpenGLRenderer::drawDisplayList(DisplayList* displayList, Rect& dirty,
        int32_t replayFlags) {
    status_t status;
    // All the usual checks and setup operations (quickReject, setupDraw, etc.)
    // will be performed by the display list itself
    if (displayList && displayList->isRenderable()) {
        if (CC_UNLIKELY(mCaches.drawDeferDisabled)) {
            status = startFrame();
            ReplayStateStruct replayStruct(*this, dirty, replayFlags);
            displayList->replay(replayStruct, 0);
            return status | replayStruct.mDrawGlStatus;
        }

        bool avoidOverdraw = !mCaches.debugOverdraw && !mCountOverdraw; // shh, don't tell devs!
        DeferredDisplayList deferredList(*(mSnapshot->clipRect), avoidOverdraw);
        DeferStateStruct deferStruct(deferredList, *this, replayFlags);
        displayList->defer(deferStruct, 0);

        flushLayers();
        status = startFrame();

        return status | deferredList.flush(*this, dirty);
    }

    return DrawGlInfo::kStatusDone;
}

void OpenGLRenderer::outputDisplayList(DisplayList* displayList) {
    if (displayList) {
        displayList->output(1);
    }
}

void OpenGLRenderer::drawAlphaBitmap(Texture* texture, float left, float top, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    int color = paint != NULL ? paint->getColor() : 0;

    float x = left;
    float y = top;

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    bool ignoreTransform = false;
    if (currentTransform().isPureTranslate()) {
        x = (int) floorf(left + currentTransform().getTranslateX() + 0.5f);
        y = (int) floorf(top + currentTransform().getTranslateY() + 0.5f);
        ignoreTransform = true;

        texture->setFilter(GL_NEAREST, true);
    } else {
        texture->setFilter(FILTER(paint), true);
    }

    // No need to check for a UV mapper on the texture object, only ARGB_8888
    // bitmaps get packed in the atlas
    drawAlpha8TextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
            paint != NULL, color, alpha, mode, (GLvoid*) NULL, (GLvoid*) gMeshTextureOffset,
            GL_TRIANGLE_STRIP, gMeshCount, ignoreTransform);
}

/**
 * Important note: this method is intended to draw batches of bitmaps and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
status_t OpenGLRenderer::drawBitmaps(SkBitmap* bitmap, AssetAtlas::Entry* entry, int bitmapCount,
        TextureVertex* vertices, bool pureTranslate, const Rect& bounds, SkPaint* paint) {
    mCaches.activeTexture(0);
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;

    const AutoTexture autoCleanup(texture);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(pureTranslate ? GL_NEAREST : FILTER(paint), true);

    const float x = (int) floorf(bounds.left + 0.5f);
    const float y = (int) floorf(bounds.top + 0.5f);
    if (CC_UNLIKELY(bitmap->getConfig() == SkBitmap::kA8_Config)) {
        int color = paint != NULL ? paint->getColor() : 0;
        drawAlpha8TextureMesh(x, y, x + bounds.getWidth(), y + bounds.getHeight(),
                texture->id, paint != NULL, color, alpha, mode,
                &vertices[0].position[0], &vertices[0].texture[0],
                GL_TRIANGLES, bitmapCount * 6, true, true, false);
    } else {
        drawTextureMesh(x, y, x + bounds.getWidth(), y + bounds.getHeight(),
                texture->id, alpha / 255.0f, mode, texture->blend,
                &vertices[0].position[0], &vertices[0].texture[0],
                GL_TRIANGLES, bitmapCount * 6, false, true, 0, true, false);
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.activeTexture(0);
    Texture* texture = getTexture(bitmap);
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
    Texture* texture = getTexture(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    // This could be done in a cheaper way, all we need is pass the matrix
    // to the vertex shader. The save/restore is a bit overkill.
    save(SkCanvas::kMatrix_SaveFlag);
    concatMatrix(matrix);
    if (CC_UNLIKELY(bitmap->getConfig() == SkBitmap::kA8_Config)) {
        drawAlphaBitmap(texture, 0.0f, 0.0f, paint);
    } else {
        drawTextureRect(0.0f, 0.0f, bitmap->width(), bitmap->height(), texture, paint);
    }
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

    if (CC_UNLIKELY(bitmap->getConfig() == SkBitmap::kA8_Config)) {
        drawAlphaBitmap(texture, left, top, paint);
    } else {
        drawTextureRect(left, top, right, bottom, texture, paint);
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    if (!vertices || mSnapshot->isIgnored()) {
        return DrawGlInfo::kStatusDone;
    }

    // TODO: use quickReject on bounds from vertices
    mCaches.enableScissor();

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

    const uint32_t count = meshWidth * meshHeight * 6;

    ColorTextureVertex mesh[count];
    ColorTextureVertex* vertex = mesh;

    bool cleanupColors = false;
    if (!colors) {
        uint32_t colorsCount = (meshWidth + 1) * (meshHeight + 1);
        colors = new int[colorsCount];
        memset(colors, 0xff, colorsCount * sizeof(int));
        cleanupColors = true;
    }

    mCaches.activeTexture(0);
    Texture* texture = mCaches.assetAtlas.getEntryTexture(bitmap);
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

    if (quickReject(left, top, right, bottom)) {
        if (cleanupColors) delete[] colors;
        return DrawGlInfo::kStatusDone;
    }

    if (!texture) {
        texture = mCaches.textureCache.get(bitmap);
        if (!texture) {
            if (cleanupColors) delete[] colors;
            return DrawGlInfo::kStatusDone;
        }
    }
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(FILTER(paint), true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    float a = alpha / 255.0f;

    if (hasLayer()) {
        dirtyLayer(left, top, right, bottom, currentTransform());
    }

    setupDraw();
    setupDrawWithTextureAndColor();
    setupDrawColor(a, a, a, a);
    setupDrawColorFilter();
    setupDrawBlending(true, mode, false);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawModelView(0.0f, 0.0f, 1.0f, 1.0f, false);
    setupDrawTexture(texture->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawMesh(&mesh[0].position[0], &mesh[0].texture[0], &mesh[0].color[0]);

    glDrawArrays(GL_TRIANGLES, 0, count);

    int slot = mCaches.currentProgram->getAttrib("colors");
    if (slot >= 0) {
        glDisableVertexAttribArray(slot);
    }

    if (cleanupColors) delete[] colors;

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
    Texture* texture = getTexture(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    float u1 = fmax(0.0f, srcLeft / width);
    float v1 = fmax(0.0f, srcTop / height);
    float u2 = fmin(1.0f, srcRight / width);
    float v2 = fmin(1.0f, srcBottom / height);

    getMapper(texture).map(u1, v1, u2, v2);

    mCaches.unbindMeshBuffer();
    resetDrawTextureTexCoords(u1, v1, u2, v2);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);

    float scaleX = (dstRight - dstLeft) / (srcRight - srcLeft);
    float scaleY = (dstBottom - dstTop) / (srcBottom - srcTop);

    bool scaled = scaleX != 1.0f || scaleY != 1.0f;
    // Apply a scale transform on the canvas only when a shader is in use
    // Skia handles the ratio between the dst and src rects as a scale factor
    // when a shader is set
    bool useScaleTransform = mDrawModifiers.mShader && scaled;
    bool ignoreTransform = false;

    if (CC_LIKELY(currentTransform().isPureTranslate() && !useScaleTransform)) {
        float x = (int) floorf(dstLeft + currentTransform().getTranslateX() + 0.5f);
        float y = (int) floorf(dstTop + currentTransform().getTranslateY() + 0.5f);

        dstRight = x + (dstRight - dstLeft);
        dstBottom = y + (dstBottom - dstTop);

        dstLeft = x;
        dstTop = y;

        texture->setFilter(scaled ? FILTER(paint) : GL_NEAREST, true);
        ignoreTransform = true;
    } else {
        texture->setFilter(FILTER(paint), true);
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

    if (CC_UNLIKELY(bitmap->getConfig() == SkBitmap::kA8_Config)) {
        int color = paint ? paint->getColor() : 0;
        drawAlpha8TextureMesh(dstLeft, dstTop, dstRight, dstBottom,
                texture->id, paint != NULL, color, alpha, mode,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, ignoreTransform);
    } else {
        drawTextureMesh(dstLeft, dstTop, dstRight, dstBottom,
                texture->id, alpha / 255.0f, mode, texture->blend,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, false, ignoreTransform);
    }

    if (CC_UNLIKELY(useScaleTransform)) {
        restore();
    }

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawPatch(SkBitmap* bitmap, Res_png_9patch* patch,
        float left, float top, float right, float bottom, SkPaint* paint) {
    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    AssetAtlas::Entry* entry = mCaches.assetAtlas.getEntry(bitmap);
    const Patch* mesh = mCaches.patchCache.get(entry, bitmap->width(), bitmap->height(),
            right - left, bottom - top, patch);

    return drawPatch(bitmap, mesh, entry, left, top, right, bottom, paint);
}

status_t OpenGLRenderer::drawPatch(SkBitmap* bitmap, const Patch* mesh, AssetAtlas::Entry* entry,
        float left, float top, float right, float bottom, SkPaint* paint) {
    if (quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    if (CC_LIKELY(mesh && mesh->verticesCount > 0)) {
        mCaches.activeTexture(0);
        Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
        if (!texture) return DrawGlInfo::kStatusDone;
        const AutoTexture autoCleanup(texture);

        texture->setWrap(GL_CLAMP_TO_EDGE, true);
        texture->setFilter(GL_LINEAR, true);

        int alpha;
        SkXfermode::Mode mode;
        getAlphaAndMode(paint, &alpha, &mode);

        const bool pureTranslate = currentTransform().isPureTranslate();
        // Mark the current layer dirty where we are going to draw the patch
        if (hasLayer() && mesh->hasEmptyQuads) {
            const float offsetX = left + currentTransform().getTranslateX();
            const float offsetY = top + currentTransform().getTranslateY();
            const size_t count = mesh->quads.size();
            for (size_t i = 0; i < count; i++) {
                const Rect& bounds = mesh->quads.itemAt(i);
                if (CC_LIKELY(pureTranslate)) {
                    const float x = (int) floorf(bounds.left + offsetX + 0.5f);
                    const float y = (int) floorf(bounds.top + offsetY + 0.5f);
                    dirtyLayer(x, y, x + bounds.getWidth(), y + bounds.getHeight());
                } else {
                    dirtyLayer(left + bounds.left, top + bounds.top,
                            left + bounds.right, top + bounds.bottom, currentTransform());
                }
            }
        }

        if (CC_LIKELY(pureTranslate)) {
            const float x = (int) floorf(left + currentTransform().getTranslateX() + 0.5f);
            const float y = (int) floorf(top + currentTransform().getTranslateY() + 0.5f);

            right = x + right - left;
            bottom = y + bottom - top;
            drawIndexedTextureMesh(x, y, right, bottom, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) mesh->offset, (GLvoid*) mesh->textureOffset,
                    GL_TRIANGLES, mesh->indexCount, false, true,
                    mCaches.patchCache.getMeshBuffer(), true, !mesh->hasEmptyQuads);
        } else {
            drawIndexedTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) mesh->offset, (GLvoid*) mesh->textureOffset,
                    GL_TRIANGLES, mesh->indexCount, false, false,
                    mCaches.patchCache.getMeshBuffer(), true, !mesh->hasEmptyQuads);
        }
    }

    return DrawGlInfo::kStatusDrew;
}

/**
 * Important note: this method is intended to draw batches of 9-patch objects and
 * will not set the scissor enable or dirty the current layer, if any.
 * The caller is responsible for properly dirtying the current layer.
 */
status_t OpenGLRenderer::drawPatches(SkBitmap* bitmap, AssetAtlas::Entry* entry,
        TextureVertex* vertices, uint32_t indexCount, SkPaint* paint) {
    mCaches.activeTexture(0);
    Texture* texture = entry ? entry->texture : mCaches.textureCache.get(bitmap);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, true);
    texture->setFilter(GL_LINEAR, true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    drawIndexedTextureMesh(0.0f, 0.0f, 1.0f, 1.0f, texture->id, alpha / 255.0f,
            mode, texture->blend, &vertices[0].position[0], &vertices[0].texture[0],
            GL_TRIANGLES, indexCount, false, true, 0, true, false);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawVertexBuffer(const VertexBuffer& vertexBuffer, SkPaint* paint,
        bool useOffset) {
    if (!vertexBuffer.getVertexCount()) {
        // no vertices to draw
        return DrawGlInfo::kStatusDone;
    }

    int color = paint->getColor();
    SkXfermode::Mode mode = getXfermode(paint->getXfermode());
    bool isAA = paint->isAntiAlias();

    setupDraw();
    setupDrawNoTexture();
    if (isAA) setupDrawAA();
    setupDrawColor(color, ((color >> 24) & 0xFF) * mSnapshot->alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(isAA, mode);
    setupDrawProgram();
    setupDrawModelViewIdentity(useOffset);
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

    glDrawArrays(GL_TRIANGLE_STRIP, 0, vertexBuffer.getVertexCount());

    if (isAA) {
        glDisableVertexAttribArray(alphaSlot);
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
status_t OpenGLRenderer::drawConvexPath(const SkPath& path, SkPaint* paint) {
    VertexBuffer vertexBuffer;
    // TODO: try clipping large paths to viewport
    PathTessellator::tessellatePath(path, paint, mSnapshot->transform, vertexBuffer);

    if (hasLayer()) {
        SkRect bounds = path.getBounds();
        PathTessellator::expandBoundsForStroke(bounds, paint, false);
        dirtyLayer(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, currentTransform());
    }

    return drawVertexBuffer(vertexBuffer, paint);
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
status_t OpenGLRenderer::drawLines(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored() || count < 4) return DrawGlInfo::kStatusDone;

    count &= ~0x3; // round down to nearest four

    VertexBuffer buffer;
    SkRect bounds;
    PathTessellator::tessellateLines(points, count, paint, mSnapshot->transform, bounds, buffer);

    if (quickReject(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom)) {
        return DrawGlInfo::kStatusDone;
    }

    dirtyLayer(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, currentTransform());

    bool useOffset = !paint->isAntiAlias();
    return drawVertexBuffer(buffer, paint, useOffset);
}

status_t OpenGLRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored() || count < 2) return DrawGlInfo::kStatusDone;

    count &= ~0x1; // round down to nearest two

    VertexBuffer buffer;
    SkRect bounds;
    PathTessellator::tessellatePoints(points, count, paint, mSnapshot->transform, bounds, buffer);

    if (quickReject(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom)) {
        return DrawGlInfo::kStatusDone;
    }

    dirtyLayer(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, currentTransform());

    bool useOffset = !paint->isAntiAlias();
    return drawVertexBuffer(buffer, paint, useOffset);
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
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p) ||
            (p->getAlpha() == 0 && getXfermode(p->getXfermode()) != SkXfermode::kClear_Mode)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getRoundRect(
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
    return drawConvexPath(path, p);
}

status_t OpenGLRenderer::drawCircle(float x, float y, float radius, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(x - radius, y - radius,
            x + radius, y + radius, p) ||
            (p->getAlpha() == 0 && getXfermode(p->getXfermode()) != SkXfermode::kClear_Mode)) {
        return DrawGlInfo::kStatusDone;
    }
    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getCircle(radius, p);
        return drawShape(x - radius, y - radius, texture, p);
    }

    SkPath path;
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        path.addCircle(x, y, radius + p->getStrokeWidth() / 2);
    } else {
        path.addCircle(x, y, radius);
    }
    return drawConvexPath(path, p);
}

status_t OpenGLRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p) ||
            (p->getAlpha() == 0 && getXfermode(p->getXfermode()) != SkXfermode::kClear_Mode)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getPathEffect() != 0) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getOval(right - left, bottom - top, p);
        return drawShape(left, top, texture, p);
    }

    SkPath path;
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
        rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
    }
    path.addOval(rect);
    return drawConvexPath(path, p);
}

status_t OpenGLRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p) ||
            (p->getAlpha() == 0 && getXfermode(p->getXfermode()) != SkXfermode::kClear_Mode)) {
        return DrawGlInfo::kStatusDone;
    }

    if (fabs(sweepAngle) >= 360.0f) {
        return drawOval(left, top, right, bottom, p);
    }

    // TODO: support fills (accounting for concavity if useCenter && sweepAngle > 180)
    if (p->getStyle() != SkPaint::kStroke_Style || p->getPathEffect() != 0 || useCenter) {
        mCaches.activeTexture(0);
        const PathTexture* texture = mCaches.pathCache.getArc(right - left, bottom - top,
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
    return drawConvexPath(path, p);
}

// See SkPaintDefaults.h
#define SkPaintDefaults_MiterLimit SkIntToScalar(4)

status_t OpenGLRenderer::drawRect(float left, float top, float right, float bottom, SkPaint* p) {
    if (mSnapshot->isIgnored() || quickRejectPreStroke(left, top, right, bottom, p) ||
            (p->getAlpha() == 0 && getXfermode(p->getXfermode()) != SkXfermode::kClear_Mode)) {
        return DrawGlInfo::kStatusDone;
    }

    if (p->getStyle() != SkPaint::kFill_Style) {
        // only fill style is supported by drawConvexPath, since others have to handle joins
        if (p->getPathEffect() != 0 || p->getStrokeJoin() != SkPaint::kMiter_Join ||
                p->getStrokeMiter() != SkPaintDefaults_MiterLimit) {
            mCaches.activeTexture(0);
            const PathTexture* texture =
                    mCaches.pathCache.getRect(right - left, bottom - top, p);
            return drawShape(left, top, texture, p);
        }

        SkPath path;
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        if (p->getStyle() == SkPaint::kStrokeAndFill_Style) {
            rect.outset(p->getStrokeWidth() / 2, p->getStrokeWidth() / 2);
        }
        path.addRect(rect);
        return drawConvexPath(path, p);
    }

    if (p->isAntiAlias() && !currentTransform().isSimple()) {
        SkPath path;
        path.addRect(left, top, right, bottom);
        return drawConvexPath(path, p);
    } else {
        drawColorRect(left, top, right, bottom, p->getColor(), getXfermode(p->getXfermode()));
        return DrawGlInfo::kStatusDrew;
    }
}

void OpenGLRenderer::drawTextShadow(SkPaint* paint, const char* text, int bytesCount, int count,
        const float* positions, FontRenderer& fontRenderer, int alpha, SkXfermode::Mode mode,
        float x, float y) {
    mCaches.activeTexture(0);

    // NOTE: The drop shadow will not perform gamma correction
    //       if shader-based correction is enabled
    mCaches.dropShadowCache.setFontRenderer(fontRenderer);
    const ShadowTexture* shadow = mCaches.dropShadowCache.get(
            paint, text, bytesCount, count, mDrawModifiers.mShadowRadius, positions);
    // If the drop shadow exceeds the max texture size or couldn't be
    // allocated, skip drawing
    if (!shadow) return;
    const AutoTexture autoCleanup(shadow);

    const float sx = x - shadow->left + mDrawModifiers.mShadowDx;
    const float sy = y - shadow->top + mDrawModifiers.mShadowDy;

    const int shadowAlpha = ((mDrawModifiers.mShadowColor >> 24) & 0xFF) * mSnapshot->alpha;
    int shadowColor = mDrawModifiers.mShadowColor;
    if (mDrawModifiers.mShader) {
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

bool OpenGLRenderer::canSkipText(const SkPaint* paint) const {
    float alpha = (mDrawModifiers.mHasShadow ? 1.0f : paint->getAlpha()) * mSnapshot->alpha;
    return alpha == 0.0f && getXfermode(paint->getXfermode()) == SkXfermode::kSrcOver_Mode;
}

status_t OpenGLRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, SkPaint* paint) {
    if (text == NULL || count == 0 || mSnapshot->isIgnored() || canSkipText(paint)) {
        return DrawGlInfo::kStatusDone;
    }

    // NOTE: Skia does not support perspective transform on drawPosText yet
    if (!currentTransform().isSimple()) {
        return DrawGlInfo::kStatusDone;
    }

    mCaches.enableScissor();

    float x = 0.0f;
    float y = 0.0f;
    const bool pureTranslate = currentTransform().isPureTranslate();
    if (pureTranslate) {
        x = (int) floorf(x + currentTransform().getTranslateX() + 0.5f);
        y = (int) floorf(y + currentTransform().getTranslateY() + 0.5f);
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, mat4::identity());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (CC_UNLIKELY(mDrawModifiers.mHasShadow)) {
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer,
                alpha, mode, 0.0f, 0.0f);
    }

    // Pick the appropriate texture filtering
    bool linearFilter = currentTransform().changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }
    fontRenderer.setTextureFiltering(linearFilter);

    const Rect* clip = pureTranslate ? mSnapshot->clipRect : &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    TextSetupFunctor functor(this, x, y, pureTranslate, alpha, mode, paint);
    if (fontRenderer.renderPosText(paint, clip, text, 0, bytesCount, count, x, y,
            positions, hasActiveLayer ? &bounds : NULL, &functor)) {
        if (hasActiveLayer) {
            if (!pureTranslate) {
                currentTransform().mapRect(bounds);
            }
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    return DrawGlInfo::kStatusDrew;
}

mat4 OpenGLRenderer::findBestFontTransform(const mat4& transform) const {
    mat4 fontTransform;
    if (CC_LIKELY(transform.isPureTranslate())) {
        fontTransform = mat4::identity();
    } else {
        if (CC_UNLIKELY(transform.isPerspective())) {
            fontTransform = mat4::identity();
        } else {
            float sx, sy;
            currentTransform().decomposeScale(sx, sy);
            fontTransform.loadScale(sx, sy, 1.0f);
        }
    }
    return fontTransform;
}

status_t OpenGLRenderer::drawText(const char* text, int bytesCount, int count, float x, float y,
        const float* positions, SkPaint* paint, float totalAdvance, const Rect& bounds,
        DrawOpMode drawOpMode) {

    if (drawOpMode == kDrawOpMode_Immediate) {
        // The checks for corner-case ignorable text and quick rejection is only done for immediate
        // drawing as ops from DeferredDisplayList are already filtered for these
        if (text == NULL || count == 0 || mSnapshot->isIgnored() || canSkipText(paint) ||
                quickReject(bounds)) {
            return DrawGlInfo::kStatusDone;
        }
    }

    const float oldX = x;
    const float oldY = y;

    const mat4& transform = currentTransform();
    const bool pureTranslate = transform.isPureTranslate();

    if (CC_LIKELY(pureTranslate)) {
        x = (int) floorf(x + transform.getTranslateX() + 0.5f);
        y = (int) floorf(y + transform.getTranslateY() + 0.5f);
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);

    if (CC_UNLIKELY(mDrawModifiers.mHasShadow)) {
        fontRenderer.setFont(paint, mat4::identity());
        drawTextShadow(paint, text, bytesCount, count, positions, fontRenderer,
                alpha, mode, oldX, oldY);
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
    mat4 fontTransform = findBestFontTransform(transform);
    fontRenderer.setFont(paint, fontTransform);

    // Pick the appropriate texture filtering
    bool linearFilter = !pureTranslate || fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    fontRenderer.setTextureFiltering(linearFilter);

    // TODO: Implement better clipping for scaled/rotated text
    const Rect* clip = !pureTranslate ? NULL : mSnapshot->clipRect;
    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    bool status;
    TextSetupFunctor functor(this, x, y, pureTranslate, alpha, mode, paint);

    // don't call issuedrawcommand, do it at end of batch
    bool forceFinish = (drawOpMode != kDrawOpMode_Defer);
    if (CC_UNLIKELY(paint->getTextAlign() != SkPaint::kLeft_Align)) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        status = fontRenderer.renderPosText(&paintCopy, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &layerBounds : NULL, &functor, forceFinish);
    } else {
        status = fontRenderer.renderPosText(paint, clip, text, 0, bytesCount, count, x, y,
                positions, hasActiveLayer ? &layerBounds : NULL, &functor, forceFinish);
    }

    if ((status || drawOpMode != kDrawOpMode_Immediate) && hasActiveLayer) {
        if (!pureTranslate) {
            transform.mapRect(layerBounds);
        }
        dirtyLayerUnchecked(layerBounds, getRegion());
    }

    drawTextDecorations(text, bytesCount, totalAdvance, oldX, oldY, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawTextOnPath(const char* text, int bytesCount, int count, SkPath* path,
        float hOffset, float vOffset, SkPaint* paint) {
    if (text == NULL || count == 0 || mSnapshot->isIgnored() || canSkipText(paint)) {
        return DrawGlInfo::kStatusDone;
    }

    // TODO: avoid scissor by calculating maximum bounds using path bounds + font metrics
    mCaches.enableScissor();

    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, mat4::identity());
    fontRenderer.setTextureFiltering(true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);
    TextSetupFunctor functor(this, 0.0f, 0.0f, false, alpha, mode, paint);

    const Rect* clip = &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    const bool hasActiveLayer = hasLayer();

    if (fontRenderer.renderTextOnPath(paint, clip, text, 0, bytesCount, count, path,
            hOffset, vOffset, hasActiveLayer ? &bounds : NULL, &functor)) {
        if (hasActiveLayer) {
            currentTransform().mapRect(bounds);
            dirtyLayerUnchecked(bounds, getRegion());
        }
    }

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return DrawGlInfo::kStatusDone;

    mCaches.activeTexture(0);

    const PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return DrawGlInfo::kStatusDone;
    const AutoTexture autoCleanup(texture);

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);

    return DrawGlInfo::kStatusDrew;
}

status_t OpenGLRenderer::drawLayer(Layer* layer, float x, float y) {
    if (!layer) {
        return DrawGlInfo::kStatusDone;
    }

    mat4* transform = NULL;
    if (layer->isTextureLayer()) {
        transform = &layer->getTransform();
        if (!transform->isIdentity()) {
            save(0);
            currentTransform().multiply(*transform);
        }
    }

    bool clipRequired = false;
    const bool rejected = quickRejectNoScissor(x, y,
            x + layer->layer.getWidth(), y + layer->layer.getHeight(), false, &clipRequired);

    if (rejected) {
        if (transform && !transform->isIdentity()) {
            restore();
        }
        return DrawGlInfo::kStatusDone;
    }

    updateLayer(layer, true);

    mCaches.setScissorEnabled(mScissorOptimizationDisabled || clipRequired);
    mCaches.activeTexture(0);

    if (CC_LIKELY(!layer->region.isEmpty())) {
        SkiaColorFilter* oldFilter = mDrawModifiers.mColorFilter;
        mDrawModifiers.mColorFilter = layer->getColorFilter();

        if (layer->region.isRect()) {
            DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate,
                    composeLayerRect(layer, layer->regionRect));
        } else if (layer->mesh) {
            const float a = getLayerAlpha(layer);
            setupDraw();
            setupDrawWithTexture();
            setupDrawColor(a, a, a, a);
            setupDrawColorFilter();
            setupDrawBlending(layer->isBlend() || a < 1.0f, layer->getMode(), false);
            setupDrawProgram();
            setupDrawPureColorUniforms();
            setupDrawColorFilterUniforms();
            setupDrawTexture(layer->getTexture());
            if (CC_LIKELY(currentTransform().isPureTranslate())) {
                int tx = (int) floorf(x + currentTransform().getTranslateX() + 0.5f);
                int ty = (int) floorf(y + currentTransform().getTranslateY() + 0.5f);

                layer->setFilter(GL_NEAREST);
                setupDrawModelViewTranslate(tx, ty,
                        tx + layer->layer.getWidth(), ty + layer->layer.getHeight(), true);
            } else {
                layer->setFilter(GL_LINEAR);
                setupDrawModelViewTranslate(x, y,
                        x + layer->layer.getWidth(), y + layer->layer.getHeight());
            }

            TextureVertex* mesh = &layer->mesh[0];
            GLsizei elementsCount = layer->meshElementCount;

            while (elementsCount > 0) {
                GLsizei drawCount = min(elementsCount, (GLsizei) gMaxNumberOfQuads * 6);

                setupDrawMeshIndices(&mesh[0].position[0], &mesh[0].texture[0]);
                DRAW_DOUBLE_STENCIL_IF(!layer->hasDrawnSinceUpdate,
                        glDrawElements(GL_TRIANGLES, drawCount, GL_UNSIGNED_SHORT, NULL));

                elementsCount -= drawCount;
                // Though there are 4 vertices in a quad, we use 6 indices per
                // quad to draw with GL_TRIANGLES
                mesh += (drawCount / 6) * 4;
            }

#if DEBUG_LAYERS_AS_REGIONS
            drawRegionRects(layer->region);
#endif
        }

        mDrawModifiers.mColorFilter = oldFilter;

        if (layer->debugDrawUpdate) {
            layer->debugDrawUpdate = false;
            drawColorRect(x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight(),
                    0x7f00ff00, SkXfermode::kSrcOver_Mode);
        }
    }
    layer->hasDrawnSinceUpdate = true;

    if (transform && !transform->isIdentity()) {
        restore();
    }

    return DrawGlInfo::kStatusDrew;
}

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShader() {
    mDrawModifiers.mShader = NULL;
}

void OpenGLRenderer::setupShader(SkiaShader* shader) {
    mDrawModifiers.mShader = shader;
    if (mDrawModifiers.mShader) {
        mDrawModifiers.mShader->setCaches(mCaches);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Color filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetColorFilter() {
    mDrawModifiers.mColorFilter = NULL;
}

void OpenGLRenderer::setupColorFilter(SkiaColorFilter* filter) {
    mDrawModifiers.mColorFilter = filter;
}

///////////////////////////////////////////////////////////////////////////////
// Drop shadow
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShadow() {
    mDrawModifiers.mHasShadow = false;
}

void OpenGLRenderer::setupShadow(float radius, float dx, float dy, int color) {
    mDrawModifiers.mHasShadow = true;
    mDrawModifiers.mShadowRadius = radius;
    mDrawModifiers.mShadowDx = dx;
    mDrawModifiers.mShadowDy = dy;
    mDrawModifiers.mShadowColor = color;
}

///////////////////////////////////////////////////////////////////////////////
// Draw filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetPaintFilter() {
    // when clearing the PaintFilter, the masks should also be cleared for simple DrawModifier
    // comparison, see MergingDrawBatch::canMergeWith
    mDrawModifiers.mHasDrawFilter = false;
    mDrawModifiers.mPaintFilterClearBits = 0;
    mDrawModifiers.mPaintFilterSetBits = 0;
}

void OpenGLRenderer::setupPaintFilter(int clearBits, int setBits) {
    mDrawModifiers.mHasDrawFilter = true;
    mDrawModifiers.mPaintFilterClearBits = clearBits & SkPaint::kAllFlags;
    mDrawModifiers.mPaintFilterSetBits = setBits & SkPaint::kAllFlags;
}

SkPaint* OpenGLRenderer::filterPaint(SkPaint* paint) {
    if (CC_LIKELY(!mDrawModifiers.mHasDrawFilter || !paint)) {
        return paint;
    }

    uint32_t flags = paint->getFlags();

    mFilteredPaint = *paint;
    mFilteredPaint.setFlags((flags & ~mDrawModifiers.mPaintFilterClearBits) |
            mDrawModifiers.mPaintFilterSetBits);

    return &mFilteredPaint;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing implementation
///////////////////////////////////////////////////////////////////////////////

Texture* OpenGLRenderer::getTexture(SkBitmap* bitmap) {
    Texture* texture = mCaches.assetAtlas.getEntryTexture(bitmap);
    if (!texture) {
        return mCaches.textureCache.get(bitmap);
    }
    return texture;
}

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
}

// Same values used by Skia
#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

void OpenGLRenderer::drawTextDecorations(const char* text, int bytesCount, float underlineWidth,
        float x, float y, SkPaint* paint) {
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

status_t OpenGLRenderer::drawRects(const float* rects, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored()) {
        return DrawGlInfo::kStatusDone;
    }

    int color = paint->getColor();
    // If a shader is set, preserve only the alpha
    if (mDrawModifiers.mShader) {
        color |= 0x00ffffff;
    }
    SkXfermode::Mode mode = getXfermode(paint->getXfermode());

    return drawColorRects(rects, count, color, mode);
}

status_t OpenGLRenderer::drawColorRects(const float* rects, int count, int color,
        SkXfermode::Mode mode, bool ignoreTransform, bool dirty, bool clip) {
    if (count == 0) {
        return DrawGlInfo::kStatusDone;
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

    if (clip && quickReject(left, top, right, bottom)) {
        return DrawGlInfo::kStatusDone;
    }

    setupDraw();
    setupDrawNoTexture();
    setupDrawColor(color, ((color >> 24) & 0xFF) * mSnapshot->alpha);
    setupDrawShader();
    setupDrawColorFilter();
    setupDrawBlending(mode);
    setupDrawProgram();
    setupDrawDirtyRegionsDisabled();
    setupDrawModelView(0.0f, 0.0f, 1.0f, 1.0f, ignoreTransform, true);
    setupDrawColorUniforms();
    setupDrawShaderUniforms();
    setupDrawColorFilterUniforms();

    if (dirty && hasLayer()) {
        dirtyLayer(left, top, right, bottom, currentTransform());
    }

    drawIndexedQuads(&mesh[0], count / 4);

    return DrawGlInfo::kStatusDrew;
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode, bool ignoreTransform) {
    // If a shader is set, preserve only the alpha
    if (mDrawModifiers.mShader) {
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

    GLvoid* vertices = (GLvoid*) NULL;
    GLvoid* texCoords = (GLvoid*) gMeshTextureOffset;

    if (texture->uvMapper) {
        vertices = &mMeshVertices[0].position[0];
        texCoords = &mMeshVertices[0].texture[0];

        Rect uvs(0.0f, 0.0f, 1.0f, 1.0f);
        texture->uvMapper->map(uvs);

        resetDrawTextureTexCoords(uvs.left, uvs.top, uvs.right, uvs.bottom);
    }

    if (CC_LIKELY(currentTransform().isPureTranslate())) {
        const float x = (int) floorf(left + currentTransform().getTranslateX() + 0.5f);
        const float y = (int) floorf(top + currentTransform().getTranslateY() + 0.5f);

        texture->setFilter(GL_NEAREST, true);
        drawTextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
                alpha / 255.0f, mode, texture->blend, vertices, texCoords,
                GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        texture->setFilter(FILTER(paint), true);
        drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode,
                texture->blend, vertices, texCoords, GL_TRIANGLE_STRIP, gMeshCount);
    }

    if (texture->uvMapper) {
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
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
    if (!dirty) setupDrawDirtyRegionsDisabled();
    if (!ignoreScale) {
        setupDrawModelView(left, top, right, bottom, ignoreTransform);
    } else {
        setupDrawModelViewTranslate(left, top, right, bottom, ignoreTransform);
    }
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawMesh(vertices, texCoords, vbo);

    glDrawArrays(drawMode, 0, elementsCount);
}

void OpenGLRenderer::drawIndexedTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform, GLuint vbo, bool ignoreScale, bool dirty) {

    setupDraw();
    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter();
    setupDrawBlending(blend, mode, swapSrcDst);
    setupDrawProgram();
    if (!dirty) setupDrawDirtyRegionsDisabled();
    if (!ignoreScale) {
        setupDrawModelView(left, top, right, bottom, ignoreTransform);
    } else {
        setupDrawModelViewTranslate(left, top, right, bottom, ignoreTransform);
    }
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawMeshIndices(vertices, texCoords, vbo);

    glDrawElements(drawMode, elementsCount, GL_UNSIGNED_SHORT, NULL);
}

void OpenGLRenderer::drawAlpha8TextureMesh(float left, float top, float right, float bottom,
        GLuint texture, bool hasColor, int color, int alpha, SkXfermode::Mode mode,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool ignoreTransform, bool ignoreScale, bool dirty) {

    setupDraw();
    setupDrawWithTexture(true);
    if (hasColor) {
        setupDrawAlpha8Color(color, alpha);
    }
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    if (!dirty) setupDrawDirtyRegionsDisabled();
    if (!ignoreScale) {
        setupDrawModelView(left, top, right, bottom, ignoreTransform);
    } else {
        setupDrawModelViewTranslate(left, top, right, bottom, ignoreTransform);
    }
    setupDrawTexture(texture);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(vertices, texCoords);

    glDrawArrays(drawMode, 0, elementsCount);
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode,
        ProgramDescription& description, bool swapSrcDst) {
    if (mCountOverdraw) {
        if (!mCaches.blend) glEnable(GL_BLEND);
        if (mCaches.lastSrcMode != GL_ONE || mCaches.lastDstMode != GL_ONE) {
            glBlendFunc(GL_ONE, GL_ONE);
        }

        mCaches.blend = true;
        mCaches.lastSrcMode = GL_ONE;
        mCaches.lastDstMode = GL_ONE;

        return;
    }

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

void OpenGLRenderer::getAlphaAndMode(SkPaint* paint, int* alpha, SkXfermode::Mode* mode) const {
    getAlphaAndModeDirect(paint, alpha,  mode);
    if (mDrawModifiers.mOverrideLayerAlpha < 1.0f) {
        // if drawing a layer, ignore the paint's alpha
        *alpha = mDrawModifiers.mOverrideLayerAlpha * 255;
    }
    *alpha *= mSnapshot->alpha;
}

float OpenGLRenderer::getLayerAlpha(Layer* layer) const {
    float alpha;
    if (mDrawModifiers.mOverrideLayerAlpha < 1.0f) {
        alpha = mDrawModifiers.mOverrideLayerAlpha;
    } else {
        alpha = layer->getAlpha() / 255.0f;
    }
    return alpha * mSnapshot->alpha;
}

}; // namespace uirenderer
}; // namespace android
