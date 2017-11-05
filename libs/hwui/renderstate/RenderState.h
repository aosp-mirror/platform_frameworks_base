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
#ifndef RENDERSTATE_H
#define RENDERSTATE_H

#include "Caches.h"
#include "Glop.h"
#include "renderstate/Blend.h"
#include "renderstate/MeshState.h"
#include "renderstate/OffscreenBufferPool.h"
#include "renderstate/PixelBufferState.h"
#include "renderstate/Scissor.h"
#include "renderstate/Stencil.h"
#include "utils/Macros.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <private/hwui/DrawGlInfo.h>
#include <ui/Region.h>
#include <utils/Functor.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <set>

class GrContext;

namespace android {
namespace uirenderer {

class Caches;
class Layer;
class DeferredLayerUpdater;

namespace renderthread {
class CacheManager;
class CanvasContext;
class RenderThread;
}

// TODO: Replace Cache's GL state tracking with this. For now it's more a thin
// wrapper of Caches for users to migrate to.
class RenderState {
    PREVENT_COPY_AND_ASSIGN(RenderState);
    friend class renderthread::RenderThread;
    friend class Caches;
    friend class renderthread::CacheManager;

public:
    void onGLContextCreated();
    void onGLContextDestroyed();

    void onVkContextCreated();
    void onVkContextDestroyed();

    void flush(Caches::FlushMode flushMode);
    void onBitmapDestroyed(uint32_t pixelRefId);

    void setViewport(GLsizei width, GLsizei height);
    void getViewport(GLsizei* outWidth, GLsizei* outHeight);

    void bindFramebuffer(GLuint fbo);
    GLuint getFramebuffer() { return mFramebuffer; }
    GLuint createFramebuffer();
    void deleteFramebuffer(GLuint fbo);

    void invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info);

    void debugOverdraw(bool enable, bool clear);

    void registerLayer(Layer* layer) { mActiveLayers.insert(layer); }
    void unregisterLayer(Layer* layer) { mActiveLayers.erase(layer); }

    void registerCanvasContext(renderthread::CanvasContext* context) {
        mRegisteredContexts.insert(context);
    }

    void unregisterCanvasContext(renderthread::CanvasContext* context) {
        mRegisteredContexts.erase(context);
    }

    void registerDeferredLayerUpdater(DeferredLayerUpdater* layerUpdater) {
        mActiveLayerUpdaters.insert(layerUpdater);
    }

    void unregisterDeferredLayerUpdater(DeferredLayerUpdater* layerUpdater) {
        mActiveLayerUpdaters.erase(layerUpdater);
    }

    // TODO: This system is a little clunky feeling, this could use some
    // more thinking...
    void postDecStrong(VirtualLightRefBase* object);

    void render(const Glop& glop, const Matrix4& orthoMatrix, bool overrideDisableBlending);

    Blend& blend() { return *mBlend; }
    MeshState& meshState() { return *mMeshState; }
    Scissor& scissor() { return *mScissor; }
    Stencil& stencil() { return *mStencil; }

    OffscreenBufferPool& layerPool() { return *mLayerPool; }

    GrContext* getGrContext() const;

    void dump();

private:
    void interruptForFunctorInvoke();
    void resumeFromFunctorInvoke();
    void destroyLayersInUpdater();

    explicit RenderState(renderthread::RenderThread& thread);
    ~RenderState();

    renderthread::RenderThread& mRenderThread;
    Caches* mCaches = nullptr;

    Blend* mBlend = nullptr;
    MeshState* mMeshState = nullptr;
    Scissor* mScissor = nullptr;
    Stencil* mStencil = nullptr;

    OffscreenBufferPool* mLayerPool = nullptr;

    std::set<Layer*> mActiveLayers;
    std::set<DeferredLayerUpdater*> mActiveLayerUpdaters;
    std::set<renderthread::CanvasContext*> mRegisteredContexts;

    GLsizei mViewportWidth;
    GLsizei mViewportHeight;
    GLuint mFramebuffer;

    pthread_t mThreadId;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERSTATE_H */
