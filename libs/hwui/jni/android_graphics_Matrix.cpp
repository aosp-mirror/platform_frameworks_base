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

#include "GraphicsJNI.h"
#include "Matrix.h"
#include "SkMatrix.h"

namespace android {

static_assert(sizeof(SkMatrix) == 40, "Unexpected sizeof(SkMatrix), "
        "update size in Matrix.java#NATIVE_ALLOCATION_SIZE and here");

class SkMatrixGlue {
public:

    // ---------------- Regular JNI -----------------------------

    static void finalizer(jlong objHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        delete obj;
    }

    static jlong getNativeFinalizer(JNIEnv* env, jobject clazz) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&finalizer));
    }

    static jlong create(JNIEnv* env, jobject clazz, jlong srcHandle) {
        const SkMatrix* src = reinterpret_cast<SkMatrix*>(srcHandle);
        SkMatrix* obj = new SkMatrix();
        if (src)
            *obj = *src;
        else
            obj->reset();
        return reinterpret_cast<jlong>(obj);
    }

    // ---------------- @FastNative -----------------------------

    static void mapPoints(JNIEnv* env, jobject clazz, jlong matrixHandle,
            jfloatArray dst, jint dstIndex, jfloatArray src, jint srcIndex,
            jint ptCount, jboolean isPts) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkASSERT(ptCount >= 0);
        AutoJavaFloatArray autoSrc(env, src, srcIndex + (ptCount << 1),
                kRO_JNIAccess);
        AutoJavaFloatArray autoDst(env, dst, dstIndex + (ptCount << 1),
                kRW_JNIAccess);
        float* srcArray = autoSrc.ptr() + srcIndex;
        float* dstArray = autoDst.ptr() + dstIndex;
        if (isPts)
            matrix->mapPoints((SkPoint*) dstArray, (const SkPoint*) srcArray,
                    ptCount);
        else
            matrix->mapVectors((SkVector*) dstArray, (const SkVector*) srcArray,
                    ptCount);
    }

    static jboolean mapRect__RectFRectF(JNIEnv* env, jobject clazz,
            jlong matrixHandle, jobjectArray dst, jobject src) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkRect dst_, src_;
        GraphicsJNI::jrectf_to_rect(env, src, &src_);
        jboolean rectStaysRect = matrix->mapRect(&dst_, src_);
        GraphicsJNI::rect_to_jrectf(dst_, env, dst);
        return rectStaysRect ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean setRectToRect(JNIEnv* env, jobject clazz,
            jlong matrixHandle, jobject src, jobject dst, jint stfHandle) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkMatrix::ScaleToFit stf = static_cast<SkMatrix::ScaleToFit>(stfHandle);
        SkRect src_;
        GraphicsJNI::jrectf_to_rect(env, src, &src_);
        SkRect dst_;
        GraphicsJNI::jrectf_to_rect(env, dst, &dst_);
        return matrix->setRectToRect(src_, dst_, stf) ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean setPolyToPoly(JNIEnv* env, jobject clazz,
            jlong matrixHandle, jfloatArray jsrc, jint srcIndex,
            jfloatArray jdst, jint dstIndex, jint ptCount) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkASSERT(srcIndex >= 0);
        SkASSERT(dstIndex >= 0);
        SkASSERT((unsigned )ptCount <= 4);

        AutoJavaFloatArray autoSrc(env, jsrc, srcIndex + (ptCount << 1),
                kRO_JNIAccess);
        AutoJavaFloatArray autoDst(env, jdst, dstIndex + (ptCount << 1),
                kRW_JNIAccess);
        float* src = autoSrc.ptr() + srcIndex;
        float* dst = autoDst.ptr() + dstIndex;
        bool result;

        result = matrix->setPolyToPoly((const SkPoint*) src,
                (const SkPoint*) dst, ptCount);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static void getValues(JNIEnv* env, jobject clazz, jlong matrixHandle,
            jfloatArray values) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        AutoJavaFloatArray autoValues(env, values, 9, kRW_JNIAccess);
        float* dst = autoValues.ptr();
        for (int i = 0; i < 9; i++) {
            dst[i] = matrix->get(i);
        }
    }

    static void setValues(JNIEnv* env, jobject clazz, jlong matrixHandle,
            jfloatArray values) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        AutoJavaFloatArray autoValues(env, values, 9, kRO_JNIAccess);
        const float* src = autoValues.ptr();

        for (int i = 0; i < 9; i++) {
            matrix->set(i, src[i]);
        }
    }

    // ---------------- @CriticalNative -----------------------------

    static jboolean isIdentity(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        return obj->isIdentity() ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean isAffine(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        return obj->asAffine(NULL) ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean rectStaysRect(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        return obj->rectStaysRect() ? JNI_TRUE : JNI_FALSE;
    }

    static void reset(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->reset();
    }

    static void set(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jlong otherHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        SkMatrix* other = reinterpret_cast<SkMatrix*>(otherHandle);
        *obj = *other;
    }

    static void setTranslate(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat dx, jfloat dy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setTranslate(dx, dy);
    }

    static void setScale__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setScale(sx, sy, px, py);
    }

    static void setScale__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setScale(sx, sy);
    }

    static void setRotate__FFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setRotate(degrees, px, py);
    }

    static void setRotate__F(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setRotate(degrees);
    }

    static void setSinCos__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sinValue,
            jfloat cosValue, jfloat px, jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setSinCos(sinValue, cosValue, px, py);
    }

    static void setSinCos__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sinValue,
            jfloat cosValue) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setSinCos(sinValue, cosValue);
    }

    static void setSkew__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat kx, jfloat ky, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setSkew(kx, ky, px, py);
    }

    static void setSkew__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat kx, jfloat ky) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->setSkew(kx, ky);
    }

    static void setConcat(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jlong aHandle, jlong bHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        SkMatrix* a = reinterpret_cast<SkMatrix*>(aHandle);
        SkMatrix* b = reinterpret_cast<SkMatrix*>(bHandle);
        obj->setConcat(*a, *b);
    }

    static void preTranslate(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat dx, jfloat dy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preTranslate(dx, dy);
    }

    static void preScale__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preScale(sx, sy, px, py);
    }

    static void preScale__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preScale(sx, sy);
    }

    static void preRotate__FFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preRotate(degrees, px, py);
    }

    static void preRotate__F(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preRotate(degrees);
    }

    static void preSkew__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat kx, jfloat ky, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preSkew(kx, ky, px, py);
    }

    static void preSkew__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat kx, jfloat ky) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->preSkew(kx, ky);
    }

    static void preConcat(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jlong otherHandle) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        SkMatrix* other = reinterpret_cast<SkMatrix*>(otherHandle);
        obj->preConcat(*other);
    }

    static void postTranslate(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat dx, jfloat dy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postTranslate(dx, dy);
    }

    static void postScale__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy,
            jfloat px, jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postScale(sx, sy, px, py);
    }

    static void postScale__FF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat sx, jfloat sy) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postScale(sx, sy);
    }

    static void postRotate__FFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postRotate(degrees, px, py);
    }

    static void postRotate__F(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat degrees) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postRotate(degrees);
    }

    static void postSkew__FFFF(CRITICAL_JNI_PARAMS_COMMA jlong objHandle, jfloat kx, jfloat ky, jfloat px,
            jfloat py) {
        SkMatrix* obj = reinterpret_cast<SkMatrix*>(objHandle);
        obj->postSkew(kx, ky, px, py);
    }

    static void postSkew__FF(CRITICAL_JNI_PARAMS_COMMA jlong matrixHandle, jfloat kx, jfloat ky) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        matrix->postSkew(kx, ky);
    }

    static void postConcat(CRITICAL_JNI_PARAMS_COMMA jlong matrixHandle, jlong otherHandle) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkMatrix* other = reinterpret_cast<SkMatrix*>(otherHandle);
        matrix->postConcat(*other);
    }

    static jboolean invert(CRITICAL_JNI_PARAMS_COMMA jlong matrixHandle, jlong inverseHandle) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkMatrix* inverse = reinterpret_cast<SkMatrix*>(inverseHandle);
        return matrix->invert(inverse);
    }

    static jfloat mapRadius(CRITICAL_JNI_PARAMS_COMMA jlong matrixHandle, jfloat radius) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        float result;
        result = SkScalarToFloat(matrix->mapRadius(radius));
        return static_cast<jfloat>(result);
    }

    static jboolean equals(CRITICAL_JNI_PARAMS_COMMA jlong aHandle, jlong bHandle) {
        const SkMatrix* a = reinterpret_cast<SkMatrix*>(aHandle);
        const SkMatrix* b = reinterpret_cast<SkMatrix*>(bHandle);
        return *a == *b;
    }
};

