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
 * The Class Ellipse2D describes an ellipse defined by a rectangular area in
 * which it is inscribed.
 * 
 * @since Android 1.0
 */
public abstract class Ellipse2D extends RectangularShape {

    /**
     * The Class Float is the subclass of Ellipse2D that has all of its data
     * values stored with float-level precision.
     * 
     * @since Android 1.0
     */
    public static class Float extends Ellipse2D {

        /**
         * The x coordinate of the upper left corner of the ellipse's bounding
         * rectangle.
         */
        public float x;

        /**
         * The y coordinate of the upper left corner of the ellipse's bounding
         * rectangle.
         */
        public float y;

        /**
         * The width of the ellipse's bounding rectangle.
         */
        public float width;

        /**
         * The height of the ellipse's bounding rectangle.
         */
        public float height;

        /**
         * Instantiates a new float-valued Ellipse2D.
         */
        public Float() {
        }

        /**
         * Instantiates a new float-valued Ellipse2D with the specified data.
         * 
         * @param x
         *            the x coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param y
         *            the y coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param width
         *            the width of the ellipse's bounding rectangle.
         * @param height
         *            the height of the ellipse's bounding rectangle.
         */
        public Float(float x, float y, float width, float height) {
            setFrame(x, y, width, height);
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

        /**
         * Sets the data of the ellipse's bounding rectangle.
         * 
         * @param x
         *            the x coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param y
         *            the y coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param width
         *            the width of the ellipse's bounding rectangle.
         * @param height
         *            the height of the ellipse's bounding rectangle.
         */
        public void setFrame(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public void setFrame(double x, double y, double width, double height) {
            this.x = (float)x;
            this.y = (float)y;
            this.width = (float)width;
            this.height = (float)height;
        }

        public Rectangle2D getBounds2D() {
            return new Rectangle2D.Float(x, y, width, height);
        }
    }

    /**
     * The Class Double is the subclass of Ellipse2D that has all of its data
     * values stored with double-level precision.
     * 
     * @since Android 1.0
     */
    public static class Double extends Ellipse2D {

        /**
         * The x coordinate of the upper left corner of the ellipse's bounding
         * rectangle.
         */
        public double x;

        /**
         * The y coordinate of the upper left corner of the ellipse's bounding
         * rectangle.
         */
        public double y;

        /**
         * The width of the ellipse's bounding rectangle.
         */
        public double width;

        /**
         * The height of the ellipse's bounding rectangle.
         */
        public double height;

        /**
         * Instantiates a new double-valued Ellipse2D.
         */
        public Double() {
        }

        /**
         * Instantiates a new double-valued Ellipse2D with the specified data.
         * 
         * @param x
         *            the x coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param y
         *            the y coordinate of the upper left corner of the ellipse's
         *            bounding rectangle.
         * @param width
         *            the width of the ellipse's bounding rectangle.
         * @param height
         *            the height of the ellipse's bounding rectangle.
         */
        public Double(double x, double y, double width, double height) {
            setFrame(x, y, width, height);
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
        public void setFrame(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public Rectangle2D getBounds2D() {
            return new Rectangle2D.Double(x, y, width, height);
        }
    }

    /*
     * Ellipse2D path iterator
     */
    /**
     * The subclass of PathIterator to traverse an Ellipse2D.
     */
    class Iterator implements PathIterator {

        /*
         * Ellipse is subdivided into four quarters by x and y axis. Each part
         * approximated by cubic Bezier curve. Arc in first quarter is started
         * in (a, 0) and finished in (0, b) points. Control points for cubic
         * curve wiil be (a, 0), (a, m), (n, b) and (0, b) where n and m are
         * calculated based on requirement Bezier curve in point 0.5 should lay
         * on the arc.
         */

        /**
         * The coefficient to calculate control points of Bezier curves.
         */
        final double u = 2.0 / 3.0 * (Math.sqrt(2.0) - 1.0);

        /**
         * The points coordinates calculation table.
         */
        final double points[][] = {
                {
                        1.0, 0.5 + u, 0.5 + u, 1.0, 0.5, 1.0
                }, {
                        0.5 - u, 1.0, 0.0, 0.5 + u, 0.0, 0.5
                }, {
                        0.0, 0.5 - u, 0.5 - u, 0.0, 0.5, 0.0
                }, {
                        0.5 + u, 0.0, 1.0, 0.5 - u, 1.0, 0.5
                }
        };

        /**
         * The x coordinate of left-upper corner of the ellipse bounds.
         */
        double x;

        /**
         * The y coordinate of left-upper corner of the ellipse bounds.
         */
        double y;

        /**
         * The width of the ellipse bounds.
         */
        double width;

        /**
         * The height of the ellipse bounds.
         */
        double height;

        /**
         * The path iterator transformation.
         */
        AffineTransform t;

        /**
         * The current segment index.
         */
        int index;

        /**
         * Constructs a new Ellipse2D.Iterator for given ellipse and
         * transformation
         * 
         * @param e
         *            the source Ellipse2D object.
         * @param t
         *            the affine transformation object.
         */
        Iterator(Ellipse2D e, AffineTransform t) {
            this.x = e.getX();
            this.y = e.getY();
            this.width = e.getWidth();
            this.height = e.getHeight();
            this.t = t;
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
                // awt.4B=Iterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            if (index == 5) {
                return SEG_CLOSE;
            }
            int type;
            int count;
            if (index == 0) {
                type = SEG_MOVETO;
                count = 1;
                double p[] = points[3];
                coords[0] = x + p[4] * width;
                coords[1] = y + p[5] * height;
            } else {
                type = SEG_CUBICTO;
                count = 3;
                double p[] = points[index - 1];
                int j = 0;
                for (int i = 0; i < 3; i++) {
                    coords[j] = x + p[j++] * width;
                    coords[j] = y + p[j++] * height;
                }
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
            if (index == 5) {
                return SEG_CLOSE;
            }
            int type;
            int count;
            if (index == 0) {
                type = SEG_MOVETO;
                count = 1;
                double p[] = points[3];
                coords[0] = (float)(x + p[4] * width);
                coords[1] = (float)(y + p[5] * height);
            } else {
                type = SEG_CUBICTO;
                count = 3;
                int j = 0;
                double p[] = points[index - 1];
                for (int i = 0; i < 3; i++) {
                    coords[j] = (float)(x + p[j++] * width);
                    coords[j] = (float)(y + p[j++] * height);
                }
            }
            if (t != null) {
                t.transform(coords, 0, coords, 0, count);
            }
            return type;
        }

    }

    /**
     * Instantiates a new Ellipse2D.
     */
    protected Ellipse2D() {
    }

    public boolean contains(double px, double py) {
        if (isEmpty()) {
            return false;
        }

        double a = (px - getX()) / getWidth() - 0.5;
        double b = (py - getY()) / getHeight() - 0.5;

        return a * a + b * b < 0.25;
    }

    public boolean intersects(double rx, double ry, double rw, double rh) {
        if (isEmpty() || rw <= 0.0 || rh <= 0.0) {
            return false;
        }

        double cx = getX() + getWidth() / 2.0;
        double cy = getY() + getHeight() / 2.0;

        double rx1 = rx;
        double ry1 = ry;
        double rx2 = rx + rw;
        double ry2 = ry + rh;

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
