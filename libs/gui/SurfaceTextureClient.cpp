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
        mSurfaceTexture(surfaceTexture), mAllocator(0), mReqWidth(1),
        mReqHeight(1), mReqFormat(DEFAULT_FORMAT), mReqUsage(0), mMutex() {
    // Initialize the ANativeWindow function pointers.
    ANativeWindow::setSwapInterval  = setSwapInterval;
    ANativeWindow::dequeueBuffer    = dequeueBuffer;
    ANativeWindow::cancelBuffer     = cancelBuffer;
    ANativeWindow::lockBuffer       = lockBuffer;
    ANativeWindow::queueBuffer      = queueBuffer;
    ANativeWindow::query            = query;
    ANativeWindow::perform          = perform;

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
        android_native_buffer_t** buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->dequeueBuffer(buffer);
}

int SurfaceTextureClient::cancelBuffer(ANativeWindow* window,
        android_native_buffer_t* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->cancelBuffer(buffer);
}

int SurfaceTextureClient::lockBuffer(ANativeWindow* window,
        android_native_buffer_t* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->lockBuffer(buffer);
}

int SurfaceTextureClient::queueBuffer(ANativeWindow* window,
        android_native_buffer_t* buffer) {
    SurfaceTextureClient* c = getSelf(window);
    return c->queueBuffer(buffer);
}

int SurfaceTextureClient::query(ANativeWindow* window, int what, int* value) {
    SurfaceTextureClient* c = getSelf(window);
    return c->query(what, value);
}

int SurfaceTextureClient::perform(ANativeWindow* window, int operation, ...) {
    va_list args;
    va_start(args, operation);
    SurfaceTextureClient* c = getSelf(window);
    return c->perform(operation, args);
}

int SurfaceTextureClient::setSwapInterval(int interval) {
    return INVALID_OPERATION;
}

int SurfaceTextureClient::dequeueBuffer(android_native_buffer_t** buffer) {
    LOGV("SurfaceTextureClient::dequeueBuffer");
    Mutex::Autolock lock(mMutex);
    int buf = -1;
    status_t err = mSurfaceTexture->dequeueBuffer(&buf);
    if (err < 0) {
        LOGV("dequeueBuffer: ISurfaceTexture::dequeueBuffer failed: %d", err);
        return err;
    }
    sp<GraphicBuffer>& gbuf(mSlots[buf]);
    if (gbuf == 0 || gbuf->getWidth() != mReqWidth ||
        gbuf->getHeight() != mReqHeight ||
        uint32_t(gbuf->getPixelFormat()) != mReqFormat ||
        (gbuf->getUsage() & mReqUsage) != mReqUsage) {
        gbuf = mSurfaceTexture->requestBuffer(buf, mReqWidth, mReqHeight,
                mReqFormat, mReqUsage);
        if (gbuf == 0) {
            LOGE("dequeueBuffer: ISurfaceTexture::requestBuffer failed");
            return NO_MEMORY;
        }
    }
    *buffer = gbuf.get();
    return OK;
}

int SurfaceTextureClient::cancelBuffer(android_native_buffer_t* buffer) {
    LOGV("SurfaceTextureClient::cancelBuffer");
    Mutex::Autolock lock(mMutex);
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        if (mSlots[i]->handle == buffer->handle) {
            mSurfaceTexture->cancelBuffer(i);
            return OK;
        }
    }
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
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        if (mSlots[i]->handle == buffer->handle) {
            return mSurfaceTexture->queueBuffer(i);
        }
    }
    LOGE("queueBuffer: unknown buffer queued");
    return BAD_VALUE;
}

int SurfaceTextureClient::query(int what, int* value) {
    LOGV("SurfaceTextureClient::query");
    Mutex::Autolock lock(mMutex);
    switch (what) {
    case NATIVE_WINDOW_WIDTH:
    case NATIVE_WINDOW_HEIGHT:
        // XXX: How should SurfaceTexture behave if setBuffersGeometry didn't
        // override the size?
        *value = 0;
        return NO_ERROR;
    case NATIVE_WINDOW_FORMAT:
        *value = DEFAULT_FORMAT;
        return NO_ERROR;
    case NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS:
        *value = MIN_UNDEQUEUED_BUFFERS;
        return NO_ERROR;
    case NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER:
        // SurfaceTextureClient currently never queues frames to SurfaceFlinger.
        *value = 0;
        return NO_ERROR;
    case NATIVE_WINDOW_CONCRETE_TYPE:
        *value = NATIVE_WINDOW_SURFACE_TEXTURE_CLIENT;
        return NO_ERROR;
    }
    return BAD_VALUE;
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

int SurfaceTextureClient::connect(int api) {
    LOGV("SurfaceTextureClient::connect");
    // XXX: Implement this!
    return INVALID_OPERATION;
}

int SurfaceTextureClient::disconnect(int api) {
    LOGV("SurfaceTextureClient::disconnect");
    // XXX: Implement this!
    return INVALID_OPERATION;
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

void SurfaceTextureClient::freeAllBuffers() {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i] = 0;
    }
}

}; // namespace android
