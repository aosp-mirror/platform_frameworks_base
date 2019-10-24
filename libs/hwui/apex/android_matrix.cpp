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

#include "android/graphics/matrix.h"
#include "android_graphics_Matrix.h"

bool AMatrix_getContents(JNIEnv* env, jobject matrixObj, float values[9]) {
    static_assert(SkMatrix::kMScaleX == 0, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMSkewX ==  1, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMTransX == 2, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMSkewY ==  3, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMScaleY == 4, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMTransY == 5, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMPersp0 == 6, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMPersp1 == 7, "SkMatrix unexpected index");
    static_assert(SkMatrix::kMPersp2 == 8, "SkMatrix unexpected index");

    SkMatrix* m = android::android_graphics_Matrix_getSkMatrix(env, matrixObj);
    if (m != nullptr) {
        m->get9(values);
        return true;
    }
    return false;
}
