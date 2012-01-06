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

#include <string.h>

#include <utils/Errors.h>

#include <gui/BitTube.h>
#include <gui/DisplayEventReceiver.h>
#include <gui/IDisplayEventConnection.h>

#include <private/gui/ComposerService.h>

#include <surfaceflinger/ISurfaceComposer.h>

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

DisplayEventReceiver::DisplayEventReceiver() {
    sp<ISurfaceComposer> sf(ComposerService::getComposerService());
    if (sf != NULL) {
        mEventConnection = sf->createDisplayEventConnection();
        if (mEventConnection != NULL) {
            mDataChannel = mEventConnection->getDataChannel();
        }
    }
}

DisplayEventReceiver::~DisplayEventReceiver() {
}

status_t DisplayEventReceiver::initCheck() const {
    if (mDataChannel != NULL)
        return NO_ERROR;
    return NO_INIT;
}

int DisplayEventReceiver::getFd() const {
    if (mDataChannel == NULL)
        return NO_INIT;

    return mDataChannel->getFd();
}

status_t DisplayEventReceiver::setVsyncRate(uint32_t count) {
    if (int32_t(count) < 0)
        return BAD_VALUE;

    if (mEventConnection != NULL) {
        mEventConnection->setVsyncRate(count);
        return NO_ERROR;
    }
    return NO_INIT;
}

status_t DisplayEventReceiver::requestNextVsync() {
    if (mEventConnection != NULL) {
        mEventConnection->requestNextVsync();
        return NO_ERROR;
    }
    return NO_INIT;
}


ssize_t DisplayEventReceiver::getEvents(DisplayEventReceiver::Event* events,
        size_t count) {
    ssize_t size = mDataChannel->read(events, sizeof(events[0])*count);
    ALOGE_IF(size<0,
            "DisplayEventReceiver::getEvents error (%s)",
            strerror(-size));
    if (size >= 0) {
        // Note: if (size % sizeof(events[0])) != 0, we've got a
        // partial read. This can happen if the queue filed up (ie: if we
        // didn't pull from it fast enough).
        // We discard the partial event and rely on the sender to
        // re-send the event if appropriate (some events, like VSYNC
        // can be lost forever).

        // returns number of events read
        size /= sizeof(events[0]);
    }
    return size;
}

// ---------------------------------------------------------------------------

}; // namespace android
