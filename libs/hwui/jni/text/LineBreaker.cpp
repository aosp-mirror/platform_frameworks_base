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

#undef LOG_TAG
#define LOG_TAG "LineBreaker"

#include "utils/misc.h"
#include "utils/Log.h"
#include "graphics_jni_helpers.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
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
static jlong nGetReleaseFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(nFinish);
}

static void nFinishP(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    nFinish(nativePtr);
}

static jlong nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
        // Inputs
        jcharArray javaText,
        jlong measuredTextPtr,
        jint length,
        jfloat firstWidth,
        jint firstWidthLineCount,
        jfloat restWidth,
        jfloatArray variableTabStops,
        jfloat defaultTabStop,
        jint indentsOffset) {
    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);

    ScopedCharArrayRO text(env, javaText);
    ScopedNullableFloatArrayRO tabStops(env, variableTabStops);

    minikin::U16StringPiece u16Text(text.get(), length);
    minikin::MeasuredText* measuredText = reinterpret_cast<minikin::MeasuredText*>(measuredTextPtr);

    std::unique_ptr<minikin::LineBreakResult> result =
          std::make_unique<minikin::LineBreakResult>(builder->computeBreaks(
                u16Text, *measuredText, firstWidth, firstWidthLineCount, restWidth, indentsOffset,
                tabStops.get(), tabStops.size(), defaultTabStop));
    return reinterpret_cast<jlong>(result.release());
}

static void recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jfloatArray recycleAscents,
                        jfloatArray recycleDescents, jintArray recycleFlags, jint recycleLength,
                        const minikin::LineBreakResult& result) {
    const size_t nBreaks = result.breakPoints.size();
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
    env->SetIntArrayRegion(recycleBreaks, 0, nBreaks, result.breakPoints.data());
    env->SetFloatArrayRegion(recycleWidths, 0, nBreaks, result.widths.data());
    env->SetFloatArrayRegion(recycleAscents, 0, nBreaks, result.ascents.data());
    env->SetFloatArrayRegion(recycleDescents, 0, nBreaks, result.descents.data());
    env->SetIntArrayRegion(recycleFlags, 0, nBreaks, result.flags.data());
}

// Variant for Android P. The outputs are passed in explicitly here.
static jint nComputeLineBreaksP(JNIEnv* env, jclass, jlong nativePtr,
                                // Inputs
                                jcharArray javaText, jlong measuredTextPtr, jint length,
                                jfloat firstWidth, jint firstWidthLineCount, jfloat restWidth,
                                jfloatArray variableTabStops, jfloat defaultTabStop,
                                jint indentsOffset,

                                // Outputs
                                jobject recycle, jint recycleLength, jintArray recycleBreaks,
                                jfloatArray recycleWidths, jfloatArray recycleAscents,
                                jfloatArray recycleDescents, jintArray recycleFlags,
                                jfloatArray charWidths) {
    minikin::android::StaticLayoutNative* builder = toNative(nativePtr);

    ScopedCharArrayRO text(env, javaText);
    ScopedNullableFloatArrayRO tabStops(env, variableTabStops);
    minikin::U16StringPiece u16Text(text.get(), length);
    minikin::MeasuredText* measuredText = reinterpret_cast<minikin::MeasuredText*>(measuredTextPtr);
    minikin::LineBreakResult result = builder->computeBreaks(
            u16Text, *measuredText, firstWidth, firstWidthLineCount, restWidth, indentsOffset,
            tabStops.get(), tabStops.size(), defaultTabStop);

    recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleAscents, recycleDescents,
                recycleFlags, recycleLength, result);

    env->SetFloatArrayRegion(charWidths, 0, measuredText->widths.size(),
                             measuredText->widths.data());

    return static_cast<jint>(result.breakPoints.size());
}

static jint nGetLineCount(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->breakPoints.size();
}

static jint nGetLineBreakOffset(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->breakPoints[i];
}

static jfloat nGetLineWidth(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->widths[i];
}

