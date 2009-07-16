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

#ifndef TIMED_EVENT_QUEUE_H_

#define TIMED_EVENT_QUEUE_H_

#include <pthread.h>

#include <utils/List.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

struct TimedEventQueue {

    struct Event : public RefBase {
        Event() {}
        virtual ~Event() {}

    protected:
        virtual void fire(TimedEventQueue *queue, int64_t now_us) = 0;

    private:
        friend class TimedEventQueue;

        Event(const Event &);
        Event &operator=(const Event &);
    };

    TimedEventQueue();
    ~TimedEventQueue();

    // Start executing the event loop.
    void start();

    // Stop executing the event loop, if flush is false, any pending
    // events are discarded, otherwise the queue will stop (and this call
    // return) once all pending events have been handled.
    void stop(bool flush = false);

    // Posts an event to the front of the queue (after all events that
    // have previously been posted to the front but before timed events).
    void postEvent(const sp<Event> &event);

    void postEventToBack(const sp<Event> &event);

    // It is an error to post an event with a negative delay.
    void postEventWithDelay(const sp<Event> &event, int64_t delay_us);

    // If the event is to be posted at a time that has already passed,
    // it will fire as soon as possible.
    void postTimedEvent(const sp<Event> &event, int64_t realtime_us);

    // Returns true iff event is currently in the queue and has been
    // successfully cancelled. In this case the event will have been
    // removed from the queue and won't fire.
    bool cancelEvent(const sp<Event> &event);

    static int64_t getRealTimeUs();

private:
    struct QueueItem {
        sp<Event> event;
        int64_t realtime_us;
    };

    struct StopEvent : public TimedEventQueue::Event {
        virtual void fire(TimedEventQueue *queue, int64_t now_us) {
            queue->mStopped = true;
        }
    };

    pthread_t mThread;
    List<QueueItem> mQueue;
    Mutex mLock;
    Condition mQueueNotEmptyCondition;
    Condition mQueueHeadChangedCondition;

    bool mRunning;
    bool mStopped;

    static void *ThreadWrapper(void *me);
    void threadEntry();

    TimedEventQueue(const TimedEventQueue &);
    TimedEventQueue &operator=(const TimedEventQueue &);
};

}  // namespace android

#endif  // TIMED_EVENT_QUEUE_H_
