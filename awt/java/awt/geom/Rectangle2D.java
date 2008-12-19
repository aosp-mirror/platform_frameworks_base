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
import org.apache.harmony.misc.HashCode;

/**
 * The Class Rectangle2D represents a rectangle whose coordinates are given with
 * the correct precision to be used with the Graphics2D classes.
 * 
 * @since Android 1.0
 */
public abstract class Rectangle2D extends RectangularShape {

    /**
     * The Constant OUT_LEFT is a mask that is used to indicate that a given
     * point is outside the rectangle and to its left.
     */
    public static final int OUT_LEFT = 1;

    /**
     * The Constant OUT_TOP is a mask that is used to indicate that a given
     * point is outside the rectangle and above it.
     */
    public static final int OUT_TOP = 2;

    /**
     * The Constant OUT_RIGHT is a mask that is used to indicate that a given
     * point is outside the rectangle and to its right.
     */
    public static final int OUT_RIGHT = 4;

    /**
     * The Constant OUT_BOTTOM is a mask that is used to indicate that a given
     * point is outside the rectangle and above it.
     */
    public static final int OUT_BOTTOM = 8;

    /**
     * The Class Float is the subclass of Rectangle2D that represents a
     * rectangle whose data values are given as floats (with float-level
     * precision).
     * 
     * @since Android 1.0
     */
    public static class Float extends Rectangle2D {

        /**
         * The x coordinate of the rectangle's upper left corner.
         */
        public float x;

        /**
         * The y coordinate of the rectangle's upper left corner.
         */
        public float y;

        /**
         * The width of the rectangle.
         */
        public float width;

        /**
         * The height of the rectangle.
         */
        public float height;

        /**
         * Instantiates a new empty rectangle with float-precision data fields.
         */
        public Float() {
        }

