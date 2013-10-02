/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.view.HardwareRenderer;

/**
 * The Path class encapsulates compound (multiple contour) geometric paths
 * consisting of straight line segments, quadratic curves, and cubic curves.
 * It can be drawn with canvas.drawPath(path, paint), either filled or stroked
 * (based on the paint's Style), or it can be used for clipping or to draw
 * text on a path.
 */
public class Path {
    /**
     * @hide
     */
    public final int mNativePath;

    /**
     * @hide
     */
    public boolean isSimplePath = true;
    /**
     * @hide
     */
    public Region rects;
    private boolean mDetectSimplePaths;
    private Direction mLastDirection = null;

    /**
     * Create an empty path
     */
    public Path() {
        mNativePath = init1();
        mDetectSimplePaths = HardwareRenderer.isAvailable();
    }

    /**
     * Create a new path, copying the contents from the src path.
     *
     * @param src The path to copy from when initializing the new path
     */
    public Path(Path src) {
        int valNative = 0;
        if (src != null) {
            valNative = src.mNativePath;
            isSimplePath = src.isSimplePath;
            if (src.rects != null) {
                rects = new Region(src.rects);
            }
        }
        mNativePath = init2(valNative);
        mDetectSimplePaths = HardwareRenderer.isAvailable();
    }
    
    /**
     * Clear any lines and curves from the path, making it empty.
     * This does NOT change the fill-type setting.
     */
    public void reset() {
        isSimplePath = true;
        if (mDetectSimplePaths) {
            mLastDirection = null;
            if (rects != null) rects.setEmpty();
        }
        // We promised not to change this, so preserve it around the native
        // call, which does now reset fill type.
        final FillType fillType = getFillType();
        native_reset(mNativePath);
        setFillType(fillType);
    }

    /**
     * Rewinds the path: clears any lines and curves from the path but
     * keeps the internal data structure for faster reuse.
     */
    public void rewind() {
        isSimplePath = true;
        if (mDetectSimplePaths) {
            mLastDirection = null;
            if (rects != null) rects.setEmpty();
        }
        native_rewind(mNativePath);
    }

    /** Replace the contents of this with the contents of src.
    */
    public void set(Path src) {
        if (this != src) {
            isSimplePath = src.isSimplePath;
            native_set(mNativePath, src.mNativePath);
        }
    }

    /**
     * The logical operations that can be performed when combining two paths.
     *
     * @see #op(Path, android.graphics.Path.Op)
     * @see #op(Path, Path, android.graphics.Path.Op)
     */
    public enum Op {
        /**
         * Subtract the second path from the first path.
         */
        DIFFERENCE,
        /**
         * Intersect the two paths.
         */
        INTERSECT,
        /**
         * Union (inclusive-or) the two paths.
         */
        UNION,
        /**
         * Exclusive-or the two paths.
         */
        XOR,
        /**
         * Subtract the first path from the second path.
         */
        REVERSE_DIFFERENCE
    }

    /**
     * Set this path to the result of applying the Op to this path and the specified path.
     * The resulting path will be constructed from non-overlapping contours.
     * The curve order is reduced where possible so that cubics may be turned
     * into quadratics, and quadratics maybe turned into lines.
     *
     * @param path The second operand (for difference, the subtrahend)
     *
     * @return True if operation succeeded, false otherwise and this path remains unmodified.
     *
     * @see Op
     * @see #op(Path, Path, android.graphics.Path.Op)
     */
    public boolean op(Path path, Op op) {
        return op(this, path, op);
    }

    /**
     * Set this path to the result of applying the Op to the two specified paths.
     * The resulting path will be constructed from non-overlapping contours.
     * The curve order is reduced where possible so that cubics may be turned
     * into quadratics, and quadratics maybe turned into lines.
     *
     * @param path1 The first operand (for difference, the minuend)
     * @param path2 The second operand (for difference, the subtrahend)
     *
     * @return True if operation succeeded, false otherwise and this path remains unmodified.
     *
     * @see Op
     * @see #op(Path, android.graphics.Path.Op)
     */
    public boolean op(Path path1, Path path2, Op op) {
        if (native_op(path1.mNativePath, path2.mNativePath, op.ordinal(), this.mNativePath)) {
            isSimplePath = false;
            rects = null;
            return true;
        }
        return false;
    }

