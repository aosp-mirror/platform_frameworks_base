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

import java.awt.geom.Rectangle2D;
import java.io.Serializable;

/**
 * The Rectangle class defines the rectangular area in terms of its upper left
 * corner coordinates [x,y], its width, and its height. A Rectangle specified by
 * [x, y, width, height] parameters has an outline path with corners at [x, y],
 * [x + width,y], [x + width,y + height], and [x, y + height]. <br>
 * <br>
 * The rectangle is empty if the width or height is negative or zero. In this
 * case the isEmpty method returns true.
 * 
 * @since Android 1.0
 */
public class Rectangle extends Rectangle2D implements Shape, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -4345857070255674764L;

    /**
     * The X coordinate of the rectangle's left upper corner.
     */
    public int x;

    /**
     * The Y coordinate of the rectangle's left upper corner.
     */
    public int y;

    /**
     * The width of rectangle.
     */
    public int width;

    /**
     * The height of rectangle.
     */
    public int height;

    /**
     * Instantiates a new rectangle with [0, 0] upper left corner coordinates,
     * the width and the height are zero.
     */
    public Rectangle() {
        setBounds(0, 0, 0, 0);
    }

    /**
     * Instantiates a new rectangle whose upper left corner coordinates are
     * given by the Point object (p.X and p.Y), and the width and the height are
     * zero.
     * 
     * @param p
     *            the Point specifies the upper left corner coordinates of the
     *            rectangle.
     */
    public Rectangle(Point p) {
        setBounds(p.x, p.y, 0, 0);
    }

    /**
     * Instantiates a new rectangle whose upper left corner coordinates are
     * given by the Point object (p.X and p.Y), and the width and the height are
     * given by Dimension object (d.width and d.height).
     * 
     * @param p
     *            the point specifies the upper left corner coordinates of the
     *            rectangle.
     * @param d
     *            the dimension specifies the width and the height of the
     *            rectangle.
     */
    public Rectangle(Point p, Dimension d) {
        setBounds(p.x, p.y, d.width, d.height);
    }

    /**
     * Instantiates a new rectangle determined by the upper left corner
     * coordinates (x, y), width and height.
     * 
     * @param x
     *            the X upper left corner coordinate of the rectangle.
     * @param y
     *            the Y upper left corner coordinate of the rectangle.
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     */
    public Rectangle(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    /**
     * Instantiates a new rectangle with [0, 0] as its upper left corner
     * coordinates and the specified width and height.
     * 
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     */
    public Rectangle(int width, int height) {
        setBounds(0, 0, width, height);
    }

    /**
     * Instantiates a new rectangle with the same coordinates as the given
     * source rectangle.
     * 
     * @param r
     *            the Rectangle object which parameters will be used for
     *            instantiating a new Rectangle.
     */
    public Rectangle(Rectangle r) {
        setBounds(r.x, r.y, r.width, r.height);
    }

    /*
     * public Rectangle(Dimension d) { setBounds(0, 0, d.width, d.height); }
     */
    /**
     * Gets the X coordinate of bound as a double.
     * 
     * @return the X coordinate of bound as a double.
     * @see java.awt.geom.RectangularShape#getX()
     */
    @Override
    public double getX() {
        return x;
    }

    /**
     * Gets the Y coordinate of bound as a double.
     * 
     * @return the Y coordinate of bound as a double.
     * @see java.awt.geom.RectangularShape#getY()
     */
    @Override
    public double getY() {
        return y;
    }

    /**
     * Gets the height of the rectangle as a double.
     * 
     * @return the height of the rectangle as a double.
     * @see java.awt.geom.RectangularShape#getHeight()
     */
    @Override
    public double getHeight() {
        return height;
    }

    /**
     * Gets the width of the rectangle as a double.
     * 
     * @return the width of the rectangle as a double.
     * @see java.awt.geom.RectangularShape#getWidth()
     */
    @Override
    public double getWidth() {
        return width;
    }

    /**
     * Determines whether or not the rectangle is empty. The rectangle is empty
     * if its width or height is negative or zero.
     * 
     * @return true, if the rectangle is empty, otherwise false.
     * @see java.awt.geom.RectangularShape#isEmpty()
     */
    @Override
    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    /**
     * Gets the size of a Rectangle as Dimension object.
     * 
     * @return a Dimension object which represents size of the rectangle.
     */
    public Dimension getSize() {
        return new Dimension(width, height);
    }

    /**
     * Sets the size of the Rectangle.
     * 
     * @param width
     *            the new width of the rectangle.
     * @param height
     *            the new height of the rectangle.
     */
    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Sets the size of a Rectangle specified as Dimension object.
     * 
     * @param d
     *            a Dimension object which represents new size of a rectangle.
     */
    public void setSize(Dimension d) {
        setSize(d.width, d.height);
    }

    /**
     * Gets the location of a rectangle's upper left corner as a Point object.
     * 
     * @return the Point object with coordinates equal to the upper left corner
     *         of the rectangle.
     */
    public Point getLocation() {
        return new Point(x, y);
    }

    /**
     * Sets the location of the rectangle in terms of its upper left corner
     * coordinates X and Y.
     * 
     * @param x
     *            the X coordinate of the rectangle's upper left corner.
     * @param y
     *            the Y coordinate of the rectangle's upper left corner.
     */
    public void setLocation(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Sets the location of a rectangle using a Point object to give the
     * coordinates of the upper left corner.
     * 
     * @param p
     *            the Point object which represents the new upper left corner
     *            coordinates of rectangle.
     */
    public void setLocation(Point p) {
        setLocation(p.x, p.y);
    }

    /**
     * Moves a rectangle to the new location by moving its upper left corner to
     * the point with coordinates X and Y.
     * 
     * @param x
     *            the new X coordinate of the rectangle's upper left corner.
     * @param y
     *            the new Y coordinate of the rectangle's upper left corner.
     * @deprecated Use setLocation(int, int) method.
     */
    @Deprecated
    public void move(int x, int y) {
        setLocation(x, y);
    }

    /**
     * Sets the rectangle to be the nearest rectangle with integer coordinates
     * bounding the rectangle defined by the double-valued parameters.
     * 
     * @param x
     *            the X coordinate of the upper left corner of the double-valued
     *            rectangle to be bounded.
     * @param y
     *            the Y coordinate of the upper left corner of the double-valued
     *            rectangle to be bounded.
     * @param width
     *            the width of the rectangle to be bounded.
     * @param height
     *            the height of the rectangle to be bounded.
     * @see java.awt.geom.Rectangle2D#setRect(double, double, double, double)
     */
    @Override
    public void setRect(double x, double y, double width, double height) {
        int x1 = (int)Math.floor(x);
        int y1 = (int)Math.floor(y);
        int x2 = (int)Math.ceil(x + width);
        int y2 = (int)Math.ceil(y + height);
        setBounds(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Sets a new size for the rectangle.
     * 
     * @param width
     *            the rectangle's new width.
     * @param height
     *            the rectangle's new height.
     * @deprecated use the setSize(int, int) method.
     */
    @Deprecated
    public void resize(int width, int height) {
        setBounds(x, y, width, height);
    }

    /**
     * Resets the bounds of a rectangle to the specified x, y, width and height
     * parameters.
     * 
     * @param x
     *            the new X coordinate of the upper left corner.
     * @param y
     *            the new Y coordinate of the upper left corner.
     * @param width
     *            the new width of rectangle.
     * @param height
     *            the new height of rectangle.
     * @deprecated use setBounds(int, int, int, int) method
     */
    @Deprecated
    public void reshape(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    /**
     * Gets bounds of the rectangle as a new Rectangle object.
     * 
     * @return the Rectangle object with the same bounds as the original
     *         rectangle.
     * @see java.awt.geom.RectangularShape#getBounds()
     */
    @Override
    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Gets the bounds of the original rectangle as a Rectangle2D object.
     * 
     * @return the Rectangle2D object which represents the bounds of the
     *         original rectangle.
     * @see java.awt.geom.Rectangle2D#getBounds2D()
     */
    @Override
    public Rectangle2D getBounds2D() {
        return getBounds();
    }

    /**
     * Sets the bounds of a rectangle to the specified x, y, width, and height
     * parameters.
     * 
     * @param x
     *            the X coordinate of the upper left corner.
     * @param y
     *            the Y coordinate of the upper left corner.
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     */
    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.height = height;
        this.width = width;
    }

    /**
     * Sets the bounds of the rectangle to match the bounds of the Rectangle
     * object sent as a parameter.
     * 
     * @param r
     *            the Rectangle object which specifies the new bounds.
     */
    public void setBounds(Rectangle r) {
        setBounds(r.x, r.y, r.width, r.height);
    }

    /**
     * Enlarges the rectangle by moving each corner outward from the center by a
     * distance of dx horizonally and a distance of dy vertically. Specifically,
     * changes a rectangle with [x, y, width, height] parameters to a rectangle
     * with [x-dx, y-dy, width+2*dx, height+2*dy] parameters.
     * 
     * @param dx
     *            the horizontal distance to move each corner coordinate.
     * @param dy
     *            the vertical distance to move each corner coordinate.
     */
    public void grow(int dx, int dy) {
        x -= dx;
        y -= dy;
        width += dx + dx;
        height += dy + dy;
    }

    /**
     * Moves a rectangle a distance of mx along the x coordinate axis and a
     * distance of my along y coordinate axis.
     * 
     * @param mx
     *            the horizontal translation increment.
     * @param my
     *            the vertical translation increment.
     */
    public void translate(int mx, int my) {
        x += mx;
        y += my;
    }

    /**
     * Enlarges the rectangle to cover the specified point.
     * 
     * @param px
     *            the X coordinate of the new point to be covered by the
     *            rectangle.
     * @param py
     *            the Y coordinate of the new point to be covered by the
     *            rectangle.
     */
    public void add(int px, int py) {
        int x1 = Math.min(x, px);
        int x2 = Math.max(x + width, px);
        int y1 = Math.min(y, py);
        int y2 = Math.max(y + height, py);
        setBounds(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Enlarges the rectangle to cover the specified point with the new point
     * given as a Point object.
     * 
     * @param p
     *            the Point object that specifies the new point to be covered by
     *            the rectangle.
     */
    public void add(Point p) {
        add(p.x, p.y);
    }

    /**
     * Adds a new rectangle to the original rectangle, the result is an union of
     * the specified specified rectangle and original rectangle.
     * 
     * @param r
     *            the Rectangle which is added to the original rectangle.
     */
    public void add(Rectangle r) {
        int x1 = Math.min(x, r.x);
        int x2 = Math.max(x + width, r.x + r.width);
        int y1 = Math.min(y, r.y);
        int y2 = Math.max(y + height, r.y + r.height);
        setBounds(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Determines whether or not the point with specified coordinates [px, py]
     * is within the bounds of the rectangle.
     * 
     * @param px
     *            the X coordinate of point.
     * @param py
     *            the Y coordinate of point.
     * @return true, if the point with specified coordinates [px, py] is within
     *         the bounds of the rectangle, false otherwise.
     */
    public boolean contains(int px, int py) {
        if (isEmpty()) {
            return false;
        }
        if (px < x || py < y) {
            return false;
        }
        px -= x;
        py -= y;
        return px < width && py < height;
    }

    /**
     * Determines whether or not the point given as a Point object is within the
     * bounds of the rectangle.
     * 
     * @param p
     *            the Point object
     * @return true, if the point p is within the bounds of the rectangle,
     *         otherwise false.
     */
    public boolean contains(Point p) {
        return contains(p.x, p.y);
    }

    /**
     * Determines whether or not the rectangle specified by [rx, ry, rw, rh]
     * parameters is located inside the original rectangle.
     * 
     * @param rx
     *            the X coordinate of the rectangle to compare.
     * @param ry
     *            the Y coordinate of the rectangle to compare.
     * @param rw
     *            the width of the rectangle to compare.
     * @param rh
     *            the height of the rectangle to compare.
     * @return true, if a rectangle with [rx, ry, rw, rh] parameters is entirely
     *         contained in the original rectangle, false otherwise.
     */
    public boolean contains(int rx, int ry, int rw, int rh) {
        return contains(rx, ry) && contains(rx + rw - 1, ry + rh - 1);
    }

    /**
     * Compares whether or not the rectangle specified by the Rectangle object
     * is located inside the original rectangle.
     * 
     * @param r
     *            the Rectangle object.
     * @return true, if the rectangle specified by Rectangle object is entirely
     *         contained in the original rectangle, false otherwise.
     */
    public boolean contains(Rectangle r) {
        return contains(r.x, r.y, r.width, r.height);
    }

    /**
     * Compares whether or not a point with specified coordinates [px, py]
     * belongs to a rectangle.
     * 
     * @param px
     *            the X coordinate of a point.
     * @param py
     *            the Y coordinate of a point.
     * @return true, if a point with specified coordinates [px, py] belongs to a
     *         rectangle, otherwise false.
     * @deprecated use contains(int, int) method.
     */
    @Deprecated
    public boolean inside(int px, int py) {
        return contains(px, py);
    }

    /**
     * Returns the intersection of the original rectangle with the specified
     * Rectangle2D.
     * 
     * @param r
     *            the Rectangle2D object.
     * @return the Rectangle2D object that is the result of intersecting the
     *         original rectangle with the specified Rectangle2D.
     * @see java.awt.geom.Rectangle2D#createIntersection(java.awt.geom.Rectangle2D)
     */
    @Override
    public Rectangle2D createIntersection(Rectangle2D r) {
        if (r instanceof Rectangle) {
            return intersection((Rectangle)r);
        }
        Rectangle2D dst = new Rectangle2D.Double();
        Rectangle2D.intersect(this, r, dst);
        return dst;
    }

    /**
     * Returns the intersection of the original rectangle with the specified
     * rectangle. An empty rectangle is returned if there is no intersection.
     * 
     * @param r
     *            the Rectangle object.
     * @return the Rectangle object is result of the original rectangle with the
     *         specified rectangle.
     */
    public Rectangle intersection(Rectangle r) {
        int x1 = Math.max(x, r.x);
        int y1 = Math.max(y, r.y);
        int x2 = Math.min(x + width, r.x + r.width);
        int y2 = Math.min(y + height, r.y + r.height);
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Determines whether or not the original rectangle intersects the specified
     * rectangle.
     * 
     * @param r
     *            the Rectangle object.
     * @return true, if the two rectangles overlap, false otherwise.
     */
    public boolean intersects(Rectangle r) {
        return !intersection(r).isEmpty();
    }

    /**
     * Determines where the specified Point is located with respect to the
     * rectangle. This method computes whether the point is to the right or to
     * the left of the rectangle and whether it is above or below the rectangle,
     * and packs the result into an integer by using a binary OR operation with
     * the following masks:
     * <ul>
     *<li>Rectangle2D.OUT_LEFT</li>
     *<li>Rectangle2D.OUT_TOP</li>
     *<li>Rectangle2D.OUT_RIGHT</li>
     *<li>Rectangle2D.OUT_BOTTOM</li>
     *</ul>
     * If the rectangle is empty, all masks are set, and if the point is inside
     * the rectangle, none are set.
     * 
     * @param px
     *            the X coordinate of the specified point.
     * @param py
     *            the Y coordinate of the specified point.
     * @return the location of the Point relative to the rectangle as the result
     *         of logical OR operation with all out masks.
     * @see java.awt.geom.Rectangle2D#outcode(double, double)
     */
    @Override
    public int outcode(double px, double py) {
        int code = 0;

        if (width <= 0) {
            code |= OUT_LEFT | OUT_RIGHT;
        } else if (px < x) {
            code |= OUT_LEFT;
        } else if (px > x + width) {
            code |= OUT_RIGHT;
        }

        if (height <= 0) {
            code |= OUT_TOP | OUT_BOTTOM;
        } else if (py < y) {
            code |= OUT_TOP;
        } else if (py > y + height) {
            code |= OUT_BOTTOM;
        }

        return code;
    }

    /**
     * Enlarges the rectangle to cover the specified Rectangle2D.
     * 
     * @param r
     *            the Rectangle2D object.
     * @return the union of the original and the specified Rectangle2D.
     * @see java.awt.geom.Rectangle2D#createUnion(java.awt.geom.Rectangle2D)
     */
    @Override
    public Rectangle2D createUnion(Rectangle2D r) {
        if (r instanceof Rectangle) {
            return union((Rectangle)r);
        }
        Rectangle2D dst = new Rectangle2D.Double();
        Rectangle2D.union(this, r, dst);
        return dst;
    }

    /**
     * Enlarges the rectangle to cover the specified rectangle.
     * 
     * @param r
     *            the Rectangle.
     * @return the union of the original and the specified rectangle.
     */
    public Rectangle union(Rectangle r) {
        Rectangle dst = new Rectangle(this);
        dst.add(r);
        return dst;
    }

    /**
     * Compares the original Rectangle with the specified object.
     * 
     * @param obj
     *            the specified Object for comparison.
     * @return true, if the specified Object is a rectangle with the same
     *         dimensions as the original rectangle, false otherwise.
     * @see java.awt.geom.Rectangle2D#equals(Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Rectangle) {
            Rectangle r = (Rectangle)obj;
            return r.x == x && r.y == y && r.width == width && r.height == height;
        }
        return false;
    }

    /**
     * Returns a string representation of the rectangle; the string contains [x,
     * y, width, height] parameters of the rectangle.
     * 
     * @return the string representation of the rectangle.
     */
    @Override
    public String toString() {
        // The output format based on 1.5 release behaviour. It could be
        // obtained in the following way
        // System.out.println(new Rectangle().toString())
        return getClass().getName() + "[x=" + x + ",y=" + y + //$NON-NLS-1$ //$NON-NLS-2$
                ",width=" + width + ",height=" + height + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

}
