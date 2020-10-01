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

#include <gui/BufferQueue.h>

namespace android {

class HostBufferQueue : public IGraphicBufferProducer, public IGraphicBufferConsumer {
public:
    HostBufferQueue() : mWidth(0), mHeight(0) { }

    virtual status_t setConsumerIsProtected(bool isProtected) { return OK; }

    virtual status_t detachBuffer(int slot) { return OK; }

    virtual status_t getReleasedBuffers(uint64_t* slotMask) { return OK; }

    virtual status_t setDefaultBufferSize(uint32_t w, uint32_t h) {
        mWidth = w;
        mHeight = h;
        mBuffer = sp<GraphicBuffer>(new GraphicBuffer(mWidth, mHeight));
        return OK;
    }

    virtual status_t setDefaultBufferFormat(PixelFormat defaultFormat) { return OK; }

    virtual status_t setDefaultBufferDataSpace(android_dataspace defaultDataSpace) { return OK; }

    virtual status_t discardFreeBuffers() { return OK; }

    virtual status_t acquireBuffer(BufferItem* buffer, nsecs_t presentWhen,
                                       uint64_t maxFrameNumber = 0) {
        buffer->mGraphicBuffer = mBuffer;
        buffer->mSlot = 0;
        return OK;
    }

    virtual status_t setMaxAcquiredBufferCount(int maxAcquiredBuffers) { return OK; }

    virtual status_t setConsumerUsageBits(uint64_t usage) { return OK; }
private:
    sp<GraphicBuffer> mBuffer;
    uint32_t mWidth;
    uint32_t mHeight;
};

void BufferQueue::createBufferQueue(sp<IGraphicBufferProducer>* outProducer,
        sp<IGraphicBufferConsumer>* outConsumer) {

    sp<HostBufferQueue> obj(new HostBufferQueue());

    *outProducer = obj;
    *outConsumer = obj;
}

} // namespace android
