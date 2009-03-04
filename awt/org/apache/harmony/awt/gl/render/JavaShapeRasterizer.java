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
package org.apache.harmony.awt.gl.render;

import java.awt.Shape;
import java.awt.geom.PathIterator;

import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.internal.nls.Messages;

public class JavaShapeRasterizer {

    static final int POINT_CAPACITY = 16;

    int edgesCount;
    int edgeCur;
    int[] edgesX;
    int[] edgesY;
    int[] edgesYS; // Y coordinate of edge START point
    int[] edgesN;
    int[] edgesDY;
    int[] bounds;
    int boundCount;
    boolean[] edgesExt; // Extremal points

    int activeCount;
    float[] activeX;
    int[] activeYEnd;
    float[] activeXStep;
    int[] activeDY;
    boolean[] activeExt;

    int[] crossX;
    int[] crossDY;

    Filler filler;

    /**
     * Rasterization filler for different path rules
     */
    static abstract class Filler {

        static class NonZero extends Filler {
            @Override
            void add(MultiRectArea.LineCash rect, int[] points, int[] orient, int length, int y) {

                int[] dst = new int[length];
                int dstLength = 1;
                dst[0] = points[0];
                int count = 0;
                boolean inside = true;
                for(int i = 0; i < length; i++) {
                    count += orient[i] > 0 ? 1 : -1;
                    if (count == 0) {
                        dst[dstLength++] = points[i];
                        inside = false;
                    } else {
                        if (!inside) {
                            dst[dstLength++] = points[i];
                            inside = true;
                        }
                    }

                }

                for(int i = 1; i < dstLength; i += 2) {
                    dst[i]--;
                }

                dstLength = excludeEmpty(dst, dstLength);
//              System.out.println("test");

                dstLength = union(dst, dstLength);

                rect.addLine(dst, dstLength);
            }
        }

        static class EvenOdd extends Filler {
            @Override
            void add(MultiRectArea.LineCash rect, int[] points, int[] orient, int length, int y) {
    /*
                int[] buf = new int[length];
                int j = 0;
                for(int i = 0; i < length - 1; i++) {
                    if (points[i] != points[i + 1]) {
                        buf[j++] = points[i];
                    }
                }
    */
                for(int i = 1; i < length; i += 2) {
                    points[i]--;
                }

                length = excludeEmpty(points, length);
//              System.out.println("test");

                length = union(points, length);
                rect.addLine(points, length);
    /*
                for(int i = 0; i < length;) {
                    rect.add(points[i++], y, points[i++], y);
                }
    */
            }
        }

        abstract void add(MultiRectArea.LineCash rect, int[] points, int[] orient, int length, int y);

        static int excludeEmpty(int[] points, int length) {
            int i = 0;
            while(i < length) {
                if (points[i] <= points[i + 1]) {
                    i += 2;
                } else {
                    length -= 2;
                    System.arraycopy(points, i + 2, points, i, length - i);
                }
            }
            return length;
        }

        static int union(int[] points, int length) {
            int i = 1;
            while(i < length - 1) {
                if (points[i] < points[i - 1]) {
                    System.arraycopy(points, i + 1, points, i - 1, length - i - 1);
                    length -= 2;
                } else
                if (points[i] >= points[i + 1] - 1) {
                    System.arraycopy(points, i + 2, points, i, length - i - 2);
                    length -= 2;
                } else {
                    i += 2;
                }
            }
            return length;
        }

    }

    public JavaShapeRasterizer() {
    }

    /**
     * Checks buffer size and realloc if necessary
     */
    int[] checkBufSize(int[] buf, int size) {
        if (size == buf.length) {
            int[] tmp;
            tmp = new int[size + POINT_CAPACITY];
            System.arraycopy(buf, 0, tmp, 0, buf.length);
            buf = tmp;
        }
        return buf;
    }

