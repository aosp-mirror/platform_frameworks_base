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
 * The Class RoundRectangle2D describes a rectangle with rounded corners with
 * high-precision data that is appropriate for geometric operations.
 * 
 * @since Android 1.0
 */
public abstract class RoundRectangle2D extends RectangularShape {

    /**
     * The Class Float is the subclass of RoundRectangle2D that has all of its
     * data values stored with float-level precision.
     * 
     * @since Android 1.0
     */
    public static class Float extends RoundRectangle2D {

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
         * The arc width of the rounded corners.
         */
        public float arcwidth;

        /**
         * The arc height of the rounded corners.
         */
        public float archeight;

        /**
         * Instantiates a new float-valued RoundRectangle2D with its data-values
         * set to zero.
         */
        public Float() {
        }

        /**
         * Instantiates a new float-valued RoundRectangle2D with the specified
         * data values.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         * @param arcwidth
         *            the arc width of the rounded corners.
         * @param archeight
         *            the arc height of the rounded corners.
         */
        public Float(float x, float y, float width, float height, float arcwidth, float archeight) {
            setRoundRect(x, y, width, height, arcwidth, archeight);
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
        public double getArcWidth() {
            return arcwidth;
        }

        @Override
        public double getArcHeight() {
            return archeight;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0.0f || height <= 0.0f;
        }

        /**
         * Sets the data of the round rectangle.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         * @param arcwidth
         *            the arc width of the rounded corners.
         * @param archeight
         *            the arc height of the rounded corners.
         */
        public void setRoundRect(float x, float y, float width, float height, float arcwidth,
                float archeight) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.arcwidth = arcwidth;
            this.archeight = archeight;
        }

        @Override
        public void setRoundRect(double x, double y, double width, double height, double arcwidth,
                double archeight) {
            this.x = (float)x;
            this.y = (float)y;
            this.width = (float)width;
            this.height = (float)height;
            this.arcwidth = (float)arcwidth;
            this.archeight = (float)archeight;
        }

        @Override
        public void setRoundRect(RoundRectangle2D rr) {
            this.x = (float)rr.getX();
            this.y = (float)rr.getY();
            this.width = (float)rr.getWidth();
            this.height = (float)rr.getHeight();
            this.arcwidth = (float)rr.getArcWidth();
            this.archeight = (float)rr.getArcHeight();
        }

        public Rectangle2D getBounds2D() {
            return new Rectangle2D.Float(x, y, width, height);
        }
    }

    /**
     * The Class Double is the subclass of RoundRectangle2D that has all of its
     * data values stored with double-level precision.
     * 
     * @since Android 1.0
     */
    public static class Double extends RoundRectangle2D {

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
         * The arc width of the rounded corners.
         */
        public double arcwidth;

        /**
         * The arc height of the rounded corners.
         */
        public double archeight;

        /**
         * Instantiates a new double-valued RoundRectangle2D with its
         * data-values set to zero.
         */
        public Double() {
        }

        /**
         * Instantiates a new double-valued RoundRectangle2D with the specified
         * data values.
         * 
         * @param x
         *            the x coordinate of the rectangle's upper left corner.
         * @param y
         *            the y coordinate of the rectangle's upper left corner.
         * @param width
         *            the width of the rectangle.
         * @param height
         *            the height of the rectangle.
         * @param arcwidth
         *            the arc width of the rounded corners.
         * @param archeight
         *            the arc height of the rounded corners.
         */
        public Double(double x, double y, double width, double height, double arcwidth,
                double archeight) {
            setRoundRect(x, y, width, height, arcwidth, archeight);
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
        public double getArcWidth() {
            return arcwidth;
        }

        @Override
        public double getArcHeight() {
            return archeight;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0.0 || height <= 0.0;
        }

        @Override
        public void setRoundRect(double x, double y, double width, double height, double arcwidth,
                double archeight) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.arcwidth = arcwidth;
            this.archeight = archeight;
        }

        @Override
        public void setRoundRect(RoundRectangle2D rr) {
            this.x = rr.getX();
            this.y = rr.getY();
            this.width = rr.getWidth();
            this.height = rr.getHeight();
            this.arcwidth = rr.getArcWidth();
            this.archeight = rr.getArcHeight();
        }

