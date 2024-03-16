/*
 * Copyright 2022 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_SURFACECONTROL_H
#define _ANDROID_VIEW_SURFACECONTROL_H

#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>

#include "jni.h"

namespace android {

/* Gets the underlying native SurfaceControl for a java SurfaceControl. */
extern SurfaceControl* android_view_SurfaceControl_getNativeSurfaceControl(
        JNIEnv* env, jobject surfaceControlObj);

extern jobject android_view_SurfaceControl_getJavaSurfaceControl(
        JNIEnv* env, const SurfaceControl& surfaceControl);

/* Gets the underlying native SurfaceControl for a java SurfaceControl. */
extern SurfaceComposerClient::Transaction*
android_view_SurfaceTransaction_getNativeSurfaceTransaction(JNIEnv* env,
                                                            jobject surfaceTransactionObj);

} // namespace android

#endif // _ANDROID_VIEW_SURFACECONTROL_H
