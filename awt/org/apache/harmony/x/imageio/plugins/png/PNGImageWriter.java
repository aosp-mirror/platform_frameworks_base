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
 * @author Viskov Nikolay
 * @version $Revision$
 */
package org.apache.harmony.x.imageio.plugins.png;

import com.android.internal.awt.ImageOutputStreamWrapper;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

import org.apache.harmony.x.imageio.internal.nls.Messages;

import org.apache.harmony.luni.util.NotImplementedException;

import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;

public class PNGImageWriter extends ImageWriter {
    
    // /* ???AWT: Debugging
    private static final boolean DEBUG = false;
    private static Bitmap bm;
    public static Bitmap getBitmap() {
        return bm;
    }
    // */
    
    private static int[][] BAND_OFFSETS = {
            {}, {
                0 }, {
                    0, 1 }, {
                    0, 1, 2 }, {
                    0, 1, 2, 3 } };

    // Each pixel is a grayscale sample.
    private static final int PNG_COLOR_TYPE_GRAY = 0;
    // Each pixel is an R,G,B triple.
    private static final int PNG_COLOR_TYPE_RGB = 2;
    // Each pixel is a palette index, a PLTE chunk must appear.
    private static final int PNG_COLOR_TYPE_PLTE = 3;
    // Each pixel is a grayscale sample, followed by an alpha sample.
    private static final int PNG_COLOR_TYPE_GRAY_ALPHA = 4;
    // Each pixel is an R,G,B triple, followed by an alpha sample.
    private static final int PNG_COLOR_TYPE_RGBA = 6;
    
    //???AWT: private static native void initIDs(Class<ImageOutputStream> iosClass);

    static {
        //???AWT
        /*
        System.loadLibrary("pngencoder"); //$NON-NLS-1$
        initIDs(ImageOutputStream.class);
        */
    }
    
    /*
    private native int encode(byte[] input, int bytesInBuffer, int bytePixelSize, Object ios, int imageWidth,
            int imageHeight, int bitDepth, int colorType, int[] palette, int i, boolean b);
    */
    
    protected PNGImageWriter(ImageWriterSpi iwSpi) {
        super(iwSpi);
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata arg0, ImageWriteParam arg1) {
        throw new NotImplementedException();
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata arg0, ImageTypeSpecifier arg1, ImageWriteParam arg2) {
        throw new NotImplementedException();
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier arg0, ImageWriteParam arg1) {
        throw new NotImplementedException();
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam arg0) {
        throw new NotImplementedException();
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage iioImage, ImageWriteParam param) throws IOException {
        if (output == null) {
            throw new IllegalStateException("Output not been set");
        }
        if (iioImage == null) {
            throw new IllegalArgumentException("Image equals null");
        }
        // AWT???: I think this is not needed anymore
        // if (iioImage.hasRaster() && !canWriteRasters()) {
        //    throw new UnsupportedOperationException("Can't write raster");
        //}// ImageOutputStreamImpl
        
        Raster sourceRaster;
        RenderedImage img = null;
        if (!iioImage.hasRaster()) {
            img = iioImage.getRenderedImage();
            if (img instanceof BufferedImage) {
                sourceRaster = ((BufferedImage) img).getRaster();
            } else {
                sourceRaster = img.getData();
            }
        } else {
            sourceRaster = iioImage.getRaster();
        }

        SampleModel model = sourceRaster.getSampleModel();
        int srcWidth = sourceRaster.getWidth();
        int srcHeight = sourceRaster.getHeight();
        int numBands = model.getNumBands();
        
        ColorModel colorModel = img.getColorModel();
        int pixelSize = colorModel.getPixelSize();
        int bytePixelSize = pixelSize / 8;
        int bitDepth = pixelSize / numBands;
        
        // byte per band
        int bpb = bitDepth > 8 ? 2 : 1;
        
        boolean isInterlace = true;
        if (param instanceof PNGImageWriterParam) {
            isInterlace = ((PNGImageWriterParam) param).getInterlace();
        }
        
        int colorType = PNG_COLOR_TYPE_GRAY;
        int[] palette = null;
        
        if (colorModel instanceof IndexColorModel) {
            if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8) {
//              Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1"));//$NON-NLS-1$
            }
            if (numBands != 1) {
//              Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1"));//$NON-NLS-1$
            }

            IndexColorModel icm = (IndexColorModel) colorModel;
            palette = new int[icm.getMapSize()];
            icm.getRGBs(palette);
            colorType = PNG_COLOR_TYPE_PLTE;
        }
        else if (numBands == 1) {
            if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8 && bitDepth != 16) {
//              Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1"));//$NON-NLS-1$
            }
            colorType = PNG_COLOR_TYPE_GRAY;
        }
        else if (numBands == 2) {
            if (bitDepth != 8 && bitDepth != 16) {
//              Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1"));//$NON-NLS-1$
            }
            colorType = PNG_COLOR_TYPE_GRAY_ALPHA;
        }
        else if (numBands == 3) {
            if (bitDepth != 8 && bitDepth != 16) {
//              Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1")); //$NON-NLS-1$
            }
            colorType = PNG_COLOR_TYPE_RGB;
        }
        else if (numBands == 4) {
            if (bitDepth != 8 && bitDepth != 16) {
                //Wrong bitDepth-numBands composition
                throw new IllegalArgumentException(Messages.getString("imageio.1")); //$NON-NLS-1$
            }
            colorType = PNG_COLOR_TYPE_RGBA;
        }
        
        /* ???AWT: I think this is not needed anymore
        int dbufferLenght = bytePixelSize * imageHeight * imageWidth;
        DataBufferByte dbuffer = new DataBufferByte(dbufferLenght);

        WritableRaster scanRaster = Raster.createInterleavedRaster(dbuffer, imageWidth, imageHeight, bpb * numBands
                * imageWidth, bpb * numBands, BAND_OFFSETS[numBands], null);

        scanRaster.setRect(((BufferedImage) image).getRaster()// image.getData()
                .createChild(0, 0, imageWidth, imageHeight, 0, 0, null));
        */

        if (DEBUG) {
            System.out.println("**** raster:" + sourceRaster);        
            System.out.println("**** model:" + model);
            System.out.println("**** type:" + colorType);
        }
        
        if (model instanceof SinglePixelPackedSampleModel) {
            DataBufferInt ibuf = (DataBufferInt)sourceRaster.getDataBuffer();
            int[] pixels = ibuf.getData();
            
            // Create a bitmap with the pixel
            bm = Bitmap.createBitmap(pixels, srcWidth, srcHeight, Bitmap.Config.ARGB_8888);
            
            // Use Bitmap.compress() to write the image
            ImageOutputStream ios = (ImageOutputStream) getOutput();
            ImageOutputStreamWrapper iosw = new ImageOutputStreamWrapper(ios);
            bm.compress(CompressFormat.PNG, 100, iosw);
        } else {
            // ???AWT: Add support for other color models
            throw new RuntimeException("Color model not supported yet");
        }
    }

    public ImageWriteParam getDefaultWriteParam() {
        return new PNGImageWriterParam();
    }
}
