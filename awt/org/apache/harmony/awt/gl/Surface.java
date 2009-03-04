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
 * Created on 10.11.2005
 *
 */
package org.apache.harmony.awt.gl;

import java.awt.Image;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;

import org.apache.harmony.awt.gl.color.LUTColorConverter;


/**
 * This class is super class for others types of Surfaces. 
 * Surface is storing data and data format description, that are using
 * in blitting operations    
 */
public abstract class Surface implements Transparency{

    // Color Space Types
    public static final int sRGB_CS = 1;
    public static final int Linear_RGB_CS = 2;
    public static final int Linear_Gray_CS = 3;
    public static final int Custom_CS = 0;
    
    // Color Model Types
    public static final int DCM = 1;  // Direct Color Model
    public static final int ICM = 2;  // Index Color Model
    public static final int CCM = 3;  // Component Color Model

    // Sample Model Types
    public static final int SPPSM = 1;  // Single Pixel Packed Sample Model
    public static final int MPPSM = 2;  // Multi Pixel Packed Sample Model
    public static final int CSM   = 3;  // Component Sample Model
    public static final int PISM  = 4;  // Pixel Interleaved Sample Model
    public static final int BSM   = 5;  // Banded Sample Model

    // Surface Types
    private static final int ALPHA_MASK = 0xff000000;
    private static final int RED_MASK = 0x00ff0000;
    private static final int GREEN_MASK = 0x0000ff00;
    private static final int BLUE_MASK = 0x000000ff;
    private static final int RED_BGR_MASK = 0x000000ff;
    private static final int GREEN_BGR_MASK = 0x0000ff00;
    private static final int BLUE_BGR_MASK = 0x00ff0000;
    private static final int RED_565_MASK = 0xf800;
    private static final int GREEN_565_MASK = 0x07e0;
    private static final int BLUE_565_MASK = 0x001f;
    private static final int RED_555_MASK = 0x7c00;
    private static final int GREEN_555_MASK = 0x03e0;
    private static final int BLUE_555_MASK = 0x001f;

    static{
        //???AWT
        /*
        System.loadLibrary("gl"); //$NON-NLS-1$
        initIDs();
        */
    }


    protected long surfaceDataPtr;        // Pointer for Native Surface data
    protected int transparency = OPAQUE;
    protected int width;
    protected int height;

    /**
     * This list contains caches with the data of this surface that are valid at the moment.
     * Surface should clear this list when its data is updated.
     * Caches may check if they are still valid using isCacheValid method.
     * When cache gets data from the surface, it should call addValidCache method of the surface.
     */
    private final ArrayList<Object> validCaches = new ArrayList<Object>();

    public abstract ColorModel getColorModel();
    public abstract WritableRaster getRaster();
    public abstract int getSurfaceType(); // Syrface type. It is equal 
                                          // BufferedImge type
    /**
     * Lock Native Surface data
     */
    public abstract long lock();     
    
    /**
     * Unlock Native Surface data 
     */
    public abstract void unlock();
    
    /**
     * Dispose Native Surface data
     */
    public abstract void dispose();
    public abstract Surface getImageSurface();

    public long getSurfaceDataPtr(){
        return surfaceDataPtr;
    }

    public final boolean isCaheValid(Object cache) {
        return validCaches.contains(cache);
    }

    public final void addValidCache(Object cache) {
        validCaches.add(cache);
    }

    protected final void clearValidCaches() {
        validCaches.clear();
    }

    /**
     * Returns could or coldn't the Surface be blit by Native blitter 
     * @return - true if the Surface could be blit by Native blitter, 
     *           false in other case
     */
    public boolean isNativeDrawable(){
        return true;
    }

    public int getTransparency() {
        return transparency;
    }

    public int getWidth(){
        return width;
    }

    public int getHeight(){
        return height;
    }
    
    /**
     * If Surface has Raster, this method returns data array of Raster's DataBuffer
     * @return - data array
     */
    public Object getData(){
        return null;
    }
    
    public boolean invalidated(){
        return true;
    }
    
    public void validate(){}
    
    public void invalidate(){}

