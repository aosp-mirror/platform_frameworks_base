/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;

/**
 * Delegate implementing the native methods of android.graphics.Path
 *
 * Through the layoutlib_create tool, the original native methods of Path have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Path class.
 *
 * @see DelegateManager
 *
 */
public final class Path_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<Path_Delegate> sManager =
            new DelegateManager<Path_Delegate>(Path_Delegate.class);

    private static final float EPSILON = 1e-4f;

    // ---- delegate data ----
    private FillType mFillType = FillType.WINDING;
    private Path2D mPath = new Path2D.Double();

    private float mLastX = 0;
    private float mLastY = 0;

    // true if the path contains does not contain a curve or line.
    private boolean mCachedIsEmpty = true;

    // ---- Public Helper methods ----

    public static Path_Delegate getDelegate(long nPath) {
        return sManager.getDelegate(nPath);
    }

    public Path2D getJavaShape() {
        return mPath;
    }

    public void setJavaShape(Shape shape) {
        reset();
        mPath.append(shape, false /*connect*/);
    }

    public void reset() {
        mPath.reset();
    }

    public void setPathIterator(PathIterator iterator) {
        reset();
        mPath.append(iterator, false /*connect*/);
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long init1() {
        // create the delegate
        Path_Delegate newDelegate = new Path_Delegate();

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static long init2(long nPath) {
        // create the delegate
        Path_Delegate newDelegate = new Path_Delegate();

        // get the delegate to copy, which could be null if nPath is 0
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate != null) {
            newDelegate.set(pathDelegate);
        }

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static void native_reset(long nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.mPath.reset();
    }

    @LayoutlibDelegate
    /*package*/ static void native_rewind(long nPath) {
        // call out to reset since there's nothing to optimize in
        // terms of data structs.
        native_reset(nPath);
    }

    @LayoutlibDelegate
    /*package*/ static void native_set(long native_dst, long native_src) {
        Path_Delegate pathDstDelegate = sManager.getDelegate(native_dst);
        if (pathDstDelegate == null) {
            return;
        }

        Path_Delegate pathSrcDelegate = sManager.getDelegate(native_src);
        if (pathSrcDelegate == null) {
            return;
        }

        pathDstDelegate.set(pathSrcDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isConvex(long nPath) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Path.isConvex is not supported.", null, null);
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static int native_getFillType(long nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return 0;
        }

        return pathDelegate.mFillType.nativeInt;
    }

    @LayoutlibDelegate
    public static void native_setFillType(long nPath, int ft) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.setFillType(Path.sFillTypeArray[ft]);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isEmpty(long nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        return pathDelegate == null || pathDelegate.isEmpty();

    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isRect(long nPath, RectF rect) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return false;
        }

        // create an Area that can test if the path is a rect
        Area area = new Area(pathDelegate.mPath);
        if (area.isRectangular()) {
            if (rect != null) {
                pathDelegate.fillBounds(rect);
            }

            return true;
        }

        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_computeBounds(long nPath, RectF bounds) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.fillBounds(bounds);
    }

    @LayoutlibDelegate
    /*package*/ static void native_incReserve(long nPath, int extraPtCount) {
        // since we use a java2D path, there's no way to pre-allocate new points,
        // so we do nothing.
    }

    @LayoutlibDelegate
    /*package*/ static void native_moveTo(long nPath, float x, float y) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.moveTo(x, y);
    }

    @LayoutlibDelegate
    /*package*/ static void native_rMoveTo(long nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.rMoveTo(dx, dy);
    }

    @LayoutlibDelegate
    /*package*/ static void native_lineTo(long nPath, float x, float y) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.lineTo(x, y);
    }

    @LayoutlibDelegate
    /*package*/ static void native_rLineTo(long nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.rLineTo(dx, dy);
    }

    @LayoutlibDelegate
    /*package*/ static void native_quadTo(long nPath, float x1, float y1, float x2, float y2) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.quadTo(x1, y1, x2, y2);
    }

    @LayoutlibDelegate
    /*package*/ static void native_rQuadTo(long nPath, float dx1, float dy1, float dx2, float dy2) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.rQuadTo(dx1, dy1, dx2, dy2);
    }

    @LayoutlibDelegate
    /*package*/ static void native_cubicTo(long nPath, float x1, float y1,
            float x2, float y2, float x3, float y3) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.cubicTo(x1, y1, x2, y2, x3, y3);
    }

    @LayoutlibDelegate
    /*package*/ static void native_rCubicTo(long nPath, float x1, float y1,
            float x2, float y2, float x3, float y3) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.rCubicTo(x1, y1, x2, y2, x3, y3);
    }

    @LayoutlibDelegate
    /*package*/ static void native_arcTo(long nPath, float left, float top, float right,
            float bottom,
                    float startAngle, float sweepAngle, boolean forceMoveTo) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.arcTo(left, top, right, bottom, startAngle, sweepAngle, forceMoveTo);
    }

    @LayoutlibDelegate
    /*package*/ static void native_close(long nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.close();
    }

    @LayoutlibDelegate
    /*package*/ static void native_addRect(long nPath,
            float left, float top, float right, float bottom, int dir) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.addRect(left, top, right, bottom, dir);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addOval(long nPath, float left, float top, float right,
            float bottom, int dir) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.mPath.append(new Ellipse2D.Float(
                left, top, right - left, bottom - top), false);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addCircle(long nPath, float x, float y, float radius, int dir) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        // because x/y is the center of the circle, need to offset this by the radius
        pathDelegate.mPath.append(new Ellipse2D.Float(
                x - radius, y - radius, radius * 2, radius * 2), false);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addArc(long nPath, float left, float top, float right,
            float bottom, float startAngle, float sweepAngle) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        // because x/y is the center of the circle, need to offset this by the radius
        pathDelegate.mPath.append(new Arc2D.Float(
                left, top, right - left, bottom - top,
                -startAngle, -sweepAngle, Arc2D.OPEN), false);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addRoundRect(long nPath, float left, float top, float right,
            float bottom, float rx, float ry, int dir) {

        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.mPath.append(new RoundRectangle2D.Float(
                left, top, right - left, bottom - top, rx * 2, ry * 2), false);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addRoundRect(long nPath, float left, float top, float right,
            float bottom, float[] radii, int dir) {

        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        float[] cornerDimensions = new float[radii.length];
        for (int i = 0; i < radii.length; i++) {
            cornerDimensions[i] = 2 * radii[i];
        }
        pathDelegate.mPath.append(new RoundRectangle(left, top, right - left, bottom - top,
                cornerDimensions), false);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addPath(long nPath, long src, float dx, float dy) {
        addPath(nPath, src, AffineTransform.getTranslateInstance(dx, dy));
    }

    @LayoutlibDelegate
    /*package*/ static void native_addPath(long nPath, long src) {
        addPath(nPath, src, null /*transform*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_addPath(long nPath, long src, long matrix) {
        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(matrix);
        if (matrixDelegate == null) {
            return;
        }

        addPath(nPath, src, matrixDelegate.getAffineTransform());
    }

    @LayoutlibDelegate
    /*package*/ static void native_offset(long nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.offset(dx, dy);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setLastPoint(long nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        pathDelegate.mLastX = dx;
        pathDelegate.mLastY = dy;
    }

    @LayoutlibDelegate
    /*package*/ static void native_transform(long nPath, long matrix,
                                                long dst_path) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(matrix);
        if (matrixDelegate == null) {
            return;
        }

        // this can be null if dst_path is 0
        Path_Delegate dstDelegate = sManager.getDelegate(dst_path);

        pathDelegate.transform(matrixDelegate, dstDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static void native_transform(long nPath, long matrix) {
        native_transform(nPath, matrix, 0);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_op(long nPath1, long nPath2, int op, long result) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED, "Path.op() not supported", null);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void finalizer(long nPath) {
        sManager.removeJavaReferenceFor(nPath);
    }

    @LayoutlibDelegate
    /*package*/ static float[] native_approximate(long nPath, float error) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            return null;
        }
        // Get a FlatteningIterator
        PathIterator iterator = pathDelegate.getJavaShape().getPathIterator(null, error);

        float segment[] = new float[6];
        float totalLength = 0;
        ArrayList<Point2D.Float> points = new ArrayList<Point2D.Float>();
        Point2D.Float previousPoint = null;
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(segment);
            Point2D.Float currentPoint = new Point2D.Float(segment[0], segment[1]);
            // MoveTo shouldn't affect the length
            if (previousPoint != null && type != PathIterator.SEG_MOVETO) {
                totalLength += currentPoint.distance(previousPoint);
            }
            previousPoint = currentPoint;
            points.add(currentPoint);
            iterator.next();
        }

        int nPoints = points.size();
        float[] result = new float[nPoints * 3];
        previousPoint = null;
        for (int i = 0; i < nPoints; i++) {
            Point2D.Float point = points.get(i);
            float distance = previousPoint != null ? (float) previousPoint.distance(point) : .0f;
            result[i * 3] = distance / totalLength;
            result[i * 3 + 1] = point.x;
            result[i * 3 + 2] = point.y;

            totalLength += distance;
            previousPoint = point;
        }

        return result;
    }

    // ---- Private helper methods ----

    private void set(Path_Delegate delegate) {
        mPath.reset();
        setFillType(delegate.mFillType);
        mPath.append(delegate.mPath, false /*connect*/);
    }

    private void setFillType(FillType fillType) {
        mFillType = fillType;
        mPath.setWindingRule(getWindingRule(fillType));
    }

    /**
     * Returns the Java2D winding rules matching a given Android {@link FillType}.
     * @param type the android fill type
     * @return the matching java2d winding rule.
     */
    private static int getWindingRule(FillType type) {
        switch (type) {
            case WINDING:
            case INVERSE_WINDING:
                return GeneralPath.WIND_NON_ZERO;
            case EVEN_ODD:
            case INVERSE_EVEN_ODD:
                return GeneralPath.WIND_EVEN_ODD;
        }

        assert false;
        throw new IllegalArgumentException();
    }

    @NonNull
    private static Direction getDirection(int direction) {
        for (Direction d : Direction.values()) {
            if (direction == d.nativeInt) {
                return d;
            }
        }

        assert false;
        return null;
    }

    public static void addPath(long destPath, long srcPath, AffineTransform transform) {
        Path_Delegate destPathDelegate = sManager.getDelegate(destPath);
        if (destPathDelegate == null) {
            return;
        }

        Path_Delegate srcPathDelegate = sManager.getDelegate(srcPath);
        if (srcPathDelegate == null) {
            return;
        }

        if (transform != null) {
            destPathDelegate.mPath.append(
                    srcPathDelegate.mPath.getPathIterator(transform), false);
        } else {
            destPathDelegate.mPath.append(srcPathDelegate.mPath, false);
        }
    }


    /**
     * Returns whether the path already contains any points.
     * Note that this is different to
     * {@link #isEmpty} because if all elements are {@link PathIterator#SEG_MOVETO},
     * {@link #isEmpty} will return true while hasPoints will return false.
     */
    public boolean hasPoints() {
        return !mPath.getPathIterator(null).isDone();
    }

    /**
     * Returns whether the path is empty (contains no lines or curves).
     * @see Path#isEmpty
     */
    public boolean isEmpty() {
        if (!mCachedIsEmpty) {
            return false;
        }

        float[] coords = new float[6];
        mCachedIsEmpty = Boolean.TRUE;
        for (PathIterator it = mPath.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(coords);
            if (type != PathIterator.SEG_MOVETO) {
                // Once we know that the path is not empty, we do not need to check again unless
                // Path#reset is called.
                mCachedIsEmpty = false;
                return false;
            }
        }

        return true;
    }

    /**
     * Fills the given {@link RectF} with the path bounds.
     * @param bounds the RectF to be filled.
     */
    public void fillBounds(RectF bounds) {
        Rectangle2D rect = mPath.getBounds2D();
        bounds.left = (float)rect.getMinX();
        bounds.right = (float)rect.getMaxX();
        bounds.top = (float)rect.getMinY();
        bounds.bottom = (float)rect.getMaxY();
    }

    /**
     * Set the beginning of the next contour to the point (x,y).
     *
     * @param x The x-coordinate of the start of a new contour
     * @param y The y-coordinate of the start of a new contour
     */
    public void moveTo(float x, float y) {
        mPath.moveTo(mLastX = x, mLastY = y);
    }

    /**
     * Set the beginning of the next contour relative to the last point on the
     * previous contour. If there is no previous contour, this is treated the
     * same as moveTo().
     *
     * @param dx The amount to add to the x-coordinate of the end of the
     *           previous contour, to specify the start of a new contour
     * @param dy The amount to add to the y-coordinate of the end of the
     *           previous contour, to specify the start of a new contour
     */
    public void rMoveTo(float dx, float dy) {
        dx += mLastX;
        dy += mLastY;
        mPath.moveTo(mLastX = dx, mLastY = dy);
    }

    /**
     * Add a line from the last point to the specified point (x,y).
     * If no moveTo() call has been made for this contour, the first point is
     * automatically set to (0,0).
     *
     * @param x The x-coordinate of the end of a line
     * @param y The y-coordinate of the end of a line
     */
    public void lineTo(float x, float y) {
        if (!hasPoints()) {
            mPath.moveTo(mLastX = 0, mLastY = 0);
        }
        mPath.lineTo(mLastX = x, mLastY = y);
    }

    /**
     * Same as lineTo, but the coordinates are considered relative to the last
     * point on this contour. If there is no previous point, then a moveTo(0,0)
     * is inserted automatically.
     *
     * @param dx The amount to add to the x-coordinate of the previous point on
     *           this contour, to specify a line
     * @param dy The amount to add to the y-coordinate of the previous point on
     *           this contour, to specify a line
     */
    public void rLineTo(float dx, float dy) {
        if (!hasPoints()) {
            mPath.moveTo(mLastX = 0, mLastY = 0);
        }

        if (Math.abs(dx) < EPSILON && Math.abs(dy) < EPSILON) {
            // The delta is so small that this shouldn't generate a line
            return;
        }

        dx += mLastX;
        dy += mLastY;
        mPath.lineTo(mLastX = dx, mLastY = dy);
    }

    /**
     * Add a quadratic bezier from the last point, approaching control point
     * (x1,y1), and ending at (x2,y2). If no moveTo() call has been made for
     * this contour, the first point is automatically set to (0,0).
     *
     * @param x1 The x-coordinate of the control point on a quadratic curve
     * @param y1 The y-coordinate of the control point on a quadratic curve
     * @param x2 The x-coordinate of the end point on a quadratic curve
     * @param y2 The y-coordinate of the end point on a quadratic curve
     */
    public void quadTo(float x1, float y1, float x2, float y2) {
        mPath.quadTo(x1, y1, mLastX = x2, mLastY = y2);
    }

    /**
     * Same as quadTo, but the coordinates are considered relative to the last
     * point on this contour. If there is no previous point, then a moveTo(0,0)
     * is inserted automatically.
     *
     * @param dx1 The amount to add to the x-coordinate of the last point on
     *            this contour, for the control point of a quadratic curve
     * @param dy1 The amount to add to the y-coordinate of the last point on
     *            this contour, for the control point of a quadratic curve
     * @param dx2 The amount to add to the x-coordinate of the last point on
     *            this contour, for the end point of a quadratic curve
     * @param dy2 The amount to add to the y-coordinate of the last point on
     *            this contour, for the end point of a quadratic curve
     */
    public void rQuadTo(float dx1, float dy1, float dx2, float dy2) {
        if (!hasPoints()) {
            mPath.moveTo(mLastX = 0, mLastY = 0);
        }
        dx1 += mLastX;
        dy1 += mLastY;
        dx2 += mLastX;
        dy2 += mLastY;
        mPath.quadTo(dx1, dy1, mLastX = dx2, mLastY = dy2);
    }

    /**
     * Add a cubic bezier from the last point, approaching control points
     * (x1,y1) and (x2,y2), and ending at (x3,y3). If no moveTo() call has been
     * made for this contour, the first point is automatically set to (0,0).
     *
     * @param x1 The x-coordinate of the 1st control point on a cubic curve
     * @param y1 The y-coordinate of the 1st control point on a cubic curve
     * @param x2 The x-coordinate of the 2nd control point on a cubic curve
     * @param y2 The y-coordinate of the 2nd control point on a cubic curve
     * @param x3 The x-coordinate of the end point on a cubic curve
     * @param y3 The y-coordinate of the end point on a cubic curve
     */
    public void cubicTo(float x1, float y1, float x2, float y2,
                        float x3, float y3) {
        if (!hasPoints()) {
            mPath.moveTo(0, 0);
        }
        mPath.curveTo(x1, y1, x2, y2, mLastX = x3, mLastY = y3);
    }

    /**
     * Same as cubicTo, but the coordinates are considered relative to the
     * current point on this contour. If there is no previous point, then a
     * moveTo(0,0) is inserted automatically.
     */
    public void rCubicTo(float dx1, float dy1, float dx2, float dy2,
                         float dx3, float dy3) {
        if (!hasPoints()) {
            mPath.moveTo(mLastX = 0, mLastY = 0);
        }
        dx1 += mLastX;
        dy1 += mLastY;
        dx2 += mLastX;
        dy2 += mLastY;
        dx3 += mLastX;
        dy3 += mLastY;
        mPath.curveTo(dx1, dy1, dx2, dy2, mLastX = dx3, mLastY = dy3);
    }

    /**
     * Append the specified arc to the path as a new contour. If the start of
     * the path is different from the path's current last point, then an
     * automatic lineTo() is added to connect the current contour to the
     * start of the arc. However, if the path is empty, then we call moveTo()
     * with the first point of the arc. The sweep angle is tread mod 360.
     *
     * @param left        The left of oval defining shape and size of the arc
     * @param top         The top of oval defining shape and size of the arc
     * @param right       The right of oval defining shape and size of the arc
     * @param bottom      The bottom of oval defining shape and size of the arc
     * @param startAngle  Starting angle (in degrees) where the arc begins
     * @param sweepAngle  Sweep angle (in degrees) measured clockwise, treated
     *                    mod 360.
     * @param forceMoveTo If true, always begin a new contour with the arc
     */
    public void arcTo(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle,
            boolean forceMoveTo) {
        Arc2D arc = new Arc2D.Float(left, top, right - left, bottom - top, -startAngle,
                -sweepAngle, Arc2D.OPEN);
        mPath.append(arc, true /*connect*/);

        resetLastPointFromPath();
    }

    /**
     * Close the current contour. If the current point is not equal to the
     * first point of the contour, a line segment is automatically added.
     */
    public void close() {
        mPath.closePath();
    }

    private void resetLastPointFromPath() {
        Point2D last = mPath.getCurrentPoint();
        mLastX = (float) last.getX();
        mLastY = (float) last.getY();
    }

    /**
     * Add a closed rectangle contour to the path
     *
     * @param left   The left side of a rectangle to add to the path
     * @param top    The top of a rectangle to add to the path
     * @param right  The right side of a rectangle to add to the path
     * @param bottom The bottom of a rectangle to add to the path
     * @param dir    The direction to wind the rectangle's contour
     */
    public void addRect(float left, float top, float right, float bottom,
                        int dir) {
        moveTo(left, top);

        Direction direction = getDirection(dir);

        switch (direction) {
            case CW:
                lineTo(right, top);
                lineTo(right, bottom);
                lineTo(left, bottom);
                break;
            case CCW:
                lineTo(left, bottom);
                lineTo(right, bottom);
                lineTo(right, top);
                break;
        }

        close();

        resetLastPointFromPath();
    }

    /**
     * Offset the path by (dx,dy), returning true on success
     *
     * @param dx  The amount in the X direction to offset the entire path
     * @param dy  The amount in the Y direction to offset the entire path
     */
    public void offset(float dx, float dy) {
        GeneralPath newPath = new GeneralPath();

        PathIterator iterator = mPath.getPathIterator(new AffineTransform(0, 0, dx, 0, 0, dy));

        newPath.append(iterator, false /*connect*/);
        mPath = newPath;
    }

    /**
     * Transform the points in this path by matrix, and write the answer
     * into dst. If dst is null, then the the original path is modified.
     *
     * @param matrix The matrix to apply to the path
     * @param dst    The transformed path is written here. If dst is null,
     *               then the the original path is modified
     */
    public void transform(Matrix_Delegate matrix, Path_Delegate dst) {
        if (matrix.hasPerspective()) {
            assert false;
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_AFFINE,
                    "android.graphics.Path#transform() only " +
                    "supports affine transformations.", null, null /*data*/);
        }

        GeneralPath newPath = new GeneralPath();

        PathIterator iterator = mPath.getPathIterator(matrix.getAffineTransform());

        newPath.append(iterator, false /*connect*/);

        if (dst != null) {
            dst.mPath = newPath;
        } else {
            mPath = newPath;
        }
    }
}
