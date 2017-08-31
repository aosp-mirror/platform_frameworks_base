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
#include <minikin/LineBreaker.h>
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

class JNILineBreakerLineWidth : public minikin::LineBreaker::LineWidthDelegate {
    public:
        JNILineBreakerLineWidth(float firstWidth, int32_t firstLineCount, float restWidth,
                std::vector<float>&& indents, int32_t indentsOffset)
            : mFirstWidth(firstWidth), mFirstLineCount(firstLineCount), mRestWidth(restWidth),
              mIndents(std::move(indents)), mIndentsOffset(indentsOffset) {}

        float getLineWidth(size_t lineNo) override {
            const float width = ((ssize_t)lineNo < (ssize_t)mFirstLineCount)
                    ? mFirstWidth : mRestWidth;
            if (mIndents.empty()) {
                return width;
            }

            const size_t indentIndex = lineNo + mIndentsOffset;
            if (indentIndex < mIndents.size()) {
                return width - mIndents[indentIndex];
            } else {
                return width - mIndents.back();
            }
        }

    private:
        const float mFirstWidth;
        const int32_t mFirstLineCount;
        const float mRestWidth;
        const std::vector<float> mIndents;
        const int32_t mIndentsOffset;
};

// set text and set a number of parameters for creating a layout (width, tabstops, strategy,
// hyphenFrequency)
static void nSetupParagraph(JNIEnv* env, jclass, jlong nativePtr, jcharArray text, jint length,
        jfloat firstWidth, jint firstWidthLineLimit, jfloat restWidth,
        jintArray variableTabStops, jint defaultTabStop, jint strategy, jint hyphenFrequency,
        jboolean isJustified, jintArray indents, jint indentsOffset) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    b->resize(length);
    env->GetCharArrayRegion(text, 0, length, b->buffer());
    b->setText();
    if (variableTabStops == nullptr) {
        b->setTabStops(nullptr, 0, defaultTabStop);
    } else {
        ScopedIntArrayRO stops(env, variableTabStops);
        b->setTabStops(stops.get(), stops.size(), defaultTabStop);
    }
    b->setStrategy(static_cast<minikin::BreakStrategy>(strategy));
    b->setHyphenationFrequency(static_cast<minikin::HyphenationFrequency>(hyphenFrequency));
    b->setJustified(isJustified);

    std::vector<float> indentVec;
    // TODO: copy indents only once when LineBreaker is started to be used.
    if (indents != nullptr) {
        ScopedIntArrayRO indentArr(env, indents);
        indentVec.assign(indentArr.get(), indentArr.get() + indentArr.size());
    }
    b->setLineWidthDelegate(std::make_unique<JNILineBreakerLineWidth>(
            firstWidth, firstWidthLineLimit, restWidth, std::move(indentVec), indentsOffset));
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
                               jobject recycle, jintArray recycleBreaks,
                               jfloatArray recycleWidths, jfloatArray recycleAscents,
                               jfloatArray recycleDescents, jintArray recycleFlags,
                               jint recycleLength) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);

    size_t nBreaks = b->computeBreaks();

    recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleAscents, recycleDescents,
            recycleFlags, recycleLength, nBreaks, b->getBreaks(), b->getWidths(), b->getAscents(),
            b->getDescents(), b->getFlags());

    b->finish();

    return static_cast<jint>(nBreaks);
}

static jlong nNewBuilder(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new minikin::LineBreaker);
}

static void nFreeBuilder(JNIEnv*, jclass, jlong nativePtr) {
    delete reinterpret_cast<minikin::LineBreaker*>(nativePtr);
}

static void nFinishBuilder(JNIEnv*, jclass, jlong nativePtr) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    b->finish();
}

static jlong nLoadHyphenator(JNIEnv* env, jclass, jobject buffer, jint offset,
        jint minPrefix, jint minSuffix) {
    const uint8_t* bytebuf = nullptr;
    if (buffer != nullptr) {
        void* rawbuf = env->GetDirectBufferAddress(buffer);
        if (rawbuf != nullptr) {
            bytebuf = reinterpret_cast<const uint8_t*>(rawbuf) + offset;
        } else {
            ALOGE("failed to get direct buffer address");
        }
    }
    minikin::Hyphenator* hyphenator = minikin::Hyphenator::loadBinary(
            bytebuf, minPrefix, minSuffix);
    return reinterpret_cast<jlong>(hyphenator);
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
static jfloat nAddStyleRun(JNIEnv* env, jclass, jlong nativePtr, jlong nativePaint, jint start,
        jint end, jboolean isRtl, jstring langTags, jlongArray hyphenators) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    const Typeface* typeface = paint->getAndroidTypeface();
    minikin::MinikinPaint minikinPaint;
    const Typeface* resolvedTypeface = Typeface::resolveDefault(typeface);
    minikin::FontStyle style = MinikinUtils::prepareMinikinPaint(&minikinPaint, paint,
            typeface);

    std::vector<minikin::Hyphenator*> hyphVec;
    const char* langTagStr;
    if (langTags == nullptr) {
        langTagStr = nullptr;  // nullptr languageTag means keeping current locale
    } else {
        ScopedLongArrayRO hyphArr(env, hyphenators);
        const size_t numLocales = hyphArr.size();
        hyphVec.reserve(numLocales);
        for (size_t i = 0; i < numLocales; i++) {
          hyphVec.push_back(reinterpret_cast<minikin::Hyphenator*>(hyphArr[i]));
        }
        langTagStr = env->GetStringUTFChars(langTags, nullptr);
    }
    float result = b->addStyleRun(&minikinPaint, resolvedTypeface->fFontCollection, style, start,
            end, isRtl, langTagStr, hyphVec);
    if (langTagStr != nullptr)  {
        env->ReleaseStringUTFChars(langTags, langTagStr);
    }
    return result;
}

// Accept width measurements for the run, passed in from Java
static void nAddMeasuredRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloatArray widths) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    env->GetFloatArrayRegion(widths, start, end - start, b->charWidths() + start);
    b->addStyleRun(nullptr, nullptr, minikin::FontStyle{}, start, end, false,
            nullptr /* keep current locale */, std::vector<minikin::Hyphenator*>());
}

static void nAddReplacementRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloat width) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    b->addReplacement(start, end, width);
}

static void nGetWidths(JNIEnv* env, jclass, jlong nativePtr, jfloatArray widths) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    env->SetFloatArrayRegion(widths, 0, b->size(), b->charWidths());
}

static const JNINativeMethod gMethods[] = {
    // TODO performance: many of these are candidates for fast jni, awaiting guidance
    {"nNewBuilder", "()J", (void*) nNewBuilder},
    {"nFreeBuilder", "(J)V", (void*) nFreeBuilder},
    {"nFinishBuilder", "(J)V", (void*) nFinishBuilder},
    {"nLoadHyphenator", "(Ljava/nio/ByteBuffer;III)J", (void*) nLoadHyphenator},
    {"nSetupParagraph", "(J[CIFIF[IIIIZ[II)V", (void*) nSetupParagraph},
    {"nAddStyleRun", "(JJIIZLjava/lang/String;[J)F", (void*) nAddStyleRun},
    {"nAddMeasuredRun", "(JII[F)V", (void*) nAddMeasuredRun},
    {"nAddReplacementRun", "(JIIF)V", (void*) nAddReplacementRun},
    {"nGetWidths", "(J[F)V", (void*) nGetWidths},
    {"nComputeLineBreaks", "(JLandroid/text/StaticLayout$LineBreaks;[I[F[F[F[II)I",
        (void*) nComputeLineBreaks}
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
