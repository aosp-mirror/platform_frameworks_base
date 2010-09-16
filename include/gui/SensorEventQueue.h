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

#ifndef ANDROID_SENSOR_EVENT_QUEUE_H
#define ANDROID_SENSOR_EVENT_QUEUE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>

#include <gui/SensorChannel.h>

// ----------------------------------------------------------------------------

struct ALooper;
struct ASensorEvent;

// Concrete types for the NDK
struct ASensorEventQueue {
    ALooper* looper;
};

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

class ISensorEventConnection;
class Sensor;
class Looper;

// ----------------------------------------------------------------------------

class SensorEventQueue : public ASensorEventQueue, public RefBase
{
public:
            SensorEventQueue(const sp<ISensorEventConnection>& connection);
    virtual ~SensorEventQueue();
    virtual void onFirstRef();

    int getFd() const;
    ssize_t write(ASensorEvent const* events, size_t numEvents);
    ssize_t read(ASensorEvent* events, size_t numEvents);

    status_t waitForEvent() const;
    status_t wake() const;

    status_t enableSensor(Sensor const* sensor) const;
    status_t disableSensor(Sensor const* sensor) const;
    status_t setEventRate(Sensor const* sensor, nsecs_t ns) const;

    // these are here only to support SensorManager.java
    status_t enableSensor(int32_t handle, int32_t us) const;
    status_t disableSensor(int32_t handle) const;

private:
    sp<Looper> getLooper() const;
    sp<ISensorEventConnection> mSensorEventConnection;
    sp<SensorChannel> mSensorChannel;
    mutable Mutex mLock;
    mutable sp<Looper> mLooper;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SENSOR_EVENT_QUEUE_H
