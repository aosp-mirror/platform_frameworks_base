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
#include <android_runtime/AndroidRuntime.h>
#include <ScopedUtfChars.h>

#include "SkBlurDrawLooper.h"
#include "SkColorFilter.h"
#include "SkMaskFilter.h"
#include "SkRasterizer.h"
#include "SkShader.h"
#include "SkTypeface.h"
#include "SkXfermode.h"
#include "unicode/uloc.h"
#include "unicode/ushape.h"
#include "utils/Blur.h"

#include <minikin/GraphemeBreak.h>
#include "MinikinSkia.h"
#include "MinikinUtils.h"
#include "Paint.h"
#include "TypefaceImpl.h"

// temporary for debugging
#include <Caches.h>
#include <utils/Log.h>

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

static void defaultSettingsForAndroid(Paint* paint) {
    // GlyphID encoding is required because we are using Harfbuzz shaping
    paint->setTextEncoding(Paint::kGlyphID_TextEncoding);
}

class PaintGlue {
public:
    enum MoveOpt {
        AFTER, AT_OR_AFTER, BEFORE, AT_OR_BEFORE, AT
    };

    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        delete obj;
    }

    static jlong init(JNIEnv* env, jobject clazz) {
        Paint* obj = new Paint();
        defaultSettingsForAndroid(obj);
        return reinterpret_cast<jlong>(obj);
    }

    static jlong initWithPaint(JNIEnv* env, jobject clazz, jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        Paint* obj = new Paint(*paint);
        return reinterpret_cast<jlong>(obj);
    }

    static void reset(JNIEnv* env, jobject clazz, jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        obj->reset();
        defaultSettingsForAndroid(obj);
    }

    static void assign(JNIEnv* env, jobject clazz, jlong dstPaintHandle, jlong srcPaintHandle) {
        Paint* dst = reinterpret_cast<Paint*>(dstPaintHandle);
        const Paint* src = reinterpret_cast<Paint*>(srcPaintHandle);
        *dst = *src;
    }

    // Equivalent to the Java Paint's FILTER_BITMAP_FLAG.
    static const uint32_t sFilterBitmapFlag = 0x02;

    static jint getFlags(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint* nativePaint = GraphicsJNI::getNativePaint(env, paint);
        uint32_t result = nativePaint->getFlags();
        result &= ~sFilterBitmapFlag; // Filtering no longer stored in this bit. Mask away.
        if (nativePaint->getFilterLevel() != Paint::kNone_FilterLevel) {
            result |= sFilterBitmapFlag;
        }
        return static_cast<jint>(result);
    }

    static void setFlags(JNIEnv* env, jobject paint, jint flags) {
        NPE_CHECK_RETURN_VOID(env, paint);
        Paint* nativePaint = GraphicsJNI::getNativePaint(env, paint);
        // Instead of modifying 0x02, change the filter level.
        nativePaint->setFilterLevel(flags & sFilterBitmapFlag
                ? Paint::kLow_FilterLevel
                : Paint::kNone_FilterLevel);
        // Don't pass through filter flag, which is no longer stored in paint's flags.
        flags &= ~sFilterBitmapFlag;
        // Use the existing value for 0x02.
        const uint32_t existing0x02Flag = nativePaint->getFlags() & sFilterBitmapFlag;
        flags |= existing0x02Flag;
        nativePaint->setFlags(flags);
    }

    static jint getHinting(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return GraphicsJNI::getNativePaint(env, paint)->getHinting()
                == Paint::kNo_Hinting ? 0 : 1;
    }

    static void setHinting(JNIEnv* env, jobject paint, jint mode) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setHinting(
                mode == 0 ? Paint::kNo_Hinting : Paint::kNormal_Hinting);
    }

    static void setAntiAlias(JNIEnv* env, jobject paint, jboolean aa) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setAntiAlias(aa);
    }

    static void setLinearText(JNIEnv* env, jobject paint, jboolean linearText) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setLinearText(linearText);
    }

    static void setSubpixelText(JNIEnv* env, jobject paint, jboolean subpixelText) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setSubpixelText(subpixelText);
    }

    static void setUnderlineText(JNIEnv* env, jobject paint, jboolean underlineText) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setUnderlineText(underlineText);
    }

    static void setStrikeThruText(JNIEnv* env, jobject paint, jboolean strikeThruText) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setStrikeThruText(strikeThruText);
    }

    static void setFakeBoldText(JNIEnv* env, jobject paint, jboolean fakeBoldText) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setFakeBoldText(fakeBoldText);
    }

    static void setFilterBitmap(JNIEnv* env, jobject paint, jboolean filterBitmap) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setFilterLevel(
                filterBitmap ? Paint::kLow_FilterLevel : Paint::kNone_FilterLevel);
    }

    static void setDither(JNIEnv* env, jobject paint, jboolean dither) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setDither(dither);
    }

    static jint getStyle(JNIEnv* env, jobject clazz,jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStyle());
    }

    static void setStyle(JNIEnv* env, jobject clazz, jlong objHandle, jint styleHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Style style = static_cast<Paint::Style>(styleHandle);
        obj->setStyle(style);
    }

    static jint getColor(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        int color;
        color = GraphicsJNI::getNativePaint(env, paint)->getColor();
        return static_cast<jint>(color);
    }

    static jint getAlpha(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        int alpha;
        alpha = GraphicsJNI::getNativePaint(env, paint)->getAlpha();
        return static_cast<jint>(alpha);
    }

    static void setColor(JNIEnv* env, jobject paint, jint color) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setColor(color);
    }

    static void setAlpha(JNIEnv* env, jobject paint, jint a) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setAlpha(a);
    }

    static jfloat getStrokeWidth(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getStrokeWidth());
    }

    static void setStrokeWidth(JNIEnv* env, jobject paint, jfloat width) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setStrokeWidth(width);
    }

    static jfloat getStrokeMiter(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getStrokeMiter());
    }

    static void setStrokeMiter(JNIEnv* env, jobject paint, jfloat miter) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setStrokeMiter(miter);
    }

    static jint getStrokeCap(JNIEnv* env, jobject clazz, jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStrokeCap());
    }

    static void setStrokeCap(JNIEnv* env, jobject clazz, jlong objHandle, jint capHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Cap cap = static_cast<Paint::Cap>(capHandle);
        obj->setStrokeCap(cap);
    }

    static jint getStrokeJoin(JNIEnv* env, jobject clazz, jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getStrokeJoin());
    }

    static void setStrokeJoin(JNIEnv* env, jobject clazz, jlong objHandle, jint joinHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Join join = (Paint::Join) joinHandle;
        obj->setStrokeJoin(join);
    }

    static jboolean getFillPath(JNIEnv* env, jobject clazz, jlong objHandle, jlong srcHandle, jlong dstHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        return obj->getFillPath(*src, dst) ? JNI_TRUE : JNI_FALSE;
    }

    static jlong setShader(JNIEnv* env, jobject clazz, jlong objHandle, jlong shaderHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
        return reinterpret_cast<jlong>(obj->setShader(shader));
    }

    static jlong setColorFilter(JNIEnv* env, jobject clazz, jlong objHandle, jlong filterHandle) {
        Paint* obj = reinterpret_cast<Paint *>(objHandle);
        SkColorFilter* filter  = reinterpret_cast<SkColorFilter *>(filterHandle);
        return reinterpret_cast<jlong>(obj->setColorFilter(filter));
    }

    static jlong setXfermode(JNIEnv* env, jobject clazz, jlong objHandle, jlong xfermodeHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkXfermode* xfermode = reinterpret_cast<SkXfermode*>(xfermodeHandle);
        return reinterpret_cast<jlong>(obj->setXfermode(xfermode));
    }

    static jlong setPathEffect(JNIEnv* env, jobject clazz, jlong objHandle, jlong effectHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkPathEffect* effect  = reinterpret_cast<SkPathEffect*>(effectHandle);
        return reinterpret_cast<jlong>(obj->setPathEffect(effect));
    }

    static jlong setMaskFilter(JNIEnv* env, jobject clazz, jlong objHandle, jlong maskfilterHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkMaskFilter* maskfilter  = reinterpret_cast<SkMaskFilter*>(maskfilterHandle);
        return reinterpret_cast<jlong>(obj->setMaskFilter(maskfilter));
    }

    static jlong setTypeface(JNIEnv* env, jobject clazz, jlong objHandle, jlong typefaceHandle) {
        // TODO: in Paint refactoring, set typeface on android Paint, not Paint
        return NULL;
    }

    static jlong setRasterizer(JNIEnv* env, jobject clazz, jlong objHandle, jlong rasterizerHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        SkAutoTUnref<SkRasterizer> rasterizer(GraphicsJNI::refNativeRasterizer(rasterizerHandle));
        return reinterpret_cast<jlong>(obj->setRasterizer(rasterizer));
    }

    static jint getTextAlign(JNIEnv* env, jobject clazz, jlong objHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        return static_cast<jint>(obj->getTextAlign());
    }

    static void setTextAlign(JNIEnv* env, jobject clazz, jlong objHandle, jint alignHandle) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        Paint::Align align = static_cast<Paint::Align>(alignHandle);
        obj->setTextAlign(align);
    }

    // generate bcp47 identifier for the supplied locale
    static void toLanguageTag(char* output, size_t outSize,
            const char* locale) {
        if (output == NULL || outSize <= 0) {
            return;
        }
        if (locale == NULL) {
            output[0] = '\0';
            return;
        }
        char canonicalChars[ULOC_FULLNAME_CAPACITY];
        UErrorCode uErr = U_ZERO_ERROR;
        uloc_canonicalize(locale, canonicalChars, ULOC_FULLNAME_CAPACITY,
                &uErr);
        if (U_SUCCESS(uErr)) {
            char likelyChars[ULOC_FULLNAME_CAPACITY];
            uErr = U_ZERO_ERROR;
            uloc_addLikelySubtags(canonicalChars, likelyChars,
                    ULOC_FULLNAME_CAPACITY, &uErr);
            if (U_SUCCESS(uErr)) {
                uErr = U_ZERO_ERROR;
                uloc_toLanguageTag(likelyChars, output, outSize, FALSE, &uErr);
                if (U_SUCCESS(uErr)) {
                    return;
                } else {
                    ALOGD("uloc_toLanguageTag(\"%s\") failed: %s", likelyChars,
                            u_errorName(uErr));
                }
            } else {
                ALOGD("uloc_addLikelySubtags(\"%s\") failed: %s",
                        canonicalChars, u_errorName(uErr));
            }
        } else {
            ALOGD("uloc_canonicalize(\"%s\") failed: %s", locale,
                    u_errorName(uErr));
        }
        // unable to build a proper language identifier
        output[0] = '\0';
    }

    static void setTextLocale(JNIEnv* env, jobject clazz, jlong objHandle, jstring locale) {
        Paint* obj = reinterpret_cast<Paint*>(objHandle);
        ScopedUtfChars localeChars(env, locale);
        char langTag[ULOC_FULLNAME_CAPACITY];
        toLanguageTag(langTag, ULOC_FULLNAME_CAPACITY, localeChars.c_str());

        obj->setTextLocale(langTag);
    }

    static jboolean isElegantTextHeight(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint* obj = GraphicsJNI::getNativePaint(env, paint);
        return obj->getFontVariant() == VARIANT_ELEGANT;
    }

    static void setElegantTextHeight(JNIEnv* env, jobject paint, jboolean aa) {
        NPE_CHECK_RETURN_VOID(env, paint);
        Paint* obj = GraphicsJNI::getNativePaint(env, paint);
        obj->setFontVariant(aa ? VARIANT_ELEGANT : VARIANT_DEFAULT);
    }

    static jfloat getTextSize(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextSize());
    }

    static void setTextSize(JNIEnv* env, jobject paint, jfloat textSize) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextSize(textSize);
    }

    static jfloat getTextScaleX(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextScaleX());
    }

    static void setTextScaleX(JNIEnv* env, jobject paint, jfloat scaleX) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextScaleX(scaleX);
    }

    static jfloat getTextSkewX(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextSkewX());
    }

    static void setTextSkewX(JNIEnv* env, jobject paint, jfloat skewX) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextSkewX(skewX);
    }

    static jfloat getLetterSpacing(JNIEnv* env, jobject clazz, jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return paint->getLetterSpacing();
    }

    static void setLetterSpacing(JNIEnv* env, jobject clazz, jlong paintHandle, jfloat letterSpacing) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        paint->setLetterSpacing(letterSpacing);
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

    static SkScalar getMetricsInternal(JNIEnv* env, jobject jpaint, Paint::FontMetrics *metrics) {
        const int kElegantTop = 2500;
        const int kElegantBottom = -1000;
        const int kElegantAscent = 1900;
        const int kElegantDescent = -500;
        const int kElegantLeading = 0;
        Paint* paint = GraphicsJNI::getNativePaint(env, jpaint);
        TypefaceImpl* typeface = GraphicsJNI::getNativeTypeface(env, jpaint);
        typeface = TypefaceImpl_resolveDefault(typeface);
        FakedFont baseFont = typeface->fFontCollection->baseFontFaked(typeface->fStyle);
        float saveSkewX = paint->getTextSkewX();
        bool savefakeBold = paint->isFakeBoldText();
        MinikinFontSkia::populateSkPaint(paint, baseFont.font, baseFont.fakery);
        SkScalar spacing = paint->getFontMetrics(metrics);
        // The populateSkPaint call may have changed fake bold / text skew
        // because we want to measure with those effects applied, so now
        // restore the original settings.
        paint->setTextSkewX(saveSkewX);
        paint->setFakeBoldText(savefakeBold);
        if (paint->getFontVariant() == VARIANT_ELEGANT) {
            SkScalar size = paint->getTextSize();
            metrics->fTop = -size * kElegantTop / 2048;
            metrics->fBottom = -size * kElegantBottom / 2048;
            metrics->fAscent = -size * kElegantAscent / 2048;
            metrics->fDescent = -size * kElegantDescent / 2048;
            metrics->fLeading = size * kElegantLeading / 2048;
            spacing = metrics->fDescent - metrics->fAscent + metrics->fLeading;
        }
        return spacing;
    }

    static jfloat ascent(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint::FontMetrics metrics;
        getMetricsInternal(env, paint, &metrics);
        return SkScalarToFloat(metrics.fAscent);
    }

    static jfloat descent(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint::FontMetrics metrics;
        getMetricsInternal(env, paint, &metrics);
        return SkScalarToFloat(metrics.fDescent);
    }

    static jfloat getFontMetrics(JNIEnv* env, jobject paint, jobject metricsObj) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint::FontMetrics metrics;
        SkScalar spacing = getMetricsInternal(env, paint, &metrics);

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

    static jint getFontMetricsInt(JNIEnv* env, jobject paint, jobject metricsObj) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        Paint::FontMetrics metrics;

        getMetricsInternal(env, paint, &metrics);
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

    static jfloat measureText_CIII(JNIEnv* env, jobject jpaint, jcharArray text, jint index, jint count,
            jint bidiFlags) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);

        size_t textLength = env->GetArrayLength(text);
        if ((index | count) < 0 || (size_t)(index + count) > textLength) {
            doThrowAIOOBE(env);
            return 0;
        }
        if (count == 0) {
            return 0;
        }

        Paint* paint = GraphicsJNI::getNativePaint(env, jpaint);
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        jfloat result = 0;

        Layout layout;
        TypefaceImpl* typeface = GraphicsJNI::getNativeTypeface(env, jpaint);
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, textArray, index, count, textLength);
        result = layout.getAdvance();
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray), JNI_ABORT);
        return result;
    }

    static jfloat measureText_StringIII(JNIEnv* env, jobject jpaint, jstring text, jint start, jint end,
            jint bidiFlags) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);

        size_t textLength = env->GetStringLength(text);
        int count = end - start;
        if ((start | count) < 0 || (size_t)end > textLength) {
            doThrowAIOOBE(env);
            return 0;
        }
        if (count == 0) {
            return 0;
        }

        const jchar* textArray = env->GetStringChars(text, NULL);
        Paint* paint = GraphicsJNI::getNativePaint(env, jpaint);
        jfloat width = 0;

        Layout layout;
        TypefaceImpl* typeface = GraphicsJNI::getNativeTypeface(env, jpaint);
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, textArray, start, count, textLength);
        width = layout.getAdvance();

        env->ReleaseStringChars(text, textArray);
        return width;
    }

    static jfloat measureText_StringI(JNIEnv* env, jobject jpaint, jstring text, jint bidiFlags) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);

        size_t textLength = env->GetStringLength(text);
        if (textLength == 0) {
            return 0;
        }

        const jchar* textArray = env->GetStringChars(text, NULL);
        Paint* paint = GraphicsJNI::getNativePaint(env, jpaint);
        jfloat width = 0;

        Layout layout;
        TypefaceImpl* typeface = GraphicsJNI::getNativeTypeface(env, jpaint);
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, textArray, 0, textLength, textLength);
        width = layout.getAdvance();

        env->ReleaseStringChars(text, textArray);
        return width;
    }

    static int dotextwidths(JNIEnv* env, Paint* paint, TypefaceImpl* typeface, const jchar text[], int count,
            jfloatArray widths, jint bidiFlags) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        NPE_CHECK_RETURN_ZERO(env, text);

        if (count < 0 || !widths) {
            doThrowAIOOBE(env);
            return 0;
        }
        if (count == 0) {
            return 0;
        }
        size_t widthsLength = env->GetArrayLength(widths);
        if ((size_t)count > widthsLength) {
            doThrowAIOOBE(env);
            return 0;
        }

        AutoJavaFloatArray autoWidths(env, widths, count);
        jfloat* widthsArray = autoWidths.ptr();

        Layout layout;
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, 0, count, count);
        layout.getAdvances(widthsArray);

        return count;
    }

    static jint getTextWidths___CIII_F(JNIEnv* env, jobject clazz, jlong paintHandle, jlong typefaceHandle, jcharArray text,
            jint index, jint count, jint bidiFlags, jfloatArray widths) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        count = dotextwidths(env, paint, typeface, textArray + index, count, widths, bidiFlags);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);
        return count;
    }

    static jint getTextWidths__StringIII_F(JNIEnv* env, jobject clazz, jlong paintHandle, jlong typefaceHandle, jstring text,
            jint start, jint end, jint bidiFlags, jfloatArray widths) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        int count = dotextwidths(env, paint, typeface, textArray + start, end - start, widths, bidiFlags);
        env->ReleaseStringChars(text, textArray);
        return count;
    }

    static jfloat doTextRunAdvances(JNIEnv *env, Paint *paint, TypefaceImpl* typeface, const jchar *text,
                                    jint start, jint count, jint contextCount, jboolean isRtl,
                                    jfloatArray advances, jint advancesIndex) {
        NPE_CHECK_RETURN_ZERO(env, paint);
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
            if ((size_t)count > advancesLength) {
                doThrowAIOOBE(env);
                return 0;
            }
        }
        jfloat* advancesArray = new jfloat[count];
        jfloat totalAdvance = 0;

        int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;

        Layout layout;
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, start, count, contextCount);
        layout.getAdvances(advancesArray);
        totalAdvance = layout.getAdvance();

        if (advances != NULL) {
            env->SetFloatArrayRegion(advances, advancesIndex, count, advancesArray);
        }
        delete [] advancesArray;
        return totalAdvance;
    }

    static jfloat getTextRunAdvances___CIIIIZ_FI(JNIEnv* env, jobject clazz, jlong paintHandle,
            jlong typefaceHandle,
            jcharArray text, jint index, jint count, jint contextIndex, jint contextCount,
            jboolean isRtl, jfloatArray advances, jint advancesIndex) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        jchar* textArray = env->GetCharArrayElements(text, NULL);
        jfloat result = doTextRunAdvances(env, paint, typeface, textArray + contextIndex,
                index - contextIndex, count, contextCount, isRtl, advances, advancesIndex);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
        return result;
    }

    static jfloat getTextRunAdvances__StringIIIIZ_FI(JNIEnv* env, jobject clazz, jlong paintHandle,
            jlong typefaceHandle,
            jstring text, jint start, jint end, jint contextStart, jint contextEnd, jboolean isRtl,
            jfloatArray advances, jint advancesIndex) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        jfloat result = doTextRunAdvances(env, paint, typeface, textArray + contextStart,
                start - contextStart, end - start, contextEnd - contextStart, isRtl,
                advances, advancesIndex);
        env->ReleaseStringChars(text, textArray);
        return result;
    }

    static jint doTextRunCursor(JNIEnv *env, Paint* paint, const jchar *text, jint start,
            jint count, jint flags, jint offset, jint opt) {
        GraphemeBreak::MoveOpt moveOpt = GraphemeBreak::MoveOpt(opt);
        size_t result = GraphemeBreak::getTextRunCursor(text, start, count, offset, moveOpt);
        return static_cast<jint>(result);
    }

    static jint getTextRunCursor___C(JNIEnv* env, jobject clazz, jlong paintHandle, jcharArray text,
            jint contextStart, jint contextCount, jint dir, jint offset, jint cursorOpt) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        jchar* textArray = env->GetCharArrayElements(text, NULL);
        jint result = doTextRunCursor(env, paint, textArray, contextStart, contextCount, dir,
                offset, cursorOpt);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
        return result;
    }

    static jint getTextRunCursor__String(JNIEnv* env, jobject clazz, jlong paintHandle, jstring text,
            jint contextStart, jint contextEnd, jint dir, jint offset, jint cursorOpt) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        jint result = doTextRunCursor(env, paint, textArray, contextStart,
                contextEnd - contextStart, dir, offset, cursorOpt);
        env->ReleaseStringChars(text, textArray);
        return result;
    }

    class GetTextFunctor {
    public:
        GetTextFunctor(const Layout& layout, SkPath* path, jfloat x, jfloat y, Paint* paint,
                    uint16_t* glyphs, SkPoint* pos)
                : layout(layout), path(path), x(x), y(y), paint(paint), glyphs(glyphs), pos(pos) {
        }

        void operator()(size_t start, size_t end) {
            for (size_t i = start; i < end; i++) {
                glyphs[i] = layout.getGlyphId(i);
                pos[i].fX = x + layout.getX(i);
                pos[i].fY = y + layout.getY(i);
            }
            if (start == 0) {
                paint->getPosTextPath(glyphs + start, (end - start) << 1, pos + start, path);
            } else {
                paint->getPosTextPath(glyphs + start, (end - start) << 1, pos + start, &tmpPath);
                path->addPath(tmpPath);
            }
        }
    private:
        const Layout& layout;
        SkPath* path;
        jfloat x;
        jfloat y;
        Paint* paint;
        uint16_t* glyphs;
        SkPoint* pos;
        SkPath tmpPath;
    };

    static void getTextPath(JNIEnv* env, Paint* paint, TypefaceImpl* typeface, const jchar* text,
            jint count, jint bidiFlags, jfloat x, jfloat y, SkPath* path) {
        Layout layout;
        MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, 0, count, count);
        size_t nGlyphs = layout.nGlyphs();
        uint16_t* glyphs = new uint16_t[nGlyphs];
        SkPoint* pos = new SkPoint[nGlyphs];

        x += MinikinUtils::xOffsetForTextAlign(paint, layout);
        Paint::Align align = paint->getTextAlign();
        paint->setTextAlign(Paint::kLeft_Align);
        paint->setTextEncoding(Paint::kGlyphID_TextEncoding);
        GetTextFunctor f(layout, path, x, y, paint, glyphs, pos);
        MinikinUtils::forFontRun(layout, paint, f);
        paint->setTextAlign(align);
        delete[] glyphs;
        delete[] pos;
    }

    static void getTextPath___C(JNIEnv* env, jobject clazz, jlong paintHandle,
            jlong typefaceHandle, jint bidiFlags,
            jcharArray text, jint index, jint count, jfloat x, jfloat y, jlong pathHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        getTextPath(env, paint, typeface, textArray + index, count, bidiFlags, x, y, path);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray), JNI_ABORT);
    }

    static void getTextPath__String(JNIEnv* env, jobject clazz, jlong paintHandle,
            jlong typefaceHandle, jint bidiFlags,
            jstring text, jint start, jint end, jfloat x, jfloat y, jlong pathHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        getTextPath(env, paint, typeface, textArray + start, end - start, bidiFlags, x, y, path);
        env->ReleaseStringChars(text, textArray);
    }

    static void setShadowLayer(JNIEnv* env, jobject clazz, jlong paintHandle, jfloat radius,
                               jfloat dx, jfloat dy, jint color) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        if (radius <= 0) {
            paint->setLooper(NULL);
        }
        else {
            SkScalar sigma = android::uirenderer::Blur::convertRadiusToSigma(radius);
            paint->setLooper(SkBlurDrawLooper::Create((SkColor)color, sigma, dx, dy))->unref();
        }
    }

    static jboolean hasShadowLayer(JNIEnv* env, jobject clazz, jlong paintHandle) {
        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        return paint->getLooper() && paint->getLooper()->asABlurShadow(NULL);
    }

    static int breakText(JNIEnv* env, const Paint& paint, TypefaceImpl* typeface, const jchar text[],
                         int count, float maxWidth, jint bidiFlags, jfloatArray jmeasured,
                         Paint::TextBufferDirection textBufferDirection) {
        size_t measuredCount = 0;
        float measured = 0;

        Layout layout;
        MinikinUtils::doLayout(&layout, &paint, bidiFlags, typeface, text, 0, count, count);
        float* advances = new float[count];
        layout.getAdvances(advances);
        const bool forwardScan = (textBufferDirection == Paint::kForward_TextBufferDirection);
        for (int i = 0; i < count; i++) {
            // traverse in the given direction
            int index = forwardScan ? i : (count - i - 1);
            float width = advances[index];
            if (measured + width > maxWidth) {
                break;
            }
            // properly handle clusters when scanning backwards
            if (forwardScan || width != 0.0f) {
                measuredCount = i + 1;
            }
            measured += width;
        }
        delete[] advances;

        if (jmeasured && env->GetArrayLength(jmeasured) > 0) {
            AutoJavaFloatArray autoMeasured(env, jmeasured, 1);
            jfloat* array = autoMeasured.ptr();
            array[0] = measured;
        }
        return measuredCount;
    }

    static jint breakTextC(JNIEnv* env, jobject clazz, jlong paintHandle, jlong typefaceHandle, jcharArray jtext,
            jint index, jint count, jfloat maxWidth, jint bidiFlags, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jtext);

        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);

        Paint::TextBufferDirection tbd;
        if (count < 0) {
            tbd = Paint::kBackward_TextBufferDirection;
            count = -count;
        }
        else {
            tbd = Paint::kForward_TextBufferDirection;
        }

        if ((index < 0) || (index + count > env->GetArrayLength(jtext))) {
            doThrowAIOOBE(env);
            return 0;
        }

        const jchar* text = env->GetCharArrayElements(jtext, NULL);
        count = breakText(env, *paint, typeface, text + index, count, maxWidth,
                          bidiFlags, jmeasuredWidth, tbd);
        env->ReleaseCharArrayElements(jtext, const_cast<jchar*>(text),
                                      JNI_ABORT);
        return count;
    }

    static jint breakTextS(JNIEnv* env, jobject clazz, jlong paintHandle, jlong typefaceHandle, jstring jtext,
                jboolean forwards, jfloat maxWidth, jint bidiFlags, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jtext);

        Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);

        Paint::TextBufferDirection tbd = forwards ?
                                        Paint::kForward_TextBufferDirection :
                                        Paint::kBackward_TextBufferDirection;

        int count = env->GetStringLength(jtext);
        const jchar* text = env->GetStringChars(jtext, NULL);
        count = breakText(env, *paint, typeface, text, count, maxWidth, bidiFlags, jmeasuredWidth, tbd);
        env->ReleaseStringChars(jtext, text);
        return count;
    }

    static void doTextBounds(JNIEnv* env, const jchar* text, int count, jobject bounds,
            const Paint& paint, TypefaceImpl* typeface, jint bidiFlags) {
        SkRect  r;
        SkIRect ir;

        Layout layout;
        MinikinUtils::doLayout(&layout, &paint, bidiFlags, typeface, text, 0, count, count);
        MinikinRect rect;
        layout.getBounds(&rect);
        r.fLeft = rect.mLeft;
        r.fTop = rect.mTop;
        r.fRight = rect.mRight;
        r.fBottom = rect.mBottom;
        r.roundOut(&ir);
        GraphicsJNI::irect_to_jrect(ir, env, bounds);
    }

    static void getStringBounds(JNIEnv* env, jobject, jlong paintHandle, jlong typefaceHandle,
                                jstring text, jint start, jint end, jint bidiFlags, jobject bounds) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);;
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        doTextBounds(env, textArray + start, end - start, bounds, *paint, typeface, bidiFlags);
        env->ReleaseStringChars(text, textArray);
    }

    static void getCharArrayBounds(JNIEnv* env, jobject, jlong paintHandle, jlong typefaceHandle,
                        jcharArray text, jint index, jint count, jint bidiFlags, jobject bounds) {
        const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        doTextBounds(env, textArray + index, count, bounds, *paint, typeface, bidiFlags);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);
    }

};

