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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */
package org.apache.harmony.x.imageio.plugins.jpeg;

import com.android.internal.awt.ImageOutputStreamWrapper;

import javax.imageio.ImageWriter;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.metadata.IIOMetadata;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.awt.image.*;
import java.awt.*;
import java.awt.color.ColorSpace;

/**
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */
public class JPEGImageWriter extends ImageWriter {

    // /* ???AWT: Debugging
    private static final boolean DEBUG = false;
    private static Bitmap bm;
    public static Bitmap getBitmap() {
        return bm;
    }
    private static BufferedImage bufImg;
    public static BufferedImage getBufImage() {
        return bufImg;
    }
    static private RenderedImage renImg;
    static public RenderedImage getRenImage() {
        return renImg;
    }
    // */
    
    private long cinfo;
    private RenderedImage image;
    private Raster sourceRaster;
    private WritableRaster scanRaster;
    private int srcXOff = 0;
    private int srcYOff = 0;
    private int srcWidth;
    private int srcHeight;

    //-- y step for image subsampling
    private int deltaY = 1;
    //-- x step for image subsampling
    private int deltaX = 1;

    private ImageOutputStream ios;

    public JPEGImageWriter(ImageWriterSpi imageWriterSpi) {
        super(imageWriterSpi);
        //???AWT: cinfo = initCompressionObj();
        cinfo = System.currentTimeMillis();
    }

    static {
        //???AWT
        /*
        System.loadLibrary("jpegencoder");
        initWriterIds(ImageOutputStream.class);
        */
    }

    @Override
    public void write(IIOMetadata iioMetadata, IIOImage iioImage, ImageWriteParam param)
            throws IOException {

        if (ios == null) {
            throw new IllegalArgumentException("ios == null");
        }
        if (iioImage == null) {
            throw new IllegalArgumentException("Image equals null");
        }

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
        
        // AWT???: Debugging
        if (DEBUG) {
            if( img==null ) {
                System.out.println("****J: Image is NULL");
            } else {
                renImg = img;
                bufImg = (BufferedImage)img;
            }
        }

        int numBands = sourceRaster.getNumBands();
        int sourceIJGCs = img == null ? JPEGConsts.JCS_UNKNOW : getSourceCSType(img);

        srcWidth = sourceRaster.getWidth();
        srcHeight = sourceRaster.getHeight();

        int destWidth = srcWidth;
        int destHeight = srcHeight;

        boolean progressive = false;
         
        if (param != null) {
            Rectangle reg = param.getSourceRegion();
            if (reg != null) {
                srcXOff = reg.x;
                srcYOff = reg.y;

                srcWidth = reg.width + srcXOff > srcWidth
                        ? srcWidth - srcXOff
                        : reg.width;
                srcHeight = reg.height + srcYOff > srcHeight
                        ? srcHeight - srcYOff
                        : reg.height;
            }

            //-- TODO uncomment when JPEGImageWriteParam be implemented
            //-- Only default progressive mode yet
            // progressive = param.getProgressiveMode() ==  ImageWriteParam.MODE_DEFAULT;

            //-- def is 1
            deltaX = param.getSourceXSubsampling();
            deltaY = param.getSourceYSubsampling();

            //-- def is 0
            int offsetX = param.getSubsamplingXOffset();
            int offsetY = param.getSubsamplingYOffset();

            srcXOff += offsetX;
            srcYOff += offsetY;
            srcWidth -= offsetX;
            srcHeight -= offsetY;

            destWidth = (srcWidth + deltaX - 1) / deltaX;
            destHeight = (srcHeight + deltaY - 1) / deltaY;
        }

        //-- default DQTs (see JPEGQTable java doc and JPEG spec K1 & K2 tables)
        //-- at http://www.w3.org/Graphics/JPEG/itu-t81.pdf
        //-- Only figuring out how to set DQT in IJG library for future metadata
        //-- support. IJG def tables are the same.
        //JPEGQTable[] dqt = new JPEGQTable[2];
//        int[][] dqt = null;
//        int[][] dqt = new int[2][];
//        dqt[0] = JPEGQTable.K1Div2Luminance.getTable();
//        dqt[1] = JPEGQTable.K2Div2Chrominance.getTable();
        
        //???AWT: I think we don't need this amymore
        /*
        //-- using default color space
        //-- TODO: Take destination cs from param or use default if there is no cs
        int destIJGCs = img == null ? JPEGConsts.JCS_UNKNOW : getDestinationCSType(img);

        DataBufferByte dbuffer = new DataBufferByte(numBands * srcWidth);

        scanRaster = Raster.createInterleavedRaster(dbuffer, srcWidth, 1,
                numBands * srcWidth, numBands, JPEGConsts.BAND_OFFSETS[numBands], null);

        encode(dbuffer.getData(), srcWidth, destWidth, destHeight, deltaX,
                sourceIJGCs, destIJGCs, numBands, progressive,
                null, cinfo);
        */
        
        SampleModel model = sourceRaster.getSampleModel();
        
        if (model instanceof SinglePixelPackedSampleModel) {
            DataBufferInt ibuf = (DataBufferInt)sourceRaster.getDataBuffer();
            int[] pixels = ibuf.getData();
            
            // Create a bitmap with the pixel
            bm = Bitmap.createBitmap(pixels, srcWidth, srcHeight, Bitmap.Config.ARGB_8888);
            
            // Use Bitmap.compress() to write the image
            ImageOutputStreamWrapper iosw = new ImageOutputStreamWrapper(ios);
            bm.compress(CompressFormat.JPEG, 100, iosw);
        } else {
            // ???AWT: Add support for other color models
            throw new RuntimeException("Color model not supported yet");
        }

    }

