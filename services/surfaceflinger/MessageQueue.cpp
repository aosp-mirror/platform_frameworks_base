/*
 * Copyright (C) 2009 The Android Open Source Project
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
#include <errno.h>
#include <sys/types.h>

#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/Log.h>
#include <binder/IPCThreadState.h>

#include "MessageQueue.h"

namespace android {

// ---------------------------------------------------------------------------

MessageBase::MessageBase()
    : MessageHandler() {
}

MessageBase::~MessageBase() {
}

void MessageBase::handleMessage(const Message&) {
    this->handler();
    barrier.open();
};

// ---------------------------------------------------------------------------

MessageQueue::MessageQueue()
    : mLooper(new Looper(true)),
      mInvalidatePending(0)
{
}

MessageQueue::~MessageQueue() {
}

void MessageQueue::waitMessage() {
    do {
        // handle invalidate events first
        if (android_atomic_and(0, &mInvalidatePending) != 0)
            break;

        IPCThreadState::self()->flushCommands();

        int32_t ret = mLooper->pollOnce(-1);
        switch (ret) {
            case ALOOPER_POLL_WAKE:
                // we got woken-up there is work to do in the main loop
                continue;

            case ALOOPER_POLL_CALLBACK:
                // callback was handled, loop again
                continue;

            case ALOOPER_POLL_TIMEOUT:
                // timeout (should not happen)
                continue;

            case ALOOPER_POLL_ERROR:
                LOGE("ALOOPER_POLL_ERROR");
                continue;

            default:
                // should not happen
                LOGE("Looper::pollOnce() returned unknown status %d", ret);
                continue;
        }
    } while (true);
}

status_t MessageQueue::postMessage(
        const sp<MessageBase>& messageHandler, nsecs_t relTime)
{
    const Message dummyMessage;
    if (relTime > 0) {
        mLooper->sendMessageDelayed(relTime, messageHandler, dummyMessage);
    } else {
        mLooper->sendMessage(messageHandler, dummyMessage);
    }
    return NO_ERROR;
}

status_t MessageQueue::invalidate() {
    android_atomic_or(1, &mInvalidatePending);
    mLooper->wake();
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

}; // namespace android
