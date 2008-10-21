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


public class JavaLineRasterizer {

    /**
     *  LineDasher class provides dashing for particular dash style
     */
    public static class LineDasher {

        int index;
        float pos;
        float phase;
        float dash[];
        float inv[];
        boolean visible;

        public LineDasher() {
        }

        public LineDasher(float dash[], float phase) {
            this.dash = dash;
            this.phase = phase;

            inv = new float[dash.length];
            int j = dash.length;
            for (float element : dash) {
                inv[--j] = element;
            }
            index = 0;
            while (phase > dash[index]) {
                phase -= dash[index];
                index = (index + 1) % dash.length;
            }
            visible = index % 2 == 0;
        }

        void move(float step) { // main dasher
            pos += step;
            step += phase;
            while(step >= dash[index]) {
                step -= dash[index];
                index = (index + 1) % dash.length;
                visible = !visible;
            }
            phase = step;
        }

        float nextDash() {
            phase = 0.0f;
            index = (index + 1) % dash.length;
            visible = !visible;
            return dash[index];
        }

        LineDasher createDiagonal(double k, float length, boolean invert) {
            LineDasher local = new LineDasher();
            local.dash = new float[dash.length];
            if (invert) { // inverted dasher
                move(length);
                local.phase = (float)((dash[index] - phase) * k);
                local.visible = visible;
                local.index = inv.length - index - 1;
                for(int i = 0; i < inv.length; i++) {
                    local.dash[i] = (float)(inv[i] * k);
                }
            } else {
                local.phase = (float)(phase * k);
                local.visible = visible;
                local.index = index;
                for(int i = 0; i < dash.length; i++) {
                    local.dash[i] = (float)(dash[i] * k);
                }
                move(length);
            }
            return local;
        }

        LineDasher createOrtogonal(float length, boolean invert) {
            LineDasher local = new LineDasher();
            local.dash = new float[dash.length];
            if (invert) { // inverted dasher
                move(length);
                local.phase = dash[index] - phase;
                local.visible = visible;
                local.index = inv.length - index - 1;
                local.dash = inv;
            } else {
                local.phase = phase;
                local.visible = visible;
                local.index = index;
                local.dash = dash;
                move(length);
            }
            return local;
        }

        LineDasher createChild(float start) {
            LineDasher child = new LineDasher();
            child.phase = phase;
            child.visible = visible;
            child.index = index;
            child.dash = dash;
            child.move(start);
            return child;
        }

    }

    /**
     * Line class provides rasterization for different line types
     */
    abstract static class Line {

        int x1, y1, x2, y2;
        int x, y;
        MultiRectArea dst;

        Line(int x1, int y1, int x2, int y2, MultiRectArea dst) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.dst = dst;
        }

        static abstract class Diag extends Line {
            int dx, dy, adx, ady, sx, sy;
            int eBase, ePos, eNeg;
            int xcount;
            int e;

            Diag(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                super(x1, y1, x2, y2, dst);
                dx = x2 - x1;
                dy = y2 - y1;
                sy = 1;
                if (dx > 0) {
                    adx = dx;
                    sx = 1;
                } else {
                    adx = -dx;
                    sx = -1;
                }
                ady = dy;
            }

            float getLength() {
                return (float)Math.sqrt(dx * dx + dy * dy);
            }

            static class Hor extends Diag {

                Hor(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                    super(x1, y1, x2, y2, dst);
                    eBase = ady + ady - adx;
                    ePos = 2 * (ady - adx);
                    eNeg = ady + ady;
                    xcount = adx;
                }

                @Override
                void rasterize() {
                    e = eBase;
                    x = x1;
                    y = y1;
                    rasterize(xcount);
                }

                @Override
                void rasterizeClipped(int nx1, int ny1, int nx2, int ny2) {
                    e = eBase + 2 * (ady * Math.abs(nx1 - x1) - adx * Math.abs(ny1 - y1));
                    x = nx1;
                    y = ny1;
                    rasterize(dx > 0 ? nx2 - nx1 : nx1 - nx2);
                }

