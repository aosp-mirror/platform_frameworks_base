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
#include <math.h>
#include <sys/types.h>

#include <utils/Errors.h>

#include <hardware/sensors.h>

#include "GravitySensor.h"
#include "SensorDevice.h"
#include "SensorFusion.h"

namespace android {
// ---------------------------------------------------------------------------

GravitySensor::GravitySensor(sensor_t const* list, size_t count)
    : mSensorDevice(SensorDevice::getInstance()),
      mSensorFusion(SensorFusion::getInstance())
{
    for (size_t i=0 ; i<count ; i++) {
        if (list[i].type == SENSOR_TYPE_ACCELEROMETER) {
            mAccelerometer = Sensor(list + i);
            break;
        }
    }
}

bool GravitySensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    const static double NS2S = 1.0 / 1000000000.0;
    if (event.type == SENSOR_TYPE_ACCELEROMETER) {
        vec3_t g;
        if (!mSensorFusion.hasEstimate())
            return false;
        const mat33_t R(mSensorFusion.getRotationMatrix());
        // FIXME: we need to estimate the length of gravity because
        // the accelerometer may have a small scaling error. This
        // translates to an offset in the linear-acceleration sensor.
        g = R[2] * GRAVITY_EARTH;

        *outEvent = event;
        outEvent->data[0] = g.x;
        outEvent->data[1] = g.y;
        outEvent->data[2] = g.z;
        outEvent->sensor = '_grv';
        outEvent->type = SENSOR_TYPE_GRAVITY;
        return true;
    }
    return false;
}

status_t GravitySensor::activate(void* ident, bool enabled) {
    return mSensorFusion.activate(this, enabled);
}

status_t GravitySensor::setDelay(void* ident, int handle, int64_t ns) {
    return mSensorFusion.setDelay(this, ns);
}

Sensor GravitySensor::getSensor() const {
    sensor_t hwSensor;
    hwSensor.name       = "Gravity Sensor";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = 3;
    hwSensor.handle     = '_grv';
    hwSensor.type       = SENSOR_TYPE_GRAVITY;
    hwSensor.maxRange   = GRAVITY_EARTH * 2;
    hwSensor.resolution = mAccelerometer.getResolution();
    hwSensor.power      = mSensorFusion.getPowerUsage();
    hwSensor.minDelay   = mSensorFusion.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------
}; // namespace android

