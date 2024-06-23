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

#ifndef _ANDROID_VIEW_MOTIONEVENT_H
#define _ANDROID_VIEW_MOTIONEVENT_H

#include <nativehelper/scoped_local_ref.h>
#include <utils/Errors.h>

#include "jni.h"

namespace android {

class MotionEvent;

/* Obtains an instance of a DVM MotionEvent object as a copy of a native MotionEvent instance.
 * Returns NULL on error. */
extern ScopedLocalRef<jobject> android_view_MotionEvent_obtainAsCopy(JNIEnv* env,
                                                                     const MotionEvent& event);

/* Obtains an instance of a Java MotionEvent object, taking over the ownership of the provided
 * native MotionEvent instance. Crashes on error. */
extern ScopedLocalRef<jobject> android_view_MotionEvent_obtainFromNative(
        JNIEnv* env, std::unique_ptr<MotionEvent> event);

/* Gets the underlying native MotionEvent instance within a DVM MotionEvent object.
 * Returns NULL if the event is NULL or if it is uninitialized. */
extern MotionEvent* android_view_MotionEvent_getNativePtr(JNIEnv* env, jobject eventObj);

/* Recycles a DVM MotionEvent object.
 * Returns non-zero on error. */
extern status_t android_view_MotionEvent_recycle(JNIEnv* env, jobject eventObj);

} // namespace android

#endif // _ANDROID_VIEW_MOTIONEVENT_H
