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

void initContext(unsigned version, gl_hooks_t *hooks) {
    pthread_once(&sPthreadOnceKey, createTLSKey);

    GLTraceContext *context = new GLTraceContext();
    context->hooks = hooks;

    setGLTraceContext(context);
}

void releaseContext() {
    GLTraceContext *c = getGLTraceContext();
    if (c != NULL) {
        delete c;
        setGLTraceContext(NULL);
    }
}

GLTraceContext::GLTraceContext() {
    fbcontents = fbcompressed = NULL;
    fbcontentsSize = 0;
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
                            unsigned *fbheight) {
    int viewport[4] = {};
    hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
    unsigned fbContentsSize = viewport[2] * viewport[3] * 4;

    resizeFBMemory(fbContentsSize);

    //TODO: On eglSwapBuffer, read FB0. For glDraw calls, read currently
    //      bound FB.
    //hooks->gl.glGetIntegerv(GL_FRAMEBUFFER_BINDING, &bound_fb);
    //hooks->gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    hooks->gl.glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                                        GL_RGBA, GL_UNSIGNED_BYTE, fbcontents);
    *fbsize = lzf_compress(fbcontents, fbContentsSize, fbcompressed, fbContentsSize);
    *fb = fbcompressed;
    *fbwidth = viewport[2];
    *fbheight = viewport[3];
}

}; // namespace gltrace
}; // namespace android
