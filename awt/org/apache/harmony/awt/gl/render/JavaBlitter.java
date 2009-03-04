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
 * @author Igor V. Stolyarov
 * @version $Revision$
 * Created on 18.11.2005
 *
 */
package org.apache.harmony.awt.gl.render;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.XORComposite;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * Java implenetation of the Blitter interface. Using when we can't 
 * draw images natively.
 */
public class JavaBlitter implements Blitter {

    /**
     * Instead of multiplication and division we are using values from
     * Lookup tables.
     */
    static byte mulLUT[][]; // Lookup table for multiplication
    static byte divLUT[][]; // Lookup table for division

    static{
        mulLUT = new byte[256][256];
        for(int i = 0; i < 256; i++){
            for(int j = 0; j < 256; j++){
                mulLUT[i][j] = (byte)((float)(i * j)/255 + 0.5f);
            }
        }
        divLUT = new byte[256][256];
        for(int i = 1; i < 256; i++){
            for(int j = 0; j < i; j++){
                divLUT[i][j] = (byte)(((float)j / 255) / ((float)i/ 255) * 255 + 0.5f);
            }
            for(int j = i; j < 256; j++){
                divLUT[i][j] = (byte)255;
            }
        }
    }

    final static int AlphaCompositeMode = 1;
    final static int XORMode = 2;

    final static JavaBlitter inst = new JavaBlitter();

    public static JavaBlitter getInstance(){
        return inst;
    }

    public void blit(int srcX, int srcY, Surface srcSurf, int dstX, int dstY,
            Surface dstSurf, int width, int height, AffineTransform sysxform,
            AffineTransform xform, Composite comp, Color bgcolor,
            MultiRectArea clip) {

        if(xform == null){
            blit(srcX, srcY, srcSurf, dstX, dstY, dstSurf, width, height,
                    sysxform, comp, bgcolor, clip);
        }else{
            double scaleX = xform.getScaleX();
            double scaleY = xform.getScaleY();
            double scaledX = dstX / scaleX;
            double scaledY = dstY / scaleY;
            AffineTransform at = new AffineTransform();
            at.setToTranslation(scaledX, scaledY);
            xform.concatenate(at);
            sysxform.concatenate(xform);
            blit(srcX, srcY, srcSurf, 0, 0, dstSurf, width, height,
                    sysxform, comp, bgcolor, clip);
        }

    }

    public void blit(int srcX, int srcY, Surface srcSurf, int dstX, int dstY,
            Surface dstSurf, int width, int height, AffineTransform sysxform,
            Composite comp, Color bgcolor, MultiRectArea clip) {

        if(sysxform == null) {
            sysxform = new AffineTransform();
        }
        int type = sysxform.getType();
        switch(type){
            case AffineTransform.TYPE_TRANSLATION:
                dstX += sysxform.getTranslateX();
                dstY += sysxform.getTranslateY();
            case AffineTransform.TYPE_IDENTITY:
                 blit(srcX, srcY, srcSurf, dstX, dstY, dstSurf,
                        width, height, comp, bgcolor, clip);
                break;
            default:
                int srcW = srcSurf.getWidth();
                int srcH = srcSurf.getHeight();

                int w = srcX + width < srcW ? width : srcW - srcX;
                int h = srcY + height < srcH ? height : srcH - srcY;

                ColorModel srcCM = srcSurf.getColorModel();
                Raster srcR = srcSurf.getRaster().createChild(srcX, srcY,
                        w, h, 0, 0, null);

                ColorModel dstCM = dstSurf.getColorModel();
                WritableRaster dstR = dstSurf.getRaster();

                transformedBlit(srcCM, srcR, 0, 0, dstCM, dstR, dstX, dstY, w, h,
                        sysxform, comp, bgcolor, clip);

        }
    }

