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

#include <gui/Sensor.h>
#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    GET_SENSOR_LIST = IBinder::FIRST_CALL_TRANSACTION,
    CREATE_SENSOR_EVENT_CONNECTION,
};

class BpSensorServer : public BpInterface<ISensorServer>
{
public:
    BpSensorServer(const sp<IBinder>& impl)
        : BpInterface<ISensorServer>(impl)
    {
    }

    virtual Vector<Sensor> getSensorList()
    {
        Parcel data, reply;
        remote()->transact(GET_SENSOR_LIST, data, &reply);
        Sensor s;
        Vector<Sensor> v;
        int32_t n = reply.readInt32();
        v.setCapacity(n);
        while (n--) {
            reply.read(static_cast<Flattenable&>(s));
            v.add(s);
        }
        return v;
    }

    virtual sp<ISensorEventConnection> createSensorEventConnection()
    {
        Parcel data, reply;
        remote()->transact(CREATE_SENSOR_EVENT_CONNECTION, data, &reply);
        return interface_cast<ISensorEventConnection>(reply.readStrongBinder());
    }
};

IMPLEMENT_META_INTERFACE(SensorServer, "android.gui.SensorServer");

// ----------------------------------------------------------------------

status_t BnSensorServer::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case GET_SENSOR_LIST: {
            CHECK_INTERFACE(ISensorServer, data, reply);
            Vector<Sensor> v(getSensorList());
            size_t n = v.size();
            reply->writeInt32(n);
            for (size_t i=0 ; i<n ; i++) {
                reply->write(static_cast<const Flattenable&>(v[i]));
            }
            return NO_ERROR;
        } break;
        case CREATE_SENSOR_EVENT_CONNECTION: {
            CHECK_INTERFACE(ISensorServer, data, reply);
            sp<ISensorEventConnection> connection(createSensorEventConnection());
            reply->writeStrongBinder(connection->asBinder());
            return NO_ERROR;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
