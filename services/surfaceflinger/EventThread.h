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
#include <utils/SortedVector.h>

#include "DisplayEventConnection.h"

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

class SurfaceFlinger;
class DisplayHardware;

// ---------------------------------------------------------------------------

class EventThread : public Thread {
    friend class DisplayEventConnection;

public:
    EventThread(const sp<SurfaceFlinger>& flinger);

    status_t registerDisplayEventConnection(
            const sp<DisplayEventConnection>& connection);

    status_t unregisterDisplayEventConnection(
            const wp<DisplayEventConnection>& connection);

    void dump(String8& result, char* buffer, size_t SIZE) const;

private:
    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

    status_t removeDisplayEventConnection(
            const wp<DisplayEventConnection>& connection);

    // constants
    sp<SurfaceFlinger> mFlinger;
    const DisplayHardware& mHw;

    mutable Mutex mLock;
    mutable Condition mCondition;

    // protected by mLock
    SortedVector<wp<DisplayEventConnection> > mDisplayEventConnections;
    size_t mDeliveredEvents;
};

// ---------------------------------------------------------------------------

}; // namespace android

// ---------------------------------------------------------------------------

#endif /* ANDROID_SURFACE_FLINGER_EVENT_THREAD_H */
