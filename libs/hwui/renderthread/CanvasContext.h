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

#ifndef CANVASCONTEXT_H_
#define CANVASCONTEXT_H_

#include <set>

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkBitmap.h>
#include <utils/Functor.h>
#include <utils/Vector.h>

#include "../DamageAccumulator.h"
#include "../DrawProfiler.h"
#include "../IContextFactory.h"
#include "../RenderNode.h"
#include "RenderTask.h"
#include "RenderThread.h"

#define FUNCTOR_PROCESS_DELAY 4

namespace android {
namespace uirenderer {

class AnimationContext;
class DeferredLayerUpdater;
class OpenGLRenderer;
class Rect;
class Layer;
class RenderState;

namespace renderthread {

class EglManager;

enum SwapBehavior {
    kSwap_default,
    kSwap_discardBuffer,
};

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
// TODO: Rename to Renderer or some other per-window, top-level manager
class CanvasContext : public IFrameCallback {
public:
    CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
            IContextFactory* contextFactory);
    virtual ~CanvasContext();

    // Won't take effect until next EGLSurface creation
    void setSwapBehavior(SwapBehavior swapBehavior);

    bool initialize(ANativeWindow* window);
    void updateSurface(ANativeWindow* window);
    bool pauseSurface(ANativeWindow* window);
    bool hasSurface() { return mNativeWindow.get(); }

    void setup(int width, int height, const Vector3& lightCenter, float lightRadius,
            uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    void setOpaque(bool opaque);
    void makeCurrent();
    void processLayerUpdate(DeferredLayerUpdater* layerUpdater);
    void prepareTree(TreeInfo& info);
    void draw();
    void destroy();

    // IFrameCallback, Chroreographer-driven frame callback entry point
    virtual void doFrame();

    void buildLayer(RenderNode* node);
    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);
    void markLayerInUse(RenderNode* node);

    void destroyHardwareResources();
    static void trimMemory(RenderThread& thread, int level);

    static void invokeFunctor(RenderThread& thread, Functor* functor);

    void runWithGlContext(RenderTask* task);

    Layer* createTextureLayer();

    ANDROID_API static void setTextureAtlas(RenderThread& thread,
            const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize);

    void stopDrawing();
    void notifyFramePending();

    DrawProfiler& profiler() { return mProfiler; }

private:
    friend class RegisterFrameCallbackTask;
    // TODO: Replace with something better for layer & other GL object
    // lifecycle tracking
    friend class android::uirenderer::RenderState;

    void setSurface(ANativeWindow* window);
    void swapBuffers();
    void requireSurface();

    void requireGlContext();

    void freePrefetechedLayers();

    RenderThread& mRenderThread;
    EglManager& mEglManager;
    sp<ANativeWindow> mNativeWindow;
    EGLSurface mEglSurface;
    bool mBufferPreserved;
    SwapBehavior mSwapBehavior;

    bool mOpaque;
    OpenGLRenderer* mCanvas;
    bool mHaveNewSurface;
    DamageAccumulator mDamageAccumulator;
    AnimationContext* mAnimationContext;

    const sp<RenderNode> mRootRenderNode;

    DrawProfiler mProfiler;

    std::set<RenderNode*> mPrefetechedLayers;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* CANVASCONTEXT_H_ */
