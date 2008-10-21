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

public class MultiRectAreaOp {

    /**
     * Rectangle buffer capacity
     */
    public static final int RECT_CAPACITY = 16;
    
    /**
     * If number of rectangle in MultiRectArea object less than MAX_SIMPLE simple algorithm applies 
     */
    private static final int MAX_SIMPLE = 8;

    /**
     * Create buffer
     */
    public static int[] createBuf(int capacity) {
        if (capacity == 0) {
            capacity = RECT_CAPACITY;
        }
        int[] buf = new int[capacity];
        buf[0] = 1;
        return buf;
    }

    /**
     * Checks buffer size and reallocate if necessary  
     */
    public static int[] checkBufSize(int[] buf, int capacity) {
        if (buf[0] + capacity >= buf.length) {
            int length = buf[0] + (capacity > RECT_CAPACITY ? capacity : RECT_CAPACITY);
            int[] tmp = new int[length];
            System.arraycopy(buf, 0, tmp, 0, buf[0]);
            buf = tmp;
        }
        buf[0] += capacity;
        return buf;
    }

    /**
     * Region class provides basic functionlity for MultiRectArea objects to make logical operations 
     */
    static class Region {

        int[] region;
        int[] active;
        int[] bottom;
        int index;

        public Region(int[] region) {
            this.region = region;
            active = new int[RECT_CAPACITY];
            bottom = new int[RECT_CAPACITY];
            active[0] = 1;
            bottom[0] = 1;
            index = 1;
        }

        void addActive(int index) {
            int length = active[0];
            active = checkBufSize(active, 4);
            int i = 1;

            while(i < length) {
                if (region[index] < active[i]) {
                    // Insert
                    System.arraycopy(active, i, active, i + 4, length - i);
                    length = i;
                    break;
                }
                i += 4;
            }
            System.arraycopy(region, index, active, length, 4);

        }

        void findActive(int top, int bottom) {
            while(index < region[0]) {
                if (region[index + 1] > bottom) { // y1 > bottom
                    return;
                }
                if (region[index + 3] >= top) { // y2 >= top
                    addActive(index);
                }
                index += 4;
            }
        }

        void deleteActive(int bottom) {
            int length = active[0];
            for(int i = 1; i < length;) {
                if (active[i + 3] == bottom) {
                    length -= 4;
                    if (i < length) {
                        System.arraycopy(active, i + 4, active, i, length - i);
                    }
                } else {
                     i += 4;
                }
            }
            active[0] = length;
        }

        void deleteActive() {
            int length = active[0];
            for(int i = length - 4; i > 0; i -= 4) {
                if (active[i + 1] > active[i + 3]) {
                    length -= 4;
                    if (i < length) {
                        System.arraycopy(active, i + 4, active, i, length - i);
                    }
                }
            }
            active[0] = length;
        }

        void createLevel(int[] level) {
            int levelCount = 1;
            int topIndex = 1;
            int i = 1;
            while(i < region[0]) {

                int top = region[i + 1];
                int bottom = region[i + 3] + 1;
                int j = topIndex;

                addTop: {
                    while(j < levelCount) {
                        if (level[j] == top) {
                            break addTop;
                        }
                        if (level[j] > top) {
                            System.arraycopy(level, j, level, j + 1, levelCount - j);
                            break;
                        }
                        j++;
                    }

                    level[j] = top;
                    levelCount++;
                    topIndex = j;
                }

                addBottom: {
                    while(j < levelCount) {
                        if (level[j] == bottom) {
                            break addBottom;
                        }
                        if (level[j] > bottom) {
                            System.arraycopy(level, j, level, j + 1, levelCount - j);
                            break;
                        }
                        j++;
                    };

                    level[j] = bottom;
                    levelCount++;
                }

                i += 4;
            }
            level[0] = levelCount;
        }

        static void sortOrdered(int[] src1, int[] src2, int[] dst) {
            int length1 = src1[0];
            int length2 = src2[0];
            int count = 1;
            int i1 = 1;
            int i2 = 1;
            int v1 = src1[1];
            int v2 = src2[1];
            while(true) {

                LEFT: {
                    while(i1 < length1) {
                        v1 = src1[i1];
                        if (v1 >= v2) {
                            break LEFT;
                        }
                        dst[count++] = v1;
                        i1++;
                    }
                    while(i2 < length2) {
                        dst[count++] = src2[i2++];
                    }
                    dst[0] = count;
                    return;
                }

                RIGHT: {
                    while(i2 < length2) {
                        v2 = src2[i2];
                        if (v2 >= v1) {
                            break RIGHT;
                        }
                        dst[count++] = v2;
                        i2++;
                    }
                    while(i1 < length1) {
                        dst[count++] = src1[i1++];
                    }
                    dst[0] = count;
                    return;
                }

                if (v1 == v2) {
                    dst[count++] = v1;
                    i1++;
                    i2++;
                    if (i1 < length1) {
                        v1 = src1[i1];
                    }
                    if (i2 < length2 - 1) {
                        v2 = src2[i2];
                    }
                }
            }
            // UNREACHABLE
        }

    }

