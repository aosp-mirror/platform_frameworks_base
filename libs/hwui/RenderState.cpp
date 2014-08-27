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

namespace android {
namespace uirenderer {

RenderState::RenderState()
        : mCaches(NULL)
        , mViewportWidth(0)
        , mViewportHeight(0)
        , mFramebuffer(0) {
}

RenderState::~RenderState() {
}

void RenderState::onGLContextCreated() {
    // This is delayed because the first access of Caches makes GL calls
    mCaches = &Caches::getInstance();
    mCaches->init();
}

void RenderState::onGLContextDestroyed() {
    LOG_ALWAYS_FATAL_IF(!mActiveLayers.empty(), "layers have survived gl context destruction");
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

} /* namespace uirenderer */
} /* namespace android */
