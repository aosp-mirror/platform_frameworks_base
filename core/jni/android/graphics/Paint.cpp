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

// This file was generated from the C++ include file: SkPaint.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkBlurDrawLooper.h"
#include "SkColorFilter.h"
#include "SkMaskFilter.h"
#include "SkRasterizer.h"
#include "SkShader.h"
#include "SkTypeface.h"
#include "SkXfermode.h"

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

static void defaultSettingsForAndroid(SkPaint* paint) {
    // looks best we decided
    paint->setHinting(SkPaint::kSlight_Hinting);
    // utf16 is required for java
    paint->setTextEncoding(SkPaint::kUTF16_TextEncoding);
}

class SkPaintGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkPaint* obj) {
        delete obj;
    }

    static SkPaint* init(JNIEnv* env, jobject clazz) {
        SkPaint* obj = new SkPaint();
        defaultSettingsForAndroid(obj);
        return obj;
    }

    static SkPaint* intiWithPaint(JNIEnv* env, jobject clazz, SkPaint* paint) {
        SkPaint* obj = new SkPaint(*paint);
        return obj;
    }
 
    static void reset(JNIEnv* env, jobject clazz, SkPaint* obj) {
        obj->reset();
        defaultSettingsForAndroid(obj);
    }
 
    static void assign(JNIEnv* env, jobject clazz, SkPaint* dst, const SkPaint* src) {
        *dst = *src;
    }
 
    static jint getFlags(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return GraphicsJNI::getNativePaint(env, paint)->getFlags();
    }
 
    static void setFlags(JNIEnv* env, jobject paint, jint flags) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setFlags(flags);
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
        GraphicsJNI::getNativePaint(env, paint)->setFilterBitmap(filterBitmap);
    }
    
    static void setDither(JNIEnv* env, jobject paint, jboolean dither) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setDither(dither);
    }
    
    static jint getStyle(JNIEnv* env, jobject clazz, SkPaint* obj) {
        return obj->getStyle();
    }
 
    static void setStyle(JNIEnv* env, jobject clazz, SkPaint* obj, SkPaint::Style style) {
        obj->setStyle(style);
    }
 
    static jint getColor(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return GraphicsJNI::getNativePaint(env, paint)->getColor();
    }
 
    static jint getAlpha(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return GraphicsJNI::getNativePaint(env, paint)->getAlpha();
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
        GraphicsJNI::getNativePaint(env, paint)->setStrokeWidth(SkFloatToScalar(width));
    }
 
    static jfloat getStrokeMiter(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getStrokeMiter());
    }
 
    static void setStrokeMiter(JNIEnv* env, jobject paint, jfloat miter) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setStrokeMiter(SkFloatToScalar(miter));
    }
 
    static jint getStrokeCap(JNIEnv* env, jobject clazz, SkPaint* obj) {
        return obj->getStrokeCap();
    }
 
    static void setStrokeCap(JNIEnv* env, jobject clazz, SkPaint* obj, SkPaint::Cap cap) {
        obj->setStrokeCap(cap);
    }
 
    static jint getStrokeJoin(JNIEnv* env, jobject clazz, SkPaint* obj) {
        return obj->getStrokeJoin();
    }
 
    static void setStrokeJoin(JNIEnv* env, jobject clazz, SkPaint* obj, SkPaint::Join join) {
        obj->setStrokeJoin(join);
    }
 
    static jboolean getFillPath(JNIEnv* env, jobject clazz, SkPaint* obj, SkPath* src, SkPath* dst) {
        return obj->getFillPath(*src, dst);
    }
 
    static SkShader* setShader(JNIEnv* env, jobject clazz, SkPaint* obj, SkShader* shader) {
        return obj->setShader(shader);
    }
 
    static SkColorFilter* setColorFilter(JNIEnv* env, jobject clazz, SkPaint* obj, SkColorFilter* filter) {
        return obj->setColorFilter(filter);
    }
 
    static SkXfermode* setXfermode(JNIEnv* env, jobject clazz, SkPaint* obj, SkXfermode* xfermode) {
        return obj->setXfermode(xfermode);
    }
 
    static SkPathEffect* setPathEffect(JNIEnv* env, jobject clazz, SkPaint* obj, SkPathEffect* effect) {
        return obj->setPathEffect(effect);
    }
 
    static SkMaskFilter* setMaskFilter(JNIEnv* env, jobject clazz, SkPaint* obj, SkMaskFilter* maskfilter) {
        return obj->setMaskFilter(maskfilter);
    }
 
    static SkTypeface* setTypeface(JNIEnv* env, jobject clazz, SkPaint* obj, SkTypeface* typeface) {
        return obj->setTypeface(typeface);
    }
 
    static SkRasterizer* setRasterizer(JNIEnv* env, jobject clazz, SkPaint* obj, SkRasterizer* rasterizer) {
        return obj->setRasterizer(rasterizer);
    }
 
    static jint getTextAlign(JNIEnv* env, jobject clazz, SkPaint* obj) {
        return obj->getTextAlign();
    }
 
    static void setTextAlign(JNIEnv* env, jobject clazz, SkPaint* obj, SkPaint::Align align) {
        obj->setTextAlign(align);
    }
 
    static jfloat getTextSize(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextSize());
    }
 
    static void setTextSize(JNIEnv* env, jobject paint, jfloat textSize) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextSize(SkFloatToScalar(textSize));
    }
 
    static jfloat getTextScaleX(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextScaleX());
    }
 
    static void setTextScaleX(JNIEnv* env, jobject paint, jfloat scaleX) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextScaleX(SkFloatToScalar(scaleX));
    }
 
    static jfloat getTextSkewX(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        return SkScalarToFloat(GraphicsJNI::getNativePaint(env, paint)->getTextSkewX());
    }
 
    static void setTextSkewX(JNIEnv* env, jobject paint, jfloat skewX) {
        NPE_CHECK_RETURN_VOID(env, paint);
        GraphicsJNI::getNativePaint(env, paint)->setTextSkewX(SkFloatToScalar(skewX));
    }
 
    static jfloat ascent(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        SkPaint::FontMetrics    metrics;
        (void)GraphicsJNI::getNativePaint(env, paint)->getFontMetrics(&metrics);
        return SkScalarToFloat(metrics.fAscent);
    }
 
    static jfloat descent(JNIEnv* env, jobject paint) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        SkPaint::FontMetrics    metrics;
        (void)GraphicsJNI::getNativePaint(env, paint)->getFontMetrics(&metrics);
        return SkScalarToFloat(metrics.fDescent);
    }
 
    static jfloat getFontMetrics(JNIEnv* env, jobject paint, jobject metricsObj) {
        NPE_CHECK_RETURN_ZERO(env, paint);
        SkPaint::FontMetrics metrics;
        SkScalar             spacing = GraphicsJNI::getNativePaint(env, paint)->getFontMetrics(&metrics);

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
        SkPaint::FontMetrics metrics;
        
        GraphicsJNI::getNativePaint(env, paint)->getFontMetrics(&metrics);
        int ascent = SkScalarRound(metrics.fAscent);
        int descent = SkScalarRound(metrics.fDescent);
        int leading = SkScalarRound(metrics.fLeading);

        if (metricsObj) {
            SkASSERT(env->IsInstanceOf(metricsObj, gFontMetricsInt_class));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.top, SkScalarFloor(metrics.fTop));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.ascent, ascent);
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.descent, descent);
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.bottom, SkScalarCeil(metrics.fBottom));
            env->SetIntField(metricsObj, gFontMetricsInt_fieldID.leading, leading);
        }
        return descent - ascent + leading;
    }

    static jfloat measureText_CII(JNIEnv* env, jobject jpaint, jcharArray text, int index, int count) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);

        size_t textLength = env->GetArrayLength(text);
        
        if ((index | count) < 0 || (size_t)(index + count) > textLength) {
            doThrow(env, "java/lang/ArrayIndexOutOfBoundsException");
            return 0;
        }

        const SkPaint* paint = GraphicsJNI::getNativePaint(env, jpaint);        
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        // we double count, since measureText wants a byteLength
        SkScalar width = paint->measureText(textArray + index, count << 1);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);

        return SkScalarToFloat(width);
    }
 
    static jfloat measureText_StringII(JNIEnv* env, jobject jpaint, jstring text, int start, int end) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);
        
        SkPaint* paint = GraphicsJNI::getNativePaint(env, jpaint);        
        const jchar* textArray = env->GetStringChars(text, NULL);
        size_t textLength = env->GetStringLength(text);
        
        int count = end - start;
        if ((start | count) < 0 || (size_t)count > textLength) {
            doThrow(env, "java/lang/IndexOutOfBoundsException");
            return 0;
        }
        
        jfloat width = SkScalarToFloat(paint->measureText(textArray + start, count << 1));
        env->ReleaseStringChars(text, textArray);
        return width;
    }
    
    static jfloat measureText_String(JNIEnv* env, jobject jpaint, jstring text) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, text);
        
        SkPaint* paint = GraphicsJNI::getNativePaint(env, jpaint);        
        const jchar* textArray = env->GetStringChars(text, NULL);
        size_t textLength = env->GetStringLength(text);
        
        jfloat width = SkScalarToFloat(paint->measureText(textArray, textLength << 1));
        env->ReleaseStringChars(text, textArray);
        return width;
    }
    
    static int dotextwidths(JNIEnv* env, SkPaint* paint, const jchar text[], int count, jfloatArray widths) {
        AutoJavaFloatArray autoWidths(env, widths, count);
        jfloat* widthsArray = autoWidths.ptr();
        SkScalar* scalarArray = (SkScalar*)widthsArray;

        count = paint->getTextWidths(text, count << 1, scalarArray);        
        for (int i = 0; i < count; i++) {
            widthsArray[i] = SkScalarToFloat(scalarArray[i]);
        }
        return count;
    }
    
    static int getTextWidths___CII_F(JNIEnv* env, jobject clazz, SkPaint* paint, jcharArray text, int index, int count, jfloatArray widths) {
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        count = dotextwidths(env, paint, textArray + index, count, widths);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);        
        return count;
    }
 
    static int getTextWidths__StringII_F(JNIEnv* env, jobject clazz, SkPaint* paint, jstring text, int start, int end, jfloatArray widths) {
        const jchar* textArray = env->GetStringChars(text, NULL);
        int count = dotextwidths(env, paint, textArray + start, end - start, widths);
        env->ReleaseStringChars(text, textArray);
        return count;
    }
    
    static void getTextPath___CIIFFPath(JNIEnv* env, jobject clazz, SkPaint* paint, jcharArray text, int index, int count, jfloat x, jfloat y, SkPath* path) {
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        paint->getTextPath(textArray + index, count << 1, SkFloatToScalar(x), SkFloatToScalar(y), path);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);
    }
 
    static void getTextPath__StringIIFFPath(JNIEnv* env, jobject clazz, SkPaint* paint, jstring text, int start, int end, jfloat x, jfloat y, SkPath* path) {
        const jchar* textArray = env->GetStringChars(text, NULL);
        paint->getTextPath(textArray + start, (end - start) << 1, SkFloatToScalar(x), SkFloatToScalar(y), path);
        env->ReleaseStringChars(text, textArray);
    }
 
    static void setShadowLayer(JNIEnv* env, jobject jpaint, jfloat radius,
                               jfloat dx, jfloat dy, int color) {
        NPE_CHECK_RETURN_VOID(env, jpaint);
        
        SkPaint* paint = GraphicsJNI::getNativePaint(env, jpaint);        
        if (radius <= 0) {
            paint->setLooper(NULL);
        }
        else {
            paint->setLooper(new SkBlurDrawLooper(SkFloatToScalar(radius),
                                                  SkFloatToScalar(dx),
                                                  SkFloatToScalar(dy),
                                                  (SkColor)color))->unref();
        }
    }

    static int breakText(JNIEnv* env, const SkPaint& paint, const jchar text[],
                         int count, float maxWidth, jfloatArray jmeasured,
                         SkPaint::TextBufferDirection tbd) {
        SkASSERT(paint.getTextEncoding() == SkPaint::kUTF16_TextEncoding);

        SkScalar     measured;
        size_t       bytes = paint.breakText(text, count << 1,
                                   SkFloatToScalar(maxWidth), &measured, tbd);
        SkASSERT((bytes & 1) == 0);

        if (jmeasured && env->GetArrayLength(jmeasured) > 0) {
            AutoJavaFloatArray autoMeasured(env, jmeasured, 1);
            jfloat* array = autoMeasured.ptr();
            array[0] = SkScalarToFloat(measured);
        }
        return bytes >> 1;
    }

    static int breakTextC(JNIEnv* env, jobject jpaint, jcharArray jtext,
            int index, int count, float maxWidth, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, jtext);

        SkPaint::TextBufferDirection tbd;
        if (count < 0) {
            tbd = SkPaint::kBackward_TextBufferDirection;
            count = -count;
        }
        else {
            tbd = SkPaint::kForward_TextBufferDirection;
        }

        if ((index < 0) || (index + count > env->GetArrayLength(jtext))) {
            doThrow(env, "java/lang/ArrayIndexOutOfBoundsException");
            return 0;
        }

        SkPaint*     paint = GraphicsJNI::getNativePaint(env, jpaint);
        const jchar* text = env->GetCharArrayElements(jtext, NULL);
        count = breakText(env, *paint, text + index, count, maxWidth,
                          jmeasuredWidth, tbd);
        env->ReleaseCharArrayElements(jtext, const_cast<jchar*>(text),
                                      JNI_ABORT);
        return count;
    }

    static int breakTextS(JNIEnv* env, jobject jpaint, jstring jtext,
                bool forwards, float maxWidth, jfloatArray jmeasuredWidth) {
        NPE_CHECK_RETURN_ZERO(env, jpaint);
        NPE_CHECK_RETURN_ZERO(env, jtext);

        SkPaint::TextBufferDirection tbd = forwards ?
                                        SkPaint::kForward_TextBufferDirection :
                                        SkPaint::kBackward_TextBufferDirection;

        SkPaint* paint = GraphicsJNI::getNativePaint(env, jpaint);
        int count = env->GetStringLength(jtext);
        const jchar* text = env->GetStringChars(jtext, NULL);
        count = breakText(env, *paint, text, count, maxWidth,
                          jmeasuredWidth, tbd);
        env->ReleaseStringChars(jtext, text);
        return count;
    }

    static void doTextBounds(JNIEnv* env, const jchar* text, int count,
                             jobject bounds, const SkPaint& paint)
    {
        SkRect  r;
        SkIRect ir;
        
        paint.measureText(text, count << 1, &r);
        r.roundOut(&ir);
        GraphicsJNI::irect_to_jrect(ir, env, bounds);
    }

    static void getStringBounds(JNIEnv* env, jobject, const SkPaint* paint,
                                jstring text, int start, int end, jobject bounds)
    {
        const jchar* textArray = env->GetStringChars(text, NULL);
        doTextBounds(env, textArray + start, end - start, bounds, *paint);
        env->ReleaseStringChars(text, textArray);
    }
    
    static void getCharArrayBounds(JNIEnv* env, jobject, const SkPaint* paint,
                        jcharArray text, int index, int count, jobject bounds)
    {
        const jchar* textArray = env->GetCharArrayElements(text, NULL);
        doTextBounds(env, textArray + index, count, bounds, *paint);
        env->ReleaseCharArrayElements(text, const_cast<jchar*>(textArray),
                                      JNI_ABORT);
    }
    
};

