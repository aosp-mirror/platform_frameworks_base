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

#define LOG_TAG "Sensors"

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Singleton.h>

#include <binder/IServiceManager.h>

#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>
#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE(SensorManager)

SensorManager::SensorManager()
    : mSensorList(0)
{
    const String16 name("sensorservice");
    while (getService(name, &mSensorServer) != NO_ERROR) {
        usleep(250000);
    }

    mSensors = mSensorServer->getSensorList();
    size_t count = mSensors.size();
    mSensorList = (Sensor const**)malloc(count * sizeof(Sensor*));
    for (size_t i=0 ; i<count ; i++) {
        mSensorList[i] = mSensors.array() + i;
    }
}

SensorManager::~SensorManager()
{
    free(mSensorList);
}

ssize_t SensorManager::getSensorList(Sensor const* const** list) const
{
    *list = mSensorList;
    return mSensors.size();
}

Sensor const* SensorManager::getDefaultSensor(int type)
{
    // For now we just return the first sensor of that type we find.
    // in the future it will make sense to let the SensorService make
    // that decision.
    for (size_t i=0 ; i<mSensors.size() ; i++) {
        if (mSensorList[i]->getType() == type)
            return mSensorList[i];
    }
    return NULL;
}

sp<SensorEventQueue> SensorManager::createEventQueue()
{
    sp<SensorEventQueue> result = new SensorEventQueue(
            mSensorServer->createSensorEventConnection());
    return result;
}

// ----------------------------------------------------------------------------
}; // namespace android
