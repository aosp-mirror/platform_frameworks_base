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
#include <gui/BitTube.h>
#include <gui/DisplayEventReceiver.h>

#include <utils/Errors.h>

#include "SurfaceFlinger.h"
#include "DisplayEventConnection.h"
#include "EventThread.h"

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

DisplayEventConnection::DisplayEventConnection(
        const sp<EventThread>& eventThread)
    : mEventThread(eventThread), mChannel(new BitTube())
{
}

DisplayEventConnection::~DisplayEventConnection() {
    mEventThread->unregisterDisplayEventConnection(this);
}

void DisplayEventConnection::onFirstRef() {
    // NOTE: mEventThread doesn't hold a strong reference on us
    mEventThread->registerDisplayEventConnection(this);
}

sp<BitTube> DisplayEventConnection::getDataChannel() const {
    return mChannel;
}

void DisplayEventConnection::setVsyncRate(uint32_t count) {
    mEventThread->setVsyncRate(count, this);
}

void DisplayEventConnection::requestNextVsync() {
    mEventThread->requestNextVsync(this);
}

status_t DisplayEventConnection::postEvent(const DisplayEventReceiver::Event& event)
{
    ssize_t size = mChannel->write(&event, sizeof(DisplayEventReceiver::Event));
    return size < 0 ? status_t(size) : status_t(NO_ERROR);
}

// ---------------------------------------------------------------------------

}; // namespace android