    public void blit(int srcX, int srcY, Surface srcSurf, int dstX, int dstY,
            Surface dstSurf, int width, int height, Composite comp,
            Color bgcolor, MultiRectArea clip) {

        javaBlt(srcX, srcY, srcSurf.getWidth(), srcSurf.getHeight(),
                srcSurf.getColorModel(), srcSurf.getRaster(), dstX, dstY,
                dstSurf.getWidth(), dstSurf.getHeight(),
                dstSurf.getColorModel(), dstSurf.getRaster(),
                width, height, comp, bgcolor, clip);

    }
    public void javaBlt(int srcX, int srcY, int srcW, int srcH,
            ColorModel srcCM, Raster srcRast, int dstX, int dstY,
            int dstW, int dstH, ColorModel dstCM, WritableRaster dstRast,
            int width, int height, Composite comp, Color bgcolor,
            MultiRectArea clip){

        int srcX2 = srcW - 1;
        int srcY2 = srcH - 1;
        int dstX2 = dstW - 1;
        int dstY2 = dstH - 1;

        if(srcX < 0){
            width += srcX;
            srcX = 0;
        }
        if(srcY < 0){
            height += srcY;
            srcY = 0;
        }

        if(dstX < 0){
            width += dstX;
            srcX -= dstX;
            dstX = 0;
        }
        if(dstY < 0){
            height += dstY;
            srcY -= dstY;
            dstY = 0;
        }

        if(srcX > srcX2 || srcY > srcY2) {
            return;
        }
        if(dstX > dstX2 || dstY > dstY2) {
            return;
        }

        if(srcX + width > srcX2) {
            width = srcX2 - srcX + 1;
        }
        if(srcY + height > srcY2) {
            height = srcY2 - srcY + 1;
        }
        if(dstX + width > dstX2) {
            width = dstX2 - dstX + 1;
        }
        if(dstY + height > dstY2) {
            height = dstY2 - dstY + 1;
        }

        if(width <= 0 || height <= 0) {
            return;
        }

        int clipRects[];
        if(clip != null) {
            clipRects = clip.rect;
        } else {
            clipRects = new int[]{5, 0, 0, dstW - 1, dstH - 1};
        }

        boolean isAlphaComp = false;
        int rule = 0;
        float alpha = 0;
        boolean isXORComp = false;
        Color xorcolor = null;
        CompositeContext cont = null;

        if(comp instanceof AlphaComposite){
            isAlphaComp = true;
            AlphaComposite ac = (AlphaComposite) comp;
            rule = ac.getRule();
            alpha = ac.getAlpha();
        }else if(comp instanceof XORComposite){
            isXORComp = true;
            XORComposite xcomp = (XORComposite) comp;
            xorcolor = xcomp.getXORColor();
        }else{
            cont = comp.createContext(srcCM, dstCM, null);
        }

        for(int i = 1; i < clipRects[0]; i += 4){
            int _sx = srcX;
            int _sy = srcY;

            int _dx = dstX;
            int _dy = dstY;

            int _w = width;
            int _h = height;

            int cx = clipRects[i];          // Clipping left top X
            int cy = clipRects[i + 1];      // Clipping left top Y
            int cx2 = clipRects[i + 2];     // Clipping right bottom X
            int cy2 = clipRects[i + 3];     // Clipping right bottom Y

            if(_dx > cx2 || _dy > cy2 || dstX2 < cx || dstY2 < cy) {
                continue;
            }

            if(cx > _dx){
                int shx = cx - _dx;
                _w -= shx;
                _dx = cx;
                _sx += shx;
            }

            if(cy > _dy){
                int shy = cy - _dy;
                _h -= shy;
                _dy = cy;
                _sy += shy;
            }

            if(_dx + _w > cx2 + 1){
                _w = cx2 - _dx + 1;
            }

            if(_dy + _h > cy2 + 1){
                _h = cy2 - _dy + 1;
            }

            if(_sx > srcX2 || _sy > srcY2) {
                continue;
            }

            if(isAlphaComp){
                alphaCompose(_sx, _sy, srcCM, srcRast, _dx, _dy,
                        dstCM, dstRast, _w, _h, rule, alpha, bgcolor);
            }else if(isXORComp){
                xorCompose(_sx, _sy, srcCM, srcRast, _dx, _dy,
                        dstCM, dstRast, _w, _h, xorcolor);
            }else{
                Raster sr = srcRast.createChild(_sx, _sy, _w, _h, 0, 0, null);
                WritableRaster dr = dstRast.createWritableChild(_dx, _dy,
                        _w, _h, 0, 0, null);
                cont.compose(sr, dr, dr);
            }
        }
    }