    /**
     * Computation type of BufferedImage or Surface
     * @param cm - ColorModel
     * @param raster - WritableRaste
     * @return - type of BufferedImage
     */
    public static int getType(ColorModel cm, WritableRaster raster){
        int transferType = cm.getTransferType();
        boolean hasAlpha = cm.hasAlpha();
        ColorSpace cs = cm.getColorSpace();
        int csType = cs.getType();
        SampleModel sm = raster.getSampleModel();

        if(csType == ColorSpace.TYPE_RGB){
            if(cm instanceof DirectColorModel){
                DirectColorModel dcm = (DirectColorModel) cm;
                switch (transferType) {
                case DataBuffer.TYPE_INT:
                    if (dcm.getRedMask() == RED_MASK &&
                            dcm.getGreenMask() == GREEN_MASK &&
                            dcm.getBlueMask() == BLUE_MASK) {
                        if (!hasAlpha) {
                            return BufferedImage.TYPE_INT_RGB;
                        }
                        if (dcm.getAlphaMask() == ALPHA_MASK) {
                            if (dcm.isAlphaPremultiplied()) {
                                return BufferedImage.TYPE_INT_ARGB_PRE;
                            }
                            return BufferedImage.TYPE_INT_ARGB;
                        }
                        return BufferedImage.TYPE_CUSTOM;
                    } else if (dcm.getRedMask() == RED_BGR_MASK &&
                            dcm.getGreenMask() == GREEN_BGR_MASK &&
                            dcm.getBlueMask() == BLUE_BGR_MASK) {
                        if (!hasAlpha) {
                            return BufferedImage.TYPE_INT_BGR;
                        }
                    } else {
                        return BufferedImage.TYPE_CUSTOM;
                    }
                case DataBuffer.TYPE_USHORT:
                    if (dcm.getRedMask() == RED_555_MASK &&
                            dcm.getGreenMask() == GREEN_555_MASK &&
                            dcm.getBlueMask() == BLUE_555_MASK && !hasAlpha) {
                        return BufferedImage.TYPE_USHORT_555_RGB;
                    } else if (dcm.getRedMask() == RED_565_MASK &&
                            dcm.getGreenMask() == GREEN_565_MASK &&
                            dcm.getBlueMask() == BLUE_565_MASK) {
                        return BufferedImage.TYPE_USHORT_565_RGB;
                    }
                default:
                    return BufferedImage.TYPE_CUSTOM;
                }
            }else if(cm instanceof IndexColorModel){
                IndexColorModel icm = (IndexColorModel) cm;
                int pixelBits = icm.getPixelSize();
                if(transferType == DataBuffer.TYPE_BYTE){
                    if(sm instanceof MultiPixelPackedSampleModel && !hasAlpha &&
                        pixelBits < 5){
                            return BufferedImage.TYPE_BYTE_BINARY;
                    }else if(pixelBits == 8){
                        return BufferedImage.TYPE_BYTE_INDEXED;
                    }
                }
                return BufferedImage.TYPE_CUSTOM;
            }else if(cm instanceof ComponentColorModel){
                ComponentColorModel ccm = (ComponentColorModel) cm;
                if(transferType == DataBuffer.TYPE_BYTE &&
                        sm instanceof ComponentSampleModel){
                    ComponentSampleModel csm =
                        (ComponentSampleModel) sm;
                    int[] offsets = csm.getBandOffsets();
                    int[] bits = ccm.getComponentSize();
                    boolean isCustom = false;
                    for (int i = 0; i < bits.length; i++) {
                        if (bits[i] != 8 ||
                               offsets[i] != offsets.length - 1 - i) {
                            isCustom = true;
                            break;
                        }
                    }
                    if (!isCustom) {
                        if (!ccm.hasAlpha()) {
                            return BufferedImage.TYPE_3BYTE_BGR;
                        } else if (ccm.isAlphaPremultiplied()) {
                            return BufferedImage.TYPE_4BYTE_ABGR_PRE;
                        } else {
                            return BufferedImage.TYPE_4BYTE_ABGR;
                        }
                    }
                }
                return BufferedImage.TYPE_CUSTOM;
            }
            return BufferedImage.TYPE_CUSTOM;
        }else if(cs == LUTColorConverter.LINEAR_GRAY_CS){
            if(cm instanceof ComponentColorModel &&
                    cm.getNumComponents() == 1){
                int bits[] = cm.getComponentSize();
                if(transferType == DataBuffer.TYPE_BYTE &&
                        bits[0] == 8){
                    return BufferedImage.TYPE_BYTE_GRAY;
                }else if(transferType == DataBuffer.TYPE_USHORT &&
                        bits[0] == 16){
                    return BufferedImage.TYPE_USHORT_GRAY;
                }else{
                    return BufferedImage.TYPE_CUSTOM;
                }
            }
            return BufferedImage.TYPE_CUSTOM;
        }
        return BufferedImage.TYPE_CUSTOM;
    }

    public static Surface getImageSurface(Image image){
        return AwtImageBackdoorAccessor.getInstance().getImageSurface(image);
    }

    @Override
    protected void finalize() throws Throwable{
        dispose();
    }

    public static boolean isGrayPallete(IndexColorModel icm){
        return AwtImageBackdoorAccessor.getInstance().isGrayPallete(icm);
    }

    /**
     * Initialization of Native data
     * 
     */
    //???AWT: private static native void initIDs();
}
