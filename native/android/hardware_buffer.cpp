/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "AHardwareBuffer"

#include <android/hardware_buffer.h>

#include <errno.h>
#include <sys/socket.h>

#include <memory>

#include <cutils/native_handle.h>

#include <utils/Log.h>

#include <ui/GraphicBuffer.h>

#include <binder/IServiceManager.h>
#include <gui/ISurfaceComposer.h>
#include <gui/IGraphicBufferAlloc.h>

#include <android_runtime/android_hardware_HardwareBuffer.h>


static constexpr int kDataBufferSize = 64 * sizeof(int);  // 64 ints

using namespace android;

static inline const GraphicBuffer* AHardwareBuffer_to_GraphicBuffer(
        const AHardwareBuffer* buffer) {
    return reinterpret_cast<const GraphicBuffer*>(buffer);
}

static inline GraphicBuffer* AHardwareBuffer_to_GraphicBuffer(
        AHardwareBuffer* buffer) {
    return reinterpret_cast<GraphicBuffer*>(buffer);
}

static inline AHardwareBuffer* GraphicBuffer_to_AHardwareBuffer(
        GraphicBuffer* buffer) {
    return reinterpret_cast<AHardwareBuffer*>(buffer);
}

// ----------------------------------------------------------------------------
// Public functions
// ----------------------------------------------------------------------------

int AHardwareBuffer_allocate(const AHardwareBuffer_Desc* desc,
        AHardwareBuffer** outBuffer) {
    if (!outBuffer || !desc) return BAD_VALUE;

    // The holder is used to destroy the buffer if an error occurs.
    sp<IServiceManager> sm = defaultServiceManager();
    if (sm == nullptr) {
        ALOGE("Unable to connect to ServiceManager");
        return PERMISSION_DENIED;
    }

    // Get the SurfaceFlingerService.
    sp<ISurfaceComposer> composer = interface_cast<ISurfaceComposer>(
            sm->getService(String16("SurfaceFlinger")));
    if (composer == nullptr) {
        ALOGE("Unable to connect to surface composer");
        return PERMISSION_DENIED;
    }
    // Get an IGraphicBufferAlloc to create the buffer.
    sp<IGraphicBufferAlloc> allocator = composer->createGraphicBufferAlloc();
    if (allocator == nullptr) {
        ALOGE("Unable to obtain a buffer allocator");
        return PERMISSION_DENIED;
    }

    int format = android_hardware_HardwareBuffer_convertToPixelFormat(
            desc->format);
    if (format == 0) {
        ALOGE("Invalid pixel format");
        return BAD_VALUE;
    }

    if (desc->format == AHARDWAREBUFFER_FORMAT_BLOB && desc->height != 1) {
        ALOGE("Height must be 1 when using the AHARDWAREBUFFER_FORMAT_BLOB "
                "format");
        return BAD_VALUE;
    }

    status_t err;
    uint64_t producerUsage = 0;
    uint64_t consumerUsage = 0;
    android_hardware_HardwareBuffer_convertToGrallocUsageBits(desc->usage0,
            desc->usage1, &producerUsage, &consumerUsage);
    sp<GraphicBuffer> gbuffer = allocator->createGraphicBuffer(desc->width,
            desc->height, format, desc->layers, producerUsage, consumerUsage,
            std::string("AHardwareBuffer pid [") + std::to_string(getpid()) +
            "]", &err);
    if (err != NO_ERROR) {
        return err;
    }

    *outBuffer = GraphicBuffer_to_AHardwareBuffer(gbuffer.get());
    // Ensure the buffer doesn't get destroyed with the sp<> goes away.
    AHardwareBuffer_acquire(*outBuffer);
    return NO_ERROR;
}

void AHardwareBuffer_acquire(AHardwareBuffer* buffer) {
    AHardwareBuffer_to_GraphicBuffer(buffer)->incStrong(
            (void*)AHardwareBuffer_acquire);
}

void AHardwareBuffer_release(AHardwareBuffer* buffer) {
    AHardwareBuffer_to_GraphicBuffer(buffer)->decStrong(
            (void*)AHardwareBuffer_acquire);
}

void AHardwareBuffer_describe(const AHardwareBuffer* buffer,
        AHardwareBuffer_Desc* outDesc) {
    if (!buffer || !outDesc) return;

    const GraphicBuffer* gbuffer = AHardwareBuffer_to_GraphicBuffer(buffer);

    outDesc->width = gbuffer->getWidth();
    outDesc->height = gbuffer->getHeight();
    outDesc->layers = gbuffer->getLayerCount();
    outDesc->usage0 =
            android_hardware_HardwareBuffer_convertFromGrallocUsageBits(
                    gbuffer->getUsage(), gbuffer->getUsage());
    outDesc->usage1 = 0;
    outDesc->format = android_hardware_HardwareBuffer_convertFromPixelFormat(
            static_cast<uint32_t>(gbuffer->getPixelFormat()));
}

