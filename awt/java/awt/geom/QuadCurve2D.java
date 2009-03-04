/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Denis M. Kishenko
 * @version $Revision$
 */

package java.awt.geom;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.NoSuchElementException;

import org.apache.harmony.awt.gl.Crossing;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class QuadCurve2D is a Shape that represents a segment of a quadratic
 * (Bezier) curve. The curved segment is determined by three points: a start
 * point, an end point, and a control point. The line from the control point to
 * the starting point gives the tangent to the curve at the starting point, and
 * the line from the control point to the end point gives the tangent to the
 * curve at the end point.
 * 
 * @since Android 1.0
 */
public abstract class QuadCurve2D implements Shape, Cloneable {

    /**
     * The Class Float is the subclass of QuadCurve2D that has all of its data
     * values stored with float-level precision.
     * 
     * @since Android 1.0
     */
    public static class Float extends QuadCurve2D {

        /**
         * The x coordinate of the starting point of the curved segment.
         */
        public float x1;

        /**
         * The y coordinate of the starting point of the curved segment.
         */
        public float y1;

        /**
         * The x coordinate of the control point.
         */
        public float ctrlx;

        /**
         * The y coordinate of the control point.
         */
        public float ctrly;

        /**
         * The x coordinate of the end point of the curved segment.
         */
        public float x2;

        /**
         * The y coordinate of the end point of the curved segment.
         */
        public float y2;

        /**
         * Instantiates a new float-valued QuadCurve2D with all coordinate
         * values set to zero.
         */
        public Float() {
        }

        /**
         * Instantiates a new float-valued QuadCurve2D with the specified
         * coordinate values.
         * 
         * @param x1
         *            the x coordinate of the starting point of the curved
         *            segment.
         * @param y1
         *            the y coordinate of the starting point of the curved
         *            segment.
         * @param ctrlx
         *            the x coordinate of the control point.
         * @param ctrly
         *            the y coordinate of the control point.
         * @param x2
         *            the x coordinate of the end point of the curved segment.
         * @param y2
         *            the y coordinate of the end point of the curved segment.
         */
        public Float(float x1, float y1, float ctrlx, float ctrly, float x2, float y2) {
            setCurve(x1, y1, ctrlx, ctrly, x2, y2);
        }

        @Override
        public double getX1() {
            return x1;
        }

        @Override
        public double getY1() {
            return y1;
        }

        @Override
        public double getCtrlX() {
            return ctrlx;
        }

        @Override
        public double getCtrlY() {
            return ctrly;
        }

        @Override
        public double getX2() {
            return x2;
        }

        @Override
        public double getY2() {
            return y2;
        }

        @Override
        public Point2D getP1() {
            return new Point2D.Float(x1, y1);
        }

        @Override
        public Point2D getCtrlPt() {
            return new Point2D.Float(ctrlx, ctrly);
        }

        @Override
        public Point2D getP2() {
            return new Point2D.Float(x2, y2);
        }

        @Override
        public void setCurve(double x1, double y1, double ctrlx, double ctrly, double x2, double y2) {
            this.x1 = (float)x1;
            this.y1 = (float)y1;
            this.ctrlx = (float)ctrlx;
            this.ctrly = (float)ctrly;
            this.x2 = (float)x2;
            this.y2 = (float)y2;
        }

        /**
         * Sets the data values of the curve.
         * 
         * @param x1
         *            the x coordinate of the starting point of the curved
         *            segment.
         * @param y1
         *            the y coordinate of the starting point of the curved
         *            segment.
         * @param ctrlx
         *            the x coordinate of the control point.
         * @param ctrly
         *            the y coordinate of the control point.
         * @param x2
         *            the x coordinate of the end point of the curved segment.
         * @param y2
         *            the y coordinate of the end point of the curved segment.
         */
        public void setCurve(float x1, float y1, float ctrlx, float ctrly, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.ctrlx = ctrlx;
            this.ctrly = ctrly;
            this.x2 = x2;
            this.y2 = y2;
        }