    /**
     * Adds to the buffers new edge 
     */
    void addEdge(int x, int y, int num) {
        edgesX = checkBufSize(edgesX, edgesCount);
        edgesY = checkBufSize(edgesY, edgesCount);
        edgesN = checkBufSize(edgesN, edgesCount);
        edgesX[edgesCount] = x;
        edgesY[edgesCount] = y;
        edgesN[edgesCount] = (num << 16) | edgesCount;
        edgesCount++;
    }

    /**
     * Prepare all buffers and variable to rasterize shape 
     */
    void makeBuffer(PathIterator path, double flatness) {
        edgesX = new int[POINT_CAPACITY];
        edgesY = new int[POINT_CAPACITY];
        edgesN = new int[POINT_CAPACITY];
        bounds = new int[POINT_CAPACITY];
        boundCount = 0;
        edgesCount = 0;

        if (path.getWindingRule() == PathIterator.WIND_EVEN_ODD) {
            filler = new Filler.EvenOdd();
        } else {
            filler = new Filler.NonZero();
        }
        float[] coords = new float[2];
        boolean closed = true;
        while (!path.isDone()) {
            switch(path.currentSegment(coords)) {
            case PathIterator.SEG_MOVETO:
                if (!closed) {
                    boundCount++;
                    bounds = checkBufSize(bounds, boundCount);
                    bounds[boundCount] = edgesCount;
                }
                addEdge((int)coords[0], (int)coords[1], boundCount);
                closed = false;
                break;
            case PathIterator.SEG_LINETO:
                addEdge((int)coords[0], (int)coords[1], boundCount);
                break;
            case PathIterator.SEG_CLOSE:
                boundCount++;
                bounds = checkBufSize(bounds, boundCount);
                bounds[boundCount] = edgesCount;
                closed = true;
                break;
            default:
                // awt.36=Wrong segment
                throw new RuntimeException(Messages.getString("awt.36")); //$NON-NLS-1$
            }
            path.next();
        }
        if (!closed) {
            boundCount++;
            bounds = checkBufSize(bounds, boundCount);
            bounds[boundCount] = edgesCount;
        }
    }

    /**
     * Sort buffers
     */
    void sort(int[] master, int[] slave, int length) {
        for(int i = 0; i < length - 1; i++) {
            int num = i;
            int min = master[num];
            for(int j = i + 1; j < length; j++) {
                if (master[j] < min) {
                    num = j;
                    min = master[num];
                }
            }
            if (num != i) {
                master[num] = master[i];
                master[i] = min;
                min = slave[num];
                slave[num] = slave[i];
                slave[i] = min;
            }
        }
    }

    int getNext(int cur) {
        int n = edgesN[cur];
        int bound = n >> 16;
        int num = (n & 0xFFFF) + 1;
        if (num == bounds[bound + 1]) {
            return bounds[bound];
        }
        return num;
    }

    int getPrev(int cur) {
        int n = edgesN[cur];
        int bound = n >> 16;
        int num = (n & 0xFFFF) - 1;
        if (num < bounds[bound]) {
            return bounds[bound + 1] - 1;
        }
        return num;
    }

    int getNextShape(int cur) {
        int bound = edgesN[cur] >> 16;
        return bounds[bound + 1];
    }

    void init() {

        edgesYS = new int[edgesCount];
        System.arraycopy(edgesY, 0, edgesYS, 0, edgesCount);
        // Create edgesDY
        edgesDY = new int[edgesCount];
        for(int i = 0; i < edgesCount; i++) {
            int dy = edgesY[getNext(i)] - edgesY[i];
            edgesDY[i] = dy;
        }

        // Create edgesExt
        edgesExt = new boolean[edgesCount];
        int prev = -1;
        int i = 0;
        int pos = 0;
        while(i < edgesCount) {

            TOP: {
                do {
                    if (edgesDY[i] > 0) {
                        break TOP;
                    }
                    i = getNext(i);
                } while (i != pos);
                i = pos = getNextShape(i);
                continue;
            }

            BOTTOM: {
                do {
                    if (edgesDY[i] < 0) {
                        break BOTTOM;
                    }
                    if (edgesDY[i] > 0) {
                        prev = i;
                    }
                    i = getNext(i);
                } while (i != pos);
                i = pos = getNextShape(i);
                continue;
            }

            if (prev != -1) {
                edgesExt[prev] = true;
            }
            edgesExt[i] = true;
        }

        // Sort edgesY and edgesN
        sort(edgesYS, edgesN, edgesCount);

        edgeCur = 0;
        activeCount = 0;
        activeX = new float[edgesCount];
        activeYEnd = new int[edgesCount];
        activeXStep = new float[edgesCount];
        activeDY = new int[edgesCount];
        activeExt = new boolean[edgesCount];

        crossX = new int[edgesCount];
        crossDY = new int[edgesCount];
    }

