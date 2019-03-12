/* libs/android_runtime/android/graphics/Paint.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "Paint"

#include <utils/Log.h>

#include "jni.h"
#include "GraphicsJNI.h"
#include "core_jni_helpers.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "SkBlurDrawLooper.h"
#include "SkColorFilter.h"
#include "SkFont.h"
#include "SkFontMetrics.h"
#include "SkFontTypes.h"
#include "SkMaskFilter.h"
#include "SkPath.h"
#include "SkPathEffect.h"
#include "SkShader.h"
#include "SkBlendMode.h"
#include "unicode/uloc.h"
#include "unicode/ushape.h"
#include "utils/Blur.h"

#include <hwui/MinikinSkia.h>
#include <hwui/MinikinUtils.h>
#include <hwui/Paint.h>
#include <hwui/Typeface.h>
#include <minikin/GraphemeBreak.h>
#include <minikin/LocaleList.h>
#include <minikin/Measurement.h>
#include <minikin/MinikinPaint.h>
#include <unicode/utf16.h>

#include <cassert>
#include <cstring>
#include <memory>
#include <vector>

namespace android {

struct JMetricsID {
    jfieldID    top;
    jfieldID    ascent;
    jfieldID    descent;
    jfieldID    bottom;
    jfieldID    leading;
};

static jclass   gFontMetrics_class;
static JMetricsID gFontMetrics_fieldID;

static jclass   gFontMetricsInt_class;
static JMetricsID gFontMetricsInt_fieldID;

static void getPosTextPath(const SkFont& font, const uint16_t glyphs[], int count,
                           const SkPoint pos[], SkPath* dst) {
    dst->reset();
    struct Rec {
        SkPath* fDst;
        const SkPoint* fPos;
    } rec = { dst, pos };
    font.getPaths(glyphs, count, [](const SkPath* src, const SkMatrix& mx, void* ctx) {
        Rec* rec = (Rec*)ctx;
        if (src) {
            SkMatrix tmp(mx);
            tmp.postTranslate(rec->fPos->fX, rec->fPos->fY);
            rec->fDst->addPath(*src, tmp);
        }
        rec->fPos += 1;
    }, &rec);
}

namespace PaintGlue {
    enum MoveOpt {
        AFTER, AT_OR_AFTER, BEFORE, AT_OR_BEFORE, AT
    };

    static void deletePaint(Paint* paint) {
        delete paint;
    }

    static jlong getNativeFinalizer(JNIEnv*, jobject) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deletePaint));
    }

    static jlong init(JNIEnv* env, jobject) {
        return reinterpret_cast<jlong>(new Paint);
    }

    static jlong initWithPaint(JNIEnv* env, jobject clazz, jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        Paint* obj = new Paint(*paint);
        return reinterpret_cast<jlong>(obj);
    }

    static int breakText(JNIEnv* env, const Paint& paint, const Typeface* typeface,
            const jchar text[], int count, float maxWidth, jint bidiFlags, jfloatArray jmeasured,
            const bool forwardScan) {
        size_t measuredCount = 0;
        float measured = 0;

        std::unique_ptr<float[]> advancesArray(new float[count]);
        MinikinUtils::measureText(&paint, static_cast<minikin::Bidi>(bidiFlags), typeface, text,
                0, count, count, advancesArray.get());

        for (int i = 0; i < count; i++) {
            // traverse in the given direction
            int index = forwardScan ? i : (count - i - 1);
            float width = advancesArray[index];
            if (measured + width > maxWidth) {
                break;
            }
            // properly handle clusters when scanning backwards
            if (forwardScan || width != 0.0f) {
                measuredCount = i + 1;
            }
            measured += width;
        }

        if (jmeasured && env->GetArrayLength(jmeasured) > 0) {
            AutoJavaFloatArray autoMeasured(env, jmeasured, 1);
            jfloat* array = autoMeasured.ptr();
            array[0] = measured;
        }
        return measuredCount;
    }

    static jint breakTextC(JNIEnv* env, jobject clazz, jlong paintHandle, jcharArray jtext,
            jint index, jint count, jfloat maxWidth, jint bidiFlags, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jtext);

        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();

        bool forwardTextDirection;
        if (count < 0) {
            forwardTextDirection = false;
            count = -count;
        }
        else {
            forwardTextDirection = true;
        }

        if ((index < 0) || (index + count > env->GetArrayLength(jtext))) {
            doThrowAIOOBE(env);
            return 0;
        }

        const jchar* text = env->GetCharArrayElements(jtext, nullptr);
        count = breakText(env, *paint, typeface, text + index, count, maxWidth,
                          bidiFlags, jmeasuredWidth, forwardTextDirection);
        env->ReleaseCharArrayElements(jtext, const_cast<jchar*>(text),
                                      JNI_ABORT);
        return count;
    }

    static jint breakTextS(JNIEnv* env, jobject clazz, jlong paintHandle, jstring jtext,
            jboolean forwards, jfloat maxWidth, jint bidiFlags, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jtext);

        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();

        int count = env->GetStringLength(jtext);
        const jchar* text = env->GetStringChars(jtext, nullptr);
        count = breakText(env, *paint, typeface, text, count, maxWidth, bidiFlags, jmeasuredWidth, forwards);
        env->ReleaseStringChars(jtext, text);
        return count;
    }

    static jfloat doTextAdvances(JNIEnv *env, Paint *paint, const Typeface* typeface,
            const jchar *text, jint start, jint count, jint contextCount, jint bidiFlags,
            jfloatArray advances, jint advancesIndex) {
        NPE_CHECK_RETURN_ZERO(env, text);

        if ((start | count | contextCount | advancesIndex) < 0 || contextCount < count) {
            doThrowAIOOBE(env);
            return 0;
        }
        if (count == 0) {
            return 0;
        }
        if (advances) {
            size_t advancesLength = env->GetArrayLength(advances);
            if ((size_t)(count  + advancesIndex) > advancesLength) {
                doThrowAIOOBE(env);
                return 0;
            }
        }
        std::unique_ptr<jfloat[]> advancesArray;
        if (advances) {
            advancesArray.reset(new jfloat[count]);
        }
        const float advance = MinikinUtils::measureText(paint,
                static_cast<minikin::Bidi>(bidiFlags), typeface, text, start, count, contextCount,
                advancesArray.get());
        if (advances) {
            env->SetFloatArrayRegion(advances, advancesIndex, count, advancesArray.get());
        }
        return advance;
    }

    static jfloat getTextAdvances___CIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,
            jcharArray text, jint index, jint count, jint contextIndex, jint contextCount,
            jint bidiFlags, jfloatArray advances, jint advancesIndex) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        jchar* textArray = env->GetCharArrayElements(text, nullptr);
        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextIndex,
                index - contextIndex, count, contextCount, bidiFlags, advances, advancesIndex);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
        return result;
    }

    static jfloat getTextAdvances__StringIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,
            jstring text, jint start, jint end, jint contextStart, jint contextEnd, jint bidiFlags,
            jfloatArray advances, jint advancesIndex) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        const jchar* textArray = env->GetStringChars(text, nullptr);
        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextStart,
                start - contextStart, end - start, contextEnd - contextStart, bidiFlags,
                advances, advancesIndex);
        env->ReleaseStringChars(text, textArray);
        return result;
    }

    static jint doTextRunCursor(JNIEnv *env, Paint* paint, const Typeface* typeface,
            const jchar *text, jint start, jint count, jint dir, jint offset, jint opt) {
        minikin::GraphemeBreak::MoveOpt moveOpt = minikin::GraphemeBreak::MoveOpt(opt);
        minikin::Bidi bidiFlags = dir == 1 ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;
        std::unique_ptr<float[]> advancesArray(new float[count]);
        MinikinUtils::measureText(paint, bidiFlags, typeface, text, start, count, start + count,
                advancesArray.get());
        size_t result = minikin::GraphemeBreak::getTextRunCursor(advancesArray.get(), text,
                start, count, offset, moveOpt);
        return static_cast<jint>(result);
    }

    static jint getTextRunCursor___C(JNIEnv* env, jobject clazz, jlong paintHandle, jcharArray text,
            jint contextStart, jint contextCount, jint dir, jint offset, jint cursorOpt) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        jchar* textArray = env->GetCharArrayElements(text, nullptr);
        jint result = doTextRunCursor(env, paint, typeface, textArray,
                contextStart, contextCount, dir, offset, cursorOpt);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
        return result;
    }

    static jint getTextRunCursor__String(JNIEnv* env, jobject clazz, jlong paintHandle,
            jstring text, jint contextStart, jint contextEnd, jint dir, jint offset,
            jint cursorOpt) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        const jchar* textArray = env->GetStringChars(text, nullptr);
        jint result = doTextRunCursor(env, paint, typeface, textArray,
                contextStart, contextEnd - contextStart, dir, offset, cursorOpt);
        env->ReleaseStringChars(text, textArray);
        return result;
    }

    class GetTextFunctor {
    public:
        GetTextFunctor(const minikin::Layout& layout, SkPath* path, jfloat x, jfloat y,
                    Paint* paint, uint16_t* glyphs, SkPoint* pos)
                : layout(layout), path(path), x(x), y(y), paint(paint), glyphs(glyphs), pos(pos) {
        }

        void operator()(size_t start, size_t end) {
            for (size_t i = start; i < end; i++) {
                glyphs[i] = layout.getGlyphId(i);
                pos[i].fX = x + layout.getX(i);
                pos[i].fY = y + layout.getY(i);
            }
            const SkFont& font = paint->getSkFont();
            if (start == 0) {
                getPosTextPath(font, glyphs, end, pos, path);
            } else {
                getPosTextPath(font, glyphs + start, end - start, pos + start, &tmpPath);
                path->addPath(tmpPath);
            }
        }
    private:
        const minikin::Layout& layout;
        SkPath* path;
        jfloat x;
        jfloat y;
        Paint* paint;
        uint16_t* glyphs;
        SkPoint* pos;
        SkPath tmpPath;
    };

    static void getTextPath(JNIEnv* env, Paint* paint, const Typeface* typeface, const jchar* text,
            jint count, jint bidiFlags, jfloat x, jfloat y, SkPath* path) {
        minikin::Layout layout = MinikinUtils::doLayout(
                paint, static_cast<minikin::Bidi>(bidiFlags), typeface,
                text, count,  // text buffer
                0, count,  // draw range
                0, count,  // context range
                nullptr);
        size_t nGlyphs = layout.nGlyphs();
        uint16_t* glyphs = new uint16_t[nGlyphs];
        SkPoint* pos = new SkPoint[nGlyphs];

        x += MinikinUtils::xOffsetForTextAlign(paint, layout);
        Paint::Align align = paint->getTextAlign();
        paint->setTextAlign(Paint::kLeft_Align);
        GetTextFunctor f(layout, path, x, y, paint, glyphs, pos);
        MinikinUtils::forFontRun(layout, paint, f);
        paint->setTextAlign(align);
        delete[] glyphs;
        delete[] pos;
    }

    static void getTextPath___C(JNIEnv* env, jobject clazz, jlong paintHandle, jint bidiFlags,
            jcharArray text, jint index, jint count, jfloat x, jfloat y, jlong pathHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        const jchar* textArray = env->GetCharArrayElements(text, nullptr);
        getTextPath(env, paint, typeface, textArray + index, count, bidiFlags, x, y, path);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray), JNI_ABORT);
    }

    static void getTextPath__String(JNIEnv* env, jobject clazz, jlong paintHandle, jint bidiFlags,
            jstring text, jint start, jint end, jfloat x, jfloat y, jlong pathHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        const jchar* textArray = env->GetStringChars(text, nullptr);
        getTextPath(env, paint, typeface, textArray + start, end - start, bidiFlags, x, y, path);
        env->ReleaseStringChars(text, textArray);
    }

    static void doTextBounds(JNIEnv* env, const jchar* text, int count, jobject bounds,
            const Paint& paint, const Typeface* typeface, jint bidiFlags) {
        SkRect  r;
        SkIRect ir;

        minikin::Layout layout = MinikinUtils::doLayout(&paint,
                static_cast<minikin::Bidi>(bidiFlags), typeface,
                text, count,  // text buffer
                0, count,  // draw range
                0, count,  // context range
                nullptr);
        minikin::MinikinRect rect;
        layout.getBounds(&rect);
        r.fLeft = rect.mLeft;
        r.fTop = rect.mTop;
        r.fRight = rect.mRight;
        r.fBottom = rect.mBottom;
        r.roundOut(&ir);
        GraphicsJNI::irect_to_jrect(ir, env, bounds);
    }

    static void getStringBounds(JNIEnv* env, jobject, jlong paintHandle, jstring text, jint start,
            jint end, jint bidiFlags, jobject bounds) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        const jchar* textArray = env->GetStringChars(text, nullptr);
        doTextBounds(env, textArray + start, end - start, bounds, *paint, typeface, bidiFlags);
        env->ReleaseStringChars(text, textArray);
    }

    static void getCharArrayBounds(JNIEnv* env, jobject, jlong paintHandle, jcharArray text,
            jint index, jint count, jint bidiFlags, jobject bounds) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        const jchar* textArray = env->GetCharArrayElements(text, nullptr);
        doTextBounds(env, textArray + index, count, bounds, *paint, typeface, bidiFlags);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);
    }

    // Returns true if the given string is exact one pair of regional indicators.
    static bool isFlag(const jchar* str, size_t length) {
        const jchar RI_LEAD_SURROGATE = 0xD83C;
        const jchar RI_TRAIL_SURROGATE_MIN = 0xDDE6;
        const jchar RI_TRAIL_SURROGATE_MAX = 0xDDFF;

        if (length != 4) {
            return false;
        }
        if (str[0] != RI_LEAD_SURROGATE || str[2] != RI_LEAD_SURROGATE) {
            return false;
        }
        return RI_TRAIL_SURROGATE_MIN <= str[1] && str[1] <= RI_TRAIL_SURROGATE_MAX &&
            RI_TRAIL_SURROGATE_MIN <= str[3] && str[3] <= RI_TRAIL_SURROGATE_MAX;
    }

    static jboolean layoutContainsNotdef(const minikin::Layout& layout) {
        for (size_t i = 0; i < layout.nGlyphs(); i++) {
            if (layout.getGlyphId(i) == 0) {
                return true;
            }
        }
        return false;
    }

    // Don't count glyphs that are the recommended "space" glyph and are zero width.
    // This logic makes assumptions about HarfBuzz layout, but does correctly handle
    // cases where ligatures form and zero width space glyphs are left in as
    // placeholders.
    static size_t countNonSpaceGlyphs(const minikin::Layout& layout) {
        size_t count = 0;
        static unsigned int kSpaceGlyphId = 3;
        for (size_t i = 0; i < layout.nGlyphs(); i++) {
            if (layout.getGlyphId(i) != kSpaceGlyphId || layout.getCharAdvance(i) != 0.0) {
                count++;
            }
        }
        return count;
    }

    static jboolean hasGlyph(JNIEnv *env, jclass, jlong paintHandle, jint bidiFlags,
            jstring string) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        ScopedStringChars str(env, string);

        /* Start by rejecting unsupported base code point and variation selector pairs. */
        size_t nChars = 0;
        const uint32_t kStartOfString = 0xFFFFFFFF;
        uint32_t prevCp = kStartOfString;
        for (size_t i = 0; i < str.size(); i++) {
            jchar cu = str[i];
            uint32_t cp = cu;
            if (U16_IS_TRAIL(cu)) {
                // invalid UTF-16, unpaired trailing surrogate
                return false;
            } else if (U16_IS_LEAD(cu)) {
                if (i + 1 == str.size()) {
                    // invalid UTF-16, unpaired leading surrogate at end of string
                    return false;
                }
                i++;
                jchar cu2 = str[i];
                if (!U16_IS_TRAIL(cu2)) {
                    // invalid UTF-16, unpaired leading surrogate
                    return false;
                }
                cp = U16_GET_SUPPLEMENTARY(cu, cu2);
            }

            if (prevCp != kStartOfString &&
                ((0xFE00 <= cp && cp <= 0xFE0F) || (0xE0100 <= cp && cp <= 0xE01EF))) {
                bool hasVS = MinikinUtils::hasVariationSelector(typeface, prevCp, cp);
                if (!hasVS) {
                    // No font has a glyph for the code point and variation selector pair.
                    return false;
                } else if (nChars == 1 && i + 1 == str.size()) {
                    // The string is just a codepoint and a VS, we have an authoritative answer
                    return true;
                }
            }
            nChars++;
            prevCp = cp;
        }
        minikin::Layout layout = MinikinUtils::doLayout(paint,
                static_cast<minikin::Bidi>(bidiFlags), typeface,
                str.get(), str.size(),  // text buffer
                0, str.size(),  // draw range
                0, str.size(),  // context range
                nullptr);
        size_t nGlyphs = countNonSpaceGlyphs(layout);
        if (nGlyphs != 1 && nChars > 1) {
            // multiple-character input, and was not a ligature
            // TODO: handle ZWJ/ZWNJ characters specially so we can detect certain ligatures
            // in joining scripts, such as Arabic and Mongolian.
            return false;
        }

        if (nGlyphs == 0 || layoutContainsNotdef(layout)) {
            return false;  // The collection doesn't have a glyph.
        }

        if (nChars == 2 && isFlag(str.get(), str.size())) {
            // Some font may have a special glyph for unsupported regional indicator pairs.
            // To return false for this case, need to compare the glyph id with the one of ZZ
            // since ZZ is reserved for unknown or invalid territory.
            // U+1F1FF (REGIONAL INDICATOR SYMBOL LETTER Z) is \uD83C\uDDFF in UTF16.
            static const jchar ZZ_FLAG_STR[] = { 0xD83C, 0xDDFF, 0xD83C, 0xDDFF };
            minikin::Layout zzLayout = MinikinUtils::doLayout(paint,
                    static_cast<minikin::Bidi>(bidiFlags), typeface,
                    ZZ_FLAG_STR, 4,  // text buffer
                    0, 4,  // draw range
                    0, 4,  // context range
                    nullptr);
            if (zzLayout.nGlyphs() != 1 || layoutContainsNotdef(zzLayout)) {
                // The font collection doesn't have a glyph for unknown flag. Just return true.
                return true;
            }
            return zzLayout.getGlyphId(0) != layout.getGlyphId(0);
        }
        return true;
    }

    static jfloat doRunAdvance(const Paint* paint, const Typeface* typeface, const jchar buf[],
            jint start, jint count, jint bufSize, jboolean isRtl, jint offset) {
        minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;
        if (offset == start + count) {
            return MinikinUtils::measureText(paint, bidiFlags, typeface, buf, start, count,
                    bufSize, nullptr);
        }
        std::unique_ptr<float[]> advancesArray(new float[count]);
        MinikinUtils::measureText(paint, bidiFlags, typeface, buf, start, count, bufSize,
                advancesArray.get());
        return minikin::getRunAdvance(advancesArray.get(), buf, start, count, offset);
    }

    static jfloat getRunAdvance___CIIIIZI_F(JNIEnv *env, jclass, jlong paintHandle, jcharArray text,
            jint start, jint end, jint contextStart, jint contextEnd, jboolean isRtl, jint offset) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        ScopedCharArrayRO textArray(env, text);
        jfloat result = doRunAdvance(paint, typeface, textArray.get() + contextStart,
                start - contextStart, end - start, contextEnd - contextStart, isRtl,
                offset - contextStart);
        return result;
    }

    static jint doOffsetForAdvance(const Paint* paint, const Typeface* typeface, const jchar buf[],
            jint start, jint count, jint bufSize, jboolean isRtl, jfloat advance) {
        minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;
        std::unique_ptr<float[]> advancesArray(new float[count]);
        MinikinUtils::measureText(paint, bidiFlags, typeface, buf, start, count, bufSize,
                advancesArray.get());
        return minikin::getOffsetForAdvance(advancesArray.get(), buf, start, count, advance);
    }

    static jint getOffsetForAdvance___CIIIIZF_I(JNIEnv *env, jclass, jlong paintHandle,
            jcharArray text, jint start, jint end, jint contextStart, jint contextEnd,
            jboolean isRtl, jfloat advance) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const Typeface* typeface = paint->getAndroidTypeface();
        ScopedCharArrayRO textArray(env, text);
        jint result = doOffsetForAdvance(paint, typeface, textArray.get() + contextStart,
                start - contextStart, end - start, contextEnd - contextStart, isRtl, advance);
        result += contextStart;
        return result;
    }

    // ------------------ @FastNative ---------------------------

    static jint setTextLocales(JNIEnv* env, jobject clazz, jlong objHandle, jstring locales) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        ScopedUtfChars localesChars(env, locales);
        jint minikinLocaleListId = minikin::registerLocaleList(localesChars.c_str());
        obj->setMinikinLocaleListId(minikinLocaleListId);
        return minikinLocaleListId;
    }

    static void setFontFeatureSettings(JNIEnv* env, jobject clazz, jlong paintHandle, jstring settings) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        if (!settings) {
            paint->setFontFeatureSettings(std::string());
        } else {
            ScopedUtfChars settingsChars(env, settings);
            paint->setFontFeatureSettings(std::string(settingsChars.c_str(), settingsChars.size()));
        }
    }

    static SkScalar getMetricsInternal(jlong paintHandle, SkFontMetrics *metrics) {
        const int kElegantTop = 2500;
        const int kElegantBottom = -1000;
        const int kElegantAscent = 1900;
        const int kElegantDescent = -500;
        const int kElegantLeading = 0;
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        SkFont* font = &paint->getSkFont();
        const Typeface* typeface = paint->getAndroidTypeface();
        typeface = Typeface::resolveDefault(typeface);
        minikin::FakedFont baseFont = typeface->fFontCollection->baseFontFaked(typeface->fStyle);
        float saveSkewX = font->getSkewX();
        bool savefakeBold = font->isEmbolden();
        MinikinFontSkia::populateSkFont(font, baseFont.font->typeface().get(), baseFont.fakery);
        SkScalar spacing = font->getMetrics(metrics);
        // The populateSkPaint call may have changed fake bold / text skew
        // because we want to measure with those effects applied, so now
        // restore the original settings.
        font->setSkewX(saveSkewX);
        font->setEmbolden(savefakeBold);
        if (paint->getFamilyVariant() == minikin::FamilyVariant::ELEGANT) {
            SkScalar size = font->getSize();
            metrics->fTop = -size * kElegantTop / 2048;
            metrics->fBottom = -size * kElegantBottom / 2048;
            metrics->fAscent = -size * kElegantAscent / 2048;
            metrics->fDescent = -size * kElegantDescent / 2048;
            metrics->fLeading = size * kElegantLeading / 2048;
            spacing = metrics->fDescent - metrics->fAscent + metrics->fLeading;
        }
        return spacing;
    }

    static jfloat getFontMetrics(JNIEnv* env, jobject, jlong paintHandle, jobject metricsObj) {
        SkFontMetrics metrics;
        SkScalar spacing = getMetricsInternal(paintHandle, &metrics);

        if (metricsObj) {
            SkASSERT(env->IsInstanceOf(metricsObj, gFontMetrics_class));
            env->SetFloatField(metricsObj, gFontMetrics_fieldID.top, SkScalarToFloat(metrics.fTop));
            env->SetFloatField(metricsObj, gFontMetrics_fieldID.ascent, SkScalarToFloat(metrics.fAscent));
            env->SetFloatField(metricsObj, gFontMetrics_fieldID.descent, SkScalarToFloat(metrics.fDescent));
            env->SetFloatField(metricsObj, gFontMetrics_fieldID.bottom, SkScalarToFloat(metrics.fBottom));
            env->SetFloatField(metricsObj, gFontMetrics_fieldID.leading, SkScalarToFloat(metrics.fLeading));
        }
        return SkScalarToFloat(spacing);
    }

    static jint getFontMetricsInt(JNIEnv* env, jobject, jlong paintHandle, jobject metricsObj) {
        SkFontMetrics metrics;

        getMetricsInternal(paintHandle, &metrics);
        int ascent = SkScalarRoundToInt(metrics.fAscent);
        int descent = SkScalarRoundToInt(metrics.fDescent);
        int leading = SkScalarRoundToInt(metrics.fLeading);

        if (metricsObj) {
            SkASSERT(env->IsInstanceOf(metricsObj, gFontMetricsInt_class));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.top, SkScalarFloorToInt(metrics.fTop));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.ascent, ascent);
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.descent, descent);
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.bottom, SkScalarCeilToInt(metrics.fBottom));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.leading, leading);
        }
        return descent - ascent + leading;
    }


    // ------------------ @CriticalNative ---------------------------

    static void reset(jlong objHandle) {
        reinterpret_cast<Paint*>(objHandle)->reset();
    }

    static void assign(jlong dstPaintHandle, jlong srcPaintHandle) {
        Paint* dst = reinterpret_cast<Paint*>(dstPaintHandle);
        const Paint* src = reinterpret_cast<Paint*>(srcPaintHandle);
        *dst = *src;
    }

    static jint getFlags(jlong paintHandle) {
        uint32_t flags = reinterpret_cast<Paint*>(paintHandle)->getJavaFlags();
        return static_cast<jint>(flags);
    }

    static void setFlags(jlong paintHandle, jint flags) {
        reinterpret_cast<Paint*>(paintHandle)->setJavaFlags(flags);
    }

    static jint getHinting(jlong paintHandle) {
        return (SkFontHinting)reinterpret_cast<Paint*>(paintHandle)->getSkFont().getHinting()
                == kNo_SkFontHinting ? 0 : 1;
    }

    static void setHinting(jlong paintHandle, jint mode) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setHinting(
                mode == 0 ? kNo_SkFontHinting : kNormal_SkFontHinting);
    }

    static void setAntiAlias(jlong paintHandle, jboolean aa) {
        reinterpret_cast<Paint*>(paintHandle)->setAntiAlias(aa);
    }

    static void setLinearText(jlong paintHandle, jboolean linearText) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setLinearMetrics(linearText);
    }

    static void setSubpixelText(jlong paintHandle, jboolean subpixelText) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setSubpixel(subpixelText);
    }

    static void setUnderlineText(jlong paintHandle, jboolean underlineText) {
        reinterpret_cast<Paint*>(paintHandle)->setUnderline(underlineText);
    }

    static void setStrikeThruText(jlong paintHandle, jboolean strikeThruText) {
        reinterpret_cast<Paint*>(paintHandle)->setStrikeThru(strikeThruText);
    }

    static void setFakeBoldText(jlong paintHandle, jboolean fakeBoldText) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setEmbolden(fakeBoldText);
    }

    static void setFilterBitmap(jlong paintHandle, jboolean filterBitmap) {
        reinterpret_cast<Paint*>(paintHandle)->setFilterQuality(
                filterBitmap ? kLow_SkFilterQuality : kNone_SkFilterQuality);
    }

    static void setDither(jlong paintHandle, jboolean dither) {
        reinterpret_cast<Paint*>(paintHandle)->setDither(dither);
    }

    static jint getStyle(jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStyle());
    }

    static void setStyle(jlong objHandle, jint styleHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Style style = static_cast<Paint::Style>(styleHandle);
        obj->setStyle(style);
    }

    static void setColorLong(jlong paintHandle, jlong colorSpaceHandle,
            jfloat r, jfloat g, jfloat b, jfloat a) {
        sk_sp<SkColorSpace> cs = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
        SkColor4f color = SkColor4f{r, g, b, a};
        reinterpret_cast<Paint*>(paintHandle)->setColor4f(color, cs.get());
    }

    static void setColor(jlong paintHandle, jint color) {
        reinterpret_cast<Paint*>(paintHandle)->setColor(color);
    }

    static void setAlpha(jlong paintHandle, jint a) {
        reinterpret_cast<Paint*>(paintHandle)->setAlpha(a);
    }

    static jfloat getStrokeWidth(jlong paintHandle) {
        return SkScalarToFloat(reinterpret_cast<Paint*>(paintHandle)->getStrokeWidth());
    }

    static void setStrokeWidth(jlong paintHandle, jfloat width) {
        reinterpret_cast<Paint*>(paintHandle)->setStrokeWidth(width);
    }

    static jfloat getStrokeMiter(jlong paintHandle) {
        return SkScalarToFloat(reinterpret_cast<Paint*>(paintHandle)->getStrokeMiter());
    }

    static void setStrokeMiter(jlong paintHandle, jfloat miter) {
        reinterpret_cast<Paint*>(paintHandle)->setStrokeMiter(miter);
    }

    static jint getStrokeCap(jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStrokeCap());
    }

    static void setStrokeCap(jlong objHandle, jint capHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Cap cap = static_cast<Paint::Cap>(capHandle);
        obj->setStrokeCap(cap);
    }

    static jint getStrokeJoin(jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStrokeJoin());
    }

    static void setStrokeJoin(jlong objHandle, jint joinHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Join join = (Paint::Join) joinHandle;
        obj->setStrokeJoin(join);
    }

    static jboolean getFillPath(jlong objHandle, jlong srcHandle, jlong dstHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        return obj->getFillPath(*src, dst) ? JNI_TRUE : JNI_FALSE;
    }

    static jlong setShader(jlong objHandle, jlong shaderHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
        obj->setShader(sk_ref_sp(shader));
        return reinterpret_cast<jlong>(obj->getShader());
    }

    static jlong setColorFilter(jlong objHandle, jlong filterHandle) {
        Paint* obj = reinterpret_cast<Paint *>(objHandle);
        SkColorFilter* filter  = reinterpret_cast<SkColorFilter *>(filterHandle);
        obj->setColorFilter(sk_ref_sp(filter));
        return reinterpret_cast<jlong>(obj->getColorFilter());
    }

    static void setXfermode(jlong paintHandle, jint xfermodeHandle) {
        // validate that the Java enum values match our expectations
        static_assert(0 == static_cast<int>(SkBlendMode::kClear), "xfermode_mismatch");
        static_assert(1 == static_cast<int>(SkBlendMode::kSrc), "xfermode_mismatch");
        static_assert(2 == static_cast<int>(SkBlendMode::kDst), "xfermode_mismatch");
        static_assert(3 == static_cast<int>(SkBlendMode::kSrcOver), "xfermode_mismatch");
        static_assert(4 == static_cast<int>(SkBlendMode::kDstOver), "xfermode_mismatch");
        static_assert(5 == static_cast<int>(SkBlendMode::kSrcIn), "xfermode_mismatch");
        static_assert(6 == static_cast<int>(SkBlendMode::kDstIn), "xfermode_mismatch");
        static_assert(7 == static_cast<int>(SkBlendMode::kSrcOut), "xfermode_mismatch");
        static_assert(8 == static_cast<int>(SkBlendMode::kDstOut), "xfermode_mismatch");
        static_assert(9 == static_cast<int>(SkBlendMode::kSrcATop), "xfermode_mismatch");
        static_assert(10 == static_cast<int>(SkBlendMode::kDstATop), "xfermode_mismatch");
        static_assert(11 == static_cast<int>(SkBlendMode::kXor), "xfermode_mismatch");
        static_assert(12 == static_cast<int>(SkBlendMode::kPlus), "xfermode_mismatch");
        static_assert(13 == static_cast<int>(SkBlendMode::kModulate), "xfermode_mismatch");
        static_assert(14 == static_cast<int>(SkBlendMode::kScreen), "xfermode_mismatch");
        static_assert(15 == static_cast<int>(SkBlendMode::kOverlay), "xfermode_mismatch");
        static_assert(16 == static_cast<int>(SkBlendMode::kDarken), "xfermode_mismatch");
        static_assert(17 == static_cast<int>(SkBlendMode::kLighten), "xfermode_mismatch");
        static_assert(18 == static_cast<int>(SkBlendMode::kColorDodge), "xfermode mismatch");
        static_assert(19 == static_cast<int>(SkBlendMode::kColorBurn), "xfermode mismatch");
        static_assert(20 == static_cast<int>(SkBlendMode::kHardLight), "xfermode mismatch");
        static_assert(21 == static_cast<int>(SkBlendMode::kSoftLight), "xfermode mismatch");
        static_assert(22 == static_cast<int>(SkBlendMode::kDifference), "xfermode mismatch");
        static_assert(23 == static_cast<int>(SkBlendMode::kExclusion), "xfermode mismatch");
        static_assert(24 == static_cast<int>(SkBlendMode::kMultiply), "xfermode mismatch");
        static_assert(25 == static_cast<int>(SkBlendMode::kHue), "xfermode mismatch");
        static_assert(26 == static_cast<int>(SkBlendMode::kSaturation), "xfermode mismatch");
        static_assert(27 == static_cast<int>(SkBlendMode::kColor), "xfermode mismatch");
        static_assert(28 == static_cast<int>(SkBlendMode::kLuminosity), "xfermode mismatch");

        SkBlendMode mode = static_cast<SkBlendMode>(xfermodeHandle);
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setBlendMode(mode);
    }

    static jlong setPathEffect(jlong objHandle, jlong effectHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkPathEffect* effect  = reinterpret_cast<SkPathEffect*>(effectHandle);
        obj->setPathEffect(sk_ref_sp(effect));
        return reinterpret_cast<jlong>(obj->getPathEffect());
    }

    static jlong setMaskFilter(jlong objHandle, jlong maskfilterHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkMaskFilter* maskfilter  = reinterpret_cast<SkMaskFilter*>(maskfilterHandle);
        obj->setMaskFilter(sk_ref_sp(maskfilter));
        return reinterpret_cast<jlong>(obj->getMaskFilter());
    }

    static void setTypeface(jlong objHandle, jlong typefaceHandle) {
        Paint* paint = reinterpret_cast<Paint*>(objHandle);
        paint->setAndroidTypeface(reinterpret_cast<Typeface*>(typefaceHandle));
    }

    static jint getTextAlign(jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getTextAlign());
    }

    static void setTextAlign(jlong objHandle, jint alignHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Align align = static_cast<Paint::Align>(alignHandle);
        obj->setTextAlign(align);
    }

    static void setTextLocalesByMinikinLocaleListId(jlong objHandle,
            jint minikinLocaleListId) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        obj->setMinikinLocaleListId(minikinLocaleListId);
    }

    static jboolean isElegantTextHeight(jlong paintHandle) {
        Paint* obj = reinterpret_cast<Paint*>(paintHandle);
        return obj->getFamilyVariant() == minikin::FamilyVariant::ELEGANT;
    }

    static void setElegantTextHeight(jlong paintHandle, jboolean aa) {
        Paint* obj = reinterpret_cast<Paint*>(paintHandle);
        obj->setFamilyVariant(
                aa ? minikin::FamilyVariant::ELEGANT : minikin::FamilyVariant::DEFAULT);
    }

    static jfloat getTextSize(jlong paintHandle) {
        return SkScalarToFloat(reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSize());
    }

    static void setTextSize(jlong paintHandle, jfloat textSize) {
        if (textSize >= 0) {
            reinterpret_cast<Paint*>(paintHandle)->getSkFont().setSize(textSize);
        }
    }

    static jfloat getTextScaleX(jlong paintHandle) {
        return SkScalarToFloat(reinterpret_cast<Paint*>(paintHandle)->getSkFont().getScaleX());
    }

    static void setTextScaleX(jlong paintHandle, jfloat scaleX) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setScaleX(scaleX);
    }

    static jfloat getTextSkewX(jlong paintHandle) {
        return SkScalarToFloat(reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSkewX());
    }

    static void setTextSkewX(jlong paintHandle, jfloat skewX) {
        reinterpret_cast<Paint*>(paintHandle)->getSkFont().setSkewX(skewX);
    }

    static jfloat getLetterSpacing(jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return paint->getLetterSpacing();
    }

    static void setLetterSpacing(jlong paintHandle, jfloat letterSpacing) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setLetterSpacing(letterSpacing);
    }

    static jfloat getWordSpacing(jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return paint->getWordSpacing();
    }

    static void setWordSpacing(jlong paintHandle, jfloat wordSpacing) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setWordSpacing(wordSpacing);
    }

    static jint getStartHyphenEdit(jlong paintHandle, jint hyphen) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return static_cast<jint>(paint->getStartHyphenEdit());
    }

    static jint getEndHyphenEdit(jlong paintHandle, jint hyphen) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return static_cast<jint>(paint->getEndHyphenEdit());
    }

    static void setStartHyphenEdit(jlong paintHandle, jint hyphen) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setStartHyphenEdit((uint32_t)hyphen);
    }

    static void setEndHyphenEdit(jlong paintHandle, jint hyphen) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setEndHyphenEdit((uint32_t)hyphen);
    }

    static jfloat ascent(jlong paintHandle) {
        SkFontMetrics metrics;
        getMetricsInternal(paintHandle, &metrics);
        return SkScalarToFloat(metrics.fAscent);
    }

    static jfloat descent(jlong paintHandle) {
        SkFontMetrics metrics;
        getMetricsInternal(paintHandle, &metrics);
        return SkScalarToFloat(metrics.fDescent);
    }

    static jfloat getUnderlinePosition(jlong paintHandle) {
        SkFontMetrics metrics;
        getMetricsInternal(paintHandle, &metrics);
        SkScalar position;
        if (metrics.hasUnderlinePosition(&position)) {
            return SkScalarToFloat(position);
        } else {
            const SkScalar textSize = reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSize();
            return SkScalarToFloat(Paint::kStdUnderline_Top * textSize);
        }
    }

    static jfloat getUnderlineThickness(jlong paintHandle) {
        SkFontMetrics metrics;
        getMetricsInternal(paintHandle, &metrics);
        SkScalar thickness;
        if (metrics.hasUnderlineThickness(&thickness)) {
            return SkScalarToFloat(thickness);
        } else {
            const SkScalar textSize = reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSize();
            return SkScalarToFloat(Paint::kStdUnderline_Thickness * textSize);
        }
    }

    static jfloat getStrikeThruPosition(jlong paintHandle) {
        const SkScalar textSize = reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSize();
        return SkScalarToFloat(Paint::kStdStrikeThru_Top * textSize);
    }

    static jfloat getStrikeThruThickness(jlong paintHandle) {
        const SkScalar textSize = reinterpret_cast<Paint*>(paintHandle)->getSkFont().getSize();
        return SkScalarToFloat(Paint::kStdStrikeThru_Thickness * textSize);
    }

    static void setShadowLayer(jlong paintHandle, jfloat radius,
                               jfloat dx, jfloat dy, jlong colorSpaceHandle,
                               jfloat r, jfloat g, jfloat b, jfloat a) {
        sk_sp<SkColorSpace> cs = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
        SkColor4f color = SkColor4f{r, g, b, a};

        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        if (radius <= 0) {
            paint->setLooper(nullptr);
        }
        else {
            SkScalar sigma = android::uirenderer::Blur::convertRadiusToSigma(radius);
            paint->setLooper(SkBlurDrawLooper::Make(color, cs.get(), sigma, dx, dy));
        }
    }

    static jboolean hasShadowLayer(jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return paint->getLooper() && paint->getLooper()->asABlurShadow(nullptr);
    }

    static jboolean equalsForTextMeasurement(jlong lPaint, jlong rPaint) {
        if (lPaint == rPaint) {
            return true;
        }
        Paint* leftPaint = reinterpret_cast<Paint*>(lPaint);
        Paint* rightPaint = reinterpret_cast<Paint*>(rPaint);

        const Typeface* leftTypeface = Typeface::resolveDefault(leftPaint->getAndroidTypeface());
        const Typeface* rightTypeface = Typeface::resolveDefault(rightPaint->getAndroidTypeface());
        minikin::MinikinPaint leftMinikinPaint
                = MinikinUtils::prepareMinikinPaint(leftPaint, leftTypeface);
        minikin::MinikinPaint rightMinikinPaint
                = MinikinUtils::prepareMinikinPaint(rightPaint, rightTypeface);

        return leftMinikinPaint == rightMinikinPaint;
    }

}; // namespace PaintGlue

