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
                const std::vector<float>& indents, const std::vector<float>& leftPaddings,
                const std::vector<float>& rightPaddings, int32_t indentsAndPaddingsOffset)
            : mFirstWidth(firstWidth), mFirstLineCount(firstLineCount), mRestWidth(restWidth),
              mIndents(indents), mLeftPaddings(leftPaddings),
              mRightPaddings(rightPaddings), mOffset(indentsAndPaddingsOffset) {}

        float getLineWidth(size_t lineNo) override {
            const float width = ((ssize_t)lineNo < (ssize_t)mFirstLineCount)
                    ? mFirstWidth : mRestWidth;
            return width - get(mIndents, lineNo);
        }

        float getLeftPadding(size_t lineNo) override {
            return get(mLeftPaddings, lineNo);
        }

        float getRightPadding(size_t lineNo) override {
            return get(mRightPaddings, lineNo);
        }

    private:
        float get(const std::vector<float>& vec, size_t lineNo) {
            if (vec.empty()) {
                return 0;
            }
            const size_t index = lineNo + mOffset;
            if (index < vec.size()) {
                return vec[index];
            } else {
                return vec.back();
            }
        }

        const float mFirstWidth;
        const int32_t mFirstLineCount;
        const float mRestWidth;
        const std::vector<float>& mIndents;
        const std::vector<float>& mLeftPaddings;
        const std::vector<float>& mRightPaddings;
        const int32_t mOffset;
};

static inline std::vector<float> jintArrayToFloatVector(JNIEnv* env, jintArray javaArray) {
    if (javaArray == nullptr) {
         return std::vector<float>();
    } else {
        ScopedIntArrayRO intArr(env, javaArray);
        return std::vector<float>(intArr.get(), intArr.get() + intArr.size());
    }
}

class Run {
    public:
        Run(int32_t start, int32_t end) : mStart(start), mEnd(end) {}
        virtual ~Run() {}

        virtual void addTo(minikin::LineBreaker* lineBreaker) = 0;

    protected:
        const int32_t mStart;
        const int32_t mEnd;

    private:
        // Forbid copy and assign.
        Run(const Run&) = delete;
        void operator=(const Run&) = delete;
};

class StyleRun : public Run {
    public:
        StyleRun(int32_t start, int32_t end, minikin::MinikinPaint&& paint,
                std::shared_ptr<minikin::FontCollection>&& collection,
                minikin::FontStyle&& style, bool isRtl)
            : Run(start, end), mPaint(std::move(paint)), mCollection(std::move(collection)),
              mStyle(std::move(style)), mIsRtl(isRtl) {}

        void addTo(minikin::LineBreaker* lineBreaker) override {
            lineBreaker->addStyleRun(&mPaint, mCollection, mStyle, mStart, mEnd, mIsRtl);
        }

    private:
        minikin::MinikinPaint mPaint;
        std::shared_ptr<minikin::FontCollection> mCollection;
        minikin::FontStyle mStyle;
        const bool mIsRtl;
};

class Replacement : public Run {
    public:
        Replacement(int32_t start, int32_t end, float width, uint32_t localeListId)
            : Run(start, end), mWidth(width), mLocaleListId(localeListId) {}

        void addTo(minikin::LineBreaker* lineBreaker) override {
            lineBreaker->addReplacement(mStart, mEnd, mWidth, mLocaleListId);
        }

    private:
        const float mWidth;
        const uint32_t mLocaleListId;
};

class StaticLayoutNative {
    public:
        StaticLayoutNative(
                minikin::BreakStrategy strategy, minikin::HyphenationFrequency frequency,
                bool isJustified, std::vector<float>&& indents, std::vector<float>&& leftPaddings,
                std::vector<float>&& rightPaddings)
            : mStrategy(strategy), mFrequency(frequency), mIsJustified(isJustified),
              mIndents(std::move(indents)), mLeftPaddings(std::move(leftPaddings)),
              mRightPaddings(std::move(rightPaddings)) {}

        void addStyleRun(int32_t start, int32_t end, minikin::MinikinPaint&& paint,
                         std::shared_ptr<minikin::FontCollection> collection,
                         minikin::FontStyle&& style, bool isRtl) {
            mRuns.emplace_back(std::make_unique<StyleRun>(
                    start, end, std::move(paint), std::move(collection), std::move(style), isRtl));
        }

        void addReplacementRun(int32_t start, int32_t end, float width, uint32_t localeListId) {
            mRuns.emplace_back(std::make_unique<Replacement>(start, end, width, localeListId));
        }

        // Only valid while this instance is alive.
        inline std::unique_ptr<minikin::LineBreaker::LineWidthDelegate> buildLineWidthDelegate(
                float firstWidth, int32_t firstLineCount, float restWidth,
                int32_t indentsAndPaddingsOffset) {
            return std::make_unique<JNILineBreakerLineWidth>(
                firstWidth, firstLineCount, restWidth, mIndents, mLeftPaddings, mRightPaddings,
                indentsAndPaddingsOffset);
        }

        void addRuns(minikin::LineBreaker* lineBreaker) {
            for (const auto& run : mRuns) {
                run->addTo(lineBreaker);
            }
        }

        void clearRuns() {
            mRuns.clear();
        }

        inline minikin::BreakStrategy getStrategy() const { return mStrategy; }
        inline minikin::HyphenationFrequency getFrequency() const { return mFrequency; }
        inline bool isJustified() const { return mIsJustified; }

    private:
        const minikin::BreakStrategy mStrategy;
        const minikin::HyphenationFrequency mFrequency;
        const bool mIsJustified;
        const std::vector<float> mIndents;
        const std::vector<float> mLeftPaddings;
        const std::vector<float> mRightPaddings;

        std::vector<std::unique_ptr<Run>> mRuns;
};

static inline StaticLayoutNative* toNative(jlong ptr) {
    return reinterpret_cast<StaticLayoutNative*>(ptr);
}

// set text and set a number of parameters for creating a layout (width, tabstops, strategy,
// hyphenFrequency)
static jlong nInit(JNIEnv* env, jclass /* unused */,
        jint breakStrategy, jint hyphenationFrequency, jboolean isJustified,
        jintArray indents, jintArray leftPaddings, jintArray rightPaddings) {
    return reinterpret_cast<jlong>(new StaticLayoutNative(
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
        jcharArray text,
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

    StaticLayoutNative* builder = toNative(nativePtr);

    // TODO: Reorganize minikin APIs.
    minikin::LineBreaker b;
    b.resize(length);
    env->GetCharArrayRegion(text, 0, length, b.buffer());
    b.setText();
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

    b.finish();
    builder->clearRuns();

    return static_cast<jint>(nBreaks);
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
// CriticalNative
static void nAddStyleRun(jlong nativePtr, jlong nativePaint, jint start, jint end, jboolean isRtl) {
    StaticLayoutNative* builder = toNative(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    const Typeface* typeface = paint->getAndroidTypeface();
    minikin::MinikinPaint minikinPaint;
    const Typeface* resolvedTypeface = Typeface::resolveDefault(typeface);
    minikin::FontStyle style = MinikinUtils::prepareMinikinPaint(&minikinPaint, paint,
            typeface);

    builder->addStyleRun(
        start, end, std::move(minikinPaint), resolvedTypeface->fFontCollection, std::move(style),
        isRtl);
}

// CriticalNative
static void nAddReplacementRun(jlong nativePtr, jlong nativePaint, jint start, jint end,
        jfloat width) {
    StaticLayoutNative* builder = toNative(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    builder->addReplacementRun(start, end, width, paint->getMinikinLangListId());
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
