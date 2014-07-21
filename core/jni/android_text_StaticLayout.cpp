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
#include <android_runtime/AndroidRuntime.h>
#include <cstdint>
#include <vector>
#include <list>
#include <algorithm>

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

class TabStops {
    public:
        // specified stops must be a sorted array (allowed to be null)
        TabStops(JNIEnv* env, jintArray stops, jint defaultTabWidth) :
            mStops(env), mTabWidth(defaultTabWidth) {
                if (stops != NULL) {
                    mStops.reset(stops);
                    mNumStops = mStops.size();
                } else {
                    mNumStops = 0;
                }
            }
        float width(float widthSoFar) const {
            const jint* mStopsArray = mStops.get();
            for (int i = 0; i < mNumStops; i++) {
                if (mStopsArray[i] > widthSoFar) {
                    return mStopsArray[i];
                }
            }
            // find the next tabstop after widthSoFar
            return static_cast<int>((widthSoFar + mTabWidth) / mTabWidth) * mTabWidth;
        }
    private:
        ScopedIntArrayRO mStops;
        const int mTabWidth;
        int mNumStops;

        // disable copying and assignment
        TabStops(const TabStops&);
        void operator=(const TabStops&);
};

enum PrimitiveType {
    kPrimitiveType_Box,
    kPrimitiveType_Glue,
    kPrimitiveType_Penalty,
    kPrimitiveType_Variable,
    kPrimitiveType_Wordbreak
};

static const float PENALTY_INFINITY = 1e7; // forced non-break, negative infinity is forced break

struct Primitive {
    PrimitiveType type;
    int location;
    // 'Box' has width
    // 'Glue' has width
    // 'Penalty' has width and penalty
    // 'Variable' has tabStop
    // 'Wordbreak' has penalty
    union {
        struct {
            float width;
            float penalty;
        };
        const TabStops* tabStop;
    };
};

class LineWidth {
    public:
        LineWidth(float firstWidth, int firstWidthLineCount, float restWidth) :
                mFirstWidth(firstWidth), mFirstWidthLineCount(firstWidthLineCount),
                mRestWidth(restWidth) {}
        float getLineWidth(int line) const {
            return (line < mFirstWidthLineCount) ? mFirstWidth : mRestWidth;
        }
    private:
        const float mFirstWidth;
        const int mFirstWidthLineCount;
        const float mRestWidth;
};

class LineBreaker {
    public:
        LineBreaker(const std::vector<Primitive>& primitives,
                    const LineWidth& lineWidth) :
                mPrimitives(primitives), mLineWidth(lineWidth) {}
        virtual ~LineBreaker() {}
        virtual void computeBreaks(std::vector<int>* breaks, std::vector<float>* widths,
                                   std::vector<unsigned char>* flags) const = 0;
    protected:
        const std::vector<Primitive>& mPrimitives;
        const LineWidth& mLineWidth;
};

