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

import org.apache.harmony.misc.HashCode;

/**
 * The Class Point2D represents a point whose data is given in high-precision
 * values appropriate for graphical operations.
 * 
 * @since Android 1.0
 */
public abstract class Point2D implements Cloneable {

    /**
     * The Class Float is the subclass of Point2D that has all of its data
     * values stored with float-level precision.
     * 
     * @since Android 1.0
     */
    public static class Float extends Point2D {

        /**
         * The x coordinate.
         */
        public float x;

        /**
         * The y coordinate.
         */
        public float y;

        /**
         * Instantiates a new float-valued Point2D with its data set to zero.
         */
        public Float() {
        }

        /**
         * Instantiates a new float-valued Point2D with the specified
         * coordinates.
         * 
         * @param x
         *            the x coordinate.
         * @param y
         *            the y coordinate.
         */
        public Float(float x, float y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        /**
         * Sets the point's coordinates.
         * 
         * @param x
         *            the x coordinate.
         * @param y
         *            the y coordinate.
         */
        public void setLocation(float x, float y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public void setLocation(double x, double y) {
            this.x = (float)x;
            this.y = (float)y;
        }

        @Override
        public String toString() {
            return getClass().getName() + "[x=" + x + ",y=" + y + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * The Class Double is the subclass of Point2D that has all of its data
     * values stored with double-level precision.
     * 
     * @since Android 1.0
     */
    public static class Double extends Point2D {

        /**
         * The x coordinate.
         */
        public double x;

        /**
         * The y coordinate.
         */
        public double y;

        /**
         * Instantiates a new double-valued Point2D with its data set to zero.
         */
        public Double() {
        }

        /**
         * Instantiates a new double-valued Point2D with the specified
         * coordinates.
         * 
         * @param x
         *            the x coordinate.
         * @param y
         *            the y coordinate.
         */
        public Double(double x, double y) {
            this.x = x;
            this.y = y;
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
        public void setLocation(double x, double y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return getClass().getName() + "[x=" + x + ",y=" + y + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * Instantiates a new Point2D.
     */
    protected Point2D() {
    }

    /**
     * Gets the x coordinate.
     * 
     * @return the x coordinate.
     */
    public abstract double getX();

    /**
     * Gets the y coordinate.
     * 
     * @return the y coordinate.
     */
    public abstract double getY();

    /**
     * Sets the point's coordinates.
     * 
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     */
    public abstract void setLocation(double x, double y);

    /**
     * Sets the point's coordinates by copying them from another point.
     * 
     * @param p
     *            the point to copy the data from.
     */
    public void setLocation(Point2D p) {
        setLocation(p.getX(), p.getY());
    }

    /**
     * Finds the square of the distance between the two specified points.
     * 
     * @param x1
     *            the x coordinate of the first point.
     * @param y1
     *            the y coordinate of the first point.
     * @param x2
     *            the x coordinate of the second point.
     * @param y2
     *            the y coordinate of the second point.
     * @return the square of the distance between the two specified points.
     */
    public static double distanceSq(double x1, double y1, double x2, double y2) {
        x2 -= x1;
        y2 -= y1;
        return x2 * x2 + y2 * y2;
    }

    /**
     * Finds the square of the distance between this point and the specified
     * point.
     * 
     * @param px
     *            the x coordinate of the point.
     * @param py
     *            the y coordinate of the point.
     * @return the square of the distance between this point and the specified
     *         point.
     */
    public double distanceSq(double px, double py) {
        return Point2D.distanceSq(getX(), getY(), px, py);
    }

    /**
     * Finds the square of the distance between this point and the specified
     * point.
     * 
     * @param p
     *            the other point.
     * @return the square of the distance between this point and the specified
     *         point.
     */
    public double distanceSq(Point2D p) {
        return Point2D.distanceSq(getX(), getY(), p.getX(), p.getY());
    }

    /**
     * Finds the distance between the two specified points.
     * 
     * @param x1
     *            the x coordinate of the first point.
     * @param y1
     *            the y coordinate of the first point.
     * @param x2
     *            the x coordinate of the second point.
     * @param y2
     *            the y coordinate of the second point.
     * @return the distance between the two specified points.
     */
    public static double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(distanceSq(x1, y1, x2, y2));
    }

    /**
     * Finds the distance between this point and the specified point.
     * 
     * @param px
     *            the x coordinate of the point.
     * @param py
     *            the y coordinate of the point.
     * @return the distance between this point and the specified point.
     */
    public double distance(double px, double py) {
        return Math.sqrt(distanceSq(px, py));
    }

    /**
     * Finds the distance between this point and the specified point.
     * 
     * @param p
     *            the other point.
     * @return the distance between this point and the specified point.
     */
    public double distance(Point2D p) {
        return Math.sqrt(distanceSq(p));
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

    @Override
    public int hashCode() {
        HashCode hash = new HashCode();
        hash.append(getX());
        hash.append(getY());
        return hash.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Point2D) {
            Point2D p = (Point2D)obj;
            return getX() == p.getX() && getY() == p.getY();
        }
        return false;
    }
}
