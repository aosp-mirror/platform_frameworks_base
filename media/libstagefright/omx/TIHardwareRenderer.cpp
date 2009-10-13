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

#include "../include/TIHardwareRenderer.h"

#include <media/stagefright/MediaDebug.h>
#include <ui/ISurface.h>
#include <ui/Overlay.h>

#include "v4l2_utils.h"

#define CACHEABLE_BUFFERS 0x1

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
      mFrameSize(mDecodedWidth * mDecodedHeight * 2),
      mIsFirstFrame(true),
      mIndex(0) {
    CHECK(mISurface.get() != NULL);
    CHECK(mDecodedWidth > 0);
    CHECK(mDecodedHeight > 0);

    sp<OverlayRef> ref = mISurface->createOverlay(
            mDisplayWidth, mDisplayHeight, OVERLAY_FORMAT_CbYCrY_422_I);

    if (ref.get() == NULL) {
        LOGE("Unable to create the overlay!");
        return;
    }

    mOverlay = new Overlay(ref);
    mOverlay->setParameter(CACHEABLE_BUFFERS, 0);

    for (size_t i = 0; i < (size_t)mOverlay->getBufferCount(); ++i) {
        mapping_data_t *data =
            (mapping_data_t *)mOverlay->getBufferAddress((void *)i);

        mOverlayAddresses.push(data->ptr);
    }
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
    // CHECK_EQ(size, mFrameSize);

    if (mOverlay.get() == NULL) {
        return;
    }

#if 0
    size_t i = 0;
    for (; i < mOverlayAddresses.size(); ++i) {
        if (mOverlayAddresses[i] == data) {
            break;
        }

        if (mIsFirstFrame) {
            LOGI("overlay buffer #%d: %p", i, mOverlayAddresses[i]);
        }
    }

    if (i == mOverlayAddresses.size()) {
        LOGE("No suitable overlay buffer found.");
        return;
    }

    mOverlay->queueBuffer((void *)i);

    overlay_buffer_t overlay_buffer;
    if (!mIsFirstFrame) {
        CHECK_EQ(mOverlay->dequeueBuffer(&overlay_buffer), OK);
    } else {
        mIsFirstFrame = false;
    }
#else
    memcpy(mOverlayAddresses[mIndex], data, size);

    mOverlay->queueBuffer((void *)mIndex);

    if (++mIndex == mOverlayAddresses.size()) {
        mIndex = 0;
    }

    overlay_buffer_t overlay_buffer;
    if (!mIsFirstFrame) {
        CHECK_EQ(mOverlay->dequeueBuffer(&overlay_buffer), OK);
    } else {
        mIsFirstFrame = false;
    }
#endif
}

}  // namespace android