    /**
     * Intersection class provides intersection of two MultiRectAre aobjects
     */
    static class Intersection {

        static void intersectRegions(int[] reg1, int[] reg2, MultiRectArea.RectCash dst, int height1, int height2) {

            Region d1 = new Region(reg1);
            Region d2 = new Region(reg2);

            int[] level = new int[height1 + height2];
            int[] level1 = new int[height1];
            int[] level2 = new int[height2];
            d1.createLevel(level1);
            d2.createLevel(level2);
            Region.sortOrdered(level1, level2, level);

            int top;
            int bottom = level[1] - 1;
            for(int i = 2; i < level[0]; i++) {

                top = bottom + 1;
                bottom = level[i] - 1;

                d1.findActive(top, bottom);
                d2.findActive(top, bottom);

                int i1 = 1;
                int i2 = 1;

                while(i1 < d1.active[0] && i2 < d2.active[0]) {

                    int x11 = d1.active[i1];
                    int x12 = d1.active[i1 + 2];
                    int x21 = d2.active[i2];
                    int x22 = d2.active[i2 + 2];

                    if (x11 <= x21) {
                        if (x12 >= x21) {
                            if (x12 <= x22) {
                                dst.addRectCashed(x21, top, x12, bottom);
                                i1 += 4;
                            } else {
                                dst.addRectCashed(x21, top, x22, bottom);
                                i2 += 4;
                            }
                        } else {
                            i1 += 4;
                        }
                    } else {
                        if (x22 >= x11) {
                            if (x22 <= x12) {
                                dst.addRectCashed(x11, top, x22, bottom);
                                i2 += 4;
                            } else {
                                dst.addRectCashed(x11, top, x12, bottom);
                                i1 += 4;
                            }
                        } else {
                            i2 += 4;
                        }
                    }
                }

                d1.deleteActive(bottom);
                d2.deleteActive(bottom);
            }
        }

        static int[] simpleIntersect(MultiRectArea src1, MultiRectArea src2) {
            int[] rect1 = src1.rect;
            int[] rect2 = src2.rect;
            int[] rect = createBuf(0);

            int k = 1;
            for(int i = 1; i < rect1[0];) {

                int x11 = rect1[i++];
                int y11 = rect1[i++];
                int x12 = rect1[i++];
                int y12 = rect1[i++];

                for(int j = 1; j < rect2[0];) {

                    int x21 = rect2[j++];
                    int y21 = rect2[j++];
                    int x22 = rect2[j++];
                    int y22 = rect2[j++];

                    if (x11 <= x22 && x12 >= x21 &&
                        y11 <= y22 && y12 >= y21)
                    {
                        rect = checkBufSize(rect, 4);
                        rect[k++] = x11 > x21 ? x11 : x21;
                        rect[k++] = y11 > y21 ? y11 : y21;
                        rect[k++] = x12 > x22 ? x22 : x12;
                        rect[k++] = y12 > y22 ? y22 : y12;
                    }
                }
            }

            rect[0] = k;
            return rect;
        }

        public static MultiRectArea getResult(MultiRectArea src1, MultiRectArea src2) {

            if (src1 == null || src2 == null || src1.isEmpty() || src2.isEmpty()) {
                return new MultiRectArea();
            }

            MultiRectArea.RectCash dst = new MultiRectArea.RectCash();

            if (!src1.sorted || !src2.sorted || 
               src1.getRectCount() <= MAX_SIMPLE || src2.getRectCount() <= MAX_SIMPLE) 
            {
                dst.setRect(simpleIntersect(src1, src2), false);
            } else {
                Rectangle bounds1 = src1.getBounds();
                Rectangle bounds2 = src2.getBounds();
                Rectangle bounds3 = bounds1.intersection(bounds2);
                if (bounds3.width > 0 && bounds3.height > 0) {
                    intersectRegions(src1.rect, src2.rect, dst, bounds1.height + 2, bounds2.height + 2);
                }
            }

            return dst;
        }

    }

