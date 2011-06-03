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
#include <math.h>
#include <sys/types.h>

#include <utils/Errors.h>

#include <hardware/sensors.h>

#include "CorrectedGyroSensor.h"
#include "SensorDevice.h"
#include "SensorFusion.h"

namespace android {
// ---------------------------------------------------------------------------

CorrectedGyroSensor::CorrectedGyroSensor(sensor_t const* list, size_t count)
    : mSensorDevice(SensorDevice::getInstance()),
      mSensorFusion(SensorFusion::getInstance())
{
    for (size_t i=0 ; i<count ; i++) {
        if (list[i].type == SENSOR_TYPE_GYROSCOPE) {
            mGyro = Sensor(list + i);
            break;
        }
    }
}

bool CorrectedGyroSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    if (event.type == SENSOR_TYPE_GYROSCOPE) {
        const vec3_t bias(mSensorFusion.getGyroBias());
        *outEvent = event;
        outEvent->data[0] -= bias.x;
        outEvent->data[1] -= bias.y;
        outEvent->data[2] -= bias.z;
        outEvent->sensor = '_cgy';
        return true;
    }
    return false;
}

status_t CorrectedGyroSensor::activate(void* ident, bool enabled) {
    mSensorDevice.activate(this, mGyro.getHandle(), enabled);
    return mSensorFusion.activate(this, enabled);
}

status_t CorrectedGyroSensor::setDelay(void* ident, int handle, int64_t ns) {
    mSensorDevice.setDelay(this, mGyro.getHandle(), ns);
    return mSensorFusion.setDelay(this, ns);
}

Sensor CorrectedGyroSensor::getSensor() const {
    sensor_t hwSensor;
    hwSensor.name       = "Corrected Gyroscope Sensor";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = 1;
    hwSensor.handle     = '_cgy';
    hwSensor.type       = SENSOR_TYPE_GYROSCOPE;
    hwSensor.maxRange   = mGyro.getMaxValue();
    hwSensor.resolution = mGyro.getResolution();
    hwSensor.power      = mSensorFusion.getPowerUsage();
    hwSensor.minDelay   = mGyro.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------
}; // namespace android

