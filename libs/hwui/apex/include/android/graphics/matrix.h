/*
 * Copyright 2020 The Android Open Source Project
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

#ifndef ANDROID_GRAPHICS_MATRIX_H
#define ANDROID_GRAPHICS_MATRIX_H

#include <jni.h>
#include <cutils/compiler.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

/**
 * Returns an array of floats that represents the 3x3 matrix of the java object.
 * @param values The 9 values of the 3x3 matrix in the following order.
 *               values[0] = scaleX  values[1] = skewX   values[2] = transX
 *               values[3] = skewY   values[4] = scaleY  values[5] = transY
 *               values[6] = persp0  values[7] = persp1  values[8] = persp2
 * @return true if the values param was populated and false otherwise.

 */
ANDROID_API bool AMatrix_getContents(JNIEnv* env, jobject matrixObj, float values[9]);

__END_DECLS

#endif // ANDROID_GRAPHICS_MATRIX_H