static const JNINativeMethod methods[] = {
    {"nGetNativeFinalizer", "()J", (void*) PaintGlue::getNativeFinalizer},
    {"nInit","()J", (void*) PaintGlue::init},
    {"nInitWithPaint","(J)J", (void*) PaintGlue::initWithPaint},
    {"nBreakText","(J[CIIFI[F)I", (void*) PaintGlue::breakTextC},
    {"nBreakText","(JLjava/lang/String;ZFI[F)I", (void*) PaintGlue::breakTextS},
    {"nGetTextAdvances","(J[CIIIII[FI)F",
            (void*) PaintGlue::getTextAdvances___CIIIII_FI},
    {"nGetTextAdvances","(JLjava/lang/String;IIIII[FI)F",
            (void*) PaintGlue::getTextAdvances__StringIIIII_FI},

    {"nGetTextRunCursor", "(J[CIIIII)I", (void*) PaintGlue::getTextRunCursor___C},
    {"nGetTextRunCursor", "(JLjava/lang/String;IIIII)I",
            (void*) PaintGlue::getTextRunCursor__String},
    {"nGetTextPath", "(JI[CIIFFJ)V", (void*) PaintGlue::getTextPath___C},
    {"nGetTextPath", "(JILjava/lang/String;IIFFJ)V", (void*) PaintGlue::getTextPath__String},
    {"nGetStringBounds", "(JLjava/lang/String;IIILandroid/graphics/Rect;)V",
            (void*) PaintGlue::getStringBounds },
    {"nGetCharArrayBounds", "(J[CIIILandroid/graphics/Rect;)V",
            (void*) PaintGlue::getCharArrayBounds },
    {"nHasGlyph", "(JILjava/lang/String;)Z", (void*) PaintGlue::hasGlyph },
    {"nGetRunAdvance", "(J[CIIIIZI)F", (void*) PaintGlue::getRunAdvance___CIIIIZI_F},
    {"nGetOffsetForAdvance", "(J[CIIIIZF)I",
            (void*) PaintGlue::getOffsetForAdvance___CIIIIZF_I},

    // --------------- @FastNative ----------------------

    {"nSetTextLocales","(JLjava/lang/String;)I", (void*) PaintGlue::setTextLocales},
    {"nSetFontFeatureSettings","(JLjava/lang/String;)V",
                (void*) PaintGlue::setFontFeatureSettings},
    {"nGetFontMetrics", "(JLandroid/graphics/Paint$FontMetrics;)F",
                (void*)PaintGlue::getFontMetrics},
    {"nGetFontMetricsInt", "(JLandroid/graphics/Paint$FontMetricsInt;)I",
            (void*)PaintGlue::getFontMetricsInt},

    // --------------- @CriticalNative ------------------

    {"nReset","(J)V", (void*) PaintGlue::reset},
    {"nSet","(JJ)V", (void*) PaintGlue::assign},
    {"nGetFlags","(J)I", (void*) PaintGlue::getFlags},
    {"nSetFlags","(JI)V", (void*) PaintGlue::setFlags},
    {"nGetHinting","(J)I", (void*) PaintGlue::getHinting},
    {"nSetHinting","(JI)V", (void*) PaintGlue::setHinting},
    {"nSetAntiAlias","(JZ)V", (void*) PaintGlue::setAntiAlias},
    {"nSetSubpixelText","(JZ)V", (void*) PaintGlue::setSubpixelText},
    {"nSetLinearText","(JZ)V", (void*) PaintGlue::setLinearText},
    {"nSetUnderlineText","(JZ)V", (void*) PaintGlue::setUnderlineText},
    {"nSetStrikeThruText","(JZ)V", (void*) PaintGlue::setStrikeThruText},
    {"nSetFakeBoldText","(JZ)V", (void*) PaintGlue::setFakeBoldText},
    {"nSetFilterBitmap","(JZ)V", (void*) PaintGlue::setFilterBitmap},
    {"nSetDither","(JZ)V", (void*) PaintGlue::setDither},
    {"nGetStyle","(J)I", (void*) PaintGlue::getStyle},
    {"nSetStyle","(JI)V", (void*) PaintGlue::setStyle},
    {"nSetColor","(JI)V", (void*) PaintGlue::setColor},
    {"nSetColor","(JJFFFF)V", (void*) PaintGlue::setColorLong},
    {"nSetAlpha","(JI)V", (void*) PaintGlue::setAlpha},
    {"nGetStrokeWidth","(J)F", (void*) PaintGlue::getStrokeWidth},
    {"nSetStrokeWidth","(JF)V", (void*) PaintGlue::setStrokeWidth},
    {"nGetStrokeMiter","(J)F", (void*) PaintGlue::getStrokeMiter},
    {"nSetStrokeMiter","(JF)V", (void*) PaintGlue::setStrokeMiter},
    {"nGetStrokeCap","(J)I", (void*) PaintGlue::getStrokeCap},
    {"nSetStrokeCap","(JI)V", (void*) PaintGlue::setStrokeCap},
    {"nGetStrokeJoin","(J)I", (void*) PaintGlue::getStrokeJoin},
    {"nSetStrokeJoin","(JI)V", (void*) PaintGlue::setStrokeJoin},
    {"nGetFillPath","(JJJ)Z", (void*) PaintGlue::getFillPath},
    {"nSetShader","(JJ)J", (void*) PaintGlue::setShader},
    {"nSetColorFilter","(JJ)J", (void*) PaintGlue::setColorFilter},
    {"nSetXfermode","(JI)V", (void*) PaintGlue::setXfermode},
    {"nSetPathEffect","(JJ)J", (void*) PaintGlue::setPathEffect},
    {"nSetMaskFilter","(JJ)J", (void*) PaintGlue::setMaskFilter},
    {"nSetTypeface","(JJ)V", (void*) PaintGlue::setTypeface},
    {"nGetTextAlign","(J)I", (void*) PaintGlue::getTextAlign},
    {"nSetTextAlign","(JI)V", (void*) PaintGlue::setTextAlign},
    {"nSetTextLocalesByMinikinLocaleListId","(JI)V",
            (void*) PaintGlue::setTextLocalesByMinikinLocaleListId},
    {"nIsElegantTextHeight","(J)Z", (void*) PaintGlue::isElegantTextHeight},
    {"nSetElegantTextHeight","(JZ)V", (void*) PaintGlue::setElegantTextHeight},
    {"nGetTextSize","(J)F", (void*) PaintGlue::getTextSize},
    {"nSetTextSize","(JF)V", (void*) PaintGlue::setTextSize},
    {"nGetTextScaleX","(J)F", (void*) PaintGlue::getTextScaleX},
    {"nSetTextScaleX","(JF)V", (void*) PaintGlue::setTextScaleX},
    {"nGetTextSkewX","(J)F", (void*) PaintGlue::getTextSkewX},
    {"nSetTextSkewX","(JF)V", (void*) PaintGlue::setTextSkewX},
    {"nGetLetterSpacing","(J)F", (void*) PaintGlue::getLetterSpacing},
    {"nSetLetterSpacing","(JF)V", (void*) PaintGlue::setLetterSpacing},
    {"nGetWordSpacing","(J)F", (void*) PaintGlue::getWordSpacing},
    {"nSetWordSpacing","(JF)V", (void*) PaintGlue::setWordSpacing},
    {"nGetStartHyphenEdit", "(J)I", (void*) PaintGlue::getStartHyphenEdit},
    {"nGetEndHyphenEdit", "(J)I", (void*) PaintGlue::getEndHyphenEdit},
    {"nSetStartHyphenEdit", "(JI)V", (void*) PaintGlue::setStartHyphenEdit},
    {"nSetEndHyphenEdit", "(JI)V", (void*) PaintGlue::setEndHyphenEdit},
    {"nAscent","(J)F", (void*) PaintGlue::ascent},
    {"nDescent","(J)F", (void*) PaintGlue::descent},
    {"nGetUnderlinePosition","(J)F", (void*) PaintGlue::getUnderlinePosition},
    {"nGetUnderlineThickness","(J)F", (void*) PaintGlue::getUnderlineThickness},
    {"nGetStrikeThruPosition","(J)F", (void*) PaintGlue::getStrikeThruPosition},
    {"nGetStrikeThruThickness","(J)F", (void*) PaintGlue::getStrikeThruThickness},
    {"nSetShadowLayer", "(JFFFJFFFF)V", (void*)PaintGlue::setShadowLayer},
    {"nHasShadowLayer", "(J)Z", (void*)PaintGlue::hasShadowLayer},
    {"nEqualsForTextMeasurement", "(JJ)Z", (void*)PaintGlue::equalsForTextMeasurement},
};

