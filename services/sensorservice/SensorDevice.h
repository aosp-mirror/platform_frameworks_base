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

#ifndef ANDROID_SENSOR_DEVICE_H
#define ANDROID_SENSOR_DEVICE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/KeyedVector.h>
#include <utils/Singleton.h>
#include <utils/String8.h>

#include <gui/Sensor.h>

// ---------------------------------------------------------------------------

namespace android {
// ---------------------------------------------------------------------------

static const nsecs_t DEFAULT_EVENTS_PERIOD = 200000000; //    5 Hz

class SensorDevice : public Singleton<SensorDevice> {
    friend class Singleton<SensorDevice>;
    struct sensors_poll_device_t* mSensorDevice;
    struct sensors_module_t* mSensorModule;
    mutable Mutex mLock; // protect mActivationCount[].rates
    // fixed-size array after construction
    struct Info {
        Info() : delay(0) { }
        KeyedVector<void*, nsecs_t> rates;
        nsecs_t delay;
        status_t setDelayForIdent(void* ident, int64_t ns);
        nsecs_t selectDelay();
    };
    DefaultKeyedVector<int, Info> mActivationCount;

    SensorDevice();
public:
    ssize_t getSensorList(sensor_t const** list);
    status_t initCheck() const;
    ssize_t poll(sensors_event_t* buffer, size_t count);
    status_t activate(void* ident, int handle, int enabled);
    status_t setDelay(void* ident, int handle, int64_t ns);
    void dump(String8& result, char* buffer, size_t SIZE);
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SENSOR_DEVICE_H
