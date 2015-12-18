/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.util;

import android.annotation.NonNull;

import java.awt.geom.CubicCurve2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.util.ArrayList;

import com.google.android.collect.Lists;

/**
 * Class that returns iterators for a given path. These iterators are lightweight and can be reused
 * multiple times to iterate over the path.
 */
public class CachedPathIteratorFactory {
    /*
     * A few conventions used in the code:
     * Coordinates or coords arrays store segment coordinates. They use the same format as
     * PathIterator#currentSegment coordinates array.
     * float arrays store always points where the first element is X and the second is Y.
     */

    // This governs how accurate the approximation of the Path is.
    private static final float PRECISION = 0.002f;

    private final int mWindingRule;
    private final int[] mTypes;
    private final float[][] mCoordinates;
    private final float[] mSegmentsLength;
    private final float mTotalLength;

    public CachedPathIteratorFactory(@NonNull PathIterator iterator) {
        mWindingRule = iterator.getWindingRule();

        ArrayList<Integer> typesArray = Lists.newArrayList();
        ArrayList<float[]> pointsArray = Lists.newArrayList();
        float[] points = new float[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(points);
            int nPoints = getNumberOfPoints(type) * 2; // 2 coordinates per point

            typesArray.add(type);
            float[] itemPoints = new float[nPoints];
            System.arraycopy(points, 0, itemPoints, 0, nPoints);
            pointsArray.add(itemPoints);
            iterator.next();
        }

        mTypes = new int[typesArray.size()];
        mCoordinates = new float[mTypes.length][];
        for (int i = 0; i < typesArray.size(); i++) {
            mTypes[i] = typesArray.get(i);
            mCoordinates[i] = pointsArray.get(i);
        }

        // Do measurement
        mSegmentsLength = new float[mTypes.length];

        // Curves that we can reuse to estimate segments length
        CubicCurve2D.Float cubicCurve = new CubicCurve2D.Float();
        QuadCurve2D.Float quadCurve = new QuadCurve2D.Float();
        float lastX = 0;
        float lastY = 0;
        float totalLength = 0;
        for (int i = 0; i < mTypes.length; i++) {
            switch (mTypes[i]) {
                case PathIterator.SEG_CUBICTO:
                    cubicCurve.setCurve(lastX, lastY,
                            mCoordinates[i][0], mCoordinates[i][1], mCoordinates[i][2],
                            mCoordinates[i][3], lastX = mCoordinates[i][4],
                            lastY = mCoordinates[i][5]);
                    mSegmentsLength[i] =
                            getFlatPathLength(cubicCurve.getPathIterator(null, PRECISION));
                    break;
                case PathIterator.SEG_QUADTO:
                    quadCurve.setCurve(lastX, lastY, mCoordinates[i][0], mCoordinates[i][1],
                            lastX = mCoordinates[i][2], lastY = mCoordinates[i][3]);
                    mSegmentsLength[i] =
                            getFlatPathLength(quadCurve.getPathIterator(null, PRECISION));
                    break;
                case PathIterator.SEG_CLOSE:
                    mSegmentsLength[i] = (float) Point2D.distance(lastX, lastY,
                            lastX = mCoordinates[0][0],
                            lastY = mCoordinates[0][1]);
                    mCoordinates[i] = new float[2];
                    // We convert a SEG_CLOSE segment to a SEG_LINETO so we do not have to worry
                    // about this special case in the rest of the code.
                    mTypes[i] = PathIterator.SEG_LINETO;
                    mCoordinates[i][0] = mCoordinates[0][0];
                    mCoordinates[i][1] = mCoordinates[0][1];
                    break;
                case PathIterator.SEG_MOVETO:
                    mSegmentsLength[i] = 0;
                    lastX = mCoordinates[i][0];
                    lastY = mCoordinates[i][1];
                    break;
                case PathIterator.SEG_LINETO:
                    mSegmentsLength[i] = (float) Point2D.distance(lastX, lastY, mCoordinates[i][0],
                            mCoordinates[i][1]);
                    lastX = mCoordinates[i][0];
                    lastY = mCoordinates[i][1];
                default:
            }
            totalLength += mSegmentsLength[i];
        }

        mTotalLength = totalLength;
    }

