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

/**
 * The Interface PathIterator represents an iterator object that can be used to
 * traverse the outline of a {@link java.awt.Shape}. It returns points along the
 * boundary of the Shape which may be actual vertices (in the case of a shape
 * made of line segments) or may be points on a curved segment with the distance
 * between the points determined by a chosen flattening factor.
 * <p>
 * If the shape is closed, the outline is traversed in the counter-clockwise
 * direction. That means that moving forward along the boundary is to travel in
 * such a way that the interior of the shape is to the left of the outline path
 * and the exterior of the shape is to the right of the outline path. The
 * interior and exterior of the shape are determined by a winding rule.
 * </p>
 * 
 * @since Android 1.0
 */
public interface PathIterator {

    /**
     * The Constant WIND_EVEN_ODD indicates the winding rule that says that a
     * point is outside the shape if any infinite ray from the point crosses the
     * outline of the shape an even number of times, otherwise it is inside.
     */
    public static final int WIND_EVEN_ODD = 0;

    /**
     * The Constant WIND_NON_ZERO indicates the winding rule that says that a
     * point is inside the shape if every infinite ray starting from that point
     * crosses the outline of the shape a non-zero number of times.
     */
    public static final int WIND_NON_ZERO = 1;

    /**
     * The Constant SEG_MOVETO indicates that to follow the shape's outline from
     * the previous point to the current point, the cursor (traversal point)
     * should be placed directly on the current point.
     */
    public static final int SEG_MOVETO = 0;

    /**
     * The Constant SEG_LINETO indicates that to follow the shape's outline from
     * the previous point to the current point, the cursor (traversal point)
     * should follow a straight line.
     */
    public static final int SEG_LINETO = 1;

    /**
     * The Constant SEG_QUADTO indicates that to follow the shape's outline from
     * the previous point to the current point, the cursor (traversal point)
     * should follow a quadratic curve.
     */
    public static final int SEG_QUADTO = 2;

    /**
     * The Constant SEG_CUBICTO indicates that to follow the shape's outline
     * from the previous point to the current point, the cursor (traversal
     * point) should follow a cubic curve.
     */
    public static final int SEG_CUBICTO = 3;

    /**
     * The Constant SEG_CLOSE indicates that the previous point was the end of
     * the shape's outline.
     */
    public static final int SEG_CLOSE = 4;

    /**
     * Gets the winding rule, either {@link PathIterator#WIND_EVEN_ODD} or
     * {@link PathIterator#WIND_NON_ZERO}.
     * 
     * @return the winding rule.
     */
    public int getWindingRule();

    /**
     * Checks if this PathIterator has been completely traversed.
     * 
     * @return true, if this PathIterator has been completely traversed.
     */
    public boolean isDone();

    /**
     * Tells this PathIterator to skip to the next segment.
     */
    public void next();

    /**
     * Gets the coordinates of the next vertex point along the shape's outline
     * and a flag that indicates what kind of segment to use in order to connect
     * the previous vertex point to the current vertex point to form the current
     * segment.
     * 
     * @param coords
     *            the array that the coordinates of the end point of the current
     *            segment are written into.
     * @return the flag that indicates how to follow the shape's outline from
     *         the previous point to the current one, chosen from the following
     *         constants: {@link PathIterator#SEG_MOVETO},
     *         {@link PathIterator#SEG_LINETO}, {@link PathIterator#SEG_QUADTO},
     *         {@link PathIterator#SEG_CUBICTO}, or
     *         {@link PathIterator#SEG_CLOSE}.
     */
    public int currentSegment(float[] coords);

    /**
     * Gets the coordinates of the next vertex point along the shape's outline
     * and a flag that indicates what kind of segment to use in order to connect
     * the previous vertex point to the current vertex point to form the current
     * segment.
     * 
     * @param coords
     *            the array that the coordinates of the end point of the current
     *            segment are written into.
     * @return the flag that indicates how to follow the shape's outline from
     *         the previous point to the current one, chosen from the following
     *         constants: {@link PathIterator#SEG_MOVETO},
     *         {@link PathIterator#SEG_LINETO}, {@link PathIterator#SEG_QUADTO},
     *         {@link PathIterator#SEG_CUBICTO}, or
     *         {@link PathIterator#SEG_CLOSE}.
     */
    public int currentSegment(double[] coords);

}
