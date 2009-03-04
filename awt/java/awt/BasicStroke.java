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

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;

import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.misc.HashCode;

/**
 * The BasicStroke class specifies a set of rendering attributes for the
 * outlines of graphics primitives. The BasicStroke attributes describe the
 * shape of the pen which draws the outline of a Shape and the decorations
 * applied at the ends and joins of path segments of the Shape. The BasicStroke
 * has the following rendering attributes:
 * <p>
 * <ul>
 * <li>line width -the pen width which draws the outlines.</li>
 * <li>end caps - indicates the decoration applied to the ends of unclosed
 * subpaths and dash segments. The BasicStroke defines three different
 * decorations: CAP_BUTT, CAP_ROUND, and CAP_SQUARE.</li>
 * <li>line joins - indicates the decoration applied at the intersection of two
 * path segments and at the intersection of the endpoints of a subpath. The
 * BasicStroke defines three decorations: JOIN_BEVEL, JOIN_MITER, and
 * JOIN_ROUND.</li>
 * <li>miter limit - the limit to trim a line join that has a JOIN_MITER
 * decoration.</li>
 * <li>dash attributes - the definition of how to make a dash pattern by
 * alternating between opaque and transparent sections</li>
 * </ul>
 * </p>
 * 
 * @since Android 1.0
 */
public class BasicStroke implements Stroke {

    /**
     * The Constant CAP_BUTT indicates the ends of unclosed subpaths and dash
     * segments have no added decoration.
     */
    public static final int CAP_BUTT = 0;

    /**
     * The Constant CAP_ROUND indicates the ends of unclosed subpaths and dash
     * segments have a round decoration.
     */
    public static final int CAP_ROUND = 1;

    /**
     * The Constant CAP_SQUARE indicates the ends of unclosed subpaths and dash
     * segments have a square projection.
     */
    public static final int CAP_SQUARE = 2;

    /**
     * The Constant JOIN_MITER indicates that path segments are joined by
     * extending their outside edges until they meet.
     */
    public static final int JOIN_MITER = 0;

    /**
     * The Constant JOIN_ROUND indicates that path segments are joined by
     * rounding off the corner at a radius of half the line width.
     */
    public static final int JOIN_ROUND = 1;

    /**
     * The Constant JOIN_BEVEL indicates that path segments are joined by
     * connecting the outer corners of their wide outlines with a straight
     * segment.
     */
    public static final int JOIN_BEVEL = 2;

    /**
     * Constants for calculating.
     */
    static final int MAX_LEVEL = 20; // Maximal deepness of curve subdivision

    /**
     * The Constant CURVE_DELTA.
     */
    static final double CURVE_DELTA = 2.0; // Width tolerance

    /**
     * The Constant CORNER_ANGLE.
     */
    static final double CORNER_ANGLE = 4.0; // Minimum corner angle

    /**
     * The Constant CORNER_ZERO.
     */
    static final double CORNER_ZERO = 0.01; // Zero angle

    /**
     * The Constant CUBIC_ARC.
     */
    static final double CUBIC_ARC = 4.0 / 3.0 * (Math.sqrt(2.0) - 1);

    /**
     * Stroke width.
     */
    float width;

    /**
     * Stroke cap type.
     */
    int cap;

    /**
     * Stroke join type.
     */
    int join;

    /**
     * Stroke miter limit.
     */
    float miterLimit;

    /**
     * Stroke dashes array.
     */
    float dash[];

    /**
     * Stroke dash phase.
     */
    float dashPhase;

    /**
     * The temporary pre-calculated values.
     */
    double curveDelta;

    /**
     * The corner delta.
     */
    double cornerDelta;

    /**
     * The zero delta.
     */
    double zeroDelta;

    /**
     * The w2.
     */
    double w2;

    /**
     * The fmy.
     */
    double fmx, fmy;

    /**
     * The smy.
     */
    double scx, scy, smx, smy;

    /**
     * The cy.
     */
    double mx, my, cx, cy;

    /**
     * The temporary indicators.
     */
    boolean isMove;

    /**
     * The is first.
     */
    boolean isFirst;

    /**
     * The check move.
     */
    boolean checkMove;

    /**
     * The temporary and destination work paths.
     */
    BufferedPath dst, lp, rp, sp;

    /**
     * Stroke dasher class.
     */
    Dasher dasher;

    /**
     * Instantiates a new BasicStroke with default width, cap, join, limit, dash
     * attributes parameters. The default parameters are a solid line of width
     * 1.0, CAP_SQUARE, JOIN_MITER, a miter limit of 10.0, null dash attributes,
     * and a dash phase of 0.0f.
     */
    public BasicStroke() {
        this(1.0f, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f);
    }

    /**
     * Instantiates a new BasicStroke with the specified width, caps, joins,
     * limit, dash attributes, dash phase parameters.
     * 
     * @param width
     *            the width of BasikStroke.
     * @param cap
     *            the end decoration of BasikStroke.
     * @param join
     *            the join segments decoration.
     * @param miterLimit
     *            the limit to trim the miter join.
     * @param dash
     *            the array with the dashing pattern.
     * @param dashPhase
     *            the offset to start the dashing pattern.
     */
    public BasicStroke(float width, int cap, int join, float miterLimit, float[] dash,
            float dashPhase) {
        if (width < 0.0f) {
            // awt.133=Negative width
            throw new IllegalArgumentException(Messages.getString("awt.133")); //$NON-NLS-1$
        }
        if (cap != CAP_BUTT && cap != CAP_ROUND && cap != CAP_SQUARE) {
            // awt.134=Illegal cap
            throw new IllegalArgumentException(Messages.getString("awt.134")); //$NON-NLS-1$
        }
        if (join != JOIN_MITER && join != JOIN_ROUND && join != JOIN_BEVEL) {
            // awt.135=Illegal join
            throw new IllegalArgumentException(Messages.getString("awt.135")); //$NON-NLS-1$
        }
        if (join == JOIN_MITER && miterLimit < 1.0f) {
            // awt.136=miterLimit less than 1.0f
            throw new IllegalArgumentException(Messages.getString("awt.136")); //$NON-NLS-1$
        }
        if (dash != null) {
            if (dashPhase < 0.0f) {
                // awt.137=Negative dashPhase
                throw new IllegalArgumentException(Messages.getString("awt.137")); //$NON-NLS-1$
            }
            if (dash.length == 0) {
                // awt.138=Zero dash length
                throw new IllegalArgumentException(Messages.getString("awt.138")); //$NON-NLS-1$
            }
            ZERO: {
                for (int i = 0; i < dash.length; i++) {
                    if (dash[i] < 0.0) {
                        // awt.139=Negative dash[{0}]
                        throw new IllegalArgumentException(Messages.getString("awt.139", i)); //$NON-NLS-1$
                    }
                    if (dash[i] > 0.0) {
                        break ZERO;
                    }
                }
                // awt.13A=All dash lengths zero
                throw new IllegalArgumentException(Messages.getString("awt.13A")); //$NON-NLS-1$
            }
        }
        this.width = width;
        this.cap = cap;
        this.join = join;
        this.miterLimit = miterLimit;
        this.dash = dash;
        this.dashPhase = dashPhase;
    }

    /**
     * Instantiates a new BasicStroke with specified width, cap, join, limit and
     * default dash attributes parameters.
     * 
     * @param width
     *            the width of BasikStroke.
     * @param cap
     *            the end decoration of BasikStroke.
     * @param join
     *            the join segments decoration.
     * @param miterLimit
     *            the limit to trim the miter join.
     */
    public BasicStroke(float width, int cap, int join, float miterLimit) {
        this(width, cap, join, miterLimit, null, 0.0f);
    }

    /**
     * Instantiates a new BasicStroke with specified width, cap, join and
     * default limit and dash attributes parameters.
     * 
     * @param width
     *            the width of BasikStroke.
     * @param cap
     *            the end decoration of BasikStroke.
     * @param join
     *            the join segments decoration.
     */
    public BasicStroke(float width, int cap, int join) {
        this(width, cap, join, 10.0f, null, 0.0f);
    }

    /**
     * Instantiates a new BasicStroke with specified width and default cap,
     * join, limit, dash attributes parameters.
     * 
     * @param width
     *            the width of BasicStroke.
     */
    public BasicStroke(float width) {
        this(width, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f);
    }

