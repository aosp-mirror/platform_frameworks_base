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

#define LOG_TAG "SurfaceRenderer"
#include <utils/Log.h>

#undef NDEBUG
#include <assert.h>

#include <media/stagefright/SurfaceRenderer.h>
#include <ui/Surface.h>

namespace android {

SurfaceRenderer::SurfaceRenderer(
        const sp<Surface> &surface,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight)
    : mSurface(surface),
      mDisplayWidth(displayWidth),
      mDisplayHeight(displayHeight),
      mDecodedWidth(decodedWidth),
      mDecodedHeight(decodedHeight) {
}

SurfaceRenderer::~SurfaceRenderer() {
}

void SurfaceRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    Surface::SurfaceInfo info;
    status_t err = mSurface->lock(&info);
    if (err != OK) {
        return;
    }

    const uint8_t *src = (const uint8_t *)data;
    uint8_t *dst = (uint8_t *)info.bits;

    for (size_t i = 0; i < mDisplayHeight; ++i) {
        memcpy(dst, src, mDisplayWidth);
        src += mDecodedWidth;
        dst += mDisplayWidth;
    }
    src += (mDecodedHeight - mDisplayHeight) * mDecodedWidth;
    
    for (size_t i = 0; i < (mDisplayHeight + 1) / 2; ++i) {
        memcpy(dst, src, (mDisplayWidth + 1) & ~1);
        src += (mDecodedWidth + 1) & ~1;
        dst += (mDisplayWidth + 1) & ~1;
    }

    mSurface->unlockAndPost();
}

}  // namespace android
