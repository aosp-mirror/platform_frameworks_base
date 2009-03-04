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

import org.apache.harmony.awt.gl.MultiRectArea;

public class JavaArcRasterizer {

    /**
     * Adds particular arc segment to mra 
     */
    static void addX0LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int x1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x2 = line[i];
            int y = cy + (b - i);
            if (x1 <= finish && x2 >= start) {
                mra.addRect(cx + Math.max(x1, start), y, cx + Math.min(x2, finish), y);
            }
            x1 = x2 + 1;
        }
    }

    static void addX1LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int x1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x2 = line[i];
            int y = cy - (b - i);
            if (x1 <= finish && x2 >= start) {
                mra.addRect(cx + Math.max(x1, start), y, cx + Math.min(x2, finish), y);
            }
            x1 = x2 + 1;
        }
    }

    static void addX2LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int x1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x2 = line[i];
            int y = cy - (b - i);
            if (x1 <= finish && x2 >= start) {
                mra.addRect(cx - Math.min(x2, finish), y, cx - Math.max(x1, start), y);
            }
            x1 = x2 + 1;
        }
    }

    static void addX3LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int x1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x2 = line[i];
            int y = cy + (b - i);
            if (x1 <= finish && x2 >= start) {
                mra.addRect(cx - Math.min(x2, finish), y, cx - Math.max(x1, start), y);
            }
            x1 = x2 + 1;
        }
    }

    static void addY0LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int y1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x = cx + (b - i);
            int y2 = line[i];
            if (y1 <= finish && y2 >= start) {
                mra.addRect(x, cy + Math.max(y1, start), x, cy + Math.min(y2, finish));
            }
            y1 = y2 + 1;
        }
    }

    static void addY1LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int y1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x = cx - (b - i);
            int y2 = line[i];
            if (y1 <= finish && y2 >= start) {
                mra.addRect(x, cy + Math.max(y1, start), x, cy + Math.min(y2, finish));
            }
            y1 = y2 + 1;
        }
    }

    static void addY2LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int y1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x = cx - (b - i);
            int y2 = line[i];
            if (y1 <= finish && y2 >= start) {
                mra.addRect(x, cy - Math.min(y2, finish), x, cy - Math.max(y1, start));
            }
            y1 = y2 + 1;
        }
    }

    static void addY3LineSeg(MultiRectArea mra, int[] line, int cx, int cy, int b, int start, int finish) {
        int y1 = 0;
        for(int i = 0; i < line.length; i++) {
            int x = cx + (b - i);
            int y2 = line[i];
            if (y1 <= finish && y2 >= start) {
                mra.addRect(x, cy - Math.min(y2, finish), x, cy - Math.max(y1, start));
            }
            y1 = y2 + 1;
        }
    }

    static void addX0Line(MultiRectArea mra, int[] line, int cx, int cy, int b) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx + prev, cy + (b - i), cx + line[i], cy + (b - i));
            prev = line[i] + 1;
        }
    }

    static void addX1Line(MultiRectArea mra, int[] line, int cx, int cy, int b) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx + prev, cy - (b - i), cx + line[i], cy - (b - i));
            prev = line[i] + 1;
        }
    }

    static void addX2Line(MultiRectArea mra, int[] line, int cx, int cy, int b) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx - line[i], cy - (b - i), cx - prev, cy - (b - i));
            prev = line[i] + 1;
        }
    }

    static void addX3Line(MultiRectArea mra, int[] line, int cx, int cy, int b) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx - line[i], cy + (b - i), cx - prev, cy + (b - i));
            prev = line[i] + 1;
        }
    }

    static void addY0Line(MultiRectArea mra, int[] line, int cx, int cy, int a) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx + (a - i), cy + prev, cx + (a - i), cy + line[i]);
            prev = line[i] + 1;
        }
    }

    static void addY1Line(MultiRectArea mra, int[] line, int cx, int cy, int a) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx - (a - i), cy + prev, cx - (a - i), cy + line[i]);
            prev = line[i] + 1;
        }
    }

    static void addY2Line(MultiRectArea mra, int[] line, int cx, int cy, int a) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx - (a - i), cy - line[i], cx - (a - i), cy - prev);
            prev = line[i] + 1;
        }
    }

    static void addY3Line(MultiRectArea mra, int[] line, int cx, int cy, int a) {
        int prev = 0;
        for(int i = 0; i < line.length; i++) {
            mra.addRect(cx + (a - i), cy - line[i], cx + (a - i), cy - prev);
            prev = line[i] + 1;
        }
    }

    /**
     * Returns normalized angle (from 0 to 360 degrees)
     */
    static double getNormAngle(double angle) {
        angle -= Math.floor(angle / 360) * 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    /**
     * Creates arc lookup table
     */
    static int[] createLine(int a, int b, int xcount, int ycount) {
        int[] buf = new int[b - ycount + 1];
        int d = a * a + 2 * b * b - 2 * a * a * b;
        int x = 0;
        int y = b;
        while (y >= ycount) {
            if (d < 0) {
                d = d + b * b * (4 * x + 6);
            } else {
                buf[b - y] = x;
                d = d + b * b * (4 * x + 6) + 4 * a * a * (1 - y);
                y--;
            }
            x++;
        }
        return buf;
    }

    /**
     * Adds head/tail arc segment to MultiRectArea
     */
    static void addSeg(MultiRectArea mra, int cx1, int cy1, int cx2, int cy2, int a, int b, int[] xline, int[] yline, int[] bounds) {
        switch(bounds[0]) {
        case 0:
            addY3LineSeg(mra, yline, cx2, cy1, a, bounds[1], bounds[2]);
            break;
        case 1:
            addX1LineSeg(mra, xline, cx2, cy1, b, bounds[1], bounds[2]);
            break;
        case 2:
            addX2LineSeg(mra, xline, cx1, cy1, b, bounds[1], bounds[2]);
            break;
        case 3:
            addY2LineSeg(mra, yline, cx1, cy1, a, bounds[1], bounds[2]);
            break;
        case 4:
            addY1LineSeg(mra, yline, cx1, cy2, a, bounds[1], bounds[2]);
            break;
        case 5:
            addX3LineSeg(mra, xline, cx1, cy2, b, bounds[1], bounds[2]);
            break;
        case 6:
            addX0LineSeg(mra, xline, cx2, cy2, b, bounds[1], bounds[2]);
            break;
        case 7:
            addY0LineSeg(mra, yline, cx2, cy2, a, bounds[1], bounds[2]);
            break;
        }
    }

    /**
     * Returns bounds for non quadratic arc head
     */
    static int[] getSegment1(double angle, int ax, int ay, int xcount, int ycount) {
        int[] bounds = new int[3];
        switch((int)(angle / 90)) {
        case 0:
            if (xcount <  ax) {
                bounds[0] = 0; // Y3
                bounds[1] = -ay;
                bounds[2] = ycount;
            } else {
                bounds[0] = 1; // X1
                bounds[1] = 0;
                bounds[2] = ax;
            }
            break;
        case 1:
            if (xcount > -ax) {
                bounds[0] = 2; // X2
                bounds[1] = -ax;
                bounds[2] = xcount;
            } else {
                bounds[0] = 3; // Y2
                bounds[1] = 0;
                bounds[2] = -ay;
            }
            break;
        case 2:
            if (xcount < -ax) {
                bounds[0] = 4; // Y1
                bounds[1] = ay;
                bounds[2] = ycount;
            } else {
                bounds[0] = 5; // X3
                bounds[1] = 0;
                bounds[2] = -ax;
            }
            break;
        case 3:
            if (xcount >  ax) {
                bounds[0] = 6; // X0
                bounds[1] = ax;
                bounds[2] = xcount;
            } else {
                bounds[0] = 7; // Y0
                bounds[1] = 0;
                bounds[2] = ay;
            }
            break;
        }
        return bounds;
    }

    /**
     * Returns bounds for non quadratic arc tail
     */
    static int[] getSegment2(double angle, int ax, int ay, int xcount, int ycount) {
        int[] bounds = new int[3];
        switch((int)(angle / 90)) {
        case 0:
            if (xcount <  ax) {
                bounds[0] = 0; // Y3
                bounds[1] = 0;
                bounds[2] = -ay;
            } else {
                bounds[0] = 1; // X1
                bounds[1] = ax;
                bounds[2] = xcount;
            }
            break;
        case 1:
            if (xcount > -ax) {
                bounds[0] = 2; // X2
                bounds[1] = 0;
                bounds[2] = -ax;
            } else {
                bounds[0] = 3; // Y2
                bounds[1] = -ay;
                bounds[2] = ycount;
            }
            break;
        case 2:
            if (xcount < -ax) {
                bounds[0] = 4; // Y1
                bounds[1] = 0;
                bounds[2] = ay;
            } else {
                bounds[0] = 5; // X3
                bounds[1] = -ax;
                bounds[2] = xcount;
            }
            break;
        case 3:
            if (xcount >  ax) {
                bounds[0] = 6; // X0
                bounds[1] = 0;
                bounds[2] = ax;
            } else {
                bounds[0] = 7; // Y0
                bounds[1] = ay;
                bounds[2] = ycount;
            }
            break;
        }
        return bounds;
    }

    /**
     * Rasterizes arc using clippind and dashing style
     * @param x1 - the x coordinate of the left-upper corner of the arc bounds
     * @param y1 - the y coordinate of the left-upper corner of the arc bounds
     * @param width - the width of the arc bounds
     * @param height - the height of the arc bounds
     * @param angleStart - the start angle of the arc in degrees
     * @param angleExtent - the angle extent in degrees
     * @param clip - the MultiRectArea object of clipping area
     * @return a MultiRectArea of rasterizer arc
     */
    public static MultiRectArea rasterize(int x, int y, int width, int height, double angleStart, double angleExtent, MultiRectArea clip) {

        MultiRectArea mra = new MultiRectArea(false);

        int cx1, cx2, cy1, cy2;
        cx1 = cx2 = x + width / 2;
        cy1 = cy2 = y + height / 2;

        if (width % 2 == 0) {
            cx2--;
        }

        if (height % 2 == 0) {
            cy2--;
        }

        int a = width / 2;
        int b = height / 2;
        double c = Math.sqrt(a * a + b * b);

        int xcount, ycount;
        if (a < b) {
            xcount = (int)Math.ceil(a * a / c);
            ycount = (int)Math.floor(b * b / c);
        } else {
            xcount = (int)Math.floor(a * a / c);
            ycount = (int)Math.ceil(b * b / c);
        }

        int[] xline = createLine(a, b, xcount, ycount);
        int[] yline = createLine(b, a, ycount, xcount);

        // Correct lines
        int i = xline.length;
        while(xline[--i] > xcount) {
            xline[i] = xcount;
        }

        i = yline.length;
        while(yline[--i] > ycount) {
            yline[i] = ycount;
        }

        if (Math.abs(angleExtent) >= 360) {
            // Rasterize CIRCLE
            addX0Line(mra, xline, cx2, cy2, b);
            addX1Line(mra, xline, cx2, cy1, b);
            addX2Line(mra, xline, cx1, cy1, b);
            addX3Line(mra, xline, cx1, cy2, b);
            addY0Line(mra, yline, cx2, cy2, a);
            addY1Line(mra, yline, cx1, cy2, a);
            addY2Line(mra, yline, cx1, cy1, a);
            addY3Line(mra, yline, cx2, cy1, a);
        } else {
            // Rasterize ARC
            angleStart = getNormAngle(angleStart);
            double angleFinish = getNormAngle(angleStart + angleExtent);

            if (angleExtent < 0) {
                double tmp = angleStart;
                angleStart = angleFinish;
                angleFinish = tmp;
            }

            double radStart = -Math.toRadians(angleStart);
            double radFinish = -Math.toRadians(angleFinish);
            int ax1 = (int)(a * Math.cos(radStart));
            int ay1 = (int)(b * Math.sin(radStart));
            int ax2 = (int)(a * Math.cos(radFinish));
            int ay2 = (int)(b * Math.sin(radFinish));

            int[] seg1 = getSegment1(angleStart, ax1, ay1, xcount, ycount);
            int[] seg2 = getSegment2(angleFinish, ax2, ay2, xcount, ycount);

            // Start and Finish located in the same quater
            if (angleStart < angleFinish && seg1[0] == seg2[0]) {
                if (seg1[0] % 2 == 0) {
                    seg1[2] = seg2[2];
                } else {
                    seg1[1] = seg2[1];
                }
                addSeg(mra, cx1, cy1, cx2, cy2, a, b, xline, yline, seg1);
                return mra;
            }

            addSeg(mra, cx1, cy1, cx2, cy2, a, b, xline, yline, seg1);
            addSeg(mra, cx1, cy1, cx2, cy2, a, b, xline, yline, seg2);

            int startSeg = (seg1[0] + 1) % 8;
            int finishSeg = seg2[0];

            while (startSeg != finishSeg) {
                switch(startSeg) {
                case 0:
                    addY3Line(mra, yline, cx2, cy1, a);
                    break;
                case 1:
                    addX1Line(mra, xline, cx2, cy1, b);
                    break;
                case 2:
                    addX2Line(mra, xline, cx1, cy1, b);
                    break;
                case 3:
                    addY2Line(mra, yline, cx1, cy1, a);
                    break;
                case 4:
                    addY1Line(mra, yline, cx1, cy2, a);
                    break;
                case 5:
                    addX3Line(mra, xline, cx1, cy2, b);
                    break;
                case 6:
                    addX0Line(mra, xline, cx2, cy2, b);
                    break;
                case 7:
                    addY0Line(mra, yline, cx2, cy2, a);
                    break;
                }
                startSeg = (startSeg + 1) % 8;
            }
        }

        if (clip != null) {
            mra.intersect(clip);
        }

        return mra;
    }

}