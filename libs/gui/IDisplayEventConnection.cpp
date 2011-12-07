/*
 * Copyright (C) 2011 The Android Open Source Project
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
#include <utils/Timers.h>

#include <binder/Parcel.h>
#include <binder/IInterface.h>

#include <gui/IDisplayEventConnection.h>
#include <gui/BitTube.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    GET_DATA_CHANNEL = IBinder::FIRST_CALL_TRANSACTION,
    SET_VSYNC_RATE,
    REQUEST_NEXT_VSYNC
};

class BpDisplayEventConnection : public BpInterface<IDisplayEventConnection>
{
public:
    BpDisplayEventConnection(const sp<IBinder>& impl)
        : BpInterface<IDisplayEventConnection>(impl)
    {
    }

    virtual sp<BitTube> getDataChannel() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IDisplayEventConnection::getInterfaceDescriptor());
        remote()->transact(GET_DATA_CHANNEL, data, &reply);
        return new BitTube(reply);
    }

    virtual void setVsyncRate(uint32_t count) {
        Parcel data, reply;
        data.writeInterfaceToken(IDisplayEventConnection::getInterfaceDescriptor());
        data.writeInt32(count);
        remote()->transact(SET_VSYNC_RATE, data, &reply);
    }

    virtual void requestNextVsync() {
        Parcel data, reply;
        data.writeInterfaceToken(IDisplayEventConnection::getInterfaceDescriptor());
        remote()->transact(REQUEST_NEXT_VSYNC, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(DisplayEventConnection, "android.gui.DisplayEventConnection");

// ----------------------------------------------------------------------------

status_t BnDisplayEventConnection::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case GET_DATA_CHANNEL: {
            CHECK_INTERFACE(IDisplayEventConnection, data, reply);
            sp<BitTube> channel(getDataChannel());
            channel->writeToParcel(reply);
            return NO_ERROR;
        } break;
        case SET_VSYNC_RATE: {
            CHECK_INTERFACE(IDisplayEventConnection, data, reply);
            setVsyncRate(data.readInt32());
            return NO_ERROR;
        } break;
        case REQUEST_NEXT_VSYNC: {
            CHECK_INTERFACE(IDisplayEventConnection, data, reply);
            requestNextVsync();
            return NO_ERROR;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
