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

#ifndef ANDROID_HWUI_PIXEL_BUFFER_H
#define ANDROID_HWUI_PIXEL_BUFFER_H

#include <GLES3/gl3.h>
#include <cutils/log.h>

namespace android {
namespace uirenderer {

/**
 * Represents a pixel buffer. A pixel buffer will be backed either by a
 * PBO on OpenGL ES 3.0 and higher or by an array of uint8_t on other
 * versions. If the buffer is backed by a PBO it will of type
 * GL_PIXEL_UNPACK_BUFFER.
 *
 * To read from or write into a PixelBuffer you must first map the
 * buffer using the map(AccessMode) method. This method returns a
 * pointer to the beginning of the buffer.
 *
 * Before the buffer can be used by the GPU, for instance to upload
 * a texture, you must first unmap the buffer. To do so, call the
 * unmap() method.
 *
 * Mapping and unmapping a PixelBuffer can have the side effect of
 * changing the currently active GL_PIXEL_UNPACK_BUFFER. It is
 * therefore recommended to call Caches::unbindPixelbuffer() after
 * using a PixelBuffer to upload to a texture.
 */
class PixelBuffer {
public:
    enum BufferType {
        kBufferType_Auto,
        kBufferType_CPU
    };

    enum AccessMode {
        kAccessMode_None = 0,
        kAccessMode_Read = GL_MAP_READ_BIT,
        kAccessMode_Write = GL_MAP_WRITE_BIT,
        kAccessMode_ReadWrite = GL_MAP_READ_BIT | GL_MAP_WRITE_BIT
    };

    /**
     * Creates a new PixelBuffer object with the specified format and
     * dimensions. The buffer is immediately allocated.
     *
     * The buffer type specifies how the buffer should be allocated.
     * By default this method will automatically choose whether to allocate
     * a CPU or GPU buffer.
     */
    static PixelBuffer* create(GLenum format, uint32_t width, uint32_t height,
            BufferType type = kBufferType_Auto);

    virtual ~PixelBuffer() {
    }

    /**
     * Returns the format of this render buffer.
     */
    GLenum getFormat() const {
        return mFormat;
    }

    /**
     * Maps this before with the specified access mode. This method
     * returns a pointer to the region of memory where the buffer was
     * mapped.
     *
     * If the buffer is already mapped when this method is invoked,
     * this method will return the previously mapped pointer. The
     * access mode can only be changed by calling unmap() first.
     *
     * The specified access mode cannot be kAccessMode_None.
     */
    virtual uint8_t* map(AccessMode mode = kAccessMode_ReadWrite) = 0;

    /**
     * Returns the current access mode for this buffer. If the buffer
     * is not mapped, this method returns kAccessMode_None.
     */
    AccessMode getAccessMode() const {
        return mAccessMode;
    }

    /**
     * Returns the currently mapped pointer. Returns NULL if the buffer
     * is not mapped.
     */
    virtual uint8_t* getMappedPointer() const = 0;

    /**
     * Upload the specified rectangle of this pixel buffer as a
     * GL_TEXTURE_2D texture. Calling this method will trigger
     * an unmap() if necessary.
     */
    virtual void upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height, int offset) = 0;

    /**
     * Upload the specified rectangle of this pixel buffer as a
     * GL_TEXTURE_2D texture. Calling this method will trigger
     * an unmap() if necessary.
     *
     * This is a convenience function provided to save callers the
     * trouble of computing the offset parameter.
     */
    void upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
        upload(x, y, width, height, getOffset(x, y));
    }

    /**
     * Returns the width of the render buffer in pixels.
     */
    uint32_t getWidth() const {
        return mWidth;
    }

    /**
     * Returns the height of the render buffer in pixels.
     */
    uint32_t getHeight() const {
        return mHeight;
    }

    /**
     * Returns the size of this pixel buffer in bytes.
     */
    uint32_t getSize() const {
        return mWidth * mHeight * formatSize(mFormat);
    }

    /**
     * Returns the offset of a pixel in this pixel buffer, in bytes.
     */
    uint32_t getOffset(uint32_t x, uint32_t y) const {
        return (y * mWidth + x) * formatSize(mFormat);
    }

    /**
     * Returns the number of bytes per pixel in the specified format.
     *
     * Supported formats:
     *      GL_ALPHA
     *      GL_RGBA
     */
    static uint32_t formatSize(GLenum format) {
        switch (format) {
            case GL_ALPHA:
                return 1;
            case GL_RGBA:
                return 4;
        }
        return 0;
    }

    /**
     * Returns the alpha channel offset in the specified format.
     *
     * Supported formats:
     *      GL_ALPHA
     *      GL_RGBA
     */
    static uint32_t formatAlphaOffset(GLenum format) {
        switch (format) {
            case GL_ALPHA:
                return 0;
            case GL_RGBA:
                return 3;
        }

        ALOGE("unsupported format: %d",format);
        return 0;
    }

protected:
    /**
     * Creates a new render buffer in the specified format and dimensions.
     * The format must be GL_ALPHA or GL_RGBA.
     */
    PixelBuffer(GLenum format, uint32_t width, uint32_t height):
            mFormat(format), mWidth(width), mHeight(height), mAccessMode(kAccessMode_None) {
    }

    /**
     * Unmaps this buffer, if needed. After the buffer is unmapped,
     * the pointer previously returned by map() becomes invalid and
     * should not be used. After calling this method, getMappedPointer()
     * will always return NULL.
     */
    virtual void unmap() = 0;

    GLenum mFormat;

    uint32_t mWidth;
    uint32_t mHeight;

    AccessMode mAccessMode;

}; // class PixelBuffer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PIXEL_BUFFER_H