    @Override
    public void dispose() {
        super.dispose();
        if (cinfo != 0) {
            //???AWT: dispose(cinfo);
            cinfo = 0;
            ios = null;
        }
    }


    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam imageWriteParam) {
        throw new UnsupportedOperationException("not supported yet");
    }

    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageTypeSpecifier, ImageWriteParam imageWriteParam) {
        throw new UnsupportedOperationException("not supported yet");
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata iioMetadata, ImageWriteParam imageWriteParam) {
        throw new UnsupportedOperationException("not supported yet");
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata iioMetadata, ImageTypeSpecifier imageTypeSpecifier, ImageWriteParam imageWriteParam) {
        throw new UnsupportedOperationException("not supported yet");
    }

    @Override
    public void setOutput(Object output) {
        super.setOutput(output);
        ios = (ImageOutputStream) output;
        //???AWT: setIOS(ios, cinfo);
        sourceRaster = null;
        scanRaster = null;
        srcXOff = 0;
        srcYOff = 0;
        srcWidth = 0;
        srcHeight = 0;
        deltaY = 1;
    }

    /**
     * Frees resources
     * @param structPointer
     */
    //???AWT: private native void dispose(long structPointer);

    /**
     * Inits methods Ids for native to java callbacks
     * @param iosClass
     */
    //???AWT: private native static void initWriterIds(Class<ImageOutputStream> iosClass);

    /**
     * Inits compression objects
     * @return pointer to the native structure
     */
    //???AWT: private native long initCompressionObj();

    /**
     * Sets image output stream in IJG layer
     * @param stream
     */
    //???AWT: private native void setIOS(ImageOutputStream stream, long structPointer);

    /**
     * Runs encoding process.
     *
     * @param data image data buffer to encode
     * @param srcWidth - source width
     * @param width - destination width
     * @param height destination height
     * @param deltaX - x subsampling step
     * @param inColorSpace - original color space
     * @param outColorSpace - destination color space
     * @param numBands - number of bands
     * @param cinfo - native handler
     * @return
     */
    //???AWT:
    /*
    private native boolean encode(byte[] data, int srcWidth,
                                  int width, int height, int deltaX,
                                  int inColorSpace, int outColorSpace,
                                  int numBands, boolean progressive,
                                  int[][] dqt,
                                  long cinfo);
    */

    /**
     * Callback for getting a next scanline
     * @param scanline scan line number
     */
    @SuppressWarnings("unused")
    private void getScanLine(int scanline) {
        //-- TODO: processImageProgress in ImageWriter
        Raster child = sourceRaster.createChild(srcXOff,
                srcYOff + scanline * deltaY, srcWidth, 1, 0, 0, null);

        scanRaster.setRect(child);
    }

    /**
     * Maps color space types to IJG color spaces
     * @param image
     * @return
     */
    private int getSourceCSType(RenderedImage image) {
        int type = JPEGConsts.JCS_UNKNOW;
        ColorModel cm = image.getColorModel();

        if (null == cm) {
            return type;
        }

        if (cm instanceof IndexColorModel) {
            throw new UnsupportedOperationException("IndexColorModel is not supported yet");
        }

        boolean hasAlpha = cm.hasAlpha();
        ColorSpace cs = cm.getColorSpace();
        switch(cs.getType()) {
            case ColorSpace.TYPE_GRAY:
                type = JPEGConsts.JCS_GRAYSCALE;
                break;
           case ColorSpace.TYPE_RGB:
                type = hasAlpha ? JPEGConsts.JCS_RGBA : JPEGConsts.JCS_RGB;
                break;
           case ColorSpace.TYPE_YCbCr:
                type = hasAlpha ? JPEGConsts.JCS_YCbCrA : JPEGConsts.JCS_YCbCr;
                break;
           case ColorSpace.TYPE_3CLR:
                 type = hasAlpha ? JPEGConsts.JCS_YCCA : JPEGConsts.JCS_YCC;
                 break;
           case ColorSpace.TYPE_CMYK:
                  type = JPEGConsts.JCS_CMYK;
                  break;
        }
        return type;
    }

    /**
     * Returns destination color space.
     * (YCbCr[A] for RGB)
     *
     * @param image
     * @return
     */
    private int getDestinationCSType(RenderedImage image) {
        int type = JPEGConsts.JCS_UNKNOW;
        ColorModel cm = image.getColorModel();
        if (null != cm) {
            boolean hasAlpha = cm.hasAlpha();
            ColorSpace cs = cm.getColorSpace();

            switch(cs.getType()) {
                case ColorSpace.TYPE_GRAY:
                    type = JPEGConsts.JCS_GRAYSCALE;
                    break;
               case ColorSpace.TYPE_RGB:
                    type = hasAlpha ? JPEGConsts.JCS_YCbCrA : JPEGConsts.JCS_YCbCr;
                    break;
               case ColorSpace.TYPE_YCbCr:
                    type = hasAlpha ? JPEGConsts.JCS_YCbCrA : JPEGConsts.JCS_YCbCr;
                    break;
               case ColorSpace.TYPE_3CLR:
                     type = hasAlpha ? JPEGConsts.JCS_YCCA : JPEGConsts.JCS_YCC;
                     break;
               case ColorSpace.TYPE_CMYK:
                      type = JPEGConsts.JCS_CMYK;
                      break;
            }
        }
        return type;
    }

    public ImageWriteParam getDefaultWriteParam() {
        return new JPEGImageWriteParam(getLocale());
    }
}
