/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <ui/PixelFormat.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

namespace android {

class BufferItem;

class Fence;

class GraphicBuffer;

class IGraphicBufferConsumer : virtual public RefBase {
public:
    enum {
        // Returned by releaseBuffer, after which the consumer must free any references to the
        // just-released buffer that it might have.
        STALE_BUFFER_SLOT = 1,
        // Returned by dequeueBuffer if there are no pending buffers available.
        NO_BUFFER_AVAILABLE,
        // Returned by dequeueBuffer if it's too early for the buffer to be acquired.
        PRESENT_LATER,
    };

    virtual status_t acquireBuffer(BufferItem* buffer, nsecs_t presentWhen,
                                   uint64_t maxFrameNumber = 0) = 0;

    virtual status_t detachBuffer(int slot) = 0;

    virtual status_t getReleasedBuffers(uint64_t* slotMask) = 0;

    virtual status_t setDefaultBufferSize(uint32_t w, uint32_t h) = 0;

    virtual status_t setMaxAcquiredBufferCount(int maxAcquiredBuffers) = 0;

    virtual status_t setDefaultBufferFormat(PixelFormat defaultFormat) = 0;

    virtual status_t setDefaultBufferDataSpace(android_dataspace defaultDataSpace) = 0;

    virtual status_t setConsumerUsageBits(uint64_t usage) = 0;

    virtual status_t setConsumerIsProtected(bool isProtected) = 0;

    virtual status_t discardFreeBuffers() = 0;
};

} // namespace android
