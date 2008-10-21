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
package org.apache.harmony.awt.gl;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import org.apache.harmony.awt.internal.nls.Messages;

public class MultiRectArea implements Shape {

    /**
     * If CHECK is true validation check active
     */
    private static final boolean CHECK = false;

    boolean sorted = true;
    
    /**
     * Rectangle buffer
     */
    public int[] rect;
    
    /**
     * Bounding box
     */
    Rectangle bounds;
    
    /**
     * Result rectangle array
     */
    Rectangle[] rectangles;

    /**
     * LineCash provides creating MultiRectArea line by line. Used in JavaShapeRasterizer.
     */
    public static class LineCash extends MultiRectArea {

        int lineY;
        int bottomCount;
        int[] bottom;

        public LineCash(int size) {
            super();
            bottom = new int[size];
            bottomCount = 0;
        }

        public void setLine(int y) {
            lineY = y;
        }

        public void skipLine() {
            lineY++;
            bottomCount = 0;
        }

        public void addLine(int[] points, int pointCount) {
            int bottomIndex = 0;
            int pointIndex = 0;
            int rectIndex = 0;
            int pointX1 = 0;
            int pointX2 = 0;
            int bottomX1 = 0;
            int bottomX2 = 0;
            boolean appendRect = false;
            boolean deleteRect = false;
            int lastCount = bottomCount;

            while (bottomIndex < lastCount || pointIndex < pointCount) {

                appendRect = false;
                deleteRect = false;

                if (bottomIndex < lastCount) {
                    rectIndex = bottom[bottomIndex];
                    bottomX1 = rect[rectIndex];
                    bottomX2 = rect[rectIndex + 2];
                } else {
                    appendRect = true;
                }

                if (pointIndex < pointCount) {
                    pointX1 = points[pointIndex];
                    pointX2 = points[pointIndex + 1];
                } else {
                    deleteRect = true;
                }

                if (!deleteRect && !appendRect) {
                    if (pointX1 == bottomX1 && pointX2 == bottomX2) {
                        rect[rectIndex + 3] = rect[rectIndex + 3] + 1;
                        pointIndex += 2;
                        bottomIndex++;
                        continue;
                    }
                    deleteRect = pointX2 >= bottomX1;
                    appendRect = pointX1 <= bottomX2;
                }

                if (deleteRect) {
                    if (bottomIndex < bottomCount - 1) {
                        System.arraycopy(bottom, bottomIndex + 1, bottom, bottomIndex, bottomCount - bottomIndex - 1);
                        rectIndex -= 4;
                    }
                    bottomCount--;
                    lastCount--;
                }

                if (appendRect) {
                    int i = rect[0];
                    bottom[bottomCount++] = i;
                    rect = MultiRectAreaOp.checkBufSize(rect, 4);
                    rect[i++] = pointX1;
                    rect[i++] = lineY;
                    rect[i++] = pointX2;
                    rect[i++] = lineY;
                    pointIndex += 2;
                }
            }
            lineY++;

            invalidate();
        }

    }

    /**
     * RectCash provides simple creating MultiRectArea
     */
    public static class RectCash extends MultiRectArea {

        int[] cash;

        public RectCash() {
            super();
            cash = new int[MultiRectAreaOp.RECT_CAPACITY];
            cash[0] = 1;
        }

