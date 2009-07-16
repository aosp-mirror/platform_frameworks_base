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

#undef NDEBUG
#include <assert.h>

#include <binder/MemoryHeapBase.h>
#include <media/stagefright/SoftwareRenderer.h>
#include <ui/ISurface.h>

namespace android {

#define QCOM_YUV        0

SoftwareRenderer::SoftwareRenderer(
        const sp<ISurface> &surface,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight)
    : mISurface(surface),
      mDisplayWidth(displayWidth),
      mDisplayHeight(displayHeight),
      mDecodedWidth(decodedWidth),
      mDecodedHeight(decodedHeight),
      mFrameSize(mDecodedWidth * mDecodedHeight * 2),  // RGB565
      mMemoryHeap(new MemoryHeapBase(2 * mFrameSize)),
      mIndex(0) {
    assert(mISurface.get() != NULL);
    assert(mDecodedWidth > 0);
    assert(mDecodedHeight > 0);
    assert(mMemoryHeap->heapID() >= 0);

    ISurface::BufferHeap bufferHeap(
            mDisplayWidth, mDisplayHeight,
            mDecodedWidth, mDecodedHeight,
            PIXEL_FORMAT_RGB_565,
            mMemoryHeap);

    status_t err = mISurface->registerBuffers(bufferHeap);
    assert(err == OK);
}

SoftwareRenderer::~SoftwareRenderer() {
    mISurface->unregisterBuffers();
}

void SoftwareRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    assert(size >= (mDecodedWidth * mDecodedHeight * 3) / 2);

    static const signed kClipMin = -278;
    static const signed kClipMax = 535;
    static uint8_t kClip[kClipMax - kClipMin + 1];
    static uint8_t *kAdjustedClip = &kClip[-kClipMin];

    static bool clipInitialized = false;

    if (!clipInitialized) {
        for (signed i = kClipMin; i <= kClipMax; ++i) {
            kClip[i - kClipMin] = (i < 0) ? 0 : (i > 255) ? 255 : (uint8_t)i;
        }
        clipInitialized = true;
    }

    size_t offset = mIndex * mFrameSize;

    void *dst = (uint8_t *)mMemoryHeap->getBase() + offset;

    uint32_t *dst_ptr = (uint32_t *)dst;

    const uint8_t *src_y = (const uint8_t *)data;

    const uint8_t *src_u =
        (const uint8_t *)src_y + mDecodedWidth * mDecodedHeight;

#if !QCOM_YUV
    const uint8_t *src_v =
        (const uint8_t *)src_u + (mDecodedWidth / 2) * (mDecodedHeight / 2);
#endif

    for (size_t y = 0; y < mDecodedHeight; ++y) {
        for (size_t x = 0; x < mDecodedWidth; x += 2) {
            // B = 1.164 * (Y - 16) + 2.018 * (U - 128)
            // G = 1.164 * (Y - 16) - 0.813 * (V - 128) - 0.391 * (U - 128)
            // R = 1.164 * (Y - 16) + 1.596 * (V - 128)

            // B = 298/256 * (Y - 16) + 517/256 * (U - 128)
            // G = .................. - 208/256 * (V - 128) - 100/256 * (U - 128)
            // R = .................. + 409/256 * (V - 128)

            // min_B = (298 * (- 16) + 517 * (- 128)) / 256 = -277
            // min_G = (298 * (- 16) - 208 * (255 - 128) - 100 * (255 - 128)) / 256 = -172
            // min_R = (298 * (- 16) + 409 * (- 128)) / 256 = -223

            // max_B = (298 * (255 - 16) + 517 * (255 - 128)) / 256 = 534
            // max_G = (298 * (255 - 16) - 208 * (- 128) - 100 * (- 128)) / 256 = 432
            // max_R = (298 * (255 - 16) + 409 * (255 - 128)) / 256 = 481

            // clip range -278 .. 535

            signed y1 = (signed)src_y[x] - 16;
            signed y2 = (signed)src_y[x + 1] - 16;

#if QCOM_YUV
            signed u = (signed)src_u[x & ~1] - 128;
            signed v = (signed)src_u[(x & ~1) + 1] - 128;
#else
            signed u = (signed)src_u[x / 2] - 128;
            signed v = (signed)src_v[x / 2] - 128;
#endif

            signed u_b = u * 517;
            signed u_g = -u * 100;
            signed v_g = -v * 208;
            signed v_r = v * 409;

            signed tmp1 = y1 * 298;
            signed b1 = (tmp1 + u_b) / 256;
            signed g1 = (tmp1 + v_g + u_g) / 256;
            signed r1 = (tmp1 + v_r) / 256;

            signed tmp2 = y2 * 298;
            signed b2 = (tmp2 + u_b) / 256;
            signed g2 = (tmp2 + v_g + u_g) / 256;
            signed r2 = (tmp2 + v_r) / 256;

            uint32_t rgb1 =
                ((kAdjustedClip[r1] >> 3) << 11)
                | ((kAdjustedClip[g1] >> 2) << 5)
                | (kAdjustedClip[b1] >> 3);

            uint32_t rgb2 =
                ((kAdjustedClip[r2] >> 3) << 11)
                | ((kAdjustedClip[g2] >> 2) << 5)
                | (kAdjustedClip[b2] >> 3);

            dst_ptr[x / 2] = (rgb2 << 16) | rgb1;
        }

        src_y += mDecodedWidth;

        if (y & 1) {
#if QCOM_YUV
            src_u += mDecodedWidth;
#else
            src_u += mDecodedWidth / 2;
            src_v += mDecodedWidth / 2;
#endif
        }

        dst_ptr += mDecodedWidth / 2;
    }

    mISurface->postBuffer(offset);
    mIndex = 1 - mIndex;
}

}  // namespace android
