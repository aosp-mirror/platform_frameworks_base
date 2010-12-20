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

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;

import android.graphics.Path.Direction;
import android.graphics.Path.FillType;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

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
            new DelegateManager<Path_Delegate>();

    // ---- delegate data ----
    private FillType mFillType = FillType.WINDING;
    private GeneralPath mPath = new GeneralPath();

    private float mLastX = 0;
    private float mLastY = 0;

    // ---- Public Helper methods ----

    public static Path_Delegate getDelegate(int nPath) {
        return sManager.getDelegate(nPath);
    }

    public Shape getJavaShape() {
        return mPath;
    }

    // ---- native methods ----

    /*package*/ static int init1() {
        // create the delegate
        Path_Delegate newDelegate = new Path_Delegate();

        return sManager.addDelegate(newDelegate);
    }

    /*package*/ static int init2(int nPath) {
        // create the delegate
        Path_Delegate newDelegate = new Path_Delegate();

        // get the delegate to copy
        if (nPath > 0) {
            Path_Delegate pathDelegate = sManager.getDelegate(nPath);
            if (pathDelegate == null) {
                assert false;
                return 0;
            }

            newDelegate.set(pathDelegate);
        }

        return sManager.addDelegate(newDelegate);
    }

    /*package*/ static void native_reset(int nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.mPath.reset();
    }

    /*package*/ static void native_rewind(int nPath) {
        // call out to reset since there's nothing to optimize in
        // terms of data structs.
        native_reset(nPath);
    }

    /*package*/ static void native_set(int native_dst, int native_src) {
        Path_Delegate pathDstDelegate = sManager.getDelegate(native_dst);
        if (pathDstDelegate == null) {
            assert false;
            return;
        }

        Path_Delegate pathSrcDelegate = sManager.getDelegate(native_src);
        if (pathSrcDelegate == null) {
            assert false;
            return;
        }

        pathDstDelegate.set(pathSrcDelegate);
    }

    /*package*/ static int native_getFillType(int nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return 0;
        }

        return pathDelegate.mFillType.nativeInt;
    }

    /*package*/ static void native_setFillType(int nPath, int ft) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.mFillType = Path.sFillTypeArray[ft];
    }

    /*package*/ static boolean native_isEmpty(int nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return true;
        }

        return pathDelegate.isEmpty();
    }

    /*package*/ static boolean native_isRect(int nPath, RectF rect) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
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

    /*package*/ static void native_computeBounds(int nPath, RectF bounds) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.fillBounds(bounds);
    }

    /*package*/ static void native_incReserve(int nPath, int extraPtCount) {
        // since we use a java2D path, there's no way to pre-allocate new points,
        // so we do nothing.
    }

    /*package*/ static void native_moveTo(int nPath, float x, float y) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.moveTo(x, y);
    }

    /*package*/ static void native_rMoveTo(int nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.rMoveTo(dx, dy);
    }

    /*package*/ static void native_lineTo(int nPath, float x, float y) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.lineTo(x, y);
    }

    /*package*/ static void native_rLineTo(int nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.rLineTo(dx, dy);
    }

    /*package*/ static void native_quadTo(int nPath, float x1, float y1, float x2, float y2) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.quadTo(x1, y1, x2, y2);
    }

    /*package*/ static void native_rQuadTo(int nPath, float dx1, float dy1,
                                              float dx2, float dy2) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.rQuadTo(dx1, dy1, dx2, dy2);
    }

    /*package*/ static void native_cubicTo(int nPath, float x1, float y1,
                float x2, float y2, float x3, float y3) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.cubicTo(x1, y1, x2, y2, x3, y3);
    }

    /*package*/ static void native_rCubicTo(int nPath, float x1, float y1,
                float x2, float y2, float x3, float y3) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.rCubicTo(x1, y1, x2, y2, x3, y3);
    }

    /*package*/ static void native_arcTo(int nPath, RectF oval,
                    float startAngle, float sweepAngle, boolean forceMoveTo) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.arcTo(oval, startAngle, sweepAngle, forceMoveTo);
    }

    /*package*/ static void native_close(int nPath) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.close();
    }

    /*package*/ static void native_addRect(int nPath, RectF rect, int dir) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.addRect(rect.left, rect.top, rect.right, rect.bottom, dir);
    }

    /*package*/ static void native_addRect(int nPath, float left, float top,
                                            float right, float bottom, int dir) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.addRect(left, top, right, bottom, dir);
    }

    /*package*/ static void native_addOval(int nPath, RectF oval, int dir) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addCircle(int nPath, float x, float y,
                                                float radius, int dir) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addArc(int nPath, RectF oval,
                                            float startAngle, float sweepAngle) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addRoundRect(int nPath, RectF rect,
                                                   float rx, float ry, int dir) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addRoundRect(int nPath, RectF r,
                                                   float[] radii, int dir) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addPath(int nPath, int src, float dx,
                                              float dy) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addPath(int nPath, int src) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_addPath(int nPath, int src, int matrix) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_offset(int nPath, float dx, float dy,
                                             int dst_path) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        Path_Delegate dstDelegate = null;
        if (dst_path > 0) {
            dstDelegate = sManager.getDelegate(dst_path);
            if (dstDelegate == null) {
                assert false;
                return;
            }
        }

        pathDelegate.offset(dx, dy, dstDelegate);
    }

    /*package*/ static void native_offset(int nPath, float dx, float dy) {
        native_offset(nPath, dx, dy, 0);
    }

    /*package*/ static void native_setLastPoint(int nPath, float dx, float dy) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        pathDelegate.mLastX = dx;
        pathDelegate.mLastY = dy;
    }

    /*package*/ static void native_transform(int nPath, int matrix,
                                                int dst_path) {
        Path_Delegate pathDelegate = sManager.getDelegate(nPath);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(matrix);
        if (matrixDelegate == null) {
            assert false;
            return;
        }

        Path_Delegate dstDelegate = null;
        if (dst_path > 0) {
            dstDelegate = sManager.getDelegate(dst_path);
            if (dstDelegate == null) {
                assert false;
                return;
            }
        }

        pathDelegate.transform(matrixDelegate, dstDelegate);
    }

    /*package*/ static void native_transform(int nPath, int matrix) {
        native_transform(nPath, matrix, 0);
    }

    /*package*/ static void finalizer(int nPath) {
        sManager.removeDelegate(nPath);
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

    private static Direction getDirection(int direction) {
        for (Direction d : Direction.values()) {
            if (direction == d.nativeInt) {
                return d;
            }
        }

        assert false;
        return null;
    }

    /**
     * Returns whether the path is empty.
     * @return true if the path is empty.
     */
    private boolean isEmpty() {
        return mPath.getCurrentPoint() == null;
    }

    /**
     * Fills the given {@link RectF} with the path bounds.
     * @param bounds the RectF to be filled.
     */
    private void fillBounds(RectF bounds) {
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
    private void moveTo(float x, float y) {
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
    private void rMoveTo(float dx, float dy) {
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
    private void lineTo(float x, float y) {
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
    private void rLineTo(float dx, float dy) {
        if (isEmpty()) {
            mPath.moveTo(mLastX = 0, mLastY = 0);
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
    private void quadTo(float x1, float y1, float x2, float y2) {
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
    private void rQuadTo(float dx1, float dy1, float dx2, float dy2) {
        if (isEmpty()) {
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
    private void cubicTo(float x1, float y1, float x2, float y2,
                        float x3, float y3) {
        mPath.curveTo(x1, y1, x2, y2, mLastX = x3, mLastY = y3);
    }

    /**
     * Same as cubicTo, but the coordinates are considered relative to the
     * current point on this contour. If there is no previous point, then a
     * moveTo(0,0) is inserted automatically.
     */
    private void rCubicTo(float dx1, float dy1, float dx2, float dy2,
                         float dx3, float dy3) {
        if (isEmpty()) {
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
     * @param oval        The bounds of oval defining shape and size of the arc
     * @param startAngle  Starting angle (in degrees) where the arc begins
     * @param sweepAngle  Sweep angle (in degrees) measured clockwise, treated
     *                    mod 360.
     * @param forceMoveTo If true, always begin a new contour with the arc
     */
    private void arcTo(RectF oval, float startAngle, float sweepAngle,
                      boolean forceMoveTo) {
        Arc2D arc = new Arc2D.Float(oval.left, oval.top, oval.width(), oval.height(), startAngle,
                sweepAngle, Arc2D.OPEN);
        mPath.append(arc, true /*connect*/);

        resetLastPointFromPath();
    }

    /**
     * Close the current contour. If the current point is not equal to the
     * first point of the contour, a line segment is automatically added.
     */
    private void close() {
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
    private void addRect(float left, float top, float right, float bottom,
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
     * @param dst The translated path is written here. If this is null, then
     *            the original path is modified.
     */
    public void offset(float dx, float dy, Path_Delegate dst) {
        GeneralPath newPath = new GeneralPath();

        PathIterator iterator = mPath.getPathIterator(new AffineTransform(0, 0, dx, 0, 0, dy));

        newPath.append(iterator, false /*connect*/);

        if (dst != null) {
            dst.mPath = newPath;
        } else {
            mPath = newPath;
        }
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
            Bridge.getLog().fidelityWarning(null,
                    "android.graphics.Path#transform() only " +
                    "supports affine transformations in the Layout Preview.", null);
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