        public void addRectCashed(int x1, int y1, int x2, int y2) {
            addRect(x1, y1, x2, y2);
            invalidate();
/*
            // Exclude from cash unnecessary rectangles
            int i = 1;
            while(i < cash[0]) {
                if (rect[cash[i] + 3] >= y1 - 1) {
                    if (i > 1) {
                        System.arraycopy(cash, i, cash, 1, cash[0] - i);
                    }
                    break;
                }
                i++;
            }
            cash[0] -= i - 1;

            // Find in cash rectangle to concatinate
            i = 1;
            while(i < cash[0]) {
                int index = cash[i];
                if (rect[index + 3] != y1 - 1) {
                    break;
                }
                if (rect[index] == x1 && rect[index + 2] == x2) {
                    rect[index + 3] += y2 - y1 + 1;

                    int pos = i + 1;
                    while(pos < cash[0]) {
                        if (rect[index + 3] <= rect[cash[i] + 3]) {
                            System.arraycopy(cash, i + 1, cash, i, pos - i);
                            break;
                        }
                        i++;
                    }
                    cash[pos - 1] = index;

                    invalidate();
                    return;
                }
                i++;
            }

            // Add rectangle to buffer
            int index = rect[0];
            rect = MultiRectAreaOp.checkBufSize(rect, 4);
            rect[index + 0] = x1;
            rect[index + 1] = y1;
            rect[index + 2] = x2;
            rect[index + 3] = y2;

            // Add rectangle to cash
            int length = cash[0];
            cash = MultiRectAreaOp.checkBufSize(cash, 1);
            while(i < length) {
                if (y2 <= rect[cash[i] + 3]) {
                    System.arraycopy(cash, i, cash, i + 1, length - i);
                    break;
                }
                i++;
            }
            cash[i] = index;
            invalidate();
*/
        }

        public void addRectCashed(int[] rect, int rectOff, int rectLength) {
            for(int i = rectOff; i < rectOff + rectLength;) {
                addRect(rect[i++], rect[i++], rect[i++], rect[i++]);
//              addRectCashed(rect[i++], rect[i++], rect[i++], rect[i++]);
            }
        }

    }

    /**
     * MultiRectArea path iterator
     */
    class Iterator implements PathIterator {

        int type;
        int index;
        int pos;

        int[] rect;
        AffineTransform t;

        Iterator(MultiRectArea mra, AffineTransform t) {
            rect = new int[mra.rect[0] - 1];
            System.arraycopy(mra.rect, 1, rect, 0, rect.length);
            this.t = t;
        }

        public int getWindingRule() {
            return WIND_NON_ZERO;
        }

        public boolean isDone() {
            return pos >= rect.length;
        }

        public void next() {
            if (index == 4) {
                pos += 4;
            }
            index = (index + 1) % 5;
        }

        public int currentSegment(double[] coords) {
            if (isDone()) {
                // awt.4B=Iiterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type = 0;

            switch(index) {
            case 0 :
                type = SEG_MOVETO;
                coords[0] = rect[pos + 0];
                coords[1] = rect[pos + 1];
                break;
            case 1:
                type = SEG_LINETO;
                coords[0] = rect[pos + 2];
                coords[1] = rect[pos + 1];
                break;
            case 2:
                type = SEG_LINETO;
                coords[0] = rect[pos + 2];
                coords[1] = rect[pos + 3];
                break;
            case 3:
                type = SEG_LINETO;
                coords[0] = rect[pos + 0];
                coords[1] = rect[pos + 3];
                break;
            case 4:
                type = SEG_CLOSE;
                break;
            }

            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return type;
        }

        public int currentSegment(float[] coords) {
            if (isDone()) {
                // awt.4B=Iiterator out of bounds
                throw new NoSuchElementException(Messages.getString("awt.4B")); //$NON-NLS-1$
            }
            int type = 0;

            switch(index) {
            case 0 :
                type = SEG_MOVETO;
                coords[0] = rect[pos + 0];
                coords[1] = rect[pos + 1];
                break;
            case 1:
                type = SEG_LINETO;
                coords[0] = rect[pos + 2];
                coords[1] = rect[pos + 1];
                break;
            case 2:
                type = SEG_LINETO;
                coords[0] = rect[pos + 2];
                coords[1] = rect[pos + 3];
                break;
            case 3:
                type = SEG_LINETO;
                coords[0] = rect[pos + 0];
                coords[1] = rect[pos + 3];
                break;
            case 4:
                type = SEG_CLOSE;
                break;
            }

            if (t != null) {
                t.transform(coords, 0, coords, 0, 1);
            }
            return type;
        }

    }

    /**
     * Constructs a new empty MultiRectArea 
     */
    public MultiRectArea() {
        rect = MultiRectAreaOp.createBuf(0);
    }

    public MultiRectArea(boolean sorted) {
       this();
       this.sorted = sorted;
    }
    
