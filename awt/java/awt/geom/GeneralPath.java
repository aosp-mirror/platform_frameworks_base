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
 * The class GeneralPath represents a shape whose outline is given by different
 * types of curved and straight segments.
 * 
 * @since Android 1.0
 */
public final class GeneralPath implements Shape, Cloneable {

    /**
     * The Constant WIND_EVEN_ODD see {@link PathIterator#WIND_EVEN_ODD}.
     */
    public static final int WIND_EVEN_ODD = PathIterator.WIND_EVEN_ODD;

    /**
     * The Constant WIND_NON_ZERO see {@link PathIterator#WIND_NON_ZERO}.
     */
    public static final int WIND_NON_ZERO = PathIterator.WIND_NON_ZERO;

    /**
     * The buffers size.
     */
    private static final int BUFFER_SIZE = 10;

    /**
     * The buffers capacity.
     */
    private static final int BUFFER_CAPACITY = 10;

    /**
     * The point's types buffer.
     */
    byte[] types;

    /**
     * The points buffer.
     */
    float[] points;

    /**
     * The point's type buffer size.
     */
    int typeSize;

    /**
     * The points buffer size.
     */
    int pointSize;

    /**
     * The path rule.
     */
    int rule;

    /**
     * The space amount in points buffer for different segmenet's types.
     */
    static int pointShift[] = {
            2, // MOVETO
            2, // LINETO
            4, // QUADTO
            6, // CUBICTO
            0
    }; // CLOSE

    /*
     * GeneralPath path iterator
     */
    /**
     * The Class Iterator is the subclass of Iterator for traversing the outline
     * of a GeneralPath.
     */
    class Iterator implements PathIterator {

        /**
         * The current cursor position in types buffer.
         */
        int typeIndex;

        /**
         * The current cursor position in points buffer.
         */
        int pointIndex;

        /**
         * The source GeneralPath object.
         */
        GeneralPath p;

        /**
         * The path iterator transformation.
         */
        AffineTransform t;

        /**
         * Constructs a new GeneralPath.Iterator for given general path.
         * 
         * @param path
         *            the source GeneralPath object.
         */
        Iterator(GeneralPath path) {
            this(path, null);
        }

        /**
         * Constructs a new GeneralPath.Iterator for given general path and
         * transformation.
         * 
         * @param path
         *            the source GeneralPath object.
         * @param at
         *            the AffineTransform object to apply rectangle path.
         */
        Iterator(GeneralPath path, AffineTransform at) {
            this.p = path;
            this.t = at;
        }

        public int getWindingRule() {
            return p.getWindingRule();
        }

        public boolean isDone() {
            return typeIndex >= p.typeSize;
        }

