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
#include "SkPathOps.h"

#include <Caches.h>
#include <vector>
#include <map>

namespace android {

class SkPathGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
#ifdef USE_OPENGL_RENDERER
        if (android::uirenderer::Caches::hasInstance()) {
            android::uirenderer::Caches::getInstance().resourceCache.destructor(obj);
            return;
        }
#endif
        delete obj;
    }

    static jlong init1(JNIEnv* env, jobject clazz) {
        return reinterpret_cast<jlong>(new SkPath());
    }

    static jlong init2(JNIEnv* env, jobject clazz, jlong valHandle) {
        SkPath* val = reinterpret_cast<SkPath*>(valHandle);
        return reinterpret_cast<jlong>(new SkPath(*val));
    }

    static void reset(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->reset();
    }

    static void rewind(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rewind();
    }

    static void assign(JNIEnv* env, jobject clazz, jlong dstHandle, jlong srcHandle) {
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        const SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        *dst = *src;
    }

    static jboolean isConvex(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->isConvex();
    }

    static jint getFillType(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->getFillType();
    }

    static void setFillType(JNIEnv* env, jobject clazz, jlong pathHandle, jint ftHandle) {
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkPath::FillType ft = static_cast<SkPath::FillType>(ftHandle);
        path->setFillType(ft);
    }

    static jboolean isEmpty(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->isEmpty();
    }
 
    static jboolean isRect(JNIEnv* env, jobject clazz, jlong objHandle, jobject rect) {
        SkRect rect_;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        jboolean result = obj->isRect(&rect_);
        GraphicsJNI::rect_to_jrectf(rect_, env, rect);
        return result;
    }
 
    static void computeBounds(JNIEnv* env, jobject clazz, jlong objHandle, jobject bounds) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        const SkRect& bounds_ = obj->getBounds();
        GraphicsJNI::rect_to_jrectf(bounds_, env, bounds);
    }
 
    static void incReserve(JNIEnv* env, jobject clazz, jlong objHandle, jint extraPtCount) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->incReserve(extraPtCount);
    }
 
    static void moveTo__FF(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x, jfloat y) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        obj->moveTo(x_, y_);
    }
 
    static void rMoveTo(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->rMoveTo(dx_, dy_);
    }
 
    static void lineTo__FF(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x, jfloat y) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        obj->lineTo(x_, y_);
    }
 
    static void rLineTo(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->rLineTo(dx_, dy_);
    }
 
    static void quadTo__FFFF(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        obj->quadTo(x1_, y1_, x2_, y2_);
    }
 
    static void rQuadTo(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar dx1_ = SkFloatToScalar(dx1);
        SkScalar dy1_ = SkFloatToScalar(dy1);
        SkScalar dx2_ = SkFloatToScalar(dx2);
        SkScalar dy2_ = SkFloatToScalar(dy2);
        obj->rQuadTo(dx1_, dy1_, dx2_, dy2_);
    }
 
    static void cubicTo__FFFFFF(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        SkScalar x3_ = SkFloatToScalar(x3);
        SkScalar y3_ = SkFloatToScalar(y3);
        obj->cubicTo(x1_, y1_, x2_, y2_, x3_, y3_);
    }
 
    static void rCubicTo(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar x1_ = SkFloatToScalar(x1);
        SkScalar y1_ = SkFloatToScalar(y1);
        SkScalar x2_ = SkFloatToScalar(x2);
        SkScalar y2_ = SkFloatToScalar(y2);
        SkScalar x3_ = SkFloatToScalar(x3);
        SkScalar y3_ = SkFloatToScalar(y3);
        obj->rCubicTo(x1_, y1_, x2_, y2_, x3_, y3_);
    }
 
    static void arcTo(JNIEnv* env, jobject clazz, jlong objHandle, jobject oval, jfloat startAngle, jfloat sweepAngle, jboolean forceMoveTo) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkRect oval_;
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        SkScalar startAngle_ = SkFloatToScalar(startAngle);
        SkScalar sweepAngle_ = SkFloatToScalar(sweepAngle);
        obj->arcTo(oval_, startAngle_, sweepAngle_, forceMoveTo);
    }
 
    static void close(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->close();
    }
 
    static void addRect__RectFI(JNIEnv* env, jobject clazz, jlong objHandle, jobject rect, jint dirHandle) {
        SkRect rect_;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        obj->addRect(rect_, dir);
    }
 
    static void addRect__FFFFI(JNIEnv* env, jobject clazz, jlong objHandle, jfloat left, jfloat top, jfloat right, jfloat bottom, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        SkScalar left_ = SkFloatToScalar(left);
        SkScalar top_ = SkFloatToScalar(top);
        SkScalar right_ = SkFloatToScalar(right);
        SkScalar bottom_ = SkFloatToScalar(bottom);
        obj->addRect(left_, top_, right_, bottom_, dir);
    }
 
    static void addOval(JNIEnv* env, jobject clazz, jlong objHandle, jobject oval, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        SkRect oval_;
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        obj->addOval(oval_, dir);
    }
 
    static void addCircle(JNIEnv* env, jobject clazz, jlong objHandle, jfloat x, jfloat y, jfloat radius, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        SkScalar radius_ = SkFloatToScalar(radius);
        obj->addCircle(x_, y_, radius_, dir);
    }
 
    static void addArc(JNIEnv* env, jobject clazz, jlong objHandle, jobject oval, jfloat startAngle, jfloat sweepAngle) {
        SkRect oval_;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        GraphicsJNI::jrectf_to_rect(env, oval, &oval_);
        SkScalar startAngle_ = SkFloatToScalar(startAngle);
        SkScalar sweepAngle_ = SkFloatToScalar(sweepAngle);
        obj->addArc(oval_, startAngle_, sweepAngle_);
    }
 
    static void addRoundRectXY(JNIEnv* env, jobject clazz, jlong objHandle, jobject rect,
            jfloat rx, jfloat ry, jint dirHandle) {
        SkRect rect_;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        SkScalar rx_ = SkFloatToScalar(rx);
        SkScalar ry_ = SkFloatToScalar(ry);
        obj->addRoundRect(rect_, rx_, ry_, dir);
    }
    
    static void addRoundRect8(JNIEnv* env, jobject, jlong objHandle, jobject rect,
            jfloatArray array, jint dirHandle) {
        SkRect rect_;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        AutoJavaFloatArray  afa(env, array, 8);
        const float* src = afa.ptr();
        SkScalar dst[8];
        
        for (int i = 0; i < 8; i++) {
            dst[i] = SkFloatToScalar(src[i]);
        }
        obj->addRoundRect(rect_, dst, dir);
    }
    
    static void addPath__PathFF(JNIEnv* env, jobject clazz, jlong objHandle, jlong srcHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->addPath(*src, dx_, dy_);
    }
 
    static void addPath__Path(JNIEnv* env, jobject clazz, jlong objHandle, jlong srcHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        obj->addPath(*src);
    }
 
    static void addPath__PathMatrix(JNIEnv* env, jobject clazz, jlong objHandle, jlong srcHandle, jlong matrixHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        obj->addPath(*src, *matrix);
    }
 
    static void offset__FFPath(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx, jfloat dy, jlong dstHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->offset(dx_, dy_, dst);
    }
 
    static void offset__FF(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->offset(dx_, dy_);
    }

    static void setLastPoint(JNIEnv* env, jobject clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        obj->setLastPt(dx_, dy_);
    }
 
    static void transform__MatrixPath(JNIEnv* env, jobject clazz, jlong objHandle, jlong matrixHandle, jlong dstHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        obj->transform(*matrix, dst);
    }
 
    static void transform__Matrix(JNIEnv* env, jobject clazz, jlong objHandle, jlong matrixHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        obj->transform(*matrix);
    }

    static jboolean op(JNIEnv* env, jobject clazz, jlong p1Handle, jlong p2Handle, jint opHandle, jlong rHandle) {
        SkPath* p1  = reinterpret_cast<SkPath*>(p1Handle);
        SkPath* p2  = reinterpret_cast<SkPath*>(p2Handle);
        SkPathOp op = static_cast<SkPathOp>(opHandle);
        SkPath* r   = reinterpret_cast<SkPath*>(rHandle);
        return Op(*p1, *p2, op, r);
     }

    typedef SkPoint (*bezierCalculation)(float t, const SkPoint* points);

    static void addMove(std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths,
            const SkPoint& point) {
        float length = 0;
        if (!lengths.empty()) {
            length = lengths.back();
        }
        segmentPoints.push_back(point);
        lengths.push_back(length);
    }

    static void addLine(std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths,
            const SkPoint& toPoint) {
        if (segmentPoints.empty()) {
            segmentPoints.push_back(SkPoint::Make(0, 0));
            lengths.push_back(0);
        } else if (segmentPoints.back() == toPoint) {
            return; // Empty line
        }
        float length = lengths.back() + SkPoint::Distance(segmentPoints.back(), toPoint);
        segmentPoints.push_back(toPoint);
        lengths.push_back(length);
    }

    static float cubicCoordinateCalculation(float t, float p0, float p1, float p2, float p3) {
        float oneMinusT = 1 - t;
        float oneMinusTSquared = oneMinusT * oneMinusT;
        float oneMinusTCubed = oneMinusTSquared * oneMinusT;
        float tSquared = t * t;
        float tCubed = tSquared * t;
        return (oneMinusTCubed * p0) + (3 * oneMinusTSquared * t * p1)
                + (3 * oneMinusT * tSquared * p2) + (tCubed * p3);
    }

    static SkPoint cubicBezierCalculation(float t, const SkPoint* points) {
        float x = cubicCoordinateCalculation(t, points[0].x(), points[1].x(),
            points[2].x(), points[3].x());
        float y = cubicCoordinateCalculation(t, points[0].y(), points[1].y(),
            points[2].y(), points[3].y());
        return SkPoint::Make(x, y);
    }

    static float quadraticCoordinateCalculation(float t, float p0, float p1, float p2) {
        float oneMinusT = 1 - t;
        return oneMinusT * ((oneMinusT * p0) + (t * p1)) + t * ((oneMinusT * p1) + (t * p2));
    }

    static SkPoint quadraticBezierCalculation(float t, const SkPoint* points) {
        float x = quadraticCoordinateCalculation(t, points[0].x(), points[1].x(), points[2].x());
        float y = quadraticCoordinateCalculation(t, points[0].y(), points[1].y(), points[2].y());
        return SkPoint::Make(x, y);
    }

    // Subdivide a section of the Bezier curve, set the mid-point and the mid-t value.
    // Returns true if further subdivision is necessary as defined by errorSquared.
    static bool subdividePoints(const SkPoint* points, bezierCalculation bezierFunction,
            float t0, const SkPoint &p0, float t1, const SkPoint &p1,
            float& midT, SkPoint &midPoint, float errorSquared) {
        midT = (t1 + t0) / 2;
        float midX = (p1.x() + p0.x()) / 2;
        float midY = (p1.y() + p0.y()) / 2;

        midPoint = (*bezierFunction)(midT, points);
        float xError = midPoint.x() - midX;
        float yError = midPoint.y() - midY;
        float midErrorSquared = (xError * xError) + (yError * yError);
        return midErrorSquared > errorSquared;
    }

    // Divides Bezier curves until linear interpolation is very close to accurate, using
    // errorSquared as a metric. Cubic Bezier curves can have an inflection point that improperly
    // short-circuit subdivision. If you imagine an S shape, the top and bottom points being the
    // starting and end points, linear interpolation would mark the center where the curve places
    // the point. It is clearly not the case that we can linearly interpolate at that point.
    // doubleCheckDivision forces a second examination between subdivisions to ensure that linear
    // interpolation works.
    static void addBezier(const SkPoint* points,
            bezierCalculation bezierFunction, std::vector<SkPoint>& segmentPoints,
            std::vector<float>& lengths, float errorSquared, bool doubleCheckDivision) {
        typedef std::map<float, SkPoint> PointMap;
        PointMap tToPoint;

        tToPoint[0] = (*bezierFunction)(0, points);
        tToPoint[1] = (*bezierFunction)(1, points);

        PointMap::iterator iter = tToPoint.begin();
        PointMap::iterator next = iter;
        ++next;
        while (next != tToPoint.end()) {
            bool needsSubdivision = true;
            SkPoint midPoint;
            do {
                float midT;
                needsSubdivision = subdividePoints(points, bezierFunction, iter->first,
                    iter->second, next->first, next->second, midT, midPoint, errorSquared);
                if (!needsSubdivision && doubleCheckDivision) {
                    SkPoint quarterPoint;
                    float quarterT;
                    needsSubdivision = subdividePoints(points, bezierFunction, iter->first,
                        iter->second, midT, midPoint, quarterT, quarterPoint, errorSquared);
                    if (needsSubdivision) {
                        // Found an inflection point. No need to double-check.
                        doubleCheckDivision = false;
                    }
                }
                if (needsSubdivision) {
                    next = tToPoint.insert(iter, PointMap::value_type(midT, midPoint));
                }
            } while (needsSubdivision);
            iter = next;
            next++;
        }

        // Now that each division can use linear interpolation with less than the allowed error
        for (iter = tToPoint.begin(); iter != tToPoint.end(); ++iter) {
            addLine(segmentPoints, lengths, iter->second);
        }
    }

    static void createVerbSegments(SkPath::Verb verb, const SkPoint* points,
        std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths, float errorSquared) {
        switch (verb) {
            case SkPath::kMove_Verb:
                addMove(segmentPoints, lengths, points[0]);
                break;
            case SkPath::kClose_Verb:
                addLine(segmentPoints, lengths, points[0]);
                break;
            case SkPath::kLine_Verb:
                addLine(segmentPoints, lengths, points[1]);
                break;
            case SkPath::kQuad_Verb:
                addBezier(points, quadraticBezierCalculation, segmentPoints, lengths,
                    errorSquared, false);
                break;
            case SkPath::kCubic_Verb:
                addBezier(points, cubicBezierCalculation, segmentPoints, lengths,
                    errorSquared, true);
                break;
            default:
                // Leave element as NULL, Conic sections are not supported.
                break;
        }
    }

    // Returns a float[] with each point along the path represented by 3 floats
    // * fractional length along the path that the point resides
    // * x coordinate
    // * y coordinate
    // Note that more than one point may have the same length along the path in
    // the case of a move.
    // NULL can be returned if the Path is empty.
    static jfloatArray approximate(JNIEnv* env, jclass, jlong pathHandle, float acceptableError)
    {
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkASSERT(path);
        SkPath::Iter pathIter(*path, false);
        SkPath::Verb verb;
        SkPoint points[4];
        std::vector<SkPoint> segmentPoints;
        std::vector<float> lengths;
        float errorSquared = acceptableError * acceptableError;

        while ((verb = pathIter.next(points)) != SkPath::kDone_Verb) {
            createVerbSegments(verb, points, segmentPoints, lengths, errorSquared);
        }

        if (segmentPoints.empty()) {
            return NULL;
        }

        size_t numPoints = segmentPoints.size();
        size_t approximationArraySize = numPoints * 3;

        float* approximation = new float[approximationArraySize];
        float totalLength = lengths.back();

        int approximationIndex = 0;
        for (size_t i = 0; i < numPoints; i++) {
            const SkPoint& point = segmentPoints[i];
            approximation[approximationIndex++] = lengths[i] / totalLength;
            approximation[approximationIndex++] = point.x();
            approximation[approximationIndex++] = point.y();
        }

        jfloatArray result = env->NewFloatArray(approximationArraySize);
        env->SetFloatArrayRegion(result, 0, approximationArraySize, approximation);
        delete[] approximation;
        return result;
    }
};

