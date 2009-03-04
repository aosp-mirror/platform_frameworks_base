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
 * The Class FlatteningPathIterator takes a PathIterator for traversing a curved
 * shape and flattens it by estimating the curve as a series of line segments.
 * The flattening factor indicates how far the estimating line segments are
 * allowed to be from the actual curve: the FlatteningPathIterator will keep
 * dividing each curved segment into smaller and smaller flat segments until
 * either the segments are within the flattening factor of the curve or until
 * the buffer limit is reached.
 * 
 * @since Android 1.0
 */
public class FlatteningPathIterator implements PathIterator {

    /**
     * The default points buffer size.
     */
    private static final int BUFFER_SIZE = 16;

    /**
     * The default curve subdivision limit.
     */
    private static final int BUFFER_LIMIT = 16;

    /**
     * The points buffer capacity.
     */
    private static final int BUFFER_CAPACITY = 16;

    /**
     * The type of current segment to be flat.
     */
    int bufType;

    /**
     * The curve subdivision limit.
     */
    int bufLimit;

    /**
     * The current points buffer size.
     */
    int bufSize;

    /**
     * The inner cursor position in points buffer.
     */
    int bufIndex;

    /**
     * The current subdivision count.
     */
    int bufSubdiv;

    /**
     * The points buffer.
     */
    double buf[];

    /**
     * The indicator of empty points buffer.
     */
    boolean bufEmpty = true;

    /**
     * The source PathIterator.
     */
    PathIterator p;

    /**
     * The flatness of new path.
     */
    double flatness;

    /**
     * The square of flatness.
     */
    double flatness2;

    /**
     * The x coordinate of previous path segment.
     */
    double px;

    /**
     * The y coordinate of previous path segment.
     */
    double py;

    /**
     * The temporary buffer for getting points from PathIterator.
     */
    double coords[] = new double[6];

    /**
     * Instantiates a new flattening path iterator given the path iterator for a
     * (possibly) curved path and a flattening factor which indicates how close
     * together the points on the curve should be chosen. The buffer limit
     * defaults to 16 which means that each curve will be divided into no more
     * than 16 segments regardless of the flattening factor.
     * 
     * @param path
     *            the path iterator of the original curve.
     * @param flatness
     *            the flattening factor that indicates how far the flat path is
     *            allowed to be from the actual curve in order to decide when to
     *            stop dividing the path into smaller and smaller segments.
     * @throws IllegalArgumentException
     *             if the flatness is less than zero.
     * @throws NullPointerException
     *             if the path is null.
     */
    public FlatteningPathIterator(PathIterator path, double flatness) {
        this(path, flatness, BUFFER_LIMIT);
    }

    /**
     * Instantiates a new flattening path iterator given the path iterator for a
     * (possibly) curved path and a flattening factor and a buffer limit. The
     * FlatteningPathIterator will keep dividing each curved segment into
     * smaller and smaller flat segments until either the segments are within
     * the flattening factor of the curve or until the buffer limit is reached.
     * 
     * @param path
     *            the path iterator of the original curve.
     * @param flatness
     *            the flattening factor that indicates how far the flat path is
     *            allowed to be from the actual curve in order to decide when to
     *            stop dividing the path into smaller and smaller segments.
     * @param limit
     *            the maximum number of flat segments to divide each curve into.
     * @throws IllegalArgumentException
     *             if the flatness or limit is less than zero.
     * @throws NullPointerException
     *             if the path is null.
     */
    public FlatteningPathIterator(PathIterator path, double flatness, int limit) {
        if (flatness < 0.0) {
            // awt.206=Flatness is less then zero
            throw new IllegalArgumentException(Messages.getString("awt.206")); //$NON-NLS-1$
        }
        if (limit < 0) {
            // awt.207=Limit is less then zero
            throw new IllegalArgumentException(Messages.getString("awt.207")); //$NON-NLS-1$
        }
        if (path == null) {
            // awt.208=Path is null
            throw new NullPointerException(Messages.getString("awt.208")); //$NON-NLS-1$
        }
        this.p = path;
        this.flatness = flatness;
        this.flatness2 = flatness * flatness;
        this.bufLimit = limit;
        this.bufSize = Math.min(bufLimit, BUFFER_SIZE);
        this.buf = new double[bufSize];
        this.bufIndex = bufSize;
    }

    /**
     * Gets the flattening factor.
     * 
     * @return the flattening factor.
     */
    public double getFlatness() {
        return flatness;
    }

    /**
     * Gets the maximum number of subdivisions per curved segment.
     * 
     * @return the maximum number of subdivisions per curved segment.
     */
    public int getRecursionLimit() {
        return bufLimit;
    }

