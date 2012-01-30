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

#ifndef ANDROID_SURFACE_FLINGER_EVENT_THREAD_H
#define ANDROID_SURFACE_FLINGER_EVENT_THREAD_H

#include <stdint.h>
#include <sys/types.h>

#include <gui/IDisplayEventConnection.h>

#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>

#include "DisplayEventConnection.h"

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

class SurfaceFlinger;
class DisplayHardware;
class DisplayEventConnection;

// ---------------------------------------------------------------------------

class EventThread : public Thread {
    friend class DisplayEventConnection;

public:
    EventThread(const sp<SurfaceFlinger>& flinger);

    sp<DisplayEventConnection> createEventConnection() const;

    status_t registerDisplayEventConnection(
            const sp<DisplayEventConnection>& connection);

    status_t unregisterDisplayEventConnection(
            const wp<DisplayEventConnection>& connection);

    void setVsyncRate(uint32_t count,
            const wp<DisplayEventConnection>& connection);

    void requestNextVsync(const wp<DisplayEventConnection>& connection);

    nsecs_t getLastVSyncTimestamp() const;

    nsecs_t getVSyncPeriod() const;

    void dump(String8& result, char* buffer, size_t SIZE) const;

private:
    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

    struct ConnectionInfo {
        ConnectionInfo() : count(-1) { }

        // count >= 1 : continuous event. count is the vsync rate
        // count == 0 : one-shot event that has not fired
        // count ==-1 : one-shot event that fired this round / disabled
        // count ==-2 : one-shot event that fired the round before
        int32_t count;
    };

    void removeDisplayEventConnection(
            const wp<DisplayEventConnection>& connection);

    ConnectionInfo* getConnectionInfoLocked(
            const wp<DisplayEventConnection>& connection);

    // constants
    sp<SurfaceFlinger> mFlinger;
    const DisplayHardware& mHw;

    mutable Mutex mLock;
    mutable Condition mCondition;

    // protected by mLock
    KeyedVector< wp<DisplayEventConnection>, ConnectionInfo > mDisplayEventConnections;
    nsecs_t mLastVSyncTimestamp;

    // main thread only
    size_t mDeliveredEvents;
};

// ---------------------------------------------------------------------------

}; // namespace android

// ---------------------------------------------------------------------------

#endif /* ANDROID_SURFACE_FLINGER_EVENT_THREAD_H */
