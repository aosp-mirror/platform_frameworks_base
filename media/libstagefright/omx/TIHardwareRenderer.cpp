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

#define LOG_TAG "TIHardwareRenderer"
#include <utils/Log.h>

#undef NDEBUG
#include <assert.h>

#include <media/stagefright/TIHardwareRenderer.h>
#include <ui/ISurface.h>
#include <ui/Overlay.h>

namespace android {

////////////////////////////////////////////////////////////////////////////////

TIHardwareRenderer::TIHardwareRenderer(
        const sp<ISurface> &surface,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight)
    : mISurface(surface),
      mDisplayWidth(displayWidth),
      mDisplayHeight(displayHeight),
      mDecodedWidth(decodedWidth),
      mDecodedHeight(decodedHeight),
      mFrameSize((mDecodedWidth * mDecodedHeight * 3) / 2) {
    assert(mISurface.get() != NULL);
    assert(mDecodedWidth > 0);
    assert(mDecodedHeight > 0);

    sp<OverlayRef> ref = mISurface->createOverlay(
            mDisplayWidth, mDisplayHeight, OVERLAY_FORMAT_CbYCrY_422_I);

    if (ref.get() == NULL) {
        LOGE("Unable to create the overlay!");
        return;
    }

    mOverlay = new Overlay(ref);

    for (size_t i = 0; i < mOverlay->getBufferCount(); ++i) {
        mOverlayAddresses.push(mOverlay->getBufferAddress((void *)i));
    }
    mIndex = mOverlayAddresses.size() - 1;
}

TIHardwareRenderer::~TIHardwareRenderer() {
    if (mOverlay.get() != NULL) {
        mOverlay->destroy();
        mOverlay.clear();

        // XXX apparently destroying an overlay is an asynchronous process...
        sleep(1);
    }
}

void TIHardwareRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    // assert(size == mFrameSize);

    if (mOverlay.get() == NULL) {
        return;
    }

#if 0
    overlay_buffer_t buffer;
    if (mOverlay->dequeueBuffer(&buffer) == OK) {
        void *addr = mOverlay->getBufferAddress(buffer);

        memcpy(addr, data, size);

        mOverlay->queueBuffer(buffer);
    }
#else
    memcpy(mOverlayAddresses[mIndex], data, size);
    mOverlay->queueBuffer((void *)mIndex);

    if (mIndex-- == 0) {
        mIndex = mOverlayAddresses.size() - 1;
    }
#endif
}

}  // namespace android

