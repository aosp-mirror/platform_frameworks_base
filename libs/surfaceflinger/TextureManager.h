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

#ifndef ANDROID_TEXTURE_MANAGER_H
#define ANDROID_TEXTURE_MANAGER_H

#include <stdint.h>
#include <sys/types.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>

#include <ui/Region.h>

#include <pixelflinger/pixelflinger.h>

namespace android {

// ---------------------------------------------------------------------------

class GraphicBuffer;

// ---------------------------------------------------------------------------

struct Texture {
    Texture() : name(-1U), width(0), height(0),
        image(EGL_NO_IMAGE_KHR), transform(0),
        NPOTAdjust(false), dirty(true) { }
    GLuint        name;
    GLuint        width;
    GLuint        height;
    GLuint        potWidth;
    GLuint        potHeight;
    GLfloat       wScale;
    GLfloat       hScale;
    EGLImageKHR   image;
    uint32_t      transform;
    bool          NPOTAdjust;
    bool          dirty;
};

// ---------------------------------------------------------------------------

class TextureManager {
    uint32_t mFlags;
    GLuint createTexture();
    static bool isSupportedYuvFormat(int format);
public:

    TextureManager(uint32_t flags);

    // load bitmap data into the active buffer
    status_t loadTexture(Texture* texture,
            const Region& dirty, const GGLSurface& t);

    // make active buffer an EGLImage if needed
    status_t initEglImage(Texture* texture,
            EGLDisplay dpy, const sp<GraphicBuffer>& buffer);
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_TEXTURE_MANAGER_H