                @Override
                void rasterize(int count) {
                    int px = x;
                    while (count-- > 0) {
                        if (e >= 0) {
                            if (sx > 0) {
                                dst.addRect(px, y, x, y);
                            } else {
                                dst.addRect(x, y, px, y);
                            }
                            x += sx;
                            y += sy;
                            e += ePos;
                            px = x;
                        } else {
                            e += eNeg;
                            x += sx;
                        }
                    }
                    if (sx > 0) {
                        dst.addRect(px, y, x, y);
                    } else {
                        dst.addRect(x, y, px, y);
                    }
                }

                @Override
                void skip(int count) {
                    while (count-- > 0) {
                        x += sx;
                        if (e >= 0) {
                            y += sy;
                            e += ePos;
                        } else {
                            e += eNeg;
                        }
                    }
                }

            }

            static class Ver extends Diag {

                Ver(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                    super(x1, y1, x2, y2, dst);
                    eBase = adx + adx - ady;
                    ePos = 2 * (adx - ady);
                    eNeg = adx + adx;
                    xcount = ady;
                }

                @Override
                void rasterize() {
                    e = eBase;
                    x = x1;
                    y = y1;
                    rasterize(xcount);
                }

                @Override
                void rasterizeClipped(int nx1, int ny1, int nx2, int ny2) {
                    e = eBase + 2 * (adx * Math.abs(ny1 - y1) - ady * Math.abs(nx1 - x1));
                    x = nx1;
                    y = ny1;
                    rasterize(ny2 - ny1);
                }

                @Override
                void rasterize(int count) {
                    int py = y;
                    while (count-- > 0) {
                        if (e >= 0) {
                            dst.addRect(x, py, x, y);
                            x += sx;
                            y += sy;
                            e += ePos;
                            py = y;
                        } else {
                            y += sy;
                            e += eNeg;
                        }
                    }
                    dst.addRect(x, py, x, y);
                }

                @Override
                void skip(int count) {
                    while (count-- > 0) {
                        y += sy;
                        if (e >= 0) {
                            x += sx;
                            e += ePos;
                        } else {
                            e += eNeg;
                        }
                    }
                }

            }

            static class HorDashed extends Hor {

                LineDasher local;

                HorDashed(int x1, int y1, int x2, int y2, MultiRectArea dst, LineDasher dasher, boolean invert) {
                    super(x1, y1, x2, y2, dst);
                    float length = getLength();
                    local = dasher.createDiagonal(xcount / length, length, invert);
                }

                @Override
                void rasterize() {
                    e = eBase;
                    x = x1;
                    y = y1;
                    rasterizeDash(xcount, local);
                }

                @Override
                void rasterizeClipped(int nx1, int ny1, int nx2, int ny2) {
                    e = eBase + 2 * (ady * Math.abs(nx1 - x1) - adx * Math.abs(ny1 - y1));
                    x = nx1;
                    y = ny1;
                    rasterizeDash(Math.abs(nx2 - nx1), local.createChild(Math.abs(nx1 - x1)));
                }

            }

            static class VerDashed extends Ver {

                LineDasher local;

                VerDashed(int x1, int y1, int x2, int y2, MultiRectArea dst, LineDasher dasher, boolean invert) {
                    super(x1, y1, x2, y2, dst);
                    float length = getLength();
                    local = dasher.createDiagonal(xcount / length, length, invert);
                }

                @Override
                void rasterize() {
                    e = eBase;
                    x = x1;
                    y = y1;
                    rasterizeDash(xcount, local);
                }

                @Override
                void rasterizeClipped(int nx1, int ny1, int nx2, int ny2) {
                    e = eBase + 2 * (adx * Math.abs(ny1 - y1) - ady * Math.abs(nx1 - x1));
                    x = nx1;
                    y = ny1;
                    rasterizeDash(ny2 - ny1, local.createChild(ny1 - y1));
                }

            }

