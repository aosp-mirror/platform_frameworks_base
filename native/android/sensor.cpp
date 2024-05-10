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
#include <android/sharedmem.h>
#include <cutils/native_handle.h>
#include <sensor/Sensor.h>
#include <sensor/SensorManager.h>
#include <sensor/SensorEventQueue.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>
#include <vndk/hardware_buffer.h>

#include <poll.h>

using android::sp;
using android::Sensor;
using android::SensorManager;
using android::SensorEventQueue;
using android::String8;
using android::String16;

/*****************************************************************************/
#define ERROR_INVALID_PARAMETER(message) ALOGE("%s: " message, __func__)

// frequently used checks
#define RETURN_IF_MANAGER_IS_NULL(retval) do {\
        if (manager == nullptr) { \
            ERROR_INVALID_PARAMETER("manager cannot be NULL"); \
            return retval; \
        } \
    } while (false)
#define RETURN_IF_SENSOR_IS_NULL(retval) do {\
        if (sensor == nullptr) { \
            ERROR_INVALID_PARAMETER("sensor cannot be NULL"); \
            return retval; \
        } \
    } while (false)
#define RETURN_IF_QUEUE_IS_NULL(retval) do {\
        if (queue == nullptr) { \
            ERROR_INVALID_PARAMETER("queue cannot be NULL"); \
            return retval; \
        } \
    } while (false)

ASensorManager* ASensorManager_getInstance() {
    return ASensorManager_getInstanceForPackage(nullptr);
}

ASensorManager* ASensorManager_getInstanceForPackage(const char* packageName) {
    if (packageName) {
        return &SensorManager::getInstanceForPackage(String16(packageName));
    } else {
        return &SensorManager::getInstanceForPackage(String16());
    }
}

int ASensorManager_getSensorList(ASensorManager* manager, ASensorList* list) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);
    Sensor const* const* l;
    int c = static_cast<SensorManager*>(manager)->getSensorList(&l);
    if (list) {
        *list = reinterpret_cast<ASensorList>(l);
    }
    return c;
}

ssize_t ASensorManager_getDynamicSensorList(ASensorManager* manager, ASensorList* list) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);
    Sensor const* const* l;
    ssize_t c = static_cast<SensorManager*>(manager)->getDynamicSensorList(&l);
    if (list) {
        *list = reinterpret_cast<ASensorList>(l);
    }
    return c;
}

ASensor const* ASensorManager_getDefaultSensor(ASensorManager* manager, int type) {
    RETURN_IF_MANAGER_IS_NULL(nullptr);
    return static_cast<SensorManager*>(manager)->getDefaultSensor(type);
}

ASensor const* ASensorManager_getDefaultSensorEx(ASensorManager* manager, int type, bool wakeUp) {
    RETURN_IF_MANAGER_IS_NULL(nullptr);
    Sensor const* const* sensorList;
    size_t size = static_cast<SensorManager*>(manager)->getSensorList(&sensorList);
    for (size_t i = 0; i < size; ++i) {
        if (ASensor_getType(sensorList[i]) == type &&
            ASensor_isWakeUpSensor(sensorList[i]) == wakeUp) {
            return reinterpret_cast<ASensor const *>(sensorList[i]);
       }
    }
    return nullptr;
}

ASensorEventQueue* ASensorManager_createEventQueue(ASensorManager* manager,
        ALooper* looper, int ident, ALooper_callbackFunc callback, void* data) {
    RETURN_IF_MANAGER_IS_NULL(nullptr);

    if (looper == nullptr) {
        ERROR_INVALID_PARAMETER("looper cannot be NULL");
        return nullptr;
    }

    sp<SensorEventQueue> queue =
            static_cast<SensorManager*>(manager)->createEventQueue();
    if (queue != 0) {
        ALooper_addFd(looper, queue->getFd(), ident, ALOOPER_EVENT_INPUT, callback, data);
        queue->looper = looper;
        queue->requestAdditionalInfo = false;
        queue->incStrong(manager);
    }
    return static_cast<ASensorEventQueue*>(queue.get());
}

