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
 * @author Ilya S. Okomin
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.render;

import java.awt.*;
import java.awt.image.*;


import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

import org.apache.harmony.awt.gl.TextRenderer;
import org.apache.harmony.awt.gl.font.CommonGlyphVector;
import org.apache.harmony.awt.gl.font.FontPeerImpl;
import org.apache.harmony.awt.gl.font.Glyph;
import org.apache.harmony.awt.gl.image.BufferedImageGraphics2D;

public class JavaTextRenderer extends TextRenderer {

    public static final JavaTextRenderer inst = new JavaTextRenderer();

    @Override
    public void drawGlyphVector(Graphics2D g, GlyphVector glyphVector,
            float x, float y) {

        AffineTransform at = g.getTransform();
        Rectangle c = g.getClipBounds();
        if (at != null){
            int atType = at.getType();
            if (atType == AffineTransform.TYPE_TRANSLATION) {
                c.translate((int)Math.round(at.getTranslateX()), (int)Math.round(at.getTranslateY()));
            }
        }

        WritableRaster wr = ((BufferedImageGraphics2D)g).getWritableRaster();
        ColorModel cm = ((BufferedImageGraphics2D)g).getColorModel();

        Rectangle rBounds = wr.getBounds();

        Object color = cm.getDataElements(g.getColor().getRGB(), null);

        drawClipGlyphVector(wr, color, glyphVector, (int)Math.round(x + at.getTranslateX()), (int)Math.round(y + at.getTranslateY()),
        Math.max(c.x,rBounds.x),
        Math.max(c.y,rBounds.y),
        Math.min((int)Math.round(c.getMaxX()), (int)Math.round(rBounds.getMaxX())),
        Math.min((int)Math.round(c.getMaxY()), (int)Math.round(rBounds.getMaxY())));

    }

    @SuppressWarnings("deprecation")
    @Override
    public void drawString(Graphics2D g, String str, float x, float y) {
        AffineTransform at = g.getTransform();
        Rectangle c = g.getClipBounds();
        if (at != null){
            int atType = at.getType();
            if (atType == AffineTransform.TYPE_TRANSLATION) {
                c.translate((int)Math.round(at.getTranslateX()), (int)Math.round(at.getTranslateY()));
            }
        }
        WritableRaster wr = ((BufferedImageGraphics2D)g).getWritableRaster();
        ColorModel cm = ((BufferedImageGraphics2D)g).getColorModel();
        Rectangle rBounds = wr.getBounds();

        Object color = cm.getDataElements(g.getColor().getRGB(), null);

        drawClipString(wr, color, str, (FontPeerImpl) (g.getFont().getPeer()),
                (int)Math.round(x + at.getTranslateX()), (int)Math.round(y + at.getTranslateY()),
                Math.max(c.x,rBounds.x),
                Math.max(c.y,rBounds.y),
                Math.min((int)Math.round(c.getMaxX()), (int)Math.round(rBounds.getMaxX())),
                Math.min((int)Math.round(c.getMaxY()), (int)Math.round(rBounds.getMaxY())));

    }

