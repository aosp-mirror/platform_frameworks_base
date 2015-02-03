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
#include "renderstate/RenderState.h"

#include "renderthread/CanvasContext.h"
#include "renderthread/EglManager.h"

namespace android {
namespace uirenderer {

RenderState::RenderState(renderthread::RenderThread& thread)
        : mRenderThread(thread)
        , mViewportWidth(0)
        , mViewportHeight(0)
        , mFramebuffer(0) {
    mThreadId = pthread_self();
}

RenderState::~RenderState() {
    LOG_ALWAYS_FATAL_IF(mBlend || mMeshState || mScissor || mStencil,
            "State object lifecycle not managed correctly");
}

void RenderState::onGLContextCreated() {
    LOG_ALWAYS_FATAL_IF(mBlend || mMeshState || mScissor || mStencil,
            "State object lifecycle not managed correctly");
    mBlend = new Blend();
    mMeshState = new MeshState();
    mScissor = new Scissor();
    mStencil = new Stencil();

    // This is delayed because the first access of Caches makes GL calls
    mCaches = &Caches::createInstance(*this);
    mCaches->init();
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

    // TODO: reset all cached state in state objects
    std::for_each(mActiveLayers.begin(), mActiveLayers.end(), layerLostGlContext);
    mAssetAtlas.terminate();

    mCaches->terminate();

    delete mBlend;
    mBlend = nullptr;
    delete mMeshState;
    mMeshState = nullptr;
    delete mScissor;
    mScissor = nullptr;
    delete mStencil;
    mStencil = nullptr;
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
    mCaches->setProgram(nullptr);
    mCaches->textureState().resetActiveTexture();
    meshState().unbindMeshBuffer();
    meshState().unbindIndicesBuffer();
    meshState().resetVertexPointers();
    meshState().disableTexCoordsVertexArray();
    debugOverdraw(false, false);
}

void RenderState::resumeFromFunctorInvoke() {
    glViewport(0, 0, mViewportWidth, mViewportHeight);
    glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    debugOverdraw(false, false);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    scissor().invalidate();
    blend().invalidate();

    mCaches->textureState().activateTexture(0);
    mCaches->textureState().resetBoundTextures();
}

void RenderState::debugOverdraw(bool enable, bool clear) {
    if (mCaches->debugOverdraw && mFramebuffer == 0) {
        if (clear) {
            scissor().setEnabled(false);
            stencil().clear();
        }
        if (enable) {
            stencil().enableDebugWrite();
        } else {
            stencil().disable();
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

    virtual void run() override {
        mObject->decStrong(nullptr);
        mObject = nullptr;
        delete this;
    }

private:
    VirtualLightRefBase* mObject;
};

void RenderState::postDecStrong(VirtualLightRefBase* object) {
    mRenderThread.queue(new DecStrongTask(object));
}

///////////////////////////////////////////////////////////////////////////////
// Render
///////////////////////////////////////////////////////////////////////////////

/*
 * Not yet supported:
 *
 * Textures + coordinates
 * SkiaShader
 * ColorFilter
 *
    // TODO: texture coord
    // TODO: texture support
    // TODO: skiashader support
    // TODO: color filter support
 */

void RenderState::render(const Glop& glop) {
    const Glop::Mesh& mesh = glop.mesh;
    const Glop::Fill& shader = glop.fill;

    // ---------- Shader + uniform setup ----------
    mCaches->setProgram(shader.program);

    Glop::Fill::Color color = shader.color;
    shader.program->setColor(color.r, color.g, color.b, color.a);

    shader.program->set(glop.transform.ortho,
            glop.transform.modelView,
            glop.transform.canvas,
            glop.transform.offset);

    // ---------- Mesh setup ----------
    if (glop.mesh.vertexFlags & kTextureCoord_Attrib) {
        // TODO: support textures
        LOG_ALWAYS_FATAL("textures not yet supported");
    } else {
        meshState().disableTexCoordsVertexArray();
    }
    if (glop.mesh.vertexFlags & kColor_Attrib) {
        LOG_ALWAYS_FATAL("color attribute not yet supported");
        // TODO: enable color, disable when done
    }
    if (glop.mesh.vertexFlags & kAlpha_Attrib) {
        LOG_ALWAYS_FATAL("alpha attribute not yet supported");
        // TODO: enable alpha attribute, disable when done
    }

    /**
    * Hard-coded vertex assumptions:
     *     - required
     *     - xy floats
     *     - 0 offset
     *     - in VBO
     */
    bool force = meshState().bindMeshBuffer(mesh.vertexBufferObject);
    meshState().bindPositionVertexPointer(force, nullptr, mesh.stride);

    /**
     * Hard-coded index assumptions:
     *     - optional
     *     - 0 offset
     *     - in IBO
     */
    meshState().bindIndicesBufferInternal(mesh.indexBufferObject);

    // ---------- GL state setup ----------
    blend().setFactors(glop.blend.src, glop.blend.dst);

    if (mesh.indexBufferObject) {
        glDrawElements(glop.mesh.primitiveMode, glop.mesh.vertexCount, GL_UNSIGNED_BYTE, nullptr);
    } else {
        glDrawArrays(GL_TRIANGLE_STRIP, 0, glop.mesh.vertexCount);
    }
}

} /* namespace uirenderer */
} /* namespace android */
