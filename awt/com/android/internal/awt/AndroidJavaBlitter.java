/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.awt;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.XORComposite;
import org.apache.harmony.awt.gl.render.Blitter;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

public class AndroidJavaBlitter implements Blitter {

    private Canvas canvas;
    private Paint paint;
    private int colorCache;
        
    public AndroidJavaBlitter(Canvas c) {
        this.canvas = c;
        this.paint = new Paint();
        this.paint.setStrokeWidth(1);
    }
    
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
                simpleBlit(srcX, srcY, srcSurf, dstX, dstY, dstSurf,
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

    public void simpleBlit(int srcX, int srcY, Surface srcSurf, int dstX, int dstY,
            Surface dstSurf, int width, int height, Composite comp,
            Color bgcolor, MultiRectArea clip) {

        // TODO It's possible, though unlikely that we might encounter non-int[]
        // data buffers. In this case the following code needs to have several
        // branches that take this into account.
        data = (DataBufferInt)srcSurf.getRaster().getDataBuffer();
        int[] pixels = data.getData();
        if (!srcSurf.getColorModel().hasAlpha()) {
            // This wouldn't be necessary if Android supported RGB_888.
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = pixels[i] | 0xff000000;
            }
        }
        bmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        canvas.drawBitmap(bmap, dstX, dstY, paint);
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

    DataBufferInt data;
    Bitmap bmap, bmp;
    
    void alphaCompose(int srcX, int srcY, ColorModel srcCM, Raster srcRast,
            int dstX, int dstY, ColorModel dstCM, WritableRaster dstRast,
            int width, int height, int rule, float alpha, Color bgcolor){
        
        Object srcPixel = getTransferArray(srcRast, 1);
        data = (DataBufferInt)srcRast.getDataBuffer();
        int pix[] = data.getData();
        bmap = Bitmap.createBitmap(pix, width, height, Bitmap.Config.RGB_565);
        canvas.drawBitmap(bmap, dstX, dstY, paint);
    }
    
    void render(int[] img, int x, int y, int width, int height) {
        canvas.drawBitmap(Bitmap.createBitmap(img, width, height, Bitmap.Config.ARGB_8888), x, y, paint);
    }

    void xorCompose(int srcX, int srcY, ColorModel srcCM, Raster srcRast,
            int dstX, int dstY, ColorModel dstCM, WritableRaster dstRast,
            int width, int height, Color xorcolor){

        data = (DataBufferInt)srcRast.getDataBuffer();
        int pix[] = data.getData();
        bmap = Bitmap.createBitmap(pix, width, height, Bitmap.Config.RGB_565);
        canvas.drawBitmap(bmap, dstX, dstY, paint);
    }
    
    private void transformedBlit(ColorModel srcCM, Raster srcR, int srcX, int srcY,
            ColorModel dstCM, WritableRaster dstR, int dstX, int dstY,
            int width, int height, AffineTransform at, Composite comp,
            Color bgcolor, MultiRectArea clip) {
        
        data = (DataBufferInt)srcR.getDataBuffer();
        int[] pixels = data.getData();
        if (!srcCM.hasAlpha()) {
            // This wouldn't be necessary if Android supported RGB_888.
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = pixels[i] | 0xff000000;
            }
        }
        bmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        
        Matrix tm = new Matrix();
        tm.setConcat(canvas.getMatrix(), AndroidGraphics2D.createMatrixObj(at));
        if(at.getType() > 1) {
            bmp = Bitmap.createBitmap(bmap, 0, 0, width, height, tm, true);
        } else {
            bmp = Bitmap.createBitmap(bmap, 0, 0, width, height, tm, false);
        }
        canvas.drawBitmap(bmp, dstX + (float)at.getTranslateX(), dstY + (float)at.getTranslateY(), paint);
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
    
    /**
     * Allocate an array that can be use to store the result for a 
     * Raster.getDataElements call.
     * @param raster  Raster (type) where the getDataElements call will be made. 
     * @param nbPixels  How many pixels to store in the array at most
     * @return the result array or null
     */
    private Object getTransferArray(Raster raster, int nbPixels) {
        int transferType = raster.getTransferType();
        int nbDataElements = raster.getSampleModel().getNumDataElements();
        int n = nbDataElements * nbPixels;
        switch (transferType) {
        case DataBuffer.TYPE_BYTE:
            return new byte[n];
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_USHORT:
            return new short[n];
        case DataBuffer.TYPE_INT:
            return new int[n];
        case DataBuffer.TYPE_FLOAT:
            return new float[n];
        case DataBuffer.TYPE_DOUBLE:
            return new double[n];
        case DataBuffer.TYPE_UNDEFINED:
        default:
            return null;
        }
    }
    
    /**
     * Draw a pixel
     */
    private void dot(int x, int y, int clr) {
        if (colorCache != clr) {
            paint.setColor(clr);  
            colorCache = clr;
        }
        canvas.drawLine(x, y, x + 1, y + 1, paint);
    }
}