    /**
     * Marks edge as active
     */
    void addActiveEdge(int levelY, int start, int end, boolean back) {
        int dy = back ? -edgesDY[end] : edgesDY[start];
        if (dy <= 0) {
            return;
        }
        int x1 = edgesX[start];
        int dx = edgesX[end] - x1;
        activeX[activeCount] = x1;
        activeYEnd[activeCount] = edgesY[end];
        activeXStep[activeCount] = dx / (float)dy;
        activeDY[activeCount] = back ? -dy : dy;
        activeExt[activeCount] = back ? edgesExt[end] : edgesExt[start];
        activeCount++;
    }

    /**
     * Find new active edges
     */
    int findActiveEdges(int levelY) {

        int edgeActive = edgeCur;
        while (edgeActive < edgesCount && edgesYS[edgeActive] == levelY) {
            edgeActive++;
        }

        int activeNext = edgeActive;

        while (edgeActive > edgeCur) {
            edgeActive--;
            int num = edgesN[edgeActive] & 0xFFFF;
            addActiveEdge(levelY, num, getPrev(edgeActive), true);
            addActiveEdge(levelY, num, getNext(edgeActive), false);
        }

        edgeCur = activeNext;

        if (activeNext == edgesCount) {
            return edgesY[edgesCount - 1];
        }
        return edgesYS[activeNext];
    }

    /**
     * Rasterizes shape with particular flatness
     * @param shape - the souze Shape to be rasterized
     * @param flatness - the rasterization flatness
     * @return a MultiRectArea of rasterized shape
     */
    public MultiRectArea rasterize(Shape shape, double flatness) {

        PathIterator path = shape.getPathIterator(null, flatness);

        // Shape is empty
        if (path.isDone()) {
            return new MultiRectArea();
        }

        makeBuffer(path, flatness);

        init();

        int y = edgesYS[0];
        int nextY = y;
        int crossCount;

        MultiRectArea.LineCash rect = new MultiRectArea.LineCash(edgesCount);
        rect.setLine(y);

        while(y <= nextY) {

            crossCount = 0;

            if (y == nextY) {

                int i = activeCount;
                while(i > 0) {
                    i--;
                    if (activeYEnd[i] == y) {

                        activeCount--;
                        int length = activeCount - i;
                        if (length != 0) {
                            int pos = i + 1;
                            System.arraycopy(activeX, pos, activeX, i, length);
                            System.arraycopy(activeYEnd, pos, activeYEnd, i, length);
                            System.arraycopy(activeXStep, pos, activeXStep, i, length);
                            System.arraycopy(activeDY, pos, activeDY, i, length);
                            System.arraycopy(activeExt, pos, activeExt, i, length);
                        }
                    }
                }

                nextY = findActiveEdges(y);
            }

            // Get X crossings
            for(int i = 0; i < activeCount; i++) {
                crossX[crossCount] = (int)Math.ceil(activeX[i]);
                crossDY[crossCount] = activeDY[i];
                crossCount++;
            }

            if (crossCount == 0) {
                rect.skipLine();
            } else {
                // Sort X crossings
                sort(crossX, crossDY, crossCount);
                filler.add(rect, crossX, crossDY, crossCount, y);
            }

            for(int i = 0; i < activeCount; i++) {
                activeX[i] += activeXStep[i];
            }

            y++;
        }

        return rect;
    }

}