            @Override
            void rasterize(int[] clip, int index) {
                int cx1 = clip[index + 0];
                int cy1 = clip[index + 1];
                int cx2 = clip[index + 2] + 1;
                int cy2 = clip[index + 3] + 1;

                int code1 =
                    (x1 < cx1 ? 1 : 0) | (x1 >= cx2 ? 2 : 0) |
                    (y1 < cy1 ? 8 : 0) | (y1 >= cy2 ? 4 : 0);
                int code2 =
                    (x2 < cx1 ? 1 : 0) | (x2 >= cx2 ? 2 : 0) |
                    (y2 < cy1 ? 8 : 0) | (y2 >= cy2 ? 4 : 0);

                // Outside
                if ((code1 & code2) != 0) {
                    return;
                }

                // Inside
                if (code1 == 0 && code2 == 0) {
                    rasterize();
                    return;
                }

                // Clip
                int nx1 = x1;
                int ny1 = y1;
                int nx2 = x2;
                int ny2 = y2;
                // need to clip
                cx1 -= x1; cx2 -= x1;
                cy1 -= y1; cy2 -= y1;
//                int d;
                int newx1 = 0, newy1 = 0, newx2 = 0, newy2 = 0;
                if (code1 != 0) {
                    newx1 = Integer.MAX_VALUE;
                    if ((code1 & 8) != 0) {
                        // clip point 1 with top clip bound
                        newy1 = cy1;
                        newx1 = clipY(dx, dy, newy1, true);

                    } else if ((code1 & 4) != 0) {
                        // clip point 1 with bottom clip bound
                        newy1 = cy2 - 1;
                        newx1 = clipY(dx, dy, newy1, false);
                    }
                    if ((code1 & 1) != 0 && (cx1 > newx1 || newx1 == Integer.MAX_VALUE)) {
                        // clip point 1 with left clip bound
                        newx1 = cx1;
                        newy1 = clipX(dx, dy, newx1, false);
                    } else if ((code1 & 2) != 0 && (newx1 >= cx2 || newx1 == Integer.MAX_VALUE)) {
                        // clip point 1 with right clip bound
                        newx1 = cx2 - 1;
                        newy1 = clipX(dx, dy, newx1, false);
                    }
                    if (newx1 < cx1 || newx1 >= cx2 || newy1 < cy1 || newy1 >= cy2) {
                        return;
                    }
//                    d = 2 * (ady * Math.abs(newx1) - adx * Math.abs(newy1)) + 2 * ady - adx;
                } else {
//                    d = (ady << 1) - adx;
                }

                if (code2 != 0) {
                    newx2=Integer.MAX_VALUE;
                    if ((code2 & 8) != 0) {
                        // clip point 2 with top clip bound
                        newy2 = cy1;
                        newx2 = clipY(dx, dy, newy2, true);
                    } else if ((code2 & 4) != 0) {
                        // clip point 2 with bottom clip bound
                        newy2 = cy2 - 1;
                        newx2 = clipY(dx, dy, newy2, false);
                    }
                    if ((code2 & 1) != 0 && (cx1 > newx2 || newx2 == Integer.MAX_VALUE)) {
                        // clip point 2 with left clip bound
                        newx2 = cx1;
                        newy2 = clipX(dx, dy, newx2, false);
                    } else if ((code2 & 2) != 0 && (newx2 >= cx2 || newx2 == Integer.MAX_VALUE)) {
                        // clip point 2 with right clip bound
                        newx2 = cx2 - 1;
                        newy2 = clipX(dx, dy, newx2, false);
                    }
                    if (newx2 < cx1 || newx2 >= cx2 || newy2 < cy1 || newy2 >= cy2) {
                        return;
                    }
                    nx2 = x1 + newx2;
                    ny2 = y1 + newy2;
                }
                nx1 = x1 + newx1;
                ny1 = y1 + newy1;

                rasterizeClipped(nx1, ny1, nx2, ny2);
            }

            abstract void rasterizeClipped(int nx1, int ny1, int nx2, int ny2);

        }

        static abstract class Ortog extends Line {

            Ortog(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                super(x1, y1, x2, y2, dst);
            }

            static class Hor extends Ortog {

                int dx;

                Hor(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                    super(x1, y1, x2, y2, dst);
                    dx = x2 - x1;
                }

                @Override
                void rasterize() {
                    if (dx > 0) {
                        dst.addRect(x1, y1, x2, y2);
                    } else {
                        dst.addRect(x2, y2, x1, y1);
                    }
                }

