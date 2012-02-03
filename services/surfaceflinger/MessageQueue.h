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

#ifndef ANDROID_MESSAGE_QUEUE_H
#define ANDROID_MESSAGE_QUEUE_H

#include <stdint.h>
#include <errno.h>
#include <sys/types.h>

#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/Looper.h>

#include <gui/DisplayEventReceiver.h>

#include "Barrier.h"

namespace android {

class IDisplayEventConnection;
class EventThread;
class SurfaceFlinger;

// ---------------------------------------------------------------------------

class MessageBase : public MessageHandler
{
public:
    MessageBase();
    
    // return true if message has a handler
    virtual bool handler() = 0;

    // waits for the handler to be processed
    void wait() const { barrier.wait(); }

protected:
    virtual ~MessageBase();

private:
    virtual void handleMessage(const Message& message);

    mutable Barrier barrier;
};

// ---------------------------------------------------------------------------

class MessageQueue {
    class Handler : public MessageHandler {
        enum {
            eventMaskInvalidate = 0x1,
            eventMaskRefresh    = 0x2
        };
        MessageQueue& mQueue;
        int32_t mEventMask;
    public:
        Handler(MessageQueue& queue) : mQueue(queue), mEventMask(0) { }
        virtual void handleMessage(const Message& message);
        void signalRefresh();
        void signalInvalidate();
    };

    friend class Handler;

    sp<SurfaceFlinger> mFlinger;
    sp<Looper> mLooper;
    sp<EventThread> mEventThread;
    sp<IDisplayEventConnection> mEvents;
    sp<BitTube> mEventTube;
    sp<Handler> mHandler;


    static int cb_eventReceiver(int fd, int events, void* data);
    int eventReceiver(int fd, int events);

public:
    enum {
        INVALIDATE = 0,
        REFRESH    = 1,
    };

    MessageQueue();
    ~MessageQueue();
    void init(const sp<SurfaceFlinger>& flinger);
    void setEventThread(const sp<EventThread>& events);

    void waitMessage();
    status_t postMessage(const sp<MessageBase>& message, nsecs_t reltime=0);
    void invalidate();
    void refresh();
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_MESSAGE_QUEUE_H */
