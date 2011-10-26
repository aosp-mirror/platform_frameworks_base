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

#ifndef ANDROID_SENSOR_SERVICE_H
#define ANDROID_SENSOR_SERVICE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Vector.h>
#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/RefBase.h>

#include <binder/BinderService.h>

#include <gui/Sensor.h>
#include <gui/BitTube.h>
#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>

#include "SensorInterface.h"

// ---------------------------------------------------------------------------

#define DEBUG_CONNECTIONS   false

struct sensors_poll_device_t;
struct sensors_module_t;

namespace android {
// ---------------------------------------------------------------------------

class SensorService :
        public BinderService<SensorService>,
        public BnSensorServer,
        protected Thread
{
   friend class BinderService<SensorService>;

   static const nsecs_t MINIMUM_EVENTS_PERIOD =   1000000; // 1000 Hz

            SensorService();
    virtual ~SensorService();

    virtual void onFirstRef();

    // Thread interface
    virtual bool threadLoop();

    // ISensorServer interface
    virtual Vector<Sensor> getSensorList();
    virtual sp<ISensorEventConnection> createSensorEventConnection();
    virtual status_t dump(int fd, const Vector<String16>& args);


    class SensorEventConnection : public BnSensorEventConnection {
        virtual ~SensorEventConnection();
        virtual void onFirstRef();
        virtual sp<BitTube> getSensorChannel() const;
        virtual status_t enableDisable(int handle, bool enabled);
        virtual status_t setEventRate(int handle, nsecs_t ns);

        sp<SensorService> const mService;
        sp<BitTube> const mChannel;
        mutable Mutex mConnectionLock;

        // protected by SensorService::mLock
        SortedVector<int> mSensorInfo;

    public:
        SensorEventConnection(const sp<SensorService>& service);

        status_t sendEvents(sensors_event_t const* buffer, size_t count,
                sensors_event_t* scratch = NULL);
        bool hasSensor(int32_t handle) const;
        bool hasAnySensor() const;
        bool addSensor(int32_t handle);
        bool removeSensor(int32_t handle);
    };

    class SensorRecord {
        SortedVector< wp<SensorEventConnection> > mConnections;
    public:
        SensorRecord(const sp<SensorEventConnection>& connection);
        bool addConnection(const sp<SensorEventConnection>& connection);
        bool removeConnection(const wp<SensorEventConnection>& connection);
        size_t getNumConnections() const { return mConnections.size(); }
    };

    SortedVector< wp<SensorEventConnection> > getActiveConnections() const;
    DefaultKeyedVector<int, SensorInterface*> getActiveVirtualSensors() const;

    String8 getSensorName(int handle) const;
    void recordLastValue(sensors_event_t const * buffer, size_t count);
    static void sortEventBuffer(sensors_event_t* buffer, size_t count);
    void registerSensor(SensorInterface* sensor);
    void registerVirtualSensor(SensorInterface* sensor);

    // constants
    Vector<Sensor> mSensorList;
    Vector<Sensor> mUserSensorList;
    DefaultKeyedVector<int, SensorInterface*> mSensorMap;
    Vector<SensorInterface *> mVirtualSensorList;
    status_t mInitCheck;

    // protected by mLock
    mutable Mutex mLock;
    DefaultKeyedVector<int, SensorRecord*> mActiveSensors;
    DefaultKeyedVector<int, SensorInterface*> mActiveVirtualSensors;
    SortedVector< wp<SensorEventConnection> > mActiveConnections;

    // The size of this vector is constant, only the items are mutable
    KeyedVector<int32_t, sensors_event_t> mLastEventSeen;

public:
    static char const* getServiceName() { return "sensorservice"; }

    void cleanupConnection(SensorEventConnection* connection);
    status_t enable(const sp<SensorEventConnection>& connection, int handle);
    status_t disable(const sp<SensorEventConnection>& connection, int handle);
    status_t setEventRate(const sp<SensorEventConnection>& connection, int handle, nsecs_t ns);
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SENSOR_SERVICE_H