static const JNINativeMethod methods[] = {
    {"nGetNativeFinalizer", "()J", (void*) SkMatrixGlue::getNativeFinalizer},
    {"nCreate","(J)J", (void*) SkMatrixGlue::create},

    // ------- @FastNative below here ---------------
    {"nMapPoints","(J[FI[FIIZ)V", (void*) SkMatrixGlue::mapPoints},
    {"nMapRect","(JLandroid/graphics/RectF;Landroid/graphics/RectF;)Z",
            (void*) SkMatrixGlue::mapRect__RectFRectF},
    {"nSetRectToRect","(JLandroid/graphics/RectF;Landroid/graphics/RectF;I)Z",
            (void*) SkMatrixGlue::setRectToRect},
    {"nSetPolyToPoly","(J[FI[FII)Z", (void*) SkMatrixGlue::setPolyToPoly},
    {"nGetValues","(J[F)V", (void*) SkMatrixGlue::getValues},
    {"nSetValues","(J[F)V", (void*) SkMatrixGlue::setValues},

    // ------- @CriticalNative below here ---------------
    {"nIsIdentity","(J)Z", (void*) SkMatrixGlue::isIdentity},
    {"nIsAffine","(J)Z", (void*) SkMatrixGlue::isAffine},
    {"nRectStaysRect","(J)Z", (void*) SkMatrixGlue::rectStaysRect},
    {"nReset","(J)V", (void*) SkMatrixGlue::reset},
    {"nSet","(JJ)V", (void*) SkMatrixGlue::set},
    {"nSetTranslate","(JFF)V", (void*) SkMatrixGlue::setTranslate},
    {"nSetScale","(JFFFF)V", (void*) SkMatrixGlue::setScale__FFFF},
    {"nSetScale","(JFF)V", (void*) SkMatrixGlue::setScale__FF},
    {"nSetRotate","(JFFF)V", (void*) SkMatrixGlue::setRotate__FFF},
    {"nSetRotate","(JF)V", (void*) SkMatrixGlue::setRotate__F},
    {"nSetSinCos","(JFFFF)V", (void*) SkMatrixGlue::setSinCos__FFFF},
    {"nSetSinCos","(JFF)V", (void*) SkMatrixGlue::setSinCos__FF},
    {"nSetSkew","(JFFFF)V", (void*) SkMatrixGlue::setSkew__FFFF},
    {"nSetSkew","(JFF)V", (void*) SkMatrixGlue::setSkew__FF},
    {"nSetConcat","(JJJ)V", (void*) SkMatrixGlue::setConcat},
    {"nPreTranslate","(JFF)V", (void*) SkMatrixGlue::preTranslate},
    {"nPreScale","(JFFFF)V", (void*) SkMatrixGlue::preScale__FFFF},
    {"nPreScale","(JFF)V", (void*) SkMatrixGlue::preScale__FF},
    {"nPreRotate","(JFFF)V", (void*) SkMatrixGlue::preRotate__FFF},
    {"nPreRotate","(JF)V", (void*) SkMatrixGlue::preRotate__F},
    {"nPreSkew","(JFFFF)V", (void*) SkMatrixGlue::preSkew__FFFF},
    {"nPreSkew","(JFF)V", (void*) SkMatrixGlue::preSkew__FF},
    {"nPreConcat","(JJ)V", (void*) SkMatrixGlue::preConcat},
    {"nPostTranslate","(JFF)V", (void*) SkMatrixGlue::postTranslate},
    {"nPostScale","(JFFFF)V", (void*) SkMatrixGlue::postScale__FFFF},
    {"nPostScale","(JFF)V", (void*) SkMatrixGlue::postScale__FF},
    {"nPostRotate","(JFFF)V", (void*) SkMatrixGlue::postRotate__FFF},
    {"nPostRotate","(JF)V", (void*) SkMatrixGlue::postRotate__F},
    {"nPostSkew","(JFFFF)V", (void*) SkMatrixGlue::postSkew__FFFF},
    {"nPostSkew","(JFF)V", (void*) SkMatrixGlue::postSkew__FF},
    {"nPostConcat","(JJ)V", (void*) SkMatrixGlue::postConcat},
    {"nInvert","(JJ)Z", (void*) SkMatrixGlue::invert},
    {"nMapRadius","(JF)F", (void*) SkMatrixGlue::mapRadius},
    {"nEquals", "(JJ)Z", (void*) SkMatrixGlue::equals}
};

