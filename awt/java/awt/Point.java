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

import java.awt.geom.Point2D;
import java.io.Serializable;

/**
 * The Point class represents a point location with coordinates X, Y in current
 * coordinate system.
 * 
 * @since Android 1.0
 */
public class Point extends Point2D implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -5276940640259749850L;

    /**
     * The X coordinate of Point.
     */
    public int x;

    /**
     * The Y coordinate of Point.
     */
    public int y;

    /**
     * Instantiates a new point with (0, O) coordinates, the origin of
     * coordinate system.
     */
    public Point() {
        setLocation(0, 0);
    }

    /**
     * Instantiates a new point with (x, y) coordinates.
     * 
     * @param x
     *            the X coordinate of Point.
     * @param y
     *            the Y coordinate of Point.
     */
    public Point(int x, int y) {
        setLocation(x, y);
    }

    /**
     * Instantiates a new point, giving it the same location as the parameter p.
     * 
     * @param p
     *            the Point object giving the coordinates of the new point.
     */
    public Point(Point p) {
        setLocation(p.x, p.y);
    }

    /**
     * Compares current Point with the specified object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the Object being compared is a Point whose coordinates
     *         are equal to the coordinates of this Point, false otherwise.
     * @see java.awt.geom.Point2D#equals(Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Point) {
            Point p = (Point)obj;
            return x == p.x && y == p.y;
        }
        return false;
    }

    /**
     * Returns string representation of the current Point object.
     * 
     * @return a string representation of the current Point object.
     */
    @Override
    public String toString() {
        return getClass().getName() + "[x=" + x + ",y=" + y + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Gets X coordinate of Point as a double.
     * 
     * @return X coordinate of the point as a double.
     * @see java.awt.geom.Point2D#getX()
     */
    @Override
    public double getX() {
        return x;
    }

    /**
     * Gets Y coordinate of Point as a double.
     * 
     * @return Y coordinate of the point as a double.
     * @see java.awt.geom.Point2D#getY()
     */
    @Override
    public double getY() {
        return y;
    }

    /**
     * Gets the location of the Point as a new Point object.
     * 
     * @return a copy of the Point.
     */
    public Point getLocation() {
        return new Point(x, y);
    }

    /**
     * Sets the location of the Point to the same coordinates as p.
     * 
     * @param p
     *            the Point that gives the new location.
     */
    public void setLocation(Point p) {
        setLocation(p.x, p.y);
    }

    /**
     * Sets the location of the Point to the coordinates X, Y.
     * 
     * @param x
     *            the X coordinate of the Point's new location.
     * @param y
     *            the Y coordinate of the Point's new location.
     */
    public void setLocation(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Sets the location of Point to the specified double coordinates.
     * 
     * @param x
     *            the X the Point's new location.
     * @param y
     *            the Y the Point's new location.
     * @see java.awt.geom.Point2D#setLocation(double, double)
     */
    @Override
    public void setLocation(double x, double y) {
        x = x < Integer.MIN_VALUE ? Integer.MIN_VALUE : x > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : x;
        y = y < Integer.MIN_VALUE ? Integer.MIN_VALUE : y > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : y;
        setLocation((int)Math.round(x), (int)Math.round(y));
    }

    /**
     * Moves the Point to the specified (x, y) location.
     * 
     * @param x
     *            the X coordinate of the new location.
     * @param y
     *            the Y coordinate of the new location.
     */
    public void move(int x, int y) {
        setLocation(x, y);
    }

    /**
     * Translates current Point moving it from the position (x, y) to the new
     * position given by (x+dx, x+dy) coordinates.
     * 
     * @param dx
     *            the horizontal delta - the Point is moved to this distance
     *            along X axis.
     * @param dy
     *            the vertical delta - the Point is moved to this distance along
     *            Y axis.
     */
    public void translate(int dx, int dy) {
        x += dx;
        y += dy;
    }

}
