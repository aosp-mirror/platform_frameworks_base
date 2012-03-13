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

#ifndef _ANDROID_APP_NATIVEACTIVITY_H
#define _ANDROID_APP_NATIVEACTIVITY_H

#include <androidfw/InputTransport.h>
#include <utils/Looper.h>

#include <android/native_activity.h>

#include "jni.h"

namespace android {

extern void android_NativeActivity_finish(
        ANativeActivity* activity);

extern void android_NativeActivity_setWindowFormat(
        ANativeActivity* activity, int32_t format);

extern void android_NativeActivity_setWindowFlags(
        ANativeActivity* activity, int32_t values, int32_t mask);

extern void android_NativeActivity_showSoftInput(
        ANativeActivity* activity, int32_t flags);

extern void android_NativeActivity_hideSoftInput(
        ANativeActivity* activity, int32_t flags);

} // namespace android


/*
 * NDK input queue API.
 *
 * Here is the event flow:
 * 1. Event arrives in input consumer, and is returned by getEvent().
 * 2. Application calls preDispatchEvent():
 *    a. Event is assigned a sequence ID and enqueued in mPreDispatchingKeys.
 *    b. Main thread picks up event, hands to input method.
 *    c. Input method eventually returns sequence # and whether it was handled.
 *    d. finishPreDispatch() is called to enqueue the information.
 *    e. next getEvent() call will:
 *       - finish any pre-dispatch events that the input method handled
 *       - return the next pre-dispatched event that the input method didn't handle.
 *    f. (A preDispatchEvent() call on this event will now return false).
 * 3. Application calls finishEvent() with whether it was handled.
 *    - If handled is true, the event is finished.
 *    - If handled is false, the event is put on mUnhandledKeys, and:
 *      a. Main thread receives event from consumeUnhandledEvent().
 *      b. Java sends event through default key handler.
 *      c. event is finished.
 */
struct AInputQueue {
public:
    /* Creates a consumer associated with an input channel. */
    explicit AInputQueue(const android::sp<android::InputChannel>& channel, int workWrite);

    /* Destroys the consumer and releases its input channel. */
    ~AInputQueue();

    void attachLooper(ALooper* looper, int ident, ALooper_callbackFunc callback, void* data);

    void detachLooper();

    int32_t hasEvents();

    int32_t getEvent(AInputEvent** outEvent);

    bool preDispatchEvent(AInputEvent* event);

    void finishEvent(AInputEvent* event, bool handled, bool didDefaultHandling);

    // ----------------------------------------------------------

    inline android::InputConsumer& getConsumer() { return mConsumer; }

    void dispatchEvent(android::KeyEvent* event);

    void finishPreDispatch(int seq, bool handled);

    android::KeyEvent* consumeUnhandledEvent();
    android::KeyEvent* consumePreDispatchingEvent(int* outSeq);

    android::KeyEvent* createKeyEvent();

    int mWorkWrite;

private:
    void doUnhandledKey(android::KeyEvent* keyEvent);
    bool preDispatchKey(android::KeyEvent* keyEvent);
    void wakeupDispatchLocked();

    android::PooledInputEventFactory mPooledInputEventFactory;
    android::InputConsumer mConsumer;
    android::sp<android::Looper> mLooper;

    int mDispatchKeyRead;
    int mDispatchKeyWrite;

    struct in_flight_event {
        android::InputEvent* event;
        int seq; // internal sequence number for synthetic pre-dispatch events
        uint32_t finishSeq; // sequence number for sendFinishedSignal, or 0 if finish not required
    };

    struct finish_pre_dispatch {
        int seq;
        bool handled;
    };

    android::Mutex mLock;

    int mSeq;

    // All input events that are actively being processed.
    android::Vector<in_flight_event> mInFlightEvents;

    // Key events that the app didn't handle, and are pending for
    // delivery to the activity's default key handling.
    android::Vector<android::KeyEvent*> mUnhandledKeys;

    // Keys that arrived in the Java framework and need to be
    // dispatched to the app.
    android::Vector<android::KeyEvent*> mDispatchingKeys;

    // Key events that are pending to be pre-dispatched to the IME.
    android::Vector<in_flight_event> mPreDispatchingKeys;

    // Event sequence numbers that we have finished pre-dispatching.
    android::Vector<finish_pre_dispatch> mFinishPreDispatches;
};

#endif // _ANDROID_APP_NATIVEACTIVITY_H
