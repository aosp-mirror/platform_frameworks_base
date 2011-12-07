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

#ifndef ANDROID_GUI_DISPLAY_EVENT_H
#define ANDROID_GUI_DISPLAY_EVENT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>

#include <binder/IInterface.h>

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

class BitTube;
class IDisplayEventConnection;

// ----------------------------------------------------------------------------

class DisplayEventReceiver {
public:
    enum {
        DISPLAY_EVENT_VSYNC = 'vsyn'
    };

    struct Event {

        struct Header {
            uint32_t type;
            nsecs_t timestamp;
        };

        struct VSync {
            uint32_t count;
        };

        Header header;
        union {
            VSync vsync;
        };
    };

public:
    /*
     * DisplayEventReceiver creates and registers an event connection with
     * SurfaceFlinger. Events start being delivered immediately.
     */
    DisplayEventReceiver();

    /*
     * ~DisplayEventReceiver severs the connection with SurfaceFlinger, new events
     * stop being delivered immediately. Note that the queue could have
     * some events pending. These will be delivered.
     */
    ~DisplayEventReceiver();

    /*
     * initCheck returns the state of DisplayEventReceiver after construction.
     */
    status_t initCheck() const;

    /*
     * getFd returns the file descriptor to use to receive events.
     * OWNERSHIP IS RETAINED by DisplayEventReceiver. DO NOT CLOSE this
     * file-descriptor.
     */
    int getFd() const;

    /*
     * getEvents reads event from the queue and returns how many events were
     * read. Returns 0 if there are no more events or a negative error code.
     * If NOT_ENOUGH_DATA is returned, the object has become invalid forever, it
     * should be destroyed and getEvents() shouldn't be called again.
     */
    ssize_t getEvents(Event* events, size_t count);

    /*
     * setVsyncRate() sets the Event::VSync delivery rate. A value of
     * 1 returns every Event::VSync. A value of 2 returns every other event,
     * etc... a value of 0 returns no event unless  requestNextVsync() has
     * been called.
     */
    status_t setVsyncRate(uint32_t count);

    /*
     * requestNextVsync() schedules the next Event::VSync. It has no effect
     * if the vsync rate is > 0.
     */
    status_t requestNextVsync();

private:
    sp<IDisplayEventConnection> mEventConnection;
    sp<BitTube> mDataChannel;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_DISPLAY_EVENT_H