int AHardwareBuffer_lock(AHardwareBuffer* buffer, uint64_t usage0,
        int32_t fence, const ARect* rect, void** outVirtualAddress) {
    if (!buffer) return BAD_VALUE;

    if (usage0 & ~(AHARDWAREBUFFER_USAGE0_CPU_READ_OFTEN |
            AHARDWAREBUFFER_USAGE0_CPU_WRITE_OFTEN)) {
        ALOGE("Invalid usage flags passed to AHardwareBuffer_lock; only "
                " AHARDWAREBUFFER_USAGE0_CPU_* flags are allowed");
        return BAD_VALUE;
    }

    uint64_t producerUsage = 0;
    uint64_t consumerUsage = 0;
    android_hardware_HardwareBuffer_convertToGrallocUsageBits(usage0, 0,
            &producerUsage, &consumerUsage);
    GraphicBuffer* gBuffer = AHardwareBuffer_to_GraphicBuffer(buffer);
    Rect bounds;
    if (!rect) {
        bounds.set(Rect(gBuffer->getWidth(), gBuffer->getHeight()));
    } else {
        bounds.set(Rect(rect->left, rect->top, rect->right, rect->bottom));
    }
    return gBuffer->lockAsync(producerUsage, consumerUsage, bounds,
            outVirtualAddress, fence);
}

int AHardwareBuffer_unlock(AHardwareBuffer* buffer, int32_t* fence) {
    if (!buffer) return BAD_VALUE;

    GraphicBuffer* gBuffer = AHardwareBuffer_to_GraphicBuffer(buffer);
    return gBuffer->unlockAsync(fence);
}

int AHardwareBuffer_sendHandleToUnixSocket(const AHardwareBuffer* buffer,
        int socketFd) {
    if (!buffer) return BAD_VALUE;
    const GraphicBuffer* gBuffer = AHardwareBuffer_to_GraphicBuffer(buffer);

    size_t flattenedSize = gBuffer->getFlattenedSize();
    size_t fdCount = gBuffer->getFdCount();

    std::unique_ptr<uint8_t[]> data(new uint8_t[flattenedSize]);
    std::unique_ptr<int[]> fds(new int[fdCount]);

    // Make copies of needed items since flatten modifies them, and we don't
    // want to send anything if there's an error during flatten.
    size_t flattenedSizeCopy = flattenedSize;
    size_t fdCountCopy = fdCount;
    void* dataStart = data.get();
    int* fdsStart = fds.get();
    status_t err = gBuffer->flatten(dataStart, flattenedSizeCopy, fdsStart,
                fdCountCopy);
    if (err != NO_ERROR) {
        return err;
    }

    struct iovec iov[1];
    iov[0].iov_base = data.get();
    iov[0].iov_len = flattenedSize;

    char buf[CMSG_SPACE(kDataBufferSize)];
    struct msghdr msg = {
        .msg_control = buf,
        .msg_controllen = sizeof(buf),
        .msg_iov = &iov[0],
        .msg_iovlen = 1,
    };

    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fdCount);
    int* fdData = reinterpret_cast<int*>(CMSG_DATA(cmsg));
    memcpy(fdData, fds.get(), sizeof(int) * fdCount);
    msg.msg_controllen = cmsg->cmsg_len;

    int result = sendmsg(socketFd, &msg, 0);
    if (result <= 0) {
        ALOGE("Error writing AHardwareBuffer to socket: error %#x (%s)",
                result, strerror(errno));
        return result;
    }
    return NO_ERROR;
}

int AHardwareBuffer_recvHandleFromUnixSocket(int socketFd,
        AHardwareBuffer** outBuffer) {
    if (!outBuffer) return BAD_VALUE;

    char dataBuf[CMSG_SPACE(kDataBufferSize)];
    char fdBuf[CMSG_SPACE(kDataBufferSize)];
    struct iovec iov[1];
    iov[0].iov_base = dataBuf;
    iov[0].iov_len = sizeof(dataBuf);

    struct msghdr msg = {
        .msg_control = fdBuf,
        .msg_controllen = sizeof(fdBuf),
        .msg_iov = &iov[0],
        .msg_iovlen = 1,
    };

    int result = recvmsg(socketFd, &msg, 0);
    if (result <= 0) {
        ALOGE("Error reading AHardwareBuffer from socket: error %#x (%s)",
                result, strerror(errno));
        return result;
    }

    if (msg.msg_iovlen != 1) {
        ALOGE("Error reading AHardwareBuffer from socket: bad data length");
        return INVALID_OPERATION;
    }

    if (msg.msg_controllen % sizeof(int) != 0) {
        ALOGE("Error reading AHardwareBuffer from socket: bad fd length");
        return INVALID_OPERATION;
    }

    size_t dataLen = msg.msg_iov[0].iov_len;
    const void* data = static_cast<const void*>(msg.msg_iov[0].iov_base);
    if (!data) {
        ALOGE("Error reading AHardwareBuffer from socket: no buffer data");
        return INVALID_OPERATION;
    }

    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg) {
        ALOGE("Error reading AHardwareBuffer from socket: no fd header");
        return INVALID_OPERATION;
    }

    size_t fdCount = msg.msg_controllen >> 2;
    const int* fdData = reinterpret_cast<const int*>(CMSG_DATA(cmsg));
    if (!fdData) {
        ALOGE("Error reading AHardwareBuffer from socket: no fd data");
        return INVALID_OPERATION;
    }

    GraphicBuffer* gBuffer = new GraphicBuffer();
    status_t err = gBuffer->unflatten(data, dataLen, fdData, fdCount);
    if (err != NO_ERROR) {
        return err;
    }
    *outBuffer = GraphicBuffer_to_AHardwareBuffer(gBuffer);
    // Ensure the buffer has a positive ref-count.
    AHardwareBuffer_acquire(*outBuffer);

    return NO_ERROR;
}

const struct native_handle* AHardwareBuffer_getNativeHandle(
        const AHardwareBuffer* buffer) {
    if (!buffer) return nullptr;
    const GraphicBuffer* gbuffer = AHardwareBuffer_to_GraphicBuffer(buffer);
    return gbuffer->handle;
}
