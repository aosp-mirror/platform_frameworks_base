/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <pthread.h>
#include <cutils/log.h>

extern "C" {
#include "liblzf/lzf.h"
}

#include "gltrace_context.h"

namespace android {
namespace gltrace {

using ::android::gl_hooks_t;

static pthread_key_t sTLSKey = -1;
static pthread_once_t sPthreadOnceKey = PTHREAD_ONCE_INIT;

void createTLSKey() {
    pthread_key_create(&sTLSKey, NULL);
}

GLTraceContext *getGLTraceContext() {
    return (GLTraceContext*) pthread_getspecific(sTLSKey);
}

void setGLTraceContext(GLTraceContext *c) {
    pthread_setspecific(sTLSKey, c);
}

void setupTraceContextThreadSpecific(GLTraceContext *context) {
    pthread_once(&sPthreadOnceKey, createTLSKey);
    setGLTraceContext(context);
}

void releaseContext() {
    GLTraceContext *c = getGLTraceContext();
    if (c != NULL) {
        delete c;
        setGLTraceContext(NULL);
    }
}

GLTraceState::GLTraceState(TCPStream *stream) {
    mTraceContextIds = 0;
    mStream = stream;

    mCollectFbOnEglSwap = false;
    mCollectFbOnGlDraw = false;
    mCollectTextureDataOnGlTexImage = false;
    pthread_rwlock_init(&mTraceOptionsRwLock, NULL);
}

GLTraceState::~GLTraceState() {
    if (mStream) {
        mStream->closeStream();
        mStream = NULL;
    }
}

TCPStream *GLTraceState::getStream() {
    return mStream;
}

void GLTraceState::safeSetValue(bool *ptr, bool value, pthread_rwlock_t *lock) {
    pthread_rwlock_wrlock(lock);
    *ptr = value;
    pthread_rwlock_unlock(lock);
}

bool GLTraceState::safeGetValue(bool *ptr, pthread_rwlock_t *lock) {
    pthread_rwlock_rdlock(lock);
    bool value = *ptr;
    pthread_rwlock_unlock(lock);
    return value;
}

void GLTraceState::setCollectFbOnEglSwap(bool en) {
    safeSetValue(&mCollectFbOnEglSwap, en, &mTraceOptionsRwLock);
}

void GLTraceState::setCollectFbOnGlDraw(bool en) {
    safeSetValue(&mCollectFbOnGlDraw, en, &mTraceOptionsRwLock);
}

void GLTraceState::setCollectTextureDataOnGlTexImage(bool en) {
    safeSetValue(&mCollectTextureDataOnGlTexImage, en, &mTraceOptionsRwLock);
}

bool GLTraceState::shouldCollectFbOnEglSwap() {
    return safeGetValue(&mCollectFbOnEglSwap, &mTraceOptionsRwLock);
}

bool GLTraceState::shouldCollectFbOnGlDraw() {
    return safeGetValue(&mCollectFbOnGlDraw, &mTraceOptionsRwLock);
}

bool GLTraceState::shouldCollectTextureDataOnGlTexImage() {
    return safeGetValue(&mCollectTextureDataOnGlTexImage, &mTraceOptionsRwLock);
}

GLTraceContext *GLTraceState::createTraceContext(int version, EGLContext eglContext) {
    int id = __sync_fetch_and_add(&mTraceContextIds, 1);

    const size_t DEFAULT_BUFFER_SIZE = 8192;
    BufferedOutputStream *stream = new BufferedOutputStream(mStream, DEFAULT_BUFFER_SIZE);
    GLTraceContext *traceContext = new GLTraceContext(id, this, stream);
    mPerContextState[eglContext] = traceContext;

    return traceContext;
}

GLTraceContext *GLTraceState::getTraceContext(EGLContext c) {
    return mPerContextState[c];
}

GLTraceContext::GLTraceContext(int id, GLTraceState *state, BufferedOutputStream *stream) {
    mId = id;
    mState = state;

    fbcontents = fbcompressed = NULL;
    fbcontentsSize = 0;
    mBufferedOutputStream = stream;
}

int GLTraceContext::getId() {
    return mId;
}

GLTraceState *GLTraceContext::getGlobalTraceState() {
    return mState;
}

void GLTraceContext::resizeFBMemory(unsigned minSize) {
    if (fbcontentsSize >= minSize) {
        return;
    }

    if (fbcontents != NULL) {
        free(fbcontents);
        free(fbcompressed);
    }

    fbcontents = malloc(minSize);
    fbcompressed = malloc(minSize);

    fbcontentsSize = minSize;
}

/** obtain a pointer to the compressed framebuffer image */
void GLTraceContext::getCompressedFB(void **fb, unsigned *fbsize, unsigned *fbwidth, 
                            unsigned *fbheight, FBBinding fbToRead) {
    int viewport[4] = {};
    hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
    unsigned fbContentsSize = viewport[2] * viewport[3] * 4;

    resizeFBMemory(fbContentsSize);

    // switch current framebuffer binding if necessary
    GLint currentFb = -1;
    bool fbSwitched = false;
    if (fbToRead != CURRENTLY_BOUND_FB) {
        hooks->gl.glGetIntegerv(GL_FRAMEBUFFER_BINDING, &currentFb);

        if (currentFb != 0) {
            hooks->gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            fbSwitched = true;
        }
    }

    hooks->gl.glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                                        GL_RGBA, GL_UNSIGNED_BYTE, fbcontents);

    // switch back to previously bound buffer if necessary
    if (fbSwitched) {
        hooks->gl.glBindFramebuffer(GL_FRAMEBUFFER, currentFb);
    }

    *fbsize = lzf_compress(fbcontents, fbContentsSize, fbcompressed, fbContentsSize);
    *fb = fbcompressed;
    *fbwidth = viewport[2];
    *fbheight = viewport[3];
}

void GLTraceContext::traceGLMessage(GLMessage *msg) {
    mBufferedOutputStream->send(msg);

    GLMessage_Function func = msg->function();
    if (func == GLMessage::eglSwapBuffers
        || func == GLMessage::glDrawArrays
        || func == GLMessage::glDrawElements) {
        mBufferedOutputStream->flush();
    }
}

}; // namespace gltrace
}; // namespace android