    /**
     * Enum for the ways a path may be filled.
     */
    public enum FillType {
        // these must match the values in SkPath.h
        /**
         * Specifies that "inside" is computed by a non-zero sum of signed
         * edge crossings.
         */
        WINDING         (0),
        /**
         * Specifies that "inside" is computed by an odd number of edge
         * crossings.
         */
        EVEN_ODD        (1),
        /**
         * Same as {@link #WINDING}, but draws outside of the path, rather than inside.
         */
        INVERSE_WINDING (2),
        /**
         * Same as {@link #EVEN_ODD}, but draws outside of the path, rather than inside.
         */
        INVERSE_EVEN_ODD(3);
        
        FillType(int ni) {
            nativeInt = ni;
        }

        final int nativeInt;
    }

    // these must be in the same order as their native values
    static final FillType[] sFillTypeArray = {
        FillType.WINDING,
        FillType.EVEN_ODD,
        FillType.INVERSE_WINDING,
        FillType.INVERSE_EVEN_ODD
    };

    /**
     * Return the path's fill type. This defines how "inside" is
     * computed. The default value is WINDING.
     *
     * @return the path's fill type
     */
    public FillType getFillType() {
        return sFillTypeArray[native_getFillType(mNativePath)];
    }

    /**
     * Set the path's fill type. This defines how "inside" is computed.
     *
     * @param ft The new fill type for this path
     */
    public void setFillType(FillType ft) {
        native_setFillType(mNativePath, ft.nativeInt);
    }
    
    /**
     * Returns true if the filltype is one of the INVERSE variants
     *
     * @return true if the filltype is one of the INVERSE variants
     */
    public boolean isInverseFillType() {
        final int ft = native_getFillType(mNativePath);
        return (ft & 2) != 0;
    }
    
    /**
     * Toggles the INVERSE state of the filltype
     */
    public void toggleInverseFillType() {
        int ft = native_getFillType(mNativePath);
        ft ^= 2;
        native_setFillType(mNativePath, ft);
    }
    
    /**
     * Returns true if the path is empty (contains no lines or curves)
     *
     * @return true if the path is empty (contains no lines or curves)
     */
    public boolean isEmpty() {
        return native_isEmpty(mNativePath);
    }

    /**
     * Returns true if the path specifies a rectangle. If so, and if rect is
     * not null, set rect to the bounds of the path. If the path does not
     * specify a rectangle, return false and ignore rect.
     *
     * @param rect If not null, returns the bounds of the path if it specifies
     *             a rectangle
     * @return     true if the path specifies a rectangle
     */
    public boolean isRect(RectF rect) {
        return native_isRect(mNativePath, rect);
    }

    /**
     * Compute the bounds of the control points of the path, and write the
     * answer into bounds. If the path contains 0 or 1 points, the bounds is
     * set to (0,0,0,0)
     *
     * @param bounds Returns the computed bounds of the path's control points.
     * @param exact This parameter is no longer used.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void computeBounds(RectF bounds, boolean exact) {
        native_computeBounds(mNativePath, bounds);
    }

    /**
     * Hint to the path to prepare for adding more points. This can allow the
     * path to more efficiently allocate its storage.
     *
     * @param extraPtCount The number of extra points that may be added to this
     *                     path
     */
    public void incReserve(int extraPtCount) {
        native_incReserve(mNativePath, extraPtCount);
    }

