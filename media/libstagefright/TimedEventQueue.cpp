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

#undef __STRICT_ANSI__
#define __STDINT_LIMITS
#define __STDC_LIMIT_MACROS
#include <stdint.h>

#define LOG_TAG "TimedEventQueue"
#include <utils/Log.h>

#include "include/TimedEventQueue.h"

#include <sys/time.h>

#include <media/stagefright/MediaDebug.h>

namespace android {

TimedEventQueue::TimedEventQueue()
    : mNextEventID(1),
      mRunning(false),
      mStopped(false) {
}

TimedEventQueue::~TimedEventQueue() {
    stop();
}

void TimedEventQueue::start() {
    if (mRunning) {
        return;
    }

    mStopped = false;

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    pthread_create(&mThread, &attr, ThreadWrapper, this);

    pthread_attr_destroy(&attr);

    mRunning = true;
}

void TimedEventQueue::stop(bool flush) {
    if (!mRunning) {
        return;
    }

    if (flush) {
        postEventToBack(new StopEvent);
    } else {
        postTimedEvent(new StopEvent, INT64_MIN);
    }

    void *dummy;
    pthread_join(mThread, &dummy);

    mQueue.clear();

    mRunning = false;
}

TimedEventQueue::event_id TimedEventQueue::postEvent(const sp<Event> &event) {
    // Reserve an earlier timeslot an INT64_MIN to be able to post
    // the StopEvent to the absolute head of the queue.
    return postTimedEvent(event, INT64_MIN + 1);
}

TimedEventQueue::event_id TimedEventQueue::postEventToBack(
        const sp<Event> &event) {
    return postTimedEvent(event, INT64_MAX);
}

TimedEventQueue::event_id TimedEventQueue::postEventWithDelay(
        const sp<Event> &event, int64_t delay_us) {
    CHECK(delay_us >= 0);
    return postTimedEvent(event, getRealTimeUs() + delay_us);
}

TimedEventQueue::event_id TimedEventQueue::postTimedEvent(
        const sp<Event> &event, int64_t realtime_us) {
    Mutex::Autolock autoLock(mLock);

    event->setEventID(mNextEventID++);

    List<QueueItem>::iterator it = mQueue.begin();
    while (it != mQueue.end() && realtime_us >= (*it).realtime_us) {
        ++it;
    }

    QueueItem item;
    item.event = event;
    item.realtime_us = realtime_us;

    if (it == mQueue.begin()) {
        mQueueHeadChangedCondition.signal();
    }

    mQueue.insert(it, item);

    mQueueNotEmptyCondition.signal();

    return event->eventID();
}

static bool MatchesEventID(
        void *cookie, const sp<TimedEventQueue::Event> &event) {
    TimedEventQueue::event_id *id =
        static_cast<TimedEventQueue::event_id *>(cookie);

    if (event->eventID() != *id) {
        return false;
    }

    *id = 0;

    return true;
}

bool TimedEventQueue::cancelEvent(event_id id) {
    CHECK(id != 0);

    cancelEvents(&MatchesEventID, &id, true /* stopAfterFirstMatch */);

    // if MatchesEventID found a match, it will have set id to 0
    // (which is not a valid event_id).

    return id == 0;
}

void TimedEventQueue::cancelEvents(
        bool (*predicate)(void *cookie, const sp<Event> &event),
        void *cookie,
        bool stopAfterFirstMatch) {
    Mutex::Autolock autoLock(mLock);

    List<QueueItem>::iterator it = mQueue.begin();
    while (it != mQueue.end()) {
        if (!(*predicate)(cookie, (*it).event)) {
            ++it;
            continue;
        }

        if (it == mQueue.begin()) {
            mQueueHeadChangedCondition.signal();
        }

        it = mQueue.erase(it);

        if (stopAfterFirstMatch) {
            return;
        }
    }
}

// static
int64_t TimedEventQueue::getRealTimeUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_sec * 1000000 + tv.tv_usec;
}

// static
void *TimedEventQueue::ThreadWrapper(void *me) {
    static_cast<TimedEventQueue *>(me)->threadEntry();

    return NULL;
}

void TimedEventQueue::threadEntry() {
    for (;;) {
        int64_t now_us;
        sp<Event> event;

        {
            Mutex::Autolock autoLock(mLock);

            if (mStopped) {
                break;
            }

            while (mQueue.empty()) {
                mQueueNotEmptyCondition.wait(mLock);
            }

            List<QueueItem>::iterator it;
            for (;;) {
                it = mQueue.begin();

                now_us = getRealTimeUs();
                int64_t when_us = (*it).realtime_us;

                int64_t delay_us;
                if (when_us < 0 || when_us == INT64_MAX) {
                    delay_us = 0;
                } else {
                    delay_us = when_us - now_us;
                }

                if (delay_us <= 0) {
                    break;
                }

                status_t err = mQueueHeadChangedCondition.waitRelative(
                        mLock, delay_us * 1000);

                if (err == -ETIMEDOUT) {
                    now_us = getRealTimeUs();
                    break;
                }
            }

            event = (*it).event;
            mQueue.erase(it);
        }

        // Fire event with the lock NOT held.
        event->fire(this, now_us);
    }
}

}  // namespace android

