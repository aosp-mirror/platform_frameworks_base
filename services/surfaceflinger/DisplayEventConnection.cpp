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

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

DisplayEventConnection::DisplayEventConnection(
        const sp<SurfaceFlinger>& flinger)
    : mFlinger(flinger), mChannel(new BitTube())
{
}

DisplayEventConnection::~DisplayEventConnection() {
    mFlinger->cleanupDisplayEventConnection(this);
}

void DisplayEventConnection::onFirstRef() {
    // nothing to do here for now.
}

sp<BitTube> DisplayEventConnection::getDataChannel() const {
    return mChannel;
}

status_t DisplayEventConnection::postEvent(const DisplayEventReceiver::Event& event)
{
    ssize_t size = mChannel->write(&event, sizeof(DisplayEventReceiver::Event));
    return size < 0 ? status_t(size) : status_t(NO_ERROR);
}


// ---------------------------------------------------------------------------

}; // namespace android
