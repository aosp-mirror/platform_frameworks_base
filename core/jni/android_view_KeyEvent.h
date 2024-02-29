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

#ifndef _ANDROID_VIEW_KEYEVENT_H
#define _ANDROID_VIEW_KEYEVENT_H

#include <nativehelper/scoped_local_ref.h>
#include <utils/Errors.h>
#include <utils/threads.h>

#include "jni.h"

namespace android {

class KeyEvent;

/* Obtains an instance of a DVM KeyEvent object as a copy of a native KeyEvent instance.
 * Returns NULL on error. */
extern ScopedLocalRef<jobject> android_view_KeyEvent_obtainAsCopy(JNIEnv* env,
                                                                  const KeyEvent& event);

/* Copies the contents of a DVM KeyEvent object to a native KeyEvent instance.
 * Returns non-zero on error. */
extern KeyEvent android_view_KeyEvent_obtainAsCopy(JNIEnv* env, jobject eventObj);

/* Recycles a DVM KeyEvent object.
 * Key events should only be recycled if they are owned by the system since user
 * code expects them to be essentially immutable, "tracking" notwithstanding.
 * Returns non-zero on error. */
extern status_t android_view_KeyEvent_recycle(JNIEnv* env, jobject eventObj);

} // namespace android

#endif // _ANDROID_VIEW_KEYEVENT_H