static JNINativeMethod methods[] = {
    {"finalizer", "(I)V", (void*) SkPaintGlue::finalizer},
    {"native_init","()I", (void*) SkPaintGlue::init},
    {"native_initWithPaint","(I)I", (void*) SkPaintGlue::intiWithPaint},
    {"native_reset","(I)V", (void*) SkPaintGlue::reset},
    {"native_set","(II)V", (void*) SkPaintGlue::assign},
    {"getFlags","()I", (void*) SkPaintGlue::getFlags},
    {"setFlags","(I)V", (void*) SkPaintGlue::setFlags},
    {"setAntiAlias","(Z)V", (void*) SkPaintGlue::setAntiAlias},
    {"setSubpixelText","(Z)V", (void*) SkPaintGlue::setSubpixelText},
    {"setLinearText","(Z)V", (void*) SkPaintGlue::setLinearText},
    {"setUnderlineText","(Z)V", (void*) SkPaintGlue::setUnderlineText},
    {"setStrikeThruText","(Z)V", (void*) SkPaintGlue::setStrikeThruText},
    {"setFakeBoldText","(Z)V", (void*) SkPaintGlue::setFakeBoldText},
    {"setFilterBitmap","(Z)V", (void*) SkPaintGlue::setFilterBitmap},
    {"setDither","(Z)V", (void*) SkPaintGlue::setDither},
    {"native_getStyle","(I)I", (void*) SkPaintGlue::getStyle},
    {"native_setStyle","(II)V", (void*) SkPaintGlue::setStyle},
    {"getColor","()I", (void*) SkPaintGlue::getColor},
    {"setColor","(I)V", (void*) SkPaintGlue::setColor},
    {"getAlpha","()I", (void*) SkPaintGlue::getAlpha},
    {"setAlpha","(I)V", (void*) SkPaintGlue::setAlpha},
    {"getStrokeWidth","()F", (void*) SkPaintGlue::getStrokeWidth},
    {"setStrokeWidth","(F)V", (void*) SkPaintGlue::setStrokeWidth},
    {"getStrokeMiter","()F", (void*) SkPaintGlue::getStrokeMiter},
    {"setStrokeMiter","(F)V", (void*) SkPaintGlue::setStrokeMiter},
    {"native_getStrokeCap","(I)I", (void*) SkPaintGlue::getStrokeCap},
    {"native_setStrokeCap","(II)V", (void*) SkPaintGlue::setStrokeCap},
    {"native_getStrokeJoin","(I)I", (void*) SkPaintGlue::getStrokeJoin},
    {"native_setStrokeJoin","(II)V", (void*) SkPaintGlue::setStrokeJoin},
    {"native_getFillPath","(III)Z", (void*) SkPaintGlue::getFillPath},
    {"native_setShader","(II)I", (void*) SkPaintGlue::setShader},
    {"native_setColorFilter","(II)I", (void*) SkPaintGlue::setColorFilter},
    {"native_setXfermode","(II)I", (void*) SkPaintGlue::setXfermode},
    {"native_setPathEffect","(II)I", (void*) SkPaintGlue::setPathEffect},
    {"native_setMaskFilter","(II)I", (void*) SkPaintGlue::setMaskFilter},
    {"native_setTypeface","(II)I", (void*) SkPaintGlue::setTypeface},
    {"native_setRasterizer","(II)I", (void*) SkPaintGlue::setRasterizer},
    {"native_getTextAlign","(I)I", (void*) SkPaintGlue::getTextAlign},
    {"native_setTextAlign","(II)V", (void*) SkPaintGlue::setTextAlign},
    {"getTextSize","()F", (void*) SkPaintGlue::getTextSize},
    {"setTextSize","(F)V", (void*) SkPaintGlue::setTextSize},
    {"getTextScaleX","()F", (void*) SkPaintGlue::getTextScaleX},
    {"setTextScaleX","(F)V", (void*) SkPaintGlue::setTextScaleX},
    {"getTextSkewX","()F", (void*) SkPaintGlue::getTextSkewX},
    {"setTextSkewX","(F)V", (void*) SkPaintGlue::setTextSkewX},
    {"ascent","()F", (void*) SkPaintGlue::ascent},
    {"descent","()F", (void*) SkPaintGlue::descent},
    {"getFontMetrics", "(Landroid/graphics/Paint$FontMetrics;)F", (void*)SkPaintGlue::getFontMetrics},
    {"getFontMetricsInt", "(Landroid/graphics/Paint$FontMetricsInt;)I", (void*)SkPaintGlue::getFontMetricsInt},
    {"native_measureText","([CII)F", (void*) SkPaintGlue::measureText_CII},
    {"native_measureText","(Ljava/lang/String;)F", (void*) SkPaintGlue::measureText_String},
    {"native_measureText","(Ljava/lang/String;II)F", (void*) SkPaintGlue::measureText_StringII},
    {"native_breakText","([CIIF[F)I", (void*) SkPaintGlue::breakTextC},
    {"native_breakText","(Ljava/lang/String;ZF[F)I", (void*) SkPaintGlue::breakTextS},
    {"native_getTextWidths","(I[CII[F)I", (void*) SkPaintGlue::getTextWidths___CII_F},
    {"native_getTextWidths","(ILjava/lang/String;II[F)I", (void*) SkPaintGlue::getTextWidths__StringII_F},
    {"native_getTextPath","(I[CIIFFI)V", (void*) SkPaintGlue::getTextPath___CIIFFPath},
    {"native_getTextPath","(ILjava/lang/String;IIFFI)V", (void*) SkPaintGlue::getTextPath__StringIIFFPath},
    {"nativeGetStringBounds", "(ILjava/lang/String;IILandroid/graphics/Rect;)V",
                                        (void*) SkPaintGlue::getStringBounds },
    {"nativeGetCharArrayBounds", "(I[CIILandroid/graphics/Rect;)V",
                                    (void*) SkPaintGlue::getCharArrayBounds },
    {"setShadowLayer", "(FFFI)V", (void*)SkPaintGlue::setShadowLayer}
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