    private static void quadCurveSegment(float[] coords, float t0, float t1) {
        // Calculate X and Y at 0.5 (We'll use this to reconstruct the control point later)
        float mt = t0 + (t1 - t0) / 2;
        float mu = 1 - mt;
        float mx = mu * mu * coords[0] + 2 * mu * mt * coords[2] + mt * mt * coords[4];
        float my = mu * mu * coords[1] + 2 * mu * mt * coords[3] + mt * mt * coords[5];

        float u0 = 1 - t0;
        float u1 = 1 - t1;

        // coords at t0
        coords[0] = coords[0] * u0 * u0 + coords[2] * 2 * t0 * u0 + coords[4] * t0 * t0;
        coords[1] = coords[1] * u0 * u0 + coords[3] * 2 * t0 * u0 + coords[5] * t0 * t0;

        // coords at t1
        coords[4] = coords[0] * u1 * u1 + coords[2] * 2 * t1 * u1 + coords[4] * t1 * t1;
        coords[5] = coords[1] * u1 * u1 + coords[3] * 2 * t1 * u1 + coords[5] * t1 * t1;

        // estimated control point at t'=0.5
        coords[2] = 2 * mx - coords[0] / 2 - coords[4] / 2;
        coords[3] = 2 * my - coords[1] / 2 - coords[5] / 2;
    }

    private static void cubicCurveSegment(float[] coords, float t0, float t1) {
        // http://stackoverflow.com/questions/11703283/cubic-bezier-curve-segment
        float u0 = 1 - t0;
        float u1 = 1 - t1;

        // Calculate the points at t0 and t1 for the quadratic curves formed by (P0, P1, P2) and
        // (P1, P2, P3)
        float qxa = coords[0] * u0 * u0 + coords[2] * 2 * t0 * u0 + coords[4] * t0 * t0;
        float qxb = coords[0] * u1 * u1 + coords[2] * 2 * t1 * u1 + coords[4] * t1 * t1;
        float qxc = coords[2] * u0 * u0 + coords[4] * 2 * t0 * u0 + coords[6] * t0 * t0;
        float qxd = coords[2] * u1 * u1 + coords[4] * 2 * t1 * u1 + coords[6] * t1 * t1;

        float qya = coords[1] * u0 * u0 + coords[3] * 2 * t0 * u0 + coords[5] * t0 * t0;
        float qyb = coords[1] * u1 * u1 + coords[3] * 2 * t1 * u1 + coords[5] * t1 * t1;
        float qyc = coords[3] * u0 * u0 + coords[5] * 2 * t0 * u0 + coords[7] * t0 * t0;
        float qyd = coords[3] * u1 * u1 + coords[5] * 2 * t1 * u1 + coords[7] * t1 * t1;

        // Linear interpolation
        coords[0] = qxa * u0 + qxc * t0;
        coords[1] = qya * u0 + qyc * t0;

        coords[2] = qxa * u1 + qxc * t1;
        coords[3] = qya * u1 + qyc * t1;

        coords[4] = qxb * u0 + qxd * t0;
        coords[5] = qyb * u0 + qyd * t0;

        coords[6] = qxb * u1 + qxd * t1;
        coords[7] = qyb * u1 + qyd * t1;
    }

    /**
     * Returns the end point of a given segment
     *
     * @param type the segment type
     * @param coords the segment coordinates array
     * @param point the return array where the point will be stored
     */
    private static void getShapeEndPoint(int type, @NonNull float[] coords, @NonNull float[]
            point) {
        // start index of the end point for the segment type
        int pointIndex = (getNumberOfPoints(type) - 1) * 2;
        point[0] = coords[pointIndex];
        point[1] = coords[pointIndex + 1];
    }

    /**
     * Returns the number of points stored in a coordinates array for the given segment type.
     */
    private static int getNumberOfPoints(int segmentType) {
        switch (segmentType) {
            case PathIterator.SEG_QUADTO:
                return 2;
            case PathIterator.SEG_CUBICTO:
                return 3;
            case PathIterator.SEG_CLOSE:
                return 0;
            default:
                return 1;
        }
    }

