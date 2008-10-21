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
 */
package java.awt.image;

import java.awt.Image;
import java.util.Hashtable;

import org.apache.harmony.awt.internal.nls.Messages;

public class PixelGrabber implements ImageConsumer {

    int width;
    int height;
    int X;
    int Y;
    int offset;
    int scanline;
    ImageProducer producer;

    byte bData[];
    int iData[];
    ColorModel cm;

    private int grabberStatus;
    private int dataType;
    private boolean isGrabbing;
    private boolean isRGB;


    private static final int DATA_TYPE_BYTE = 0;
    private static final int DATA_TYPE_INT = 1;
    private static final int DATA_TYPE_UNDEFINED = 2;

    private static final int ALL_BITS = (ImageObserver.FRAMEBITS |
            ImageObserver.ALLBITS);

    private static final int GRABBING_STOP = ALL_BITS | ImageObserver.ERROR;



    public PixelGrabber(ImageProducer ip, int x, int y, int w, int h, int[] pix,
            int off, int scansize) {
        initialize(ip, x, y, w, h, pix, off, scansize, true);
    }

    public PixelGrabber(Image img, int x, int y, int w, int h, int[] pix,
            int off, int scansize) {
        initialize(img.getSource(), x, y, w, h, pix, off, scansize, true);
    }

    public PixelGrabber(Image img, int x, int y, int w, int h, boolean forceRGB) {
        initialize(img.getSource(), x, y, w, h, null, 0, 0, forceRGB);
    }

    public void setProperties(Hashtable<?, ?> props) {
        return;
    }

    public synchronized Object getPixels() {
        switch(dataType){
        case DATA_TYPE_BYTE:
            return bData;
        case DATA_TYPE_INT:
            return iData;
        default:
            return null;
        }
    }

    public void setColorModel(ColorModel model) {
        return;
    }

    public void setPixels(int srcX, int srcY, int srcW, int srcH,
            ColorModel model, byte[] pixels, int srcOff, int srcScan) {
        if(srcY < Y){
            int delta = Y - srcY;
            if(delta >= height) {
                return;
            }
            srcY += delta;
            srcH -= delta;
            srcOff += srcScan * delta;
        }

        if(srcY + srcH > Y + height){
            srcH = Y + height - srcY;
            if(srcH <= 0) {
                return;
            }
        }

        if(srcX < X){
            int delta = X - srcX;
            if(delta >= width) {
                return;
            }
            srcW -= delta;
            srcX += delta;
            srcOff += delta;
        }

        if(srcX + srcW > X + width){
            srcW = X + width - srcX;
            if(srcW <= 0) {
                return;
            }
        }
        if(scanline == 0) {
            scanline = width;
        }
        int realOff = offset + (srcY - Y) * scanline + (srcX - X);
        switch(dataType){
        case DATA_TYPE_UNDEFINED:
            cm = model;
            if(model != ColorModel.getRGBdefault()){
                bData = new byte[width * height];
                isRGB = false;
                dataType = DATA_TYPE_BYTE;
            }else{
                iData = new int[width * height];
                isRGB = true;
                dataType = DATA_TYPE_INT;
            }
        case DATA_TYPE_BYTE:
            if(!isRGB && cm == model){
                for(int y = 0; y < srcH; y++){
                    System.arraycopy(pixels, srcOff, bData, realOff, srcW);
                    srcOff += srcScan;
                    realOff += scanline;
                }
                break;
            }
            forceToRGB();
        case DATA_TYPE_INT:
            for(int y = 0; y < srcH; y++){
                for(int x = 0; x < srcW; x++){
                    iData[realOff + x] = cm.getRGB(pixels[srcOff + x] & 0xff);                    
                }
                srcOff += srcScan;
                realOff += scanline;
            }
        }

        return;
    }

    public void setPixels(int srcX, int srcY, int srcW, int srcH,
            ColorModel model, int[] pixels, int srcOff, int srcScan) {

        if(srcY < Y){
            int delta = Y - srcY;
            if(delta >= height) {
                return;
            }
            srcY += delta;
            srcH -= delta;
            srcOff += srcScan * delta;
        }

        if(srcY + srcH > Y + height){
            srcH = Y + height - srcY;
            if(srcH <= 0) {
                return;
            }
        }

        if(srcX < X){
            int delta = X - srcX;
            if(delta >= width) {
                return;
            }
            srcW -= delta;
            srcX += delta;
            srcOff += delta;
        }

        if(srcX + srcW > X + width){
            srcW = X + width - srcX;
            if(srcW <= 0) {
                return;
            }
        }
        if(scanline == 0) {
            scanline = width;
        }
        int realOff = offset + (srcY - Y) * scanline + (srcX - X);

        int mask = 0xFF;

        switch(dataType){
        case DATA_TYPE_UNDEFINED:
            cm = model;
            iData = new int[width * height];
            dataType = DATA_TYPE_INT;
            isRGB = (cm == ColorModel.getRGBdefault());

        case DATA_TYPE_INT:
            if(cm == model){
                for(int y = 0; y < srcH; y++){
                    System.arraycopy(pixels, srcOff, iData, realOff, srcW);
                    srcOff += srcScan;
                    realOff += scanline;
                }
                break;
            }
            mask = 0xFFFFFFFF;

        case DATA_TYPE_BYTE:
            forceToRGB();
            for(int y = 0; y < srcH; y++){
                for(int x = 0; x < srcW; x++){
                    iData[realOff+x] = cm.getRGB(pixels[srcOff+x] & mask);
                }
                srcOff += srcScan;
                realOff += scanline;
            }
        }
    }