int ASensorManager_destroyEventQueue(ASensorManager* manager, ASensorEventQueue* queue) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);

    sp<SensorEventQueue> q = static_cast<SensorEventQueue*>(queue);
    ALooper_removeFd(q->looper, q->getFd());
    q->decStrong(manager);
    return 0;
}

int ASensorManager_createSharedMemoryDirectChannel(ASensorManager *manager, int fd, size_t size) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);

    if (fd < 0) {
        ERROR_INVALID_PARAMETER("fd is invalid.");
        return android::BAD_VALUE;
    }

    if (size < sizeof(ASensorEvent)) {
        ERROR_INVALID_PARAMETER("size has to be greater or equal to sizeof(ASensorEvent).");
        return android::BAD_VALUE;
    }

    native_handle_t *resourceHandle = native_handle_create(1 /* nFd */, 0 /* nInt */);
    if (!resourceHandle) {
        return android::NO_MEMORY;
    }

    resourceHandle->data[0] = fd;
    int ret = static_cast<SensorManager *>(manager)->createDirectChannel(
            size, ASENSOR_DIRECT_CHANNEL_TYPE_SHARED_MEMORY, resourceHandle);
    native_handle_delete(resourceHandle);
    return ret;
}

int ASensorManager_createHardwareBufferDirectChannel(
        ASensorManager *manager, AHardwareBuffer const *buffer, size_t size) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);

    if (buffer == nullptr) {
        ERROR_INVALID_PARAMETER("buffer cannot be NULL");
        return android::BAD_VALUE;
    }

    if (size < sizeof(ASensorEvent)) {
        ERROR_INVALID_PARAMETER("size has to be greater or equal to sizeof(ASensorEvent).");
        return android::BAD_VALUE;
    }

    const native_handle_t *resourceHandle = AHardwareBuffer_getNativeHandle(buffer);
    if (!resourceHandle) {
        return android::NO_MEMORY;
    }

    return static_cast<SensorManager *>(manager)->createDirectChannel(
            size, ASENSOR_DIRECT_CHANNEL_TYPE_HARDWARE_BUFFER, resourceHandle);
}

void ASensorManager_destroyDirectChannel(ASensorManager *manager, int channelId) {
    RETURN_IF_MANAGER_IS_NULL(void());

    static_cast<SensorManager *>(manager)->destroyDirectChannel(channelId);
}

int ASensorManager_configureDirectReport(
        ASensorManager *manager, ASensor const *sensor, int channelId, int rate) {
    RETURN_IF_MANAGER_IS_NULL(android::BAD_VALUE);

    int sensorHandle;
    if (sensor == nullptr) {
        if (rate != ASENSOR_DIRECT_RATE_STOP) {
            ERROR_INVALID_PARAMETER(
                "sensor cannot be null when rate is not ASENSOR_DIRECT_RATE_STOP");
            return android::BAD_VALUE;
        }
        sensorHandle = -1;
    } else {
        sensorHandle = static_cast<Sensor const *>(sensor)->getHandle();
    }
    return static_cast<SensorManager *>(manager)->configureDirectChannel(
            channelId, sensorHandle, rate);
}

/*****************************************************************************/

int ASensorEventQueue_registerSensor(ASensorEventQueue* queue, ASensor const* sensor,
        int32_t samplingPeriodUs, int64_t maxBatchReportLatencyUs) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    RETURN_IF_SENSOR_IS_NULL(android::BAD_VALUE);
    if (samplingPeriodUs < 0 || maxBatchReportLatencyUs < 0) {
        ERROR_INVALID_PARAMETER("samplingPeriodUs and maxBatchReportLatencyUs cannot be negative");
        return android::BAD_VALUE;
    }

    return static_cast<SensorEventQueue*>(queue)->enableSensor(
            static_cast<Sensor const*>(sensor)->getHandle(), samplingPeriodUs,
                    maxBatchReportLatencyUs, 0);
}