        public Rectangle2D getBounds2D() {
            return new Rectangle2D.Double(x, y, width, height);
        }
    }

    /*
     * RoundRectangle2D path iterator
     */
    /**
     * The subclass of PathIterator to traverse a RoundRectangle2D.
     */
    class Iterator implements PathIterator {

        /*
         * Path for round corners generated the same way as Ellipse2D
         */

        /**
         * The coefficient to calculate control points of Bezier curves.
         */
        double u = 0.5 - 2.0 / 3.0 * (Math.sqrt(2.0) - 1.0);

        /**
         * The points coordinates calculation table.
         */
        double points[][] = {
                {
                        0.0, 0.5, 0.0, 0.0
                }, // MOVETO
                {
                        1.0, -0.5, 0.0, 0.0
                }, // LINETO
                {
                        1.0, -u, 0.0, 0.0, // CUBICTO
                        1.0, 0.0, 0.0, u, 1.0, 0.0, 0.0, 0.5
                }, {
                        1.0, 0.0, 1.0, -0.5
                }, // LINETO
                {
                        1.0, 0.0, 1.0, -u, // CUBICTO
                        1.0, -u, 1.0, 0.0, 1.0, -0.5, 1.0, 0.0
                }, {
                        0.0, 0.5, 1.0, 0.0
                }, // LINETO
                {
                        0.0, u, 1.0, 0.0, // CUBICTO
                        0.0, 0.0, 1.0, -u, 0.0, 0.0, 1.0, -0.5
                }, {
                        0.0, 0.0, 0.0, 0.5
                }, // LINETO
                {
                        0.0, 0.0, 0.0, u, // CUBICTO
                        0.0, u, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0
                }
        };

        /**
         * The segment types correspond to points array.
         */
        int types[] = {
                SEG_MOVETO, SEG_LINETO, SEG_CUBICTO, SEG_LINETO, SEG_CUBICTO, SEG_LINETO,
                SEG_CUBICTO, SEG_LINETO, SEG_CUBICTO
        };

        /**
         * The x coordinate of left-upper corner of the round rectangle bounds.
         */
        double x;

        /**
         * The y coordinate of left-upper corner of the round rectangle bounds.
         */
        double y;

        /**
         * The width of the round rectangle bounds.
         */
        double width;

        /**
         * The height of the round rectangle bounds.
         */
        double height;

        /**
         * The width of arc corners of the round rectangle.
         */
        double aw;

        /**
         * The height of arc corners of the round rectangle.
         */
        double ah;

        /**
         * The path iterator transformation.
         */
        AffineTransform t;

        /**
         * The current segment index.
         */
        int index;

        /**
         * Constructs a new RoundRectangle2D.Iterator for given round rectangle
         * and transformation.
         * 
         * @param rr
         *            - the source RoundRectangle2D object
         * @param at
         *            - the AffineTransform object to apply rectangle path
         */
        Iterator(RoundRectangle2D rr, AffineTransform at) {
            this.x = rr.getX();
            this.y = rr.getY();
            this.width = rr.getWidth();
            this.height = rr.getHeight();
            this.aw = Math.min(width, rr.getArcWidth());
            this.ah = Math.min(height, rr.getArcHeight());
            this.t = at;
            if (width < 0.0 || height < 0.0 || aw < 0.0 || ah < 0.0) {
                index = points.length;
            }
        }

        public int getWindingRule() {
            return WIND_NON_ZERO;
        }

        public boolean isDone() {
            return index > points.length;
        }