static JNINativeMethod methods[] = {
    {"finalizer", "(J)V", (void*) PaintGlue::finalizer},
    {"native_init","()J", (void*) PaintGlue::init},
    {"native_initWithPaint","(J)J", (void*) PaintGlue::initWithPaint},

    {"native_reset","!(J)V", (void*) PaintGlue::reset},
    {"native_set","!(JJ)V", (void*) PaintGlue::assign},
    {"getFlags","!()I", (void*) PaintGlue::getFlags},
    {"setFlags","!(I)V", (void*) PaintGlue::setFlags},
    {"getHinting","!()I", (void*) PaintGlue::getHinting},
    {"setHinting","!(I)V", (void*) PaintGlue::setHinting},
    {"setAntiAlias","!(Z)V", (void*) PaintGlue::setAntiAlias},
    {"setSubpixelText","!(Z)V", (void*) PaintGlue::setSubpixelText},
    {"setLinearText","!(Z)V", (void*) PaintGlue::setLinearText},
    {"setUnderlineText","!(Z)V", (void*) PaintGlue::setUnderlineText},
    {"setStrikeThruText","!(Z)V", (void*) PaintGlue::setStrikeThruText},
    {"setFakeBoldText","!(Z)V", (void*) PaintGlue::setFakeBoldText},
    {"setFilterBitmap","!(Z)V", (void*) PaintGlue::setFilterBitmap},
    {"setDither","!(Z)V", (void*) PaintGlue::setDither},
    {"native_getStyle","!(J)I", (void*) PaintGlue::getStyle},
    {"native_setStyle","!(JI)V", (void*) PaintGlue::setStyle},
    {"getColor","!()I", (void*) PaintGlue::getColor},
    {"setColor","!(I)V", (void*) PaintGlue::setColor},
    {"getAlpha","!()I", (void*) PaintGlue::getAlpha},
    {"setAlpha","!(I)V", (void*) PaintGlue::setAlpha},
    {"getStrokeWidth","!()F", (void*) PaintGlue::getStrokeWidth},
    {"setStrokeWidth","!(F)V", (void*) PaintGlue::setStrokeWidth},
    {"getStrokeMiter","!()F", (void*) PaintGlue::getStrokeMiter},
    {"setStrokeMiter","!(F)V", (void*) PaintGlue::setStrokeMiter},
    {"native_getStrokeCap","!(J)I", (void*) PaintGlue::getStrokeCap},
    {"native_setStrokeCap","!(JI)V", (void*) PaintGlue::setStrokeCap},
    {"native_getStrokeJoin","!(J)I", (void*) PaintGlue::getStrokeJoin},
    {"native_setStrokeJoin","!(JI)V", (void*) PaintGlue::setStrokeJoin},
    {"native_getFillPath","!(JJJ)Z", (void*) PaintGlue::getFillPath},
    {"native_setShader","!(JJ)J", (void*) PaintGlue::setShader},
    {"native_setColorFilter","!(JJ)J", (void*) PaintGlue::setColorFilter},
    {"native_setXfermode","!(JJ)J", (void*) PaintGlue::setXfermode},
    {"native_setPathEffect","!(JJ)J", (void*) PaintGlue::setPathEffect},
    {"native_setMaskFilter","!(JJ)J", (void*) PaintGlue::setMaskFilter},
    {"native_setTypeface","!(JJ)J", (void*) PaintGlue::setTypeface},
    {"native_setRasterizer","!(JJ)J", (void*) PaintGlue::setRasterizer},
    {"native_getTextAlign","!(J)I", (void*) PaintGlue::getTextAlign},
    {"native_setTextAlign","!(JI)V", (void*) PaintGlue::setTextAlign},
    {"native_setTextLocale","!(JLjava/lang/String;)V", (void*) PaintGlue::setTextLocale},
    {"isElegantTextHeight","!()Z", (void*) PaintGlue::isElegantTextHeight},
    {"setElegantTextHeight","!(Z)V", (void*) PaintGlue::setElegantTextHeight},
    {"getTextSize","!()F", (void*) PaintGlue::getTextSize},
    {"setTextSize","!(F)V", (void*) PaintGlue::setTextSize},
    {"getTextScaleX","!()F", (void*) PaintGlue::getTextScaleX},
    {"setTextScaleX","!(F)V", (void*) PaintGlue::setTextScaleX},
    {"getTextSkewX","!()F", (void*) PaintGlue::getTextSkewX},
    {"setTextSkewX","!(F)V", (void*) PaintGlue::setTextSkewX},
    {"native_getLetterSpacing","!(J)F", (void*) PaintGlue::getLetterSpacing},
    {"native_setLetterSpacing","!(JF)V", (void*) PaintGlue::setLetterSpacing},
    {"native_setFontFeatureSettings","(JLjava/lang/String;)V", (void*) PaintGlue::setFontFeatureSettings},
    {"ascent","!()F", (void*) PaintGlue::ascent},
    {"descent","!()F", (void*) PaintGlue::descent},

    {"getFontMetrics", "(Landroid/graphics/Paint$FontMetrics;)F", (void*)PaintGlue::getFontMetrics},
    {"getFontMetricsInt", "(Landroid/graphics/Paint$FontMetricsInt;)I", (void*)PaintGlue::getFontMetricsInt},
    {"native_measureText","([CIII)F", (void*) PaintGlue::measureText_CIII},
    {"native_measureText","(Ljava/lang/String;I)F", (void*) PaintGlue::measureText_StringI},
    {"native_measureText","(Ljava/lang/String;III)F", (void*) PaintGlue::measureText_StringIII},
    {"native_breakText","(JJ[CIIFI[F)I", (void*) PaintGlue::breakTextC},
    {"native_breakText","(JJLjava/lang/String;ZFI[F)I", (void*) PaintGlue::breakTextS},
    {"native_getTextWidths","(JJ[CIII[F)I", (void*) PaintGlue::getTextWidths___CIII_F},
    {"native_getTextWidths","(JJLjava/lang/String;III[F)I", (void*) PaintGlue::getTextWidths__StringIII_F},
    {"native_getTextRunAdvances","(JJ[CIIIIZ[FI)F",
        (void*) PaintGlue::getTextRunAdvances___CIIIIZ_FI},
    {"native_getTextRunAdvances","(JJLjava/lang/String;IIIIZ[FI)F",
        (void*) PaintGlue::getTextRunAdvances__StringIIIIZ_FI},

    {"native_getTextRunCursor", "(J[CIIIII)I", (void*) PaintGlue::getTextRunCursor___C},
    {"native_getTextRunCursor", "(JLjava/lang/String;IIIII)I",
        (void*) PaintGlue::getTextRunCursor__String},
    {"native_getTextPath","(JJI[CIIFFJ)V", (void*) PaintGlue::getTextPath___C},
    {"native_getTextPath","(JJILjava/lang/String;IIFFJ)V", (void*) PaintGlue::getTextPath__String},
    {"nativeGetStringBounds", "(JJLjava/lang/String;IIILandroid/graphics/Rect;)V",
                                        (void*) PaintGlue::getStringBounds },
    {"nativeGetCharArrayBounds", "(JJ[CIIILandroid/graphics/Rect;)V",
                                    (void*) PaintGlue::getCharArrayBounds },

    {"native_setShadowLayer", "!(JFFFI)V", (void*)PaintGlue::setShadowLayer},
    {"native_hasShadowLayer", "!(J)Z", (void*)PaintGlue::hasShadowLayer}
};