    /**
     * 
     * Draws string on specified raster at desired position.
     *  
     * @param raster specified WritableRaster to draw at
     * @param color color of the text
     * @param glyphVector GlyphVector object to draw
     * @param x start X position to draw
     * @param y start Y position to draw
     * @param cMinX minimum x of the raster area to draw
     * @param cMinY minimum y of the raster area to draw
     * @param cMaxX maximum x of the raster area to draw
     * @param cMaxY maximum y of the raster area to draw
     */
    public void drawClipGlyphVector(WritableRaster raster, Object color,
            GlyphVector glyphVector, int x, int y,
            int cMinX, int cMinY, int cMaxX, int cMaxY) {
        // TODO: implement complex clipping

        int xSrcSurf, ySrcSurf; // Start point in String rectangle
        int xDstSurf, yDstSurf; // Start point in Surface rectangle
        int clWidth, clHeight;

        for (int i = 0; i < glyphVector.getNumGlyphs(); i++) {
            Glyph gl = ((CommonGlyphVector) glyphVector).vector[i];

            if (gl.getPointWidth() == 0) {
                continue;
            }

            byte[] data = gl.getBitmap();
            if (data != null) {
                Point2D pos = glyphVector.getGlyphPosition(i);

                xSrcSurf = 0;//gl.bmp_left;
                ySrcSurf = 0;//gl.bmp_rows - gl.bmp_top;

                xDstSurf = x + (int)pos.getX() + (int) gl.getGlyphPointMetrics().getLSB();// + gl.bmp_left;
                yDstSurf = y - gl.bmp_top/*getPointHeight()*/  + (int) pos.getY();// - (gl.bmp_rows-gl.bmp_top);

                int textWidth = gl.bmp_width;
                int textHeight = gl.getPointHeight();

                // if Regions don't intersect
                if ((xDstSurf > cMaxX) || (yDstSurf > cMaxY) || (xDstSurf + textWidth < cMinX)
                        || (yDstSurf + textHeight < cMinY)) {
                    // Nothing to do
                } else {
                    if (xDstSurf >= cMinX) {
                        clWidth = Math.min(textWidth, cMaxX - xDstSurf);
                    } else {
                        xSrcSurf += cMinX - xDstSurf;
                        clWidth = Math.min(cMaxX - cMinX, textWidth - (cMinX - xDstSurf));
                        xDstSurf = cMinX;
                    }
                    if (yDstSurf >= cMinY) {
                        clHeight = Math.min(textHeight, cMaxY - yDstSurf);
                    } else {
                        ySrcSurf += cMinY - yDstSurf;
                        clHeight = Math.min(cMaxY - cMinY, textHeight - (cMinY - yDstSurf));
                        yDstSurf = cMinY;
                    }
                    //     Drawing on the Raster
                    for (int h=0; h<clHeight; h++){
                        for (int w=0; w < clWidth ; w++) {
                            byte currByte = data[(ySrcSurf + h)*gl.bmp_pitch + (xSrcSurf+w)/8];
                            boolean emptyByte = ((currByte & (1 << (7 - ((xSrcSurf+w) % 8)))) != 0);
                            if (emptyByte) {
                                raster.setDataElements(xDstSurf+w, yDstSurf+h, color);
                            } else {
                                // Nothing to do
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Draws string on specified raster at desired position.
     *  
     * @param raster specified WritableRaster to draw at
     * @param color color of the text
     * @param str text to draw
     * @param font font peer to use for drawing text
     * @param x start X position to draw
     * @param y start Y position to draw
     * @param cMinX minimum x of the raster area to draw
     * @param cMinY minimum y of the raster area to draw
     * @param cMaxX maximum x of the raster area to draw
     * @param cMaxY maximum y of the raster area to draw
     */    
    public void drawClipString(WritableRaster raster, Object color, String str,
            FontPeerImpl font, int x, int y, int cMinX, int cMinY, int cMaxX,
            int cMaxY) {
        // TODO: implement complex clipping

        int xSrcSurf, ySrcSurf; // Start point in String rectangle
        int xDstSurf, yDstSurf; // Start point in Surface rectangle
        int clWidth, clHeight;

        char[] chars = str.toCharArray();

        int xBaseLine = x;
        int yBaseLine = y;

        for (char element : chars) {
            Glyph gl = font.getGlyph(element);
            GlyphMetrics pointMetrics = gl.getGlyphPointMetrics();
            if (gl.getWidth() == 0) {
                xBaseLine += pointMetrics.getAdvanceX();
                continue;
            }

            byte[] data = gl.getBitmap();
            if (data == null) {
                xBaseLine += pointMetrics.getAdvanceX();
            } else {

                xSrcSurf = 0;
                ySrcSurf = 0;

                xDstSurf = Math.round(xBaseLine + gl.getGlyphPointMetrics().getLSB());
                yDstSurf = yBaseLine - gl.bmp_top;

                int textWidth = gl.bmp_width;
                int textHeight = gl.getPointHeight();

                // if Regions don't intersect
                if ((xDstSurf > cMaxX) || (yDstSurf > cMaxY) || (xDstSurf + textWidth < cMinX)
                        || (yDstSurf + textHeight < cMinY)) {
                    // Nothing to do
                } else {
                    if (xDstSurf >= cMinX) {
                        clWidth = Math.min(textWidth, cMaxX - xDstSurf);
                    } else {
                        xSrcSurf += cMinX - xDstSurf;
                        clWidth = Math.min(cMaxX - cMinX, textWidth - (cMinX - xDstSurf));
                        xDstSurf = cMinX;
                    }
                    if (yDstSurf >= cMinY) {
                        clHeight = Math.min(textHeight, cMaxY - yDstSurf);
                    } else {
                        ySrcSurf += cMinY - yDstSurf;
                        clHeight = Math.min(cMaxY - cMinY, textHeight - (cMinY - yDstSurf));
                        yDstSurf = cMinY;
                    }

                    // Drawing on the Raster
                    for (int h=0; h<clHeight; h++){
                        for (int w=0; w < clWidth ; w++) {
                            byte currByte = data[(ySrcSurf + h)*gl.bmp_pitch + (xSrcSurf+w)/8];
                            boolean emptyByte = ((currByte & (1 << (7 - ((xSrcSurf+w) % 8)))) != 0);
                            if (emptyByte) {
                                raster.setDataElements(xDstSurf+w, yDstSurf+h, color);
                            } else {
                                // Nothing to do
                            }
                        }
                    }
                }
                xBaseLine += pointMetrics.getAdvanceX();
            }
        }
    }

}