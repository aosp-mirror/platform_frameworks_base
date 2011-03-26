/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "ISurface"

#include <stdio.h>
#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <ui/GraphicBuffer.h>

#include <surfaceflinger/Surface.h>
#include <surfaceflinger/ISurface.h>

namespace android {

// ----------------------------------------------------------------------

class BpSurface : public BpInterface<ISurface>
{
public:
    BpSurface(const sp<IBinder>& impl)
        : BpInterface<ISurface>(impl)
    {
    }

    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx,
            uint32_t w, uint32_t h, uint32_t format, uint32_t usage)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(bufferIdx);
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(format);
        data.writeInt32(usage);
        remote()->transact(REQUEST_BUFFER, data, &reply);
        sp<GraphicBuffer> buffer = new GraphicBuffer();
        reply.read(*buffer);
        return buffer;
    }

    virtual status_t setBufferCount(int bufferCount)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(bufferCount);
        remote()->transact(SET_BUFFER_COUNT, data, &reply);
        status_t err = reply.readInt32();
        return err;
    }
};

IMPLEMENT_META_INTERFACE(Surface, "android.ui.ISurface");

// ----------------------------------------------------------------------

status_t BnSurface::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case REQUEST_BUFFER: {
            CHECK_INTERFACE(ISurface, data, reply);
            int bufferIdx = data.readInt32();
            uint32_t w = data.readInt32();
            uint32_t h = data.readInt32();
            uint32_t format = data.readInt32();
            uint32_t usage = data.readInt32();
            sp<GraphicBuffer> buffer(requestBuffer(bufferIdx, w, h, format, usage));
            if (buffer == NULL)
                return BAD_VALUE;
            return reply->write(*buffer);
        }
        case SET_BUFFER_COUNT: {
            CHECK_INTERFACE(ISurface, data, reply);
            int bufferCount = data.readInt32();
            status_t err = setBufferCount(bufferCount);
            reply->writeInt32(err);
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
