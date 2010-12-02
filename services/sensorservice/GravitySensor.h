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

#ifndef ANDROID_GRAVITY_SENSOR_H
#define ANDROID_GRAVITY_SENSOR_H

#include <stdint.h>
#include <sys/types.h>

#include <gui/Sensor.h>

#include "SensorDevice.h"
#include "SensorInterface.h"
#include "SecondOrderLowPassFilter.h"

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

class GravitySensor : public SensorInterface {
    SensorDevice& mSensorDevice;
    Sensor mAccelerometer;
    double mAccTime;

    SecondOrderLowPassFilter mLowPass;
    CascadedBiquadFilter mX, mY, mZ;

public:
    GravitySensor(sensor_t const* list, size_t count);
    virtual bool process(sensors_event_t* outEvent,
            const sensors_event_t& event);
    virtual status_t activate(void* ident, bool enabled);
    virtual status_t setDelay(void* ident, int handle, int64_t ns);
    virtual Sensor getSensor() const;
    virtual bool isVirtual() const { return true; }
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GRAVITY_SENSOR_H
