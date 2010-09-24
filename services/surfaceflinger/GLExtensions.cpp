/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>

#include "GLExtensions.h"

namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE( GLExtensions )

GLExtensions::GLExtensions()
    : mHaveTextureExternal(false),
      mHaveNpot(false),
      mHaveDirectTexture(false)
{
}

void GLExtensions::initWithGLStrings(
        GLubyte const* vendor,
        GLubyte const* renderer,
        GLubyte const* version,
        GLubyte const* extensions,
        char const* egl_vendor,
        char const* egl_version,
        char const* egl_extensions)
{
    mVendor     = (char const*)vendor;
    mRenderer   = (char const*)renderer;
    mVersion    = (char const*)version;
    mExtensions = (char const*)extensions;
    mEglVendor     = egl_vendor;
    mEglVersion    = egl_version;
    mEglExtensions = egl_extensions;

    char const* curr = (char const*)extensions;
    char const* head = curr;
    do {
        head = strchr(curr, ' ');
        String8 s(curr, head ? head-curr : strlen(curr));
        if (s.length()) {
            mExtensionList.add(s);
        }
        curr = head+1;
    } while (head);

    curr = egl_extensions;
    head = curr;
    do {
        head = strchr(curr, ' ');
        String8 s(curr, head ? head-curr : strlen(curr));
        if (s.length()) {
            mExtensionList.add(s);
        }
        curr = head+1;
    } while (head);

#ifdef EGL_ANDROID_image_native_buffer
    if (hasExtension("GL_OES_EGL_image") &&
        (hasExtension("EGL_KHR_image_base") || hasExtension("EGL_KHR_image")) &&
        hasExtension("EGL_ANDROID_image_native_buffer"))
    {
        mHaveDirectTexture = true;
    }
#else
#warning "EGL_ANDROID_image_native_buffer not supported"
#endif

    if (hasExtension("GL_ARB_texture_non_power_of_two")) {
        mHaveNpot = true;
    }

    if (hasExtension("GL_OES_EGL_image_external")) {
        mHaveTextureExternal = true;
    } else if (strstr(mRenderer.string(), "Adreno")) {
        // hack for Adreno 200
        mHaveTextureExternal = true;
    }

    if (hasExtension("GL_OES_framebuffer_object")) {
        mHaveFramebufferObject = true;
    }
}

bool GLExtensions::hasExtension(char const* extension) const
{
    const String8 s(extension);
    return mExtensionList.indexOf(s) >= 0;
}

char const* GLExtensions::getVendor() const {
    return mVendor.string();
}

char const* GLExtensions::getRenderer() const {
    return mRenderer.string();
}

char const* GLExtensions::getVersion() const {
    return mVersion.string();
}

char const* GLExtensions::getExtension() const {
    return mExtensions.string();
}

char const* GLExtensions::getEglVendor() const {
    return mEglVendor.string();
}

char const* GLExtensions::getEglVersion() const {
    return mEglVersion.string();
}

char const* GLExtensions::getEglExtension() const {
    return mEglExtensions.string();
}


// ---------------------------------------------------------------------------
}; // namespace android
