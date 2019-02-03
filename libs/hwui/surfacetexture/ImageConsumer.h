/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <gui/BufferQueueDefs.h>

#include <SkImage.h>
#include <cutils/compiler.h>
#include <gui/BufferItem.h>
#include <system/graphics.h>

namespace android {

namespace uirenderer {
class RenderState;
}

class SurfaceTexture;

/*
 * ImageConsumer implements the parts of SurfaceTexture that deal with
 * images consumed by HWUI view system.
 */
class ImageConsumer {
public:
    sk_sp<SkImage> dequeueImage(bool* queueEmpty, SurfaceTexture& cb,
                                uirenderer::RenderState& renderState);

    /**
     * onAcquireBufferLocked amends the ConsumerBase method to update the
     * mImageSlots array in addition to the ConsumerBase behavior.
     */
    void onAcquireBufferLocked(BufferItem* item);

    /**
     * onReleaseBufferLocked amends the ConsumerBase method to update the
     * mImageSlots array in addition to the ConsumerBase.
     */
    void onReleaseBufferLocked(int slot);

    /**
     * onFreeBufferLocked frees up the given buffer slot. If the slot has been
     * initialized this will release the reference to the GraphicBuffer in that
     * slot and destroy the SkImage in that slot. Otherwise it has no effect.
     */
    void onFreeBufferLocked(int slotIndex);

private:
    /**
     * ImageSlot contains the information and object references that
     * ImageConsumer maintains about a BufferQueue buffer slot.
     */
    struct ImageSlot {
        ImageSlot() : mDataspace(HAL_DATASPACE_UNKNOWN), mEglFence(EGL_NO_SYNC_KHR) {}

        // mImage is the SkImage created from mGraphicBuffer.
        sk_sp<SkImage> mImage;

        // the dataspace associated with the current image
        android_dataspace mDataspace;

        /**
         * mEglFence is the EGL sync object that must signal before the buffer
         * associated with this buffer slot may be dequeued.
         */
        EGLSyncKHR mEglFence;

        void createIfNeeded(sp<GraphicBuffer> graphicBuffer, android_dataspace dataspace,
                            bool forceCreate);
    };

    /**
     * ImageConsumer stores the SkImages that have been allocated by the BufferQueue
     * for each buffer slot.  It is initialized to null pointers, and gets
     * filled in with the result of BufferQueue::acquire when the
     * client dequeues a buffer from a
     * slot that has not yet been used. The buffer allocated to a slot will also
     * be replaced if the requested buffer usage or geometry differs from that
     * of the buffer allocated to a slot.
     */
    ImageSlot mImageSlots[BufferQueueDefs::NUM_BUFFER_SLOTS];
};

} /* namespace android */
