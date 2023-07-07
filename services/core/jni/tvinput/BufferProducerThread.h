/*
 * Copyright 2022 The Android Open Source Project
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

#include <gui/Surface.h>
#include <hardware/tv_input.h>
#include <utils/Thread.h>

namespace android {

class BufferProducerThread : public Thread {
public:
    BufferProducerThread(tv_input_device_t* device, int deviceId, const tv_stream_t* stream);

    virtual status_t readyToRun();

    void setSurface(const sp<Surface>& surface);
    void onCaptured(uint32_t seq, bool succeeded);
    void shutdown();

private:
    Mutex mLock;
    Condition mCondition;
    sp<Surface> mSurface;
    tv_input_device_t* mDevice;
    int mDeviceId;
    tv_stream_t mStream;
    sp<ANativeWindowBuffer_t> mBuffer;
    enum {
        CAPTURING,
        CAPTURED,
        RELEASED,
    } mBufferState;
    uint32_t mSeq;
    bool mShutdown;

    virtual bool threadLoop();

    void setSurfaceLocked(const sp<Surface>& surface);
};

} // namespace android
