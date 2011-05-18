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

#include "LinearAccelerationSensor.h"
#include "SensorDevice.h"
#include "SensorFusion.h"

namespace android {
// ---------------------------------------------------------------------------

LinearAccelerationSensor::LinearAccelerationSensor(sensor_t const* list, size_t count)
    : mSensorDevice(SensorDevice::getInstance()),
      mGravitySensor(list, count)
{
}

bool LinearAccelerationSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    bool result = mGravitySensor.process(outEvent, event);
    if (result && event.type == SENSOR_TYPE_ACCELEROMETER) {
        outEvent->data[0] = event.acceleration.x - outEvent->data[0];
        outEvent->data[1] = event.acceleration.y - outEvent->data[1];
        outEvent->data[2] = event.acceleration.z - outEvent->data[2];
        outEvent->sensor = '_lin';
        outEvent->type = SENSOR_TYPE_LINEAR_ACCELERATION;
        return true;
    }
    return false;
}

status_t LinearAccelerationSensor::activate(void* ident, bool enabled) {
    return mGravitySensor.activate(this, enabled);
}

status_t LinearAccelerationSensor::setDelay(void* ident, int handle, int64_t ns) {
    return mGravitySensor.setDelay(this, handle, ns);
}

Sensor LinearAccelerationSensor::getSensor() const {
    Sensor gsensor(mGravitySensor.getSensor());
    sensor_t hwSensor;
    hwSensor.name       = "Linear Acceleration Sensor";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = gsensor.getVersion();
    hwSensor.handle     = '_lin';
    hwSensor.type       = SENSOR_TYPE_LINEAR_ACCELERATION;
    hwSensor.maxRange   = gsensor.getMaxValue();
    hwSensor.resolution = gsensor.getResolution();
    hwSensor.power      = gsensor.getPowerUsage();
    hwSensor.minDelay   = gsensor.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------
}; // namespace android

