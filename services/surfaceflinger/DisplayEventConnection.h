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

#ifndef ANDROID_SURFACE_FLINGER_DISPLAY_EVENT_CONNECTION_H
#define ANDROID_SURFACE_FLINGER_DISPLAY_EVENT_CONNECTION_H

#include <stdint.h>
#include <sys/types.h>

#include <gui/IDisplayEventConnection.h>

#include <utils/Errors.h>
#include <gui/DisplayEventReceiver.h>

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

class BitTube;
class EventThread;

// ---------------------------------------------------------------------------

class DisplayEventConnection : public BnDisplayEventConnection {
public:
    DisplayEventConnection(const sp<EventThread>& flinger);

    status_t postEvent(const DisplayEventReceiver::Event& event);

private:
    virtual ~DisplayEventConnection();
    virtual void onFirstRef();
    virtual sp<BitTube> getDataChannel() const;
    virtual void setVsyncRate(uint32_t count);
    virtual void requestNextVsync();    // asynchronous

    sp<EventThread> const mEventThread;
    sp<BitTube> const mChannel;
};

// ---------------------------------------------------------------------------

}; // namespace android

// ---------------------------------------------------------------------------

#endif /* ANDROID_SURFACE_FLINGER_DISPLAY_EVENT_CONNECTION_H */
