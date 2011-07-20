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

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <utils/Timers.h>

#include <binder/Parcel.h>
#include <binder/IInterface.h>

#include <gui/ISurfaceTexture.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    REQUEST_BUFFER = IBinder::FIRST_CALL_TRANSACTION,
    SET_BUFFER_COUNT,
    DEQUEUE_BUFFER,
    QUEUE_BUFFER,
    CANCEL_BUFFER,
    SET_CROP,
    SET_TRANSFORM,
    GET_ALLOCATOR,
    QUERY,
    SET_SYNCHRONOUS_MODE,
    CONNECT,
    DISCONNECT,
    SET_SCALING_MODE,
};


class BpSurfaceTexture : public BpInterface<ISurfaceTexture>
{
public:
    BpSurfaceTexture(const sp<IBinder>& impl)
        : BpInterface<ISurfaceTexture>(impl)
    {
    }

    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(bufferIdx);
        remote()->transact(REQUEST_BUFFER, data, &reply);
        sp<GraphicBuffer> buffer;
        bool nonNull = reply.readInt32();
        if (nonNull) {
            buffer = new GraphicBuffer();
            reply.read(*buffer);
        }
        return buffer;
    }

    virtual status_t setBufferCount(int bufferCount)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(bufferCount);
        remote()->transact(SET_BUFFER_COUNT, data, &reply);
        status_t err = reply.readInt32();
        return err;
    }

    virtual status_t dequeueBuffer(int *buf, uint32_t w, uint32_t h,
            uint32_t format, uint32_t usage) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(format);
        data.writeInt32(usage);
        remote()->transact(DEQUEUE_BUFFER, data, &reply);
        *buf = reply.readInt32();
        int result = reply.readInt32();
        return result;
    }

    virtual status_t queueBuffer(int buf, int64_t timestamp,
            uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(buf);
        data.writeInt64(timestamp);
        remote()->transact(QUEUE_BUFFER, data, &reply);
        *outWidth = reply.readInt32();
        *outHeight = reply.readInt32();
        *outTransform = reply.readInt32();
        status_t result = reply.readInt32();
        return result;
    }

    virtual void cancelBuffer(int buf) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(buf);
        remote()->transact(CANCEL_BUFFER, data, &reply);
    }

    virtual status_t setCrop(const Rect& reg) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeFloat(reg.left);
        data.writeFloat(reg.top);
        data.writeFloat(reg.right);
        data.writeFloat(reg.bottom);
        remote()->transact(SET_CROP, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual status_t setTransform(uint32_t transform) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(transform);
        remote()->transact(SET_TRANSFORM, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual status_t setScalingMode(int mode) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(mode);
        remote()->transact(SET_SCALING_MODE, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual sp<IBinder> getAllocator() {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        remote()->transact(GET_ALLOCATOR, data, &reply);
        return reply.readStrongBinder();
    }

    virtual int query(int what, int* value) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(what);
        remote()->transact(QUERY, data, &reply);
        value[0] = reply.readInt32();
        status_t result = reply.readInt32();
        return result;
    }

    virtual status_t setSynchronousMode(bool enabled) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(enabled);
        remote()->transact(SET_SYNCHRONOUS_MODE, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual status_t connect(int api) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(api);
        remote()->transact(CONNECT, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual status_t disconnect(int api) {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceTexture::getInterfaceDescriptor());
        data.writeInt32(api);
        remote()->transact(DISCONNECT, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }
};

IMPLEMENT_META_INTERFACE(SurfaceTexture, "android.gui.SurfaceTexture");

// ----------------------------------------------------------------------

status_t BnSurfaceTexture::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case REQUEST_BUFFER: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int bufferIdx   = data.readInt32();
            sp<GraphicBuffer> buffer(requestBuffer(bufferIdx));
            reply->writeInt32(buffer != 0);
            if (buffer != 0) {
                reply->write(*buffer);
            }
            return NO_ERROR;
        } break;
        case SET_BUFFER_COUNT: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int bufferCount = data.readInt32();
            int result = setBufferCount(bufferCount);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case DEQUEUE_BUFFER: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            uint32_t w      = data.readInt32();
            uint32_t h      = data.readInt32();
            uint32_t format = data.readInt32();
            uint32_t usage  = data.readInt32();
            int buf;
            int result = dequeueBuffer(&buf, w, h, format, usage);
            reply->writeInt32(buf);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case QUEUE_BUFFER: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int buf = data.readInt32();
            int64_t timestamp = data.readInt64();
            uint32_t outWidth, outHeight, outTransform;
            status_t result = queueBuffer(buf, timestamp,
                    &outWidth, &outHeight, &outTransform);
            reply->writeInt32(outWidth);
            reply->writeInt32(outHeight);
            reply->writeInt32(outTransform);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case CANCEL_BUFFER: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int buf = data.readInt32();
            cancelBuffer(buf);
            return NO_ERROR;
        } break;
        case SET_CROP: {
            Rect reg;
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            reg.left = data.readFloat();
            reg.top = data.readFloat();
            reg.right = data.readFloat();
            reg.bottom = data.readFloat();
            status_t result = setCrop(reg);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case SET_TRANSFORM: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            uint32_t transform = data.readInt32();
            status_t result = setTransform(transform);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case SET_SCALING_MODE: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int mode = data.readInt32();
            status_t result = setScalingMode(mode);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case GET_ALLOCATOR: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            sp<IBinder> result = getAllocator();
            reply->writeStrongBinder(result);
            return NO_ERROR;
        } break;
        case QUERY: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int value;
            int what = data.readInt32();
            int res = query(what, &value);
            reply->writeInt32(value);
            reply->writeInt32(res);
            return NO_ERROR;
        } break;
        case SET_SYNCHRONOUS_MODE: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            bool enabled = data.readInt32();
            status_t res = setSynchronousMode(enabled);
            reply->writeInt32(res);
            return NO_ERROR;
        } break;
        case CONNECT: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int api = data.readInt32();
            status_t res = connect(api);
            reply->writeInt32(res);
            return NO_ERROR;
        } break;
        case DISCONNECT: {
            CHECK_INTERFACE(ISurfaceTexture, data, reply);
            int api = data.readInt32();
            status_t res = disconnect(api);
            reply->writeInt32(res);
            return NO_ERROR;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------

}; // namespace android
