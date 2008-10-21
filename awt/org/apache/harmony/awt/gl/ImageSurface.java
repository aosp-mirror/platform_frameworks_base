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

import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

import org.apache.harmony.awt.gl.color.LUTColorConverter;
import org.apache.harmony.awt.gl.image.DataBufferListener;
import org.apache.harmony.awt.internal.nls.Messages;


/**
 * This class represent Surface for different types of Images (BufferedImage, 
 * OffscreenImage and so on) 
 */
public class ImageSurface extends Surface implements DataBufferListener {

    boolean nativeDrawable = true;
    int surfaceType;
    int csType;
    ColorModel cm;
    WritableRaster raster;
    Object data;
    
    boolean needToRefresh = true;
    boolean dataTaken = false;
    
    private long cachedDataPtr;       // Pointer for cached Image Data
    private boolean alphaPre;         // Cached Image Data alpha premultiplied 

    public ImageSurface(ColorModel cm, WritableRaster raster){
        this(cm, raster, Surface.getType(cm, raster));
    }

    public ImageSurface(ColorModel cm, WritableRaster raster, int type){
        if (!cm.isCompatibleRaster(raster)) {
            // awt.4D=The raster is incompatible with this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.4D")); //$NON-NLS-1$
        }
        this.cm = cm;
        this.raster = raster;
        surfaceType = type;

        data = AwtImageBackdoorAccessor.getInstance().
        getData(raster.getDataBuffer());
        ColorSpace cs = cm.getColorSpace();
        transparency = cm.getTransparency();
        width = raster.getWidth();
        height = raster.getHeight();

        // For the moment we can build natively only images which have 
        // sRGB, Linear_RGB, Linear_Gray Color Space and type different
        // from BufferedImage.TYPE_CUSTOM
        if(cs == LUTColorConverter.sRGB_CS){
            csType = sRGB_CS;
        }else if(cs == LUTColorConverter.LINEAR_RGB_CS){
            csType = Linear_RGB_CS;
        }else if(cs == LUTColorConverter.LINEAR_GRAY_CS){
            csType = Linear_Gray_CS;
        }else{
            csType = Custom_CS;
            nativeDrawable = false;
        }

        if(type == BufferedImage.TYPE_CUSTOM){
            nativeDrawable = false;
        }
    }

    @Override
    public ColorModel getColorModel() {
        return cm;
    }

    @Override
    public WritableRaster getRaster() {
        return raster;
    }

    @Override
    public long getSurfaceDataPtr() {
        if(surfaceDataPtr == 0L && nativeDrawable){
            createSufaceStructure();
        }
        return surfaceDataPtr;
    }

    @Override
    public Object getData(){
        return data;
    }

    @Override
    public boolean isNativeDrawable(){
        return nativeDrawable;
    }

    @Override
    public int getSurfaceType() {
        return surfaceType;
    }

