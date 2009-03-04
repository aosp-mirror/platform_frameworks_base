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
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * The Shape interface defines a geometric shape defined by a boundary (outline)
 * path. The path outline can be accessed through a PathIterator object. The
 * Shape interface provides methods for obtaining the bounding box (which is the
 * smallest rectangle containing the shape and for obtaining a PathIterator
 * object for current Shape, as well as utility methods which determine if the
 * Shape contains or intersects a Rectangle or contains a Point.
 * 
 * @since Android 1.0
 */
public interface Shape {

    /**
     * Checks whether or not the point with specified coordinates lies inside
     * the Shape.
     * 
     * @param x
     *            the X coordinate.
     * @param y
     *            the Y coordinate.
     * @return true, if the specified coordinates lie inside the Shape, false
     *         otherwise.
     */
    public boolean contains(double x, double y);

    /**
     * Checks whether or not the rectangle with specified [x, y, width, height]
     * parameters lies inside the Shape.
     * 
     * @param x
     *            the X double coordinate of the rectangle's upper left corner.
     * @param y
     *            the Y double coordinate of the rectangle's upper left corner.
     * @param w
     *            the width of rectangle.
     * @param h
     *            the height of rectangle.
     * @return true, if the specified rectangle lies inside the Shape, false
     *         otherwise.
     */
    public boolean contains(double x, double y, double w, double h);

    /**
     * Checks whether or not the specified Point2D lies inside the Shape.
     * 
     * @param point
     *            the Point2D object.
     * @return true, if the specified Point2D lies inside the Shape, false
     *         otherwise.
     */
    public boolean contains(Point2D point);

    /**
     * Checks whether or not the specified rectangle lies inside the Shape.
     * 
     * @param r
     *            the Rectangle2D object.
     * @return true, if the specified rectangle lies inside the Shape, false
     *         otherwise.
     */
    public boolean contains(Rectangle2D r);

    /**
     * Gets the bounding rectangle of the Shape. The bounding rectangle is the
     * smallest rectangle which contains the Shape.
     * 
     * @return the bounding rectangle of the Shape.
     */
    public Rectangle getBounds();

    /**
     * Gets the Rectangle2D which represents Shape bounds. The bounding
     * rectangle is the smallest rectangle which contains the Shape.
     * 
     * @return the bounding rectangle of the Shape.
     */
    public Rectangle2D getBounds2D();

    /**
     * Gets the PathIterator object of the Shape which provides access to the
     * shape's boundary modified by the specified AffineTransform.
     * 
     * @param at
     *            the specified AffineTransform object or null.
     * @return PathIterator object for the Shape.
     */
    public PathIterator getPathIterator(AffineTransform at);

    /**
     * Gets the PathIterator object of the Shape which provides access to the
     * coordinates of the shapes boundary modified by the specified
     * AffineTransform. The flatness parameter defines the amount of subdivision
     * of the curved segments and specifies the maximum distance which every
     * point on the unflattened transformed curve can deviate from the returned
     * flattened path segments.
     * 
     * @param at
     *            the specified AffineTransform object or null.
     * @param flatness
     *            the maximum number of the control points for a given curve
     *            which varies from colinear before a subdivided curve is
     *            replaced by a straight line connecting the endpoints.
     * @return PathIterator object for the Shape.
     */
    public PathIterator getPathIterator(AffineTransform at, double flatness);

    /**
     * Checks whether or not the interior of rectangular specified by [x, y,
     * width, height] parameters intersects the interior of the Shape.
     * 
     * @param x
     *            the X double coordinate of the rectangle's upper left corner.
     * @param y
     *            the Y double coordinate of the rectangle's upper left corner.
     * @param w
     *            the width of rectangle.
     * @param h
     *            the height of rectangle.
     * @return true, if the rectangle specified by [x, y, width, height]
     *         parameters intersects the interior of the Shape, false otherwise.
     */
    public boolean intersects(double x, double y, double w, double h);

    /**
     * Checks whether or not the interior of rectangle specified by Rectangle2D
     * object intersects the interior of the Shape.
     * 
     * @param r
     *            the Rectangle2D object.
     * @return true, if the Rectangle2D intersects the interior of the Shape,
     *         otherwise false.
     */
    public boolean intersects(Rectangle2D r);
}