    /**
     * Gets the line width of the BasicStroke.
     * 
     * @return the line width of the BasicStroke.
     */
    public float getLineWidth() {
        return width;
    }

    /**
     * Gets the end cap style of the BasicStroke.
     * 
     * @return the end cap style of the BasicStroke.
     */
    public int getEndCap() {
        return cap;
    }

    /**
     * Gets the line join style of the BasicStroke.
     * 
     * @return the line join style of the BasicStroke.
     */
    public int getLineJoin() {
        return join;
    }

    /**
     * Gets the miter limit of the BasicStroke (the limit to trim the miter
     * join).
     * 
     * @return the miter limit of the BasicStroke.
     */
    public float getMiterLimit() {
        return miterLimit;
    }

    /**
     * Gets the dash attributes array of the BasicStroke.
     * 
     * @return the dash attributes array of the BasicStroke.
     */
    public float[] getDashArray() {
        return dash;
    }

    /**
     * Gets the dash phase of the BasicStroke.
     * 
     * @return the dash phase of the BasicStroke.
     */
    public float getDashPhase() {
        return dashPhase;
    }

    /**
     * Returns hash code of this BasicStroke.
     * 
     * @return the hash code of this BasicStroke.
     */
    @Override
    public int hashCode() {
        HashCode hash = new HashCode();
        hash.append(width);
        hash.append(cap);
        hash.append(join);
        hash.append(miterLimit);
        if (dash != null) {
            hash.append(dashPhase);
            for (float element : dash) {
                hash.append(element);
            }
        }
        return hash.hashCode();
    }

    /**
     * Compares this BasicStroke object with the specified Object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the Object is a BasicStroke with the same data values as
     *         this BasicStroke; false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof BasicStroke) {
            BasicStroke bs = (BasicStroke)obj;
            return bs.width == width && bs.cap == cap && bs.join == join
                    && bs.miterLimit == miterLimit && bs.dashPhase == dashPhase
                    && java.util.Arrays.equals(bs.dash, dash);
        }
        return false;
    }

    /**
     * Calculates allowable curve derivation.
     * 
     * @param width
     *            the width.
     * @return the curve delta.
     */
    double getCurveDelta(double width) {
        double a = width + CURVE_DELTA;
        double cos = 1.0 - 2.0 * width * width / (a * a);
        double sin = Math.sqrt(1.0 - cos * cos);
        return Math.abs(sin / cos);
    }

    /**
     * Calculates the value to detect a small angle.
     * 
     * @param width
     *            the width.
     * @return the corner delta.
     */
    double getCornerDelta(double width) {
        return width * width * Math.sin(Math.PI * CORNER_ANGLE / 180.0);
    }

    /**
     * Calculates value to detect a zero angle.
     * 
     * @param width
     *            the width.
     * @return the zero delta.
     */
    double getZeroDelta(double width) {
        return width * width * Math.sin(Math.PI * CORNER_ZERO / 180.0);
    }

    /**
     * Creates a Shape from the outline of the specified shape drawn with this
     * BasicStroke.
     * 
     * @param s
     *            the specified Shape to be stroked.
     * @return the Shape of the stroked outline.
     * @see java.awt.Stroke#createStrokedShape(java.awt.Shape)
     */
    public Shape createStrokedShape(Shape s) {
        w2 = width / 2.0;
        curveDelta = getCurveDelta(w2);
        cornerDelta = getCornerDelta(w2);
        zeroDelta = getZeroDelta(w2);

        dst = new BufferedPath();
        lp = new BufferedPath();
        rp = new BufferedPath();

        if (dash == null) {
            createSolidShape(s.getPathIterator(null));
        } else {
            createDashedShape(s.getPathIterator(null));
        }

        return dst.createGeneralPath();
    }

