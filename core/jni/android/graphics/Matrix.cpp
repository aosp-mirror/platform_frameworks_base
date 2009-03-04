/* libs/android_runtime/android/graphics/Matrix.cpp
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

// This file was generated from the C++ include file: SkMatrix.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkMatrix.h"
#include "SkTemplates.h"

namespace android {

class SkMatrixGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkMatrix* obj) {
        delete obj;
    }

    static SkMatrix* create(JNIEnv* env, jobject clazz, const SkMatrix* src) {
        SkMatrix* obj = new SkMatrix();
        if (src)
            *obj = *src;
        else
            obj->reset();
        return obj;
    }
 
    static jboolean isIdentity(JNIEnv* env, jobject clazz, SkMatrix* obj) {
        return obj->isIdentity();
    }
 
    static jboolean rectStaysRect(JNIEnv* env, jobject clazz, SkMatrix* obj) {
        return obj->rectStaysRect();
    }
 
    static void reset(JNIEnv* env, jobject clazz, SkMatrix* obj) {
        obj->reset();
    }
 
    static void set(JNIEnv* env, jobject clazz, SkMatrix* obj, SkMatrix* other) {
        *obj = *other;
    }
 
    static void setTranslate(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->setTranslate(dx_, dy_);
    }
 
    static void setScale__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy, jfloat px, jfloat py) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        obj->setScale(sx_, sy_, px_, py_);
    }
 
    static void setScale__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        obj->setScale(sx_, sy_);
    }
 
    static void setRotate__FFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees, jfloat px, jfloat py) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        obj->setRotate(degrees_, px_, py_);
    }
 
    static void setRotate__F(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        obj->setRotate(degrees_);
    }
 
    static void setSinCos__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sinValue, jfloat cosValue, jfloat px, jfloat py) {
        SkScalar sinValue_ = SkFloatToScalar(sinValue);
        SkScalar cosValue_ = SkFloatToScalar(cosValue);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        obj->setSinCos(sinValue_, cosValue_, px_, py_);
    }
 
    static void setSinCos__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sinValue, jfloat cosValue) {
        SkScalar sinValue_ = SkFloatToScalar(sinValue);
        SkScalar cosValue_ = SkFloatToScalar(cosValue);
        obj->setSinCos(sinValue_, cosValue_);
    }
 
    static void setSkew__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat kx, jfloat ky, jfloat px, jfloat py) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        obj->setSkew(kx_, ky_, px_, py_);
    }
 
    static void setSkew__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat kx, jfloat ky) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        obj->setSkew(kx_, ky_);
    }
 
    static jboolean setConcat(JNIEnv* env, jobject clazz, SkMatrix* obj, SkMatrix* a, SkMatrix* b) {
        return obj->setConcat(*a, *b);
    }
 
    static jboolean preTranslate(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        return obj->preTranslate(dx_, dy_);
    }
 
    static jboolean preScale__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy, jfloat px, jfloat py) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->preScale(sx_, sy_, px_, py_);
    }
 
    static jboolean preScale__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        return obj->preScale(sx_, sy_);
    }
 
    static jboolean preRotate__FFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees, jfloat px, jfloat py) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->preRotate(degrees_, px_, py_);
    }
 
    static jboolean preRotate__F(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        return obj->preRotate(degrees_);
    }
 
    static jboolean preSkew__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat kx, jfloat ky, jfloat px, jfloat py) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->preSkew(kx_, ky_, px_, py_);
    }
 
    static jboolean preSkew__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat kx, jfloat ky) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        return obj->preSkew(kx_, ky_);
    }
 
    static jboolean preConcat(JNIEnv* env, jobject clazz, SkMatrix* obj, SkMatrix* other) {
        return obj->preConcat(*other);
    }
 
    static jboolean postTranslate(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        return obj->postTranslate(dx_, dy_);
    }
 
    static jboolean postScale__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy, jfloat px, jfloat py) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->postScale(sx_, sy_, px_, py_);
    }
 
    static jboolean postScale__FF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat sx, jfloat sy) {
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        return obj->postScale(sx_, sy_);
    }
 
    static jboolean postRotate__FFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees, jfloat px, jfloat py) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->postRotate(degrees_, px_, py_);
    }
 
    static jboolean postRotate__F(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat degrees) {
        SkScalar degrees_ = SkFloatToScalar(degrees);
        return obj->postRotate(degrees_);
    }
 
    static jboolean postSkew__FFFF(JNIEnv* env, jobject clazz, SkMatrix* obj, jfloat kx, jfloat ky, jfloat px, jfloat py) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        SkScalar px_ = SkFloatToScalar(px);
        SkScalar py_ = SkFloatToScalar(py);
        return obj->postSkew(kx_, ky_, px_, py_);
    }
 
    static jboolean postSkew__FF(JNIEnv* env, jobject clazz, SkMatrix* matrix, jfloat kx, jfloat ky) {
        SkScalar kx_ = SkFloatToScalar(kx);
        SkScalar ky_ = SkFloatToScalar(ky);
        return matrix->postSkew(kx_, ky_);
    }
 
    static jboolean postConcat(JNIEnv* env, jobject clazz, SkMatrix* matrix, SkMatrix* other) {
        return matrix->postConcat(*other);
    }
 
    static jboolean setRectToRect(JNIEnv* env, jobject clazz, SkMatrix* matrix, jobject src, jobject dst, SkMatrix::ScaleToFit stf) {
        SkRect src_;
        GraphicsJNI::jrectf_to_rect(env, src, &src_);
        SkRect dst_;
        GraphicsJNI::jrectf_to_rect(env, dst, &dst_);
        return matrix->setRectToRect(src_, dst_, stf);
    }
 
    static jboolean setPolyToPoly(JNIEnv* env, jobject clazz, SkMatrix* matrix,
                                  jfloatArray jsrc, int srcIndex,
                                  jfloatArray jdst, int dstIndex, int ptCount) {
        SkASSERT(srcIndex >= 0);
        SkASSERT(dstIndex >= 0);
        SkASSERT((unsigned)ptCount <= 4);

        AutoJavaFloatArray autoSrc(env, jsrc, srcIndex + (ptCount << 1));
        AutoJavaFloatArray autoDst(env, jdst, dstIndex + (ptCount << 1));
        float* src = autoSrc.ptr() + srcIndex;
        float* dst = autoDst.ptr() + dstIndex;

#ifdef SK_SCALAR_IS_FIXED        
        SkPoint srcPt[4], dstPt[4];
        for (int i = 0; i < ptCount; i++) {
            int x = i << 1;
            int y = x + 1;
            srcPt[i].set(SkFloatToScalar(src[x]), SkFloatToScalar(src[y]));
            dstPt[i].set(SkFloatToScalar(dst[x]), SkFloatToScalar(dst[y]));
        }
        return matrix->setPolyToPoly(srcPt, dstPt, ptCount);
#else
        return matrix->setPolyToPoly((const SkPoint*)src, (const SkPoint*)dst,
                                     ptCount);
#endif
    }
 
    static jboolean invert(JNIEnv* env, jobject clazz, SkMatrix* matrix, SkMatrix* inverse) {
        return matrix->invert(inverse);
    }
 
    static void mapPoints(JNIEnv* env, jobject clazz, SkMatrix* matrix,
                              jfloatArray dst, int dstIndex,
                              jfloatArray src, int srcIndex,
                              int ptCount, bool isPts) {
        SkASSERT(ptCount >= 0);
        AutoJavaFloatArray autoSrc(env, src, srcIndex + (ptCount << 1));
        AutoJavaFloatArray autoDst(env, dst, dstIndex + (ptCount << 1));
        float* srcArray = autoSrc.ptr() + srcIndex;
        float* dstArray = autoDst.ptr() + dstIndex;
        
#ifdef SK_SCALAR_IS_FIXED        
        // we allocate twice the count, 1 set for src, 1 for dst
        SkAutoSTMalloc<32, SkPoint> storage(ptCount * 2);
        SkPoint* pts = storage.get();
        SkPoint* srcPt = pts;
        SkPoint* dstPt = pts + ptCount;
        
        int i;
        for (i = 0; i < ptCount; i++) {
            srcPt[i].set(SkFloatToScalar(srcArray[i << 1]),
                         SkFloatToScalar(srcArray[(i << 1) + 1]));
        }
        
        if (isPts)
            matrix->mapPoints(dstPt, srcPt, ptCount);
        else
            matrix->mapVectors(dstPt, srcPt, ptCount);
        
        for (i = 0; i < ptCount; i++) {
            dstArray[i << 1]  = SkScalarToFloat(dstPt[i].fX);
            dstArray[(i << 1) + 1]  = SkScalarToFloat(dstPt[i].fY);
        }
#else
        if (isPts)
            matrix->mapPoints((SkPoint*)dstArray, (const SkPoint*)srcArray,
                              ptCount);
        else
            matrix->mapVectors((SkVector*)dstArray, (const SkVector*)srcArray,
                               ptCount);
#endif
    }
 
    static jboolean mapRect__RectFRectF(JNIEnv* env, jobject clazz, SkMatrix* matrix, jobjectArray dst, jobject src) {
        SkRect dst_, src_;
        GraphicsJNI::jrectf_to_rect(env, src, &src_);
        jboolean rectStaysRect = matrix->mapRect(&dst_, src_);
        GraphicsJNI::rect_to_jrectf(dst_, env, dst);
        return rectStaysRect;
    }
 
    static jfloat mapRadius(JNIEnv* env, jobject clazz, SkMatrix* matrix, jfloat radius) {
        return SkScalarToFloat(matrix->mapRadius(SkFloatToScalar(radius)));
    }
 
    static void getValues(JNIEnv* env, jobject clazz, SkMatrix* matrix, jfloatArray values) {
        AutoJavaFloatArray autoValues(env, values, 9);
        float* dst = autoValues.ptr();

#ifdef SK_SCALAR_IS_FIXED
        for (int i = 0; i < 6; i++) {
            dst[i] = SkFixedToFloat(matrix->get(i));
        }
        for (int j = 6; j < 9; j++) {
            dst[j] = SkFractToFloat(matrix->get(j));
        }
#else
        for (int i = 0; i < 9; i++) {
            dst[i] = matrix->get(i);
        }
#endif
    }
 
    static void setValues(JNIEnv* env, jobject clazz, SkMatrix* matrix, jfloatArray values) {
        AutoJavaFloatArray autoValues(env, values, 9);
        const float* src = autoValues.ptr();

#ifdef SK_SCALAR_IS_FIXED
        for (int i = 0; i < 6; i++) {
            matrix->set(i, SkFloatToFixed(src[i]));
        }
        for (int j = 6; j < 9; j++) {
            matrix->set(j, SkFloatToFract(src[j]));
        }
#else
        for (int i = 0; i < 9; i++) {
            matrix->set(i, src[i]);
        }
#endif
    }

    static jboolean equals(JNIEnv* env, jobject clazz, const SkMatrix* a, const SkMatrix* b) {
        return *a == *b;
    }
 };

static JNINativeMethod methods[] = {
    {"finalizer", "(I)V", (void*) SkMatrixGlue::finalizer},
    {"native_create","(I)I", (void*) SkMatrixGlue::create},
    {"native_isIdentity","(I)Z", (void*) SkMatrixGlue::isIdentity},
    {"native_rectStaysRect","(I)Z", (void*) SkMatrixGlue::rectStaysRect},
    {"native_reset","(I)V", (void*) SkMatrixGlue::reset},
    {"native_set","(II)V", (void*) SkMatrixGlue::set},
    {"native_setTranslate","(IFF)V", (void*) SkMatrixGlue::setTranslate},
    {"native_setScale","(IFFFF)V", (void*) SkMatrixGlue::setScale__FFFF},
    {"native_setScale","(IFF)V", (void*) SkMatrixGlue::setScale__FF},
    {"native_setRotate","(IFFF)V", (void*) SkMatrixGlue::setRotate__FFF},
    {"native_setRotate","(IF)V", (void*) SkMatrixGlue::setRotate__F},
    {"native_setSinCos","(IFFFF)V", (void*) SkMatrixGlue::setSinCos__FFFF},
    {"native_setSinCos","(IFF)V", (void*) SkMatrixGlue::setSinCos__FF},
    {"native_setSkew","(IFFFF)V", (void*) SkMatrixGlue::setSkew__FFFF},
    {"native_setSkew","(IFF)V", (void*) SkMatrixGlue::setSkew__FF},
    {"native_setConcat","(III)Z", (void*) SkMatrixGlue::setConcat},
    {"native_preTranslate","(IFF)Z", (void*) SkMatrixGlue::preTranslate},
    {"native_preScale","(IFFFF)Z", (void*) SkMatrixGlue::preScale__FFFF},
    {"native_preScale","(IFF)Z", (void*) SkMatrixGlue::preScale__FF},
    {"native_preRotate","(IFFF)Z", (void*) SkMatrixGlue::preRotate__FFF},
    {"native_preRotate","(IF)Z", (void*) SkMatrixGlue::preRotate__F},
    {"native_preSkew","(IFFFF)Z", (void*) SkMatrixGlue::preSkew__FFFF},
    {"native_preSkew","(IFF)Z", (void*) SkMatrixGlue::preSkew__FF},
    {"native_preConcat","(II)Z", (void*) SkMatrixGlue::preConcat},
    {"native_postTranslate","(IFF)Z", (void*) SkMatrixGlue::postTranslate},
    {"native_postScale","(IFFFF)Z", (void*) SkMatrixGlue::postScale__FFFF},
    {"native_postScale","(IFF)Z", (void*) SkMatrixGlue::postScale__FF},
    {"native_postRotate","(IFFF)Z", (void*) SkMatrixGlue::postRotate__FFF},
    {"native_postRotate","(IF)Z", (void*) SkMatrixGlue::postRotate__F},
    {"native_postSkew","(IFFFF)Z", (void*) SkMatrixGlue::postSkew__FFFF},
    {"native_postSkew","(IFF)Z", (void*) SkMatrixGlue::postSkew__FF},
    {"native_postConcat","(II)Z", (void*) SkMatrixGlue::postConcat},
    {"native_setRectToRect","(ILandroid/graphics/RectF;Landroid/graphics/RectF;I)Z", (void*) SkMatrixGlue::setRectToRect},
    {"native_setPolyToPoly","(I[FI[FII)Z", (void*) SkMatrixGlue::setPolyToPoly},
    {"native_invert","(II)Z", (void*) SkMatrixGlue::invert},
    {"native_mapPoints","(I[FI[FIIZ)V", (void*) SkMatrixGlue::mapPoints},
    {"native_mapRect","(ILandroid/graphics/RectF;Landroid/graphics/RectF;)Z", (void*) SkMatrixGlue::mapRect__RectFRectF},
    {"native_mapRadius","(IF)F", (void*) SkMatrixGlue::mapRadius},
    {"native_getValues","(I[F)V", (void*) SkMatrixGlue::getValues},
    {"native_setValues","(I[F)V", (void*) SkMatrixGlue::setValues},
    {"native_equals", "(II)Z", (void*) SkMatrixGlue::equals}
};

int register_android_graphics_Matrix(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/Matrix", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
