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

/**
 * The Class RectangularShape represents a Shape whose data is (at least
 * partially) described by a rectangular frame. This includes shapes which are
 * obviously rectangular (such as Rectangle2D) as well as shapes like Arc2D
 * which are largely determined by the rectangle they fit inside.
 * 
 * @since Android 1.0
 */
public abstract class RectangularShape implements Shape, Cloneable {

    /**
     * Instantiates a new rectangular shape.
     */
    protected RectangularShape() {
    }

    /**
     * Gets the x coordinate of the upper left corner of the rectangle.
     * 
     * @return the x coordinate of the upper left corner of the rectangle.
     */
    public abstract double getX();

    /**
     * Gets the y coordinate of the upper left corner of the rectangle.
     * 
     * @return the y coordinate of the upper left corner of the rectangle.
     */
    public abstract double getY();

    /**
     * Gets the width of the rectangle.
     * 
     * @return the width of the rectangle.
     */
    public abstract double getWidth();

    /**
     * Gets the height of the rectangle.
     * 
     * @return the height of the rectangle.
     */
    public abstract double getHeight();

    /**
     * Checks if this is an empty rectangle: one with zero as its width or
     * height.
     * 
     * @return true, if the width or height is empty.
     */
    public abstract boolean isEmpty();

    /**
     * Sets the data for the bounding rectangle in terms of double values.
     * 
     * @param x
     *            the x coordinate of the upper left corner of the rectangle.
     * @param y
     *            the y coordinate of the upper left corner of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     */
    public abstract void setFrame(double x, double y, double w, double h);

    /**
     * Gets the minimum x value of the bounding rectangle (the x coordinate of
     * the upper left corner of the rectangle).
     * 
     * @return the minimum x value of the bounding rectangle.
     */
    public double getMinX() {
        return getX();
    }

    /**
     * Gets the minimum y value of the bounding rectangle (the y coordinate of
     * the upper left corner of the rectangle).
     * 
     * @return the minimum y value of the bounding rectangle.
     */
    public double getMinY() {
        return getY();
    }

    /**
     * Gets the maximum x value of the bounding rectangle (the x coordinate of
     * the upper left corner of the rectangle plus the rectangle's width).
     * 
     * @return the maximum x value of the bounding rectangle.
     */
    public double getMaxX() {
        return getX() + getWidth();
    }

    /**
     * Gets the maximum y value of the bounding rectangle (the y coordinate of
     * the upper left corner of the rectangle plus the rectangle's height).
     * 
     * @return the maximum y value of the bounding rectangle.
     */
    public double getMaxY() {
        return getY() + getHeight();
    }

    /**
     * Gets the x coordinate of the center of the rectangle.
     * 
     * @return the x coordinate of the center of the rectangle.
     */
    public double getCenterX() {
        return getX() + getWidth() / 2.0;
    }

    /**
     * Gets the y coordinate of the center of the rectangle.
     * 
     * @return the y coordinate of the center of the rectangle.
     */
    public double getCenterY() {
        return getY() + getHeight() / 2.0;
    }

    /**
     * Places the rectangle's size and location data in a new Rectangle2D object
     * and returns it.
     * 
     * @return the bounding rectangle as a new Rectangle2D object.
     */
    public Rectangle2D getFrame() {
        return new Rectangle2D.Double(getX(), getY(), getWidth(), getHeight());
    }

    /**
     * Sets the bounding rectangle in terms of a Point2D which gives its upper
     * left corner and a Dimension2D object giving its width and height.
     * 
     * @param loc
     *            the new upper left corner coordinate.
     * @param size
     *            the new size dimensions.
     */
    public void setFrame(Point2D loc, Dimension2D size) {
        setFrame(loc.getX(), loc.getY(), size.getWidth(), size.getHeight());
    }

    /**
     * Sets the bounding rectangle to match the data contained in the specified
     * Rectangle2D.
     * 
     * @param r
     *            the rectangle that gives the new frame data.
     */
    public void setFrame(Rectangle2D r) {
        setFrame(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    /**
     * Sets the framing rectangle given two opposite corners. Any two corners
     * may be used in any order as long as they are diagonally opposite one
     * another.
     * 
     * @param x1
     *            the x coordinate of one of the corner points.
     * @param y1
     *            the y coordinate of one of the corner points.
     * @param x2
     *            the x coordinate of the other corner point.
     * @param y2
     *            the y coordinate of the other corner point.
     */
    public void setFrameFromDiagonal(double x1, double y1, double x2, double y2) {
        double rx, ry, rw, rh;
        if (x1 < x2) {
            rx = x1;
            rw = x2 - x1;
        } else {
            rx = x2;
            rw = x1 - x2;
        }
        if (y1 < y2) {
            ry = y1;
            rh = y2 - y1;
        } else {
            ry = y2;
            rh = y1 - y2;
        }
        setFrame(rx, ry, rw, rh);
    }

    /**
     * Sets the framing rectangle given two opposite corners. Any two corners
     * may be used in any order as long as they are diagonally opposite one
     * another.
     * 
     * @param p1
     *            one of the corner points.
     * @param p2
     *            the other corner point.
     */
    public void setFrameFromDiagonal(Point2D p1, Point2D p2) {
        setFrameFromDiagonal(p1.getX(), p1.getY(), p2.getX(), p2.getY());
    }

    /**
     * Sets the framing rectangle given the center point and one corner. Any
     * corner may be used.
     * 
     * @param centerX
     *            the x coordinate of the center point.
     * @param centerY
     *            the y coordinate of the center point.
     * @param cornerX
     *            the x coordinate of one of the corner points.
     * @param cornerY
     *            the y coordinate of one of the corner points.
     */
    public void setFrameFromCenter(double centerX, double centerY, double cornerX, double cornerY) {
        double width = Math.abs(cornerX - centerX);
        double height = Math.abs(cornerY - centerY);
        setFrame(centerX - width, centerY - height, width * 2.0, height * 2.0);
    }

    /**
     * Sets the framing rectangle given the center point and one corner. Any
     * corner may be used.
     * 
     * @param center
     *            the center point.
     * @param corner
     *            a corner point.
     */
    public void setFrameFromCenter(Point2D center, Point2D corner) {
        setFrameFromCenter(center.getX(), center.getY(), corner.getX(), corner.getY());
    }

    public boolean contains(Point2D point) {
        return contains(point.getX(), point.getY());
    }

    public boolean intersects(Rectangle2D rect) {
        return intersects(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    public boolean contains(Rectangle2D rect) {
        return contains(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    public Rectangle getBounds() {
        int x1 = (int)Math.floor(getMinX());
        int y1 = (int)Math.floor(getMinY());
        int x2 = (int)Math.ceil(getMaxX());
        int y2 = (int)Math.ceil(getMaxY());
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
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