    void alphaCompose(int srcX, int srcY, ColorModel srcCM, Raster srcRast,
            int dstX, int dstY, ColorModel dstCM, WritableRaster dstRast,
            int width, int height, int rule, float alpha, Color bgcolor){

        Object srcPixel, dstPixel;
        int srcConstAllpha = (int)(alpha * 255 + 0.5f);
        int srcRGB, dstRGB = 0;

        if(bgcolor != null){
            dstRGB = bgcolor.getRGB();
        }

        for(int sy = srcY, dy = dstY, srcYMax = srcY + height; sy < srcYMax; sy++, dy++){
            for(int sx = srcX, dx = dstX, srcXMax = srcX + width; sx < srcXMax; sx++, dx++){
                srcPixel = srcRast.getDataElements(sx, sy, null);
                srcRGB = srcCM.getRGB(srcPixel);
                if(bgcolor == null){
                    dstPixel = dstRast.getDataElements(dx, dy, null);
                    dstRGB = dstCM.getRGB(dstPixel);
                }

                dstRGB = compose(srcRGB, srcCM.isAlphaPremultiplied(),
                        dstRGB, dstCM.hasAlpha(), dstCM.isAlphaPremultiplied(),
                        rule, srcConstAllpha);

                dstPixel = dstCM.getDataElements(dstRGB, null);
                dstRast.setDataElements(dx,dy,dstPixel);
            }
        }
    }

    void xorCompose(int srcX, int srcY, ColorModel srcCM, Raster srcRast,
            int dstX, int dstY, ColorModel dstCM, WritableRaster dstRast,
            int width, int height, Color xorcolor){

        Object srcPixel, dstPixel;
        int xorRGB = xorcolor.getRGB();
        int srcRGB, dstRGB;

        for(int sy = srcY, dy = dstY, srcYMax = srcY + height; sy < srcYMax; sy++, dy++){
            for(int sx = srcX, dx = dstX, srcXMax = srcX + width; sx < srcXMax; sx++, dx++){
                srcPixel = srcRast.getDataElements(sx, sy, null);
                dstPixel = dstRast.getDataElements(dx, dy, null);

                srcRGB = srcCM.getRGB(srcPixel);
                dstRGB = dstCM.getRGB(dstPixel);
                dstRGB = srcRGB ^ xorRGB ^ dstRGB;

                dstRGB = 0xff000000 | dstRGB;
                dstPixel = dstCM.getDataElements(dstRGB, dstPixel);
                dstRast.setDataElements(dx,dy,dstPixel);

            }
        }

    }

