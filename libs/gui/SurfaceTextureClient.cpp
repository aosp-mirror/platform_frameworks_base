/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "SurfaceTextureClient"
//#define LOG_NDEBUG 0

#include <gui/SurfaceTextureClient.h>

#include <utils/Log.h>

namespace android {

SurfaceTextureClient::SurfaceTextureClient(
        const sp<ISurfaceTexture>& surfaceTexture):
        mSurfaceTexture(surfaceTexture), mAllocator(0), mReqWidth(0),
        mReqHeight(0), mReqFormat(0), mReqUsage(0),
        mTimestamp(NATIVE_WINDOW_TIMESTAMP_AUTO),
        mQueryWidth(0), mQueryHeight(0), mQueryFormat(0),
        mMutex() {
    // Initialize the ANativeWindow function pointers.
    ANativeWindow::setSwapInterval  = setSwapInterval;
    ANativeWindow::dequeueBuffer    = dequeueBuffer;
    ANativeWindow::cancelBuffer     = cancelBuffer;
    ANativeWindow::lockBuffer       = lockBuffer;
    ANativeWindow::queueBuffer      = queueBuffer;
    ANativeWindow::query            = query;
    ANativeWindow::perform          = perform;

    const_cast<int&>(ANativeWindow::minSwapInterval) = 0;
    const_cast<int&>(ANativeWindow::maxSwapInterval) = 1;

    // Get a reference to the allocator.
    mAllocator = mSurfaceTexture->getAllocator();
}

sp<ISurfaceTexture> SurfaceTextureClient::getISurfaceTexture() const {
    return mSurfaceTexture;
}

int SurfaceTextureClient::setSwapInterval(ANativeWindow* window, int interval) {
    SurfaceTextureClient* c = getSelf(window);
    return c->setSwapInterval(interval);
}

int SurfaceTextureClient::dequeueBuffer(ANativeWindow* window,
        ANativeWindowBuffer** buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->dequeueBuffer(buffer);
}

int SurfaceTextureClient::cancelBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->cancelBuffer(buffer);
}

int SurfaceTextureClient::lockBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->lockBuffer(buffer);
}

int SurfaceTextureClient::queueBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->queueBuffer(buffer);
}

int SurfaceTextureClient::query(const ANativeWindow* window,
                                int what, int* value) {
    const SurfaceTextureClient* c = getSelf(window);
    return c->query(what, value);
}

int SurfaceTextureClient::perform(ANativeWindow* window, int operation, ...) {
    va_list args;
    va_start(args, operation);
    SurfaceTextureClient* c = getSelf(window);
    return c->perform(operation, args);
}

int SurfaceTextureClient::setSwapInterval(int interval) {
    // EGL specification states:
    //  interval is silently clamped to minimum and maximum implementation
    //  dependent values before being stored.
    // Although we don't have to, we apply the same logic here.

    if (interval < minSwapInterval)
        interval = minSwapInterval;

    if (interval > maxSwapInterval)
        interval = maxSwapInterval;

    status_t res = mSurfaceTexture->setSynchronousMode(interval ? true : false);

    return res;
}

int SurfaceTextureClient::dequeueBuffer(android_native_buffer_t** buffer) {
    LOGV("SurfaceTextureClient::dequeueBuffer");
    Mutex::Autolock lock(mMutex);
    int buf = -1;
    status_t result = mSurfaceTexture->dequeueBuffer(&buf, mReqWidth, mReqHeight,
            mReqFormat, mReqUsage);
    if (result < 0) {
        LOGV("dequeueBuffer: ISurfaceTexture::dequeueBuffer(%d, %d, %d, %d)"
             "failed: %d", mReqWidth, mReqHeight, mReqFormat, mReqUsage,
             result);
        return result;
    }
    sp<GraphicBuffer>& gbuf(mSlots[buf]);
    if (result & ISurfaceTexture::RELEASE_ALL_BUFFERS) {
        freeAllBuffers();
    }

    if ((result & ISurfaceTexture::BUFFER_NEEDS_REALLOCATION) || gbuf == 0) {
        gbuf = mSurfaceTexture->requestBuffer(buf);
        if (gbuf == 0) {
            LOGE("dequeueBuffer: ISurfaceTexture::requestBuffer failed");
            return NO_MEMORY;
        }
        mQueryWidth  = gbuf->width;
        mQueryHeight = gbuf->height;
        mQueryFormat = gbuf->format;
    }
    *buffer = gbuf.get();
    return OK;
}

