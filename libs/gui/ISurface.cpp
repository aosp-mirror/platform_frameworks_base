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

#include <gui/ISurface.h>
#include <gui/ISurfaceTexture.h>

namespace android {

// ----------------------------------------------------------------------

class BpSurface : public BpInterface<ISurface>
{
public:
    BpSurface(const sp<IBinder>& impl)
        : BpInterface<ISurface>(impl)
    {
    }

    virtual sp<ISurfaceTexture> getSurfaceTexture() const {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        remote()->transact(GET_SURFACE_TEXTURE, data, &reply);
        return interface_cast<ISurfaceTexture>(reply.readStrongBinder());
    }
};

IMPLEMENT_META_INTERFACE(Surface, "android.ui.ISurface");

// ----------------------------------------------------------------------

status_t BnSurface::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case GET_SURFACE_TEXTURE: {
            CHECK_INTERFACE(ISurface, data, reply);
            reply->writeStrongBinder( getSurfaceTexture()->asBinder() );
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