class GreedyLineBreaker : public LineBreaker {
    public:
        GreedyLineBreaker(const std::vector<Primitive>& primitives, const LineWidth& lineWidth) :
            LineBreaker(primitives, lineWidth) {}
        void computeBreaks(std::vector<int>* breaks, std::vector<float>* widths,
                           std::vector<unsigned char>* flags) const {
            int lineNum = 0;
            float width = 0, printedWidth = 0;
            bool breakFound = false, goodBreakFound = false;
            int breakIndex = 0, goodBreakIndex = 0;
            float breakWidth = 0, goodBreakWidth = 0;
            int firstTabIndex = INT_MAX;

            float maxWidth = mLineWidth.getLineWidth(lineNum);

            const int numPrimitives = mPrimitives.size();
            // greedily fit as many characters as possible on each line
            // loop over all primitives, and choose the best break point
            // (if possible, a break point without splitting a word)
            // after going over the maximum length
            for (int i = 0; i < numPrimitives; i++) {
                const Primitive& p = mPrimitives[i];

                // update the current line width
                if (p.type == kPrimitiveType_Box || p.type == kPrimitiveType_Glue) {
                    width += p.width;
                    if (p.type == kPrimitiveType_Box) {
                        printedWidth = width;
                    }
                } else if (p.type == kPrimitiveType_Variable) {
                    width = p.tabStop->width(width);
                    // keep track of first tab character in the region we are examining
                    // so we can determine whether or not a line contains a tab
                    firstTabIndex = std::min(firstTabIndex, i);
                }

                // find the best break point for the characters examined so far
                if (printedWidth > maxWidth) {
                    if (breakFound || goodBreakFound) {
                        if (goodBreakFound) {
                            // a true line break opportunity existed in the characters examined so far,
                            // so there is no need to split a word
                            i = goodBreakIndex; // no +1 because of i++
                            lineNum++;
                            maxWidth = mLineWidth.getLineWidth(lineNum);
                            breaks->push_back(mPrimitives[goodBreakIndex].location);
                            widths->push_back(goodBreakWidth);
                            flags->push_back(firstTabIndex < goodBreakIndex);
                            firstTabIndex = SIZE_MAX;
                        } else {
                            // must split a word because there is no other option
                            i = breakIndex; // no +1 because of i++
                            lineNum++;
                            maxWidth = mLineWidth.getLineWidth(lineNum);
                            breaks->push_back(mPrimitives[breakIndex].location);
                            widths->push_back(breakWidth);
                            flags->push_back(firstTabIndex < breakIndex);
                            firstTabIndex = SIZE_MAX;
                        }
                        printedWidth = width = 0;
                        goodBreakFound = breakFound = false;
                        goodBreakWidth = breakWidth = 0;
                        continue;
                    } else {
                        // no choice, keep going... must make progress by putting at least one
                        // character on a line, even if part of that character is cut off --
                        // there is no other option
                    }
                }

                // update possible break points
                if (p.type == kPrimitiveType_Penalty && p.penalty < PENALTY_INFINITY) {
                    // this does not handle penalties with width

                    // handle forced line break
                    if (p.penalty == -PENALTY_INFINITY) {
                        lineNum++;
                        maxWidth = mLineWidth.getLineWidth(lineNum);
                        breaks->push_back(p.location);
                        widths->push_back(printedWidth);
                        flags->push_back(firstTabIndex < i);
                        firstTabIndex = SIZE_MAX;
                        printedWidth = width = 0;
                        goodBreakFound = breakFound = false;
                        goodBreakWidth = breakWidth = 0;
                        continue;
                    }
                    if (i > breakIndex && (printedWidth <= maxWidth || breakFound == false)) {
                        breakFound = true;
                        breakIndex = i;
                        breakWidth = printedWidth;
                    }
                    if (i > goodBreakIndex && printedWidth <= maxWidth) {
                        goodBreakFound = true;
                        goodBreakIndex = i;
                        goodBreakWidth = printedWidth;
                    }
                } else if (p.type == kPrimitiveType_Wordbreak) {
                    // only do this if necessary -- we don't want to break words
                    // when possible, but sometimes it is unavoidable
                    if (i > breakIndex && (printedWidth <= maxWidth || breakFound == false)) {
                        breakFound = true;
                        breakIndex = i;
                        breakWidth = printedWidth;
                    }
                }
            }

            if (breakFound || goodBreakFound) {
                // output last break if there are more characters to output
                if (goodBreakFound) {
                    breaks->push_back(mPrimitives[goodBreakIndex].location);
                    widths->push_back(goodBreakWidth);
                    flags->push_back(firstTabIndex < goodBreakIndex);
                } else {
                    breaks->push_back(mPrimitives[breakIndex].location);
                    widths->push_back(breakWidth);
                    flags->push_back(firstTabIndex < breakIndex);
                }
            }
        }
};

class ScopedBreakIterator {
    public:
        ScopedBreakIterator(JNIEnv* env, BreakIterator* breakIterator, const jchar* inputText,
                            jint length) : mBreakIterator(breakIterator), mChars(inputText) {
            UErrorCode status = U_ZERO_ERROR;
            mUText = utext_openUChars(NULL, mChars, length, &status);
            if (mUText == NULL) {
                return;
            }

            mBreakIterator->setText(mUText, status);
        }

        inline BreakIterator* operator->() {
            return mBreakIterator;
        }

        ~ScopedBreakIterator() {
            utext_close(mUText);
            delete mBreakIterator;
        }
    private:
        BreakIterator* mBreakIterator;
        const jchar* mChars;
        UText* mUText;

        // disable copying and assignment
        ScopedBreakIterator(const ScopedBreakIterator&);
        void operator=(const ScopedBreakIterator&);
};

static jint recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jbooleanArray recycleFlags,
                        jint recycleLength, const std::vector<jint>& breaks,
                        const std::vector<jfloat>& widths, const std::vector<jboolean>& flags) {
    int bufferLength = breaks.size();
    if (recycleLength < bufferLength) {
        // have to reallocate buffers
        recycleBreaks = env->NewIntArray(bufferLength);
        recycleWidths = env->NewFloatArray(bufferLength);
        recycleFlags = env->NewBooleanArray(bufferLength);

        env->SetObjectField(recycle, gLineBreaks_fieldID.breaks, recycleBreaks);
        env->SetObjectField(recycle, gLineBreaks_fieldID.widths, recycleWidths);
        env->SetObjectField(recycle, gLineBreaks_fieldID.flags, recycleFlags);
    }
    // copy data
    env->SetIntArrayRegion(recycleBreaks, 0, breaks.size(), &breaks.front());
    env->SetFloatArrayRegion(recycleWidths, 0, widths.size(), &widths.front());
    env->SetBooleanArrayRegion(recycleFlags, 0, flags.size(), &flags.front());

    return bufferLength;
}