    /**
     * Constructs a new MultiRectArea as a copy of another one 
     */
    public MultiRectArea(MultiRectArea mra) {
        if (mra == null) {
            rect = MultiRectAreaOp.createBuf(0);
        } else {
            rect = new int[mra.rect.length];
            System.arraycopy(mra.rect, 0, rect, 0, mra.rect.length);
            check(this, "MultiRectArea(MRA)"); //$NON-NLS-1$
        }
    }

    /**
     * Constructs a new MultiRectArea consists of single rectangle 
     */
    public MultiRectArea(Rectangle r) {
        rect = MultiRectAreaOp.createBuf(0);
        if (r != null && !r.isEmpty()) {
            rect[0] = 5;
            rect[1] = r.x;
            rect[2] = r.y;
            rect[3] = r.x + r.width - 1;
            rect[4] = r.y + r.height - 1;
        }
        check(this, "MultiRectArea(Rectangle)"); //$NON-NLS-1$
    }

    /**
     * Constructs a new MultiRectArea consists of single rectangle
     */
    public MultiRectArea(int x0, int y0, int x1, int y1) {
        rect = MultiRectAreaOp.createBuf(0);
        if (x1 >= x0 && y1 >= y0) {
            rect[0] = 5;
            rect[1] = x0;
            rect[2] = y0;
            rect[3] = x1;
            rect[4] = y1;
        }
        check(this, "MultiRectArea(Rectangle)"); //$NON-NLS-1$
    }

    /**
     * Constructs a new MultiRectArea and append rectangle from buffer
     */
    public MultiRectArea(Rectangle[] buf) {
        this();
        for (Rectangle element : buf) {
            add(element);
        }
    }

    /**
     * Constructs a new MultiRectArea and append rectangle from array
     */
    public MultiRectArea(ArrayList<Rectangle> buf) {
        this();
        for(int i = 0; i < buf.size(); i++) {
            add(buf.get(i));
        }
    }

    /**
     * Sort rectangle buffer
     */
    void resort() {
        int[] buf = new int[4];
        for(int i = 1; i < rect[0]; i += 4) {
            int k = i;
            int x1 = rect[k];
            int y1 = rect[k + 1];
            for(int j = i + 4; j < rect[0]; j += 4) {
                int x2 = rect[j];
                int y2 = rect[j + 1];
                if (y1 > y2 || (y1 == y2 && x1 > x2)) {
                    x1 = x2;
                    y1 = y2;
                    k = j;
                }
            }
            if (k != i) {
                System.arraycopy(rect, i, buf, 0, 4);
                System.arraycopy(rect, k, rect, i, 4);
                System.arraycopy(buf, 0, rect, k, 4);
            }
        }
        invalidate();
    }

    /**
     * Tests equals with another object
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof MultiRectArea) {
            MultiRectArea mra = (MultiRectArea) obj;
            for(int i = 0; i < rect[0]; i++) {
                if (rect[i] != mra.rect[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Checks validation of MultiRectArea object
     */
    static MultiRectArea check(MultiRectArea mra, String msg) {
        if (CHECK && mra != null) {
            if (MultiRectArea.checkValidation(mra.getRectangles(), mra.sorted) != -1) {
                // awt.4C=Invalid MultiRectArea in method {0}
                new RuntimeException(Messages.getString("awt.4C", msg)); //$NON-NLS-1$
            }
        }
        return mra;
    }

