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
#include "RenderState.h"

#include "renderthread/CanvasContext.h"
#include "renderthread/EglManager.h"

namespace android {
namespace uirenderer {

RenderState::RenderState(renderthread::RenderThread& thread)
        : mRenderThread(thread)
        , mCaches(NULL)
        , mViewportWidth(0)
        , mViewportHeight(0)
        , mFramebuffer(0) {
    mThreadId = pthread_self();
}

RenderState::~RenderState() {
}

void RenderState::onGLContextCreated() {
    // This is delayed because the first access of Caches makes GL calls
    mCaches = &Caches::getInstance();
    mCaches->init();
    mCaches->setRenderState(this);
    mCaches->textureCache.setAssetAtlas(&mAssetAtlas);
}

static void layerLostGlContext(Layer* layer) {
    layer->onGlContextLost();
}

void RenderState::onGLContextDestroyed() {
/*
    size_t size = mActiveLayers.size();
    if (CC_UNLIKELY(size != 0)) {
        ALOGE("Crashing, have %d contexts and %d layers at context destruction. isempty %d",
                mRegisteredContexts.size(), size, mActiveLayers.empty());
        mCaches->dumpMemoryUsage();
        for (std::set<renderthread::CanvasContext*>::iterator cit = mRegisteredContexts.begin();
                cit != mRegisteredContexts.end(); cit++) {
            renderthread::CanvasContext* context = *cit;
            ALOGE("Context: %p (root = %p)", context, context->mRootRenderNode.get());
            ALOGE("  Prefeteched layers: %zu", context->mPrefetechedLayers.size());
            for (std::set<RenderNode*>::iterator pit = context->mPrefetechedLayers.begin();
                    pit != context->mPrefetechedLayers.end(); pit++) {
                (*pit)->debugDumpLayers("    ");
            }
            context->mRootRenderNode->debugDumpLayers("  ");
        }


        if (mActiveLayers.begin() == mActiveLayers.end()) {
            ALOGE("set has become empty. wat.");
        }
        for (std::set<const Layer*>::iterator lit = mActiveLayers.begin();
             lit != mActiveLayers.end(); lit++) {
            const Layer* layer = *(lit);
            ALOGE("Layer %p, state %d, texlayer %d, fbo %d, buildlayered %d",
                    layer, layer->state, layer->isTextureLayer(), layer->getFbo(), layer->wasBuildLayered);
        }
        LOG_ALWAYS_FATAL("%d layers have survived gl context destruction", size);
    }
*/
    std::for_each(mActiveLayers.begin(), mActiveLayers.end(), layerLostGlContext);
    mAssetAtlas.terminate();
}

void RenderState::setViewport(GLsizei width, GLsizei height) {
    mViewportWidth = width;
    mViewportHeight = height;
    glViewport(0, 0, mViewportWidth, mViewportHeight);
}


void RenderState::getViewport(GLsizei* outWidth, GLsizei* outHeight) {
    *outWidth = mViewportWidth;
    *outHeight = mViewportHeight;
}

void RenderState::bindFramebuffer(GLuint fbo) {
    if (mFramebuffer != fbo) {
        mFramebuffer = fbo;
        glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    }
}

void RenderState::invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info) {
    interruptForFunctorInvoke();
    (*functor)(mode, info);
    resumeFromFunctorInvoke();
}

void RenderState::interruptForFunctorInvoke() {
    if (mCaches->currentProgram) {
        if (mCaches->currentProgram->isInUse()) {
            mCaches->currentProgram->remove();
            mCaches->currentProgram = NULL;
        }
    }
    mCaches->resetActiveTexture();
    mCaches->unbindMeshBuffer();
    mCaches->unbindIndicesBuffer();
    mCaches->resetVertexPointers();
    mCaches->disableTexCoordsVertexArray();
    debugOverdraw(false, false);
}

void RenderState::resumeFromFunctorInvoke() {
    glViewport(0, 0, mViewportWidth, mViewportHeight);
    glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    debugOverdraw(false, false);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    mCaches->scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    mCaches->enableScissor();
    mCaches->resetScissor();

    mCaches->activeTexture(0);
    mCaches->resetBoundTextures();

    mCaches->blend = true;
    glEnable(GL_BLEND);
    glBlendFunc(mCaches->lastSrcMode, mCaches->lastDstMode);
    glBlendEquation(GL_FUNC_ADD);
}

void RenderState::debugOverdraw(bool enable, bool clear) {
    if (mCaches->debugOverdraw && mFramebuffer == 0) {
        if (clear) {
            mCaches->disableScissor();
            mCaches->stencil.clear();
        }
        if (enable) {
            mCaches->stencil.enableDebugWrite();
        } else {
            mCaches->stencil.disable();
        }
    }
}

void RenderState::requireGLContext() {
    assertOnGLThread();
    mRenderThread.eglManager().requireGlContext();
}

void RenderState::assertOnGLThread() {
    pthread_t curr = pthread_self();
    LOG_ALWAYS_FATAL_IF(!pthread_equal(mThreadId, curr), "Wrong thread!");
}


class DecStrongTask : public renderthread::RenderTask {
public:
    DecStrongTask(VirtualLightRefBase* object) : mObject(object) {}

    virtual void run() {
        mObject->decStrong(0);
        mObject = 0;
        delete this;
    }

private:
    VirtualLightRefBase* mObject;
};

void RenderState::postDecStrong(VirtualLightRefBase* object) {
    mRenderThread.queue(new DecStrongTask(object));
}

} /* namespace uirenderer */
} /* namespace android */