    private void transformedBlit(ColorModel srcCM, Raster srcR, int srcX, int srcY,
            ColorModel dstCM, WritableRaster dstR, int dstX, int dstY,
            int width, int height, AffineTransform at, Composite comp,
            Color bgcolor,MultiRectArea clip) {

        Rectangle srcBounds = new Rectangle(width, height);
        Rectangle dstBlitBounds = new Rectangle(dstX, dstY, srcR.getWidth(), srcR.getHeight());

        Rectangle transSrcBounds = getBounds2D(at, srcBounds).getBounds();
        Rectangle transDstBlitBounds = getBounds2D(at, dstBlitBounds).getBounds();

        int translateX = transDstBlitBounds.x - transSrcBounds.x;
        int translateY = transDstBlitBounds.y - transSrcBounds.y;

        AffineTransform inv = null;
        try {
             inv = at.createInverse();
        } catch (NoninvertibleTransformException e) {
            return;
        }

        double[] m = new double[6];
        inv.getMatrix(m);

        int clipRects[];
        if(clip != null) {
            clipRects = clip.rect;
        } else {
            clipRects = new int[]{5, 0, 0, dstR.getWidth(), dstR.getHeight()};
        }

        int compType = 0;
        int srcConstAlpha = 0;
        int rule = 0;
        int bgRGB = bgcolor == null ? 0 : bgcolor.getRGB();
        int srcRGB = 0, dstRGB = 0;
        Object srcVal = null, dstVal = null;
        if(comp instanceof AlphaComposite){
            compType = AlphaCompositeMode;
            AlphaComposite ac = (AlphaComposite) comp;
            rule = ac.getRule();
            srcConstAlpha = (int)(ac.getAlpha() * 255 + 0.5f);
        }else if(comp instanceof XORComposite){
            compType = XORMode;
            XORComposite xor = (XORComposite) comp;
            bgRGB = xor.getXORColor().getRGB();
        }

        for(int i = 1; i < clipRects[0]; i += 4){
            Rectangle dstBounds = new Rectangle(clipRects[i], clipRects[i + 1], 0, 0);
            dstBounds.add(clipRects[i + 2] + 1, clipRects[i + 1]);
            dstBounds.add(clipRects[i + 2] + 1, clipRects[i + 3] + 1);
            dstBounds.add(clipRects[i], clipRects[i + 3] + 1);

            Rectangle bounds = dstBounds.intersection(transDstBlitBounds);

            int minSrcX = srcBounds.x;
            int minSrcY = srcBounds.y;
            int maxSrcX = minSrcX + srcBounds.width;
            int maxSrcY = minSrcY + srcBounds.height;

            int minX = bounds.x;
            int minY = bounds.y;
            int maxX = minX + bounds.width;
            int maxY = minY + bounds.height;

            int hx = (int)((m[0] * 256) + 0.5);
            int hy = (int)((m[1] * 256) + 0.5);
            int vx = (int)((m[2] * 256) + 0.5);
            int vy = (int)((m[3] * 256) + 0.5);
            int sx = (int)((m[4] + m[0] * (bounds.x - translateX) + m[2] * (bounds.y - translateY)) * 256 + 0.5);
            int sy = (int)((m[5] + m[1] * (bounds.x - translateX) + m[3] * (bounds.y - translateY)) * 256 + 0.5);

            vx -= hx * bounds.width;
            vy -= hy * bounds.width;

            for(int y = minY; y < maxY; y++) {
                for(int x = minX; x < maxX; x++) {
                    int px = sx >> 8;
                    int py = sy >> 8;
                    if (px >= minSrcX && py >= minSrcY && px < maxSrcX && py < maxSrcY) {
                        switch(compType){
                            case AlphaCompositeMode:
                                srcVal = srcR.getDataElements(px , py , null);
                                srcRGB = srcCM.getRGB(srcVal);
                                if(bgcolor != null){
                                    dstRGB = bgRGB;
                                }else{
                                    dstVal = dstR.getDataElements(x, y, null);
                                    dstRGB = dstCM.getRGB(dstVal);
                                }
                                dstRGB = compose(srcRGB, srcCM.isAlphaPremultiplied(),
                                        dstRGB, dstCM.hasAlpha(), dstCM.isAlphaPremultiplied(),
                                        rule, srcConstAlpha);
                                dstVal = dstCM.getDataElements(dstRGB, null);
                                dstR.setDataElements(x, y, dstVal);
                                break;

                            case XORMode:
                                srcVal = srcR.getDataElements(px , py , null);
                                srcRGB = srcCM.getRGB(srcVal);
                                dstVal = dstR.getDataElements(x, y, null);
                                dstRGB = dstCM.getRGB(dstVal);
                                dstRGB = srcRGB ^ bgRGB;

                                dstRGB = 0xff000000 | dstRGB;
                                dstVal = dstCM.getDataElements(dstRGB, null);
                                dstR.setDataElements(x, y, dstVal);
                                break;

                            default:
                                // awt.37=Unknown  composite type {0}
                                throw new IllegalArgumentException(Messages.getString("awt.37", //$NON-NLS-1$
                                        comp.getClass()));
                        }
                    }
                    sx += hx;
                    sy += hy;
                }
                sx += vx;
                sy += vy;
            }
        }

    }

