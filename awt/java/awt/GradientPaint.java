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

package java.awt;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The GradientPaint class defines a way to fill a Shape with a linear color
 * gradient pattern.
 * <p>
 * The GradientPaint's fill pattern is determined by two points and two colors,
 * plus the cyclic mode option. Each of the two points is painted with its
 * corresponding color, and on the line segment connecting the two points, the
 * color is proportionally changed between the two colors. For points on the
 * same line which are not between the two specified points (outside of the
 * connecting segment) their color is determined by the cyclic mode option. If
 * the mode is cyclic, then the rest of the line repeats the color pattern of
 * the connecting segment, cycling back and forth between the two colors. If
 * not, the mode is acyclic which means that all points on the line outside the
 * connecting line segment are given the same color as the closest of the two
 * specified points.
 * <p>
 * The color of points that are not on the line connecting the two specified
 * points are given by perpendicular projection: by taking the set of lines
 * perpendicular to the connecting line and for each one, the whole line is
 * colored with the same color.
 * 
 * @since Android 1.0
 */
public class GradientPaint implements Paint {

    /**
     * The start point color.
     */
    Color color1;

    /**
     * The end color point.
     */
    Color color2;

    /**
     * The location of the start point.
     */
    Point2D point1;

    /**
     * The location of the end point.
     */
    Point2D point2;

    /**
     * The indicator of cycle filling. If TRUE filling repeated outside points
     * stripe, if FALSE solid color filling outside.
     */
    boolean cyclic;

    /**
     * Instantiates a new GradientPaint with cyclic or acyclic mode.
     * 
     * @param point1
     *            the first specified point.
     * @param color1
     *            the Color of the first specified point.
     * @param point2
     *            the second specified point.
     * @param color2
     *            the Color of the second specified point.
     * @param cyclic
     *            the cyclic mode - true if the gradient pattern should cycle
     *            repeatedly between the two colors; false otherwise.
     */
    public GradientPaint(Point2D point1, Color color1, Point2D point2, Color color2, boolean cyclic) {
        if (point1 == null || point2 == null) {
            // awt.6D=Point is null
            throw new NullPointerException(Messages.getString("awt.6D")); //$NON-NLS-1$
        }
        if (color1 == null || color2 == null) {
            // awt.6E=Color is null
            throw new NullPointerException(Messages.getString("awt.6E")); //$NON-NLS-1$
        }

        this.point1 = point1;
        this.point2 = point2;
        this.color1 = color1;
        this.color2 = color2;
        this.cyclic = cyclic;
    }

    /**
     * Instantiates a new GradientPaint with cyclic or acyclic mode; points are
     * specified by coordinates.
     * 
     * @param x1
     *            the X coordinate of the first point.
     * @param y1
     *            the Y coordinate of the first point.
     * @param color1
     *            the color of the first point.
     * @param x2
     *            the X coordinate of the second point.
     * @param y2
     *            the Y coordinate of the second point.
     * @param color2
     *            the color of the second point.
     * @param cyclic
     *            the cyclic mode - true if the gradient pattern should cycle
     *            repeatedly between the two colors; false otherwise.
     */
    public GradientPaint(float x1, float y1, Color color1, float x2, float y2, Color color2,
            boolean cyclic) {
        this(new Point2D.Float(x1, y1), color1, new Point2D.Float(x2, y2), color2, cyclic);
    }

    /**
     * Instantiates a new acyclic GradientPaint; points are specified by
     * coordinates.
     * 
     * @param x1
     *            the X coordinate of the first point.
     * @param y1
     *            the Y coordinate of the first point.
     * @param color1
     *            the color of the first point.
     * @param x2
     *            the X coordinate of the second point.
     * @param y2
     *            the Y coordinate of the second point.
     * @param color2
     *            the color of the second point.
     */
    public GradientPaint(float x1, float y1, Color color1, float x2, float y2, Color color2) {
        this(x1, y1, color1, x2, y2, color2, false);
    }

    /**
     * Instantiates a new acyclic GradientPaint.
     * 
     * @param point1
     *            the first specified point.
     * @param color1
     *            the Color of the first specified point.
     * @param point2
     *            the second specified point.
     * @param color2
     *            the Color of the second specified point.
     */
    public GradientPaint(Point2D point1, Color color1, Point2D point2, Color color2) {
        this(point1, color1, point2, color2, false);
    }

    /**
     * Creates PaintContext for a color pattern generating.
     * 
     * @param cm
     *            the ColorModel of the Paint data.
     * @param deviceBounds
     *            the bounding Rectangle of graphics primitives being rendered
     *            in the device space.
     * @param userBounds
     *            the bounding Rectangle of graphics primitives being rendered
     *            in the user space.
     * @param t
     *            the AffineTransform from user space into device space.
     * @param hints
     *            the RrenderingHints object.
     * @return the PaintContext for color pattern generating.
     * @see java.awt.Paint#createContext(java.awt.image.ColorModel,
     *      java.awt.Rectangle, java.awt.geom.Rectangle2D,
     *      java.awt.geom.AffineTransform, java.awt.RenderingHints)
     */
    public PaintContext createContext(ColorModel cm, Rectangle deviceBounds,
            Rectangle2D userBounds, AffineTransform t, RenderingHints hints) {
        return new GradientPaintContext(cm, t, point1, color1, point2, color2, cyclic);
    }

    /**
     * Gets the color of the first point.
     * 
     * @return the color of the first point.
     */
    public Color getColor1() {
        return color1;
    }

    /**
     * Gets the color of the second point.
     * 
     * @return the color of the second point.
     */
    public Color getColor2() {
        return color2;
    }

    /**
     * Gets the first point.
     * 
     * @return the Point object - the first point.
     */
    public Point2D getPoint1() {
        return point1;
    }

    /**
     * Gets the second point.
     * 
     * @return the Point object - the second point.
     */
    public Point2D getPoint2() {
        return point2;
    }

    /**
     * Gets the transparency mode for the GradientPaint.
     * 
     * @return the transparency mode for the GradientPaint.
     * @see java.awt.Transparency#getTransparency()
     */
    public int getTransparency() {
        int a1 = color1.getAlpha();
        int a2 = color2.getAlpha();
        return (a1 == 0xFF && a2 == 0xFF) ? OPAQUE : TRANSLUCENT;
    }

    /**
     * Returns the GradientPaint mode: true for cyclic mode, false for acyclic
     * mode.
     * 
     * @return true if the gradient cycles repeatedly between the two colors;
     *         false otherwise.
     */
    public boolean isCyclic() {
        return cyclic;
    }
}
