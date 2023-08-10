/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <minikin/GraphemeBreak.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "GraphicsJNI.h"

namespace android {

static void nIsGraphemeBreak(JNIEnv* env, jclass, jfloatArray advances, jcharArray text, jint start,
                             jint end, jbooleanArray isGraphemeBreak) {
    if (start > end || env->GetArrayLength(advances) < end ||
        env->GetArrayLength(isGraphemeBreak) < end - start) {
        doThrowAIOOBE(env);
    }

    if (start == end) {
        return;
    }

    ScopedFloatArrayRO advancesArray(env, advances);
    ScopedCharArrayRO textArray(env, text);
    ScopedBooleanArrayRW isGraphemeBreakArray(env, isGraphemeBreak);

    size_t count = end - start;
    for (size_t offset = 0; offset < count; ++offset) {
        bool isBreak = minikin::GraphemeBreak::isGraphemeBreak(advancesArray.get(), textArray.get(),
                                                               start, end, start + offset);
        isGraphemeBreakArray[offset] = isBreak ? JNI_TRUE : JNI_FALSE;
    }
}

static const JNINativeMethod gMethods[] = {
        {"nIsGraphemeBreak",
         "("
         "[F"  // advances
         "[C"  // text
         "I"   // start
         "I"   // end
         "[Z"  // isGraphemeBreak
         ")V",
         (void*)nIsGraphemeBreak},
};

int register_android_graphics_text_GraphemeBreak(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/text/GraphemeBreak", gMethods,
                                NELEM(gMethods));
}

}  // namespace android