        public void next() {
            index++;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            if (index == points.length) {
                return SEG_CLOSE;
            }
            int j = 0;
            double p[] = points[index];
            for (int i = 0; i < p.length; i += 4) {
                coords[j++] = x + p[i + 0] * width + p[i + 1] * aw;
                coords[j++] = y + p[i + 2] * height + p[i + 3] * ah;
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, j / 2);
            }
            return types[index];
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            if (index == points.length) {
                return SEG_CLOSE;
            }
            int j = 0;
            double p[] = points[index];
            for (int i = 0; i < p.length; i += 4) {
                coords[j++] = (float)(x + p[i + 0] * width + p[i + 1] * aw);
                coords[j++] = (float)(y + p[i + 2] * height + p[i + 3] * ah);
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, j / 2);
            }
            return types[index];
        }

    }

    /**
     * Instantiates a new RoundRectangle2D.
     */
    protected RoundRectangle2D() {
    }

    /**
     * Gets the arc width.
     * 
     * @return the arc width.
     */
    public abstract double getArcWidth();

    /**
     * Gets the arc height.
     * 
     * @return the arc height.
     */
    public abstract double getArcHeight();

    /**
     * Sets the data of the RoundRectangle2D.
     * 
     * @param x
     *            the x coordinate of the rectangle's upper left corner.
     * @param y
     *            the y coordinate of the rectangle's upper left corner.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     * @param arcWidth
     *            the arc width of the rounded corners.
     * @param arcHeight
     *            the arc height of the rounded corners.
     */
    public abstract void setRoundRect(double x, double y, double width, double height,
            double arcWidth, double arcHeight);

    /**
     * Sets the data of the RoundRectangle2D by copying the values from an
     * existing RoundRectangle2D.
     * 
     * @param rr
     *            the round rectangle to copy the data from.
     * @throws NullPointerException
     *             if rr is null.
     */
    public void setRoundRect(RoundRectangle2D rr) {
        setRoundRect(rr.getX(), rr.getY(), rr.getWidth(), rr.getHeight(), rr.getArcWidth(), rr
                .getArcHeight());
    }

    @Override
    public void setFrame(double x, double y, double width, double height) {
        setRoundRect(x, y, width, height, getArcWidth(), getArcHeight());
    }

    public boolean contains(double px, double py) {
        if (isEmpty()) {
            return false;
        }

        double rx1 = getX();
        double ry1 = getY();
        double rx2 = rx1 + getWidth();
        double ry2 = ry1 + getHeight();

        if (px < rx1 || px >= rx2 || py < ry1 || py >= ry2) {
            return false;
        }

        double aw = getArcWidth() / 2.0;
        double ah = getArcHeight() / 2.0;

        double cx, cy;

        if (px < rx1 + aw) {
            cx = rx1 + aw;
        } else if (px > rx2 - aw) {
            cx = rx2 - aw;
        } else {
            return true;
        }

        if (py < ry1 + ah) {
            cy = ry1 + ah;
        } else if (py > ry2 - ah) {
            cy = ry2 - ah;
        } else {
            return true;
        }

        px = (px - cx) / aw;
        py = (py - cy) / ah;
        return px * px + py * py <= 1.0;
    }

    public boolean intersects(double rx, double ry, double rw, double rh) {
        if (isEmpty() || rw <= 0.0 || rh <= 0.0) {
            return false;
        }

        double x1 = getX();
        double y1 = getY();
        double x2 = x1 + getWidth();
        double y2 = y1 + getHeight();

        double rx1 = rx;
        double ry1 = ry;
        double rx2 = rx + rw;
        double ry2 = ry + rh;

        if (rx2 < x1 || x2 < rx1 || ry2 < y1 || y2 < ry1) {
            return false;
        }

        double cx = (x1 + x2) / 2.0;
        double cy = (y1 + y2) / 2.0;

        double nx = cx < rx1 ? rx1 : (cx > rx2 ? rx2 : cx);
        double ny = cy < ry1 ? ry1 : (cy > ry2 ? ry2 : cy);

        return contains(nx, ny);
    }

    public boolean contains(double rx, double ry, double rw, double rh) {
        if (isEmpty() || rw <= 0.0 || rh <= 0.0) {
            return false;
        }

        double rx1 = rx;
        double ry1 = ry;
        double rx2 = rx + rw;
        double ry2 = ry + rh;

        return contains(rx1, ry1) && contains(rx2, ry1) && contains(rx2, ry2) && contains(rx1, ry2);
    }

    public PathIterator getPathIterator(AffineTransform at) {
        return new Iterator(this, at);
    }

}