    /**
     * Generates a shape with a solid (not dashed) outline.
     * 
     * @param p
     *            the PathIterator of source shape.
     */
    void createSolidShape(PathIterator p) {
        double coords[] = new double[6];
        mx = my = cx = cy = 0.0;
        isMove = false;
        isFirst = false;
        checkMove = true;
        boolean isClosed = true;

        while (!p.isDone()) {
            switch (p.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    if (!isClosed) {
                        closeSolidShape();
                    }
                    rp.clean();
                    mx = cx = coords[0];
                    my = cy = coords[1];
                    isMove = true;
                    isClosed = false;
                    break;
                case PathIterator.SEG_LINETO:
                    addLine(cx, cy, cx = coords[0], cy = coords[1], true);
                    break;
                case PathIterator.SEG_QUADTO:
                    addQuad(cx, cy, coords[0], coords[1], cx = coords[2], cy = coords[3]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    addCubic(cx, cy, coords[0], coords[1], coords[2], coords[3], cx = coords[4],
                            cy = coords[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    addLine(cx, cy, mx, my, false);
                    addJoin(lp, mx, my, lp.xMove, lp.yMove, true);
                    addJoin(rp, mx, my, rp.xMove, rp.yMove, false);
                    lp.closePath();
                    rp.closePath();
                    lp.appendReverse(rp);
                    isClosed = true;
                    break;
            }
            p.next();
        }
        if (!isClosed) {
            closeSolidShape();
        }

        dst = lp;
    }

    /**
     * Closes solid shape path.
     */
    void closeSolidShape() {
        addCap(lp, cx, cy, rp.xLast, rp.yLast);
        lp.combine(rp);
        addCap(lp, mx, my, lp.xMove, lp.yMove);
        lp.closePath();
    }

    /**
     * Generates dashed stroked shape.
     * 
     * @param p
     *            the PathIterator of source shape.
     */
    void createDashedShape(PathIterator p) {
        double coords[] = new double[6];
        mx = my = cx = cy = 0.0;
        smx = smy = scx = scy = 0.0;
        isMove = false;
        checkMove = false;
        boolean isClosed = true;

        while (!p.isDone()) {
            switch (p.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:

                    if (!isClosed) {
                        closeDashedShape();
                    }

                    dasher = new Dasher(dash, dashPhase);
                    lp.clean();
                    rp.clean();
                    sp = null;
                    isFirst = true;
                    isMove = true;
                    isClosed = false;
                    mx = cx = coords[0];
                    my = cy = coords[1];
                    break;
                case PathIterator.SEG_LINETO:
                    addDashLine(cx, cy, cx = coords[0], cy = coords[1]);
                    break;
                case PathIterator.SEG_QUADTO:
                    addDashQuad(cx, cy, coords[0], coords[1], cx = coords[2], cy = coords[3]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    addDashCubic(cx, cy, coords[0], coords[1], coords[2], coords[3],
                            cx = coords[4], cy = coords[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    addDashLine(cx, cy, cx = mx, cy = my);

                    if (dasher.isConnected()) {
                        // Connect current and head segments
                        addJoin(lp, fmx, fmy, sp.xMove, sp.yMove, true);
                        lp.join(sp);
                        addJoin(lp, fmx, fmy, rp.xLast, rp.yLast, true);
                        lp.combine(rp);
                        addCap(lp, smx, smy, lp.xMove, lp.yMove);
                        lp.closePath();
                        dst.append(lp);
                        sp = null;
                    } else {
                        closeDashedShape();
                    }

                    isClosed = true;
                    break;
            }
            p.next();
        }

        if (!isClosed) {
            closeDashedShape();
        }

    }

    /**
     * Closes dashed shape path.
     */
    void closeDashedShape() {
        // Add head segment
        if (sp != null) {
            addCap(sp, fmx, fmy, sp.xMove, sp.yMove);
            sp.closePath();
            dst.append(sp);
        }
        if (lp.typeSize > 0) {
            // Close current segment
            if (!dasher.isClosed()) {
                addCap(lp, scx, scy, rp.xLast, rp.yLast);
                lp.combine(rp);
                addCap(lp, smx, smy, lp.xMove, lp.yMove);
                lp.closePath();
            }
            dst.append(lp);
        }
    }

    /**
     * Adds cap to the work path.
     * 
     * @param p
     *            the BufferedPath object of work path.
     * @param x0
     *            the x coordinate of the source path.
     * @param y0
     *            the y coordinate on the source path.
     * @param x2
     *            the x coordinate of the next point on the work path.
     * @param y2
     *            the y coordinate of the next point on the work path.
     */
    void addCap(BufferedPath p, double x0, double y0, double x2, double y2) {
        double x1 = p.xLast;
        double y1 = p.yLast;
        double x10 = x1 - x0;
        double y10 = y1 - y0;
        double x20 = x2 - x0;
        double y20 = y2 - y0;

        switch (cap) {
            case CAP_BUTT:
                p.lineTo(x2, y2);
                break;
            case CAP_ROUND:
                double mx = x10 * CUBIC_ARC;
                double my = y10 * CUBIC_ARC;

                double x3 = x0 + y10;
                double y3 = y0 - x10;

                x10 *= CUBIC_ARC;
                y10 *= CUBIC_ARC;
                x20 *= CUBIC_ARC;
                y20 *= CUBIC_ARC;

                p.cubicTo(x1 + y10, y1 - x10, x3 + mx, y3 + my, x3, y3);
                p.cubicTo(x3 - mx, y3 - my, x2 - y20, y2 + x20, x2, y2);
                break;
            case CAP_SQUARE:
                p.lineTo(x1 + y10, y1 - x10);
                p.lineTo(x2 - y20, y2 + x20);
                p.lineTo(x2, y2);
                break;
        }
    }

    /**
     * Adds bevel and miter join to the work path.
     * 
     * @param p
     *            the BufferedPath object of work path.
     * @param x0
     *            the x coordinate of the source path.
     * @param y0
     *            the y coordinate on the source path.
     * @param x2
     *            the x coordinate of the next point on the work path.
     * @param y2
     *            the y coordinate of the next point on the work path.
     * @param isLeft
     *            the orientation of work path, true if work path lies to the
     *            left from source path, false otherwise.
     */
    void addJoin(BufferedPath p, double x0, double y0, double x2, double y2, boolean isLeft) {
        double x1 = p.xLast;
        double y1 = p.yLast;
        double x10 = x1 - x0;
        double y10 = y1 - y0;
        double x20 = x2 - x0;
        double y20 = y2 - y0;
        double sin0 = x10 * y20 - y10 * x20;

        // Small corner
        if (-cornerDelta < sin0 && sin0 < cornerDelta) {
            double cos0 = x10 * x20 + y10 * y20;
            if (cos0 > 0.0) {
                // if zero corner do nothing
                if (-zeroDelta > sin0 || sin0 > zeroDelta) {
                    double x3 = x0 + w2 * w2 * (y20 - y10) / sin0;
                    double y3 = y0 + w2 * w2 * (x10 - x20) / sin0;
                    p.setLast(x3, y3);
                }
                return;
            }
            // Zero corner
            if (-zeroDelta < sin0 && sin0 < zeroDelta) {
                p.lineTo(x2, y2);
            }
            return;
        }

        if (isLeft ^ (sin0 < 0.0)) {
            // Twisted corner
            p.lineTo(x0, y0);
            p.lineTo(x2, y2);
        } else {
            switch (join) {
                case JOIN_BEVEL:
                    p.lineTo(x2, y2);
                    break;
                case JOIN_MITER:
                    double s1 = x1 * x10 + y1 * y10;
                    double s2 = x2 * x20 + y2 * y20;
                    double x3 = (s1 * y20 - s2 * y10) / sin0;
                    double y3 = (s2 * x10 - s1 * x20) / sin0;
                    double x30 = x3 - x0;
                    double y30 = y3 - y0;
                    double miterLength = Math.sqrt(x30 * x30 + y30 * y30);
                    if (miterLength < miterLimit * w2) {
                        p.lineTo(x3, y3);
                    }
                    p.lineTo(x2, y2);
                    break;
                case JOIN_ROUND:
                    addRoundJoin(p, x0, y0, x2, y2, isLeft);
                    break;
            }
        }
    }

    /**
     * Adds round join to the work path.
     * 
     * @param p
     *            the BufferedPath object of work path.
     * @param x0
     *            the x coordinate of the source path.
     * @param y0
     *            the y coordinate on the source path.
     * @param x2
     *            the x coordinate of the next point on the work path.
     * @param y2
     *            the y coordinate of the next point on the work path.
     * @param isLeft
     *            the orientation of work path, true if work path lies to the
     *            left from source path, false otherwise.
     */
    void addRoundJoin(BufferedPath p, double x0, double y0, double x2, double y2, boolean isLeft) {
        double x1 = p.xLast;
        double y1 = p.yLast;
        double x10 = x1 - x0;
        double y10 = y1 - y0;
        double x20 = x2 - x0;
        double y20 = y2 - y0;

        double x30 = x10 + x20;
        double y30 = y10 + y20;

        double l30 = Math.sqrt(x30 * x30 + y30 * y30);

        if (l30 < 1E-5) {
            p.lineTo(x2, y2);
            return;
        }

        double w = w2 / l30;

        x30 *= w;
        y30 *= w;

        double x3 = x0 + x30;
        double y3 = y0 + y30;

        double cos = x10 * x20 + y10 * y20;
        double a = Math.acos(cos / (w2 * w2));
        if (cos >= 0.0) {
            double k = 4.0 / 3.0 * Math.tan(a / 4.0);
            if (isLeft) {
                k = -k;
            }

            x10 *= k;
            y10 *= k;
            x20 *= k;
            y20 *= k;

            p.cubicTo(x1 - y10, y1 + x10, x2 + y20, y2 - x20, x2, y2);
        } else {
            double k = 4.0 / 3.0 * Math.tan(a / 8.0);
            if (isLeft) {
                k = -k;
            }

            x10 *= k;
            y10 *= k;
            x20 *= k;
            y20 *= k;
            x30 *= k;
            y30 *= k;

            p.cubicTo(x1 - y10, y1 + x10, x3 + y30, y3 - x30, x3, y3);
            p.cubicTo(x3 - y30, y3 + x30, x2 + y20, y2 - x20, x2, y2);
        }

    }

    /**
     * Adds solid line segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the start line point.
     * @param y1
     *            the y coordinate of the start line point.
     * @param x2
     *            the x coordinate of the end line point.
     * @param y2
     *            the y coordinate of the end line point.
     * @param zero
     *            if true it's allowable to add zero length line segment.
     */
    void addLine(double x1, double y1, double x2, double y2, boolean zero) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0.0 && dy == 0.0) {
            if (!zero) {
                return;
            }
            dx = w2;
            dy = 0;
        } else {
            double w = w2 / Math.sqrt(dx * dx + dy * dy);
            dx *= w;
            dy *= w;
        }

        double lx1 = x1 - dy;
        double ly1 = y1 + dx;
        double rx1 = x1 + dy;
        double ry1 = y1 - dx;

        if (checkMove) {
            if (isMove) {
                isMove = false;
                lp.moveTo(lx1, ly1);
                rp.moveTo(rx1, ry1);
            } else {
                addJoin(lp, x1, y1, lx1, ly1, true);
                addJoin(rp, x1, y1, rx1, ry1, false);
            }
        }

        lp.lineTo(x2 - dy, y2 + dx);
        rp.lineTo(x2 + dy, y2 - dx);
    }

    /**
     * Adds solid quad segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     */
    void addQuad(double x1, double y1, double x2, double y2, double x3, double y3) {
        double x21 = x2 - x1;
        double y21 = y2 - y1;
        double x23 = x2 - x3;
        double y23 = y2 - y3;

        double l21 = Math.sqrt(x21 * x21 + y21 * y21);
        double l23 = Math.sqrt(x23 * x23 + y23 * y23);

        if (l21 == 0.0 && l23 == 0.0) {
            addLine(x1, y1, x3, y3, false);
            return;
        }

        if (l21 == 0.0) {
            addLine(x2, y2, x3, y3, false);
            return;
        }

        if (l23 == 0.0) {
            addLine(x1, y1, x2, y2, false);
            return;
        }

        double w;
        w = w2 / l21;
        double mx1 = -y21 * w;
        double my1 = x21 * w;
        w = w2 / l23;
        double mx3 = y23 * w;
        double my3 = -x23 * w;

        double lx1 = x1 + mx1;
        double ly1 = y1 + my1;
        double rx1 = x1 - mx1;
        double ry1 = y1 - my1;

        if (checkMove) {
            if (isMove) {
                isMove = false;
                lp.moveTo(lx1, ly1);
                rp.moveTo(rx1, ry1);
            } else {
                addJoin(lp, x1, y1, lx1, ly1, true);
                addJoin(rp, x1, y1, rx1, ry1, false);
            }
        }

        if (x21 * y23 - y21 * x23 == 0.0) {
            // On line curve
            if (x21 * x23 + y21 * y23 > 0.0) {
                // Twisted curve
                if (l21 == l23) {
                    double px = x1 + (x21 + x23) / 4.0;
                    double py = y1 + (y21 + y23) / 4.0;
                    lp.lineTo(px + mx1, py + my1);
                    rp.lineTo(px - mx1, py - my1);
                    lp.lineTo(px - mx1, py - my1);
                    rp.lineTo(px + mx1, py + my1);
                    lp.lineTo(x3 - mx1, y3 - my1);
                    rp.lineTo(x3 + mx1, y3 + my1);
                } else {
                    double px1, py1;
                    double k = l21 / (l21 + l23);
                    double px = x1 + (x21 + x23) * k * k;
                    double py = y1 + (y21 + y23) * k * k;
                    px1 = (x1 + px) / 2.0;
                    py1 = (y1 + py) / 2.0;
                    lp.quadTo(px1 + mx1, py1 + my1, px + mx1, py + my1);
                    rp.quadTo(px1 - mx1, py1 - my1, px - mx1, py - my1);
                    lp.lineTo(px - mx1, py - my1);
                    rp.lineTo(px + mx1, py + my1);
                    px1 = (x3 + px) / 2.0;
                    py1 = (y3 + py) / 2.0;
                    lp.quadTo(px1 - mx1, py1 - my1, x3 - mx1, y3 - my1);
                    rp.quadTo(px1 + mx1, py1 + my1, x3 + mx1, y3 + my1);
                }
            } else {
                // Simple curve
                lp.quadTo(x2 + mx1, y2 + my1, x3 + mx3, y3 + my3);
                rp.quadTo(x2 - mx1, y2 - my1, x3 - mx3, y3 - my3);
            }
        } else {
            addSubQuad(x1, y1, x2, y2, x3, y3, 0);
        }
    }

    /**
     * Subdivides solid quad curve to make outline for source quad segment and
     * adds it to work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     * @param level
     *            the maximum level of subdivision deepness.
     */
    void addSubQuad(double x1, double y1, double x2, double y2, double x3, double y3, int level) {
        double x21 = x2 - x1;
        double y21 = y2 - y1;
        double x23 = x2 - x3;
        double y23 = y2 - y3;

        double cos = x21 * x23 + y21 * y23;
        double sin = x21 * y23 - y21 * x23;

        if (level < MAX_LEVEL && (cos >= 0.0 || (Math.abs(sin / cos) > curveDelta))) {
            double c1x = (x2 + x1) / 2.0;
            double c1y = (y2 + y1) / 2.0;
            double c2x = (x2 + x3) / 2.0;
            double c2y = (y2 + y3) / 2.0;
            double c3x = (c1x + c2x) / 2.0;
            double c3y = (c1y + c2y) / 2.0;
            addSubQuad(x1, y1, c1x, c1y, c3x, c3y, level + 1);
            addSubQuad(c3x, c3y, c2x, c2y, x3, y3, level + 1);
        } else {
            double w;
            double l21 = Math.sqrt(x21 * x21 + y21 * y21);
            double l23 = Math.sqrt(x23 * x23 + y23 * y23);
            w = w2 / sin;
            double mx2 = (x21 * l23 + x23 * l21) * w;
            double my2 = (y21 * l23 + y23 * l21) * w;
            w = w2 / l23;
            double mx3 = y23 * w;
            double my3 = -x23 * w;
            lp.quadTo(x2 + mx2, y2 + my2, x3 + mx3, y3 + my3);
            rp.quadTo(x2 - mx2, y2 - my2, x3 - mx3, y3 - my3);
        }
    }

    /**
     * Adds solid cubic segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     * @param x4
     *            the x coordinate of the fours control point.
     * @param y4
     *            the y coordinate of the fours control point.
     */
    void addCubic(double x1, double y1, double x2, double y2, double x3, double y3, double x4,
            double y4) {
        double x12 = x1 - x2;
        double y12 = y1 - y2;
        double x23 = x2 - x3;
        double y23 = y2 - y3;
        double x34 = x3 - x4;
        double y34 = y3 - y4;

        double l12 = Math.sqrt(x12 * x12 + y12 * y12);
        double l23 = Math.sqrt(x23 * x23 + y23 * y23);
        double l34 = Math.sqrt(x34 * x34 + y34 * y34);

        // All edges are zero
        if (l12 == 0.0 && l23 == 0.0 && l34 == 0.0) {
            addLine(x1, y1, x4, y4, false);
            return;
        }

        // One zero edge
        if (l12 == 0.0 && l23 == 0.0) {
            addLine(x3, y3, x4, y4, false);
            return;
        }

        if (l23 == 0.0 && l34 == 0.0) {
            addLine(x1, y1, x2, y2, false);
            return;
        }

        if (l12 == 0.0 && l34 == 0.0) {
            addLine(x2, y2, x3, y3, false);
            return;
        }

        double w, mx1, my1, mx4, my4;
        boolean onLine;

        if (l12 == 0.0) {
            w = w2 / l23;
            mx1 = y23 * w;
            my1 = -x23 * w;
            w = w2 / l34;
            mx4 = y34 * w;
            my4 = -x34 * w;
            onLine = -x23 * y34 + y23 * x34 == 0.0; // sin3
        } else if (l34 == 0.0) {
            w = w2 / l12;
            mx1 = y12 * w;
            my1 = -x12 * w;
            w = w2 / l23;
            mx4 = y23 * w;
            my4 = -x23 * w;
            onLine = -x12 * y23 + y12 * x23 == 0.0; // sin2
        } else {
            w = w2 / l12;
            mx1 = y12 * w;
            my1 = -x12 * w;
            w = w2 / l34;
            mx4 = y34 * w;
            my4 = -x34 * w;
            if (l23 == 0.0) {
                onLine = -x12 * y34 + y12 * x34 == 0.0;
            } else {
                onLine = -x12 * y34 + y12 * x34 == 0.0 && -x12 * y23 + y12 * x23 == 0.0 && // sin2
                        -x23 * y34 + y23 * x34 == 0.0; // sin3
            }
        }

        double lx1 = x1 + mx1;
        double ly1 = y1 + my1;
        double rx1 = x1 - mx1;
        double ry1 = y1 - my1;

        if (checkMove) {
            if (isMove) {
                isMove = false;
                lp.moveTo(lx1, ly1);
                rp.moveTo(rx1, ry1);
            } else {
                addJoin(lp, x1, y1, lx1, ly1, true);
                addJoin(rp, x1, y1, rx1, ry1, false);
            }
        }

        if (onLine) {
            if ((x1 == x2 && y1 < y2) || x1 < x2) {
                l12 = -l12;
            }
            if ((x2 == x3 && y2 < y3) || x2 < x3) {
                l23 = -l23;
            }
            if ((x3 == x4 && y3 < y4) || x3 < x4) {
                l34 = -l34;
            }
            double d = l23 * l23 - l12 * l34;
            double roots[] = new double[3];
            int rc = 0;
            if (d == 0.0) {
                double t = (l12 - l23) / (l12 + l34 - l23 - l23);
                if (0.0 < t && t < 1.0) {
                    roots[rc++] = t;
                }
            } else if (d > 0.0) {
                d = Math.sqrt(d);
                double z = l12 + l34 - l23 - l23;
                double t;
                t = (l12 - l23 + d) / z;
                if (0.0 < t && t < 1.0) {
                    roots[rc++] = t;
                }
                t = (l12 - l23 - d) / z;
                if (0.0 < t && t < 1.0) {
                    roots[rc++] = t;
                }
            }

            if (rc > 0) {
                // Sort roots
                if (rc == 2 && roots[0] > roots[1]) {
                    double tmp = roots[0];
                    roots[0] = roots[1];
                    roots[1] = tmp;
                }
                roots[rc++] = 1.0;

                double ax = -x34 - x12 + x23 + x23;
                double ay = -y34 - y12 + y23 + y23;
                double bx = 3.0 * (-x23 + x12);
                double by = 3.0 * (-y23 + y12);
                double cx = 3.0 * (-x12);
                double cy = 3.0 * (-y12);
                double xPrev = x1;
                double yPrev = y1;
                for (int i = 0; i < rc; i++) {
                    double t = roots[i];
                    double px = t * (t * (t * ax + bx) + cx) + x1;
                    double py = t * (t * (t * ay + by) + cy) + y1;
                    double px1 = (xPrev + px) / 2.0;
                    double py1 = (yPrev + py) / 2.0;
                    lp.cubicTo(px1 + mx1, py1 + my1, px1 + mx1, py1 + my1, px + mx1, py + my1);
                    rp.cubicTo(px1 - mx1, py1 - my1, px1 - mx1, py1 - my1, px - mx1, py - my1);
                    if (i < rc - 1) {
                        lp.lineTo(px - mx1, py - my1);
                        rp.lineTo(px + mx1, py + my1);
                    }
                    xPrev = px;
                    yPrev = py;
                    mx1 = -mx1;
                    my1 = -my1;
                }
            } else {
                lp.cubicTo(x2 + mx1, y2 + my1, x3 + mx4, y3 + my4, x4 + mx4, y4 + my4);
                rp.cubicTo(x2 - mx1, y2 - my1, x3 - mx4, y3 - my4, x4 - mx4, y4 - my4);
            }
        } else {
            addSubCubic(x1, y1, x2, y2, x3, y3, x4, y4, 0);
        }
    }

    /**
     * Subdivides solid cubic curve to make outline for source quad segment and
     * adds it to work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     * @param x4
     *            the x coordinate of the fours control point.
     * @param y4
     *            the y coordinate of the fours control point.
     * @param level
     *            the maximum level of subdivision deepness.
     */
    void addSubCubic(double x1, double y1, double x2, double y2, double x3, double y3, double x4,
            double y4, int level) {
        double x12 = x1 - x2;
        double y12 = y1 - y2;
        double x23 = x2 - x3;
        double y23 = y2 - y3;
        double x34 = x3 - x4;
        double y34 = y3 - y4;

        double cos2 = -x12 * x23 - y12 * y23;
        double cos3 = -x23 * x34 - y23 * y34;
        double sin2 = -x12 * y23 + y12 * x23;
        double sin3 = -x23 * y34 + y23 * x34;
        double sin0 = -x12 * y34 + y12 * x34;
        double cos0 = -x12 * x34 - y12 * y34;

        if (level < MAX_LEVEL
                && (sin2 != 0.0 || sin3 != 0.0 || sin0 != 0.0)
                && (cos2 >= 0.0 || cos3 >= 0.0 || cos0 >= 0.0
                        || (Math.abs(sin2 / cos2) > curveDelta)
                        || (Math.abs(sin3 / cos3) > curveDelta) || (Math.abs(sin0 / cos0) > curveDelta))) {
            double cx = (x2 + x3) / 2.0;
            double cy = (y2 + y3) / 2.0;
            double lx2 = (x2 + x1) / 2.0;
            double ly2 = (y2 + y1) / 2.0;
            double rx3 = (x3 + x4) / 2.0;
            double ry3 = (y3 + y4) / 2.0;
            double lx3 = (cx + lx2) / 2.0;
            double ly3 = (cy + ly2) / 2.0;
            double rx2 = (cx + rx3) / 2.0;
            double ry2 = (cy + ry3) / 2.0;
            cx = (lx3 + rx2) / 2.0;
            cy = (ly3 + ry2) / 2.0;
            addSubCubic(x1, y1, lx2, ly2, lx3, ly3, cx, cy, level + 1);
            addSubCubic(cx, cy, rx2, ry2, rx3, ry3, x4, y4, level + 1);
        } else {
            double w, mx1, my1, mx2, my2, mx3, my3, mx4, my4;
            double l12 = Math.sqrt(x12 * x12 + y12 * y12);
            double l23 = Math.sqrt(x23 * x23 + y23 * y23);
            double l34 = Math.sqrt(x34 * x34 + y34 * y34);

            if (l12 == 0.0) {
                w = w2 / l23;
                mx1 = y23 * w;
                my1 = -x23 * w;
                w = w2 / l34;
                mx4 = y34 * w;
                my4 = -x34 * w;
            } else if (l34 == 0.0) {
                w = w2 / l12;
                mx1 = y12 * w;
                my1 = -x12 * w;
                w = w2 / l23;
                mx4 = y23 * w;
                my4 = -x23 * w;
            } else {
                // Common case
                w = w2 / l12;
                mx1 = y12 * w;
                my1 = -x12 * w;
                w = w2 / l34;
                mx4 = y34 * w;
                my4 = -x34 * w;
            }

            if (sin2 == 0.0) {
                mx2 = mx1;
                my2 = my1;
            } else {
                w = w2 / sin2;
                mx2 = -(x12 * l23 - x23 * l12) * w;
                my2 = -(y12 * l23 - y23 * l12) * w;
            }
            if (sin3 == 0.0) {
                mx3 = mx4;
                my3 = my4;
            } else {
                w = w2 / sin3;
                mx3 = -(x23 * l34 - x34 * l23) * w;
                my3 = -(y23 * l34 - y34 * l23) * w;
            }

            lp.cubicTo(x2 + mx2, y2 + my2, x3 + mx3, y3 + my3, x4 + mx4, y4 + my4);
            rp.cubicTo(x2 - mx2, y2 - my2, x3 - mx3, y3 - my3, x4 - mx4, y4 - my4);
        }
    }

    /**
     * Adds dashed line segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the start line point.
     * @param y1
     *            the y coordinate of the start line point.
     * @param x2
     *            the x coordinate of the end line point.
     * @param y2
     *            the y coordinate of the end line point.
     */
    void addDashLine(double x1, double y1, double x2, double y2) {
        double x21 = x2 - x1;
        double y21 = y2 - y1;

        double l21 = Math.sqrt(x21 * x21 + y21 * y21);

        if (l21 == 0.0) {
            return;
        }

        double px1, py1;
        px1 = py1 = 0.0;
        double w = w2 / l21;
        double mx = -y21 * w;
        double my = x21 * w;

        dasher.init(new DashIterator.Line(l21));

        while (!dasher.eof()) {
            double t = dasher.getValue();
            scx = x1 + t * x21;
            scy = y1 + t * y21;

            if (dasher.isOpen()) {
                px1 = scx;
                py1 = scy;
                double lx1 = px1 + mx;
                double ly1 = py1 + my;
                double rx1 = px1 - mx;
                double ry1 = py1 - my;
                if (isMove) {
                    isMove = false;
                    smx = px1;
                    smy = py1;
                    rp.clean();
                    lp.moveTo(lx1, ly1);
                    rp.moveTo(rx1, ry1);
                } else {
                    addJoin(lp, x1, y1, lx1, ly1, true);
                    addJoin(rp, x1, y1, rx1, ry1, false);
                }
            } else if (dasher.isContinue()) {
                double px2 = scx;
                double py2 = scy;
                lp.lineTo(px2 + mx, py2 + my);
                rp.lineTo(px2 - mx, py2 - my);
                if (dasher.close) {
                    addCap(lp, px2, py2, rp.xLast, rp.yLast);
                    lp.combine(rp);
                    if (isFirst) {
                        isFirst = false;
                        fmx = smx;
                        fmy = smy;
                        sp = lp;
                        lp = new BufferedPath();
                    } else {
                        addCap(lp, smx, smy, lp.xMove, lp.yMove);
                        lp.closePath();
                    }
                    isMove = true;
                }
            }

            dasher.next();
        }
    }

    /**
     * Adds dashed quad segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     */
    void addDashQuad(double x1, double y1, double x2, double y2, double x3, double y3) {

        double x21 = x2 - x1;
        double y21 = y2 - y1;
        double x23 = x2 - x3;
        double y23 = y2 - y3;

        double l21 = Math.sqrt(x21 * x21 + y21 * y21);
        double l23 = Math.sqrt(x23 * x23 + y23 * y23);

        if (l21 == 0.0 && l23 == 0.0) {
            return;
        }

        if (l21 == 0.0) {
            addDashLine(x2, y2, x3, y3);
            return;
        }

        if (l23 == 0.0) {
            addDashLine(x1, y1, x2, y2);
            return;
        }

        double ax = x1 + x3 - x2 - x2;
        double ay = y1 + y3 - y2 - y2;
        double bx = x2 - x1;
        double by = y2 - y1;
        double cx = x1;
        double cy = y1;

        double px1, py1, dx1, dy1;
        px1 = py1 = dx1 = dy1 = 0.0;
        double prev = 0.0;

        dasher.init(new DashIterator.Quad(x1, y1, x2, y2, x3, y3));

        while (!dasher.eof()) {
            double t = dasher.getValue();
            double dx = t * ax + bx;
            double dy = t * ay + by;
            scx = t * (dx + bx) + cx; // t^2 * ax + 2.0 * t * bx + cx
            scy = t * (dy + by) + cy; // t^2 * ay + 2.0 * t * by + cy
            if (dasher.isOpen()) {
                px1 = scx;
                py1 = scy;
                dx1 = dx;
                dy1 = dy;
                double w = w2 / Math.sqrt(dx1 * dx1 + dy1 * dy1);
                double mx1 = -dy1 * w;
                double my1 = dx1 * w;
                double lx1 = px1 + mx1;
                double ly1 = py1 + my1;
                double rx1 = px1 - mx1;
                double ry1 = py1 - my1;
                if (isMove) {
                    isMove = false;
                    smx = px1;
                    smy = py1;
                    rp.clean();
                    lp.moveTo(lx1, ly1);
                    rp.moveTo(rx1, ry1);
                } else {
                    addJoin(lp, x1, y1, lx1, ly1, true);
                    addJoin(rp, x1, y1, rx1, ry1, false);
                }
            } else if (dasher.isContinue()) {
                double px3 = scx;
                double py3 = scy;
                double sx = x2 - x23 * prev;
                double sy = y2 - y23 * prev;
                double t2 = (t - prev) / (1 - prev);
                double px2 = px1 + (sx - px1) * t2;
                double py2 = py1 + (sy - py1) * t2;

                addQuad(px1, py1, px2, py2, px3, py3);
                if (dasher.isClosed()) {
                    addCap(lp, px3, py3, rp.xLast, rp.yLast);
                    lp.combine(rp);
                    if (isFirst) {
                        isFirst = false;
                        fmx = smx;
                        fmy = smy;
                        sp = lp;
                        lp = new BufferedPath();
                    } else {
                        addCap(lp, smx, smy, lp.xMove, lp.yMove);
                        lp.closePath();
                    }
                    isMove = true;
                }
            }

            prev = t;
            dasher.next();
        }
    }

    /**
     * Adds dashed cubic segment to the work path.
     * 
     * @param x1
     *            the x coordinate of the first control point.
     * @param y1
     *            the y coordinate of the first control point.
     * @param x2
     *            the x coordinate of the second control point.
     * @param y2
     *            the y coordinate of the second control point.
     * @param x3
     *            the x coordinate of the third control point.
     * @param y3
     *            the y coordinate of the third control point.
     * @param x4
     *            the x coordinate of the fours control point.
     * @param y4
     *            the y coordinate of the fours control point.
     */
    void addDashCubic(double x1, double y1, double x2, double y2, double x3, double y3, double x4,
            double y4) {

        double x12 = x1 - x2;
        double y12 = y1 - y2;
        double x23 = x2 - x3;
        double y23 = y2 - y3;
        double x34 = x3 - x4;
        double y34 = y3 - y4;

        double l12 = Math.sqrt(x12 * x12 + y12 * y12);
        double l23 = Math.sqrt(x23 * x23 + y23 * y23);
        double l34 = Math.sqrt(x34 * x34 + y34 * y34);

        // All edges are zero
        if (l12 == 0.0 && l23 == 0.0 && l34 == 0.0) {
            // NOTHING
            return;
        }

        // One zero edge
        if (l12 == 0.0 && l23 == 0.0) {
            addDashLine(x3, y3, x4, y4);
            return;
        }

        if (l23 == 0.0 && l34 == 0.0) {
            addDashLine(x1, y1, x2, y2);
            return;
        }

        if (l12 == 0.0 && l34 == 0.0) {
            addDashLine(x2, y2, x3, y3);
            return;
        }

        double ax = x4 - x1 + 3.0 * (x2 - x3);
        double ay = y4 - y1 + 3.0 * (y2 - y3);
        double bx = 3.0 * (x1 + x3 - x2 - x2);
        double by = 3.0 * (y1 + y3 - y2 - y2);
        double cx = 3.0 * (x2 - x1);
        double cy = 3.0 * (y2 - y1);
        double dx = x1;
        double dy = y1;

        double px1 = 0.0;
        double py1 = 0.0;
        double prev = 0.0;

        dasher.init(new DashIterator.Cubic(x1, y1, x2, y2, x3, y3, x4, y4));

        while (!dasher.eof()) {

            double t = dasher.getValue();
            scx = t * (t * (t * ax + bx) + cx) + dx;
            scy = t * (t * (t * ay + by) + cy) + dy;
            if (dasher.isOpen()) {
                px1 = scx;
                py1 = scy;
                double dx1 = t * (t * (ax + ax + ax) + bx + bx) + cx;
                double dy1 = t * (t * (ay + ay + ay) + by + by) + cy;
                double w = w2 / Math.sqrt(dx1 * dx1 + dy1 * dy1);
                double mx1 = -dy1 * w;
                double my1 = dx1 * w;
                double lx1 = px1 + mx1;
                double ly1 = py1 + my1;
                double rx1 = px1 - mx1;
                double ry1 = py1 - my1;
                if (isMove) {
                    isMove = false;
                    smx = px1;
                    smy = py1;
                    rp.clean();
                    lp.moveTo(lx1, ly1);
                    rp.moveTo(rx1, ry1);
                } else {
                    addJoin(lp, x1, y1, lx1, ly1, true);
                    addJoin(rp, x1, y1, rx1, ry1, false);
                }
            } else if (dasher.isContinue()) {
                double sx1 = x2 - x23 * prev;
                double sy1 = y2 - y23 * prev;
                double sx2 = x3 - x34 * prev;
                double sy2 = y3 - y34 * prev;
                double sx3 = sx1 + (sx2 - sx1) * prev;
                double sy3 = sy1 + (sy2 - sy1) * prev;
                double t2 = (t - prev) / (1 - prev);
                double sx4 = sx3 + (sx2 - sx3) * t2;
                double sy4 = sy3 + (sy2 - sy3) * t2;

                double px4 = scx;
                double py4 = scy;
                double px2 = px1 + (sx3 - px1) * t2;
                double py2 = py1 + (sy3 - py1) * t2;
                double px3 = px2 + (sx4 - px2) * t2;
                double py3 = py2 + (sy4 - py2) * t2;

                addCubic(px1, py1, px2, py2, px3, py3, px4, py4);
                if (dasher.isClosed()) {
                    addCap(lp, px4, py4, rp.xLast, rp.yLast);
                    lp.combine(rp);
                    if (isFirst) {
                        isFirst = false;
                        fmx = smx;
                        fmy = smy;
                        sp = lp;
                        lp = new BufferedPath();
                    } else {
                        addCap(lp, smx, smy, lp.xMove, lp.yMove);
                        lp.closePath();
                    }
                    isMove = true;
                }
            }

            prev = t;
            dasher.next();
        }
    }

    /**
     * Dasher class provides dashing for particular dash style.
     */
    class Dasher {

        /**
         * The pos.
         */
        double pos;

        /**
         * The first.
         */
        boolean close, visible, first;

        /**
         * The dash.
         */
        float dash[];

        /**
         * The phase.
         */
        float phase;

        /**
         * The index.
         */
        int index;

        /**
         * The iter.
         */
        DashIterator iter;

        /**
         * Instantiates a new dasher.
         * 
         * @param dash
         *            the dash.
         * @param phase
         *            the phase.
         */
        Dasher(float dash[], float phase) {
            this.dash = dash;
            this.phase = phase;
            index = 0;
            pos = phase;
            visible = true;
            while (pos >= dash[index]) {
                visible = !visible;
                pos -= dash[index];
                index = (index + 1) % dash.length;
            }
            pos = -pos;
            first = visible;
        }

        /**
         * Inits the.
         * 
         * @param iter
         *            the iter.
         */
        void init(DashIterator iter) {
            this.iter = iter;
            close = true;
        }

        /**
         * Checks if is open.
         * 
         * @return true, if is open.
         */
        boolean isOpen() {
            return visible && pos < iter.length;
        }

        /**
         * Checks if is continue.
         * 
         * @return true, if is continue.
         */
        boolean isContinue() {
            return !visible && pos > 0;
        }

        /**
         * Checks if is closed.
         * 
         * @return true, if is closed.
         */
        boolean isClosed() {
            return close;
        }

        /**
         * Checks if is connected.
         * 
         * @return true, if is connected.
         */
        boolean isConnected() {
            return first && !close;
        }

        /**
         * Eof.
         * 
         * @return true, if successful.
         */
        boolean eof() {
            if (!close) {
                pos -= iter.length;
                return true;
            }
            if (pos >= iter.length) {
                if (visible) {
                    pos -= iter.length;
                    return true;
                }
                close = pos == iter.length;
            }
            return false;
        }

        /**
         * Next.
         */
        void next() {
            if (close) {
                pos += dash[index];
                index = (index + 1) % dash.length;
            } else {
                // Go back
                index = (index + dash.length - 1) % dash.length;
                pos -= dash[index];
            }
            visible = !visible;
        }

        /**
         * Gets the value.
         * 
         * @return the value.
         */
        double getValue() {
            double t = iter.getNext(pos);
            return t < 0 ? 0 : (t > 1 ? 1 : t);
        }

    }

    /**
     * DashIterator class provides dashing for particular segment type.
     */
    static abstract class DashIterator {

        /**
         * The Constant FLATNESS.
         */
        static final double FLATNESS = 1.0;

        /**
         * The Class Line.
         */
        static class Line extends DashIterator {

            /**
             * Instantiates a new line.
             * 
             * @param len
             *            the len.
             */
            Line(double len) {
                length = len;
            }

            @Override
            double getNext(double dashPos) {
                return dashPos / length;
            }

        }

        /**
         * The Class Quad.
         */
        static class Quad extends DashIterator {

            /**
             * The val size.
             */
            int valSize;

            /**
             * The val pos.
             */
            int valPos;

            /**
             * The cur len.
             */
            double curLen;

            /**
             * The prev len.
             */
            double prevLen;

            /**
             * The last len.
             */
            double lastLen;

            /**
             * The values.
             */
            double[] values;

            /**
             * The step.
             */
            double step;

            /**
             * Instantiates a new quad.
             * 
             * @param x1
             *            the x1.
             * @param y1
             *            the y1.
             * @param x2
             *            the x2.
             * @param y2
             *            the y2.
             * @param x3
             *            the x3.
             * @param y3
             *            the y3.
             */
            Quad(double x1, double y1, double x2, double y2, double x3, double y3) {

                double nx = x1 + x3 - x2 - x2;
                double ny = y1 + y3 - y2 - y2;

                int n = (int)(1 + Math.sqrt(0.75 * (Math.abs(nx) + Math.abs(ny)) * FLATNESS));
                step = 1.0 / n;

                double ax = x1 + x3 - x2 - x2;
                double ay = y1 + y3 - y2 - y2;
                double bx = 2.0 * (x2 - x1);
                double by = 2.0 * (y2 - y1);

                double dx1 = step * (step * ax + bx);
                double dy1 = step * (step * ay + by);
                double dx2 = step * (step * ax * 2.0);
                double dy2 = step * (step * ay * 2.0);
                double vx = x1;
                double vy = y1;

                valSize = n;
                values = new double[valSize];
                double pvx = vx;
                double pvy = vy;
                length = 0.0;
                for (int i = 0; i < n; i++) {
                    vx += dx1;
                    vy += dy1;
                    dx1 += dx2;
                    dy1 += dy2;
                    double lx = vx - pvx;
                    double ly = vy - pvy;
                    values[i] = Math.sqrt(lx * lx + ly * ly);
                    length += values[i];
                    pvx = vx;
                    pvy = vy;
                }

                valPos = 0;
                curLen = 0.0;
                prevLen = 0.0;
            }

            @Override
            double getNext(double dashPos) {
                double t = 2.0;
                while (curLen <= dashPos && valPos < valSize) {
                    prevLen = curLen;
                    curLen += lastLen = values[valPos++];
                }
                if (curLen > dashPos) {
                    t = (valPos - 1 + (dashPos - prevLen) / lastLen) * step;
                }
                return t;
            }

        }

        /**
         * The Class Cubic.
         */
        static class Cubic extends DashIterator {

            /**
             * The val size.
             */
            int valSize;

            /**
             * The val pos.
             */
            int valPos;

            /**
             * The cur len.
             */
            double curLen;

            /**
             * The prev len.
             */
            double prevLen;

            /**
             * The last len.
             */
            double lastLen;

            /**
             * The values.
             */
            double[] values;

            /**
             * The step.
             */
            double step;

            /**
             * Instantiates a new cubic.
             * 
             * @param x1
             *            the x1.
             * @param y1
             *            the y1.
             * @param x2
             *            the x2.
             * @param y2
             *            the y2.
             * @param x3
             *            the x3.
             * @param y3
             *            the y3.
             * @param x4
             *            the x4.
             * @param y4
             *            the y4.
             */
            Cubic(double x1, double y1, double x2, double y2, double x3, double y3, double x4,
                    double y4) {

                double nx1 = x1 + x3 - x2 - x2;
                double ny1 = y1 + y3 - y2 - y2;
                double nx2 = x2 + x4 - x3 - x3;
                double ny2 = y2 + y4 - y3 - y3;

                double max = Math.max(Math.abs(nx1) + Math.abs(ny1), Math.abs(nx2) + Math.abs(ny2));
                int n = (int)(1 + Math.sqrt(0.75 * max) * FLATNESS);
                step = 1.0 / n;

                double ax = x4 - x1 + 3.0 * (x2 - x3);
                double ay = y4 - y1 + 3.0 * (y2 - y3);
                double bx = 3.0 * (x1 + x3 - x2 - x2);
                double by = 3.0 * (y1 + y3 - y2 - y2);
                double cx = 3.0 * (x2 - x1);
                double cy = 3.0 * (y2 - y1);

                double dx1 = step * (step * (step * ax + bx) + cx);
                double dy1 = step * (step * (step * ay + by) + cy);
                double dx2 = step * (step * (step * ax * 6.0 + bx * 2.0));
                double dy2 = step * (step * (step * ay * 6.0 + by * 2.0));
                double dx3 = step * (step * (step * ax * 6.0));
                double dy3 = step * (step * (step * ay * 6.0));
                double vx = x1;
                double vy = y1;

                valSize = n;
                values = new double[valSize];
                double pvx = vx;
                double pvy = vy;
                length = 0.0;
                for (int i = 0; i < n; i++) {
                    vx += dx1;
                    vy += dy1;
                    dx1 += dx2;
                    dy1 += dy2;
                    dx2 += dx3;
                    dy2 += dy3;
                    double lx = vx - pvx;
                    double ly = vy - pvy;
                    values[i] = Math.sqrt(lx * lx + ly * ly);
                    length += values[i];
                    pvx = vx;
                    pvy = vy;
                }

                valPos = 0;
                curLen = 0.0;
                prevLen = 0.0;
            }

            @Override
            double getNext(double dashPos) {
                double t = 2.0;
                while (curLen <= dashPos && valPos < valSize) {
                    prevLen = curLen;
                    curLen += lastLen = values[valPos++];
                }
                if (curLen > dashPos) {
                    t = (valPos - 1 + (dashPos - prevLen) / lastLen) * step;
                }
                return t;
            }

        }

        /**
         * The length.
         */
        double length;

        /**
         * Gets the next.
         * 
         * @param dashPos
         *            the dash pos.
         * @return the next.
         */
        abstract double getNext(double dashPos);

    }

    /**
     * BufferedPath class provides work path storing and processing.
     */
    static class BufferedPath {

        /**
         * The Constant bufCapacity.
         */
        private static final int bufCapacity = 10;

        /**
         * The point shift.
         */
        static int pointShift[] = {
                2, // MOVETO
                2, // LINETO
                4, // QUADTO
                6, // CUBICTO
                0
        }; // CLOSE

        /**
         * The types.
         */
        byte[] types;

        /**
         * The points.
         */
        float[] points;

        /**
         * The type size.
         */
        int typeSize;

        /**
         * The point size.
         */
        int pointSize;

        /**
         * The x last.
         */
        float xLast;

        /**
         * The y last.
         */
        float yLast;

        /**
         * The x move.
         */
        float xMove;

        /**
         * The y move.
         */
        float yMove;

        /**
         * Instantiates a new buffered path.
         */
        public BufferedPath() {
            types = new byte[bufCapacity];
            points = new float[bufCapacity * 2];
        }

        /**
         * Check buf.
         * 
         * @param typeCount
         *            the type count.
         * @param pointCount
         *            the point count.
         */
        void checkBuf(int typeCount, int pointCount) {
            if (typeSize + typeCount > types.length) {
                byte tmp[] = new byte[typeSize + Math.max(bufCapacity, typeCount)];
                System.arraycopy(types, 0, tmp, 0, typeSize);
                types = tmp;
            }
            if (pointSize + pointCount > points.length) {
                float tmp[] = new float[pointSize + Math.max(bufCapacity * 2, pointCount)];
                System.arraycopy(points, 0, tmp, 0, pointSize);
                points = tmp;
            }
        }

        /**
         * Checks if is empty.
         * 
         * @return true, if is empty.
         */
        boolean isEmpty() {
            return typeSize == 0;
        }

        /**
         * Clean.
         */
        void clean() {
            typeSize = 0;
            pointSize = 0;
        }

        /**
         * Move to.
         * 
         * @param x
         *            the x.
         * @param y
         *            the y.
         */
        void moveTo(double x, double y) {
            checkBuf(1, 2);
            types[typeSize++] = PathIterator.SEG_MOVETO;
            points[pointSize++] = xMove = (float)x;
            points[pointSize++] = yMove = (float)y;
        }

        /**
         * Line to.
         * 
         * @param x
         *            the x.
         * @param y
         *            the y.
         */
        void lineTo(double x, double y) {
            checkBuf(1, 2);
            types[typeSize++] = PathIterator.SEG_LINETO;
            points[pointSize++] = xLast = (float)x;
            points[pointSize++] = yLast = (float)y;
        }

        /**
         * Quad to.
         * 
         * @param x1
         *            the x1.
         * @param y1
         *            the y1.
         * @param x2
         *            the x2.
         * @param y2
         *            the y2.
         */
        void quadTo(double x1, double y1, double x2, double y2) {
            checkBuf(1, 4);
            types[typeSize++] = PathIterator.SEG_QUADTO;
            points[pointSize++] = (float)x1;
            points[pointSize++] = (float)y1;
            points[pointSize++] = xLast = (float)x2;
            points[pointSize++] = yLast = (float)y2;
        }

        /**
         * Cubic to.
         * 
         * @param x1
         *            the x1.
         * @param y1
         *            the y1.
         * @param x2
         *            the x2.
         * @param y2
         *            the y2.
         * @param x3
         *            the x3.
         * @param y3
         *            the y3.
         */
        void cubicTo(double x1, double y1, double x2, double y2, double x3, double y3) {
            checkBuf(1, 6);
            types[typeSize++] = PathIterator.SEG_CUBICTO;
            points[pointSize++] = (float)x1;
            points[pointSize++] = (float)y1;
            points[pointSize++] = (float)x2;
            points[pointSize++] = (float)y2;
            points[pointSize++] = xLast = (float)x3;
            points[pointSize++] = yLast = (float)y3;
        }

        /**
         * Close path.
         */
        void closePath() {
            checkBuf(1, 0);
            types[typeSize++] = PathIterator.SEG_CLOSE;
        }

        /**
         * Sets the last.
         * 
         * @param x
         *            the x.
         * @param y
         *            the y.
         */
        void setLast(double x, double y) {
            points[pointSize - 2] = xLast = (float)x;
            points[pointSize - 1] = yLast = (float)y;
        }

        /**
         * Append.
         * 
         * @param p
         *            the p.
         */
        void append(BufferedPath p) {
            checkBuf(p.typeSize, p.pointSize);
            System.arraycopy(p.points, 0, points, pointSize, p.pointSize);
            System.arraycopy(p.types, 0, types, typeSize, p.typeSize);
            pointSize += p.pointSize;
            typeSize += p.typeSize;
            xLast = points[pointSize - 2];
            yLast = points[pointSize - 1];
        }

        /**
         * Append reverse.
         * 
         * @param p
         *            the p.
         */
        void appendReverse(BufferedPath p) {
            checkBuf(p.typeSize, p.pointSize);
            // Skip last point, beacause it's the first point of the second path
            for (int i = p.pointSize - 2; i >= 0; i -= 2) {
                points[pointSize++] = p.points[i + 0];
                points[pointSize++] = p.points[i + 1];
            }
            // Skip first type, beacuse it's always MOVETO
            int closeIndex = 0;
            for (int i = p.typeSize - 1; i >= 0; i--) {
                byte type = p.types[i];
                if (type == PathIterator.SEG_MOVETO) {
                    types[closeIndex] = PathIterator.SEG_MOVETO;
                    types[typeSize++] = PathIterator.SEG_CLOSE;
                } else {
                    if (type == PathIterator.SEG_CLOSE) {
                        closeIndex = typeSize;
                    }
                    types[typeSize++] = type;
                }
            }
            xLast = points[pointSize - 2];
            yLast = points[pointSize - 1];
        }

        /**
         * Join.
         * 
         * @param p
         *            the p.
         */
        void join(BufferedPath p) {
            // Skip MOVETO
            checkBuf(p.typeSize - 1, p.pointSize - 2);
            System.arraycopy(p.points, 2, points, pointSize, p.pointSize - 2);
            System.arraycopy(p.types, 1, types, typeSize, p.typeSize - 1);
            pointSize += p.pointSize - 2;
            typeSize += p.typeSize - 1;
            xLast = points[pointSize - 2];
            yLast = points[pointSize - 1];
        }

        /**
         * Combine.
         * 
         * @param p
         *            the p.
         */
        void combine(BufferedPath p) {
            checkBuf(p.typeSize - 1, p.pointSize - 2);
            // Skip last point, beacause it's the first point of the second path
            for (int i = p.pointSize - 4; i >= 0; i -= 2) {
                points[pointSize++] = p.points[i + 0];
                points[pointSize++] = p.points[i + 1];
            }
            // Skip first type, beacuse it's always MOVETO
            for (int i = p.typeSize - 1; i >= 1; i--) {
                types[typeSize++] = p.types[i];
            }
            xLast = points[pointSize - 2];
            yLast = points[pointSize - 1];
        }

        /**
         * Creates the general path.
         * 
         * @return the general path.
         */
        GeneralPath createGeneralPath() {
            GeneralPath p = new GeneralPath();
            int j = 0;
            for (int i = 0; i < typeSize; i++) {
                int type = types[i];
                switch (type) {
                    case PathIterator.SEG_MOVETO:
                        p.moveTo(points[j], points[j + 1]);
                        break;
                    case PathIterator.SEG_LINETO:
                        p.lineTo(points[j], points[j + 1]);
                        break;
                    case PathIterator.SEG_QUADTO:
                        p.quadTo(points[j], points[j + 1], points[j + 2], points[j + 3]);
                        break;
                    case PathIterator.SEG_CUBICTO:
                        p.curveTo(points[j], points[j + 1], points[j + 2], points[j + 3],
                                points[j + 4], points[j + 5]);
                        break;
                    case PathIterator.SEG_CLOSE:
                        p.closePath();
                        break;
                }
                j += pointShift[type];
            }
            return p;
        }

    }

}