    /**
     * Checks validation of MultiRectArea object
     */
    public static int checkValidation(Rectangle[] r, boolean sorted) {

        // Check width and height
        for(int i = 0; i < r.length; i++) {
            if (r[i].width <= 0 || r[i].height <= 0) {
                return i;
            }
        }

        // Check order
        if (sorted) {
            for(int i = 1; i < r.length; i++) {
                if (r[i - 1].y > r[i].y) {
                    return i;
                }
                if (r[i - 1].y == r[i].y) {
                    if (r[i - 1].x > r[i].x) {
                        return i;
                    }
                }
            }
        }

        // Check override
        for(int i = 0; i < r.length; i++) {
            for(int j = i + 1; j < r.length; j++) {
                if (r[i].intersects(r[j])) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Assigns rectangle from another buffer
     */
    protected void setRect(int[] buf, boolean copy) {
        if (copy) {
            rect = new int[buf.length];
            System.arraycopy(buf, 0, rect, 0, buf.length);
        } else {
            rect = buf;
        }
        invalidate();
    }

    /**
     * Union with another MultiRectArea object
     */
    public void add(MultiRectArea mra) {
        setRect(union(this, mra).rect, false);
        invalidate();
    }

    /**
     * Intersect with another MultiRectArea object
     */
    public void intersect(MultiRectArea mra) {
        setRect(intersect(this, mra).rect, false);
        invalidate();
    }

    /**
     * Subtract another MultiRectArea object
     */
    public void substract(MultiRectArea mra) {
        setRect(subtract(this, mra).rect, false);
        invalidate();
    }

    /**
     * Union with Rectangle object
     */
    public void add(Rectangle rect) {
        setRect(union(this, new MultiRectArea(rect)).rect, false);
        invalidate();
    }

    /**
     * Intersect with Rectangle object
     */
    public void intersect(Rectangle rect) {
        setRect(intersect(this, new MultiRectArea(rect)).rect, false);
        invalidate();
    }

    /**
     * Subtract rectangle object
     */
    public void substract(Rectangle rect) {
        setRect(subtract(this, new MultiRectArea(rect)).rect, false);
    }

    /**
     * Union two MutliRectareArea objects
     */
    public static MultiRectArea intersect(MultiRectArea src1, MultiRectArea src2) {
        MultiRectArea res = check(MultiRectAreaOp.Intersection.getResult(src1, src2), "intersect(MRA,MRA)"); //$NON-NLS-1$
        return res;
    }

    /**
     * Intersect two MultiRectArea objects
     */
    public static MultiRectArea union(MultiRectArea src1, MultiRectArea src2) {
        MultiRectArea res = check(new MultiRectAreaOp.Union().getResult(src1, src2), "union(MRA,MRA)"); //$NON-NLS-1$
        return res;
    }

    /**
     * Subtract two MultiRectArea objects
     */
    public static MultiRectArea subtract(MultiRectArea src1, MultiRectArea src2) {
        MultiRectArea res = check(MultiRectAreaOp.Subtraction.getResult(src1, src2), "subtract(MRA,MRA)"); //$NON-NLS-1$
        return res;
    }

    /**
     * Print MultiRectArea object to output stream
     */
    public static void print(MultiRectArea mra, String msg) {
        if (mra == null) {
            System.out.println(msg + "=null"); //$NON-NLS-1$
        } else {
            Rectangle[] rects = mra.getRectangles();
            System.out.println(msg + "(" + rects.length + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Rectangle element : rects) {
                System.out.println(
                        element.x + "," + //$NON-NLS-1$
                        element.y + "," + //$NON-NLS-1$
                        (element.x + element.width - 1) + "," + //$NON-NLS-1$
                        (element.y + element.height - 1));
            }
        }
    }

    /**
     * Translate MultiRectArea object by (x, y)
     */
    public void translate(int x, int y) {
        for(int i = 1; i < rect[0];) {
            rect[i++] += x;
            rect[i++] += y;
            rect[i++] += x;
            rect[i++] += y;
        }

        if (bounds != null && !bounds.isEmpty()) {
            bounds.translate(x, y);
        }

        if (rectangles != null) {
            for (Rectangle element : rectangles) {
                element.translate(x, y);
            }
        }
    }

    /**
     * Add rectangle to the buffer without any checking
     */
    public void addRect(int x1, int y1, int x2, int y2) {
        int i = rect[0];
        rect = MultiRectAreaOp.checkBufSize(rect, 4);
        rect[i++] = x1;
        rect[i++] = y1;
        rect[i++] = x2;
        rect[i++] = y2;
    }

    /**
     * Tests is MultiRectArea empty 
     */
    public boolean isEmpty() {
        return rect[0] == 1;
    }

    void invalidate() {
        bounds = null;
        rectangles = null;
    }

    /**
     * Returns bounds of MultiRectArea object
     */
    public Rectangle getBounds() {
        if (bounds != null) {
            return bounds;
        }

        if (isEmpty()) {
            return bounds = new Rectangle();
        }

        int x1 = rect[1];
        int y1 = rect[2];
        int x2 = rect[3];
        int y2 = rect[4];
        
        for(int i = 5; i < rect[0]; i += 4) {
            int rx1 = rect[i + 0];
            int ry1 = rect[i + 1];
            int rx2 = rect[i + 2];
            int ry2 = rect[i + 3];
            if (rx1 < x1) {
                x1 = rx1;
            }
            if (rx2 > x2) {
                x2 = rx2;
            }
            if (ry1 < y1) {
                y1 = ry1;
            }
            if (ry2 > y2) {
                y2 = ry2;
            }
        }
        
        return bounds = new Rectangle(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
    }

    /**
     * Recturn rectangle count in the buffer
     */
    public int getRectCount() {
        return (rect[0] - 1) / 4;
    }

    /**
     * Returns Rectangle array 
     */
    public Rectangle[] getRectangles() {
        if (rectangles != null) {
            return rectangles;
        }

        rectangles = new Rectangle[(rect[0] - 1) / 4];
        int j = 0;
        for(int i = 1; i < rect[0]; i += 4) {
            rectangles[j++] = new Rectangle(
                    rect[i],
                    rect[i + 1],
                    rect[i + 2] - rect[i] + 1,
                    rect[i + 3] - rect[i + 1] + 1);
        }
        return rectangles;
    }

    /**
     * Returns Bounds2D
     */
    public Rectangle2D getBounds2D() {
        return getBounds();
    }

    /**
     * Tests does point lie inside MultiRectArea object
     */
    public boolean contains(double x, double y) {
        for(int i = 1; i < rect[0]; i+= 4) {
            if (rect[i] <= x && x <= rect[i + 2] && rect[i + 1] <= y && y <= rect[i + 3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests does Point2D lie inside MultiRectArea object
     */
    public boolean contains(Point2D p) {
        return contains(p.getX(), p.getY());
    }

    /**
     * Tests does rectangle lie inside MultiRectArea object
     */
    public boolean contains(double x, double y, double w, double h) {
        throw new RuntimeException("Not implemented"); //$NON-NLS-1$
    }

    /**
     * Tests does Rectangle2D lie inside MultiRectArea object
     */
    public boolean contains(Rectangle2D r) {
        throw new RuntimeException("Not implemented"); //$NON-NLS-1$
    }

    /**
     * Tests does rectangle intersect MultiRectArea object
     */
    public boolean intersects(double x, double y, double w, double h) {
        Rectangle r = new Rectangle();
        r.setRect(x, y, w, h);
        return intersects(r);
    }

    /**
     * Tests does Rectangle2D intersect MultiRectArea object
     */
    public boolean intersects(Rectangle2D r) {
        if (r == null || r.isEmpty()) {
            return false;
        }
        for(int i = 1; i < rect[0]; i+= 4) {
            if (r.intersects(rect[i], rect[i+1], rect[i + 2]-rect[i]+1, rect[i + 3]-rect[i + 1]+1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns path iterator
     */
    public PathIterator getPathIterator(AffineTransform t, double flatness) {
        return new Iterator(this, t);
    }

    /**
     * Returns path iterator
     */
    public PathIterator getPathIterator(AffineTransform t) {
        return new Iterator(this, t);
    }

    /**
     * Returns MultiRectArea object converted to string 
     */
    @Override
    public String toString() {
        int cnt = getRectCount();
        StringBuffer sb = new StringBuffer((cnt << 5) + 128);
        sb.append(getClass().getName()).append(" ["); //$NON-NLS-1$
        for(int i = 1; i < rect[0]; i += 4) {
            sb.append(i > 1 ? ", [" : "[").append(rect[i]).append(", ").append(rect[i + 1]). //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            append(", ").append(rect[i + 2] - rect[i] + 1).append(", "). //$NON-NLS-1$ //$NON-NLS-2$
            append(rect[i + 3] - rect[i + 1] + 1).append("]"); //$NON-NLS-1$
        }
        return sb.append("]").toString(); //$NON-NLS-1$
    }

}

