/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_VERIFIEDMOTIONEVENT_H
#define _ANDROID_VIEW_VERIFIEDMOTIONEVENT_H

#include "jni.h"

namespace android {

class VerifiedMotionEvent;

/* Create an instance of a DVM VerifiedMotionEvent object
 * Return nullptr on error. */
extern jobject android_view_VerifiedMotionEvent(JNIEnv* env, const VerifiedMotionEvent& event);

} // namespace android

#endif // _ANDROID_VIEW_VERIFIEDMOTIONEVENT_H