    /**
     * Returns the estimated length of a flat path. If the passed path is not flat (i.e. contains a
     * segment that is not {@link PathIterator#SEG_CLOSE}, {@link PathIterator#SEG_MOVETO} or {@link
     * PathIterator#SEG_LINETO} this method will fail.
     */
    private static float getFlatPathLength(@NonNull PathIterator iterator) {
        float segment[] = new float[6];
        float totalLength = 0;
        float[] previousPoint = new float[2];
        boolean isFirstPoint = true;

        while (!iterator.isDone()) {
            int type = iterator.currentSegment(segment);
            assert type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE || type ==
                    PathIterator.SEG_MOVETO;

            // MoveTo shouldn't affect the length
            if (!isFirstPoint && type != PathIterator.SEG_MOVETO) {
                totalLength += Point2D.distance(previousPoint[0], previousPoint[1], segment[0],
                        segment[1]);
            } else {
                isFirstPoint = false;
            }
            previousPoint[0] = segment[0];
            previousPoint[1] = segment[1];
            iterator.next();
        }

        return totalLength;
    }

    /**
     * Returns the estimated position along a path of the given length.
     */
    private void getPointAtLength(int type, @NonNull float[] coords, float lastX, float
            lastY, float t, @NonNull float[] point) {
        if (type == PathIterator.SEG_LINETO) {
            point[0] = lastX + (coords[0] - lastX) * t;
            point[1] = lastY + (coords[1] - lastY) * t;
            // Return here, since we do not need a shape to estimate
            return;
        }

        float[] curve = new float[8];
        int lastPointIndex = (getNumberOfPoints(type) - 1) * 2;

        System.arraycopy(coords, 0, curve, 2, coords.length);
        curve[0] = lastX;
        curve[1] = lastY;
        if (type == PathIterator.SEG_CUBICTO) {
            cubicCurveSegment(curve, 0f, t);
        } else {
            quadCurveSegment(curve, 0f, t);
        }

        point[0] = curve[2 + lastPointIndex];
        point[1] = curve[2 + lastPointIndex + 1];
    }

    public CachedPathIterator iterator() {
        return new CachedPathIterator();
    }

    /**
     * Class that allows us to iterate over a path multiple times
     */
    public class CachedPathIterator implements PathIterator {
        private int mNextIndex;

        /**
         * Current segment type.
         *
         * @see PathIterator
         */
        private int mCurrentType;

        /**
         * Stores the coordinates array of the current segment. The number of points stored depends
         * on the segment type.
         *
         * @see PathIterator
         */
        private float[] mCurrentCoords = new float[6];
        private float mCurrentSegmentLength;

        /**
         * Current segment length offset. When asking for the length of the current segment, the
         * length will be reduced by this amount. This is useful when we are only using portions of
         * the segment.
         *
         * @see #jumpToSegment(float)
         */
        private float mOffsetLength;

        /** Point where the current segment started */
        private float[] mLastPoint = new float[2];
        private boolean isIteratorDone;

        private CachedPathIterator() {
            next();
        }

        public float getCurrentSegmentLength() {
            return mCurrentSegmentLength;
        }

        @Override
        public int getWindingRule() {
            return mWindingRule;
        }

        @Override
        public boolean isDone() {
            return isIteratorDone;
        }

        @Override
        public void next() {
            if (mNextIndex >= mTypes.length) {
                isIteratorDone = true;
                return;
            }

            if (mNextIndex >= 1) {
                // We've already called next() once so there is a previous segment in this path.
                // We want to get the coordinates where the path ends.
                getShapeEndPoint(mCurrentType, mCurrentCoords, mLastPoint);
            } else {
                // This is the first segment, no previous point so initialize to 0, 0
                mLastPoint[0] = mLastPoint[1] = 0f;
            }
            mCurrentType = mTypes[mNextIndex];
            mCurrentSegmentLength = mSegmentsLength[mNextIndex] - mOffsetLength;

            if (mOffsetLength > 0f && (mCurrentType == SEG_CUBICTO || mCurrentType == SEG_QUADTO)) {
                // We need to skip part of the start of the current segment (because
                // mOffsetLength > 0)
                float[] points = new float[8];

                if (mNextIndex < 1) {
                    points[0] = points[1] = 0f;
                } else {
                    getShapeEndPoint(mTypes[mNextIndex - 1], mCoordinates[mNextIndex - 1], points);
                }

                System.arraycopy(mCoordinates[mNextIndex], 0, points, 2,
                        mCoordinates[mNextIndex].length);
                float t0 = (mSegmentsLength[mNextIndex] - mCurrentSegmentLength) /
                        mSegmentsLength[mNextIndex];
                if (mCurrentType == SEG_CUBICTO) {
                    cubicCurveSegment(points, t0, 1f);
                } else {
                    quadCurveSegment(points, t0, 1f);
                }
                System.arraycopy(points, 2, mCurrentCoords, 0, mCoordinates[mNextIndex].length);
            } else {
                System.arraycopy(mCoordinates[mNextIndex], 0, mCurrentCoords, 0,
                        mCoordinates[mNextIndex].length);
            }

            mOffsetLength = 0f;
            mNextIndex++;
        }