    public synchronized ColorModel getColorModel() {
        return cm;
    }

    public synchronized boolean grabPixels(long ms) 
    throws InterruptedException {
        if((grabberStatus & GRABBING_STOP) != 0){
            return ((grabberStatus & ALL_BITS) != 0);
        }

        long start = System.currentTimeMillis();

        if(!isGrabbing){
            isGrabbing = true;
            grabberStatus &= ~ImageObserver.ABORT;
            producer.startProduction(this);
        }
        while((grabberStatus & GRABBING_STOP) == 0){
            if(ms != 0){
                ms = start + ms - System.currentTimeMillis();
                if(ms <= 0) {
                    break;
                }
            }
            wait(ms);
        }

        return ((grabberStatus & ALL_BITS) != 0);
    }

    public void setDimensions(int w, int h) {
        if(width < 0) {
            width = w - X;
        }
        if(height < 0) {
            height = h - Y;
        }

        grabberStatus |= ImageObserver.WIDTH | ImageObserver.HEIGHT;

        if(width <=0 || height <=0){
            imageComplete(STATICIMAGEDONE);
            return;
        }

        if(isRGB && dataType == DATA_TYPE_UNDEFINED){
            iData = new int[width * height];
            dataType = DATA_TYPE_INT;
            scanline = width;
        }
    }

    public void setHints(int hints) {
        return;
    }

    public synchronized void imageComplete(int status) {
        switch(status){
        case IMAGEABORTED:
            grabberStatus |= ImageObserver.ABORT;
            break;
        case IMAGEERROR:
            grabberStatus |= ImageObserver.ERROR | ImageObserver.ABORT;
            break;
        case SINGLEFRAMEDONE:
            grabberStatus |= ImageObserver.FRAMEBITS;
            break;
        case STATICIMAGEDONE:
            grabberStatus |= ImageObserver.ALLBITS;
            break;
        default:
            // awt.26A=Incorrect ImageConsumer completion status
            throw new IllegalArgumentException(Messages.getString("awt.26A")); //$NON-NLS-1$
        }
        isGrabbing = false;
        producer.removeConsumer(this);
        notifyAll();
    }

    public boolean grabPixels() throws InterruptedException {
        return grabPixels(0);
    }

    public synchronized void startGrabbing() {
        if((grabberStatus & GRABBING_STOP) != 0){
            return;
        }
        if(!isGrabbing){
            isGrabbing = true;
            grabberStatus &= ~ImageObserver.ABORT;
            producer.startProduction(this);
        }
    }

    public synchronized void abortGrabbing() {
        imageComplete(IMAGEABORTED);
    }

    public synchronized int status() {
        return grabberStatus;
    }

    public synchronized int getWidth() {
        if(width < 0) {
            return -1;
        }
        return width;
    }

    public synchronized int getStatus() {
        return grabberStatus;
    }

    public synchronized int getHeight() {
        if(height < 0) {
            return -1;
        }
        return height;
    }

    private void initialize(ImageProducer ip, int x, int y, int w, int h,
            int pixels[], int off, int scansize, boolean forceRGB){

        producer = ip;
        X = x;
        Y = y;
        width = w;
        height = h;
        iData = pixels;
        dataType = (pixels == null) ? DATA_TYPE_UNDEFINED : DATA_TYPE_INT;
        offset = off;
        scanline = scansize;
        if(forceRGB){
            cm = ColorModel.getRGBdefault();
            isRGB = true;
        }
    }

    /**
     * Force pixels to INT RGB mode
     */
    private void forceToRGB(){
        if (isRGB)
            return;
    
        switch(dataType){
        case DATA_TYPE_BYTE:
            iData = new int[width * height];
            for(int i = 0; i < iData.length; i++){
                iData[i] = cm.getRGB(bData[i] & 0xff);
            }
            dataType = DATA_TYPE_INT;
            bData = null;
            break;

        case DATA_TYPE_INT:
            int buff[] = new int[width * height];
            for(int i = 0; i < iData.length; i++){
                buff[i] = cm.getRGB(iData[i]);
            }
            iData = buff;
            break;
        }
        offset = 0;
        scanline = width;
        cm = ColorModel.getRGBdefault();
        isRGB = true;
    }

}
