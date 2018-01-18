/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "MeasuredParagraph"

#include "ScopedIcuLocale.h"
#include "unicode/locid.h"
#include "unicode/brkiter.h"
#include "utils/misc.h"
#include "utils/Log.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
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

static inline minikin::MeasuredTextBuilder* toBuilder(jlong ptr) {
    return reinterpret_cast<minikin::MeasuredTextBuilder*>(ptr);
}

static inline Paint* toPaint(jlong ptr) {
    return reinterpret_cast<Paint*>(ptr);
}

static inline minikin::MeasuredText* toMeasuredParagraph(jlong ptr) {
    return reinterpret_cast<minikin::MeasuredText*>(ptr);
}

template<typename Ptr> static inline jlong toJLong(Ptr ptr) {
    return reinterpret_cast<jlong>(ptr);
}

static void releaseMeasuredParagraph(jlong measuredTextPtr) {
    delete toMeasuredParagraph(measuredTextPtr);
}

// Regular JNI
static jlong nInitBuilder() {
    return toJLong(new minikin::MeasuredTextBuilder());
}

// Regular JNI
static void nAddStyleRun(JNIEnv* /* unused */, jclass /* unused */, jlong builderPtr,
                         jlong paintPtr, jint start, jint end, jboolean isRtl) {
    Paint* paint = toPaint(paintPtr);
    const Typeface* typeface = Typeface::resolveDefault(paint->getAndroidTypeface());
    minikin::MinikinPaint minikinPaint = MinikinUtils::prepareMinikinPaint(paint, typeface);
    toBuilder(builderPtr)->addStyleRun(start, end, std::move(minikinPaint),
                                       typeface->fFontCollection, isRtl);
}

// Regular JNI
static void nAddReplacementRun(JNIEnv* /* unused */, jclass /* unused */, jlong builderPtr,
                               jlong paintPtr, jint start, jint end, jfloat width) {
    toBuilder(builderPtr)->addReplacementRun(start, end, width,
                                             toPaint(paintPtr)->getMinikinLocaleListId());
}

// Regular JNI
static jlong nBuildNativeMeasuredParagraph(JNIEnv* env, jclass /* unused */, jlong builderPtr,
                                      jcharArray javaText, jboolean computeHyphenation) {
    ScopedCharArrayRO text(env, javaText);
    const minikin::U16StringPiece textBuffer(text.get(), text.size());

    // Pass the ownership to Java.
    return toJLong(toBuilder(builderPtr)->build(textBuffer, computeHyphenation).release());
}

// Regular JNI
static void nFreeBuilder(JNIEnv* env, jclass /* unused */, jlong builderPtr) {
    delete toBuilder(builderPtr);
}

// CriticalNative
static jlong nGetReleaseFunc() {
    return toJLong(&releaseMeasuredParagraph);
}

static const JNINativeMethod gMethods[] = {
    // MeasuredParagraphBuilder native functions.
    {"nInitBuilder", "()J", (void*) nInitBuilder},
    {"nAddStyleRun", "(JJIIZ)V", (void*) nAddStyleRun},
    {"nAddReplacementRun", "(JJIIF)V", (void*) nAddReplacementRun},
    {"nBuildNativeMeasuredParagraph", "(J[CZ)J", (void*) nBuildNativeMeasuredParagraph},
    {"nFreeBuilder", "(J)V", (void*) nFreeBuilder},

    // MeasuredParagraph native functions.
    {"nGetReleaseFunc", "()J", (void*) nGetReleaseFunc},  // Critical Natives
};

int register_android_text_MeasuredParagraph(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/text/MeasuredParagraph", gMethods, NELEM(gMethods));
}

}