static jfloat nGetLineAscent(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->ascents[i];
}

static jfloat nGetLineDescent(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->descents[i];
}

static jint nGetLineFlag(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    return reinterpret_cast<minikin::LineBreakResult*>(ptr)->flags[i];
}

static void nReleaseResult(jlong ptr) {
    delete reinterpret_cast<minikin::LineBreakResult*>(ptr);
}

static jlong nGetReleaseResultFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(nReleaseResult);
}

static const JNINativeMethod gMethods[] = {
        // Fast Natives
        {"nInit",
         "("
         "I"   // breakStrategy
         "I"   // hyphenationFrequency
         "Z"   // isJustified
         "[I"  // indents
         ")J",
         (void*)nInit},

        // Critical Natives
        {"nGetReleaseFunc", "()J", (void*)nGetReleaseFunc},

        // Regular JNI
        {"nComputeLineBreaks",
         "("
         "J"   // nativePtr
         "[C"  // text
         "J"   // MeasuredParagraph ptr.
         "I"   // length
         "F"   // firstWidth
         "I"   // firstWidthLineCount
         "F"   // restWidth
         "[F"  // variableTabStops
         "F"   // defaultTabStop
         "I"   // indentsOffset
         ")J",
         (void*)nComputeLineBreaks},

        // Regular JNI
        {"nComputeLineBreaksP",
         "("
         "J"  // nativePtr

         // Inputs
         "[C"  // text
         "J"   // MeasuredParagraph ptr.
         "I"   // length
         "F"   // firstWidth
         "I"   // firstWidthLineCount
         "F"   // restWidth
         "[F"  // variableTabStops
         "F"   // defaultTabStop
         "I"   // indentsOffset

         // Outputs
         "Ljava/lang/Object;"  // recycle
         "I"                   // recycleLength
         "[I"                  // recycleBreaks
         "[F"                  // recycleWidths
         "[F"                  // recycleAscents
         "[F"                  // recycleDescents
         "[I"                  // recycleFlags
         "[F"                  // charWidths
         ")I",
         (void*)nComputeLineBreaksP},

        // Result accessors, CriticalNatives
        {"nGetLineCount", "(J)I", (void*)nGetLineCount},
        {"nGetLineBreakOffset", "(JI)I", (void*)nGetLineBreakOffset},
        {"nGetLineWidth", "(JI)F", (void*)nGetLineWidth},
        {"nGetLineAscent", "(JI)F", (void*)nGetLineAscent},
        {"nGetLineDescent", "(JI)F", (void*)nGetLineDescent},
        {"nGetLineFlag", "(JI)I", (void*)nGetLineFlag},
        {"nGetReleaseResultFunc", "()J", (void*)nGetReleaseResultFunc},
        {"nFinishP", "(J)V", (void*)nFinishP},
};

int register_android_graphics_text_LineBreaker(JNIEnv* env) {
    int robolectricApiLevel = GetRobolectricApiLevel(env);
    if (robolectricApiLevel == 28) {
        gLineBreaks_class = MakeGlobalRefOrDie(
                env, FindClassOrDie(env, "android/text/StaticLayout$LineBreaks"));

        gLineBreaks_fieldID.breaks = GetFieldIDOrDie(env, gLineBreaks_class, "breaks", "[I");
        gLineBreaks_fieldID.widths = GetFieldIDOrDie(env, gLineBreaks_class, "widths", "[F");
        gLineBreaks_fieldID.ascents = GetFieldIDOrDie(env, gLineBreaks_class, "ascents", "[F");
        gLineBreaks_fieldID.descents = GetFieldIDOrDie(env, gLineBreaks_class, "descents", "[F");
        gLineBreaks_fieldID.flags = GetFieldIDOrDie(env, gLineBreaks_class, "flags", "[I");
    }
    return RegisterMethodsOrDie(env, "android/graphics/text/LineBreaker", gMethods,
                                NELEM(gMethods));
}

}
