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

struct Image {
    enum { TEXTURE_2D=0, TEXTURE_EXTERNAL=1 };
    Image() : name(-1U), image(EGL_NO_IMAGE_KHR), width(0), height(0),
        transform(0), dirty(1), target(TEXTURE_2D) { }
    GLuint        name;
    EGLImageKHR   image;
    GLuint        width;
    GLuint        height;
    uint32_t      transform;
    unsigned      dirty     : 1;
    unsigned      target    : 1;
};

struct Texture : public Image {
    Texture() : Image(), NPOTAdjust(0) { }
    GLuint      potWidth;
    GLuint      potHeight;
    GLfloat     wScale;
    GLfloat     hScale;
    unsigned    NPOTAdjust  : 1;
};

// ---------------------------------------------------------------------------

class TextureManager {
    uint32_t mFlags;
    static status_t initTexture(Image* texture, int32_t format);
    static status_t initTexture(Texture* texture);
    static bool isSupportedYuvFormat(int format);
    static bool isYuvFormat(int format);
    static GLenum getTextureTarget(const Image* pImage);
public:

    TextureManager(uint32_t flags);

    // load bitmap data into the active buffer
    status_t loadTexture(Texture* texture,
            const Region& dirty, const GGLSurface& t);

    // make active buffer an EGLImage if needed
    status_t initEglImage(Image* texture,
            EGLDisplay dpy, const sp<GraphicBuffer>& buffer);

    // activate a texture
    static void activateTexture(const Texture& texture, bool filter);

    // deactivate a texture
    static void deactivateTextures();
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_TEXTURE_MANAGER_H