        public Rectangle2D getBounds2D() {
            float rx0 = Math.min(Math.min(x1, x2), ctrlx);
            float ry0 = Math.min(Math.min(y1, y2), ctrly);
            float rx1 = Math.max(Math.max(x1, x2), ctrlx);
            float ry1 = Math.max(Math.max(y1, y2), ctrly);
            return new Rectangle2D.Float(rx0, ry0, rx1 - rx0, ry1 - ry0);
        }
    }

    /**
     * The Class Double is the subclass of QuadCurve2D that has all of its data
     * values stored with double-level precision.
     * 
     * @since Android 1.0
     */
    public static class Double extends QuadCurve2D {

        /**
         * The x coordinate of the starting point of the curved segment.
         */
        public double x1;

        /**
         * The y coordinate of the starting point of the curved segment.
         */
        public double y1;

        /**
         * The x coordinate of the control point.
         */
        public double ctrlx;

        /**
         * The y coordinate of the control point.
         */
        public double ctrly;

        /**
         * The x coordinate of the end point of the curved segment.
         */
        public double x2;

        /**
         * The y coordinate of the end point of the curved segment.
         */
        public double y2;

        /**
         * Instantiates a new double-valued QuadCurve2D with all coordinate
         * values set to zero.
         */
        public Double() {
        }

        /**
         * Instantiates a new double-valued QuadCurve2D with the specified
         * coordinate values.
         * 
         * @param x1
         *            the x coordinate of the starting point of the curved
         *            segment.
         * @param y1
         *            the y coordinate of the starting point of the curved
         *            segment.
         * @param ctrlx
         *            the x coordinate of the control point.
         * @param ctrly
         *            the y coordinate of the control point.
         * @param x2
         *            the x coordinate of the end point of the curved segment.
         * @param y2
         *            the y coordinate of the end point of the curved segment.
         */
        public Double(double x1, double y1, double ctrlx, double ctrly, double x2, double y2) {
            setCurve(x1, y1, ctrlx, ctrly, x2, y2);
        }

        @Override
        public double getX1() {
            return x1;
        }

        @Override
        public double getY1() {
            return y1;
        }

        @Override
        public double getCtrlX() {
            return ctrlx;
        }

        @Override
        public double getCtrlY() {
            return ctrly;
        }

        @Override
        public double getX2() {
            return x2;
        }

        @Override
        public double getY2() {
            return y2;
        }

        @Override
        public Point2D getP1() {
            return new Point2D.Double(x1, y1);
        }

        @Override
        public Point2D getCtrlPt() {
            return new Point2D.Double(ctrlx, ctrly);
        }

        @Override
        public Point2D getP2() {
            return new Point2D.Double(x2, y2);
        }

        @Override
        public void setCurve(double x1, double y1, double ctrlx, double ctrly, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.ctrlx = ctrlx;
            this.ctrly = ctrly;
            this.x2 = x2;
            this.y2 = y2;
        }

        public Rectangle2D getBounds2D() {
            double rx0 = Math.min(Math.min(x1, x2), ctrlx);
            double ry0 = Math.min(Math.min(y1, y2), ctrly);
            double rx1 = Math.max(Math.max(x1, x2), ctrlx);
            double ry1 = Math.max(Math.max(y1, y2), ctrly);
            return new Rectangle2D.Double(rx0, ry0, rx1 - rx0, ry1 - ry0);
        }
    }

    /*
     * QuadCurve2D path iterator
     */
    /**
     * The PathIterator for a Quad2D curve.
     */
    class Iterator implements PathIterator {

        /**
         * The source QuadCurve2D object.
         */
        QuadCurve2D c;

        /**
         * The path iterator transformation.
         */
        AffineTransform t;

        /**
         * The current segment index.
         */
        int index;

        /**
         * Constructs a new QuadCurve2D.Iterator for given curve and
         * transformation
         * 
         * @param q
         *            the source QuadCurve2D object.
         * @param t
         *            the AffineTransform that acts on the coordinates before
         *            returning them (or null).
         */
        Iterator(QuadCurve2D q, AffineTransform t) {
            this.c = q;
            this.t = t;
        }

