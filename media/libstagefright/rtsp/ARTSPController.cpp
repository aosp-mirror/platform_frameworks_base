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

#include "ARTSPController.h"

#include "MyHandler.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

ARTSPController::ARTSPController(const sp<ALooper> &looper)
    : mLooper(looper) {
}

ARTSPController::~ARTSPController() {
}

status_t ARTSPController::connect(const char *url) {
    if (mHandler != NULL) {
        return ERROR_ALREADY_CONNECTED;
    }

    mHandler = new MyHandler(url, mLooper);
    sleep(10);

    return OK;
}

void ARTSPController::disconnect() {
    if (mHandler == NULL) {
        return;
    }

    mHandler.clear();
}

size_t ARTSPController::countTracks() {
    if (mHandler == NULL) {
        return 0;
    }

    return mHandler->countTracks();
}

sp<MediaSource> ARTSPController::getTrack(size_t index) {
    CHECK(mHandler != NULL);

    return mHandler->getPacketSource(index);
}

sp<MetaData> ARTSPController::getTrackMetaData(
        size_t index, uint32_t flags) {
    CHECK(mHandler != NULL);

    return mHandler->getPacketSource(index)->getFormat();
}

}  // namespace android
