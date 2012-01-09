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

#include <binder/IBinder.h>
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
    // okay we're not locked here, but it's not needed during construction
    assertStateLocked();
}

SensorManager::~SensorManager()
{
    free(mSensorList);
}

void SensorManager::sensorManagerDied()
{
    Mutex::Autolock _l(mLock);
    mSensorServer.clear();
    free(mSensorList);
    mSensorList = NULL;
    mSensors.clear();
}

status_t SensorManager::assertStateLocked() const {
    if (mSensorServer == NULL) {
        // try for one second
        const String16 name("sensorservice");
        for (int i=0 ; i<4 ; i++) {
            status_t err = getService(name, &mSensorServer);
            if (err == NAME_NOT_FOUND) {
                usleep(250000);
                continue;
            }
            if (err != NO_ERROR) {
                return err;
            }
            break;
        }

        class DeathObserver : public IBinder::DeathRecipient {
            SensorManager& mSensorManger;
            virtual void binderDied(const wp<IBinder>& who) {
                ALOGW("sensorservice died [%p]", who.unsafe_get());
                mSensorManger.sensorManagerDied();
            }
        public:
            DeathObserver(SensorManager& mgr) : mSensorManger(mgr) { }
        };

        mDeathObserver = new DeathObserver(*const_cast<SensorManager *>(this));
        mSensorServer->asBinder()->linkToDeath(mDeathObserver);

        mSensors = mSensorServer->getSensorList();
        size_t count = mSensors.size();
        mSensorList = (Sensor const**)malloc(count * sizeof(Sensor*));
        for (size_t i=0 ; i<count ; i++) {
            mSensorList[i] = mSensors.array() + i;
        }
    }

    return NO_ERROR;
}



ssize_t SensorManager::getSensorList(Sensor const* const** list) const
{
    Mutex::Autolock _l(mLock);
    status_t err = assertStateLocked();
    if (err < 0) {
        return ssize_t(err);
    }
    *list = mSensorList;
    return mSensors.size();
}

Sensor const* SensorManager::getDefaultSensor(int type)
{
    Mutex::Autolock _l(mLock);
    if (assertStateLocked() == NO_ERROR) {
        // For now we just return the first sensor of that type we find.
        // in the future it will make sense to let the SensorService make
        // that decision.
        for (size_t i=0 ; i<mSensors.size() ; i++) {
            if (mSensorList[i]->getType() == type)
                return mSensorList[i];
        }
    }
    return NULL;
}

sp<SensorEventQueue> SensorManager::createEventQueue()
{
    sp<SensorEventQueue> queue;

    Mutex::Autolock _l(mLock);
    while (assertStateLocked() == NO_ERROR) {
        sp<ISensorEventConnection> connection =
                mSensorServer->createSensorEventConnection();
        if (connection == NULL) {
            // SensorService just died.
            ALOGE("createEventQueue: connection is NULL. SensorService died.");
            continue;
        }
        queue = new SensorEventQueue(connection);
        break;
    }
    return queue;
}

// ----------------------------------------------------------------------------
}; // namespace android
