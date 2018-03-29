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
#include "core_jni_helpers.h"

#include "SkPath.h"
#include "SkPathOps.h"
#include "SkGeometry.h" // WARNING: Internal Skia Header

#include <Caches.h>
#include <vector>
#include <map>

namespace android {

class SkPathGlue {
public:

    static void finalizer(SkPath* obj) {
        // Purge entries from the HWUI path cache if this path's data is unique
        if (obj->unique() && android::uirenderer::Caches::hasInstance()) {
            android::uirenderer::Caches::getInstance().pathCache.removeDeferred(obj);
        }
        delete obj;
    }

    // ---------------- Regular JNI -----------------------------

    static jlong init(JNIEnv* env, jclass clazz) {
        return reinterpret_cast<jlong>(new SkPath());
    }

    static jlong init_Path(JNIEnv* env, jclass clazz, jlong valHandle) {
        SkPath* val = reinterpret_cast<SkPath*>(valHandle);
        return reinterpret_cast<jlong>(new SkPath(*val));
    }

    static jlong getFinalizer(JNIEnv* env, jclass clazz) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&finalizer));
    }

    static void set(JNIEnv* env, jclass clazz, jlong dstHandle, jlong srcHandle) {
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        const SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        *dst = *src;
    }

    static void computeBounds(JNIEnv* env, jclass clazz, jlong objHandle, jobject jbounds) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        const SkRect& bounds = obj->getBounds();
        GraphicsJNI::rect_to_jrectf(bounds, env, jbounds);
    }

    static void incReserve(JNIEnv* env, jclass clazz, jlong objHandle, jint extraPtCount) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->incReserve(extraPtCount);
    }

    static void moveTo__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->moveTo(x, y);
    }

    static void rMoveTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rMoveTo(dx, dy);
    }

    static void lineTo__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->lineTo(x, y);
    }

    static void rLineTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rLineTo(dx, dy);
    }

    static void quadTo__FFFF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->quadTo(x1, y1, x2, y2);
    }

    static void rQuadTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx1, jfloat dy1,
            jfloat dx2, jfloat dy2) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rQuadTo(dx1, dy1, dx2, dy2);
    }

    static void cubicTo__FFFFFF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->cubicTo(x1, y1, x2, y2, x3, y3);
    }

    static void rCubicTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rCubicTo(x1, y1, x2, y2, x3, y3);
    }

    static void arcTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
            jfloat right, jfloat bottom, jfloat startAngle, jfloat sweepAngle,
            jboolean forceMoveTo) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        obj->arcTo(oval, startAngle, sweepAngle, forceMoveTo);
    }

    static void close(JNIEnv* env, jclass clazz, jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->close();
    }

    static void addRect(JNIEnv* env, jclass clazz, jlong objHandle,
            jfloat left, jfloat top, jfloat right, jfloat bottom, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        obj->addRect(left, top, right, bottom, dir);
    }

    static void addOval(JNIEnv* env, jclass clazz, jlong objHandle,
            jfloat left, jfloat top, jfloat right, jfloat bottom, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        obj->addOval(oval, dir);
    }

    static void addCircle(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y,
            jfloat radius, jint dirHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        obj->addCircle(x, y, radius, dir);
    }

    static void addArc(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
            jfloat right, jfloat bottom, jfloat startAngle, jfloat sweepAngle) {
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->addArc(oval, startAngle, sweepAngle);
    }

    static void addRoundRectXY(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
            jfloat right, jfloat bottom, jfloat rx, jfloat ry, jint dirHandle) {
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        obj->addRoundRect(rect, rx, ry, dir);
    }

    static void addRoundRect8(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
                jfloat right, jfloat bottom, jfloatArray array, jint dirHandle) {
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath::Direction dir = static_cast<SkPath::Direction>(dirHandle);
        AutoJavaFloatArray  afa(env, array, 8);
#ifdef SK_SCALAR_IS_FLOAT
        const float* src = afa.ptr();
#else
        #error Need to convert float array to SkScalar array before calling the following function.
#endif
        obj->addRoundRect(rect, src, dir);
    }

    static void addPath__PathFF(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle,
            jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        obj->addPath(*src, dx, dy);
    }

    static void addPath__Path(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        obj->addPath(*src);
    }

    static void addPath__PathMatrix(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle,
            jlong matrixHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkPath* src = reinterpret_cast<SkPath*>(srcHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        obj->addPath(*src, *matrix);
    }

    static void offset__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->offset(dx, dy);
    }

    static void setLastPoint(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->setLastPt(dx, dy);
    }

    static void transform__MatrixPath(JNIEnv* env, jclass clazz, jlong objHandle, jlong matrixHandle,
            jlong dstHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        obj->transform(*matrix, dst);
    }

    static void transform__Matrix(JNIEnv* env, jclass clazz, jlong objHandle, jlong matrixHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        obj->transform(*matrix);
    }

    static jboolean op(JNIEnv* env, jclass clazz, jlong p1Handle, jlong p2Handle, jint opHandle,
            jlong rHandle) {
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

    static void createVerbSegments(const SkPath::Iter& pathIter, SkPath::Verb verb,
            const SkPoint* points, std::vector<SkPoint>& segmentPoints,
            std::vector<float>& lengths, float errorSquared, float errorConic) {
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
            case SkPath::kConic_Verb: {
                SkAutoConicToQuads converter;
                const SkPoint* quads = converter.computeQuads(
                        points, pathIter.conicWeight(), errorConic);
                for (int i = 0; i < converter.countQuads(); i++) {
                    // Note: offset each subsequent quad by 2, since end points are shared
                    const SkPoint* quad = quads + i * 2;
                    addBezier(quad, quadraticBezierCalculation, segmentPoints, lengths,
                        errorConic, false);
                }
                break;
            }
            default:
                static_assert(SkPath::kMove_Verb == 0
                                && SkPath::kLine_Verb == 1
                                && SkPath::kQuad_Verb == 2
                                && SkPath::kConic_Verb == 3
                                && SkPath::kCubic_Verb == 4
                                && SkPath::kClose_Verb == 5
                                && SkPath::kDone_Verb == 6,
                        "Path enum changed, new types may have been added.");
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
    static jfloatArray approximate(JNIEnv* env, jclass clazz, jlong pathHandle,
            float acceptableError) {
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkASSERT(path);
        SkPath::Iter pathIter(*path, false);
        SkPath::Verb verb;
        SkPoint points[4];
        std::vector<SkPoint> segmentPoints;
        std::vector<float> lengths;
        float errorSquared = acceptableError * acceptableError;
        float errorConic = acceptableError / 2; // somewhat arbitrary

        while ((verb = pathIter.next(points, false)) != SkPath::kDone_Verb) {
            createVerbSegments(pathIter, verb, points, segmentPoints, lengths,
                    errorSquared, errorConic);
        }

        if (segmentPoints.empty()) {
            int numVerbs = path->countVerbs();
            if (numVerbs == 1) {
                addMove(segmentPoints, lengths, path->getPoint(0));
            } else {
                // Invalid or empty path. Fall back to point(0,0)
                addMove(segmentPoints, lengths, SkPoint());
            }
        }

        float totalLength = lengths.back();
        if (totalLength == 0) {
            // Lone Move instructions should still be able to animate at the same value.
            segmentPoints.push_back(segmentPoints.back());
            lengths.push_back(1);
            totalLength = 1;
        }

        size_t numPoints = segmentPoints.size();
        size_t approximationArraySize = numPoints * 3;

        float* approximation = new float[approximationArraySize];

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

    // ---------------- @FastNative -----------------------------

    static jboolean isRect(JNIEnv* env, jclass clazz, jlong objHandle, jobject jrect) {
        SkRect rect;
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        jboolean result = obj->isRect(&rect);
        if (jrect) {
            GraphicsJNI::rect_to_jrectf(rect, env, jrect);
        }
        return result;
    }

    // ---------------- @CriticalNative -------------------------

    static void reset(jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->reset();
    }

    static void rewind(jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        obj->rewind();
    }

    static jboolean isEmpty(jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->isEmpty();
    }

    static jboolean isConvex(jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->isConvex();
    }

    static jint getFillType(jlong objHandle) {
        SkPath* obj = reinterpret_cast<SkPath*>(objHandle);
        return obj->getFillType();
    }

    static void setFillType(jlong pathHandle, jint ftHandle) {;
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkPath::FillType ft = static_cast<SkPath::FillType>(ftHandle);
        path->setFillType(ft);
    }
};

static const JNINativeMethod methods[] = {
    {"nInit","()J", (void*) SkPathGlue::init},
    {"nInit","(J)J", (void*) SkPathGlue::init_Path},
    {"nGetFinalizer", "()J", (void*) SkPathGlue::getFinalizer},
    {"nSet","(JJ)V", (void*) SkPathGlue::set},
    {"nComputeBounds","(JLandroid/graphics/RectF;)V", (void*) SkPathGlue::computeBounds},
    {"nIncReserve","(JI)V", (void*) SkPathGlue::incReserve},
    {"nMoveTo","(JFF)V", (void*) SkPathGlue::moveTo__FF},
    {"nRMoveTo","(JFF)V", (void*) SkPathGlue::rMoveTo},
    {"nLineTo","(JFF)V", (void*) SkPathGlue::lineTo__FF},
    {"nRLineTo","(JFF)V", (void*) SkPathGlue::rLineTo},
    {"nQuadTo","(JFFFF)V", (void*) SkPathGlue::quadTo__FFFF},
    {"nRQuadTo","(JFFFF)V", (void*) SkPathGlue::rQuadTo},
    {"nCubicTo","(JFFFFFF)V", (void*) SkPathGlue::cubicTo__FFFFFF},
    {"nRCubicTo","(JFFFFFF)V", (void*) SkPathGlue::rCubicTo},
    {"nArcTo","(JFFFFFFZ)V", (void*) SkPathGlue::arcTo},
    {"nClose","(J)V", (void*) SkPathGlue::close},
    {"nAddRect","(JFFFFI)V", (void*) SkPathGlue::addRect},
    {"nAddOval","(JFFFFI)V", (void*) SkPathGlue::addOval},
    {"nAddCircle","(JFFFI)V", (void*) SkPathGlue::addCircle},
    {"nAddArc","(JFFFFFF)V", (void*) SkPathGlue::addArc},
    {"nAddRoundRect","(JFFFFFFI)V", (void*) SkPathGlue::addRoundRectXY},
    {"nAddRoundRect","(JFFFF[FI)V", (void*) SkPathGlue::addRoundRect8},
    {"nAddPath","(JJFF)V", (void*) SkPathGlue::addPath__PathFF},
    {"nAddPath","(JJ)V", (void*) SkPathGlue::addPath__Path},
    {"nAddPath","(JJJ)V", (void*) SkPathGlue::addPath__PathMatrix},
    {"nOffset","(JFF)V", (void*) SkPathGlue::offset__FF},
    {"nSetLastPoint","(JFF)V", (void*) SkPathGlue::setLastPoint},
    {"nTransform","(JJJ)V", (void*) SkPathGlue::transform__MatrixPath},
    {"nTransform","(JJ)V", (void*) SkPathGlue::transform__Matrix},
    {"nOp","(JJIJ)Z", (void*) SkPathGlue::op},
    {"nApproximate", "(JF)[F", (void*) SkPathGlue::approximate},

    // ------- @FastNative below here ----------------------
    {"nIsRect","(JLandroid/graphics/RectF;)Z", (void*) SkPathGlue::isRect},

    // ------- @CriticalNative below here ------------------
    {"nReset","(J)V", (void*) SkPathGlue::reset},
    {"nRewind","(J)V", (void*) SkPathGlue::rewind},
    {"nIsEmpty","(J)Z", (void*) SkPathGlue::isEmpty},
    {"nIsConvex","(J)Z", (void*) SkPathGlue::isConvex},
    {"nGetFillType","(J)I", (void*) SkPathGlue::getFillType},
    {"nSetFillType","(JI)V", (void*) SkPathGlue::setFillType},
};

int register_android_graphics_Path(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/Path", methods, NELEM(methods));

    static_assert(0  == SkPath::kCW_Direction,  "direction_mismatch");
    static_assert(1  == SkPath::kCCW_Direction, "direction_mismatch");
}

}
