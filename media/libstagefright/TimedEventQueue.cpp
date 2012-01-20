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

//#define LOG_NDEBUG 0
#define LOG_TAG "TimedEventQueue"
#include <utils/Log.h>
#include <utils/threads.h>

#include "include/TimedEventQueue.h"

#include <cutils/sched_policy.h>

#include <sys/prctl.h>
#include <sys/time.h>

#include <media/stagefright/MediaDebug.h>

#ifdef ANDROID_SIMULATOR
#include <jni.h>
#endif

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
    if (id == 0) {
        return false;
    }

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

        ALOGV("cancelling event %d", (*it).event->eventID());

        (*it).event->setEventID(0);
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

    return (int64_t)tv.tv_sec * 1000000ll + tv.tv_usec;
}

// static
void *TimedEventQueue::ThreadWrapper(void *me) {

#ifdef ANDROID_SIMULATOR
    // The simulator runs everything as one process, so any
    // Binder calls happen on this thread instead of a thread
    // in another process. We therefore need to make sure that
    // this thread can do calls into interpreted code.
    // On the device this is not an issue because the remote
    // thread will already be set up correctly for this.
    JavaVM *vm;
    int numvms;
    JNI_GetCreatedJavaVMs(&vm, 1, &numvms);
    JNIEnv *env;
    vm->AttachCurrentThread(&env, NULL);
#endif

    androidSetThreadPriority(0, ANDROID_PRIORITY_FOREGROUND);

    static_cast<TimedEventQueue *>(me)->threadEntry();

#ifdef ANDROID_SIMULATOR
    vm->DetachCurrentThread();
#endif
    return NULL;
}

void TimedEventQueue::threadEntry() {
    prctl(PR_SET_NAME, (unsigned long)"TimedEventQueue", 0, 0, 0);

    for (;;) {
        int64_t now_us = 0;
        sp<Event> event;

        {
            Mutex::Autolock autoLock(mLock);

            if (mStopped) {
                break;
            }

            while (mQueue.empty()) {
                mQueueNotEmptyCondition.wait(mLock);
            }

            event_id eventID = 0;
            for (;;) {
                if (mQueue.empty()) {
                    // The only event in the queue could have been cancelled
                    // while we were waiting for its scheduled time.
                    break;
                }

                List<QueueItem>::iterator it = mQueue.begin();
                eventID = (*it).event->eventID();

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

                static int64_t kMaxTimeoutUs = 10000000ll;  // 10 secs
                bool timeoutCapped = false;
                if (delay_us > kMaxTimeoutUs) {
                    LOGW("delay_us exceeds max timeout: %lld us", delay_us);

                    // We'll never block for more than 10 secs, instead
                    // we will split up the full timeout into chunks of
                    // 10 secs at a time. This will also avoid overflow
                    // when converting from us to ns.
                    delay_us = kMaxTimeoutUs;
                    timeoutCapped = true;
                }

                status_t err = mQueueHeadChangedCondition.waitRelative(
                        mLock, delay_us * 1000ll);

                if (!timeoutCapped && err == -ETIMEDOUT) {
                    // We finally hit the time this event is supposed to
                    // trigger.
                    now_us = getRealTimeUs();
                    break;
                }
            }

            // The event w/ this id may have been cancelled while we're
            // waiting for its trigger-time, in that case
            // removeEventFromQueue_l will return NULL.
            // Otherwise, the QueueItem will be removed
            // from the queue and the referenced event returned.
            event = removeEventFromQueue_l(eventID);
        }

        if (event != NULL) {
            // Fire event with the lock NOT held.
            event->fire(this, now_us);
        }
    }
}

sp<TimedEventQueue::Event> TimedEventQueue::removeEventFromQueue_l(
        event_id id) {
    for (List<QueueItem>::iterator it = mQueue.begin();
         it != mQueue.end(); ++it) {
        if ((*it).event->eventID() == id) {
            sp<Event> event = (*it).event;
            event->setEventID(0);

            mQueue.erase(it);

            return event;
        }
    }

    LOGW("Event %d was not found in the queue, already cancelled?", id);

    return NULL;
}

}  // namespace android

