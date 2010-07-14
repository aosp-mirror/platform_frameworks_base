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
#include <utils/Timers.h>

#include <binder/Parcel.h>
#include <binder/IInterface.h>

#include <gui/ISensorEventConnection.h>
#include <gui/SensorChannel.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    GET_SENSOR_CHANNEL = IBinder::FIRST_CALL_TRANSACTION,
    ENABLE_DISABLE,
    SET_EVENT_RATE
};

class BpSensorEventConnection : public BpInterface<ISensorEventConnection>
{
public:
    BpSensorEventConnection(const sp<IBinder>& impl)
        : BpInterface<ISensorEventConnection>(impl)
    {
    }

    virtual sp<SensorChannel> getSensorChannel() const
    {
        Parcel data, reply;
        remote()->transact(GET_SENSOR_CHANNEL, data, &reply);
        return new SensorChannel(reply);
    }

    virtual status_t enableDisable(int handle, bool enabled)
    {
        Parcel data, reply;
        data.writeInt32(handle);
        data.writeInt32(enabled);
        remote()->transact(ENABLE_DISABLE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setEventRate(int handle, nsecs_t ns)
    {
        Parcel data, reply;
        data.writeInt32(handle);
        data.writeInt64(ns);
        remote()->transact(SET_EVENT_RATE, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(SensorEventConnection, "android.gui.SensorEventConnection");

// ----------------------------------------------------------------------------

status_t BnSensorEventConnection::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case GET_SENSOR_CHANNEL: {
            CHECK_INTERFACE(ISensorEventConnection, data, reply);
            sp<SensorChannel> channel(getSensorChannel());
            channel->writeToParcel(reply);
            return NO_ERROR;
        } break;
        case ENABLE_DISABLE: {
            CHECK_INTERFACE(ISensorEventConnection, data, reply);
            int handle = data.readInt32();
            int enabled = data.readInt32();
            status_t result = enableDisable(handle, enabled);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
        case SET_EVENT_RATE: {
            CHECK_INTERFACE(ISensorEventConnection, data, reply);
            int handle = data.readInt32();
            int ns = data.readInt64();
            status_t result = setEventRate(handle, ns);
            reply->writeInt32(result);
            return NO_ERROR;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