int SurfaceTextureClient::cancelBuffer(android_native_buffer_t* buffer) {
    LOGV("SurfaceTextureClient::cancelBuffer");
    Mutex::Autolock lock(mMutex);
    int i = getSlotFromBufferLocked(buffer);
    if (i < 0) {
        return i;
    }
    mSurfaceTexture->cancelBuffer(i);
    return OK;
}

int SurfaceTextureClient::getSlotFromBufferLocked(
        android_native_buffer_t* buffer) const {
    bool dumpedState = false;
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        // XXX: Dump the slots whenever we hit a NULL entry while searching for
        // a buffer.
        if (mSlots[i] == NULL) {
            if (!dumpedState) {
                LOGD("getSlotFromBufferLocked: encountered NULL buffer in slot %d "
                        "looking for buffer %p", i, buffer->handle);
                for (int j = 0; j < NUM_BUFFER_SLOTS; j++) {
                    if (mSlots[j] == NULL) {
                        LOGD("getSlotFromBufferLocked:   %02d: NULL", j);
                    } else {
                        LOGD("getSlotFromBufferLocked:   %02d: %p", j, mSlots[j]->handle);
                    }
                }
                dumpedState = true;
            }
        }

        if (mSlots[i] != NULL && mSlots[i]->handle == buffer->handle) {
            return i;
        }
    }
    LOGE("getSlotFromBufferLocked: unknown buffer: %p", buffer->handle);
    return BAD_VALUE;
}

int SurfaceTextureClient::lockBuffer(android_native_buffer_t* buffer) {
    LOGV("SurfaceTextureClient::lockBuffer");
    Mutex::Autolock lock(mMutex);
    return OK;
}

int SurfaceTextureClient::queueBuffer(android_native_buffer_t* buffer) {
    LOGV("SurfaceTextureClient::queueBuffer");
    Mutex::Autolock lock(mMutex);
    int64_t timestamp;
    if (mTimestamp == NATIVE_WINDOW_TIMESTAMP_AUTO) {
        timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
        LOGV("SurfaceTextureClient::queueBuffer making up timestamp: %.2f ms",
             timestamp / 1000000.f);
    } else {
        timestamp = mTimestamp;
    }
    int i = getSlotFromBufferLocked(buffer);
    if (i < 0) {
        return i;
    }
    mSurfaceTexture->queueBuffer(i, timestamp);
    return OK;
}

int SurfaceTextureClient::query(int what, int* value) const {
    LOGV("SurfaceTextureClient::query");
    switch (what) {
    case NATIVE_WINDOW_FORMAT:
        if (mReqFormat) {
            *value = mReqFormat;
            return NO_ERROR;
        }
        break;
    case NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER:
        // TODO: this is not needed anymore
        *value = 0;
        return NO_ERROR;
    case NATIVE_WINDOW_CONCRETE_TYPE:
        // TODO: this is not needed anymore
        *value = NATIVE_WINDOW_SURFACE_TEXTURE_CLIENT;
        return NO_ERROR;
    }
    return mSurfaceTexture->query(what, value);
}