        /**
         * Instantiates a new rectangle with the specified float-precision data.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         */
        public Float(float x, float y, float width, float height) {
            setRect(x, y, width, height);
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
        public boolean isEmpty() {
            return width <= 0.0f || height <= 0.0f;
        }

        /**
         * Sets the rectangle's data to the given values.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         */
        public void setRect(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public void setRect(double x, double y, double width, double height) {
            this.x = (float)x;
            this.y = (float)y;
            this.width = (float)width;
            this.height = (float)height;
        }

        @Override
        public void setRect(Rectangle2D r) {
            this.x = (float)r.getX();
            this.y = (float)r.getY();
            this.width = (float)r.getWidth();
            this.height = (float)r.getHeight();
        }

        @Override
        public int outcode(double px, double py) {
            int code = 0;

            if (width <= 0.0f) {
                code |= OUT_LEFT | OUT_RIGHT;
            } else if (px < x) {
                code |= OUT_LEFT;
            } else if (px > x + width) {
                code |= OUT_RIGHT;
            }

            if (height <= 0.0f) {
                code |= OUT_TOP | OUT_BOTTOM;
            } else if (py < y) {
                code |= OUT_TOP;
            } else if (py > y + height) {
                code |= OUT_BOTTOM;
            }

            return code;
        }

        @Override
        public Rectangle2D getBounds2D() {
            return new Float(x, y, width, height);
        }

        @Override
        public Rectangle2D createIntersection(Rectangle2D r) {
            Rectangle2D dst;
            if (r instanceof Double) {
                dst = new Rectangle2D.Double();
            } else {
                dst = new Rectangle2D.Float();
            }
            Rectangle2D.intersect(this, r, dst);
            return dst;
        }

        @Override
        public Rectangle2D createUnion(Rectangle2D r) {
            Rectangle2D dst;
            if (r instanceof Double) {
                dst = new Rectangle2D.Double();
            } else {
                dst = new Rectangle2D.Float();
            }
            Rectangle2D.union(this, r, dst);
            return dst;
        }

        @Override
        public String toString() {
            // The output format based on 1.5 release behaviour. It could be
            // obtained in the following way
            // System.out.println(new Rectangle2D.Float().toString())
            return getClass().getName()
                    + "[x=" + x + ",y=" + y + ",width=" + width + ",height=" + height + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
    }

    /**
     * The Class Double is the subclass of Rectangle2D that represents a
     * rectangle whose data values are given as doubles (with
     * double-precision-level precision).
     * 
     * @since Android 1.0
     */
    public static class Double extends Rectangle2D {

        /**
         * The x coordinate of the rectangle's upper left corner.
         */
        public double x;

        /**
         * The y coordinate of the rectangle's upper left corner.
         */
        public double y;

        /**
         * The width of the rectangle.
         */
        public double width;

        /**
         * The height of the rectangle.
         */
        public double height;

        /**
         * Instantiates a new empty rectangle with double-precision data fields.
         */
        public Double() {
        }

        /**
         * Instantiates a new rectangle with the given double values.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         */
        public Double(double x, double y, double width, double height) {
            setRect(x, y, width, height);
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
        public boolean isEmpty() {
            return width <= 0.0 || height <= 0.0;
        }

        @Override
        public void setRect(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public void setRect(Rectangle2D r) {
            this.x = r.getX();
            this.y = r.getY();
            this.width = r.getWidth();
            this.height = r.getHeight();
        }

        @Override
        public int outcode(double px, double py) {
            int code = 0;

            if (width <= 0.0) {
                code |= OUT_LEFT | OUT_RIGHT;
            } else if (px < x) {
                code |= OUT_LEFT;
            } else if (px > x + width) {
                code |= OUT_RIGHT;
            }

            if (height <= 0.0) {
                code |= OUT_TOP | OUT_BOTTOM;
            } else if (py < y) {
                code |= OUT_TOP;
            } else if (py > y + height) {
                code |= OUT_BOTTOM;
            }

            return code;
        }

        @Override
        public Rectangle2D getBounds2D() {
            return new Double(x, y, width, height);
        }

        @Override
        public Rectangle2D createIntersection(Rectangle2D r) {
            Rectangle2D dst = new Rectangle2D.Double();
            Rectangle2D.intersect(this, r, dst);
            return dst;
        }

        @Override
        public Rectangle2D createUnion(Rectangle2D r) {
            Rectangle2D dest = new Rectangle2D.Double();
            Rectangle2D.union(this, r, dest);
            return dest;
        }

        @Override
        public String toString() {
            // The output format based on 1.5 release behaviour. It could be
            // obtained in the following way
            // System.out.println(new Rectangle2D.Double().toString())
            return getClass().getName()
                    + "[x=" + x + ",y=" + y + ",width=" + width + ",height=" + height + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
    }

    /**
     * The Class Iterator provides access to the coordinates of the
     * Rectangle2D's boundary modified by an AffineTransform.
     */
    class Iterator implements PathIterator {

        /**
         * The x coordinate of the rectangle's upper left corner.
         */
        double x;

        /**
         * The y coordinate of the rectangle's upper left corner.
         */
        double y;

        /**
         * The width of the rectangle.
         */
        double width;

        /**
         * The height of the rectangle.
         */
        double height;

        /**
         * The AffineTransform that is used to modify the coordinates that are
         * returned by the path iterator.
         */
        AffineTransform t;

        /**
         * The current segment index.
         */
        int index;

        /**
         * Constructs a new Rectangle2D.Iterator for given rectangle and
         * transformation.
         * 
         * @param r
         *            the source Rectangle2D object.
         * @param at
         *            the AffineTransform object to apply to the coordinates
         *            before returning them.
         */
        Iterator(Rectangle2D r, AffineTransform at) {
            this.x = r.getX();
            this.y = r.getY();
            this.width = r.getWidth();
            this.height = r.getHeight();
            this.t = at;
            if (width < 0.0 || height < 0.0) {
                index = 6;
            }
        }

        public int getWindingRule() {
            return WIND_NON_ZERO;
        }

        public boolean isDone() {
            return index > 5;
        }

        public void next() {
            index++;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            if (index == 5) {
                return SEG_CLOSE;
            }
            int type;
            if (index == 0) {
                type = SEG_MOVETO;
                coords[0] = x;
                coords[1] = y;
            } else {
                type = SEG_LINETO;
                switch (index) {
                    case 1:
                        coords[0] = x + width;
                        coords[1] = y;
                        break;
                    case 2:
                        coords[0] = x + width;
                        coords[1] = y + height;
                        break;
                    case 3:
                        coords[0] = x;
                        coords[1] = y + height;
                        break;
                    case 4:
                        coords[0] = x;
                        coords[1] = y;
                        break;
                }
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return type;
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            if (index == 5) {
                return SEG_CLOSE;
            }
            int type;
            if (index == 0) {
                coords[0] = (float)x;
                coords[1] = (float)y;
                type = SEG_MOVETO;
            } else {
                type = SEG_LINETO;
                switch (index) {
                    case 1:
                        coords[0] = (float)(x + width);
                        coords[1] = (float)y;
                        break;
                    case 2:
                        coords[0] = (float)(x + width);
                        coords[1] = (float)(y + height);
                        break;
                    case 3:
                        coords[0] = (float)x;
                        coords[1] = (float)(y + height);
                        break;
                    case 4:
                        coords[0] = (float)x;
                        coords[1] = (float)y;
                        break;
                }
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return type;
        }

    }

    /**
     * Instantiates a new Rectangle2D.
     */
    protected Rectangle2D() {
    }

    /**
     * Sets the rectangle's location and dimension.
     * 
     * @param x
     *            the x coordinate of the rectangle's upper left corner.
     * @param y
     *            the y coordinate of the rectangle's upper left corner.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     */
    public abstract void setRect(double x, double y, double width, double height);

    /**
     * Gets the location of the point with respect to the rectangle and packs
     * the information into a single integer using the bitmasks
     * {@link Rectangle2D#OUT_LEFT}, {@link Rectangle2D#OUT_RIGHT},
     * {@link Rectangle2D#OUT_TOP}, and {@link Rectangle2D#OUT_BOTTOM}. If the
     * rectangle has zero or negative width, then every point is regarded as
     * being both to the left and to the right of the rectangle. Similarly, if
     * the height is zero or negative then all points are considered to be both
     * both above and below it.
     * 
     * @param x
     *            the x coordinate of the point to check.
     * @param y
     *            the y coordinate of the point to check.
     * @return the point's location with respect to the rectangle.
     */
    public abstract int outcode(double x, double y);

    /**
     * Creates an new rectangle that is the intersection of this rectangle with
     * the given rectangle. The resulting rectangle may be empty. The data of
     * this rectangle is left unchanged.
     * 
     * @param r
     *            the rectangle to intersect with this rectangle.
     * @return the new rectangle given by intersection.
     */
    public abstract Rectangle2D createIntersection(Rectangle2D r);

    /**
     * Creates an new rectangle that is the union of this rectangle with the
     * given rectangle. The new rectangle is the smallest rectangle which
     * contains both this rectangle and the rectangle specified as a parameter.
     * The data of this rectangle is left unchanged.
     * 
     * @param r
     *            the rectangle to combine with this rectangle.
     * @return the new rectangle given by union.
     */
    public abstract Rectangle2D createUnion(Rectangle2D r);

    /**
     * Sets the data of this rectangle to match the data of the given rectangle.
     * 
     * @param r
     *            the rectangle whose data is to be copied into this rectangle's
     *            fields.
     */
    public void setRect(Rectangle2D r) {
        setRect(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    @Override
    public void setFrame(double x, double y, double width, double height) {
        setRect(x, y, width, height);
    }

    public Rectangle2D getBounds2D() {
        return (Rectangle2D)clone();
    }

    /**
     * Determines whether any part of the line segment between (and including)
     * the two given points touches any part of the rectangle, including its
     * boundary.
     * 
     * @param x1
     *            the x coordinate of one of the points that determines the line
     *            segment to test.
     * @param y1
     *            the y coordinate of one of the points that determines the line
     *            segment to test.
     * @param x2
     *            the x coordinate of one of the points that determines the line
     *            segment to test.
     * @param y2
     *            the y coordinate of one of the points that determines the line
     *            segment to test.
     * @return true, if at least one point of the line segment between the two
     *         points matches any point of the interior of the rectangle or the
     *         rectangle's boundary.
     */
    public boolean intersectsLine(double x1, double y1, double x2, double y2) {
        double rx1 = getX();
        double ry1 = getY();
        double rx2 = rx1 + getWidth();
        double ry2 = ry1 + getHeight();
        return (rx1 <= x1 && x1 <= rx2 && ry1 <= y1 && y1 <= ry2)
                || (rx1 <= x2 && x2 <= rx2 && ry1 <= y2 && y2 <= ry2)
                || Line2D.linesIntersect(rx1, ry1, rx2, ry2, x1, y1, x2, y2)
                || Line2D.linesIntersect(rx2, ry1, rx1, ry2, x1, y1, x2, y2);
    }

    /**
     * Determines whether any part of the specified line segment touches any
     * part of the rectangle, including its boundary.
     * 
     * @param l
     *            the line segment to test.
     * @return true, if at least one point of the given line segment matches any
     *         point of the interior of the rectangle or the rectangle's
     *         boundary.
     */
    public boolean intersectsLine(Line2D l) {
        return intersectsLine(l.getX1(), l.getY1(), l.getX2(), l.getY2());
    }

    /**
     * Gets the location of the point with respect to the rectangle and packs
     * the information into a single integer using the bitmasks
     * {@link Rectangle2D#OUT_LEFT}, {@link Rectangle2D#OUT_RIGHT},
     * {@link Rectangle2D#OUT_TOP}, and {@link Rectangle2D#OUT_BOTTOM}. If the
     * rectangle has zero or negative width, then every point is regarded as
     * being both to the left and to the right of the rectangle. Similarly, if
     * the height is zero or negative then all points are considered to be both
     * both above and below it.
     * 
     * @param p
     *            the point to check.
     * @return the point's location with respect to the rectangle.
     */
    public int outcode(Point2D p) {
        return outcode(p.getX(), p.getY());
    }

    public boolean contains(double x, double y) {
        if (isEmpty()) {
            return false;
        }

        double x1 = getX();
        double y1 = getY();
        double x2 = x1 + getWidth();
        double y2 = y1 + getHeight();

        return x1 <= x && x < x2 && y1 <= y && y < y2;
    }

    public boolean intersects(double x, double y, double width, double height) {
        if (isEmpty() || width <= 0.0 || height <= 0.0) {
            return false;
        }

        double x1 = getX();
        double y1 = getY();
        double x2 = x1 + getWidth();
        double y2 = y1 + getHeight();

        return x + width > x1 && x < x2 && y + height > y1 && y < y2;
    }

    public boolean contains(double x, double y, double width, double height) {
        if (isEmpty() || width <= 0.0 || height <= 0.0) {
            return false;
        }

        double x1 = getX();
        double y1 = getY();
        double x2 = x1 + getWidth();
        double y2 = y1 + getHeight();

        return x1 <= x && x + width <= x2 && y1 <= y && y + height <= y2;
    }

    /**
     * Changes the data values of the destination rectangle to match the
     * intersection of the two source rectangles, leaving the two source
     * rectangles unchanged. The resulting rectangle may be empty.
     * 
     * @param src1
     *            one of the two source rectangles giving the data to intersect.
     * @param src2
     *            one of the two source rectangles giving the data to intersect.
     * @param dst
     *            the destination object where the data of the intersection is
     *            written.
     */
    public static void intersect(Rectangle2D src1, Rectangle2D src2, Rectangle2D dst) {
        double x1 = Math.max(src1.getMinX(), src2.getMinX());
        double y1 = Math.max(src1.getMinY(), src2.getMinY());
        double x2 = Math.min(src1.getMaxX(), src2.getMaxX());
        double y2 = Math.min(src1.getMaxY(), src2.getMaxY());
        dst.setFrame(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Changes the data values of the destination rectangle to match the union
     * of the two source rectangles, leaving the two source rectangles
     * unchanged. The union is the smallest rectangle that completely covers the
     * two source rectangles.
     * 
     * @param src1
     *            one of the two source rectangles giving the data.
     * @param src2
     *            one of the two source rectangles giving the data.
     * @param dst
     *            the destination object where the data of the union is written.
     */
    public static void union(Rectangle2D src1, Rectangle2D src2, Rectangle2D dst) {
        double x1 = Math.min(src1.getMinX(), src2.getMinX());
        double y1 = Math.min(src1.getMinY(), src2.getMinY());
        double x2 = Math.max(src1.getMaxX(), src2.getMaxX());
        double y2 = Math.max(src1.getMaxY(), src2.getMaxY());
        dst.setFrame(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Enlarges the rectangle so that it includes the given point.
     * 
     * @param x
     *            the x coordinate of the new point to be covered by the
     *            rectangle.
     * @param y
     *            the y coordinate of the new point to be covered by the
     *            rectangle.
     */
    public void add(double x, double y) {
        double x1 = Math.min(getMinX(), x);
        double y1 = Math.min(getMinY(), y);
        double x2 = Math.max(getMaxX(), x);
        double y2 = Math.max(getMaxY(), y);
        setRect(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Enlarges the rectangle so that it includes the given point.
     * 
     * @param p
     *            the new point to be covered by the rectangle.
     */
    public void add(Point2D p) {
        add(p.getX(), p.getY());
    }

    /**
     * Enlarges the rectangle so that it covers the given rectangle.
     * 
     * @param r
     *            the new rectangle to be covered by this rectangle.
     */
    public void add(Rectangle2D r) {
        union(this, r, this);
    }

    public PathIterator getPathIterator(AffineTransform t) {
        return new Iterator(this, t);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform t, double flatness) {
        return new Iterator(this, t);
    }

    @Override
    public int hashCode() {
        HashCode hash = new HashCode();
        hash.append(getX());
        hash.append(getY());
        hash.append(getWidth());
        hash.append(getHeight());
        return hash.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D)obj;
            return getX() == r.getX() && getY() == r.getY() && getWidth() == r.getWidth()
                    && getHeight() == r.getHeight();
        }
        return false;
    }

}