    /**
     * Union class provides union of two MultiRectAre aobjects
     */
    static class Union {

        int rx1, rx2;
        int top, bottom;
        MultiRectArea.RectCash dst;

        boolean next(Region d, int index) {
            int x1 = d.active[index];
            int x2 = d.active[index + 2];
            boolean res = false;

            if (x2 < rx1 - 1) {
                res = true;
                dst.addRectCashed(x1, top, x2, bottom);
            } else
                if (x1 > rx2 + 1) {
                    res = false;
                    dst.addRectCashed(rx1, top, rx2, bottom);
                    rx1 = x1;
                    rx2 = x2;
                } else {
                    res = x2 <= rx2;
                    rx1 = Math.min(x1, rx1);
                    rx2 = Math.max(x2, rx2);
                }

            // Top
            if (d.active[index + 1] < top) {
                dst.addRectCashed(x1, d.active[index + 1], x2, top - 1);
            }
            // Bottom
            if (d.active[index + 3] > bottom) {
                d.active[index + 1] = bottom + 1;
            }
            return res;
        }

        void check(Region d, int index, boolean t) {
            int x1 = d.active[index];
            int x2 = d.active[index + 2];
            // Top
            if (d.active[index + 1] < top) {
                dst.addRectCashed(x1, d.active[index + 1], x2, top - 1);
            }
            if (t) {
                dst.addRectCashed(x1, top, x2, bottom);
            }
            // Bottom
            if (d.active[index + 3] > bottom) {
                d.active[index + 1] = bottom + 1;
            }
        }

        void unionRegions(int[] reg1, int[] reg2, int height1, int height2) {
            Region d1 = new Region(reg1);
            Region d2 = new Region(reg2);

            int[] level = new int[height1 + height2];
            int[] level1 = new int[height1];
            int[] level2 = new int[height2];
            d1.createLevel(level1);
            d2.createLevel(level2);
            Region.sortOrdered(level1, level2, level);

            bottom = level[1] - 1;
            for(int i = 2; i < level[0]; i++) {

                top = bottom + 1;
                bottom = level[i] - 1;

                d1.findActive(top, bottom);
                d2.findActive(top, bottom);

                int i1 = 1;
                int i2 = 1;
                boolean res1, res2;

                if (d1.active[0] > 1) {
                    check(d1, 1, false);
                    rx1 = d1.active[1];
                    rx2 = d1.active[3];
                    i1 += 4;
                    res1 = false;
                    res2 = true;
                } else
                    if (d2.active[0] > 1) {
                        check(d2, 1, false);
                        rx1 = d2.active[1];
                        rx2 = d2.active[3];
                        i2 += 4;
                        res1 = true;
                        res2 = false;
                    } else {
                        continue;
                    }

            outer:
                while(true) {

                    while (res1) {
                        if (i1 >= d1.active[0]) {
                            dst.addRectCashed(rx1, top, rx2, bottom);
                            while(i2 < d2.active[0]) {
                                check(d2, i2, true);
                                i2 += 4;
                            }
                            break outer;
                        }
                        res1 = next(d1, i1);
                        i1 += 4;
                    }

                    while (res2) {
                        if (i2 >= d2.active[0]) {
                            dst.addRectCashed(rx1, top, rx2, bottom);
                            while(i1 < d1.active[0]) {
                                check(d1, i1, true);
                                i1 += 4;
                            }
                            break outer;
                        }
                        res2 = next(d2, i2);
                        i2 += 4;
                    }

                    res1 = true;
                    res2 = true;
                } // while

                d1.deleteActive(bottom);
                d2.deleteActive(bottom);

            }
        }

        static void simpleUnion(MultiRectArea src1, MultiRectArea src2, MultiRectArea dst) {
            if (src1.getRectCount() < src2.getRectCount()) {
                simpleUnion(src2, src1, dst);
            } else {
                Subtraction.simpleSubtract(src1, src2, dst);
                int pos = dst.rect[0];
                int size = src2.rect[0] - 1;
                dst.rect = checkBufSize(dst.rect, size);
                System.arraycopy(src2.rect,1, dst.rect, pos, size);
                dst.resort();
            }
        }