void computePrimitives(const jchar* textArr, const jfloat* widthsArr, jint length, const std::vector<int>& breaks,
                       const TabStops& tabStopCalculator, std::vector<Primitive>* primitives) {
    int breaksSize = breaks.size();
    int breakIndex = 0;
    Primitive p;
    for (int i = 0; i < length; i++) {
        p.location = i;
        jchar c = textArr[i];
        if (c == CHAR_SPACE || c == CHAR_ZWSP) {
            p.type = kPrimitiveType_Glue;
            p.width = widthsArr[i];
            primitives->push_back(p);
        } else if (c == CHAR_TAB) {
            p.type = kPrimitiveType_Variable;
            p.tabStop = &tabStopCalculator; // shared between all variable primitives
            primitives->push_back(p);
        } else if (c != CHAR_NEWLINE) {
            while (breakIndex < breaksSize && breaks[breakIndex] < i) breakIndex++;
            p.width = 0;
            if (breakIndex < breaksSize && breaks[breakIndex] == i) {
                p.type = kPrimitiveType_Penalty;
                p.penalty = 0;
            } else {
                p.type = kPrimitiveType_Wordbreak;
            }
            if (widthsArr[i] != 0) {
                primitives->push_back(p);
            }

            p.type = kPrimitiveType_Box;
            p.width = widthsArr[i];
            primitives->push_back(p);
        }
    }
    // final break at end of everything
    p.location = length;
    p.type = kPrimitiveType_Penalty;
    p.width = 0;
    p.penalty = -PENALTY_INFINITY;
    primitives->push_back(p);
}

static jint nComputeLineBreaks(JNIEnv* env, jclass, jstring javaLocaleName,
                               jcharArray inputText, jfloatArray widths, jint length,
                               jfloat firstWidth, jint firstWidthLineLimit, jfloat restWidth,
                               jintArray variableTabStops, jint defaultTabStop,
                               jobject recycle, jintArray recycleBreaks,
                               jfloatArray recycleWidths, jbooleanArray recycleFlags,
                               jint recycleLength) {
    jintArray ret;
    std::vector<int> breaks;

    ScopedCharArrayRO textScopedArr(env, inputText);
    ScopedFloatArrayRO widthsScopedArr(env, widths);

    ScopedIcuLocale icuLocale(env, javaLocaleName);
    if (icuLocale.valid()) {
        UErrorCode status = U_ZERO_ERROR;
        BreakIterator* it = BreakIterator::createLineInstance(icuLocale.locale(), status);
        if (!U_SUCCESS(status) || it == NULL) {
            if (it) {
                delete it;
            }
        } else {
            ScopedBreakIterator breakIterator(env, it, textScopedArr.get(), length);
            int loc = breakIterator->first();
            while ((loc = breakIterator->next()) != BreakIterator::DONE) {
                breaks.push_back(loc);
            }
        }
    }

    std::vector<Primitive> primitives;
    TabStops tabStops(env, variableTabStops, defaultTabStop);
    computePrimitives(textScopedArr.get(), widthsScopedArr.get(), length, breaks, tabStops, &primitives);

    LineWidth lineWidth(firstWidth, firstWidthLineLimit, restWidth);
    std::vector<int> computedBreaks;
    std::vector<float> computedWidths;
    std::vector<unsigned char> computedFlags;

    GreedyLineBreaker breaker(primitives, lineWidth);
    breaker.computeBreaks(&computedBreaks, &computedWidths, &computedFlags);

    return recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleFlags, recycleLength,
            computedBreaks, computedWidths, computedFlags);
}

static JNINativeMethod gMethods[] = {
    {"nComputeLineBreaks", "(Ljava/lang/String;[C[FIFIF[IILandroid/text/StaticLayout$LineBreaks;[I[F[ZI)I", (void*) nComputeLineBreaks}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    gLineBreaks_class = reinterpret_cast<jclass>(env->NewGlobalRef(
            env->FindClass("android/text/StaticLayout$LineBreaks")));

    gLineBreaks_fieldID.breaks = env->GetFieldID(gLineBreaks_class, "breaks", "[I");
    gLineBreaks_fieldID.widths = env->GetFieldID(gLineBreaks_class, "widths", "[F");
    gLineBreaks_fieldID.flags = env->GetFieldID(gLineBreaks_class, "flags", "[Z");

    return AndroidRuntime::registerNativeMethods(env, "android/text/StaticLayout",
            gMethods, NELEM(gMethods));
}

}
