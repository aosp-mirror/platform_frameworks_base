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

//#define LOG_NDEBUG 0
#define LOG_TAG "ALooperRoster"
#include <utils/Log.h>

#include "ALooperRoster.h"

#include "ADebug.h"
#include "AHandler.h"
#include "AMessage.h"

namespace android {

ALooperRoster::ALooperRoster()
    : mNextHandlerID(1) {
}

ALooper::handler_id ALooperRoster::registerHandler(
        const sp<ALooper> looper, const sp<AHandler> &handler) {
    Mutex::Autolock autoLock(mLock);

    if (handler->id() != 0) {
        CHECK(!"A handler must only be registered once.");
        return INVALID_OPERATION;
    }

    HandlerInfo info;
    info.mLooper = looper;
    info.mHandler = handler;
    ALooper::handler_id handlerID = mNextHandlerID++;
    mHandlers.add(handlerID, info);

    handler->setID(handlerID);

    return handlerID;
}

void ALooperRoster::unregisterHandler(ALooper::handler_id handlerID) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mHandlers.indexOfKey(handlerID);
    CHECK(index >= 0);

    const HandlerInfo &info = mHandlers.valueAt(index);
    info.mHandler->setID(0);

    mHandlers.removeItemsAt(index);
}

void ALooperRoster::postMessage(
        const sp<AMessage> &msg, int64_t delayUs) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mHandlers.indexOfKey(msg->target());

    if (index < 0) {
        LOG(WARNING) << "failed to post message. Target handler not registered.";
        return;
    }

    const HandlerInfo &info = mHandlers.valueAt(index);
    info.mLooper->post(msg, delayUs);
}

void ALooperRoster::deliverMessage(const sp<AMessage> &msg) {
    sp<AHandler> handler;

    {
        Mutex::Autolock autoLock(mLock);

        ssize_t index = mHandlers.indexOfKey(msg->target());

        if (index < 0) {
            LOG(WARNING) << "failed to deliver message. Target handler not registered.";
            return;
        }

        const HandlerInfo &info = mHandlers.valueAt(index);
        handler = info.mHandler;
    }

    handler->onMessageReceived(msg);
}

sp<ALooper> ALooperRoster::findLooper(ALooper::handler_id handlerID) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mHandlers.indexOfKey(handlerID);

    if (index < 0) {
        return NULL;
    }

    return mHandlers.valueAt(index).mLooper;
}

}  // namespace android
