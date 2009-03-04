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

package java.awt;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;
import java.util.NoSuchElementException;

import org.apache.harmony.awt.gl.*;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Polygon class defines an closed area specified by n vertices and n edges.
 * The coordinates of the vertices are specified by x, y arrays. The edges are
 * the line segments from the point (x[i], y[i]) to the point (x[i+1], y[i+1]),
 * for -1 < i < (n-1) plus the line segment from the point (x[n-1], y[n-1]) to
 * the point (x[0], y[0]) point. The Polygon is empty if the number of vertices
 * is zero.
 * 
 * @since Android 1.0
 */
public class Polygon implements Shape, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -6460061437900069969L;

    /**
     * The points buffer capacity.
     */
    private static final int BUFFER_CAPACITY = 4;

    /**
     * The number of Polygon vertices.
     */
    public int npoints;

    /**
     * The array of X coordinates of the vertices.
     */
    public int[] xpoints;

    /**
     * The array of Y coordinates of the vertices.
     */
    public int[] ypoints;

    /**
     * The smallest Rectangle that completely contains this Polygon.
     */
    protected Rectangle bounds;

    /*
     * Polygon path iterator
     */
    /**
     * The internal Class Iterator.
     */
    class Iterator implements PathIterator {

        /**
         * The source Polygon object.
         */
        public Polygon p;

        /**
         * The path iterator transformation.
         */
        public AffineTransform t;

        /**
         * The current segment index.
         */
        public int index;

        /**
         * Constructs a new Polygon.Iterator for the given polygon and
         * transformation
         * 
         * @param at
         *            the AffineTransform object to apply rectangle path.
         * @param p
         *            the p.
         */
        public Iterator(AffineTransform at, Polygon p) {
            this.p = p;
            this.t = at;
            if (p.npoints == 0) {
                index = 1;
            }
        }

        public int getWindingRule() {
            return WIND_EVEN_ODD;
        }

        public boolean isDone() {
            return index > p.npoints;
        }

        public void next() {
            index++;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                // awt.110=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.110")); //$NON-NLS-1$
            }
            if (index == p.npoints) {
                return SEG_CLOSE;
            }
            coords[0] = p.xpoints[index];
            coords[1] = p.ypoints[index];
            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return index == 0 ? SEG_MOVETO : SEG_LINETO;
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                // awt.110=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.110")); //$NON-NLS-1$
            }
            if (index == p.npoints) {
                return SEG_CLOSE;
            }
            coords[0] = p.xpoints[index];
            coords[1] = p.ypoints[index];
            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return index == 0 ? SEG_MOVETO : SEG_LINETO;
        }
    }

    /**
     * Instantiates a new empty polygon.
     */
    public Polygon() {
        xpoints = new int[BUFFER_CAPACITY];
        ypoints = new int[BUFFER_CAPACITY];
    }

    /**
     * Instantiates a new polygon with the specified number of vertices, and the
     * given arrays of x, y vertex coordinates. The length of each coordinate
     * array may not be less than the specified number of vertices but may be
     * greater. Only the first n elements are used from each coordinate array.
     * 
     * @param xpoints
     *            the array of X vertex coordinates.
     * @param ypoints
     *            the array of Y vertex coordinates.
     * @param npoints
     *            the number vertices of the polygon.
     * @throws IndexOutOfBoundsException
     *             if the length of xpoints or ypoints is less than n.
     * @throws NegativeArraySizeException
     *             if n is negative.
     */
    public Polygon(int[] xpoints, int[] ypoints, int npoints) {
        if (npoints > xpoints.length || npoints > ypoints.length) {
            // awt.111=Parameter npoints is greater than array length
            throw new IndexOutOfBoundsException(Messages.getString("awt.111")); //$NON-NLS-1$
        }
        if (npoints < 0) {
            // awt.112=Negative number of points
            throw new NegativeArraySizeException(Messages.getString("awt.112")); //$NON-NLS-1$
        }
        this.npoints = npoints;
        this.xpoints = new int[npoints];
        this.ypoints = new int[npoints];
        System.arraycopy(xpoints, 0, this.xpoints, 0, npoints);
        System.arraycopy(ypoints, 0, this.ypoints, 0, npoints);
    }

    /**
     * Resets the current Polygon to an empty Polygon. More precisely, the
     * number of Polygon vertices is set to zero, but x, y coordinates arrays
     * are not affected.
     */
    public void reset() {
        npoints = 0;
        bounds = null;
    }

    /**
     * Invalidates the data that depends on the vertex coordinates. This method
     * should be called after direct manipulations of the x, y vertex
     * coordinates arrays to avoid unpredictable results of methods which rely
     * on the bounding box.
     */
    public void invalidate() {
        bounds = null;
    }

    /**
     * Adds the point to the Polygon and updates the bounding box accordingly.
     * 
     * @param px
     *            the X coordinate of the added vertex.
     * @param py
     *            the Y coordinate of the added vertex.
     */
    public void addPoint(int px, int py) {
        if (npoints == xpoints.length) {
            int[] tmp;

            tmp = new int[xpoints.length + BUFFER_CAPACITY];
            System.arraycopy(xpoints, 0, tmp, 0, xpoints.length);
            xpoints = tmp;

            tmp = new int[ypoints.length + BUFFER_CAPACITY];
            System.arraycopy(ypoints, 0, tmp, 0, ypoints.length);
            ypoints = tmp;
        }

        xpoints[npoints] = px;
        ypoints[npoints] = py;
        npoints++;

        if (bounds != null) {
            bounds.setFrameFromDiagonal(Math.min(bounds.getMinX(), px), Math.min(bounds.getMinY(),
                    py), Math.max(bounds.getMaxX(), px), Math.max(bounds.getMaxY(), py));
        }
    }

    /**
     * Gets the bounding rectangle of the Polygon. The bounding rectangle is the
     * smallest rectangle which contains the Polygon.
     * 
     * @return the bounding rectangle of the Polygon.
     * @see java.awt.Shape#getBounds()
     */
    public Rectangle getBounds() {
        if (bounds != null) {
            return bounds;
        }
        if (npoints == 0) {
            return new Rectangle();
        }

        int bx1 = xpoints[0];
        int by1 = ypoints[0];
        int bx2 = bx1;
        int by2 = by1;

        for (int i = 1; i < npoints; i++) {
            int x = xpoints[i];
            int y = ypoints[i];
            if (x < bx1) {
                bx1 = x;
            } else if (x > bx2) {
                bx2 = x;
            }
            if (y < by1) {
                by1 = y;
            } else if (y > by2) {
                by2 = y;
            }
        }

        return bounds = new Rectangle(bx1, by1, bx2 - bx1, by2 - by1);
    }

    /**
     * Gets the bounding rectangle of the Polygon. The bounding rectangle is the
     * smallest rectangle which contains the Polygon.
     * 
     * @return the bounding rectangle of the Polygon.
     * @deprecated Use getBounds() method.
     */
    @Deprecated
    public Rectangle getBoundingBox() {
        return getBounds();
    }

    /**
     * Gets the Rectangle2D which represents Polygon bounds. The bounding
     * rectangle is the smallest rectangle which contains the Polygon.
     * 
     * @return the bounding rectangle of the Polygon.
     * @see java.awt.Shape#getBounds2D()
     */
    public Rectangle2D getBounds2D() {
        return getBounds().getBounds2D();
    }

    /**
     * Translates all vertices of Polygon the specified distances along X, Y
     * axis.
     * 
     * @param mx
     *            the distance to translate horizontally.
     * @param my
     *            the distance to translate vertically.
     */
    public void translate(int mx, int my) {
        for (int i = 0; i < npoints; i++) {
            xpoints[i] += mx;
            ypoints[i] += my;
        }
        if (bounds != null) {
            bounds.translate(mx, my);
        }
    }

    /**
     * Checks whether or not the point given by the coordinates x, y lies inside
     * the Polygon.
     * 
     * @param x
     *            the X coordinate of the point to check.
     * @param y
     *            the Y coordinate of the point to check.
     * @return true, if the specified point lies inside the Polygon, false
     *         otherwise.
     * @deprecated Use contains(int, int) method.
     */
    @Deprecated
    public boolean inside(int x, int y) {
        return contains((double)x, (double)y);
    }

    /**
     * Checks whether or not the point given by the coordinates x, y lies inside
     * the Polygon.
     * 
     * @param x
     *            the X coordinate of the point to check.
     * @param y
     *            the Y coordinate of the point to check.
     * @return true, if the specified point lies inside the Polygon, false
     *         otherwise.
     */
    public boolean contains(int x, int y) {
        return contains((double)x, (double)y);
    }

    /**
     * Checks whether or not the point with specified double coordinates lies
     * inside the Polygon.
     * 
     * @param x
     *            the X coordinate of the point to check.
     * @param y
     *            the Y coordinate of the point to check.
     * @return true, if the point given by the double coordinates lies inside
     *         the Polygon, false otherwise.
     * @see java.awt.Shape#contains(double, double)
     */
    public boolean contains(double x, double y) {
        return Crossing.isInsideEvenOdd(Crossing.crossShape(this, x, y));
    }

    /**
     * Checks whether or not the rectangle determined by the parameters [x, y,
     * width, height] lies inside the Polygon.
     * 
     * @param x
     *            the X coordinate of the rectangles's left upper corner as a
     *            double.
     * @param y
     *            the Y coordinate of the rectangles's left upper corner as a
     *            double.
     * @param width
     *            the width of rectangle as a double.
     * @param height
     *            the height of rectangle as a double.
     * @return true, if the specified rectangle lies inside the Polygon, false
     *         otherwise.
     * @see java.awt.Shape#contains(double, double, double, double)
     */
    public boolean contains(double x, double y, double width, double height) {
        int cross = Crossing.intersectShape(this, x, y, width, height);
        return cross != Crossing.CROSSING && Crossing.isInsideEvenOdd(cross);
    }

    /**
     * Checks whether or not the rectangle determined by the parameters [x, y,
     * width, height] intersects the interior of the Polygon.
     * 
     * @param x
     *            the X coordinate of the rectangles's left upper corner as a
     *            double.
     * @param y
     *            the Y coordinate of the rectangles's left upper corner as a
     *            double.
     * @param width
     *            the width of rectangle as a double.
     * @param height
     *            the height of rectangle as a double.
     * @return true, if the specified rectangle intersects the interior of the
     *         Polygon, false otherwise.
     * @see java.awt.Shape#intersects(double, double, double, double)
     */
    public boolean intersects(double x, double y, double width, double height) {
        int cross = Crossing.intersectShape(this, x, y, width, height);
        return cross == Crossing.CROSSING || Crossing.isInsideEvenOdd(cross);
    }

    /**
     * Checks whether or not the specified rectangle lies inside the Polygon.
     * 
     * @param rect
     *            the Rectangle2D object.
     * @return true, if the specified rectangle lies inside the Polygon, false
     *         otherwise.
     * @see java.awt.Shape#contains(java.awt.geom.Rectangle2D)
     */
    public boolean contains(Rectangle2D rect) {
        return contains(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    /**
     * Checks whether or not the specified Point lies inside the Polygon.
     * 
     * @param point
     *            the Point object.
     * @return true, if the specified Point lies inside the Polygon, false
     *         otherwise.
     */
    public boolean contains(Point point) {
        return contains(point.getX(), point.getY());
    }

    /**
     * Checks whether or not the specified Point2D lies inside the Polygon.
     * 
     * @param point
     *            the Point2D object.
     * @return true, if the specified Point2D lies inside the Polygon, false
     *         otherwise.
     * @see java.awt.Shape#contains(java.awt.geom.Point2D)
     */
    public boolean contains(Point2D point) {
        return contains(point.getX(), point.getY());
    }

    /**
     * Checks whether or not the interior of rectangle specified by the
     * Rectangle2D object intersects the interior of the Polygon.
     * 
     * @param rect
     *            the Rectangle2D object.
     * @return true, if the Rectangle2D intersects the interior of the Polygon,
     *         false otherwise.
     * @see java.awt.Shape#intersects(java.awt.geom.Rectangle2D)
     */
    public boolean intersects(Rectangle2D rect) {
        return intersects(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    /**
     * Gets the PathIterator object which gives the coordinates of the polygon,
     * transformed according to the specified AffineTransform.
     * 
     * @param t
     *            the specified AffineTransform object or null.
     * @return PathIterator object for the Polygon.
     * @see java.awt.Shape#getPathIterator(java.awt.geom.AffineTransform)
     */
    public PathIterator getPathIterator(AffineTransform t) {
        return new Iterator(t, this);
    }

    /**
     * Gets the PathIterator object which gives the coordinates of the polygon,
     * transformed according to the specified AffineTransform. The flatness
     * parameter is ignored.
     * 
     * @param t
     *            the specified AffineTransform object or null.
     * @param flatness
     *            the maximum number of the control points for a given curve
     *            which varies from colinear before a subdivided curve is
     *            replaced by a straight line connecting the endpoints. This
     *            parameter is ignored for the Polygon class.
     * @return PathIterator object for the Polygon.
     * @see java.awt.Shape#getPathIterator(java.awt.geom.AffineTransform,
     *      double)
     */
    public PathIterator getPathIterator(AffineTransform t, double flatness) {
        return new Iterator(t, this);
    }

}
