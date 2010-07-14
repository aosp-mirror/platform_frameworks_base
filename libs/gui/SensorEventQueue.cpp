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

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <gui/Sensor.h>
#include <gui/SensorChannel.h>
#include <gui/SensorEventQueue.h>
#include <gui/ISensorEventConnection.h>

#include <android/sensor.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

SensorEventQueue::SensorEventQueue(const sp<ISensorEventConnection>& connection)
    : mSensorEventConnection(connection)
{
}

SensorEventQueue::~SensorEventQueue()
{
}

void SensorEventQueue::onFirstRef()
{
    mSensorChannel = mSensorEventConnection->getSensorChannel();
}

int SensorEventQueue::getFd() const
{
    return mSensorChannel->getFd();
}

ssize_t SensorEventQueue::write(ASensorEvent const* events, size_t numEvents)
{
    ssize_t size = mSensorChannel->write(events, numEvents * sizeof(events[0]));
    if (size >= 0) {
        if (size % sizeof(events[0])) {
            // partial write!!! should never happen.
            return -EINVAL;
        }
        // returns number of events written
        size /= sizeof(events[0]);
    }
    return size;
}

ssize_t SensorEventQueue::read(ASensorEvent* events, size_t numEvents)
{
    ssize_t size = mSensorChannel->read(events, numEvents*sizeof(events[0]));
    if (size >= 0) {
        if (size % sizeof(events[0])) {
            // partial write!!! should never happen.
            return -EINVAL;
        }
        // returns number of events read
        size /= sizeof(events[0]);
    }
    return size;
}

status_t SensorEventQueue::enableSensor(Sensor const* sensor) const
{
    return mSensorEventConnection->enableDisable(sensor->getHandle(), true);
}

status_t SensorEventQueue::disableSensor(Sensor const* sensor) const
{
    return mSensorEventConnection->enableDisable(sensor->getHandle(), false);
}

status_t SensorEventQueue::setEventRate(Sensor const* sensor, nsecs_t ns) const
{
    return mSensorEventConnection->setEventRate(sensor->getHandle(), ns);
}

// ----------------------------------------------------------------------------
}; // namespace android

