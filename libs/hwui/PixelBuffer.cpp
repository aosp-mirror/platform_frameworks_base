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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>

#include "Caches.h"
#include "Debug.h"
#include "Extensions.h"
#include "PixelBuffer.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// CPU pixel buffer
///////////////////////////////////////////////////////////////////////////////

class CpuPixelBuffer: public PixelBuffer {
public:
    CpuPixelBuffer(GLenum format, uint32_t width, uint32_t height);
    ~CpuPixelBuffer();

    uint8_t* map(AccessMode mode = kAccessMode_ReadWrite);
    void unmap();

    uint8_t* getMappedPointer() const;

    void upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height, int offset);

private:
    uint8_t* mBuffer;
};

CpuPixelBuffer::CpuPixelBuffer(GLenum format, uint32_t width, uint32_t height):
        PixelBuffer(format, width, height) {
    mBuffer = new uint8_t[width * height * formatSize(format)];
}

CpuPixelBuffer::~CpuPixelBuffer() {
    delete[] mBuffer;
}

uint8_t* CpuPixelBuffer::map(AccessMode mode) {
    if (mAccessMode == kAccessMode_None) {
        mAccessMode = mode;
    }
    return mBuffer;
}

void CpuPixelBuffer::unmap() {
    mAccessMode = kAccessMode_None;
}

uint8_t* CpuPixelBuffer::getMappedPointer() const {
    return mAccessMode == kAccessMode_None ? NULL : mBuffer;
}

void CpuPixelBuffer::upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height, int offset) {
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
            mFormat, GL_UNSIGNED_BYTE, mBuffer + offset);
}

///////////////////////////////////////////////////////////////////////////////
// GPU pixel buffer
///////////////////////////////////////////////////////////////////////////////

class GpuPixelBuffer: public PixelBuffer {
public:
    GpuPixelBuffer(GLenum format, uint32_t width, uint32_t height);
    ~GpuPixelBuffer();

    uint8_t* map(AccessMode mode = kAccessMode_ReadWrite);
    void unmap();

    uint8_t* getMappedPointer() const;

    void upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height, int offset);

private:
    GLuint mBuffer;
    uint8_t* mMappedPointer;
    Caches& mCaches;
};

GpuPixelBuffer::GpuPixelBuffer(GLenum format, uint32_t width, uint32_t height):
        PixelBuffer(format, width, height), mMappedPointer(0), mCaches(Caches::getInstance()) {
    glGenBuffers(1, &mBuffer);
    mCaches.bindPixelBuffer(mBuffer);
    glBufferData(GL_PIXEL_UNPACK_BUFFER, getSize(), NULL, GL_DYNAMIC_DRAW);
    mCaches.unbindPixelBuffer();
}

GpuPixelBuffer::~GpuPixelBuffer() {
    glDeleteBuffers(1, &mBuffer);
}

uint8_t* GpuPixelBuffer::map(AccessMode mode) {
    if (mAccessMode == kAccessMode_None) {
        mCaches.bindPixelBuffer(mBuffer);
        mMappedPointer = (uint8_t*) glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, getSize(), mode);
#if DEBUG_OPENGL
        if (!mMappedPointer) {
            GLenum status = GL_NO_ERROR;
            while ((status = glGetError()) != GL_NO_ERROR) {
                ALOGE("Could not map GPU pixel buffer: 0x%x", status);
            }
        }
#endif
        mAccessMode = mode;
    }

    return mMappedPointer;
}

void GpuPixelBuffer::unmap() {
    if (mAccessMode != kAccessMode_None) {
        if (mMappedPointer) {
            mCaches.bindPixelBuffer(mBuffer);
            GLboolean status = glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
            if (status == GL_FALSE) {
                ALOGE("Corrupted GPU pixel buffer");
            }
        }
        mAccessMode = kAccessMode_None;
        mMappedPointer = NULL;
    }
}

uint8_t* GpuPixelBuffer::getMappedPointer() const {
    return mMappedPointer;
}

void GpuPixelBuffer::upload(uint32_t x, uint32_t y, uint32_t width, uint32_t height, int offset) {
    // If the buffer is not mapped, unmap() will not bind it
    mCaches.bindPixelBuffer(mBuffer);
    unmap();
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, mFormat,
            GL_UNSIGNED_BYTE, (void*) offset);
}

///////////////////////////////////////////////////////////////////////////////
// Factory
///////////////////////////////////////////////////////////////////////////////

PixelBuffer* PixelBuffer::create(GLenum format, uint32_t width, uint32_t height, BufferType type) {
    if (type == kBufferType_Auto && Caches::getInstance().gpuPixelBuffersEnabled) {
        return new GpuPixelBuffer(format, width, height);
    }
    return new CpuPixelBuffer(format, width, height);
}

}; // namespace uirenderer
}; // namespace android