int ASensorEventQueue_enableSensor(ASensorEventQueue* queue, ASensor const* sensor) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    RETURN_IF_SENSOR_IS_NULL(android::BAD_VALUE);

    return static_cast<SensorEventQueue*>(queue)->enableSensor(
            static_cast<Sensor const*>(sensor));
}

int ASensorEventQueue_disableSensor(ASensorEventQueue* queue, ASensor const* sensor) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    RETURN_IF_SENSOR_IS_NULL(android::BAD_VALUE);

    return static_cast<SensorEventQueue*>(queue)->disableSensor(
            static_cast<Sensor const*>(sensor));
}

int ASensorEventQueue_setEventRate(ASensorEventQueue* queue, ASensor const* sensor, int32_t usec) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    RETURN_IF_SENSOR_IS_NULL(android::BAD_VALUE);

    if (usec < 0) {
        ERROR_INVALID_PARAMETER("usec cannot be negative");
        return android::BAD_VALUE;
    }

    return static_cast<SensorEventQueue*>(queue)->setEventRate(
            static_cast<Sensor const*>(sensor), us2ns(usec));
}

int ASensorEventQueue_hasEvents(ASensorEventQueue* queue) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);

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

ssize_t ASensorEventQueue_getEvents(ASensorEventQueue* queue, ASensorEvent* events, size_t count) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    if (events == nullptr) {
        ERROR_INVALID_PARAMETER("events cannot be NULL");
        return android::BAD_VALUE;
    }

    SensorEventQueue* sensorQueue = static_cast<SensorEventQueue*>(queue);
    ssize_t actual = sensorQueue->read(events, count);
    if (actual > 0) {
        sensorQueue->sendAck(events, actual);
    }

    return sensorQueue->filterEvents(events, actual);
}

int ASensorEventQueue_requestAdditionalInfoEvents(ASensorEventQueue* queue, bool enable) {
    RETURN_IF_QUEUE_IS_NULL(android::BAD_VALUE);
    queue->requestAdditionalInfo = enable;
    return android::OK;
}

/*****************************************************************************/

const char* ASensor_getName(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(nullptr);
    return static_cast<Sensor const*>(sensor)->getName().c_str();
}

const char* ASensor_getVendor(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(nullptr);
    return static_cast<Sensor const*>(sensor)->getVendor().c_str();
}

int ASensor_getType(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_TYPE_INVALID);
    return static_cast<Sensor const*>(sensor)->getType();
}

float ASensor_getResolution(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_RESOLUTION_INVALID);
    return static_cast<Sensor const*>(sensor)->getResolution();
}

int ASensor_getMinDelay(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_DELAY_INVALID);
    return static_cast<Sensor const*>(sensor)->getMinDelay();
}

int ASensor_getFifoMaxEventCount(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_FIFO_COUNT_INVALID);
    return static_cast<Sensor const*>(sensor)->getFifoMaxEventCount();
}

int ASensor_getFifoReservedEventCount(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_FIFO_COUNT_INVALID);
    return static_cast<Sensor const*>(sensor)->getFifoReservedEventCount();
}

const char* ASensor_getStringType(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(nullptr);
    return static_cast<Sensor const*>(sensor)->getStringType().c_str();
}

int ASensor_getReportingMode(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(AREPORTING_MODE_INVALID);
    return static_cast<Sensor const*>(sensor)->getReportingMode();
}

bool ASensor_isWakeUpSensor(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(false);
    return static_cast<Sensor const*>(sensor)->isWakeUpSensor();
}

bool ASensor_isDirectChannelTypeSupported(ASensor const *sensor, int channelType) {
    RETURN_IF_SENSOR_IS_NULL(false);
    return static_cast<Sensor const *>(sensor)->isDirectChannelTypeSupported(channelType);
}

int ASensor_getHighestDirectReportRateLevel(ASensor const *sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_DIRECT_RATE_STOP);
    return static_cast<Sensor const *>(sensor)->getHighestDirectReportRateLevel();
}

int ASensor_getHandle(ASensor const* sensor) {
    RETURN_IF_SENSOR_IS_NULL(ASENSOR_INVALID);
    return static_cast<Sensor const*>(sensor)->getHandle();
}
