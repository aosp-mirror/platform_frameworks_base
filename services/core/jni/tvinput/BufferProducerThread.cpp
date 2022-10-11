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

#include "BufferProducerThread.h"

namespace android {

BufferProducerThread::BufferProducerThread(tv_input_device_t* device, int deviceId,
                                           const tv_stream_t* stream)
      : Thread(false),
        mDevice(device),
        mDeviceId(deviceId),
        mBuffer(NULL),
        mBufferState(RELEASED),
        mSeq(0u),
        mShutdown(false) {
    memcpy(&mStream, stream, sizeof(mStream));
}

status_t BufferProducerThread::readyToRun() {
    sp<ANativeWindow> anw(mSurface);
    status_t err = native_window_set_usage(anw.get(), mStream.buffer_producer.usage);
    if (err != NO_ERROR) {
        return err;
    }
    err = native_window_set_buffers_dimensions(anw.get(), mStream.buffer_producer.width,
                                               mStream.buffer_producer.height);
    if (err != NO_ERROR) {
        return err;
    }
    err = native_window_set_buffers_format(anw.get(), mStream.buffer_producer.format);
    if (err != NO_ERROR) {
        return err;
    }
    return NO_ERROR;
}

void BufferProducerThread::setSurface(const sp<Surface>& surface) {
    Mutex::Autolock autoLock(&mLock);
    setSurfaceLocked(surface);
}

void BufferProducerThread::setSurfaceLocked(const sp<Surface>& surface) {
    if (surface == mSurface) {
        return;
    }

    if (mBufferState == CAPTURING) {
        mDevice->cancel_capture(mDevice, mDeviceId, mStream.stream_id, mSeq);
    }
    while (mBufferState == CAPTURING) {
        status_t err = mCondition.waitRelative(mLock, s2ns(1));
        if (err != NO_ERROR) {
            ALOGE("error %d while wating for buffer state to change.", err);
            break;
        }
    }
    mBuffer.clear();
    mBufferState = RELEASED;

    mSurface = surface;
    mCondition.broadcast();
}

void BufferProducerThread::onCaptured(uint32_t seq, bool succeeded) {
    Mutex::Autolock autoLock(&mLock);
    if (seq != mSeq) {
        ALOGW("Incorrect sequence value: expected %u actual %u", mSeq, seq);
    }
    if (mBufferState != CAPTURING) {
        ALOGW("mBufferState != CAPTURING : instead %d", mBufferState);
    }
    if (succeeded) {
        mBufferState = CAPTURED;
    } else {
        mBuffer.clear();
        mBufferState = RELEASED;
    }
    mCondition.broadcast();
}

void BufferProducerThread::shutdown() {
    Mutex::Autolock autoLock(&mLock);
    mShutdown = true;
    setSurfaceLocked(NULL);
    requestExitAndWait();
}

bool BufferProducerThread::threadLoop() {
    Mutex::Autolock autoLock(&mLock);

    status_t err = NO_ERROR;
    if (mSurface == NULL) {
        err = mCondition.waitRelative(mLock, s2ns(1));
        // It's OK to time out here.
        if (err != NO_ERROR && err != TIMED_OUT) {
            ALOGE("error %d while wating for non-null surface to be set", err);
            return false;
        }
        return true;
    }
    sp<ANativeWindow> anw(mSurface);
    while (mBufferState == CAPTURING) {
        err = mCondition.waitRelative(mLock, s2ns(1));
        if (err != NO_ERROR) {
            ALOGE("error %d while wating for buffer state to change.", err);
            return false;
        }
    }
    if (mBufferState == CAPTURED && anw != NULL) {
        err = anw->queueBuffer(anw.get(), mBuffer.get(), -1);
        if (err != NO_ERROR) {
            ALOGE("error %d while queueing buffer to surface", err);
            return false;
        }
        mBuffer.clear();
        mBufferState = RELEASED;
    }
    if (mBuffer == NULL && !mShutdown && anw != NULL) {
        ANativeWindowBuffer_t* buffer = NULL;
        err = native_window_dequeue_buffer_and_wait(anw.get(), &buffer);
        if (err != NO_ERROR) {
            ALOGE("error %d while dequeueing buffer to surface", err);
            return false;
        }
        mBuffer = buffer;
        mBufferState = CAPTURING;
        mDevice->request_capture(mDevice, mDeviceId, mStream.stream_id, buffer->handle, ++mSeq);
    }

    return true;
}

} // namespace android