static JNINativeMethod methods[] = {
    {"finalizer", "(J)V", (void*) SkPathGlue::finalizer},
    {"init1","()J", (void*) SkPathGlue::init1},
    {"init2","(J)J", (void*) SkPathGlue::init2},
    {"native_reset","(J)V", (void*) SkPathGlue::reset},
    {"native_rewind","(J)V", (void*) SkPathGlue::rewind},
    {"native_set","(JJ)V", (void*) SkPathGlue::assign},
    {"native_isConvex","(J)Z", (void*) SkPathGlue::isConvex},
    {"native_getFillType","(J)I", (void*) SkPathGlue::getFillType},
    {"native_setFillType","(JI)V", (void*) SkPathGlue::setFillType},
    {"native_isEmpty","(J)Z", (void*) SkPathGlue::isEmpty},
    {"native_isRect","(JLandroid/graphics/RectF;)Z", (void*) SkPathGlue::isRect},
    {"native_computeBounds","(JLandroid/graphics/RectF;)V", (void*) SkPathGlue::computeBounds},
    {"native_incReserve","(JI)V", (void*) SkPathGlue::incReserve},
    {"native_moveTo","(JFF)V", (void*) SkPathGlue::moveTo__FF},
    {"native_rMoveTo","(JFF)V", (void*) SkPathGlue::rMoveTo},
    {"native_lineTo","(JFF)V", (void*) SkPathGlue::lineTo__FF},
    {"native_rLineTo","(JFF)V", (void*) SkPathGlue::rLineTo},
    {"native_quadTo","(JFFFF)V", (void*) SkPathGlue::quadTo__FFFF},
    {"native_rQuadTo","(JFFFF)V", (void*) SkPathGlue::rQuadTo},
    {"native_cubicTo","(JFFFFFF)V", (void*) SkPathGlue::cubicTo__FFFFFF},
    {"native_rCubicTo","(JFFFFFF)V", (void*) SkPathGlue::rCubicTo},
    {"native_arcTo","(JLandroid/graphics/RectF;FFZ)V", (void*) SkPathGlue::arcTo},
    {"native_close","(J)V", (void*) SkPathGlue::close},
    {"native_addRect","(JLandroid/graphics/RectF;I)V", (void*) SkPathGlue::addRect__RectFI},
    {"native_addRect","(JFFFFI)V", (void*) SkPathGlue::addRect__FFFFI},
    {"native_addOval","(JLandroid/graphics/RectF;I)V", (void*) SkPathGlue::addOval},
    {"native_addCircle","(JFFFI)V", (void*) SkPathGlue::addCircle},
    {"native_addArc","(JLandroid/graphics/RectF;FF)V", (void*) SkPathGlue::addArc},
    {"native_addRoundRect","(JLandroid/graphics/RectF;FFI)V", (void*) SkPathGlue::addRoundRectXY},
    {"native_addRoundRect","(JLandroid/graphics/RectF;[FI)V", (void*) SkPathGlue::addRoundRect8},
    {"native_addPath","(JJFF)V", (void*) SkPathGlue::addPath__PathFF},
    {"native_addPath","(JJ)V", (void*) SkPathGlue::addPath__Path},
    {"native_addPath","(JJJ)V", (void*) SkPathGlue::addPath__PathMatrix},
    {"native_offset","(JFFJ)V", (void*) SkPathGlue::offset__FFPath},
    {"native_offset","(JFF)V", (void*) SkPathGlue::offset__FF},
    {"native_setLastPoint","(JFF)V", (void*) SkPathGlue::setLastPoint},
    {"native_transform","(JJJ)V", (void*) SkPathGlue::transform__MatrixPath},
    {"native_transform","(JJ)V", (void*) SkPathGlue::transform__Matrix},
    {"native_op","(JJIJ)Z", (void*) SkPathGlue::op},
    {"native_approximate", "(JF)[F", (void*) SkPathGlue::approximate},
};

int register_android_graphics_Path(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/Path", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