int SurfaceTextureClient::perform(int operation, va_list args)
{
    int res = NO_ERROR;
    switch (operation) {
    case NATIVE_WINDOW_CONNECT:
        res = dispatchConnect(args);
        break;
    case NATIVE_WINDOW_DISCONNECT:
        res = dispatchDisconnect(args);
        break;
    case NATIVE_WINDOW_SET_USAGE:
        res = dispatchSetUsage(args);
        break;
    case NATIVE_WINDOW_SET_CROP:
        res = dispatchSetCrop(args);
        break;
    case NATIVE_WINDOW_SET_BUFFER_COUNT:
        res = dispatchSetBufferCount(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_GEOMETRY:
        res = dispatchSetBuffersGeometry(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_TRANSFORM:
        res = dispatchSetBuffersTransform(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP:
        res = dispatchSetBuffersTimestamp(args);
        break;
    default:
        res = NAME_NOT_FOUND;
        break;
    }
    return res;
}

int SurfaceTextureClient::dispatchConnect(va_list args) {
    int api = va_arg(args, int);
    return connect(api);
}

int SurfaceTextureClient::dispatchDisconnect(va_list args) {
    int api = va_arg(args, int);
    return disconnect(api);
}

int SurfaceTextureClient::dispatchSetUsage(va_list args) {
    int usage = va_arg(args, int);
    return setUsage(usage);
}

int SurfaceTextureClient::dispatchSetCrop(va_list args) {
    android_native_rect_t const* rect = va_arg(args, android_native_rect_t*);
    return setCrop(reinterpret_cast<Rect const*>(rect));
}

int SurfaceTextureClient::dispatchSetBufferCount(va_list args) {
    size_t bufferCount = va_arg(args, size_t);
    return setBufferCount(bufferCount);
}

int SurfaceTextureClient::dispatchSetBuffersGeometry(va_list args) {
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    int f = va_arg(args, int);
    return setBuffersGeometry(w, h, f);
}

int SurfaceTextureClient::dispatchSetBuffersTransform(va_list args) {
    int transform = va_arg(args, int);
    return setBuffersTransform(transform);
}

int SurfaceTextureClient::dispatchSetBuffersTimestamp(va_list args) {
    int64_t timestamp = va_arg(args, int64_t);
    return setBuffersTimestamp(timestamp);
}

int SurfaceTextureClient::connect(int api) {
    LOGV("SurfaceTextureClient::connect");
    Mutex::Autolock lock(mMutex);
    return mSurfaceTexture->connect(api);
}

int SurfaceTextureClient::disconnect(int api) {
    LOGV("SurfaceTextureClient::disconnect");
    Mutex::Autolock lock(mMutex);
    return mSurfaceTexture->disconnect(api);
}

int SurfaceTextureClient::getConnectedApi() const
{
    // XXX: This method will be going away shortly, and is currently bogus.  It
    // always returns "nothing is connected".  It will go away once Surface gets
    // updated to actually connect as the 'CPU' API when locking a buffer.
    Mutex::Autolock lock(mMutex);
    return 0;
}


int SurfaceTextureClient::setUsage(uint32_t reqUsage)
{
    LOGV("SurfaceTextureClient::setUsage");
    Mutex::Autolock lock(mMutex);
    mReqUsage = reqUsage;
    return OK;
}

int SurfaceTextureClient::setCrop(Rect const* rect)
{
    LOGV("SurfaceTextureClient::setCrop");
    Mutex::Autolock lock(mMutex);

    Rect realRect;
    if (rect == NULL || rect->isEmpty()) {
        realRect = Rect(0, 0);
    } else {
        realRect = *rect;
    }

    status_t err = mSurfaceTexture->setCrop(*rect);
    LOGE_IF(err, "ISurfaceTexture::setCrop(...) returned %s", strerror(-err));

    return err;
}

int SurfaceTextureClient::setBufferCount(int bufferCount)
{
    LOGV("SurfaceTextureClient::setBufferCount");
    Mutex::Autolock lock(mMutex);

    status_t err = mSurfaceTexture->setBufferCount(bufferCount);
    LOGE_IF(err, "ISurfaceTexture::setBufferCount(%d) returned %s",
            bufferCount, strerror(-err));

    if (err == NO_ERROR) {
        freeAllBuffers();
    }

    return err;
}

int SurfaceTextureClient::setBuffersGeometry(int w, int h, int format)
{
    LOGV("SurfaceTextureClient::setBuffersGeometry");
    Mutex::Autolock lock(mMutex);

    if (w<0 || h<0 || format<0)
        return BAD_VALUE;

    if ((w && !h) || (!w && h))
        return BAD_VALUE;

    mReqWidth = w;
    mReqHeight = h;
    mReqFormat = format;

    status_t err = mSurfaceTexture->setCrop(Rect(0, 0));
    LOGE_IF(err, "ISurfaceTexture::setCrop(...) returned %s", strerror(-err));

    return err;
}

int SurfaceTextureClient::setBuffersTransform(int transform)
{
    LOGV("SurfaceTextureClient::setBuffersTransform");
    Mutex::Autolock lock(mMutex);
    status_t err = mSurfaceTexture->setTransform(transform);
    return err;
}

int SurfaceTextureClient::setBuffersTimestamp(int64_t timestamp)
{
    LOGV("SurfaceTextureClient::setBuffersTimestamp");
    Mutex::Autolock lock(mMutex);
    mTimestamp = timestamp;
    return NO_ERROR;
}

void SurfaceTextureClient::freeAllBuffers() {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i] = 0;
    }
}

}; // namespace android
