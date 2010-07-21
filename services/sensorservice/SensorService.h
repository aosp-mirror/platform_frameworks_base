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
#include <binder/Permission.h>

#include <gui/Sensor.h>
#include <gui/SensorChannel.h>
#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>

// ---------------------------------------------------------------------------

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
        virtual sp<SensorChannel> getSensorChannel() const;
        virtual status_t enableDisable(int handle, bool enabled);
        virtual status_t setEventRate(int handle, nsecs_t ns);
        sp<SensorService> const mService;
        sp<SensorChannel> const mChannel;
        SortedVector<int32_t> mSensorList;
    public:
        SensorEventConnection(const sp<SensorService>& service);
        virtual ~SensorEventConnection();
        virtual void onFirstRef();
        status_t sendEvents(sensors_event_t const* buffer, size_t count);
        bool hasSensor(int32_t handle) const;
        bool hasAnySensor() const;
        void addSensor(int32_t handle);
        void removeSensor(int32_t handle);
    };

    class SensorRecord {
        SortedVector< wp<SensorEventConnection> > mConnections;
    public:
        SensorRecord(const sp<SensorEventConnection>& connection);
        status_t addConnection(const sp<SensorEventConnection>& connection);
        bool removeConnection(const wp<SensorEventConnection>& connection);
        size_t getNumConnections() const { return mConnections.size(); }
    };

    SortedVector< wp<SensorEventConnection> > getActiveConnections() const;
    String8 getSensorName(int handle) const;

    // constants
    Vector<Sensor> mSensorList;
    struct sensors_poll_device_t* mSensorDevice;
    struct sensors_module_t* mSensorModule;
    Permission mDump;

    // protected by mLock
    mutable Mutex mLock;
    SortedVector< wp<SensorEventConnection> > mConnections;
    DefaultKeyedVector<int, SensorRecord*> mActiveSensors;
    SortedVector< wp<SensorEventConnection> > mActiveConnections;

public:
    static char const* getServiceName() { return "sensorservice"; }

    void cleanupConnection(const wp<SensorEventConnection>& connection);
    status_t enable(const sp<SensorEventConnection>& connection, int handle);
    status_t disable(const sp<SensorEventConnection>& connection, int handle);
    status_t setRate(const sp<SensorEventConnection>& connection, int handle, nsecs_t ns);
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SENSOR_SERVICE_H
