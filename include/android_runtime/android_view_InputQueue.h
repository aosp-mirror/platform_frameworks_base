/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_INPUTQUEUE_H
#define _ANDROID_VIEW_INPUTQUEUE_H

#include <input/Input.h>
#include <utils/Looper.h>
#include <utils/TypeHelpers.h>
#include <utils/Vector.h>

#include "JNIHelp.h"

/*
 * Declare a concrete type for the NDK's AInputQueue forward declaration
 */
struct AInputQueue{
};

namespace android {

class InputQueue : public AInputQueue, public MessageHandler {
public:
    virtual ~InputQueue();

    void attachLooper(Looper* looper, int ident, ALooper_callbackFunc callback, void* data);

    void detachLooper();

    bool hasEvents();

    status_t getEvent(InputEvent** outEvent);

    bool preDispatchEvent(InputEvent* event);

    void finishEvent(InputEvent* event, bool handled);

    KeyEvent* createKeyEvent();

    MotionEvent* createMotionEvent();

    void recycleInputEvent(InputEvent* event);

    void enqueueEvent(InputEvent* event);

    static InputQueue* createQueue(jobject inputQueueObj, const sp<Looper>& looper);

protected:
    virtual void handleMessage(const Message& message);

private:
    InputQueue(jobject inputQueueObj, const sp<Looper>& looper,
            int readDispatchFd, int writeDispatchFd);

    void detachLooperLocked();

    jobject mInputQueueWeakGlobal;
    int mDispatchReadFd;
    int mDispatchWriteFd;
    Vector<Looper*> mAppLoopers;
    sp<Looper> mDispatchLooper;
    sp<WeakMessageHandler> mHandler;
    PooledInputEventFactory mPooledInputEventFactory;
    // Guards the pending and finished event vectors
    mutable Mutex mLock;
    Vector<InputEvent*> mPendingEvents;
    Vector<key_value_pair_t<InputEvent*, bool> > mFinishedEvents;
};

} // namespace android

#endif
