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
#include <sys/types.h>

#include <cutils/log.h>

#include "SensorInterface.h"

namespace android {
// ---------------------------------------------------------------------------

SensorInterface::~SensorInterface()
{
}

// ---------------------------------------------------------------------------

HardwareSensor::HardwareSensor(const sensor_t& sensor)
    : mSensorDevice(SensorDevice::getInstance()),
      mSensor(&sensor)
{
    ALOGI("%s", sensor.name);
}

HardwareSensor::~HardwareSensor() {
}

bool HardwareSensor::process(sensors_event_t* outEvent,
        const sensors_event_t& event) {
    *outEvent = event;
    return true;
}

status_t HardwareSensor::activate(void* ident, bool enabled) {
    return mSensorDevice.activate(ident, mSensor.getHandle(), enabled);
}

status_t HardwareSensor::setDelay(void* ident, int handle, int64_t ns) {
    return mSensorDevice.setDelay(ident, handle, ns);
}

Sensor HardwareSensor::getSensor() const {
    return mSensor;
}


// ---------------------------------------------------------------------------
}; // namespace android
