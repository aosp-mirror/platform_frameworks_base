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

#ifndef _ANDROID_OS_MESSAGEQUEUE_H
#define _ANDROID_OS_MESSAGEQUEUE_H

#include "jni.h"
#include <utils/Looper.h>

namespace android {

class MessageQueue : public virtual RefBase {
public:
    /* Gets the message queue's looper. */
    inline sp<Looper> getLooper() const {
        return mLooper;
    }

    /* Checks whether the JNI environment has a pending exception.
     *
     * If an exception occurred, logs it together with the specified message,
     * and calls raiseException() to ensure the exception will be raised when
     * the callback returns, clears the pending exception from the environment,
     * then returns true.
     *
     * If no exception occurred, returns false.
     */
    bool raiseAndClearException(JNIEnv* env, const char* msg);

    /* Raises an exception from within a callback function.
     * The exception will be rethrown when control returns to the message queue which
     * will typically cause the application to crash.
     *
     * This message can only be called from within a callback function.  If it is called
     * at any other time, the process will simply be killed.
     *
     * Does nothing if exception is NULL.
     *
     * (This method does not take ownership of the exception object reference.
     * The caller is responsible for releasing its reference when it is done.)
     */
    virtual void raiseException(JNIEnv* env, const char* msg, jthrowable exceptionObj) = 0;

protected:
    MessageQueue();
    virtual ~MessageQueue();

protected:
    sp<Looper> mLooper;
};

/* Gets the native object associated with a MessageQueue. */
extern sp<MessageQueue> android_os_MessageQueue_getMessageQueue(
        JNIEnv* env, jobject messageQueueObj);

} // namespace android

#endif // _ANDROID_OS_MESSAGEQUEUE_H
