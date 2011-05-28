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

#include "RotationVectorSensor.h"

namespace android {
// ---------------------------------------------------------------------------

RotationVectorSensor::RotationVectorSensor()
    : mSensorDevice(SensorDevice::getInstance()),
      mSensorFusion(SensorFusion::getInstance())
{
}

bool RotationVectorSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    if (event.type == SENSOR_TYPE_ACCELEROMETER) {
        if (mSensorFusion.hasEstimate()) {
            const vec4_t q(mSensorFusion.getAttitude());
            *outEvent = event;
            outEvent->data[0] = q.x;
            outEvent->data[1] = q.y;
            outEvent->data[2] = q.z;
            outEvent->data[3] = q.w;
            outEvent->sensor = '_rov';
            outEvent->type = SENSOR_TYPE_ROTATION_VECTOR;
            return true;
        }
    }
    return false;
}

status_t RotationVectorSensor::activate(void* ident, bool enabled) {
    return mSensorFusion.activate(this, enabled);
}

status_t RotationVectorSensor::setDelay(void* ident, int handle, int64_t ns) {
    return mSensorFusion.setDelay(this, ns);
}

Sensor RotationVectorSensor::getSensor() const {
    sensor_t hwSensor;
    hwSensor.name       = "Rotation Vector Sensor";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = 3;
    hwSensor.handle     = '_rov';
    hwSensor.type       = SENSOR_TYPE_ROTATION_VECTOR;
    hwSensor.maxRange   = 1;
    hwSensor.resolution = 1.0f / (1<<24);
    hwSensor.power      = mSensorFusion.getPowerUsage();
    hwSensor.minDelay   = mSensorFusion.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------

GyroDriftSensor::GyroDriftSensor()
    : mSensorDevice(SensorDevice::getInstance()),
      mSensorFusion(SensorFusion::getInstance())
{
}

bool GyroDriftSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    if (event.type == SENSOR_TYPE_ACCELEROMETER) {
        if (mSensorFusion.hasEstimate()) {
            const vec3_t b(mSensorFusion.getGyroBias());
            *outEvent = event;
            outEvent->data[0] = b.x;
            outEvent->data[1] = b.y;
            outEvent->data[2] = b.z;
            outEvent->sensor = '_gbs';
            outEvent->type = SENSOR_TYPE_ACCELEROMETER;
            return true;
        }
    }
    return false;
}

status_t GyroDriftSensor::activate(void* ident, bool enabled) {
    return mSensorFusion.activate(this, enabled);
}

status_t GyroDriftSensor::setDelay(void* ident, int handle, int64_t ns) {
    return mSensorFusion.setDelay(this, ns);
}

Sensor GyroDriftSensor::getSensor() const {
    sensor_t hwSensor;
    hwSensor.name       = "Gyroscope Bias (debug)";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = 1;
    hwSensor.handle     = '_gbs';
    hwSensor.type       = SENSOR_TYPE_ACCELEROMETER;
    hwSensor.maxRange   = 1;
    hwSensor.resolution = 1.0f / (1<<24);
    hwSensor.power      = mSensorFusion.getPowerUsage();
    hwSensor.minDelay   = mSensorFusion.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------
}; // namespace android