                @Override
                void rasterize(int step) {
                    int px = x;
                    if (dx > 0) {
                        x += step;
                        dst.addRect(px, y1, x - 1, y2);
                    } else {
                        x -= step;
                        dst.addRect(x + 1, y2, px, y1);
                    }
                }

                @Override
                void skip(int step) {
                    if (dx > 0) {
                        x += step;
                    } else {
                        x -= step;
                    }
                }

                void rasterizeClipped(int nx1, int nx2) {
                    if (nx1 < nx2) {
                        dst.addRect(nx1, y1, nx2, y1);
                    } else {
                        dst.addRect(nx2, y1, nx1, y1);
                    }
                }

                @Override
                void rasterize(int[] clip, int index) {
                    if (y1 >= clip[index + 1] && y1 <= clip[index + 3]) {
                        int cx1 = clip[index + 0];
                        int cx2 = clip[index + 2];
                        if (x1 <= cx2 && x2 >= cx1) {
                            int nx1, nx2;
                            if (dx > 0) {
                                nx1 = Math.max(x1, cx1);
                                nx2 = Math.min(x2, cx2);
                            } else {
                                nx2 = Math.max(x2, cx1);
                                nx1 = Math.min(x1, cx2);
                            }
                            rasterizeClipped(nx1, nx2);
                        }
                    }
                }

            }

            static class Ver extends Ortog {

                int dy;

                Ver(int x1, int y1, int x2, int y2, MultiRectArea dst) {
                    super(x1, y1, x2, y2, dst);
                    dy = y2 - y1;
                }

                @Override
                void rasterize() {
                    dst.addRect(x1, y1, x2, y2);
                }

                @Override
                void rasterize(int step) {
                    int py = y;
                    y += step;
                    dst.addRect(x1, py, x2, y - 1);
                }

                @Override
                void skip(int step) {
                    y += step;
                }

                void rasterizeClipped(int ny1, int ny2) {
                    dst.addRect(x1, ny1, x1, ny2);
                }

                @Override
                void rasterize(int[] clip, int index) {
                    if (x1 >= clip[index] && x1 <= clip[index + 2]) {
                        int cy1 = clip[index + 1];
                        int cy2 = clip[index + 3];
                        if (y1 <= cy2 && y2 >= cy1) {
                            rasterizeClipped(Math.max(y1, cy1), Math.min(y2, cy2));
                        }
                    }
                }

            }

            static class HorDashed extends Hor {

                LineDasher local;

                HorDashed(int x1, int y1, int x2, int y2, MultiRectArea dst, LineDasher dasher) {
                    super(x1, y1, x2, y2, dst);
                    dx = x2 - x1;
                    local = dasher.createOrtogonal(Math.abs(dx), false);
                }

                @Override
                void rasterize() {
                    x = x1;
                    y = y1;
                    rasterizeDash(Math.abs(dx), local);
                }

                @Override
                void rasterizeClipped(int nx1, int nx2) {
                    x = nx1;
                    y = y1;
                    rasterizeDash(Math.abs(nx2 - nx1), local.createChild(Math.abs(nx1 - x1)));
                }

            }

            static class VerDashed extends Ver {

                LineDasher local;

                VerDashed(int x1, int y1, int x2, int y2, MultiRectArea dst, LineDasher dasher, boolean invert) {
                    super(x1, y1, x2, y2, dst);
                    dy = y2 - y1;
                    local = dasher.createOrtogonal(dy, invert);
                }

                @Override
                void rasterize() {
                    x = x1;
                    y = y1;
                    rasterizeDash(dy, local);
                }

                @Override
                void rasterizeClipped(int ny1, int ny2) {
                    x = x1;
                    y = ny1;
                    rasterizeDash(ny2 - ny1, local.createChild(ny1));
                }

            }

        }

        abstract void rasterize();
        abstract void rasterize(int[] clip, int index);
        abstract void rasterize(int count);
        abstract void skip(int count);

