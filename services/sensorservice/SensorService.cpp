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

#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/BinderService.h>

#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>

#include <hardware/sensors.h>

#include "SensorService.h"

namespace android {
// ---------------------------------------------------------------------------

/*
 * TODO:
 * - handle per-connection event rate
 * - filter events per connection
 * - make sure to keep the last value of each event type so we can quickly
 *   send something to application when they enable a sensor that is already
 *   active (the issue here is that it can take time before a value is
 *   produced by the h/w if the rate is low or if it's a one-shot sensor).
 */

SensorService::SensorService()
    : Thread(false),
      mDump("android.permission.DUMP")
{
}

void SensorService::onFirstRef()
{
    status_t err = hw_get_module(SENSORS_HARDWARE_MODULE_ID,
            (hw_module_t const**)&mSensorModule);

    LOGE_IF(err, "couldn't load %s module (%s)",
            SENSORS_HARDWARE_MODULE_ID, strerror(-err));

    err = sensors_open(&mSensorModule->common, &mSensorDevice);

    LOGE_IF(err, "couldn't open device for module %s (%s)",
            SENSORS_HARDWARE_MODULE_ID, strerror(-err));

    LOGD("nuSensorService starting...");

    struct sensor_t const* list;
    int count = mSensorModule->get_sensors_list(mSensorModule, &list);
    for (int i=0 ; i<count ; i++) {
        Sensor sensor(list + i);
        LOGI("%s", sensor.getName().string());
        mSensorList.add(sensor);
        mSensorDevice->activate(mSensorDevice, sensor.getHandle(), 0);
    }

    run("SensorService", PRIORITY_URGENT_DISPLAY);
}

SensorService::~SensorService()
{
}

status_t SensorService::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 1024;
    char buffer[SIZE];
    String8 result;
    if (!mDump.checkCalling()) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
        Mutex::Autolock _l(mLock);
        snprintf(buffer, SIZE, "%d connections / %d active\n",
                mConnections.size(), mActiveConnections.size());
        result.append(buffer);
        snprintf(buffer, SIZE, "Active sensors:\n");
        result.append(buffer);
        for (size_t i=0 ; i<mActiveSensors.size() ; i++) {
            snprintf(buffer, SIZE, "handle=%d, connections=%d\n",
                    mActiveSensors.keyAt(i),
                    mActiveSensors.valueAt(i)->getNumConnections());
            result.append(buffer);
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

bool SensorService::threadLoop()
{
    LOGD("nuSensorService thread starting...");

    sensors_event_t buffer[16];
    struct sensors_poll_device_t* device = mSensorDevice;
    ssize_t count;

    do {
        count = device->poll(device, buffer, sizeof(buffer)/sizeof(*buffer));
        if (count<0) {
            LOGE("sensor poll failed (%s)", strerror(-count));
            break;
        }

        const SortedVector< wp<SensorEventConnection> > activeConnections(
                getActiveConnections());

        size_t numConnections = activeConnections.size();
        if (numConnections) {
            for (size_t i=0 ; i<numConnections ; i++) {
                sp<SensorEventConnection> connection(activeConnections[i].promote());
                if (connection != 0) {
                    connection->sendEvents(buffer, count);
                }
            }
        }

    } while (count >= 0 || Thread::exitPending());

    LOGW("Exiting SensorService::threadLoop!");
    return false;
}

SortedVector< wp<SensorService::SensorEventConnection> >
SensorService::getActiveConnections() const
{
    Mutex::Autolock _l(mLock);
    return mActiveConnections;
}

Vector<Sensor> SensorService::getSensorList()
{
    return mSensorList;
}

sp<ISensorEventConnection> SensorService::createSensorEventConnection()
{
    sp<SensorEventConnection> result(new SensorEventConnection(this));
    Mutex::Autolock _l(mLock);
    mConnections.add(result);
    return result;
}

void SensorService::cleanupConnection(const wp<SensorEventConnection>& connection)
{
    Mutex::Autolock _l(mLock);
    ssize_t index = mConnections.indexOf(connection);
    if (index >= 0) {

        size_t size = mActiveSensors.size();
        for (size_t i=0 ; i<size ; ) {
            SensorRecord* rec = mActiveSensors.valueAt(i);
            if (rec && rec->removeConnection(connection)) {
                mSensorDevice->activate(mSensorDevice, mActiveSensors.keyAt(i), 0);
                mActiveSensors.removeItemsAt(i, 1);
                delete rec;
                size--;
            } else {
                i++;
            }
        }

        mActiveConnections.remove(connection);
        mConnections.removeItemsAt(index, 1);
    }
}

status_t SensorService::enable(const sp<SensorEventConnection>& connection,
        int handle)
{
    status_t err = NO_ERROR;
    Mutex::Autolock _l(mLock);
    SensorRecord* rec = mActiveSensors.valueFor(handle);
    if (rec == 0) {
        rec = new SensorRecord(connection);
        mActiveSensors.add(handle, rec);
        err = mSensorDevice->activate(mSensorDevice, handle, 1);
        LOGE_IF(err, "Error activating sensor %d (%s)", handle, strerror(-err));
    } else {
        err = rec->addConnection(connection);
    }
    if (err == NO_ERROR) {
        // connection now active
        connection->addSensor(handle);
        if (mActiveConnections.indexOf(connection) < 0) {
            mActiveConnections.add(connection);
        }
    }
    return err;
}

status_t SensorService::disable(const sp<SensorEventConnection>& connection,
        int handle)
{
    status_t err = NO_ERROR;
    Mutex::Autolock _l(mLock);
    SensorRecord* rec = mActiveSensors.valueFor(handle);
    LOGW("sensor (handle=%d) is not enabled", handle);
    if (rec) {
        // see if this connection becomes inactive
        connection->removeSensor(handle);
        if (connection->hasAnySensor() == false) {
            mActiveConnections.remove(connection);
        }
        // see if this sensor becomes inactive
        if (rec->removeConnection(connection)) {
            mActiveSensors.removeItem(handle);
            delete rec;
            err = mSensorDevice->activate(mSensorDevice, handle, 0);
        }
    }
    return err;
}

status_t SensorService::setRate(const sp<SensorEventConnection>& connection,
        int handle, nsecs_t ns)
{
    status_t err = NO_ERROR;
    Mutex::Autolock _l(mLock);

    err = mSensorDevice->setDelay(mSensorDevice, handle, ns);

    // TODO: handle rate per connection
    return err;
}

// ---------------------------------------------------------------------------

SensorService::SensorRecord::SensorRecord(
        const sp<SensorEventConnection>& connection)
{
    mConnections.add(connection);
}

status_t SensorService::SensorRecord::addConnection(
        const sp<SensorEventConnection>& connection)
{
    if (mConnections.indexOf(connection) < 0) {
        mConnections.add(connection);
    }
    return NO_ERROR;
}

bool SensorService::SensorRecord::removeConnection(
        const wp<SensorEventConnection>& connection)
{
    ssize_t index = mConnections.indexOf(connection);
    if (index >= 0) {
        mConnections.removeItemsAt(index, 1);
    }
    return mConnections.size() ? false : true;
}

// ---------------------------------------------------------------------------

SensorService::SensorEventConnection::SensorEventConnection(
        const sp<SensorService>& service)
    : mService(service), mChannel(new SensorChannel())
{
}

SensorService::SensorEventConnection::~SensorEventConnection()
{
    mService->cleanupConnection(this);
}

void SensorService::SensorEventConnection::onFirstRef()
{
}

void SensorService::SensorEventConnection::addSensor(int32_t handle) {
    if (mSensorList.indexOf(handle) <= 0) {
        mSensorList.add(handle);
    }
}

void SensorService::SensorEventConnection::removeSensor(int32_t handle) {
    mSensorList.remove(handle);
}

bool SensorService::SensorEventConnection::hasSensor(int32_t handle) const {
    return mSensorList.indexOf(handle) >= 0;
}

bool SensorService::SensorEventConnection::hasAnySensor() const {
    return mSensorList.size() ? true : false;
}

status_t SensorService::SensorEventConnection::sendEvents(
        sensors_event_t const* buffer, size_t count)
{
    // TODO: we should only send the events for the sensors this connection
    // is registered for.

    ssize_t size = mChannel->write(buffer, count*sizeof(sensors_event_t));
    if (size == -EAGAIN) {
        // the destination doesn't accept events anymore, it's probably
        // full. For now, we just drop the events on the floor.
        LOGW("dropping %d events on the floor", count);
        return size;
    }

    LOGE_IF(size<0, "dropping %d events on the floor (%s)",
            count, strerror(-size));

    return size < 0 ? size : NO_ERROR;
}

sp<SensorChannel> SensorService::SensorEventConnection::getSensorChannel() const
{
    return mChannel;
}

status_t SensorService::SensorEventConnection::enableDisable(
        int handle, bool enabled)
{
    status_t err;
    if (enabled) {
        err = mService->enable(this, handle);
    } else {
        err = mService->disable(this, handle);
    }
    return err;
}

status_t SensorService::SensorEventConnection::setEventRate(
        int handle, nsecs_t ns)
{
    return mService->setRate(this, handle, ns);
}

// ---------------------------------------------------------------------------
}; // namespace android

