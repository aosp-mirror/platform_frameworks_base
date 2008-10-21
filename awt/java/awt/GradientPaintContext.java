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

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

class GradientPaintContext implements PaintContext {

    /**
     * The size of noncyclic part of color lookup table
     */
    static int LOOKUP_SIZE = 256;
    
    /**
     * The index mask to lookup color in the table
     */
    static int LOOKUP_MASK = 0x1FF;
    
    /**
     * The min value equivalent to zero. If absolute value less then ZERO it considered as zero.  
     */
    static double ZERO = 1E-10;

    /**
     * The ColorModel user defined for PaintContext
     */
    ColorModel cm;
    
    /**
     * The indicator of cycle filling.
     */
    boolean cyclic;
    
    /**
     * The integer color value of the start point
     */
    int c1;
    
    /**
     * The integer color value of the end point
     */
    int c2;
    
    /**
     * The lookup gradient color table 
     */
    int[] table;

    /**
     * The tempopary pre-calculated value to evalutae color index 
     */
    int dx;
    
    /**
     * The tempopary pre-calculated value to evalutae color index 
     */
    int dy;
    
    /**
     * The tempopary pre-calculated value to evalutae color index 
     */
    int delta;
    
    /**
     * Constructs a new GradientPaintcontext
     * @param cm - not used
     * @param t - the fill transformation
     * @param point1 - the start fill point
     * @param color1 - color of the start point 
     * @param point2 - the end fill point
     * @param color2 - color of the end point
     * @param cyclic - the indicator of cycle filling
     */
    GradientPaintContext(ColorModel cm, AffineTransform t, Point2D point1, Color color1, Point2D point2, Color color2, boolean cyclic) {
        this.cyclic = cyclic;
        this.cm = ColorModel.getRGBdefault();

        c1 = color1.getRGB();
        c2 = color2.getRGB();

        double px = point2.getX() - point1.getX();
        double py = point2.getY() - point1.getY();

        Point2D p = t.transform(point1, null);
        Point2D bx = new Point2D.Double(px, py);
        Point2D by = new Point2D.Double(py, -px);

        t.deltaTransform(bx, bx);
        t.deltaTransform(by, by);

        double vec = bx.getX() * by.getY() - bx.getY() * by.getX();

        if (Math.abs(vec) < ZERO) {
            dx = dy = delta = 0;
            table = new int[1];
            table[0] = c1;
        } else {
            double mult = LOOKUP_SIZE * 256 / vec;
            dx = (int)(by.getX() * mult);
            dy = (int)(by.getY() * mult);
            delta = (int)((p.getX() * by.getY() - p.getY() * by.getX()) * mult);
            createTable();
        }
    }

    /**
     * Create color index lookup table. Calculate 256 step trasformation from 
     * the start point color to the end point color. Colors multiplied by 256 to do integer calculations. 
     */
    void createTable() {
        double ca = (c1 >> 24) & 0xFF;
        double cr = (c1 >> 16) & 0xFF;
        double cg = (c1 >> 8) & 0xFF;
        double cb = c1 & 0xFF;

        double k = 1.0 / LOOKUP_SIZE;
        double da = (((c2 >> 24) & 0xFF) - ca) * k;
        double dr = (((c2 >> 16) & 0xFF) - cr) * k;
        double dg = (((c2 >> 8) & 0xFF) - cg) * k;
        double db = ((c2 & 0xFF) - cb) * k;

        table = new int[cyclic ? LOOKUP_SIZE + LOOKUP_SIZE : LOOKUP_SIZE];
        for(int i = 0; i < LOOKUP_SIZE; i++) {
            table[i] =
                (int)ca << 24 |
                (int)cr << 16 |
                (int)cg << 8 |
                (int)cb;
            ca += da;
            cr += dr;
            cg += dg;
            cb += db;
        }
        if (cyclic) {
            for(int i = 0; i < LOOKUP_SIZE; i++) {
                table[LOOKUP_SIZE + LOOKUP_SIZE - 1 - i] = table[i];
            }
        }
    }

    public ColorModel getColorModel() {
        return cm;
    }

    public void dispose() {
    }

    public Raster getRaster(int x, int y, int w, int h) {
        WritableRaster rast = cm.createCompatibleWritableRaster(w, h);

        int[] buf = ((DataBufferInt)rast.getDataBuffer()).getData();

        int c = x * dy - y * dx - delta;
        int cx = dy;
        int cy = - w * dy - dx;
        int k = 0;

        if (cyclic) {
            for(int j = 0; j < h; j++) {
                for(int i = 0; i < w; i++) {
                    buf[k++] = table[(c >> 8) & LOOKUP_MASK];
                    c += cx;
                }
                c += cy;
            }
        } else {
            for(int j = 0; j < h; j++) {
                for(int i = 0; i < w; i++) {
                    int index = c >> 8;
                    buf[k++] = index < 0 ? c1 : index >= LOOKUP_SIZE ? c2 : table[index];
                    c += cx;
                }
                c += cy;
            }
        }

        return rast;
    }

}