        public int getWindingRule() {
            return WIND_NON_ZERO;
        }

        public boolean isDone() {
            return (index > 1);
        }

        public void next() {
            index++;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type;
            int count;
            if (index == 0) {
                type = SEG_MOVETO;
                coords[0] = c.getX1();
                coords[1] = c.getY1();
                count = 1;
            } else {
                type = SEG_QUADTO;
                coords[0] = c.getCtrlX();
                coords[1] = c.getCtrlY();
                coords[2] = c.getX2();
                coords[3] = c.getY2();
                count = 2;
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, count);
            }
            return type;
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type;
            int count;
            if (index == 0) {
                type = SEG_MOVETO;
                coords[0] = (float)c.getX1();
                coords[1] = (float)c.getY1();
                count = 1;
            } else {
                type = SEG_QUADTO;
                coords[0] = (float)c.getCtrlX();
                coords[1] = (float)c.getCtrlY();
                coords[2] = (float)c.getX2();
                coords[3] = (float)c.getY2();
                count = 2;
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, count);
            }
            return type;
        }

    }

    /**
     * Instantiates a new quadratic curve.
     */
    protected QuadCurve2D() {
    }

    /**
     * Gets the x coordinate of the starting point.
     * 
     * @return the x coordinate of the starting point.
     */
    public abstract double getX1();

    /**
     * Gets the y coordinate of the starting point.
     * 
     * @return the y coordinate of the starting point.
     */
    public abstract double getY1();

    /**
     * Gets the starting point.
     * 
     * @return the starting point.
     */
    public abstract Point2D getP1();

    /**
     * Gets the x coordinate of the control point.
     * 
     * @return the x coordinate of the control point.
     */
    public abstract double getCtrlX();

    /**
     * Gets the y coordinate of the control point.
     * 
     * @return y coordinate of the control point.
     */
    public abstract double getCtrlY();

    /**
     * Gets the control point.
     * 
     * @return the control point.
     */
    public abstract Point2D getCtrlPt();

    /**
     * Gets the x coordinate of the end point.
     * 
     * @return the x coordinate of the end point.
     */
    public abstract double getX2();

    /**
     * Gets the y coordinate of the end point.
     * 
     * @return the y coordinate of the end point.
     */
    public abstract double getY2();

    /**
     * Gets the end point.
     * 
     * @return the end point.
     */
    public abstract Point2D getP2();

    /**
     * Sets the data of the curve.
     * 
     * @param x1
     *            the x coordinate of the starting point of the curved segment.
     * @param y1
     *            the y coordinate of the starting point of the curved segment.
     * @param ctrlx
     *            the x coordinate of the control point.
     * @param ctrly
     *            the y coordinate of the control point.
     * @param x2
     *            the x coordinate of the end point of the curved segment.
     * @param y2
     *            the y coordinate of the end point of the curved segment.
     */
    public abstract void setCurve(double x1, double y1, double ctrlx, double ctrly, double x2,
            double y2);

    /**
     * Sets the data of the curve.
     * 
     * @param p1
     *            the starting point of the curved segment.
     * @param cp
     *            the control point.
     * @param p2
     *            the end point of the curved segment.
     * @throws NullPointerException
     *             if any of the three points is null.
     */
    public void setCurve(Point2D p1, Point2D cp, Point2D p2) {
        setCurve(p1.getX(), p1.getY(), cp.getX(), cp.getY(), p2.getX(), p2.getY());
    }

    /**
     * Sets the data of the curve by reading the data from an array of values.
     * The values are read in the same order as the arguments of the method
     * {@link QuadCurve2D#setCurve(double, double, double, double, double, double)}
     * .
     * 
     * @param coords
     *            the array of values containing the new coordinates.
     * @param offset
     *            the offset of the data to read within the array.
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code coords.length} < offset + 6.
     * @throws NullPointerException
     *             if the coordinate array is null.
     */
    public void setCurve(double[] coords, int offset) {
        setCurve(coords[offset + 0], coords[offset + 1], coords[offset + 2], coords[offset + 3],
                coords[offset + 4], coords[offset + 5]);
    }

    /**
     * Sets the data of the curve by reading the data from an array of points.
     * The values are read in the same order as the arguments of the method
     * {@link QuadCurve2D#setCurve(Point2D, Point2D, Point2D)}.
     * 
     * @param points
     *            the array of points containing the new coordinates.
     * @param offset
     *            the offset of the data to read within the array.
     * @throws ArrayIndexOutOfBoundsException
     *             if points.length < offset + 3.
     * @throws NullPointerException
     *             if the point array is null.
     */
    public void setCurve(Point2D[] points, int offset) {
        setCurve(points[offset + 0].getX(), points[offset + 0].getY(), points[offset + 1].getX(),
                points[offset + 1].getY(), points[offset + 2].getX(), points[offset + 2].getY());
    }

    /**
     * Sets the data of the curve by copying it from another QuadCurve2D.
     * 
     * @param curve
     *            the curve to copy the data points from.
     * @throws NullPointerException
     *             if the curve is null.
     */
    public void setCurve(QuadCurve2D curve) {
        setCurve(curve.getX1(), curve.getY1(), curve.getCtrlX(), curve.getCtrlY(), curve.getX2(),
                curve.getY2());
    }

    /**
     * Gets the square of the distance from the control point to the straight
     * line segment connecting the start point and the end point for this curve.
     * 
     * @return the square of the distance from the control point to the straight
     *         line segment connecting the start point and the end point.
     */
    public double getFlatnessSq() {
        return Line2D.ptSegDistSq(getX1(), getY1(), getX2(), getY2(), getCtrlX(), getCtrlY());
    }

    /**
     * Gets the square of the distance from the control point to the straight
     * line segment connecting the start point and the end point.
     * 
     * @param x1
     *            the x coordinate of the starting point of the curved segment.
     * @param y1
     *            the y coordinate of the starting point of the curved segment.
     * @param ctrlx
     *            the x coordinate of the control point.
     * @param ctrly
     *            the y coordinate of the control point.
     * @param x2
     *            the x coordinate of the end point of the curved segment.
     * @param y2
     *            the y coordinate of the end point of the curved segment.
     * @return the square of the distance from the control point to the straight
     *         line segment connecting the start point and the end point.
     */
    public static double getFlatnessSq(double x1, double y1, double ctrlx, double ctrly, double x2,
            double y2) {
        return Line2D.ptSegDistSq(x1, y1, x2, y2, ctrlx, ctrly);
    }

    /**
     * Gets the square of the distance from the control point to the straight
     * line segment connecting the start point and the end point by reading the
     * coordinates of the points from an array of values. The values are read in
     * the same order as the arguments of the method
     * {@link QuadCurve2D#getFlatnessSq(double, double, double, double, double, double)}
     * .
     * 
     * @param coords
     *            the array of points containing the coordinates to use for the
     *            calculation
     * @param offset
     *            the offset of the data to read within the array
     * @return the square of the distance from the control point to the straight
     *         line segment connecting the start point and the end point.
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code coords.length} < offset + 6.
     * @throws NullPointerException
     *             if the coordinate array is null.
     */
    public static double getFlatnessSq(double coords[], int offset) {
        return Line2D.ptSegDistSq(coords[offset + 0], coords[offset + 1], coords[offset + 4],
                coords[offset + 5], coords[offset + 2], coords[offset + 3]);
    }

    /**
     * Gets the distance from the control point to the straight line segment
     * connecting the start point and the end point of this QuadCurve2D.
     * 
     * @return the the distance from the control point to the straight line
     *         segment connecting the start point and the end point of this
     *         QuadCurve2D.
     */
    public double getFlatness() {
        return Line2D.ptSegDist(getX1(), getY1(), getX2(), getY2(), getCtrlX(), getCtrlY());
    }

    /**
     * Gets the distance from the control point to the straight line segment
     * connecting the start point and the end point.
     * 
     * @param x1
     *            the x coordinate of the starting point of the curved segment.
     * @param y1
     *            the y coordinate of the starting point of the curved segment.
     * @param ctrlx
     *            the x coordinate of the control point.
     * @param ctrly
     *            the y coordinate of the control point.
     * @param x2
     *            the x coordinate of the end point of the curved segment.
     * @param y2
     *            the y coordinate of the end point of the curved segment.
     * @return the the distance from the control point to the straight line
     *         segment connecting the start point and the end point.
     */
    public static double getFlatness(double x1, double y1, double ctrlx, double ctrly, double x2,
            double y2) {
        return Line2D.ptSegDist(x1, y1, x2, y2, ctrlx, ctrly);
    }

    /**
     * Gets the the distance from the control point to the straight line segment
     * connecting the start point and the end point. The values are read in the
     * same order as the arguments of the method
     * {@link QuadCurve2D#getFlatness(double, double, double, double, double, double)}
     * .
     * 
     * @param coords
     *            the array of points containing the coordinates to use for the
     *            calculation.
     * @param offset
     *            the offset of the data to read within the array.
     * @return the the distance from the control point to the straight line
     *         segment connecting the start point and the end point.
     * @throws ArrayIndexOutOfBoundsException
     *             if {code coords.length} < offset + 6.
     * @throws NullPointerException
     *             if the coordinate array is null.
     */
    public static double getFlatness(double coords[], int offset) {
        return Line2D.ptSegDist(coords[offset + 0], coords[offset + 1], coords[offset + 4],
                coords[offset + 5], coords[offset + 2], coords[offset + 3]);
    }

    /**
     * Creates the data for two quadratic curves by dividing this curve in two.
     * The division point is the point on the curve that is closest to this
     * curve's control point. The data of this curve is left unchanged.
     * 
     * @param left
     *            the QuadCurve2D where the left (start) segment's data is
     *            written.
     * @param right
     *            the QuadCurve2D where the right (end) segment's data is
     *            written.
     * @throws NullPointerException
     *             if either curve is null.
     */
    public void subdivide(QuadCurve2D left, QuadCurve2D right) {
        subdivide(this, left, right);
    }

    /**
     * Creates the data for two quadratic curves by dividing a source curve in
     * two. The division point is the point on the curve that is closest to the
     * source curve's control point. The data of the source curve is left
     * unchanged.
     * 
     * @param src
     *            the curve that provides the initial data.
     * @param left
     *            the QuadCurve2D where the left (start) segment's data is
     *            written.
     * @param right
     *            the QuadCurve2D where the right (end) segment's data is
     *            written.
     * @throws NullPointerException
     *             if one of the curves is null.
     */
    public static void subdivide(QuadCurve2D src, QuadCurve2D left, QuadCurve2D right) {
        double x1 = src.getX1();
        double y1 = src.getY1();
        double cx = src.getCtrlX();
        double cy = src.getCtrlY();
        double x2 = src.getX2();
        double y2 = src.getY2();
        double cx1 = (x1 + cx) / 2.0;
        double cy1 = (y1 + cy) / 2.0;
        double cx2 = (x2 + cx) / 2.0;
        double cy2 = (y2 + cy) / 2.0;
        cx = (cx1 + cx2) / 2.0;
        cy = (cy1 + cy2) / 2.0;
        if (left != null) {
            left.setCurve(x1, y1, cx1, cy1, cx, cy);
        }
        if (right != null) {
            right.setCurve(cx, cy, cx2, cy2, x2, y2);
        }
    }

    /**
     * Creates the data for two quadratic curves by dividing a source curve in
     * two. The division point is the point on the curve that is closest to the
     * source curve's control point. The data for the three curves is read and
     * written from arrays of values in the usual order: x1, y1, cx, cy, x2, y2.
     * 
     * @param src
     *            the array that gives the data values for the source curve.
     * @param srcoff
     *            the offset in the src array to read the values from.
     * @param left
     *            the array where the coordinates of the start curve should be
     *            written.
     * @param leftOff
     *            the offset in the left array to start writing the values.
     * @param right
     *            the array where the coordinates of the end curve should be
     *            written.
     * @param rightOff
     *            the offset in the right array to start writing the values.
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code src.length} < srcoff + 6 or if {@code left.length}
     *             < leftOff + 6 or if {@code right.length} < rightOff + 6.
     * @throws NullPointerException
     *             if one of the arrays is null.
     */
    public static void subdivide(double src[], int srcoff, double left[], int leftOff,
            double right[], int rightOff) {
        double x1 = src[srcoff + 0];
        double y1 = src[srcoff + 1];
        double cx = src[srcoff + 2];
        double cy = src[srcoff + 3];
        double x2 = src[srcoff + 4];
        double y2 = src[srcoff + 5];
        double cx1 = (x1 + cx) / 2.0;
        double cy1 = (y1 + cy) / 2.0;
        double cx2 = (x2 + cx) / 2.0;
        double cy2 = (y2 + cy) / 2.0;
        cx = (cx1 + cx2) / 2.0;
        cy = (cy1 + cy2) / 2.0;
        if (left != null) {
            left[leftOff + 0] = x1;
            left[leftOff + 1] = y1;
            left[leftOff + 2] = cx1;
            left[leftOff + 3] = cy1;
            left[leftOff + 4] = cx;
            left[leftOff + 5] = cy;
        }
        if (right != null) {
            right[rightOff + 0] = cx;
            right[rightOff + 1] = cy;
            right[rightOff + 2] = cx2;
            right[rightOff + 3] = cy2;
            right[rightOff + 4] = x2;
            right[rightOff + 5] = y2;
        }
    }

    /**
     * Finds the roots of the quadratic polynomial. This is accomplished by
     * finding the (real) values of x that solve the following equation:
     * eqn[2]*x*x + eqn[1]*x + eqn[0] = 0. The solutions are written back into
     * the array eqn starting from the index 0 in the array. The return value
     * tells how many array elements have been changed by this method call.
     * 
     * @param eqn
     *            an array containing the coefficients of the quadratic
     *            polynomial to solve.
     * @return the number of roots of the quadratic polynomial.
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code eqn.length} < 3.
     * @throws NullPointerException
     *             if the array is null.
     */
    public static int solveQuadratic(double eqn[]) {
        return solveQuadratic(eqn, eqn);
    }

    /**
     * Finds the roots of the quadratic polynomial. This is accomplished by
     * finding the (real) values of x that solve the following equation:
     * eqn[2]*x*x + eqn[1]*x + eqn[0] = 0. The solutions are written into the
     * array res starting from the index 0 in the array. The return value tells
     * how many array elements have been written by this method call.
     * 
     * @param eqn
     *            an array containing the coefficients of the quadratic
     *            polynomial to solve.
     * @param res
     *            the array that this method writes the results into.
     * @return the number of roots of the quadratic polynomial.
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code eqn.length} < 3 or if {@code res.length} is less
     *             than the number of roots.
     * @throws NullPointerException
     *             if either array is null.
     */
    public static int solveQuadratic(double eqn[], double res[]) {
        return Crossing.solveQuad(eqn, res);
    }

    public boolean contains(double px, double py) {
        return Crossing.isInsideEvenOdd(Crossing.crossShape(this, px, py));
    }

    public boolean contains(double rx, double ry, double rw, double rh) {
        int cross = Crossing.intersectShape(this, rx, ry, rw, rh);
        return cross != Crossing.CROSSING && Crossing.isInsideEvenOdd(cross);
    }

    public boolean intersects(double rx, double ry, double rw, double rh) {
        int cross = Crossing.intersectShape(this, rx, ry, rw, rh);
        return cross == Crossing.CROSSING || Crossing.isInsideEvenOdd(cross);
    }

    public boolean contains(Point2D p) {
        return contains(p.getX(), p.getY());
    }

    public boolean intersects(Rectangle2D r) {
        return intersects(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public boolean contains(Rectangle2D r) {
        return contains(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public Rectangle getBounds() {
        return getBounds2D().getBounds();
    }

    public PathIterator getPathIterator(AffineTransform t) {
        return new Iterator(this, t);
    }

    public PathIterator getPathIterator(AffineTransform t, double flatness) {
        return new FlatteningPathIterator(getPathIterator(t), flatness);
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

}
