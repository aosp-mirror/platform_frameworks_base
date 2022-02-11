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

#ifndef _ANDROID_GRAPHICS_MATRIX_H_
#define _ANDROID_GRAPHICS_MATRIX_H_

#include "jni.h"
#include "SkMatrix.h"

namespace android {

/* Gets the underlying SkMatrix from a Matrix object. */
SkMatrix* android_graphics_Matrix_getSkMatrix(JNIEnv* env, jobject matrixObj);

/* Creates a new Matrix java object. */
jobject android_graphics_Matrix_newInstance(JNIEnv* env);

} // namespace android

#endif // _ANDROID_GRAPHICS_MATRIX_H_