static const JNINativeMethod extra_methods[] = {
        {"nGetNativeFinalizer", "()J", (void*)SkMatrixGlue::getNativeFinalizer},
        {"nCreate", "(J)J", (void*)SkMatrixGlue::create},
};

static jclass sClazz;
static jfieldID sNativeInstanceField;
static jmethodID sCtor;

int register_android_graphics_Matrix(JNIEnv* env) {
    // Methods only used on Ravenwood (for now). See the javadoc on Matrix$ExtraNativesx
    // for why we need it.
    //
    // We don't need it on non-ravenwood, but we don't (yet) have a way to detect ravenwood
    // environment, so we just always run it.
    RegisterMethodsOrDie(env, "android/graphics/Matrix$ExtraNatives", extra_methods,
                         NELEM(extra_methods));

    int result = RegisterMethodsOrDie(env, "android/graphics/Matrix", methods, NELEM(methods));

    jclass clazz = FindClassOrDie(env, "android/graphics/Matrix");
    sClazz = MakeGlobalRefOrDie(env, clazz);
    sNativeInstanceField = GetFieldIDOrDie(env, clazz, "native_instance", "J");
    sCtor = GetMethodIDOrDie(env, clazz, "<init>", "()V");

    return result;
}

SkMatrix* android_graphics_Matrix_getSkMatrix(JNIEnv* env, jobject matrixObj) {
    return reinterpret_cast<SkMatrix*>(env->GetLongField(matrixObj, sNativeInstanceField));
}

jobject android_graphics_Matrix_newInstance(JNIEnv* env) {
    return env->NewObject(sClazz, sCtor);
}
}
