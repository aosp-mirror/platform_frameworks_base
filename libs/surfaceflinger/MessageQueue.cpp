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
#include <utils/IPCThreadState.h>

#include "MessageQueue.h"

namespace android {

// ---------------------------------------------------------------------------

MessageQueue::MessageQueue()
{
    mInvalidateMessage = new MessageBase(INVALIDATE);
}

MessageQueue::~MessageQueue()
{
}

MessageList::NODE_PTR MessageQueue::waitMessage(nsecs_t timeout)
{
    MessageList::NODE_PTR result;
    bool again;
    do {
        const nsecs_t timeoutTime = systemTime() + timeout;
        while (true) {
            Mutex::Autolock _l(mLock);
            nsecs_t now = systemTime();
            nsecs_t nextEventTime = -1;

            // invalidate messages are always handled first
            if (mInvalidate) {
                mInvalidate = false;
                mInvalidateMessage->when = now;
                result = mInvalidateMessage;
                break;
            }

            result = mMessages.head();

            if (result != 0) {
                if (result->when <= now) {
                    // there is a message to deliver
                    mMessages.remove(result);
                    result->detach();
                    break;
                }
                if (timeout>=0 && timeoutTime < now) {
                    // we timed-out, return a NULL message
                    result = 0;
                    break;
                }
                nextEventTime = result->when;
                result = 0;
            }

            if (timeout >= 0 && nextEventTime > 0) {
                if (nextEventTime > timeoutTime) {
                    nextEventTime = timeoutTime;
                }
            }

            if (nextEventTime >= 0) {
                //LOGD("nextEventTime = %lld ms", nextEventTime);
                if (nextEventTime > 0) {
                    // we're about to wait, flush the binder command buffer
                    IPCThreadState::self()->flushCommands();
                    mCondition.wait(mLock, nextEventTime);
                }
            } else {
                //LOGD("going to wait");
                // we're about to wait, flush the binder command buffer
                IPCThreadState::self()->flushCommands();
                mCondition.wait(mLock);
            }
        } 
        // here we're not holding the lock anymore

        if (result == 0)
            break;

        again = result->handler();
        if (again) {
            // the message has been processed. release our reference to it
            // without holding the lock.
            result = 0;
        }
        
    } while (again);
    return result;
}

status_t MessageQueue::postMessage(
        const MessageList::NODE_PTR& message, nsecs_t relTime, uint32_t flags)
{
    return queueMessage(message, relTime, flags);
}

status_t MessageQueue::invalidate() {
    Mutex::Autolock _l(mLock);
    mInvalidate = true;
    mCondition.signal();
    return NO_ERROR;
}

status_t MessageQueue::queueMessage(
        const MessageList::NODE_PTR& message, nsecs_t relTime, uint32_t flags)
{
    Mutex::Autolock _l(mLock);
    message->when = systemTime() + relTime;
    mMessages.insert(message);
    
    //LOGD("MessageQueue::queueMessage time = %lld ms", message->when);
    //dumpLocked(message);

    mCondition.signal();
    return NO_ERROR;
}

void MessageQueue::dump(const MessageList::NODE_PTR& message)
{
    Mutex::Autolock _l(mLock);
    dumpLocked(message);
}

void MessageQueue::dumpLocked(const MessageList::NODE_PTR& message)
{
    MessageList::NODE_PTR l(mMessages.head());
    int c = 0;
    while (l != 0) {
        const char tick = (l == message) ? '>' : ' ';
        LOGD("%c %d: msg{.what=%08x, when=%lld}", tick, c, l->what, l->when);
        l = l->getNext();
        c++;
    }
}

// ---------------------------------------------------------------------------

}; // namespace android
