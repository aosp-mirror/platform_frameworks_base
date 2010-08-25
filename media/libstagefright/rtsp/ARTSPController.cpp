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
    : mState(DISCONNECTED),
      mLooper(looper) {
    mReflector = new AHandlerReflector<ARTSPController>(this);
    looper->registerHandler(mReflector);
}

ARTSPController::~ARTSPController() {
    CHECK_EQ((int)mState, (int)DISCONNECTED);
    mLooper->unregisterHandler(mReflector->id());
}

status_t ARTSPController::connect(const char *url) {
    Mutex::Autolock autoLock(mLock);

    if (mState != DISCONNECTED) {
        return ERROR_ALREADY_CONNECTED;
    }

    sp<AMessage> msg = new AMessage(kWhatConnectDone, mReflector->id());

    mHandler = new MyHandler(url, mLooper);

    mState = CONNECTING;

    mHandler->connect(msg);

    while (mState == CONNECTING) {
        mCondition.wait(mLock);
    }

    if (mState != CONNECTED) {
        mHandler.clear();
    }

    return mConnectionResult;
}

void ARTSPController::disconnect() {
    Mutex::Autolock autoLock(mLock);

    if (mState != CONNECTED) {
        return;
    }

    sp<AMessage> msg = new AMessage(kWhatDisconnectDone, mReflector->id());
    mHandler->disconnect(msg);

    while (mState == CONNECTED) {
        mCondition.wait(mLock);
    }

    mHandler.clear();
}

void ARTSPController::seek(int64_t timeUs) {
    Mutex::Autolock autoLock(mLock);

    if (mState != CONNECTED) {
        return;
    }

    mHandler->seek(timeUs);
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

void ARTSPController::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatConnectDone:
        {
            Mutex::Autolock autoLock(mLock);

            CHECK(msg->findInt32("result", &mConnectionResult));
            mState = (mConnectionResult == OK) ? CONNECTED : DISCONNECTED;

            mCondition.signal();
            break;
        }

        case kWhatDisconnectDone:
        {
            Mutex::Autolock autoLock(mLock);
            mState = DISCONNECTED;
            mCondition.signal();
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

}  // namespace android