    private Rectangle2D getBounds2D(AffineTransform at, Rectangle r) {
        int x = r.x;
        int y = r.y;
        int width = r.width;
        int height = r.height;

        float[] corners = {
            x, y,
            x + width, y,
            x + width, y + height,
            x, y + height
        };

        at.transform(corners, 0, corners, 0, 4);

        Rectangle2D.Float bounds = new Rectangle2D.Float(corners[0], corners[1], 0 , 0);
        bounds.add(corners[2], corners[3]);
        bounds.add(corners[4], corners[5]);
        bounds.add(corners[6], corners[7]);

        return bounds;
    }

    private int compose(int srcRGB, boolean isSrcAlphaPre,
            int dstRGB, boolean dstHasAlpha, boolean isDstAlphaPre,
            int rule, int srcConstAlpha){

        int sa, sr, sg, sb, da, dr, dg, db;

        sa = (srcRGB >> 24) & 0xff;
        sr = (srcRGB >> 16) & 0xff;
        sg = (srcRGB >> 8) & 0xff;
        sb = srcRGB & 0xff;

        if(isSrcAlphaPre){
            sa = mulLUT[srcConstAlpha][sa] & 0xff;
            sr = mulLUT[srcConstAlpha][sr] & 0xff;
            sg = mulLUT[srcConstAlpha][sg] & 0xff;
            sb = mulLUT[srcConstAlpha][sb] & 0xff;
        }else{
            sa = mulLUT[srcConstAlpha][sa] & 0xff;
            sr = mulLUT[sa][sr] & 0xff;
            sg = mulLUT[sa][sg] & 0xff;
            sb = mulLUT[sa][sb] & 0xff;
        }

        da = (dstRGB >> 24) & 0xff;
        dr = (dstRGB >> 16) & 0xff;
        dg = (dstRGB >> 8) & 0xff;
        db = dstRGB & 0xff;

        if(!isDstAlphaPre){
            dr = mulLUT[da][dr] & 0xff;
            dg = mulLUT[da][dg] & 0xff;
            db = mulLUT[da][db] & 0xff;
        }

        int Fs = 0;
        int Fd = 0;
        switch(rule){
        case AlphaComposite.CLEAR:
            break;

        case AlphaComposite.DST:
            Fd = 255;
            break;

        case AlphaComposite.DST_ATOP:
            Fs = 255 - da;
            Fd = sa;
            break;

        case AlphaComposite.DST_IN:
            Fd = sa;
            break;

        case AlphaComposite.DST_OUT:
            Fd = 255 - sa;
            break;

        case AlphaComposite.DST_OVER:
            Fs = 255 - da;
            Fd = 255;
            break;

        case AlphaComposite.SRC:
            Fs = 255;
            break;

        case AlphaComposite.SRC_ATOP:
            Fs = da;
            Fd = 255 - sa;
            break;

        case AlphaComposite.SRC_IN:
            Fs = da;
            break;

        case AlphaComposite.SRC_OUT:
            Fs = 255 - da;
            break;

        case AlphaComposite.SRC_OVER:
            Fs = 255;
            Fd = 255 - sa;
            break;

        case AlphaComposite.XOR:
            Fs = 255 - da;
            Fd = 255 - sa;
            break;
        }
        dr = (mulLUT[sr][Fs] & 0xff) + (mulLUT[dr][Fd] & 0xff);
        dg = (mulLUT[sg][Fs] & 0xff) + (mulLUT[dg][Fd] & 0xff);
        db = (mulLUT[sb][Fs] & 0xff) + (mulLUT[db][Fd] & 0xff);

        da = (mulLUT[sa][Fs] & 0xff) + (mulLUT[da][Fd] & 0xff);

        if(!isDstAlphaPre){
            if(da != 255){
                dr = divLUT[da][dr] & 0xff;
                dg = divLUT[da][dg] & 0xff;
                db = divLUT[da][db] & 0xff;
            }
        }
        if(!dstHasAlpha) {
            da = 0xff;
        }
        dstRGB = (da << 24) | (dr << 16) | (dg << 8) | db;

        return dstRGB;

    }

}
