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
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/GraphicBuffer.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <hardware/hardware.h>

#include "clz.h"
#include "DisplayHardware/DisplayHardware.h"
#include "TextureManager.h"

namespace android {

// ---------------------------------------------------------------------------

TextureManager::TextureManager(uint32_t flags)
    : mFlags(flags)
{
}

GLuint TextureManager::createTexture()
{
    GLuint textureName = -1;
    glGenTextures(1, &textureName);
    glBindTexture(GL_TEXTURE_2D, textureName);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    return textureName;
}

bool TextureManager::isSupportedYuvFormat(int format)
{
    switch (format) {
        case HAL_PIXEL_FORMAT_YCbCr_422_SP:
        case HAL_PIXEL_FORMAT_YCbCr_420_SP:
        case HAL_PIXEL_FORMAT_YCbCr_422_P:
        case HAL_PIXEL_FORMAT_YCbCr_420_P:
        case HAL_PIXEL_FORMAT_YCbCr_422_I:
        case HAL_PIXEL_FORMAT_YCbCr_420_I:
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            return true;
    }
    return false;
}

status_t TextureManager::initEglImage(Texture* texture,
        EGLDisplay dpy, const sp<GraphicBuffer>& buffer)
{
    status_t err = NO_ERROR;
    if (!texture->dirty) return err;

    // free the previous image
    if (texture->image != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(dpy, texture->image);
        texture->image = EGL_NO_IMAGE_KHR;
    }

    // construct an EGL_NATIVE_BUFFER_ANDROID
    android_native_buffer_t* clientBuf = buffer->getNativeBuffer();

    // create the new EGLImageKHR
    const EGLint attrs[] = {
            EGL_IMAGE_PRESERVED_KHR,    EGL_TRUE,
            EGL_NONE,                   EGL_NONE
    };
    texture->image = eglCreateImageKHR(
            dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            (EGLClientBuffer)clientBuf, attrs);

    if (texture->image != EGL_NO_IMAGE_KHR) {
        if (texture->name == -1UL) {
            texture->name = createTexture();
            texture->width = 0;
            texture->height = 0;
        }
        glBindTexture(GL_TEXTURE_2D, texture->name);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D,
                (GLeglImageOES)texture->image);
        GLint error = glGetError();
        if (error != GL_NO_ERROR) {
            LOGE("glEGLImageTargetTexture2DOES(%p) failed err=0x%04x",
                    texture->image, error);
            err = INVALID_OPERATION;
        } else {
            // Everything went okay!
            texture->NPOTAdjust = false;
            texture->dirty  = false;
            texture->width  = clientBuf->width;
            texture->height = clientBuf->height;
        }
    } else {
        LOGE("eglCreateImageKHR() failed. err=0x%4x", eglGetError());
        err = INVALID_OPERATION;
    }
    return err;
}

status_t TextureManager::loadTexture(Texture* texture,
        const Region& dirty, const GGLSurface& t)
{
    if (texture->name == -1UL) {
        texture->name = createTexture();
        texture->width = 0;
        texture->height = 0;
    }

    glBindTexture(GL_TEXTURE_2D, texture->name);

    /*
     * In OpenGL ES we can't specify a stride with glTexImage2D (however,
     * GL_UNPACK_ALIGNMENT is a limited form of stride).
     * So if the stride here isn't representable with GL_UNPACK_ALIGNMENT, we
     * need to do something reasonable (here creating a bigger texture).
     *
     * extra pixels = (((stride - width) * pixelsize) / GL_UNPACK_ALIGNMENT);
     *
     * This situation doesn't happen often, but some h/w have a limitation
     * for their framebuffer (eg: must be multiple of 8 pixels), and
     * we need to take that into account when using these buffers as
     * textures.
     *
     * This should never be a problem with POT textures
     */

    int unpack = __builtin_ctz(t.stride * bytesPerPixel(t.format));
    unpack = 1 << ((unpack > 3) ? 3 : unpack);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpack);

    /*
     * round to POT if needed
     */
    if (!(mFlags & DisplayHardware::NPOT_EXTENSION)) {
        texture->NPOTAdjust = true;
    }

    if (texture->NPOTAdjust) {
        // find the smallest power-of-two that will accommodate our surface
        texture->potWidth  = 1 << (31 - clz(t.width));
        texture->potHeight = 1 << (31 - clz(t.height));
        if (texture->potWidth  < t.width)  texture->potWidth  <<= 1;
        if (texture->potHeight < t.height) texture->potHeight <<= 1;
        texture->wScale = float(t.width)  / texture->potWidth;
        texture->hScale = float(t.height) / texture->potHeight;
    } else {
        texture->potWidth  = t.width;
        texture->potHeight = t.height;
    }

    Rect bounds(dirty.bounds());
    GLvoid* data = 0;
    if (texture->width != t.width || texture->height != t.height) {
        texture->width  = t.width;
        texture->height = t.height;

        // texture size changed, we need to create a new one
        bounds.set(Rect(t.width, t.height));
        if (t.width  == texture->potWidth &&
            t.height == texture->potHeight) {
            // we can do it one pass
            data = t.data;
        }

        if (t.format == HAL_PIXEL_FORMAT_RGB_565) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGB, texture->potWidth, texture->potHeight, 0,
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5, data);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_4444) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGBA, texture->potWidth, texture->potHeight, 0,
                    GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, data);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_8888 ||
                   t.format == HAL_PIXEL_FORMAT_RGBX_8888) {
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_RGBA, texture->potWidth, texture->potHeight, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, data);
        } else if (isSupportedYuvFormat(t.format)) {
            // just show the Y plane of YUV buffers
            glTexImage2D(GL_TEXTURE_2D, 0,
                    GL_LUMINANCE, texture->potWidth, texture->potHeight, 0,
                    GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
        } else {
            // oops, we don't handle this format!
            LOGE("texture=%d, using format %d, which is not "
                 "supported by the GL", texture->name, t.format);
        }
    }
    if (!data) {
        if (t.format == HAL_PIXEL_FORMAT_RGB_565) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5,
                    t.data + bounds.top*t.stride*2);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_4444) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4,
                    t.data + bounds.top*t.stride*2);
        } else if (t.format == HAL_PIXEL_FORMAT_RGBA_8888 ||
                   t.format == HAL_PIXEL_FORMAT_RGBX_8888) {
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_RGBA, GL_UNSIGNED_BYTE,
                    t.data + bounds.top*t.stride*4);
        } else if (isSupportedYuvFormat(t.format)) {
            // just show the Y plane of YUV buffers
            glTexSubImage2D(GL_TEXTURE_2D, 0,
                    0, bounds.top, t.width, bounds.height(),
                    GL_LUMINANCE, GL_UNSIGNED_BYTE,
                    t.data + bounds.top*t.stride);
        }
    }
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

}; // namespace android
