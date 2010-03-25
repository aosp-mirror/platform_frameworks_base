/* libs/android_runtime/android/graphics/Path.cpp
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

// This file was generated from the C++ include file: SkPath.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkPath.h"

namespace android {

class SkPathGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkPath* obj) {
        delete obj;
    }

    static SkPath* init1(JNIEnv* env, jobject clazz) {
        return new SkPath();
    }
 
    static SkPath* init2(JNIEnv* env, jobject clazz, SkPath* val) {
        return new SkPath(*val);
    }
 
    static void reset(JNIEnv* env, jobject clazz, SkPath* obj) {
        obj->reset();
    }

    static void rewind(JNIEnv* env, jobject clazz, SkPath* obj) {
        obj->rewind();
    }

    static void assign(JNIEnv* env, jobject clazz, SkPath* dst, const SkPath* src) {
        *dst = *src;
    }
 
    static jint getFillType(JNIEnv* env, jobject clazz, SkPath* obj) {
        return obj->getFillType();
    }
 
    static void setFillType(JNIEnv* env, jobject clazz, SkPath* path,
                            SkPath::FillType ft) {
        path->setFillType(ft);
    }
 
    static jboolean isEmpty(JNIEnv* env, jobject clazz, SkPath* obj) {
        return obj->isEmpty();
    }
 
    static jboolean isRect(JNIEnv* env, jobject clazz, SkPath* obj, jobject rect) {
        SkRect rect_;
        jboolean result = obj->isRect(&rect_);
        GraphicsJNI::rect_to_jrectf(rect_, env, rect);
        return result;
    }
 
    static void computeBounds(JNIEnv* env, jobject clazz, SkPath* obj, jobject bounds) {
        const SkRect& bounds_ = obj->getBounds();
        GraphicsJNI::rect_to_jrectf(bounds_, env, bounds);
    }
 
    static void incReserve(JNIEnv* env, jobject clazz, SkPath* obj, jint extraPtCount) {
        obj->incReserve(extraPtCount);
    }
 
    static void moveTo__FF(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x, jfloat y) {
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        obj->moveTo(x_, y_);
    }
 
    static void rMoveTo(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->rMoveTo(dx_, dy_);
    }
 
    static void lineTo__FF(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x, jfloat y) {
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        obj->lineTo(x_, y_);
    }
 
    static void rLineTo(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->rLineTo(dx_, dy_);
    }
 
    static void quadTo__FFFF(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        obj->quadTo(x1_, y1_, x2_, y2_);
    }
 
    static void rQuadTo(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2) {
        SkScalar dx1_ = SkFloatToScalar(dx1);
        SkScalar dy1_ = SkFloatToScalar(dy1);
        SkScalar dx2_ = SkFloatToScalar(dx2);
        SkScalar dy2_ = SkFloatToScalar(dy2);
        obj->rQuadTo(dx1_, dy1_, dx2_, dy2_);
    }
 
    static void cubicTo__FFFFFF(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        SkScalar x3_ = SkFloatToScalar(x3);
        SkScalar y3_ = SkFloatToScalar(y3);
        obj->cubicTo(x1_, y1_, x2_, y2_, x3_, y3_);
    }
 
    static void rCubicTo(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        SkScalar x3_ = SkFloatToScalar(x3);
        SkScalar y3_ = SkFloatToScalar(y3);
        obj->rCubicTo(x1_, y1_, x2_, y2_, x3_, y3_);
    }
 
    static void arcTo(JNIEnv* env, jobject clazz, SkPath* obj, jobject oval, jfloat startAngle, jfloat sweepAngle, jboolean forceMoveTo) {
        SkRect oval_;
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        SkScalar startAngle_ = SkFloatToScalar(startAngle);
        SkScalar sweepAngle_ = SkFloatToScalar(sweepAngle);
        obj->arcTo(oval_, startAngle_, sweepAngle_, forceMoveTo);
    }
 
    static void close(JNIEnv* env, jobject clazz, SkPath* obj) {
        obj->close();
    }
 
    static void addRect__RectFI(JNIEnv* env, jobject clazz, SkPath* obj, jobject rect, SkPath::Direction dir) {
        SkRect rect_;
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        obj->addRect(rect_, dir);
    }
 
    static void addRect__FFFFI(JNIEnv* env, jobject clazz, SkPath* obj, jfloat left, jfloat top, jfloat right, jfloat bottom, SkPath::Direction dir) {
        SkScalar left_ = SkFloatToScalar(left);
        SkScalar top_ = SkFloatToScalar(top);
        SkScalar right_ = SkFloatToScalar(right);
        SkScalar bottom_ = SkFloatToScalar(bottom);
        obj->addRect(left_, top_, right_, bottom_, dir);
    }
 
    static void addOval(JNIEnv* env, jobject clazz, SkPath* obj, jobject oval, SkPath::Direction dir) {
        SkRect oval_;
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        obj->addOval(oval_, dir);
    }
 
    static void addCircle(JNIEnv* env, jobject clazz, SkPath* obj, jfloat x, jfloat y, jfloat radius, SkPath::Direction dir) {
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        SkScalar radius_ = SkFloatToScalar(radius);
        obj->addCircle(x_, y_, radius_, dir);
    }
 
    static void addArc(JNIEnv* env, jobject clazz, SkPath* obj, jobject oval, jfloat startAngle, jfloat sweepAngle) {
        SkRect oval_;
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        SkScalar startAngle_ = SkFloatToScalar(startAngle);
        SkScalar sweepAngle_ = SkFloatToScalar(sweepAngle);
        obj->addArc(oval_, startAngle_, sweepAngle_);
    }
 
    static void addRoundRectXY(JNIEnv* env, jobject clazz, SkPath* obj, jobject rect,
                               jfloat rx, jfloat ry, SkPath::Direction dir) {
        SkRect rect_;
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        SkScalar rx_ = SkFloatToScalar(rx);
        SkScalar ry_ = SkFloatToScalar(ry);
        obj->addRoundRect(rect_, rx_, ry_, dir);
    }
    
    static void addRoundRect8(JNIEnv* env, jobject, SkPath* obj, jobject rect,
                              jfloatArray array, SkPath::Direction dir) {
        SkRect rect_;
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        AutoJavaFloatArray  afa(env, array, 8);
        const float* src = afa.ptr();
        SkScalar dst[8];
        
        for (int i = 0; i < 8; i++) {
            dst[i] = SkFloatToScalar(src[i]);
        }
        obj->addRoundRect(rect_, dst, dir);
    }
    
    static void addPath__PathFF(JNIEnv* env, jobject clazz, SkPath* obj, SkPath* src, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->addPath(*src, dx_, dy_);
    }
 
    static void addPath__Path(JNIEnv* env, jobject clazz, SkPath* obj, SkPath* src) {
        obj->addPath(*src);
    }
 
    static void addPath__PathMatrix(JNIEnv* env, jobject clazz, SkPath* obj, SkPath* src, SkMatrix* matrix) {
        obj->addPath(*src, *matrix);
    }
 
    static void offset__FFPath(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx, jfloat dy, SkPath* dst) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->offset(dx_, dy_, dst);
    }
 
    static void offset__FF(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->offset(dx_, dy_);
    }

    static void setLastPoint(JNIEnv* env, jobject clazz, SkPath* obj, jfloat dx, jfloat dy) {
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->setLastPt(dx_, dy_);
    }
 
    static void transform__MatrixPath(JNIEnv* env, jobject clazz, SkPath* obj, SkMatrix* matrix, SkPath* dst) {
        obj->transform(*matrix, dst);
    }
 
    static void transform__Matrix(JNIEnv* env, jobject clazz, SkPath* obj, SkMatrix* matrix) {
        obj->transform(*matrix);
    }
 
};

static JNINativeMethod methods[] = {
    {"finalizer", "(I)V", (void*) SkPathGlue::finalizer},
    {"init1","()I", (void*) SkPathGlue::init1},
    {"init2","(I)I", (void*) SkPathGlue::init2},
    {"native_reset","(I)V", (void*) SkPathGlue::reset},
    {"native_rewind","(I)V", (void*) SkPathGlue::rewind},
    {"native_set","(II)V", (void*) SkPathGlue::assign},
    {"native_getFillType","(I)I", (void*) SkPathGlue::getFillType},
    {"native_setFillType","(II)V", (void*) SkPathGlue::setFillType},
    {"native_isEmpty","(I)Z", (void*) SkPathGlue::isEmpty},
    {"native_isRect","(ILandroid/graphics/RectF;)Z", (void*) SkPathGlue::isRect},
    {"native_computeBounds","(ILandroid/graphics/RectF;)V", (void*) SkPathGlue::computeBounds},
    {"native_incReserve","(II)V", (void*) SkPathGlue::incReserve},
    {"native_moveTo","(IFF)V", (void*) SkPathGlue::moveTo__FF},
    {"native_rMoveTo","(IFF)V", (void*) SkPathGlue::rMoveTo},
    {"native_lineTo","(IFF)V", (void*) SkPathGlue::lineTo__FF},
    {"native_rLineTo","(IFF)V", (void*) SkPathGlue::rLineTo},
    {"native_quadTo","(IFFFF)V", (void*) SkPathGlue::quadTo__FFFF},
    {"native_rQuadTo","(IFFFF)V", (void*) SkPathGlue::rQuadTo},
    {"native_cubicTo","(IFFFFFF)V", (void*) SkPathGlue::cubicTo__FFFFFF},
    {"native_rCubicTo","(IFFFFFF)V", (void*) SkPathGlue::rCubicTo},
    {"native_arcTo","(ILandroid/graphics/RectF;FFZ)V", (void*) SkPathGlue::arcTo},
    {"native_close","(I)V", (void*) SkPathGlue::close},
    {"native_addRect","(ILandroid/graphics/RectF;I)V", (void*) SkPathGlue::addRect__RectFI},
    {"native_addRect","(IFFFFI)V", (void*) SkPathGlue::addRect__FFFFI},
    {"native_addOval","(ILandroid/graphics/RectF;I)V", (void*) SkPathGlue::addOval},
    {"native_addCircle","(IFFFI)V", (void*) SkPathGlue::addCircle},
    {"native_addArc","(ILandroid/graphics/RectF;FF)V", (void*) SkPathGlue::addArc},
    {"native_addRoundRect","(ILandroid/graphics/RectF;FFI)V", (void*) SkPathGlue::addRoundRectXY},
    {"native_addRoundRect","(ILandroid/graphics/RectF;[FI)V", (void*) SkPathGlue::addRoundRect8},
    {"native_addPath","(IIFF)V", (void*) SkPathGlue::addPath__PathFF},
    {"native_addPath","(II)V", (void*) SkPathGlue::addPath__Path},
    {"native_addPath","(III)V", (void*) SkPathGlue::addPath__PathMatrix},
    {"native_offset","(IFFI)V", (void*) SkPathGlue::offset__FFPath},
    {"native_offset","(IFF)V", (void*) SkPathGlue::offset__FF},
    {"native_setLastPoint","(IFF)V", (void*) SkPathGlue::setLastPoint},
    {"native_transform","(III)V", (void*) SkPathGlue::transform__MatrixPath},
    {"native_transform","(II)V", (void*) SkPathGlue::transform__Matrix}
};

int register_android_graphics_Path(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/Path", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
