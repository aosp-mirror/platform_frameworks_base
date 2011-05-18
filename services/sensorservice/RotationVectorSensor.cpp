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

template <typename T>
static inline T clamp(T v) {
    return v < 0 ? 0 : v;
}

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
            const mat33_t R(mSensorFusion.getRotationMatrix());

            // matrix to rotation vector (normalized quaternion)
            const float Hx = R[0].x;
            const float My = R[1].y;
            const float Az = R[2].z;

            float qw = sqrtf( clamp( Hx + My + Az + 1) * 0.25f );
            float qx = sqrtf( clamp( Hx - My - Az + 1) * 0.25f );
            float qy = sqrtf( clamp(-Hx + My - Az + 1) * 0.25f );
            float qz = sqrtf( clamp(-Hx - My + Az + 1) * 0.25f );
            qx = copysignf(qx, R[2].y - R[1].z);
            qy = copysignf(qy, R[0].z - R[2].x);
            qz = copysignf(qz, R[1].x - R[0].y);

            // this quaternion is guaranteed to be normalized, by construction
            // of the rotation matrix.

            *outEvent = event;
            outEvent->data[0] = qx;
            outEvent->data[1] = qy;
            outEvent->data[2] = qz;
            outEvent->data[3] = qw;
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
    hwSensor.version    = mSensorFusion.hasGyro() ? 3 : 2;
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
}; // namespace android

