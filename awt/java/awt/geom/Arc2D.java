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

import java.util.NoSuchElementException;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class Arc2D represents a segment of a curve inscribed in a rectangle. The
 * curve is defined by a start angle and an extent angle (the end angle minus
 * the start angle) as a pie wedge whose point is in the center of the
 * rectangle. The Arc2D as a shape may be either OPEN (including nothing but the
 * curved arc segment itself), CHORD (the curved arc segment closed by a
 * connecting segment from the end to the beginning of the arc, or PIE (the
 * segments from the end of the arc to the center of the rectangle and from the
 * center of the rectangle back to the arc's start point are included).
 * 
 * @since Android 1.0
 */
public abstract class Arc2D extends RectangularShape {

    /**
     * The arc type OPEN indicates that the shape includes only the curved arc
     * segment.
     */
    public final static int OPEN = 0;

    /**
     * The arc type CHORD indicates that as a shape the connecting segment from
     * the end point of the curved arc to the beginning point is included.
     */
    public final static int CHORD = 1;

    /**
     * The arc type PIE indicates that as a shape the two segments from the
     * arc's endpoint to the center of the rectangle and from the center of the
     * rectangle to the arc's endpoint are included.
     */
    public final static int PIE = 2;

    /**
     * The Class Float is a subclass of Arc2D in which all of the data values
     * are given as floats.
     * 
     * @see Arc2D.Double
     * @since Android 1.0
     */
    public static class Float extends Arc2D {

        /**
         * The x coordinate of the upper left corner of the rectangle that
         * contains the arc.
         */
        public float x;

        /**
         * The y coordinate of the upper left corner of the rectangle that
         * contains the arc.
         */
        public float y;

        /**
         * The width of the rectangle that contains the arc.
         */
        public float width;

        /**
         * The height of the rectangle that contains the arc.
         */
        public float height;

        /**
         * The start angle of the arc in degrees.
         */
        public float start;

        /**
         * The width angle of the arc in degrees.
         */
        public float extent;

        /**
         * Instantiates a new Arc2D of type OPEN with float values.
         */
        public Float() {
            super(OPEN);
        }

        /**
         * Instantiates a new Arc2D of the specified type with float values.
         * 
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Float(int type) {
            super(type);
        }

        /**
         * Instantiates a Arc2D with the specified float-valued data.
         * 
         * @param x
         *            the x coordinate of the upper left corner of the rectangle
         *            that contains the arc.
         * @param y
         *            the y coordinate of the upper left corner of the rectangle
         *            that contains the arc.
         * @param width
         *            the width of the rectangle that contains the arc.
         * @param height
         *            the height of the rectangle that contains the arc.
         * @param start
         *            the start angle of the arc in degrees.
         * @param extent
         *            the width angle of the arc in degrees.
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Float(float x, float y, float width, float height, float start, float extent,
                int type) {
            super(type);
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.start = start;
            this.extent = extent;
        }

        /**
         * Instantiates a new Angle2D with the specified float-valued data and
         * the bounding rectangle given by the parameter bounds.
         * 
         * @param bounds
         *            the bounding rectangle of the Angle2D.
         * @param start
         *            the start angle of the arc in degrees.
         * @param extent
         *            the width angle of the arc in degrees.
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Float(Rectangle2D bounds, float start, float extent, int type) {
            super(type);
            this.x = (float)bounds.getX();
            this.y = (float)bounds.getY();
            this.width = (float)bounds.getWidth();
            this.height = (float)bounds.getHeight();
            this.start = start;
            this.extent = extent;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getWidth() {
            return width;
        }

        @Override
        public double getHeight() {
            return height;
        }

        @Override
        public double getAngleStart() {
            return start;
        }

        @Override
        public double getAngleExtent() {
            return extent;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0.0f || height <= 0.0f;
        }

        @Override
        public void setArc(double x, double y, double width, double height, double start,
                double extent, int type) {
            this.setArcType(type);
            this.x = (float)x;
            this.y = (float)y;
            this.width = (float)width;
            this.height = (float)height;
            this.start = (float)start;
            this.extent = (float)extent;
        }

        @Override
        public void setAngleStart(double start) {
            this.start = (float)start;
        }

        @Override
        public void setAngleExtent(double extent) {
            this.extent = (float)extent;
        }

        @Override
        protected Rectangle2D makeBounds(double x, double y, double width, double height) {
            return new Rectangle2D.Float((float)x, (float)y, (float)width, (float)height);
        }

    }

    /**
     * The Class Double is a subclass of Arc2D in which all of the data values
     * are given as doubles.
     * 
     * @see Arc2D.Float
     * @since Android 1.0
     */
    public static class Double extends Arc2D {

        /**
         * The x coordinate of the upper left corner of the rectangle that
         * contains the arc.
         */
        public double x;

        /**
         * The y coordinate of the upper left corner of the rectangle that
         * contains the arc.
         */
        public double y;

        /**
         * The width of the rectangle that contains the arc.
         */
        public double width;

        /**
         * The height of the rectangle that contains the arc.
         */
        public double height;

        /**
         * The start angle of the arc in degrees.
         */
        public double start;

        /**
         * The width angle of the arc in degrees.
         */
        public double extent;

        /**
         * Instantiates a new Arc2D of type OPEN with double values.
         */
        public Double() {
            super(OPEN);
        }

        /**
         * Instantiates a new Arc2D of the specified type with double values.
         * 
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Double(int type) {
            super(type);
        }

        /**
         * Instantiates a Arc2D with the specified double-valued data.
         * 
         * @param x
         *            the x coordinate of the upper left corner of the rectangle
         *            that contains the arc.
         * @param y
         *            the y coordinate of the upper left corner of the rectangle
         *            that contains the arc.
         * @param width
         *            the width of the rectangle that contains the arc.
         * @param height
         *            the height of the rectangle that contains the arc.
         * @param start
         *            the start angle of the arc in degrees.
         * @param extent
         *            the width angle of the arc in degrees.
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Double(double x, double y, double width, double height, double start, double extent,
                int type) {
            super(type);
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.start = start;
            this.extent = extent;
        }

        /**
         * Instantiates a new Angle2D with the specified float-valued data and
         * the bounding rectangle given by the parameter bounds.
         * 
         * @param bounds
         *            the bounding rectangle of the Angle2D.
         * @param start
         *            the start angle of the arc in degrees.
         * @param extent
         *            the width angle of the arc in degrees.
         * @param type
         *            the type of the new Arc2D, either {@link Arc2D#OPEN},
         *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
         */
        public Double(Rectangle2D bounds, double start, double extent, int type) {
            super(type);
            this.x = bounds.getX();
            this.y = bounds.getY();
            this.width = bounds.getWidth();
            this.height = bounds.getHeight();
            this.start = start;
            this.extent = extent;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getWidth() {
            return width;
        }

        @Override
        public double getHeight() {
            return height;
        }

        @Override
        public double getAngleStart() {
            return start;
        }

        @Override
        public double getAngleExtent() {
            return extent;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0.0 || height <= 0.0;
        }

        @Override
        public void setArc(double x, double y, double width, double height, double start,
                double extent, int type) {
            this.setArcType(type);
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.start = start;
            this.extent = extent;
        }

        @Override
        public void setAngleStart(double start) {
            this.start = start;
        }

        @Override
        public void setAngleExtent(double extent) {
            this.extent = extent;
        }

        @Override
        protected Rectangle2D makeBounds(double x, double y, double width, double height) {
            return new Rectangle2D.Double(x, y, width, height);
        }

    }

    /**
     * The Class Iterator is the subclass of PathIterator that is used to
     * traverse the boundary of a shape of type Arc2D.
     */
    class Iterator implements PathIterator {

        /**
         * The x coordinate of the center of the arc's bounding rectangle.
         */
        double x;

        /**
         * The y coordinate of the center of the arc's bounding rectangle.
         */
        double y;

        /**
         * Half of the width of the arc's bounding rectangle (the radius in the
         * case of a circular arc).
         */
        double width;

        /**
         * Half of the height of the arc's bounding rectangle (the radius in the
         * case of a circular arc).
         */
        double height;

        /**
         * The start angle of the arc in degrees.
         */
        double angle;

        /**
         * The angle extent in degrees.
         */
        double extent;

        /**
         * The closure type of the arc.
         */
        int type;

        /**
         * The path iterator transformation.
         */
        AffineTransform t;

        /**
         * The current segment index.
         */
        int index;

        /**
         * The number of arc segments the source arc subdivided to be
         * approximated by Bezier curves. Depends on extent value.
         */
        int arcCount;

        /**
         * The number of line segments. Depends on closure type.
         */
        int lineCount;

        /**
         * The step to calculate next arc subdivision point.
         */
        double step;

        /**
         * The temporary value of cosinus of the current angle.
         */
        double cos;

        /**
         * The temporary value of sinus of the current angle.
         */
        double sin;

        /** The coefficient to calculate control points of Bezier curves. */
        double k;

        /**
         * The temporary value of x coordinate of the Bezier curve control
         * vector.
         */
        double kx;

        /**
         * The temporary value of y coordinate of the Bezier curve control
         * vector.
         */
        double ky;

        /**
         * The x coordinate of the first path point (MOVE_TO).
         */
        double mx;

        /**
         * The y coordinate of the first path point (MOVE_TO).
         */
        double my;

        /**
         * Constructs a new Arc2D.Iterator for given line and transformation
         * 
         * @param a
         *            the source Arc2D object.
         * @param t
         *            the AffineTransformation.
         */
        Iterator(Arc2D a, AffineTransform t) {
            if (width < 0 || height < 0) {
                arcCount = 0;
                lineCount = 0;
                index = 1;
                return;
            }

            this.width = a.getWidth() / 2.0;
            this.height = a.getHeight() / 2.0;
            this.x = a.getX() + width;
            this.y = a.getY() + height;
            this.angle = -Math.toRadians(a.getAngleStart());
            this.extent = -a.getAngleExtent();
            this.type = a.getArcType();
            this.t = t;

            if (Math.abs(extent) >= 360.0) {
                arcCount = 4;
                k = 4.0 / 3.0 * (Math.sqrt(2.0) - 1.0);
                step = Math.PI / 2.0;
                if (extent < 0.0) {
                    step = -step;
                    k = -k;
                }
            } else {
                arcCount = (int)Math.rint(Math.abs(extent) / 90.0);
                step = Math.toRadians(extent / arcCount);
                k = 4.0 / 3.0 * (1.0 - Math.cos(step / 2.0)) / Math.sin(step / 2.0);
            }

            lineCount = 0;
            if (type == Arc2D.CHORD) {
                lineCount++;
            } else if (type == Arc2D.PIE) {
                lineCount += 2;
            }
        }

        public int getWindingRule() {
            return WIND_NON_ZERO;
        }

        public boolean isDone() {
            return index > arcCount + lineCount;
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
                count = 1;
                cos = Math.cos(angle);
                sin = Math.sin(angle);
                kx = k * width * sin;
                ky = k * height * cos;
                coords[0] = mx = x + cos * width;
                coords[1] = my = y + sin * height;
            } else if (index <= arcCount) {
                type = SEG_CUBICTO;
                count = 3;
                coords[0] = mx - kx;
                coords[1] = my + ky;
                angle += step;
                cos = Math.cos(angle);
                sin = Math.sin(angle);
                kx = k * width * sin;
                ky = k * height * cos;
                coords[4] = mx = x + cos * width;
                coords[5] = my = y + sin * height;
                coords[2] = mx + kx;
                coords[3] = my - ky;
            } else if (index == arcCount + lineCount) {
                type = SEG_CLOSE;
                count = 0;
            } else {
                type = SEG_LINETO;
                count = 1;
                coords[0] = x;
                coords[1] = y;
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
                count = 1;
                cos = Math.cos(angle);
                sin = Math.sin(angle);
                kx = k * width * sin;
                ky = k * height * cos;
                coords[0] = (float)(mx = x + cos * width);
                coords[1] = (float)(my = y + sin * height);
            } else if (index <= arcCount) {
                type = SEG_CUBICTO;
                count = 3;
                coords[0] = (float)(mx - kx);
                coords[1] = (float)(my + ky);
                angle += step;
                cos = Math.cos(angle);
                sin = Math.sin(angle);
                kx = k * width * sin;
                ky = k * height * cos;
                coords[4] = (float)(mx = x + cos * width);
                coords[5] = (float)(my = y + sin * height);
                coords[2] = (float)(mx + kx);
                coords[3] = (float)(my - ky);
            } else if (index == arcCount + lineCount) {
                type = SEG_CLOSE;
                count = 0;
            } else {
                type = SEG_LINETO;
                count = 1;
                coords[0] = (float)x;
                coords[1] = (float)y;
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, count);
            }
            return type;
        }

    }

    /**
     * The closure type of the arc.
     */
    private int type;

    /**
     * Instantiates a new arc2D.
     * 
     * @param type
     *            the closure type.
     */
    protected Arc2D(int type) {
        setArcType(type);
    }

    /**
     * Takes the double-valued data and creates the corresponding Rectangle2D
     * object with values either of type float or of type double depending on
     * whether this Arc2D instance is of type Float or Double.
     * 
     * @param x
     *            the x coordinate of the upper left corner of the bounding
     *            rectangle.
     * @param y
     *            the y coordinate of the upper left corner of the bounding
     *            rectangle.
     * @param width
     *            the width of the bounding rectangle.
     * @param height
     *            the height of the bounding rectangle.
     * @return the corresponding Rectangle2D object.
     */
    protected abstract Rectangle2D makeBounds(double x, double y, double width, double height);

    /**
     * Gets the start angle.
     * 
     * @return the start angle.
     */
    public abstract double getAngleStart();

    /**
     * Gets the width angle.
     * 
     * @return the width angle.
     */
    public abstract double getAngleExtent();

    /**
     * Sets the start angle.
     * 
     * @param start
     *            the new start angle.
     */
    public abstract void setAngleStart(double start);

    /**
     * Sets the width angle.
     * 
     * @param extent
     *            the new width angle.
     */
    public abstract void setAngleExtent(double extent);

    /**
     * Sets the data values that define the arc.
     * 
     * @param x
     *            the x coordinate of the upper left corner of the rectangle
     *            that contains the arc.
     * @param y
     *            the y coordinate of the upper left corner of the rectangle
     *            that contains the arc.
     * @param width
     *            the width of the rectangle that contains the arc.
     * @param height
     *            the height of the rectangle that contains the arc.
     * @param start
     *            the start angle of the arc in degrees.
     * @param extent
     *            the width angle of the arc in degrees.
     * @param type
     *            the type of the new Arc2D, either {@link Arc2D#OPEN},
     *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
     */
    public abstract void setArc(double x, double y, double width, double height, double start,
            double extent, int type);

    /**
     * Gets the arc type, either {@link Arc2D#OPEN}, {@link Arc2D#CHORD}, or
     * {@link Arc2D#PIE}.
     * 
     * @return the arc type.
     */
    public int getArcType() {
        return type;
    }

    /**
     * Sets the arc type, either {@link Arc2D#OPEN}, {@link Arc2D#CHORD}, or
     * {@link Arc2D#PIE}.
     * 
     * @param type
     *            the new arc type.
     */
    public void setArcType(int type) {
        if (type != OPEN && type != CHORD && type != PIE) {
            // awt.205=Invalid type of Arc: {0}
            throw new IllegalArgumentException(Messages.getString("awt.205", type)); //$NON-NLS-1$
        }
        this.type = type;
    }

    /**
     * Gets the start point of the arc as a Point2D.
     * 
     * @return the start point of the curved arc segment.
     */
    public Point2D getStartPoint() {
        double a = Math.toRadians(getAngleStart());
        return new Point2D.Double(getX() + (1.0 + Math.cos(a)) * getWidth() / 2.0, getY()
                + (1.0 - Math.sin(a)) * getHeight() / 2.0);
    }

    /**
     * Gets the end point of the arc as a Point2D.
     * 
     * @return the end point of the curved arc segment.
     */
    public Point2D getEndPoint() {
        double a = Math.toRadians(getAngleStart() + getAngleExtent());
        return new Point2D.Double(getX() + (1.0 + Math.cos(a)) * getWidth() / 2.0, getY()
                + (1.0 - Math.sin(a)) * getHeight() / 2.0);
    }

    public Rectangle2D getBounds2D() {
        if (isEmpty()) {
            return makeBounds(getX(), getY(), getWidth(), getHeight());
        }
        double rx1 = getX();
        double ry1 = getY();
        double rx2 = rx1 + getWidth();
        double ry2 = ry1 + getHeight();

        Point2D p1 = getStartPoint();
        Point2D p2 = getEndPoint();

        double bx1 = containsAngle(180.0) ? rx1 : Math.min(p1.getX(), p2.getX());
        double by1 = containsAngle(90.0) ? ry1 : Math.min(p1.getY(), p2.getY());
        double bx2 = containsAngle(0.0) ? rx2 : Math.max(p1.getX(), p2.getX());
        double by2 = containsAngle(270.0) ? ry2 : Math.max(p1.getY(), p2.getY());

        if (type == PIE) {
            double cx = getCenterX();
            double cy = getCenterY();
            bx1 = Math.min(bx1, cx);
            by1 = Math.min(by1, cy);
            bx2 = Math.max(bx2, cx);
            by2 = Math.max(by2, cy);
        }
        return makeBounds(bx1, by1, bx2 - bx1, by2 - by1);
    }

    @Override
    public void setFrame(double x, double y, double width, double height) {
        setArc(x, y, width, height, getAngleStart(), getAngleExtent(), type);
    }

    /**
     * Sets the data that defines the arc.
     * 
     * @param point
     *            the upper left corner of the bounding rectangle.
     * @param size
     *            the size of the bounding rectangle.
     * @param start
     *            the start angle of the arc in degrees.
     * @param extent
     *            the angle width of the arc in degrees.
     * @param type
     *            the closure type, either {@link Arc2D#OPEN},
     *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
     */
    public void setArc(Point2D point, Dimension2D size, double start, double extent, int type) {
        setArc(point.getX(), point.getY(), size.getWidth(), size.getHeight(), start, extent, type);
    }

    /**
     * Sets the data that defines the arc.
     * 
     * @param rect
     *            the arc's bounding rectangle.
     * @param start
     *            the start angle of the arc in degrees.
     * @param extent
     *            the angle width of the arc in degrees.
     * @param type
     *            the closure type, either {@link Arc2D#OPEN},
     *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
     */
    public void setArc(Rectangle2D rect, double start, double extent, int type) {
        setArc(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), start, extent, type);
    }

    /**
     * Sets the data that defines the arc by copying it from another Arc2D.
     * 
     * @param arc
     *            the arc whose data is copied into this arc.
     */
    public void setArc(Arc2D arc) {
        setArc(arc.getX(), arc.getY(), arc.getWidth(), arc.getHeight(), arc.getAngleStart(), arc
                .getAngleExtent(), arc.getArcType());
    }

    /**
     * Sets the data for a circular arc by giving its center and radius.
     * 
     * @param x
     *            the x coordinate of the center of the circle.
     * @param y
     *            the y coordinate of the center of the circle.
     * @param radius
     *            the radius of the circle.
     * @param start
     *            the start angle of the arc in degrees.
     * @param extent
     *            the angle width of the arc in degrees.
     * @param type
     *            the closure type, either {@link Arc2D#OPEN},
     *            {@link Arc2D#CHORD}, or {@link Arc2D#PIE}.
     */
    public void setArcByCenter(double x, double y, double radius, double start, double extent,
            int type) {
        setArc(x - radius, y - radius, radius * 2.0, radius * 2.0, start, extent, type);
    }

    /**
     * Sets the arc data for a circular arc based on two tangent lines and the
     * radius. The two tangent lines are the lines from p1 to p2 and from p2 to
     * p3, which determine a unique circle with the given radius. The start and
     * end points of the arc are the points where the circle touches the two
     * lines, and the arc itself is the shorter of the two circle segments
     * determined by the two points (in other words, it is the piece of the
     * circle that is closer to the lines' intersection point p2 and forms a
     * concave shape with the segments from p1 to p2 and from p2 to p3).
     * 
     * @param p1
     *            a point which determines one of the two tangent lines (with
     *            p2).
     * @param p2
     *            the point of intersection of the two tangent lines.
     * @param p3
     *            a point which determines one of the two tangent lines (with
     *            p2).
     * @param radius
     *            the radius of the circular arc.
     */
    public void setArcByTangent(Point2D p1, Point2D p2, Point2D p3, double radius) {
        // Used simple geometric calculations of arc center, radius and angles
        // by tangents
        double a1 = -Math.atan2(p1.getY() - p2.getY(), p1.getX() - p2.getX());
        double a2 = -Math.atan2(p3.getY() - p2.getY(), p3.getX() - p2.getX());
        double am = (a1 + a2) / 2.0;
        double ah = a1 - am;
        double d = radius / Math.abs(Math.sin(ah));
        double x = p2.getX() + d * Math.cos(am);
        double y = p2.getY() - d * Math.sin(am);
        ah = ah >= 0.0 ? Math.PI * 1.5 - ah : Math.PI * 0.5 - ah;
        a1 = getNormAngle(Math.toDegrees(am - ah));
        a2 = getNormAngle(Math.toDegrees(am + ah));
        double delta = a2 - a1;
        if (delta <= 0.0) {
            delta += 360.0;
        }
        setArcByCenter(x, y, radius, a1, delta, type);
    }

    /**
     * Sets a new start angle to be the angle given by the the vector from the
     * current center point to the specified point.
     * 
     * @param point
     *            the point that determines the new start angle.
     */
    public void setAngleStart(Point2D point) {
        double angle = Math.atan2(point.getY() - getCenterY(), point.getX() - getCenterX());
        setAngleStart(getNormAngle(-Math.toDegrees(angle)));
    }

    /**
     * Sets the angles in terms of vectors from the current arc center to the
     * points (x1, y1) and (x2, y2). The start angle is given by the vector from
     * the current center to the point (x1, y1) and the end angle is given by
     * the vector from the center to the point (x2, y2).
     * 
     * @param x1
     *            the x coordinate of the point whose vector from the center
     *            point determines the new start angle of the arc.
     * @param y1
     *            the y coordinate of the point whose vector from the center
     *            point determines the new start angle of the arc.
     * @param x2
     *            the x coordinate of the point whose vector from the center
     *            point determines the new end angle of the arc.
     * @param y2
     *            the y coordinate of the point whose vector from the center
     *            point determines the new end angle of the arc.
     */
    public void setAngles(double x1, double y1, double x2, double y2) {
        double cx = getCenterX();
        double cy = getCenterY();
        double a1 = getNormAngle(-Math.toDegrees(Math.atan2(y1 - cy, x1 - cx)));
        double a2 = getNormAngle(-Math.toDegrees(Math.atan2(y2 - cy, x2 - cx)));
        a2 -= a1;
        if (a2 <= 0.0) {
            a2 += 360.0;
        }
        setAngleStart(a1);
        setAngleExtent(a2);
    }

    /**
     * Sets the angles in terms of vectors from the current arc center to the
     * points p1 and p2. The start angle is given by the vector from the current
     * center to the point p1 and the end angle is given by the vector from the
     * center to the point p2.
     * 
     * @param p1
     *            the point whose vector from the center point determines the
     *            new start angle of the arc.
     * @param p2
     *            the point whose vector from the center point determines the
     *            new end angle of the arc.
     */
    public void setAngles(Point2D p1, Point2D p2) {
        setAngles(p1.getX(), p1.getY(), p2.getX(), p2.getY());
    }

    /**
     * Normalizes the angle by removing extra winding (past 360 degrees) and
     * placing it in the positive degree range.
     * 
     * @param angle
     *            the source angle in degrees.
     * @return an angle between 0 and 360 degrees which corresponds to the same
     *         direction vector as the source angle.
     */
    double getNormAngle(double angle) {
        double n = Math.floor(angle / 360.0);
        return angle - n * 360.0;
    }

    /**
     * Determines whether the given angle is contained in the span of the arc.
     * 
     * @param angle
     *            the angle to test in degrees.
     * @return true, if the given angle is between the start angle and the end
     *         angle of the arc.
     */
    public boolean containsAngle(double angle) {
        double extent = getAngleExtent();
        if (extent >= 360.0) {
            return true;
        }
        angle = getNormAngle(angle);
        double a1 = getNormAngle(getAngleStart());
        double a2 = a1 + extent;
        if (a2 > 360.0) {
            return angle >= a1 || angle <= a2 - 360.0;
        }
        if (a2 < 0.0) {
            return angle >= a2 + 360.0 || angle <= a1;
        }
        return extent > 0.0 ? a1 <= angle && angle <= a2 : a2 <= angle && angle <= a1;
    }

    public boolean contains(double px, double py) {
        // Normalize point
        double nx = (px - getX()) / getWidth() - 0.5;
        double ny = (py - getY()) / getHeight() - 0.5;

        if ((nx * nx + ny * ny) > 0.25) {
            return false;
        }

        double extent = getAngleExtent();
        double absExtent = Math.abs(extent);
        if (absExtent >= 360.0) {
            return true;
        }

        boolean containsAngle = containsAngle(Math.toDegrees(-Math.atan2(ny, nx)));
        if (type == PIE) {
            return containsAngle;
        }
        if (absExtent <= 180.0 && !containsAngle) {
            return false;
        }

        Line2D l = new Line2D.Double(getStartPoint(), getEndPoint());
        int ccw1 = l.relativeCCW(px, py);
        int ccw2 = l.relativeCCW(getCenterX(), getCenterY());
        return ccw1 == 0 || ccw2 == 0 || ((ccw1 + ccw2) == 0 ^ absExtent > 180.0);
    }

    public boolean contains(double rx, double ry, double rw, double rh) {

        if (!(contains(rx, ry) && contains(rx + rw, ry) && contains(rx + rw, ry + rh) && contains(
                rx, ry + rh))) {
            return false;
        }

        double absExtent = Math.abs(getAngleExtent());
        if (type != PIE || absExtent <= 180.0 || absExtent >= 360.0) {
            return true;
        }

        Rectangle2D r = new Rectangle2D.Double(rx, ry, rw, rh);

        double cx = getCenterX();
        double cy = getCenterY();
        if (r.contains(cx, cy)) {
            return false;
        }

        Point2D p1 = getStartPoint();
        Point2D p2 = getEndPoint();

        return !r.intersectsLine(cx, cy, p1.getX(), p1.getY())
                && !r.intersectsLine(cx, cy, p2.getX(), p2.getY());
    }

    @Override
    public boolean contains(Rectangle2D rect) {
        return contains(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    public boolean intersects(double rx, double ry, double rw, double rh) {

        if (isEmpty() || rw <= 0.0 || rh <= 0.0) {
            return false;
        }

        // Check: Does arc contain rectangle's points
        if (contains(rx, ry) || contains(rx + rw, ry) || contains(rx, ry + rh)
                || contains(rx + rw, ry + rh)) {
            return true;
        }

        double cx = getCenterX();
        double cy = getCenterY();
        Point2D p1 = getStartPoint();
        Point2D p2 = getEndPoint();
        Rectangle2D r = new Rectangle2D.Double(rx, ry, rw, rh);

        // Check: Does rectangle contain arc's points
        if (r.contains(p1) || r.contains(p2) || (type == PIE && r.contains(cx, cy))) {
            return true;
        }

        if (type == PIE) {
            if (r.intersectsLine(p1.getX(), p1.getY(), cx, cy)
                    || r.intersectsLine(p2.getX(), p2.getY(), cx, cy)) {
                return true;
            }
        } else {
            if (r.intersectsLine(p1.getX(), p1.getY(), p2.getX(), p2.getY())) {
                return true;
            }
        }

        // Nearest rectangle point
        double nx = cx < rx ? rx : (cx > rx + rw ? rx + rw : cx);
        double ny = cy < ry ? ry : (cy > ry + rh ? ry + rh : cy);
        return contains(nx, ny);
    }

    public PathIterator getPathIterator(AffineTransform at) {
        return new Iterator(this, at);
    }

}