        public void next() {
            typeIndex++;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type = p.types[typeIndex];
            int count = GeneralPath.pointShift[type];
            for (int i = 0; i < count; i++) {
                coords[i] = p.points[pointIndex + i];
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, count / 2);
            }
            pointIndex += count;
            return type;
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type = p.types[typeIndex];
            int count = GeneralPath.pointShift[type];
            System.arraycopy(p.points, pointIndex, coords, 0, count);
            if (t != null) {
                t.transform(coords, 0, coords, 0, count / 2);
            }
            pointIndex += count;
            return type;
        }

    }

    /**
     * Instantiates a new general path with the winding rule set to
     * {@link PathIterator#WIND_NON_ZERO} and the initial capacity (number of
     * segments) set to the default value 10.
     */
    public GeneralPath() {
        this(WIND_NON_ZERO, BUFFER_SIZE);
    }

    /**
     * Instantiates a new general path with the given winding rule and the
     * initial capacity (number of segments) set to the default value 10.
     * 
     * @param rule
     *            the winding rule, either {@link PathIterator#WIND_EVEN_ODD} or
     *            {@link PathIterator#WIND_NON_ZERO}.
     */
    public GeneralPath(int rule) {
        this(rule, BUFFER_SIZE);
    }

    /**
     * Instantiates a new general path with the given winding rule and initial
     * capacity (number of segments).
     * 
     * @param rule
     *            the winding rule, either {@link PathIterator#WIND_EVEN_ODD} or
     *            {@link PathIterator#WIND_NON_ZERO}.
     * @param initialCapacity
     *            the number of segments the path is set to hold.
     */
    public GeneralPath(int rule, int initialCapacity) {
        setWindingRule(rule);
        types = new byte[initialCapacity];
        points = new float[initialCapacity * 2];
    }

    /**
     * Creates a new GeneralPath from the outline of the given shape.
     * 
     * @param shape
     *            the shape.
     */
    public GeneralPath(Shape shape) {
        this(WIND_NON_ZERO, BUFFER_SIZE);
        PathIterator p = shape.getPathIterator(null);
        setWindingRule(p.getWindingRule());
        append(p, false);
    }

    /**
     * Sets the winding rule, which determines how to decide whether a point
     * that isn't on the path itself is inside or outside of the shape.
     * 
     * @param rule
     *            the new winding rule.
     * @throws IllegalArgumentException
     *             if the winding rule is neither
     *             {@link PathIterator#WIND_EVEN_ODD} nor
     *             {@link PathIterator#WIND_NON_ZERO}.
     */
    public void setWindingRule(int rule) {
        if (rule != WIND_EVEN_ODD && rule != WIND_NON_ZERO) {
            // awt.209=Invalid winding rule value
            throw new java.lang.IllegalArgumentException(Messages.getString("awt.209")); //$NON-NLS-1$
        }
        this.rule = rule;
    }

    /**
     * Gets the winding rule.
     * 
     * @return the winding rule, either {@link PathIterator#WIND_EVEN_ODD} or
     *         {@link PathIterator#WIND_NON_ZERO}.
     */
    public int getWindingRule() {
        return rule;
    }

    /**
     * Checks the point data buffer sizes to see whether pointCount additional
     * point-data elements can fit. (Note that the number of point data elements
     * to add is more than one per point -- it depends on the type of point
     * being added.) Reallocates the buffers to enlarge the size if necessary.
     * 
     * @param pointCount
     *            the number of point data elements to be added.
     * @param checkMove
     *            whether to check for existing points.
     * @throws IllegalPathStateException
     *             checkMove is true and the path is currently empty.
     */
    void checkBuf(int pointCount, boolean checkMove) {
        if (checkMove && typeSize == 0) {
            // awt.20A=First segment should be SEG_MOVETO type
            throw new IllegalPathStateException(Messages.getString("awt.20A")); //$NON-NLS-1$
        }
        if (typeSize == types.length) {
            byte tmp[] = new byte[typeSize + BUFFER_CAPACITY];
            System.arraycopy(types, 0, tmp, 0, typeSize);
            types = tmp;
        }
        if (pointSize + pointCount > points.length) {
            float tmp[] = new float[pointSize + Math.max(BUFFER_CAPACITY * 2, pointCount)];
            System.arraycopy(points, 0, tmp, 0, pointSize);
            points = tmp;
        }
    }

    /**
     * Appends a new point to the end of this general path, disconnected from
     * the existing path.
     * 
     * @param x
     *            the x coordinate of the next point to append.
     * @param y
     *            the y coordinate of the next point to append.
     */
    public void moveTo(float x, float y) {
        if (typeSize > 0 && types[typeSize - 1] == PathIterator.SEG_MOVETO) {
            points[pointSize - 2] = x;
            points[pointSize - 1] = y;
        } else {
            checkBuf(2, false);
            types[typeSize++] = PathIterator.SEG_MOVETO;
            points[pointSize++] = x;
            points[pointSize++] = y;
        }
    }

    /**
     * Appends a new segment to the end of this general path by making a
     * straight line segment from the current endpoint to the given new point.
     * 
     * @param x
     *            the x coordinate of the next point to append.
     * @param y
     *            the y coordinate of the next point to append.
     */
    public void lineTo(float x, float y) {
        checkBuf(2, true);
        types[typeSize++] = PathIterator.SEG_LINETO;
        points[pointSize++] = x;
        points[pointSize++] = y;
    }

    /**
     * Appends a new segment to the end of this general path by making a
     * quadratic curve from the current endpoint to the point (x2, y2) using the
     * point (x1, y1) as the quadratic curve's control point.
     * 
     * @param x1
     *            the x coordinate of the quadratic curve's control point.
     * @param y1
     *            the y coordinate of the quadratic curve's control point.
     * @param x2
     *            the x coordinate of the quadratic curve's end point.
     * @param y2
     *            the y coordinate of the quadratic curve's end point.
     */
    public void quadTo(float x1, float y1, float x2, float y2) {
        checkBuf(4, true);
        types[typeSize++] = PathIterator.SEG_QUADTO;
        points[pointSize++] = x1;
        points[pointSize++] = y1;
        points[pointSize++] = x2;
        points[pointSize++] = y2;
    }

    /**
     * Appends a new segment to the end of this general path by making a cubic
     * curve from the current endpoint to the point (x3, y3) using (x1, y1) and
     * (x2, y2) as control points.
     * 
     * @see java.awt.geom.CubicCurve2D
     * @param x1
     *            the x coordinate of the new cubic segment's first control
     *            point.
     * @param y1
     *            the y coordinate of the new cubic segment's first control
     *            point.
     * @param x2
     *            the x coordinate of the new cubic segment's second control
     *            point.
     * @param y2
     *            the y coordinate of the new cubic segment's second control
     *            point.
     * @param x3
     *            the x coordinate of the new cubic segment's end point.
     * @param y3
     *            the y coordinate of the new cubic segment's end point.
     */
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        checkBuf(6, true);
        types[typeSize++] = PathIterator.SEG_CUBICTO;
        points[pointSize++] = x1;
        points[pointSize++] = y1;
        points[pointSize++] = x2;
        points[pointSize++] = y2;
        points[pointSize++] = x3;
        points[pointSize++] = y3;
    }

    /**
     * Appends the type information to declare that the current endpoint closes
     * the curve.
     */
    public void closePath() {
        if (typeSize == 0 || types[typeSize - 1] != PathIterator.SEG_CLOSE) {
            checkBuf(0, true);
            types[typeSize++] = PathIterator.SEG_CLOSE;
        }
    }

    /**
     * Appends the outline of the specified shape onto the end of this
     * GeneralPath.
     * 
     * @param shape
     *            the shape whose outline is to be appended.
     * @param connect
     *            true to connect this path's current endpoint to the first
     *            point of the shape's outline or false to append the shape's
     *            outline without connecting it.
     * @throws NullPointerException
     *             if the shape parameter is null.
     */
    public void append(Shape shape, boolean connect) {
        PathIterator p = shape.getPathIterator(null);
        append(p, connect);
    }

    /**
     * Appends the path defined by the specified PathIterator onto the end of
     * this GeneralPath.
     * 
     * @param path
     *            the PathIterator that defines the new path to append.
     * @param connect
     *            true to connect this path's current endpoint to the first
     *            point of the shape's outline or false to append the shape's
     *            outline without connecting it.
     */
    public void append(PathIterator path, boolean connect) {
        while (!path.isDone()) {
            float coords[] = new float[6];
            switch (path.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (!connect || typeSize == 0) {
                        moveTo(coords[0], coords[1]);
                        break;
                    }
                    if (types[typeSize - 1] != PathIterator.SEG_CLOSE
                            && points[pointSize - 2] == coords[0]
                            && points[pointSize - 1] == coords[1]) {
                        break;
                    }
                    // NO BREAK;
                case PathIterator.SEG_LINETO:
                    lineTo(coords[0], coords[1]);
                    break;
                case PathIterator.SEG_QUADTO:
                    quadTo(coords[0], coords[1], coords[2], coords[3]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    closePath();
                    break;
            }
            path.next();
            connect = false;
        }
    }

    /**
     * Gets the current end point of the path.
     * 
     * @return the current end point of the path.
     */
    public Point2D getCurrentPoint() {
        if (typeSize == 0) {
            return null;
        }
        int j = pointSize - 2;
        if (types[typeSize - 1] == PathIterator.SEG_CLOSE) {

            for (int i = typeSize - 2; i > 0; i--) {
                int type = types[i];
                if (type == PathIterator.SEG_MOVETO) {
                    break;
                }
                j -= pointShift[type];
            }
        }
        return new Point2D.Float(points[j], points[j + 1]);
    }

    /**
     * Resets the GeneralPath to being an empty path. The underlying point and
     * segment data is not deleted but rather the end indices of the data arrays
     * are set to zero.
     */
    public void reset() {
        typeSize = 0;
        pointSize = 0;
    }

    /**
     * Transform all of the coordinates of this path according to the specified
     * AffineTransform.
     * 
     * @param t
     *            the AffineTransform.
     */
    public void transform(AffineTransform t) {
        t.transform(points, 0, points, 0, pointSize / 2);
    }

    /**
     * Creates a new GeneralPath whose data is given by this path's data
     * transformed according to the specified AffineTransform.
     * 
     * @param t
     *            the AffineTransform.
     * @return the new GeneralPath whose data is given by this path's data
     *         transformed according to the specified AffineTransform.
     */
    public Shape createTransformedShape(AffineTransform t) {
        GeneralPath p = (GeneralPath)clone();
        if (t != null) {
            p.transform(t);
        }
        return p;
    }

    public Rectangle2D getBounds2D() {
        float rx1, ry1, rx2, ry2;
        if (pointSize == 0) {
            rx1 = ry1 = rx2 = ry2 = 0.0f;
        } else {
            int i = pointSize - 1;
            ry1 = ry2 = points[i--];
            rx1 = rx2 = points[i--];
            while (i > 0) {
                float y = points[i--];
                float x = points[i--];
                if (x < rx1) {
                    rx1 = x;
                } else if (x > rx2) {
                    rx2 = x;
                }
                if (y < ry1) {
                    ry1 = y;
                } else if (y > ry2) {
                    ry2 = y;
                }
            }
        }
        return new Rectangle2D.Float(rx1, ry1, rx2 - rx1, ry2 - ry1);
    }

    public Rectangle getBounds() {
        return getBounds2D().getBounds();
    }

    /**
     * Checks the cross count (number of times a ray from the point crosses the
     * shape's boundary) to determine whether the number of crossings
     * corresponds to a point inside the shape or not (according to the shape's
     * path rule).
     * 
     * @param cross
     *            the point's cross count.
     * @return true if the point is inside the path, or false otherwise.
     */
    boolean isInside(int cross) {
        if (rule == WIND_NON_ZERO) {
            return Crossing.isInsideNonZero(cross);
        }
        return Crossing.isInsideEvenOdd(cross);
    }

    public boolean contains(double px, double py) {
        return isInside(Crossing.crossShape(this, px, py));
    }

    public boolean contains(double rx, double ry, double rw, double rh) {
        int cross = Crossing.intersectShape(this, rx, ry, rw, rh);
        return cross != Crossing.CROSSING && isInside(cross);
    }

    public boolean intersects(double rx, double ry, double rw, double rh) {
        int cross = Crossing.intersectShape(this, rx, ry, rw, rh);
        return cross == Crossing.CROSSING || isInside(cross);
    }

    public boolean contains(Point2D p) {
        return contains(p.getX(), p.getY());
    }

    public boolean contains(Rectangle2D r) {
        return contains(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public boolean intersects(Rectangle2D r) {
        return intersects(r.getX(), r.getY(), r.getWidth(), r.getHeight());
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
            GeneralPath p = (GeneralPath)super.clone();
            p.types = types.clone();
            p.points = points.clone();
            return p;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

}