        void rasterizeDash(int count, LineDasher dasher) {
            float delta = dasher.dash[dasher.index] - dasher.phase;
            int step = (int)delta;
            delta -= step;
            while(count > step) {
                if (dasher.visible) {
                    rasterize(step);
                } else {
                    skip(step);
                }
                count -= step;
                delta += dasher.nextDash();
                step = (int)delta;
                delta -= step;
            }
            if (count > 0 && dasher.visible) {
                rasterize(count);
                dasher.move(count);
            }
        }

    }

    /**
     * Common clipping method
     */
    static int clip(int dX1, int dX2, int cX, boolean top) {
        int adX1 = dX1 < 0 ? -dX1 : dX1;
        int adX2 = dX2 < 0 ? -dX2 : dX2;
        if (adX1 <= adX2) {
            // obtuse intersection angle
            return ((dX1 << 1) * cX + (dX1 > 0 ? dX2 : -dX2)) / (dX2 << 1);
        }
        int k;
        if (top) {
            k = -dX1 + (dX2 < 0 ? 0 : dX1 > 0 ? (dX2 << 1) : -(dX2 << 1));
        } else {
            k = dX1 + (dX2 > 0 ? 0 : dX1 > 0 ? (dX2 << 1) : -(dX2 << 1));
        }

        k += dX1 > 0 == dX2 > 0 ? -1 : 1;
        return ((dX1 << 1) * cX + k) / (dX2 << 1);
    }

    /**
     * Clipping along X axis
     */
    static int clipX(int dx, int dy, int cy, boolean top) {
        return clip(dy, dx, cy, top);
    }

    /**
     * Clipping along Y axis
     */
    static int clipY(int dx, int dy, int cx, boolean top) {
        return clip(dx, dy, cx, top);
    }

    /**
     * Rasterizes line using clippind and dashing style
     * @param x1 - the x coordinate of the first control point
     * @param y1 - the y coordinate of the first control point
     * @param x2 - the x coordinate of the second control point
     * @param y2 - the y coordinate of the second control point
     * @param clip - the MultiRectArea object of clipping area
     * @param dasher - the dasher style
     * @param invert - the invert indicator, always false
     * @return a MultiRectArea of rasterizer line
     */
    public static MultiRectArea rasterize(int x1, int y1, int x2, int y2, MultiRectArea clip, LineDasher dasher, boolean invert) {

        MultiRectArea dst = new MultiRectArea(false);
        int dx = x2 - x1;
        int dy = y2 - y1;

        // Point
        if (dx == 0 && dy == 0) {
            if ((clip == null || clip.contains(x1, y1)) && (dasher == null || dasher.visible)) {
                dst = new MultiRectArea(x1, y1, x1, y1);
            }
            return dst;
        }

        if (dy < 0) {
            return rasterize(x2, y2, x1, y1, clip, dasher, true);
        }

        Line line;
        if (dasher == null) {
            if (dx == 0) {
                line = new Line.Ortog.Ver(x1, y1, x2, y2, dst);
            } else
                if (dy == 0) {
                    line = new Line.Ortog.Hor(x1, y1, x2, y2, dst);
                } else {
                    if (dy < Math.abs(dx)) {
                        line = new Line.Diag.Hor(x1, y1, x2, y2, dst);
                    } else {
                        line = new Line.Diag.Ver(x1, y1, x2, y2, dst);
                    }
                }
        } else {
            if (dx == 0) {
                line = new Line.Ortog.VerDashed(x1, y1, x2, y2, dst, dasher, invert);
            } else
                if (dy == 0) {
                    line = new Line.Ortog.HorDashed(x1, y1, x2, y2, dst, dasher);
                } else {
                    if (dy < Math.abs(dx)) {
                        line = new Line.Diag.HorDashed(x1, y1, x2, y2, dst, dasher, invert);
                    } else {
                        line = new Line.Diag.VerDashed(x1, y1, x2, y2, dst, dasher, invert);
                    }
                }
        }


        if (clip == null || clip.isEmpty()) {
            line.rasterize();
        } else {
            for(int i = 1; i < clip.rect[0]; i += 4) {
                line.rasterize(clip.rect, i);
            }
        }

        return dst;
    }

}
