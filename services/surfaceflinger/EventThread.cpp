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
#include <sys/types.h>

#include <gui/IDisplayEventConnection.h>
#include <gui/DisplayEventReceiver.h>

#include <utils/Errors.h>

#include "DisplayHardware/DisplayHardware.h"
#include "DisplayEventConnection.h"
#include "EventThread.h"
#include "SurfaceFlinger.h"

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

EventThread::EventThread(const sp<SurfaceFlinger>& flinger)
    : mFlinger(flinger),
      mHw(flinger->graphicPlane(0).displayHardware()),
      mDeliveredEvents(0)
{
}

void EventThread::onFirstRef() {
    run("EventThread", PRIORITY_URGENT_DISPLAY + PRIORITY_MORE_FAVORABLE);
}

status_t EventThread::registerDisplayEventConnection(
        const sp<DisplayEventConnection>& connection) {
    Mutex::Autolock _l(mLock);
    mDisplayEventConnections.add(connection);
    mCondition.signal();
    return NO_ERROR;
}

status_t EventThread::unregisterDisplayEventConnection(
        const wp<DisplayEventConnection>& connection) {
    Mutex::Autolock _l(mLock);
    mDisplayEventConnections.remove(connection);
    mCondition.signal();
    return NO_ERROR;
}

bool EventThread::threadLoop() {

    nsecs_t timestamp;
    Mutex::Autolock _l(mLock);
    do {
        // wait for listeners
        while (!mDisplayEventConnections.size()) {
            mCondition.wait(mLock);
        }

        // wait for vsync
        mLock.unlock();
        timestamp = mHw.waitForVSync();
        mLock.lock();

        // make sure we still have some listeners
    } while (!mDisplayEventConnections.size());


    // dispatch vsync events to listeners...
    mDeliveredEvents++;
    const size_t count = mDisplayEventConnections.size();

    DisplayEventReceiver::Event vsync;
    vsync.header.type = DisplayEventReceiver::DISPLAY_EVENT_VSYNC;
    vsync.header.timestamp = timestamp;
    vsync.vsync.count = mDeliveredEvents;

    for (size_t i=0 ; i<count ; i++) {
        sp<DisplayEventConnection> conn(mDisplayEventConnections.itemAt(i).promote());
        // make sure the connection didn't die
        if (conn != NULL) {
            status_t err = conn->postEvent(vsync);
            if (err == -EAGAIN || err == -EWOULDBLOCK) {
                // The destination doesn't accept events anymore, it's probably
                // full. For now, we just drop the events on the floor.
                // Note that some events cannot be dropped and would have to be
                // re-sent later. Right-now we don't have the ability to do
                // this, but it doesn't matter for VSYNC.
            } else if (err < 0) {
                // handle any other error on the pipe as fatal. the only
                // reasonable thing to do is to clean-up this connection.
                // The most common error we'll get here is -EPIPE.
                mDisplayEventConnections.remove(conn);
            }
        }
    }

    return true;
}

status_t EventThread::readyToRun() {
    LOGI("EventThread ready to run.");
    return NO_ERROR;
}

void EventThread::dump(String8& result, char* buffer, size_t SIZE) const {
    Mutex::Autolock _l(mLock);
    result.append("VSYNC state:\n");
    snprintf(buffer, SIZE, "  numListeners=%u, events-delivered: %u\n",
            mDisplayEventConnections.size(), mDeliveredEvents);
    result.append(buffer);
}

// ---------------------------------------------------------------------------

}; // namespace android
