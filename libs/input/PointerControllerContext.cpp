/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "PointerControllerContext.h"
#include "PointerController.h"

namespace {
// Time to wait before starting the fade when the pointer is inactive.
const nsecs_t INACTIVITY_TIMEOUT_DELAY_TIME_NORMAL = 15 * 1000 * 1000000LL; // 15 seconds
const nsecs_t INACTIVITY_TIMEOUT_DELAY_TIME_SHORT = 3 * 1000 * 1000000LL;   // 3 seconds

// The number of events to be read at once for DisplayEventReceiver.
const int EVENT_BUFFER_SIZE = 100;
} // namespace

namespace android {

// --- PointerControllerContext ---

PointerControllerContext::PointerControllerContext(
        const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
        const sp<SpriteController>& spriteController, PointerController& controller)
      : mPolicy(policy),
        mLooper(looper),
        mSpriteController(spriteController),
        mHandler(new MessageHandler()),
        mCallback(new LooperCallback()),
        mController(controller) {
    std::scoped_lock lock(mLock);
    mLocked.inactivityTimeout = InactivityTimeout::NORMAL;
    mLocked.animationPending = false;
}

PointerControllerContext::~PointerControllerContext() {
    mLooper->removeMessages(mHandler);
}

void PointerControllerContext::setInactivityTimeout(InactivityTimeout inactivityTimeout) {
    std::scoped_lock lock(mLock);

    if (mLocked.inactivityTimeout != inactivityTimeout) {
        mLocked.inactivityTimeout = inactivityTimeout;
        resetInactivityTimeoutLocked();
    }
}

void PointerControllerContext::startAnimation() {
    std::scoped_lock lock(mLock);
    if (!mLocked.animationPending) {
        mLocked.animationPending = true;
        mLocked.animationTime = systemTime(SYSTEM_TIME_MONOTONIC);
        mDisplayEventReceiver.requestNextVsync();
    }
}

void PointerControllerContext::resetInactivityTimeout() {
    std::scoped_lock lock(mLock);
    resetInactivityTimeoutLocked();
}

void PointerControllerContext::resetInactivityTimeoutLocked() REQUIRES(mLock) {
    mLooper->removeMessages(mHandler, MessageHandler::MSG_INACTIVITY_TIMEOUT);

    nsecs_t timeout = mLocked.inactivityTimeout == InactivityTimeout::SHORT
            ? INACTIVITY_TIMEOUT_DELAY_TIME_SHORT
            : INACTIVITY_TIMEOUT_DELAY_TIME_NORMAL;
    mLooper->sendMessageDelayed(timeout, mHandler, MessageHandler::MSG_INACTIVITY_TIMEOUT);
}

void PointerControllerContext::removeInactivityTimeout() {
    std::scoped_lock lock(mLock);
    mLooper->removeMessages(mHandler, MessageHandler::MSG_INACTIVITY_TIMEOUT);
}

void PointerControllerContext::setAnimationPending(bool animationPending) {
    std::scoped_lock lock(mLock);
    mLocked.animationPending = animationPending;
}

nsecs_t PointerControllerContext::getAnimationTime() {
    std::scoped_lock lock(mLock);
    return mLocked.animationTime;
}

void PointerControllerContext::setHandlerController(std::shared_ptr<PointerController> controller) {
    mHandler->pointerController = controller;
}

void PointerControllerContext::setCallbackController(
        std::shared_ptr<PointerController> controller) {
    mCallback->pointerController = controller;
}

sp<PointerControllerPolicyInterface> PointerControllerContext::getPolicy() {
    return mPolicy;
}

sp<SpriteController> PointerControllerContext::getSpriteController() {
    return mSpriteController;
}

void PointerControllerContext::initializeDisplayEventReceiver() {
    if (mDisplayEventReceiver.initCheck() == NO_ERROR) {
        mLooper->addFd(mDisplayEventReceiver.getFd(), Looper::POLL_CALLBACK, Looper::EVENT_INPUT,
                       mCallback, nullptr);
    } else {
        ALOGE("Failed to initialize DisplayEventReceiver.");
    }
}

void PointerControllerContext::handleDisplayEvents() {
    bool gotVsync = false;
    ssize_t n;
    nsecs_t timestamp;
    DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
    while ((n = mDisplayEventReceiver.getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
        for (size_t i = 0; i < static_cast<size_t>(n); ++i) {
            if (buf[i].header.type == DisplayEventReceiver::DISPLAY_EVENT_VSYNC) {
                timestamp = buf[i].header.timestamp;
                gotVsync = true;
            }
        }
    }
    if (gotVsync) {
        mController.doAnimate(timestamp);
    }
}

void PointerControllerContext::MessageHandler::handleMessage(const Message& message) {
    std::shared_ptr<PointerController> controller = pointerController.lock();

    if (controller == nullptr) {
        ALOGE("PointerController instance was released before processing message: what=%d",
              message.what);
        return;
    }
    switch (message.what) {
        case MSG_INACTIVITY_TIMEOUT:
            controller->doInactivityTimeout();
            break;
    }
}

int PointerControllerContext::LooperCallback::handleEvent(int /* fd */, int events,
                                                          void* /* data */) {
    std::shared_ptr<PointerController> controller = pointerController.lock();
    if (controller == nullptr) {
        ALOGW("PointerController instance was released with pending callbacks.  events=0x%x",
              events);
        return 0; // Remove the callback, the PointerController is gone anyways
    }
    if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
        ALOGE("Display event receiver pipe was closed or an error occurred.  events=0x%x", events);
        return 0; // remove the callback
    }

    if (!(events & Looper::EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  events=0x%x", events);
        return 1; // keep the callback
    }

    controller->mContext.handleDisplayEvents();
    return 1; // keep the callback
}

} // namespace android
