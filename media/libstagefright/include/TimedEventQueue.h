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

    typedef int32_t event_id;

    struct Event : public RefBase {
        Event()
            : mEventID(0) {
        }

        virtual ~Event() {}

        event_id eventID() {
            return mEventID;
        }

    protected:
        virtual void fire(TimedEventQueue *queue, int64_t now_us) = 0;

    private:
        friend class TimedEventQueue;

        event_id mEventID;

        void setEventID(event_id id) {
            mEventID = id;
        }

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
    event_id postEvent(const sp<Event> &event);

    event_id postEventToBack(const sp<Event> &event);

    // It is an error to post an event with a negative delay.
    event_id postEventWithDelay(const sp<Event> &event, int64_t delay_us);

    // If the event is to be posted at a time that has already passed,
    // it will fire as soon as possible.
    event_id postTimedEvent(const sp<Event> &event, int64_t realtime_us);

    // Returns true iff event is currently in the queue and has been
    // successfully cancelled. In this case the event will have been
    // removed from the queue and won't fire.
    bool cancelEvent(event_id id);

    // Cancel any pending event that satisfies the predicate.
    // If stopAfterFirstMatch is true, only cancels the first event
    // satisfying the predicate (if any).
    void cancelEvents(
            bool (*predicate)(void *cookie, const sp<Event> &event),
            void *cookie,
            bool stopAfterFirstMatch = false);

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
    event_id mNextEventID;

    bool mRunning;
    bool mStopped;

    static void *ThreadWrapper(void *me);
    void threadEntry();

    sp<Event> removeEventFromQueue_l(event_id id);

    TimedEventQueue(const TimedEventQueue &);
    TimedEventQueue &operator=(const TimedEventQueue &);
};

}  // namespace android

#endif  // TIMED_EVENT_QUEUE_H_
