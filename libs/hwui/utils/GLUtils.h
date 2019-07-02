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
#ifndef GLUTILS_H
#define GLUTILS_H

#include "Debug.h"

#include <log/log.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>

namespace android {
namespace uirenderer {

#if DEBUG_OPENGL
#define GL_CHECKPOINT(LEVEL)                                                                      \
    do {                                                                                          \
        if (DEBUG_OPENGL >= DEBUG_LEVEL_##LEVEL) {                                                \
            LOG_ALWAYS_FATAL_IF(android::uirenderer::GLUtils::dumpGLErrors(), "GL errors! %s:%d", \
                                __FILE__, __LINE__);                                              \
        }                                                                                         \
    } while (0)
#else
#define GL_CHECKPOINT(LEVEL)
#endif

class GLUtils {
public:
    /**
     * Print out any GL errors with ALOGE, returns true if any errors were found.
     * You probably want to use GL_CHECKPOINT(LEVEL) instead of calling this directly
     */
    static bool dumpGLErrors();

    static const char* getGLFramebufferError();
};  // class GLUtils

class AutoEglImage {
public:
    AutoEglImage(EGLDisplay display, EGLClientBuffer clientBuffer) : mDisplay(display) {
        EGLint imageAttrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer,
                                  imageAttrs);
    }

    ~AutoEglImage() {
        if (image != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mDisplay, image);
        }
    }

    EGLImageKHR image = EGL_NO_IMAGE_KHR;

private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
};

class AutoSkiaGlTexture {
public:
    AutoSkiaGlTexture() {
        glGenTextures(1, &mTexture);
        glBindTexture(GL_TEXTURE_2D, mTexture);
    }

    ~AutoSkiaGlTexture() { glDeleteTextures(1, &mTexture); }

    GLuint mTexture = 0;
};

class AutoGLFramebuffer {
public:
    AutoGLFramebuffer() {
        glGenFramebuffers(1, &mFb);
        glBindFramebuffer(GL_FRAMEBUFFER, mFb);
    }

    ~AutoGLFramebuffer() { glDeleteFramebuffers(1, &mFb); }

    GLuint mFb;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* GLUTILS_H */
