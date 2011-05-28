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

#include "OrientationSensor.h"
#include "SensorDevice.h"
#include "SensorFusion.h"

namespace android {
// ---------------------------------------------------------------------------

OrientationSensor::OrientationSensor()
    : mSensorDevice(SensorDevice::getInstance()),
      mSensorFusion(SensorFusion::getInstance())
{
}

bool OrientationSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event)
{
    if (event.type == SENSOR_TYPE_ACCELEROMETER) {
        if (mSensorFusion.hasEstimate()) {
            vec3_t g;
            const float rad2deg = 180 / M_PI;
            const mat33_t R(mSensorFusion.getRotationMatrix());
            g[0] = atan2f(-R[1][0], R[0][0])    * rad2deg;
            g[1] = atan2f(-R[2][1], R[2][2])    * rad2deg;
            g[2] = asinf ( R[2][0])             * rad2deg;
            if (g[0] < 0)
                g[0] += 360;

            *outEvent = event;
            outEvent->orientation.azimuth = g.x;
            outEvent->orientation.pitch   = g.y;
            outEvent->orientation.roll    = g.z;
            outEvent->orientation.status  = SENSOR_STATUS_ACCURACY_HIGH;
            outEvent->sensor = '_ypr';
            outEvent->type = SENSOR_TYPE_ORIENTATION;
            return true;
        }
    }
    return false;
}

status_t OrientationSensor::activate(void* ident, bool enabled) {
    return mSensorFusion.activate(this, enabled);
}

status_t OrientationSensor::setDelay(void* ident, int handle, int64_t ns) {
    return mSensorFusion.setDelay(this, ns);
}

Sensor OrientationSensor::getSensor() const {
    sensor_t hwSensor;
    hwSensor.name       = "Orientation Sensor";
    hwSensor.vendor     = "Google Inc.";
    hwSensor.version    = 1;
    hwSensor.handle     = '_ypr';
    hwSensor.type       = SENSOR_TYPE_ORIENTATION;
    hwSensor.maxRange   = 360.0f;
    hwSensor.resolution = 1.0f/256.0f; // FIXME: real value here
    hwSensor.power      = mSensorFusion.getPowerUsage();
    hwSensor.minDelay   = mSensorFusion.getMinDelay();
    Sensor sensor(&hwSensor);
    return sensor;
}

// ---------------------------------------------------------------------------
}; // namespace android