        MultiRectArea getResult(MultiRectArea src1, MultiRectArea src2) {

            if (src1 == null || src1.isEmpty()) {
                return new MultiRectArea(src2);
            }

            if (src2 == null || src2.isEmpty()) {
                return new MultiRectArea(src1);
            }

            dst = new MultiRectArea.RectCash();

            if (!src1.sorted || !src2.sorted ||
               src1.getRectCount() <= MAX_SIMPLE || src2.getRectCount() <= MAX_SIMPLE) 
            {
                simpleUnion(src1, src2, dst);
            } else {
                Rectangle bounds1 = src1.getBounds();
                Rectangle bounds2 = src2.getBounds();
                Rectangle bounds3 = bounds1.intersection(bounds2);

                if (bounds3.width < 0 || bounds3.height < 0) {
                    if (bounds1.y + bounds1.height < bounds2.y) {
                        dst.setRect(addVerRegion(src1.rect, src2.rect), false);
                    } else
                        if (bounds2.y + bounds2.height < bounds1.y) {
                            dst.setRect(addVerRegion(src2.rect, src1.rect), false);
                        } else
                            if (bounds1.x < bounds2.x) {
                                dst.setRect(addHorRegion(src1.rect, src2.rect), false);
                            } else {
                                dst.setRect(addHorRegion(src2.rect, src1.rect), false);
                            }
                } else {
                    unionRegions(src1.rect, src2.rect, bounds1.height + 2, bounds2.height + 2);
                }
            }

            return dst;
        }

        int[] addVerRegion(int[] top, int[] bottom) {
            int length = top[0] + bottom[0] - 1;
            int[] dst = new int[length];
            dst[0] = length;
            System.arraycopy(top, 1, dst, 1, top[0] - 1);
            System.arraycopy(bottom, 1, dst, top[0], bottom[0] - 1);
            return dst;
        }

        int[] addHorRegion(int[] left, int[] right) {
            int count1 = left[0];
            int count2 = right[0];
            int[] dst = new int[count1 + count2 + 1];
            int count = 1;
            int index1 = 1;
            int index2 = 1;

            int top1 = left[2];
            int top2 = right[2];
            int pos1, pos2;

            while(true) {

                if (index1 >= count1) {
                    System.arraycopy(right, index2, dst, count, count2 - index2);
                    count += count2 - index2;
                    break;
                }
                if (index2 >= count2) {
                    System.arraycopy(left, index1, dst, count, count1 - index1);
                    count += count1 - index1;
                    break;
                }

                if (top1 < top2) {
                    pos1 = index1;
                    do {
                        index1 += 4;
                    } while (index1 < count1 && (top1 = left[index1 + 1]) < top2);
                    System.arraycopy(left, pos1, dst, count, index1 - pos1);
                    count += index1 - pos1;
                    continue;
                }

                if (top1 > top2) {
                    pos2 = index2;
                    do {
                        index2 += 4;
                    } while (index2 < count2 && (top2 = right[index2 + 1]) < top1);
                    System.arraycopy(right, pos2, dst, count, index2 - pos2);
                    count += index2 - pos2;
                    continue;
                }

                int top = top1;
                pos1 = index1;
                pos2 = index2;
                do  {
                    index1 += 4;
                } while(index1 < count1 && (top1 = left[index1 + 1]) == top);
                do {
                    index2 += 4;
                } while(index2 < count2 && (top2 = right[index2 + 1]) == top);

                System.arraycopy(left, pos1, dst, count, index1 - pos1);
                count += index1 - pos1;
                System.arraycopy(right, pos2, dst, count, index2 - pos2);
                count += index2 - pos2;
            }

            dst[0] = count;
            return dst;
        }

    }

    /**
     * Subtraction class provides subtraction of two MultiRectAre aobjects
     */
    static class Subtraction {