        @Override
        public int currentSegment(float[] coords) {
            System.arraycopy(mCurrentCoords, 0, coords, 0, getNumberOfPoints(mCurrentType) * 2);
            return mCurrentType;
        }

        @Override
        public int currentSegment(double[] coords) {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns the point where the current segment ends
         */
        public void getCurrentSegmentEnd(float[] point) {
            point[0] = mLastPoint[0];
            point[1] = mLastPoint[1];
        }

        /**
         * Restarts the iterator and jumps all the segments of this path up to the length value.
         */
        public void jumpToSegment(float length) {
            isIteratorDone = false;
            if (length <= 0f) {
                mNextIndex = 0;
                return;
            }

            float accLength = 0;
            float lastPoint[] = new float[2];
            for (mNextIndex = 0; mNextIndex < mTypes.length; mNextIndex++) {
                float segmentLength = mSegmentsLength[mNextIndex];
                if (accLength + segmentLength >= length && mTypes[mNextIndex] != SEG_MOVETO) {
                    float[] estimatedPoint = new float[2];
                    getPointAtLength(mTypes[mNextIndex],
                            mCoordinates[mNextIndex], lastPoint[0], lastPoint[1],
                            (length - accLength) / segmentLength,
                            estimatedPoint);

                    // This segment makes us go further than length so we go back one step,
                    // set a moveto and offset the length of the next segment by the length
                    // of this segment that we've already used.
                    mCurrentType = PathIterator.SEG_MOVETO;
                    mCurrentCoords[0] = estimatedPoint[0];
                    mCurrentCoords[1] = estimatedPoint[1];
                    mCurrentSegmentLength = 0;

                    // We need to offset next path length to account for the segment we've just
                    // skipped.
                    mOffsetLength = length - accLength;
                    return;
                }
                accLength += segmentLength;
                getShapeEndPoint(mTypes[mNextIndex], mCoordinates[mNextIndex], lastPoint);
            }
        }

        /**
         * Returns the current segment up to certain length. If the current segment is shorter than
         * length, then the whole segment is returned. The segment coordinates are copied into the
         * coords array.
         *
         * @return the segment type
         */
        public int currentSegment(@NonNull float[] coords, float length) {
            int type = currentSegment(coords);
            // If the length is greater than the current segment length, no need to find
            // the cut point. Same if this is a SEG_MOVETO.
            if (mCurrentSegmentLength <= length || type == SEG_MOVETO) {
                return type;
            }

            float t = length / getCurrentSegmentLength();

            // We find at which offset the end point is located within the coords array and set
            // a new end point to cut the segment short
            switch (type) {
                case SEG_CUBICTO:
                case SEG_QUADTO:
                    float[] curve = new float[8];
                    curve[0] = mLastPoint[0];
                    curve[1] = mLastPoint[1];
                    System.arraycopy(coords, 0, curve, 2, coords.length);
                    if (type == SEG_CUBICTO) {
                        cubicCurveSegment(curve, 0f, t);
                    } else {
                        quadCurveSegment(curve, 0f, t);
                    }
                    System.arraycopy(curve, 2, coords, 0, coords.length);
                    break;
                default:
                    float[] point = new float[2];
                    getPointAtLength(type, coords, mLastPoint[0], mLastPoint[1], t, point);
                    coords[0] = point[0];
                    coords[1] = point[1];
            }

            return type;
        }

        /**
         * Returns the total length of the path
         */
        public float getTotalLength() {
            return mTotalLength;
        }
    }
}
