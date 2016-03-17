/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "sensor"
#include <utils/Log.h>

#include <android/looper.h>
#include <android/sensor.h>

#include <utils/RefBase.h>
#include <utils/Looper.h>
#include <utils/Timers.h>

#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>

#include <poll.h>

using android::sp;
using android::Sensor;
using android::SensorManager;
using android::SensorEventQueue;
using android::String8;
using android::String16;

/*****************************************************************************/
ASensorManager* ASensorManager_getInstance()
{
    return ASensorManager_getInstanceForPackage(NULL);
}

ASensorManager* ASensorManager_getInstanceForPackage(const char* packageName)
{
    if (packageName) {
        return &SensorManager::getInstanceForPackage(String16(packageName));
    } else {
        return &SensorManager::getInstanceForPackage(String16());
    }
}

int ASensorManager_getSensorList(ASensorManager* manager,
        ASensorList* list)
{
    Sensor const* const* l;
    int c = static_cast<SensorManager*>(manager)->getSensorList(&l);
    if (list) {
        *list = reinterpret_cast<ASensorList>(l);
    }
    return c;
}

ASensor const* ASensorManager_getDefaultSensor(ASensorManager* manager, int type)
{
    return static_cast<SensorManager*>(manager)->getDefaultSensor(type);
}

ASensor const* ASensorManager_getDefaultSensorEx(ASensorManager* manager,
        int type, bool wakeUp) {
    Sensor const* const* sensorList;
    size_t size = static_cast<SensorManager*>(manager)->getSensorList(&sensorList);
    for (size_t i = 0; i < size; ++i) {
        if (ASensor_getType(sensorList[i]) == type &&
            ASensor_isWakeUpSensor(sensorList[i]) == wakeUp) {
            return reinterpret_cast<ASensor const *>(sensorList[i]);
       }
    }
    return NULL;
}

ASensorEventQueue* ASensorManager_createEventQueue(ASensorManager* manager,
        ALooper* looper, int ident, ALooper_callbackFunc callback, void* data)
{
    sp<SensorEventQueue> queue =
            static_cast<SensorManager*>(manager)->createEventQueue();
    if (queue != 0) {
        ALooper_addFd(looper, queue->getFd(), ident, ALOOPER_EVENT_INPUT, callback, data);
        queue->looper = looper;
        queue->incStrong(manager);
    }
    return static_cast<ASensorEventQueue*>(queue.get());
}

int ASensorManager_destroyEventQueue(ASensorManager* manager,
        ASensorEventQueue* inQueue)
{
    sp<SensorEventQueue> queue = static_cast<SensorEventQueue*>(inQueue);
    ALooper_removeFd(queue->looper, queue->getFd());
    queue->decStrong(manager);
    return 0;
}

/*****************************************************************************/

int ASensorEventQueue_registerSensor(ASensorEventQueue* queue, ASensor const* sensor,
        int32_t samplingPeriodUs, int maxBatchReportLatencyUs)
{
    return static_cast<SensorEventQueue*>(queue)->enableSensor(
            static_cast<Sensor const*>(sensor)->getHandle(), samplingPeriodUs,
                    maxBatchReportLatencyUs, 0);
}

int ASensorEventQueue_enableSensor(ASensorEventQueue* queue, ASensor const* sensor)
{
    return static_cast<SensorEventQueue*>(queue)->enableSensor(
            static_cast<Sensor const*>(sensor));
}

int ASensorEventQueue_disableSensor(ASensorEventQueue* queue, ASensor const* sensor)
{
    return static_cast<SensorEventQueue*>(queue)->disableSensor(
            static_cast<Sensor const*>(sensor));
}

int ASensorEventQueue_setEventRate(ASensorEventQueue* queue, ASensor const* sensor,
        int32_t usec)
{
    return static_cast<SensorEventQueue*>(queue)->setEventRate(
            static_cast<Sensor const*>(sensor), us2ns(usec));
}

int ASensorEventQueue_hasEvents(ASensorEventQueue* queue)
{
    struct pollfd pfd;
    pfd.fd = static_cast<SensorEventQueue*>(queue)->getFd();
    pfd.events = POLLIN;
    pfd.revents = 0;

    int nfd = poll(&pfd, 1, 0);

    if (nfd < 0)
        return -errno;

    if (pfd.revents != POLLIN)
        return -1;

    return (nfd == 0) ? 0 : 1;
}

ssize_t ASensorEventQueue_getEvents(ASensorEventQueue* queue,
                ASensorEvent* events, size_t count)
{
    ssize_t actual = static_cast<SensorEventQueue*>(queue)->read(events, count);
    if (actual > 0) {
        static_cast<SensorEventQueue*>(queue)->sendAck(events, actual);
    }
    return actual;
}

/*****************************************************************************/

const char* ASensor_getName(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getName().string();
}

const char* ASensor_getVendor(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getVendor().string();
}

int ASensor_getType(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getType();
}

float ASensor_getResolution(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getResolution();
}

int ASensor_getMinDelay(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getMinDelay();
}

int ASensor_getFifoMaxEventCount(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getFifoMaxEventCount();
}

int ASensor_getFifoReservedEventCount(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getFifoReservedEventCount();
}

const char* ASensor_getStringType(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getStringType().string();
}

int ASensor_getReportingMode(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->getReportingMode();
}

bool ASensor_isWakeUpSensor(ASensor const* sensor)
{
    return static_cast<Sensor const*>(sensor)->isWakeUpSensor();
}