        static void subtractRegions(int[] reg1, int[] reg2, MultiRectArea.RectCash dst, int height1, int height2) {
            Region d1 = new Region(reg1);
            Region d2 = new Region(reg2);

            int[] level = new int[height1 + height2];
            int[] level1 = new int[height1];
            int[] level2 = new int[height2];
            d1.createLevel(level1);
            d2.createLevel(level2);
            Region.sortOrdered(level1, level2, level);

            int top;
            int bottom = level[1] - 1;
            for(int i = 2; i < level[0]; i++) {

                top = bottom + 1;
                bottom = level[i] - 1;

                d1.findActive(top, bottom);
                if (d1.active[0] == 1) {
                    d2.deleteActive(bottom);
                    continue;
                }

                d2.findActive(top, bottom);

                int i1 = 1;
                int i2 = 1;

                int rx1 = 0;
                int rx2 = 0;

                boolean next = true;

                while(true) {

                    if (next) {
                        next = false;
                        if (i1 >= d1.active[0]) {
                            break;
                        }
                        // Bottom
                        d1.active[i1 + 1] = bottom + 1;
                        rx1 = d1.active[i1];
                        rx2 = d1.active[i1 + 2];
                        i1 += 4;
                    }

                    if (i2 >= d2.active[0]) {
                        dst.addRectCashed(rx1, top, rx2, bottom);
                        for(int j = i1; j < d1.active[0]; j += 4) {
                            dst.addRectCashed(d1.active[j], top, d1.active[j + 2], bottom);
                            d1.active[j + 1] = bottom + 1;
                        }
                        break;
                    }

                    int x1 = d2.active[i2];
                    int x2 = d2.active[i2 + 2];

                    if (rx1 < x1) {
                        if (rx2 >= x1) {
                            if (rx2 <= x2) {
                                //  [-----------]
                                //       [-------------]
                                dst.addRectCashed(rx1, top, x1 - 1, bottom);
                                next = true;
                            } else {
                                // [-----------------]
                                //      [------]
                                dst.addRectCashed(rx1, top, x1 - 1, bottom);
                                rx1 = x2 + 1;
                                i2 += 4;
                            }
                        } else {
                            // [-----]
                            //         [----]
                            dst.addRectCashed(rx1, top, rx2, bottom);
                            next = true;
                        }
                    } else {
                        if (rx1 <= x2) {
                            if (rx2 <= x2) {
                                //    [------]
                                //  [-----------]
                                next = true;
                            } else {
                                //     [------------]
                                // [---------]
                                rx1 = x2 + 1;
                                i2 += 4;
                            }
                        } else {
                            //         [----]
                            // [-----]
                            i2 += 4;
                        }
                    }

                }
                d1.deleteActive();
                d2.deleteActive(bottom);
            }
        }

        static void subtractRect(int x11, int y11, int x12, int y12, int[] rect, int index, MultiRectArea dst) {

            for(int i = index; i < rect[0]; i += 4) {
                int x21 = rect[i + 0];
                int y21 = rect[i + 1];
                int x22 = rect[i + 2];
                int y22 = rect[i + 3];

                if (x11 <= x22 && x12 >= x21 && y11 <= y22 && y12 >= y21) {
                    int top, bottom;
                    if (y11 < y21) {
                        subtractRect(x11, y11, x12, y21 - 1, rect, i + 4, dst);
                        top = y21;
                    } else {
                        top = y11;
                    }
                    if (y12 > y22) {
                        subtractRect(x11, y22 + 1, x12, y12, rect, i + 4, dst);
                        bottom = y22;
                    } else {
                        bottom = y12;
                    }
                    if (x11 < x21) {
                        subtractRect(x11, top, x21 - 1, bottom, rect, i + 4, dst);
                    }
                    if (x12 > x22) {
                        subtractRect(x22 + 1, top, x12, bottom, rect, i + 4, dst);
                    }
                    return;
                }
            }
            dst.addRect(x11, y11, x12, y12);
        }

        static void simpleSubtract(MultiRectArea src1, MultiRectArea src2, MultiRectArea dst) {
            for(int i = 1; i < src1.rect[0]; i += 4) {
                subtractRect(
                        src1.rect[i + 0],
                        src1.rect[i + 1],
                        src1.rect[i + 2],
                        src1.rect[i + 3],
                        src2.rect,
                        1,
                        dst);
            }
            dst.resort();
        }

        public static MultiRectArea getResult(MultiRectArea src1, MultiRectArea src2) {

            if (src1 == null || src1.isEmpty()) {
                return new MultiRectArea();
            }

            if (src2 == null || src2.isEmpty()) {
                return new MultiRectArea(src1);
            }

            MultiRectArea.RectCash dst = new MultiRectArea.RectCash();

            if (!src1.sorted || !src2.sorted ||
               src1.getRectCount() <= MAX_SIMPLE || src2.getRectCount() <= MAX_SIMPLE) 
            {
                simpleSubtract(src1, src2, dst);
            } else {
                Rectangle bounds1 = src1.getBounds();
                Rectangle bounds2 = src2.getBounds();
                Rectangle bounds3 = bounds1.intersection(bounds2);

                if (bounds3.width > 0 && bounds3.height > 0) {
                    subtractRegions(src1.rect, src2.rect, dst, bounds1.height + 2, bounds2.height + 2);
                } else {
                    dst.setRect(src1.rect, true);
                }
            }

            return dst;
        }

    }

}