    /**
     * Set the beginning of the next contour to the point (x,y).
     *
     * @param x The x-coordinate of the start of a new contour
     * @param y The y-coordinate of the start of a new contour
     */
    public void moveTo(float x, float y) {
        native_moveTo(mNativePath, x, y);
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
        native_rMoveTo(mNativePath, dx, dy);
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
        isSimplePath = false;
        native_lineTo(mNativePath, x, y);
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
        isSimplePath = false;
        native_rLineTo(mNativePath, dx, dy);
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
        isSimplePath = false;
        native_quadTo(mNativePath, x1, y1, x2, y2);
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
        isSimplePath = false;
        native_rQuadTo(mNativePath, dx1, dy1, dx2, dy2);
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
        isSimplePath = false;
        native_cubicTo(mNativePath, x1, y1, x2, y2, x3, y3);
    }

    /**
     * Same as cubicTo, but the coordinates are considered relative to the
     * current point on this contour. If there is no previous point, then a
     * moveTo(0,0) is inserted automatically.
     */
    public void rCubicTo(float x1, float y1, float x2, float y2,
                         float x3, float y3) {
        isSimplePath = false;
        native_rCubicTo(mNativePath, x1, y1, x2, y2, x3, y3);
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
    public void arcTo(RectF oval, float startAngle, float sweepAngle,
                      boolean forceMoveTo) {
        isSimplePath = false;
        native_arcTo(mNativePath, oval, startAngle, sweepAngle, forceMoveTo);
    }
    
    /**
     * Append the specified arc to the path as a new contour. If the start of
     * the path is different from the path's current last point, then an
     * automatic lineTo() is added to connect the current contour to the
     * start of the arc. However, if the path is empty, then we call moveTo()
     * with the first point of the arc.
     *
     * @param oval        The bounds of oval defining shape and size of the arc
     * @param startAngle  Starting angle (in degrees) where the arc begins
     * @param sweepAngle  Sweep angle (in degrees) measured clockwise
     */
    public void arcTo(RectF oval, float startAngle, float sweepAngle) {
        isSimplePath = false;
        native_arcTo(mNativePath, oval, startAngle, sweepAngle, false);
    }
    
    /**
     * Close the current contour. If the current point is not equal to the
     * first point of the contour, a line segment is automatically added.
     */
    public void close() {
        isSimplePath = false;
        native_close(mNativePath);
    }

    /**
     * Specifies how closed shapes (e.g. rects, ovals) are oriented when they
     * are added to a path.
     */
    public enum Direction {
        /** clockwise */
        CW  (1),    // must match enum in SkPath.h
        /** counter-clockwise */
        CCW (2);    // must match enum in SkPath.h
        
        Direction(int ni) {
            nativeInt = ni;
        }
        final int nativeInt;
    }
    
    private void detectSimplePath(float left, float top, float right, float bottom, Direction dir) {
        if (mDetectSimplePaths) {
            if (mLastDirection == null) {
                mLastDirection = dir;
            }
            if (mLastDirection != dir) {
                isSimplePath = false;
            } else {
                if (rects == null) rects = new Region();
                rects.op((int) left, (int) top, (int) right, (int) bottom, Region.Op.UNION);
            }
        }
    }

    /**
     * Add a closed rectangle contour to the path
     *
     * @param rect The rectangle to add as a closed contour to the path
     * @param dir  The direction to wind the rectangle's contour
     */
    public void addRect(RectF rect, Direction dir) {
        if (rect == null) {
            throw new NullPointerException("need rect parameter");
        }
        detectSimplePath(rect.left, rect.top, rect.right, rect.bottom, dir);
        native_addRect(mNativePath, rect, dir.nativeInt);
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
    public void addRect(float left, float top, float right, float bottom, Direction dir) {
        detectSimplePath(left, top, right, bottom, dir);
        native_addRect(mNativePath, left, top, right, bottom, dir.nativeInt);
    }

    /**
     * Add a closed oval contour to the path
     *
     * @param oval The bounds of the oval to add as a closed contour to the path
     * @param dir  The direction to wind the oval's contour
     */
    public void addOval(RectF oval, Direction dir) {
        if (oval == null) {
            throw new NullPointerException("need oval parameter");
        }
        isSimplePath = false;
        native_addOval(mNativePath, oval, dir.nativeInt);
    }

    /**
     * Add a closed circle contour to the path
     *
     * @param x   The x-coordinate of the center of a circle to add to the path
     * @param y   The y-coordinate of the center of a circle to add to the path
     * @param radius The radius of a circle to add to the path
     * @param dir    The direction to wind the circle's contour
     */
    public void addCircle(float x, float y, float radius, Direction dir) {
        isSimplePath = false;
        native_addCircle(mNativePath, x, y, radius, dir.nativeInt);
    }

    /**
     * Add the specified arc to the path as a new contour.
     *
     * @param oval The bounds of oval defining the shape and size of the arc
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     */
    public void addArc(RectF oval, float startAngle, float sweepAngle) {
        if (oval == null) {
            throw new NullPointerException("need oval parameter");
        }
        isSimplePath = false;
        native_addArc(mNativePath, oval, startAngle, sweepAngle);
    }

    /**
        * Add a closed round-rectangle contour to the path
     *
     * @param rect The bounds of a round-rectangle to add to the path
     * @param rx   The x-radius of the rounded corners on the round-rectangle
     * @param ry   The y-radius of the rounded corners on the round-rectangle
     * @param dir  The direction to wind the round-rectangle's contour
     */
    public void addRoundRect(RectF rect, float rx, float ry, Direction dir) {
        if (rect == null) {
            throw new NullPointerException("need rect parameter");
        }
        isSimplePath = false;
        native_addRoundRect(mNativePath, rect, rx, ry, dir.nativeInt);
    }
    
    /**
     * Add a closed round-rectangle contour to the path. Each corner receives
     * two radius values [X, Y]. The corners are ordered top-left, top-right,
     * bottom-right, bottom-left
     *
     * @param rect The bounds of a round-rectangle to add to the path
     * @param radii Array of 8 values, 4 pairs of [X,Y] radii
     * @param dir  The direction to wind the round-rectangle's contour
     */
    public void addRoundRect(RectF rect, float[] radii, Direction dir) {
        if (rect == null) {
            throw new NullPointerException("need rect parameter");
        }
        if (radii.length < 8) {
            throw new ArrayIndexOutOfBoundsException("radii[] needs 8 values");
        }
        isSimplePath = false;
        native_addRoundRect(mNativePath, rect, radii, dir.nativeInt);
    }
    
    /**
     * Add a copy of src to the path, offset by (dx,dy)
     *
     * @param src The path to add as a new contour
     * @param dx  The amount to translate the path in X as it is added
     */
    public void addPath(Path src, float dx, float dy) {
        isSimplePath = false;
        native_addPath(mNativePath, src.mNativePath, dx, dy);
    }

    /**
     * Add a copy of src to the path
     *
     * @param src The path that is appended to the current path
     */
    public void addPath(Path src) {
        isSimplePath = false;
        native_addPath(mNativePath, src.mNativePath);
    }

    /**
     * Add a copy of src to the path, transformed by matrix
     *
     * @param src The path to add as a new contour
     */
    public void addPath(Path src, Matrix matrix) {
        if (!src.isSimplePath) isSimplePath = false;
        native_addPath(mNativePath, src.mNativePath, matrix.native_instance);
    }

    /**
     * Offset the path by (dx,dy), returning true on success
     *
     * @param dx  The amount in the X direction to offset the entire path
     * @param dy  The amount in the Y direction to offset the entire path
     * @param dst The translated path is written here. If this is null, then
     *            the original path is modified.
     */
    public void offset(float dx, float dy, Path dst) {
        int dstNative = 0;
        if (dst != null) {
            dstNative = dst.mNativePath;
            dst.isSimplePath = false;
        }
        native_offset(mNativePath, dx, dy, dstNative);
    }

    /**
     * Offset the path by (dx,dy), returning true on success
     *
     * @param dx The amount in the X direction to offset the entire path
     * @param dy The amount in the Y direction to offset the entire path
     */
    public void offset(float dx, float dy) {
        isSimplePath = false;
        native_offset(mNativePath, dx, dy);
    }

    /**
     * Sets the last point of the path.
     *
     * @param dx The new X coordinate for the last point
     * @param dy The new Y coordinate for the last point
     */
    public void setLastPoint(float dx, float dy) {
        isSimplePath = false;
        native_setLastPoint(mNativePath, dx, dy);
    }

    /**
     * Transform the points in this path by matrix, and write the answer
     * into dst. If dst is null, then the the original path is modified.
     *
     * @param matrix The matrix to apply to the path
     * @param dst    The transformed path is written here. If dst is null,
     *               then the the original path is modified
     */
    public void transform(Matrix matrix, Path dst) {
        int dstNative = 0;
        if (dst != null) {
            dst.isSimplePath = false;
            dstNative = dst.mNativePath;
        }
        native_transform(mNativePath, matrix.native_instance, dstNative);
    }

    /**
     * Transform the points in this path by matrix.
     *
     * @param matrix The matrix to apply to the path
     */
    public void transform(Matrix matrix) {
        isSimplePath = false;
        native_transform(mNativePath, matrix.native_instance);
    }

    protected void finalize() throws Throwable {
        try {
            finalizer(mNativePath);
        } finally {
            super.finalize();
        }
    }

    final int ni() {
        return mNativePath;
    }

    private static native int init1();
    private static native int init2(int nPath);
    private static native void native_reset(int nPath);
    private static native void native_rewind(int nPath);
    private static native void native_set(int native_dst, int native_src);
    private static native int native_getFillType(int nPath);
    private static native void native_setFillType(int nPath, int ft);
    private static native boolean native_isEmpty(int nPath);
    private static native boolean native_isRect(int nPath, RectF rect);
    private static native void native_computeBounds(int nPath, RectF bounds);
    private static native void native_incReserve(int nPath, int extraPtCount);
    private static native void native_moveTo(int nPath, float x, float y);
    private static native void native_rMoveTo(int nPath, float dx, float dy);
    private static native void native_lineTo(int nPath, float x, float y);
    private static native void native_rLineTo(int nPath, float dx, float dy);
    private static native void native_quadTo(int nPath, float x1, float y1,
                                             float x2, float y2);
    private static native void native_rQuadTo(int nPath, float dx1, float dy1,
                                              float dx2, float dy2);
    private static native void native_cubicTo(int nPath, float x1, float y1,
                                        float x2, float y2, float x3, float y3);
    private static native void native_rCubicTo(int nPath, float x1, float y1,
                                        float x2, float y2, float x3, float y3);
    private static native void native_arcTo(int nPath, RectF oval,
                    float startAngle, float sweepAngle, boolean forceMoveTo);
    private static native void native_close(int nPath);
    private static native void native_addRect(int nPath, RectF rect, int dir);
    private static native void native_addRect(int nPath, float left, float top,
                                            float right, float bottom, int dir);
    private static native void native_addOval(int nPath, RectF oval, int dir);
    private static native void native_addCircle(int nPath, float x, float y, float radius, int dir);
    private static native void native_addArc(int nPath, RectF oval,
                                            float startAngle, float sweepAngle);
    private static native void native_addRoundRect(int nPath, RectF rect,
                                                   float rx, float ry, int dir);
    private static native void native_addRoundRect(int nPath, RectF r, float[] radii, int dir);
    private static native void native_addPath(int nPath, int src, float dx, float dy);
    private static native void native_addPath(int nPath, int src);
    private static native void native_addPath(int nPath, int src, int matrix);
    private static native void native_offset(int nPath, float dx, float dy, int dst_path);
    private static native void native_offset(int nPath, float dx, float dy);
    private static native void native_setLastPoint(int nPath, float dx, float dy);
    private static native void native_transform(int nPath, int matrix, int dst_path);
    private static native void native_transform(int nPath, int matrix);
    private static native boolean native_op(int path1, int path2, int op, int result);
    private static native void finalizer(int nPath);
}