    public int getWindingRule() {
        return p.getWindingRule();
    }

    public boolean isDone() {
        return bufEmpty && p.isDone();
    }

    /**
     * Calculates flat path points for current segment of the source shape. Line
     * segment is flat by itself. Flatness of quad and cubic curves evaluated by
     * getFlatnessSq() method. Curves subdivided until current flatness is
     * bigger than user defined and subdivision limit isn't exhausted. Single
     * source segment translated to series of buffer points. The less flatness
     * the bigger series. Every currentSegment() call extract one point from the
     * buffer. When series completed evaluate() takes next source shape segment.
     */
    void evaluate() {
        if (bufEmpty) {
            bufType = p.currentSegment(coords);
        }

        switch (bufType) {
            case SEG_MOVETO:
            case SEG_LINETO:
                px = coords[0];
                py = coords[1];
                break;
            case SEG_QUADTO:
                if (bufEmpty) {
                    bufIndex -= 6;
                    buf[bufIndex + 0] = px;
                    buf[bufIndex + 1] = py;
                    System.arraycopy(coords, 0, buf, bufIndex + 2, 4);
                    bufSubdiv = 0;
                }

                while (bufSubdiv < bufLimit) {
                    if (QuadCurve2D.getFlatnessSq(buf, bufIndex) < flatness2) {
                        break;
                    }

                    // Realloc buffer
                    if (bufIndex <= 4) {
                        double tmp[] = new double[bufSize + BUFFER_CAPACITY];
                        System.arraycopy(buf, bufIndex, tmp, bufIndex + BUFFER_CAPACITY, bufSize
                                - bufIndex);
                        buf = tmp;
                        bufSize += BUFFER_CAPACITY;
                        bufIndex += BUFFER_CAPACITY;
                    }

                    QuadCurve2D.subdivide(buf, bufIndex, buf, bufIndex - 4, buf, bufIndex);

                    bufIndex -= 4;
                    bufSubdiv++;
                }

                bufIndex += 4;
                px = buf[bufIndex];
                py = buf[bufIndex + 1];

                bufEmpty = (bufIndex == bufSize - 2);
                if (bufEmpty) {
                    bufIndex = bufSize;
                    bufType = SEG_LINETO;
                } else {
                    bufSubdiv--;
                }
                break;
            case SEG_CUBICTO:
                if (bufEmpty) {
                    bufIndex -= 8;
                    buf[bufIndex + 0] = px;
                    buf[bufIndex + 1] = py;
                    System.arraycopy(coords, 0, buf, bufIndex + 2, 6);
                    bufSubdiv = 0;
                }

                while (bufSubdiv < bufLimit) {
                    if (CubicCurve2D.getFlatnessSq(buf, bufIndex) < flatness2) {
                        break;
                    }

                    // Realloc buffer
                    if (bufIndex <= 6) {
                        double tmp[] = new double[bufSize + BUFFER_CAPACITY];
                        System.arraycopy(buf, bufIndex, tmp, bufIndex + BUFFER_CAPACITY, bufSize
                                - bufIndex);
                        buf = tmp;
                        bufSize += BUFFER_CAPACITY;
                        bufIndex += BUFFER_CAPACITY;
                    }

                    CubicCurve2D.subdivide(buf, bufIndex, buf, bufIndex - 6, buf, bufIndex);

                    bufIndex -= 6;
                    bufSubdiv++;
                }

                bufIndex += 6;
                px = buf[bufIndex];
                py = buf[bufIndex + 1];

                bufEmpty = (bufIndex == bufSize - 2);
                if (bufEmpty) {
                    bufIndex = bufSize;
                    bufType = SEG_LINETO;
                } else {
                    bufSubdiv--;
                }
                break;
        }

    }

    public void next() {
        if (bufEmpty) {
            p.next();
        }
    }

    public int currentSegment(float[] coords) {
        if (isDone()) {
            // awt.4B=Iterator out of bounds
            throw new NoSuchElementException(Messages.getString("awt.4Bx")); //$NON-NLS-1$
        }
        evaluate();
        int type = bufType;
        if (type != SEG_CLOSE) {
            coords[0] = (float)px;
            coords[1] = (float)py;
            if (type != SEG_MOVETO) {
                type = SEG_LINETO;
            }
        }
        return type;
    }

    public int currentSegment(double[] coords) {
        if (isDone()) {
            // awt.4B=Iterator out of bounds
            throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
        }
        evaluate();
        int type = bufType;
        if (type != SEG_CLOSE) {
            coords[0] = px;
            coords[1] = py;
            if (type != SEG_MOVETO) {
                type = SEG_LINETO;
            }
        }
        return type;
    }
}