int register_android_graphics_Paint(JNIEnv* env) {
    gFontMetrics_class = FindClassOrDie(env, "android/graphics/Paint$FontMetrics");
    gFontMetrics_class = MakeGlobalRefOrDie(env, gFontMetrics_class);

    gFontMetrics_fieldID.top = GetFieldIDOrDie(env, gFontMetrics_class, "top", "F");
    gFontMetrics_fieldID.ascent = GetFieldIDOrDie(env, gFontMetrics_class, "ascent", "F");
    gFontMetrics_fieldID.descent = GetFieldIDOrDie(env, gFontMetrics_class, "descent", "F");
    gFontMetrics_fieldID.bottom = GetFieldIDOrDie(env, gFontMetrics_class, "bottom", "F");
    gFontMetrics_fieldID.leading = GetFieldIDOrDie(env, gFontMetrics_class, "leading", "F");

    gFontMetricsInt_class = FindClassOrDie(env, "android/graphics/Paint$FontMetricsInt");
    gFontMetricsInt_class = MakeGlobalRefOrDie(env, gFontMetricsInt_class);

    gFontMetricsInt_fieldID.top = GetFieldIDOrDie(env, gFontMetricsInt_class, "top", "I");
    gFontMetricsInt_fieldID.ascent = GetFieldIDOrDie(env, gFontMetricsInt_class, "ascent", "I");
    gFontMetricsInt_fieldID.descent = GetFieldIDOrDie(env, gFontMetricsInt_class, "descent", "I");
    gFontMetricsInt_fieldID.bottom = GetFieldIDOrDie(env, gFontMetricsInt_class, "bottom", "I");
    gFontMetricsInt_fieldID.leading = GetFieldIDOrDie(env, gFontMetricsInt_class, "leading", "I");

    return RegisterMethodsOrDie(env, "android/graphics/Paint", methods, NELEM(methods));
}

}