static jfieldID req_fieldID(jfieldID id) {
    SkASSERT(id);
    return id;
}

int register_android_graphics_Paint(JNIEnv* env) {
    gFontMetrics_class = env->FindClass("android/graphics/Paint$FontMetrics");
    SkASSERT(gFontMetrics_class);
    gFontMetrics_class = (jclass)env->NewGlobalRef(gFontMetrics_class);

    gFontMetrics_fieldID.top = req_fieldID(env->GetFieldID(gFontMetrics_class, "top", "F"));
    gFontMetrics_fieldID.ascent = req_fieldID(env->GetFieldID(gFontMetrics_class, "ascent", "F"));
    gFontMetrics_fieldID.descent = req_fieldID(env->GetFieldID(gFontMetrics_class, "descent", "F"));
    gFontMetrics_fieldID.bottom = req_fieldID(env->GetFieldID(gFontMetrics_class, "bottom", "F"));
    gFontMetrics_fieldID.leading = req_fieldID(env->GetFieldID(gFontMetrics_class, "leading", "F"));

    gFontMetricsInt_class = env->FindClass("android/graphics/Paint$FontMetricsInt");
    SkASSERT(gFontMetricsInt_class);
    gFontMetricsInt_class = (jclass)env->NewGlobalRef(gFontMetricsInt_class);

    gFontMetricsInt_fieldID.top = req_fieldID(env->GetFieldID(gFontMetricsInt_class, "top", "I"));
    gFontMetricsInt_fieldID.ascent = req_fieldID(env->GetFieldID(gFontMetricsInt_class, "ascent", "I"));
    gFontMetricsInt_fieldID.descent = req_fieldID(env->GetFieldID(gFontMetricsInt_class, "descent", "I"));
    gFontMetricsInt_fieldID.bottom = req_fieldID(env->GetFieldID(gFontMetricsInt_class, "bottom", "I"));
    gFontMetricsInt_fieldID.leading = req_fieldID(env->GetFieldID(gFontMetricsInt_class, "leading", "I"));

    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/Paint", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
