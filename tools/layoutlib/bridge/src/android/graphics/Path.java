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

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;

/**
 * The Path class encapsulates compound (multiple contour) geometric paths
 * consisting of straight line segments, quadratic curves, and cubic curves.
 * It can be drawn with canvas.drawPath(path, paint), either filled or stroked
 * (based on the paint's Style), or it can be used for clipping or to draw
 * text on a path.
 */
public class Path {
    
    private FillType mFillType = FillType.WINDING;
    private GeneralPath mPath = new GeneralPath();
    
    private float mLastX = 0;
    private float mLastY = 0;
    
    //---------- Custom methods ----------

    public Shape getAwtShape() {
        return mPath;
    }

    //----------

    /**
     * Create an empty path
     */
    public Path() {
    }

    /**
     * Create a new path, copying the contents from the src path.
     *
     * @param src The path to copy from when initializing the new path
     */
    public Path(Path src) {
        mPath.append(src.mPath, false /* connect */);
    }
    
    /**
     * Clear any lines and curves from the path, making it empty.
     * This does NOT change the fill-type setting.
     */
    public void reset() {
        mPath = new GeneralPath();
    }

    /**
     * Rewinds the path: clears any lines and curves from the path but
     * keeps the internal data structure for faster reuse.
     */
    public void rewind() {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /** Replace the contents of this with the contents of src.
    */
    public void set(Path src) {
        mPath.append(src.mPath, false /* connect */);
    }

    /** Enum for the ways a path may be filled
    */
    public enum FillType {
        // these must match the values in SkPath.h
        WINDING         (GeneralPath.WIND_NON_ZERO, false),
        EVEN_ODD        (GeneralPath.WIND_EVEN_ODD, false),
        INVERSE_WINDING (GeneralPath.WIND_NON_ZERO, true),
        INVERSE_EVEN_ODD(GeneralPath.WIND_EVEN_ODD, true);
        
        FillType(int rule, boolean inverse) {
            this.rule = rule;
            this.inverse = inverse;
        }

        final int rule;
        final boolean inverse;
    }
    
    /**
     * Return the path's fill type. This defines how "inside" is
     * computed. The default value is WINDING.
     *
     * @return the path's fill type
     */
    public FillType getFillType() {
        return mFillType;
    }

    /**
     * Set the path's fill type. This defines how "inside" is computed.
     *
     * @param ft The new fill type for this path
     */
    public void setFillType(FillType ft) {
        mFillType = ft;
        mPath.setWindingRule(ft.rule);
    }
    
    /**
     * Returns true if the filltype is one of the INVERSE variants
     *
     * @return true if the filltype is one of the INVERSE variants
     */
    public boolean isInverseFillType() {
        return mFillType.inverse;
    }
    
    /**
     * Toggles the INVERSE state of the filltype
     */
    public void toggleInverseFillType() {
        switch (mFillType) {
            case WINDING:
                mFillType = FillType.INVERSE_WINDING;
                break;
            case EVEN_ODD:
                mFillType = FillType.INVERSE_EVEN_ODD;
                break;
            case INVERSE_WINDING:
                mFillType = FillType.WINDING;
                break;
            case INVERSE_EVEN_ODD:
                mFillType = FillType.EVEN_ODD;
                break;
        }
    }
    
    /**
     * Returns true if the path is empty (contains no lines or curves)
     *
     * @return true if the path is empty (contains no lines or curves)
     */
    public boolean isEmpty() {
        return mPath.getCurrentPoint() == null;
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /**
     * Compute the bounds of the path, and write the answer into bounds. If the
     * path contains 0 or 1 points, the bounds is set to (0,0,0,0)
     *
     * @param bounds Returns the computed bounds of the path
     * @param exact If true, return the exact (but slower) bounds, else return
     *              just the bounds of all control points
     */
    public void computeBounds(RectF bounds, boolean exact) {
        Rectangle2D rect = mPath.getBounds2D();
        bounds.left = (float)rect.getMinX();
        bounds.right = (float)rect.getMaxX();
        bounds.top = (float)rect.getMinY();
        bounds.bottom = (float)rect.getMaxY();
    }

    /**
     * Hint to the path to prepare for adding more points. This can allow the
     * path to more efficiently allocate its storage.
     *
     * @param extraPtCount The number of extra points that may be added to this
     *                     path
     */
    public void incReserve(int extraPtCount) {
        // pass
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
    public void cubicTo(float x1, float y1, float x2, float y2,
                        float x3, float y3) {
        mPath.curveTo(x1, y1, x2, y2, mLastX = x3, mLastY = y3);
    }

    /**
     * Same as cubicTo, but the coordinates are considered relative to the
     * current point on this contour. If there is no previous point, then a
     * moveTo(0,0) is inserted automatically.
     */
    public void rCubicTo(float dx1, float dy1, float dx2, float dy2,
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
    public void arcTo(RectF oval, float startAngle, float sweepAngle,
                      boolean forceMoveTo) {
        throw new UnsupportedOperationException();
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
        throw new UnsupportedOperationException();
    }
    
    /**
     * Close the current contour. If the current point is not equal to the
     * first point of the contour, a line segment is automatically added.
     */
    public void close() {
        mPath.closePath();
    }

    /**
     * Specifies how closed shapes (e.g. rects, ovals) are oriented when they
     * are added to a path.
     */
    public enum Direction {
        /** clockwise */
        CW  (0),    // must match enum in SkPath.h
        /** counter-clockwise */
        CCW (1);    // must match enum in SkPath.h
        
        Direction(int ni) {
            nativeInt = ni;
        }
        final int nativeInt;
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
        
        addRect(rect.left, rect.top, rect.right, rect.bottom, dir);
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
                        Direction dir) {
        moveTo(left, top);

        switch (dir) {
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

        // FIXME Need to support direction
        Ellipse2D ovalShape = new Ellipse2D.Float(oval.left, oval.top, oval.width(), oval.height());
        
        mPath.append(ovalShape, false /* connect */);
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
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
    }
    
    /**
     * Add a copy of src to the path, offset by (dx,dy)
     *
     * @param src The path to add as a new contour
     * @param dx  The amount to translate the path in X as it is added
     */
    public void addPath(Path src, float dx, float dy) {
        PathIterator iterator = src.mPath.getPathIterator(new AffineTransform(0, 0, dx, 0, 0, dy));
        mPath.append(iterator, false /* connect */);
    }

    /**
     * Add a copy of src to the path
     *
     * @param src The path that is appended to the current path
     */
    public void addPath(Path src) {
        addPath(src, 0, 0);
    }

    /**
     * Add a copy of src to the path, transformed by matrix
     *
     * @param src The path to add as a new contour
     */
    public void addPath(Path src, Matrix matrix) {
        // FIXME
        throw new UnsupportedOperationException();
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
        GeneralPath newPath = new GeneralPath();
        
        PathIterator iterator = mPath.getPathIterator(new AffineTransform(0, 0, dx, 0, 0, dy));
        
        newPath.append(iterator, false /* connect */);
        
        if (dst != null) {
            dst.mPath = newPath;
        } else {
            mPath = newPath;
        }
    }

    /**
     * Offset the path by (dx,dy), returning true on success
     *
     * @param dx The amount in the X direction to offset the entire path
     * @param dy The amount in the Y direction to offset the entire path
     */
    public void offset(float dx, float dy) {
        offset(dx, dy, null /* dst */);
    }

    /**
     * Sets the last point of the path.
     *
     * @param dx The new X coordinate for the last point
     * @param dy The new Y coordinate for the last point
     */
    public void setLastPoint(float dx, float dy) {
        mLastX = dx;
        mLastY = dy;
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /**
     * Transform the points in this path by matrix.
     *
     * @param matrix The matrix to apply to the path
     */
    public void transform(Matrix matrix) {
        transform(matrix, null /* dst */);
    }
}
