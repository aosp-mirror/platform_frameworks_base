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

#ifndef ANDROID_HWUI_RENDER_BUFFER_H
#define ANDROID_HWUI_RENDER_BUFFER_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

/**
 * Represents an OpenGL render buffer. Render buffers are attached
 * to layers to perform stencil work.
 */
struct RenderBuffer {
    /**
     * Creates a new render buffer in the specified format and dimensions.
     * The format must be one of the formats allowed by glRenderbufferStorage().
     */
    RenderBuffer(GLenum format, uint32_t width, uint32_t height)
            : mFormat(format), mWidth(width), mHeight(height), mAllocated(false) {
        glGenRenderbuffers(1, &mName);
    }

    ~RenderBuffer() {
        if (mName) {
            glDeleteRenderbuffers(1, &mName);
        }
    }

    /**
     * Returns the GL name of this render buffer.
     */
    GLuint getName() const { return mName; }

    /**
     * Returns the format of this render buffer.
     */
    GLenum getFormat() const { return mFormat; }

    /**
     * Binds this render buffer to the current GL context.
     */
    void bind() const { glBindRenderbuffer(GL_RENDERBUFFER, mName); }

    /**
     * Indicates whether this render buffer has allocated its
     * storage. See allocate() and resize().
     */
    bool isAllocated() const { return mAllocated; }

    /**
     * Allocates this render buffer's storage if needed.
     * This method doesn't do anything if isAllocated() returns true.
     */
    void allocate() {
        if (!mAllocated) {
            glRenderbufferStorage(GL_RENDERBUFFER, mFormat, mWidth, mHeight);
            mAllocated = true;
        }
    }

    /**
     * Resizes this render buffer. If the buffer was previously allocated,
     * the storage is re-allocated wit the new specified dimensions. If the
     * buffer wasn't previously allocated, the buffer remains unallocated.
     */
    void resize(uint32_t width, uint32_t height) {
        if (isAllocated() && (width != mWidth || height != mHeight)) {
            glRenderbufferStorage(GL_RENDERBUFFER, mFormat, width, height);
        }

        mWidth = width;
        mHeight = height;
    }

    /**
     * Returns the width of the render buffer in pixels.
     */
    uint32_t getWidth() const { return mWidth; }

    /**
     * Returns the height of the render buffer in pixels.
     */
    uint32_t getHeight() const { return mHeight; }

    /**
     * Returns the size of this render buffer in bytes.
     */
    uint32_t getSize() const {
        // Round to the nearest byte
        return (uint32_t)((mWidth * mHeight * formatSize(mFormat)) / 8.0f + 0.5f);
    }

    /**
     * Returns the number of bits per component in the specified format.
     * The format must be one of the formats allowed by glRenderbufferStorage().
     */
    static uint32_t formatSize(GLenum format) {
        switch (format) {
            case GL_STENCIL_INDEX8:
                return 8;
            case GL_STENCIL_INDEX1_OES:
                return 1;
            case GL_STENCIL_INDEX4_OES:
                return 4;
            case GL_DEPTH_COMPONENT16:
            case GL_RGBA4:
            case GL_RGB565:
            case GL_RGB5_A1:
                return 16;
        }
        return 0;
    }

    /**
     * Indicates whether the specified format represents a stencil buffer.
     */
    static bool isStencilBuffer(GLenum format) {
        switch (format) {
            case GL_STENCIL_INDEX8:
            case GL_STENCIL_INDEX1_OES:
            case GL_STENCIL_INDEX4_OES:
                return true;
        }
        return false;
    }

    /**
     * Returns the name of the specified render buffer format.
     */
    static const char* formatName(GLenum format) {
        switch (format) {
            case GL_STENCIL_INDEX8:
                return "STENCIL_8";
            case GL_STENCIL_INDEX1_OES:
                return "STENCIL_1";
            case GL_STENCIL_INDEX4_OES:
                return "STENCIL_4";
            case GL_DEPTH_COMPONENT16:
                return "DEPTH_16";
            case GL_RGBA4:
                return "RGBA_4444";
            case GL_RGB565:
                return "RGB_565";
            case GL_RGB5_A1:
                return "RGBA_5551";
        }
        return "Unknown";
    }

private:
    GLenum mFormat;

    uint32_t mWidth;
    uint32_t mHeight;

    bool mAllocated;

    GLuint mName;
};  // struct RenderBuffer

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_RENDER_BUFFER_H
