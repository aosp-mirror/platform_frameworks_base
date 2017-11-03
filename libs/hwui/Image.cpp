/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include <utils/Log.h>

#include "Caches.h"
#include "Image.h"

namespace android {
namespace uirenderer {

Image::Image(sp<GraphicBuffer> buffer) {
    // Create the EGLImage object that maps the GraphicBuffer
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLClientBuffer clientBuffer = (EGLClientBuffer)buffer->getNativeBuffer();
    EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    mImage = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer,
                               attrs);

    if (mImage == EGL_NO_IMAGE_KHR) {
        ALOGW("Error creating image (%#x)", eglGetError());
        mTexture = 0;
    } else {
        // Create a 2D texture to sample from the EGLImage
        glGenTextures(1, &mTexture);
        Caches::getInstance().textureState().bindTexture(mTexture);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, mImage);

        GLenum status = GL_NO_ERROR;
        while ((status = glGetError()) != GL_NO_ERROR) {
            ALOGW("Error creating image (%#x)", status);
        }
    }
}

Image::~Image() {
    if (mImage != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(eglGetDisplay(EGL_DEFAULT_DISPLAY), mImage);
        mImage = EGL_NO_IMAGE_KHR;

        Caches::getInstance().textureState().deleteTexture(mTexture);
        mTexture = 0;
    }
}

};  // namespace uirenderer
};  // namespace android
