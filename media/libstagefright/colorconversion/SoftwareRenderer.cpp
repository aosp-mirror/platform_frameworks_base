/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "SoftwareRenderer"
#include <utils/Log.h>

#include "../include/SoftwareRenderer.h"

#include <binder/MemoryHeapBase.h>
#include <binder/MemoryHeapPmem.h>
#include <media/stagefright/MediaDebug.h>
#include <surfaceflinger/ISurface.h>

namespace android {

SoftwareRenderer::SoftwareRenderer(
        OMX_COLOR_FORMATTYPE colorFormat,
        const sp<ISurface> &surface,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight,
        int32_t rotationDegrees)
    : mInitCheck(NO_INIT),
      mColorFormat(colorFormat),
      mConverter(colorFormat, OMX_COLOR_Format16bitRGB565),
      mISurface(surface),
      mDisplayWidth(displayWidth),
      mDisplayHeight(displayHeight),
      mDecodedWidth(decodedWidth),
      mDecodedHeight(decodedHeight),
      mFrameSize(mDecodedWidth * mDecodedHeight * 2),  // RGB565
      mIndex(0) {
    mMemoryHeap = new MemoryHeapBase("/dev/pmem_adsp", 2 * mFrameSize);
    if (mMemoryHeap->heapID() < 0) {
        LOGI("Creating physical memory heap failed, reverting to regular heap.");
        mMemoryHeap = new MemoryHeapBase(2 * mFrameSize);
    } else {
        sp<MemoryHeapPmem> pmemHeap = new MemoryHeapPmem(mMemoryHeap);
        pmemHeap->slap();
        mMemoryHeap = pmemHeap;
    }

    CHECK(mISurface.get() != NULL);
    CHECK(mDecodedWidth > 0);
    CHECK(mDecodedHeight > 0);
    CHECK(mMemoryHeap->heapID() >= 0);
    CHECK(mConverter.isValid());

    uint32_t orientation;
    switch (rotationDegrees) {
        case 0: orientation = ISurface::BufferHeap::ROT_0; break;
        case 90: orientation = ISurface::BufferHeap::ROT_90; break;
        case 180: orientation = ISurface::BufferHeap::ROT_180; break;
        case 270: orientation = ISurface::BufferHeap::ROT_270; break;
        default: orientation = ISurface::BufferHeap::ROT_0; break;
    }

    ISurface::BufferHeap bufferHeap(
            mDisplayWidth, mDisplayHeight,
            mDecodedWidth, mDecodedHeight,
            PIXEL_FORMAT_RGB_565,
            orientation, 0,
            mMemoryHeap);

    status_t err = mISurface->registerBuffers(bufferHeap);

    if (err != OK) {
        LOGW("ISurface failed to register buffers (0x%08x)", err);
    }

    mInitCheck = err;
}

SoftwareRenderer::~SoftwareRenderer() {
    mISurface->unregisterBuffers();
}

status_t SoftwareRenderer::initCheck() const {
    return mInitCheck;
}

void SoftwareRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    if (mInitCheck != OK) {
        return;
    }

    size_t offset = mIndex * mFrameSize;
    void *dst = (uint8_t *)mMemoryHeap->getBase() + offset;

    mConverter.convert(
            mDecodedWidth, mDecodedHeight,
            data, 0, dst, 2 * mDecodedWidth);

    mISurface->postBuffer(offset);
    mIndex = 1 - mIndex;
}

}  // namespace android
