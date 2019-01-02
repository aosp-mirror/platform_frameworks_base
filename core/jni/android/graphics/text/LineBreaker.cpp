/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "LineBreaker"

#include "utils/misc.h"
#include "utils/Log.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "scoped_nullable_primitive_array.h"
#include <cstdint>
#include <vector>
#include <list>
#include <algorithm>

#include "SkPaint.h"
#include "SkTypeface.h"
#include <hwui/MinikinSkia.h>
#include <hwui/MinikinUtils.h>
#include <hwui/Paint.h>
#include <minikin/FontCollection.h>
#include <minikin/AndroidLineBreakerHelper.h>
#include <minikin/MinikinFont.h>

namespace android {

static inline std::vector<float> jintArrayToFloatVector(JNIEnv* env, jintArray javaArray) {
    if (javaArray == nullptr) {
         return std::vector<float>();
    } else {
        ScopedIntArrayRO intArr(env, javaArray);
        return std::vector<float>(intArr.get(), intArr.get() + intArr.size());
    }
}

static inline minikin::android::StaticLayoutNative* toNative(jlong ptr) {
    return reinterpret_cast<minikin::android::StaticLayoutNative*>(ptr);
}

// set text and set a number of parameters for creating a layout (width, tabstops, strategy,
// hyphenFrequency)
static jlong nInit(JNIEnv* env, jclass /* unused */,
        jint breakStrategy, jint hyphenationFrequency, jboolean isJustified, jintArray indents) {
    return reinterpret_cast<jlong>(new minikin::android::StaticLayoutNative(
            static_cast<minikin::BreakStrategy>(breakStrategy),
            static_cast<minikin::HyphenationFrequency>(hyphenationFrequency),
            isJustified,
            jintArrayToFloatVector(env, indents)));
}

static void nFinish(jlong nativePtr) {
    delete toNative(nativePtr);
}

// CriticalNative
static jlong nGetReleaseFunc() {
    return reinterpret_cast<jlong>(nFinish);
}

static jlong nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
        // Inputs
        jcharArray javaText,
        jlong measuredTextPtr,
        jint length,
        jfloat firstWidth,
        jint firstWidthLineCount,
        jfloat restWidth,
        jintArray variableTabStops,
        jint defaultTabStop,
        jint indentsOffset) {
    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);

    ScopedCharArrayRO text(env, javaText);
    ScopedNullableIntArrayRO tabStops(env, variableTabStops);

    minikin::U16StringPiece u16Text(text.get(), length);
    minikin::MeasuredText* measuredText = reinterpret_cast<minikin::MeasuredText*>(measuredTextPtr);

    std::unique_ptr<minikin::LineBreakResult> result =
          std::make_unique<minikin::LineBreakResult>(builder->computeBreaks(
                u16Text, *measuredText, firstWidth, firstWidthLineCount, restWidth, indentsOffset,
                tabStops.get(), tabStops.size(), defaultTabStop));
    return reinterpret_cast<jlong>(result.release());
}

static jint nGetLineCount(jlong ptr) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->breakPoints.size();
}

static jint nGetLineBreakOffset(jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->breakPoints[i];
}

static jfloat nGetLineWidth(jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->widths[i];
}

static jfloat nGetLineAscent(jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->ascents[i];
}

static jfloat nGetLineDescent(jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->descents[i];
}

static jint nGetLineFlag(jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->flags[i];
}

static void nReleaseResult(jlong ptr) {
    delete reinterpret_cast<minikin::LineBreakResult*>(ptr);
}

static jlong nGetReleaseResultFunc() {
    return reinterpret_cast<jlong>(nReleaseResult);
}

static const JNINativeMethod gMethods[] = {
    // Fast Natives
    {"nInit", "("
        "I"  // breakStrategy
        "I"  // hyphenationFrequency
        "Z"  // isJustified
        "[I"  // indents
        ")J", (void*) nInit},

    // Critical Natives
    {"nGetReleaseFunc", "()J", (void*) nGetReleaseFunc},

    // Regular JNI
    {"nComputeLineBreaks", "("
        "J"  // nativePtr
        "[C"  // text
        "J"  // MeasuredParagraph ptr.
        "I"  // length
        "F"  // firstWidth
        "I"  // firstWidthLineCount
        "F"  // restWidth
        "[I"  // variableTabStops
        "I"  // defaultTabStop
        "I"  // indentsOffset
        ")J", (void*) nComputeLineBreaks},

    // Result accessors, CriticalNatives
    {"nGetLineCount", "(J)I", (void*)nGetLineCount},
    {"nGetLineBreakOffset", "(JI)I", (void*)nGetLineBreakOffset},
    {"nGetLineWidth", "(JI)F", (void*)nGetLineWidth},
    {"nGetLineAscent", "(JI)F", (void*)nGetLineAscent},
    {"nGetLineDescent", "(JI)F", (void*)nGetLineDescent},
    {"nGetLineFlag", "(JI)I", (void*)nGetLineFlag},
    {"nGetReleaseResultFunc", "()J", (void*)nGetReleaseResultFunc},
};

int register_android_graphics_text_LineBreaker(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/text/LineBreaker", gMethods,
                                NELEM(gMethods));
}

}
