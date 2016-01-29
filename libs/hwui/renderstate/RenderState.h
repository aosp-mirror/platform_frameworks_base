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

#include "AssetAtlas.h"
#include "Caches.h"
#include "Glop.h"
#include "renderstate/Blend.h"
#include "renderstate/MeshState.h"
#include "renderstate/OffscreenBufferPool.h"
#include "renderstate/PixelBufferState.h"
#include "renderstate/Scissor.h"
#include "renderstate/Stencil.h"
#include "utils/Macros.h"

#include <set>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <ui/Region.h>
#include <utils/Mutex.h>
#include <utils/Functor.h>
#include <utils/RefBase.h>
#include <private/hwui/DrawGlInfo.h>

namespace android {
namespace uirenderer {

class Caches;
class Layer;

namespace renderthread {
class CanvasContext;
class RenderThread;
}

// TODO: Replace Cache's GL state tracking with this. For now it's more a thin
// wrapper of Caches for users to migrate to.
class RenderState {
    PREVENT_COPY_AND_ASSIGN(RenderState);
    friend class renderthread::RenderThread;
    friend class Caches;
public:
    void onGLContextCreated();
    void onGLContextDestroyed();

    void flush(Caches::FlushMode flushMode);

    void setViewport(GLsizei width, GLsizei height);
    void getViewport(GLsizei* outWidth, GLsizei* outHeight);

    void bindFramebuffer(GLuint fbo);
    GLuint getFramebuffer() { return mFramebuffer; }
    GLuint createFramebuffer();
    void deleteFramebuffer(GLuint fbo);

    void invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info);

    void debugOverdraw(bool enable, bool clear);

    void registerLayer(Layer* layer) {
        mActiveLayers.insert(layer);
    }
    void unregisterLayer(Layer* layer) {
        mActiveLayers.erase(layer);
    }

    void registerCanvasContext(renderthread::CanvasContext* context) {
        mRegisteredContexts.insert(context);
    }

    void unregisterCanvasContext(renderthread::CanvasContext* context) {
        mRegisteredContexts.erase(context);
    }

    // TODO: This system is a little clunky feeling, this could use some
    // more thinking...
    void postDecStrong(VirtualLightRefBase* object);

    void render(const Glop& glop, const Matrix4& orthoMatrix);

    AssetAtlas& assetAtlas() { return mAssetAtlas; }
    Blend& blend() { return *mBlend; }
    MeshState& meshState() { return *mMeshState; }
    Scissor& scissor() { return *mScissor; }
    Stencil& stencil() { return *mStencil; }

    OffscreenBufferPool& layerPool() { return mLayerPool; }

    void dump();

private:
    void interruptForFunctorInvoke();
    void resumeFromFunctorInvoke();

    RenderState(renderthread::RenderThread& thread);
    ~RenderState();


    renderthread::RenderThread& mRenderThread;
    Caches* mCaches = nullptr;

    Blend* mBlend = nullptr;
    MeshState* mMeshState = nullptr;
    Scissor* mScissor = nullptr;
    Stencil* mStencil = nullptr;

    OffscreenBufferPool mLayerPool;

    AssetAtlas mAssetAtlas;
    std::set<Layer*> mActiveLayers;
    std::set<renderthread::CanvasContext*> mRegisteredContexts;

    GLsizei mViewportWidth;
    GLsizei mViewportHeight;
    GLuint mFramebuffer;

    pthread_t mThreadId;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERSTATE_H */
