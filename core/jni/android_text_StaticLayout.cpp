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

#define LOG_TAG "StaticLayout"

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

struct JLineBreaksID {
    jfieldID breaks;
    jfieldID widths;
    jfieldID ascents;
    jfieldID descents;
    jfieldID flags;
};

static jclass gLineBreaks_class;
static JLineBreaksID gLineBreaks_fieldID;

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
        jint breakStrategy, jint hyphenationFrequency, jboolean isJustified,
        jintArray indents, jintArray leftPaddings, jintArray rightPaddings) {
    return reinterpret_cast<jlong>(new minikin::android::StaticLayoutNative(
            static_cast<minikin::BreakStrategy>(breakStrategy),
            static_cast<minikin::HyphenationFrequency>(hyphenationFrequency),
            isJustified,
            jintArrayToFloatVector(env, indents),
            jintArrayToFloatVector(env, leftPaddings),
            jintArrayToFloatVector(env, rightPaddings)));
}

// CriticalNative
static void nFinish(jlong nativePtr) {
    delete toNative(nativePtr);
}

static void recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jfloatArray recycleAscents,
                        jfloatArray recycleDescents, jintArray recycleFlags,
                        jint recycleLength, size_t nBreaks, const jint* breaks,
                        const jfloat* widths, const jfloat* ascents, const jfloat* descents,
                        const jint* flags) {
    if ((size_t)recycleLength < nBreaks) {
        // have to reallocate buffers
        recycleBreaks = env->NewIntArray(nBreaks);
        recycleWidths = env->NewFloatArray(nBreaks);
        recycleAscents = env->NewFloatArray(nBreaks);
        recycleDescents = env->NewFloatArray(nBreaks);
        recycleFlags = env->NewIntArray(nBreaks);

        env->SetObjectField(recycle, gLineBreaks_fieldID.breaks, recycleBreaks);
        env->SetObjectField(recycle, gLineBreaks_fieldID.widths, recycleWidths);
        env->SetObjectField(recycle, gLineBreaks_fieldID.ascents, recycleAscents);
        env->SetObjectField(recycle, gLineBreaks_fieldID.descents, recycleDescents);
        env->SetObjectField(recycle, gLineBreaks_fieldID.flags, recycleFlags);
    }
    // copy data
    env->SetIntArrayRegion(recycleBreaks, 0, nBreaks, breaks);
    env->SetFloatArrayRegion(recycleWidths, 0, nBreaks, widths);
    env->SetFloatArrayRegion(recycleAscents, 0, nBreaks, ascents);
    env->SetFloatArrayRegion(recycleDescents, 0, nBreaks, descents);
    env->SetIntArrayRegion(recycleFlags, 0, nBreaks, flags);
}

static jint nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
        // Inputs
        jcharArray javaText,
        jint length,
        jfloat firstWidth,
        jint firstWidthLineCount,
        jfloat restWidth,
        jintArray variableTabStops,
        jint defaultTabStop,
        jint indentsOffset,

        // Outputs
        jobject recycle,
        jint recycleLength,
        jintArray recycleBreaks,
        jfloatArray recycleWidths,
        jfloatArray recycleAscents,
        jfloatArray recycleDescents,
        jintArray recycleFlags,
        jfloatArray charWidths) {

    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);

    ScopedCharArrayRO text(env, javaText);

    // TODO: Reorganize minikin APIs.
    minikin::LineBreaker b(minikin::U16StringPiece(text.get(), length));
    if (variableTabStops == nullptr) {
        b.setTabStops(nullptr, 0, defaultTabStop);
    } else {
        ScopedIntArrayRO stops(env, variableTabStops);
        b.setTabStops(stops.get(), stops.size(), defaultTabStop);
    }
    b.setStrategy(builder->getStrategy());
    b.setHyphenationFrequency(builder->getFrequency());
    b.setJustified(builder->isJustified());
    b.setLineWidthDelegate(builder->buildLineWidthDelegate(
            firstWidth, firstWidthLineCount, restWidth, indentsOffset));

    builder->addRuns(&b);

    size_t nBreaks = b.computeBreaks();

    recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleAscents, recycleDescents,
            recycleFlags, recycleLength, nBreaks, b.getBreaks(), b.getWidths(), b.getAscents(),
            b.getDescents(), b.getFlags());

    env->SetFloatArrayRegion(charWidths, 0, b.size(), b.charWidths());

    builder->clearRuns();

    return static_cast<jint>(nBreaks);
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
// CriticalNative
static void nAddStyleRun(jlong nativePtr, jlong nativePaint, jint start, jint end, jboolean isRtl) {
    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    const Typeface* typeface = Typeface::resolveDefault(paint->getAndroidTypeface());
    minikin::MinikinPaint minikinPaint = MinikinUtils::prepareMinikinPaint(paint, typeface);
    builder->addStyleRun(start, end, std::move(minikinPaint), typeface->fFontCollection, isRtl);
}

// CriticalNative
static void nAddReplacementRun(jlong nativePtr, jlong nativePaint, jint start, jint end,
        jfloat width) {
    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    builder->addReplacementRun(start, end, width, paint->getMinikinLocaleListId());
}

static const JNINativeMethod gMethods[] = {
    // Fast Natives
    {"nInit", "("
        "I"  // breakStrategy
        "I"  // hyphenationFrequency
        "Z"  // isJustified
        "[I"  // indents
        "[I"  // left paddings
        "[I"  // right paddings
        ")J", (void*) nInit},

    // Critical Natives
    {"nFinish", "(J)V", (void*) nFinish},
    {"nAddStyleRun", "(JJIIZ)V", (void*) nAddStyleRun},
    {"nAddReplacementRun", "(JJIIF)V", (void*) nAddReplacementRun},

    // Regular JNI
    {"nComputeLineBreaks", "("
        "J"  // nativePtr

        // Inputs
        "[C"  // text
        "I"  // length
        "F"  // firstWidth
        "I"  // firstWidthLineCount
        "F"  // restWidth
        "[I"  // variableTabStops
        "I"  // defaultTabStop
        "I"  // indentsOffset

        // Outputs
        "Landroid/text/StaticLayout$LineBreaks;"  // recycle
        "I"  // recycleLength
        "[I"  // recycleBreaks
        "[F"  // recycleWidths
        "[F"  // recycleAscents
        "[F"  // recycleDescents
        "[I"  // recycleFlags
        "[F"  // charWidths
        ")I", (void*) nComputeLineBreaks}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    gLineBreaks_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/text/StaticLayout$LineBreaks"));

    gLineBreaks_fieldID.breaks = GetFieldIDOrDie(env, gLineBreaks_class, "breaks", "[I");
    gLineBreaks_fieldID.widths = GetFieldIDOrDie(env, gLineBreaks_class, "widths", "[F");
    gLineBreaks_fieldID.ascents = GetFieldIDOrDie(env, gLineBreaks_class, "ascents", "[F");
    gLineBreaks_fieldID.descents = GetFieldIDOrDie(env, gLineBreaks_class, "descents", "[F");
    gLineBreaks_fieldID.flags = GetFieldIDOrDie(env, gLineBreaks_class, "flags", "[I");

    return RegisterMethodsOrDie(env, "android/text/StaticLayout", gMethods, NELEM(gMethods));
}

}
