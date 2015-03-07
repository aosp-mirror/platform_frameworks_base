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
#include "ScopedPrimitiveArray.h"
#include "JNIHelp.h"
#include "core_jni_helpers.h"
#include <cstdint>
#include <vector>
#include <list>
#include <algorithm>

#include "SkPaint.h"
#include "SkTypeface.h"
#include "MinikinSkia.h"
#include "MinikinUtils.h"
#include "Paint.h"
#include "minikin/LineBreaker.h"

namespace android {

struct JLineBreaksID {
    jfieldID breaks;
    jfieldID widths;
    jfieldID flags;
};

static jclass gLineBreaks_class;
static JLineBreaksID gLineBreaks_fieldID;

static const int CHAR_SPACE = 0x20;
static const int CHAR_TAB = 0x09;
static const int CHAR_NEWLINE = 0x0a;
static const int CHAR_ZWSP = 0x200b;

// set text and set a number of parameters for creating a layout (width, tabstops, strategy)
static void nSetupParagraph(JNIEnv* env, jclass, jlong nativePtr, jcharArray text, jint length,
        jfloat firstWidth, jint firstWidthLineLimit, jfloat restWidth,
        jintArray variableTabStops, jint defaultTabStop, jint strategy) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    b->resize(length);
    env->GetCharArrayRegion(text, 0, length, b->buffer());
    b->setText();
    b->setLineWidths(firstWidth, firstWidthLineLimit, restWidth);
    if (variableTabStops == nullptr) {
        b->setTabStops(nullptr, 0, defaultTabStop);
    } else {
        ScopedIntArrayRO stops(env, variableTabStops);
        b->setTabStops(stops.get(), stops.size(), defaultTabStop);
    }
    b->setStrategy(static_cast<BreakStrategy>(strategy));
}

static void recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jbooleanArray recycleFlags,
                        jint recycleLength, size_t nBreaks, const jint* breaks,
                        const jfloat* widths, const jboolean* flags) {
    if ((size_t)recycleLength < nBreaks) {
        // have to reallocate buffers
        recycleBreaks = env->NewIntArray(nBreaks);
        recycleWidths = env->NewFloatArray(nBreaks);
        recycleFlags = env->NewBooleanArray(nBreaks);

        env->SetObjectField(recycle, gLineBreaks_fieldID.breaks, recycleBreaks);
        env->SetObjectField(recycle, gLineBreaks_fieldID.widths, recycleWidths);
        env->SetObjectField(recycle, gLineBreaks_fieldID.flags, recycleFlags);
    }
    // copy data
    env->SetIntArrayRegion(recycleBreaks, 0, nBreaks, breaks);
    env->SetFloatArrayRegion(recycleWidths, 0, nBreaks, widths);
    env->SetBooleanArrayRegion(recycleFlags, 0, nBreaks, flags);
}

static jint nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
                               jobject recycle, jintArray recycleBreaks,
                               jfloatArray recycleWidths, jbooleanArray recycleFlags,
                               jint recycleLength) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);

    size_t nBreaks = b->computeBreaks();

    recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleFlags, recycleLength,
            nBreaks, b->getBreaks(), b->getWidths(), b->getFlags());

    b->finish();

    return static_cast<jint>(nBreaks);
}

static jlong nNewBuilder(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new LineBreaker);
}

static void nFreeBuilder(JNIEnv*, jclass, jlong nativePtr) {
    delete reinterpret_cast<LineBreaker*>(nativePtr);
}

static void nFinishBuilder(JNIEnv*, jclass, jlong nativePtr) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    b->finish();
}

static void nSetLocale(JNIEnv* env, jclass, jlong nativePtr, jstring javaLocaleName) {
    ScopedIcuLocale icuLocale(env, javaLocaleName);
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);

    if (icuLocale.valid()) {
        b->setLocale(icuLocale.locale());
    }
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
static jfloat nAddStyleRun(JNIEnv* env, jclass, jlong nativePtr,
        jlong nativePaint, jlong nativeTypeface, jint start, jint end, jboolean isRtl) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(nativeTypeface);
    FontCollection *font;
    MinikinPaint minikinPaint;
    FontStyle style = MinikinUtils::prepareMinikinPaint(&minikinPaint, &font, paint, typeface);
    return b->addStyleRun(&minikinPaint, font, style, start, end, isRtl);
}

// Accept width measurements for the run, passed in from Java
static void nAddMeasuredRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloatArray widths) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    env->GetFloatArrayRegion(widths, start, end - start, b->charWidths() + start);
    b->addStyleRun(nullptr, nullptr, FontStyle{}, start, end, false);
}

static void nAddReplacementRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloat width) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    b->addReplacement(start, end, width);
}

static void nGetWidths(JNIEnv* env, jclass, jlong nativePtr, jfloatArray widths) {
    LineBreaker* b = reinterpret_cast<LineBreaker*>(nativePtr);
    env->SetFloatArrayRegion(widths, 0, b->size(), b->charWidths());
}

static JNINativeMethod gMethods[] = {
    // TODO performance: many of these are candidates for fast jni, awaiting guidance
    {"nNewBuilder", "()J", (void*) nNewBuilder},
    {"nFreeBuilder", "(J)V", (void*) nFreeBuilder},
    {"nFinishBuilder", "(J)V", (void*) nFinishBuilder},
    {"nSetLocale", "(JLjava/lang/String;)V", (void*) nSetLocale},
    {"nSetupParagraph", "(J[CIFIF[III)V", (void*) nSetupParagraph},
    {"nAddStyleRun", "(JJJIIZ)F", (void*) nAddStyleRun},
    {"nAddMeasuredRun", "(JII[F)V", (void*) nAddMeasuredRun},
    {"nAddReplacementRun", "(JIIF)V", (void*) nAddReplacementRun},
    {"nGetWidths", "(J[F)V", (void*) nGetWidths},
    {"nComputeLineBreaks", "(JLandroid/text/StaticLayout$LineBreaks;[I[F[ZI)I",
        (void*) nComputeLineBreaks}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    gLineBreaks_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/text/StaticLayout$LineBreaks"));

    gLineBreaks_fieldID.breaks = GetFieldIDOrDie(env, gLineBreaks_class, "breaks", "[I");
    gLineBreaks_fieldID.widths = GetFieldIDOrDie(env, gLineBreaks_class, "widths", "[F");
    gLineBreaks_fieldID.flags = GetFieldIDOrDie(env, gLineBreaks_class, "flags", "[Z");

    return RegisterMethodsOrDie(env, "android/text/StaticLayout", gMethods, NELEM(gMethods));
}

}