    /**
     * Creates native Surface structure which used for native blitting
     */
    private void createSufaceStructure(){
        int cmType = 0;
        int numComponents = cm.getNumComponents();
        boolean hasAlpha = cm.hasAlpha();
        boolean isAlphaPre = cm.isAlphaPremultiplied();
        int transparency = cm.getTransparency();
        int bits[] = cm.getComponentSize();
        int pixelStride = cm.getPixelSize();
        int masks[] = null;
        int colorMap[] = null;
        int colorMapSize = 0;
        int transpPixel = -1;
        boolean isGrayPallete = false;
        SampleModel sm = raster.getSampleModel();
        int smType = 0;
        int dataType = sm.getDataType();
        int scanlineStride = 0;
        int bankIndeces[] = null;
        int bandOffsets[] = null;
        int offset = raster.getDataBuffer().getOffset();

        if(cm instanceof DirectColorModel){
            cmType = DCM;
            DirectColorModel dcm = (DirectColorModel) cm;
            masks = dcm.getMasks();
            smType = SPPSM;
            SinglePixelPackedSampleModel sppsm = (SinglePixelPackedSampleModel) sm;
            scanlineStride = sppsm.getScanlineStride();

        }else if(cm instanceof IndexColorModel){
            cmType = ICM;
            IndexColorModel icm = (IndexColorModel) cm;
            colorMapSize = icm.getMapSize();
            colorMap = new int[colorMapSize];
            icm.getRGBs(colorMap);
            transpPixel = icm.getTransparentPixel();
            isGrayPallete = Surface.isGrayPallete(icm);

            if(sm instanceof MultiPixelPackedSampleModel){
                smType = MPPSM;
                MultiPixelPackedSampleModel mppsm =
                    (MultiPixelPackedSampleModel) sm;
                scanlineStride = mppsm.getScanlineStride();
            }else if(sm instanceof ComponentSampleModel){
                smType = CSM;
                ComponentSampleModel csm =
                    (ComponentSampleModel) sm;
                scanlineStride = csm.getScanlineStride();
            }else{
                // awt.4D=The raster is incompatible with this ColorModel
                throw new IllegalArgumentException(Messages.getString("awt.4D")); //$NON-NLS-1$
            }

        }else if(cm instanceof ComponentColorModel){
            cmType = CCM;
            if(sm instanceof ComponentSampleModel){
                ComponentSampleModel csm = (ComponentSampleModel) sm;
                scanlineStride = csm.getScanlineStride();
                bankIndeces = csm.getBankIndices();
                bandOffsets = csm.getBandOffsets();
                if(sm instanceof PixelInterleavedSampleModel){
                    smType = PISM;
                }else if(sm instanceof BandedSampleModel){
                    smType = BSM;
                }else{
                    smType = CSM;
                }
            }else{
                // awt.4D=The raster is incompatible with this ColorModel
                throw new IllegalArgumentException(Messages.getString("awt.4D")); //$NON-NLS-1$
            }

        }else{
            surfaceDataPtr = 0L;
            return;
        }
        surfaceDataPtr = createSurfStruct(surfaceType, width, height, cmType, csType, smType, dataType,
                numComponents, pixelStride, scanlineStride, bits, masks, colorMapSize,
                colorMap, transpPixel, isGrayPallete, bankIndeces, bandOffsets,
                offset, hasAlpha, isAlphaPre, transparency);
    }

    @Override
    public void dispose() {
        if(surfaceDataPtr != 0L){
            dispose(surfaceDataPtr);
            surfaceDataPtr = 0L;
        }
    }
    
    public long getCachedData(boolean alphaPre){
        if(nativeDrawable){
            if(cachedDataPtr == 0L || needToRefresh || this.alphaPre != alphaPre){
                cachedDataPtr = updateCache(getSurfaceDataPtr(), data, alphaPre);
                this.alphaPre = alphaPre;
                validate(); 
            }
        }
        return cachedDataPtr;
    }

    private native long createSurfStruct(int surfaceType, int width, int height, 
            int cmType, int csType, int smType, int dataType,
            int numComponents, int pixelStride, int scanlineStride,
            int bits[], int masks[], int colorMapSize, int colorMap[],
            int transpPixel, boolean isGrayPalette, int bankIndeces[], 
            int bandOffsets[], int offset, boolean hasAlpha, boolean isAlphaPre,
            int transparency);

    private native void dispose(long structPtr);

    private native void setImageSize(long structPtr, int width, int height);

    private native long updateCache(long structPtr, Object data, boolean alphaPre);
    
    /**
     * Supposes that new raster is compatible with an old one
     * @param r
     */
    public void setRaster(WritableRaster r) {
        raster = r;
        data = AwtImageBackdoorAccessor.getInstance().getData(r.getDataBuffer());
        if (surfaceDataPtr != 0) {
            setImageSize(surfaceDataPtr, r.getWidth(), r.getHeight());
        }
        this.width = r.getWidth();
        this.height = r.getHeight();
    }

    @Override
    public long lock() {
        // TODO
        return 0;
    }

    @Override
    public void unlock() {
        //TODO
    }

    @Override
    public Surface getImageSurface() {
        return this;
    }

    public void dataChanged() {
        needToRefresh = true;
        clearValidCaches();
    }

    public void dataTaken() {
        dataTaken = true;
        needToRefresh = true;
        clearValidCaches();
    }
    
    public void dataReleased(){
        dataTaken = false;
        needToRefresh = true;
        clearValidCaches();
    }
    
    @Override
    public void invalidate(){
        needToRefresh = true;
        clearValidCaches();
    }
    
    @Override
    public void validate(){
        if(!needToRefresh) {
            return;
        }
        if(!dataTaken){
            needToRefresh = false;
            AwtImageBackdoorAccessor ba = AwtImageBackdoorAccessor.getInstance();
            ba.validate(raster.getDataBuffer());
        }
        
    }
    
    @Override
    public boolean invalidated(){
        return needToRefresh;
    }
}
