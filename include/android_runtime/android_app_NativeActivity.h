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

#include <ui/InputTransport.h>

#include <android/native_activity.h>

#include "jni.h"

namespace android {

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
 */
struct AInputQueue {
public:
    /* Creates a consumer associated with an input channel. */
    explicit AInputQueue(const android::sp<android::InputChannel>& channel, int workWrite);

    /* Destroys the consumer and releases its input channel. */
    ~AInputQueue();

    void attachLooper(ALooper* looper, ALooper_callbackFunc* callback, void* data);

    void detachLooper();

    int32_t hasEvents();

    int32_t getEvent(AInputEvent** outEvent);

    void finishEvent(AInputEvent* event, bool handled);


    // ----------------------------------------------------------

    inline android::InputConsumer& getConsumer() { return mConsumer; }

    void dispatchEvent(android::KeyEvent* event);

    android::KeyEvent* consumeUnhandledEvent();

    int mWorkWrite;

private:
    void doDefaultKey(android::KeyEvent* keyEvent);

    android::InputConsumer mConsumer;
    android::PreallocatedInputEventFactory mInputEventFactory;
    android::sp<android::PollLoop> mPollLoop;

    int mDispatchKeyRead;
    int mDispatchKeyWrite;

    // This is only touched by the event reader thread.  It is the current
    // key events that came out of the mDispatchingKeys list and are now
    //Êdelivered to the app.
    android::Vector<android::KeyEvent*> mDeliveringKeys;

    android::Mutex mLock;
    android::Vector<android::KeyEvent*> mPendingKeys;
    android::Vector<android::KeyEvent*> mDispatchingKeys;
};

#endif // _ANDROID_APP_NATIVEACTIVITY_H
