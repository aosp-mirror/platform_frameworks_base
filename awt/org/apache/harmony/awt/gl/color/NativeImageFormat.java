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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.color;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.ArrayList;

import org.apache.harmony.awt.gl.AwtImageBackdoorAccessor;
import org.apache.harmony.awt.internal.nls.Messages;


/**
 * This class converts java color/sample models to the LCMS pixel formats.
 * It also encapsulates all the information about the image format, which native CMM
 * needs to have in order to read/write data.
 *
 * At present planar formats (multiple bands) are not supported
 * and they are handled as a common (custom) case.
 * Samples other than 1 - 7 bytes and multiple of 8 bits are
 * also handled as custom (and won't be supported in the nearest future).
 */
class NativeImageFormat {
    //////////////////////////////////////////////
    //  LCMS Pixel types
    private static final int PT_ANY = 0;    // Don't check colorspace
    // 1 & 2 are reserved
    private static final int PT_GRAY     = 3;
    private static final int PT_RGB      = 4;
    // Skipping other since we don't use them here
    ///////////////////////////////////////////////

    // Conversion of predefined BufferedImage formats to LCMS formats
    private static final int INT_RGB_LCMS_FMT =
        colorspaceSh(PT_RGB)|
        extraSh(1)|
        channelsSh(3)|
        bytesSh(1)|
        doswapSh(1)|
        swapfirstSh(1);

    private static final int INT_ARGB_LCMS_FMT = INT_RGB_LCMS_FMT;

    private static final int INT_BGR_LCMS_FMT =
        colorspaceSh(PT_RGB)|
        extraSh(1)|
        channelsSh(3)|
        bytesSh(1);

    private static final int THREE_BYTE_BGR_LCMS_FMT =
        colorspaceSh(PT_RGB)|
        channelsSh(3)|
        bytesSh(1)|
        doswapSh(1);

    private static final int FOUR_BYTE_ABGR_LCMS_FMT =
        colorspaceSh(PT_RGB)|
        extraSh(1)|
        channelsSh(3)|
        bytesSh(1)|
        doswapSh(1);

    private static final int BYTE_GRAY_LCMS_FMT =
        colorspaceSh(PT_GRAY)|
        channelsSh(1)|
        bytesSh(1);

    private static final int USHORT_GRAY_LCMS_FMT =
        colorspaceSh(PT_GRAY)|
        channelsSh(1)|
        bytesSh(2);

    // LCMS format packed into 32 bit value. For description
    // of this format refer to LCMS documentation.
    private int cmmFormat = 0;

    // Dimensions
    private int rows = 0;
    private int cols = 0;

    //  Scanline may contain some padding in the end
    private int scanlineStride = -1;

    private Object imageData;
    // It's possible to have offset from the beginning of the array
    private int dataOffset;

    // Has the image alpha channel? If has - here its band band offset goes
    private int alphaOffset = -1;

    // initializes proper field IDs
    private static native void initIDs();

    static {
        NativeCMM.loadCMM();
        initIDs();
    }

    ////////////////////////////////////
    // LCMS image format encoders
    ////////////////////////////////////
    private static int colorspaceSh(int s) {
        return (s << 16);
    }

    private static int swapfirstSh(int s) {
        return (s << 14);
    }

    private static int flavorSh(int s) {
        return (s << 13);
    }

    private static int planarSh(int s) {
        return (s << 12);
    }

    private static int endianSh(int s) {
        return (s << 11);
    }

    private static int doswapSh(int s) {
        return (s << 10);
    }

    private static int extraSh(int s) {
        return (s << 7);
    }

    private static int channelsSh(int s) {
        return (s << 3);
    }

    private static int bytesSh(int s) {
        return s;
    }
    ////////////////////////////////////
    // End of LCMS image format encoders
    ////////////////////////////////////

    // Accessors
    Object getChannelData() {
        return imageData;
    }

    int getNumCols() {
        return cols;
    }

    int getNumRows() {
        return rows;
    }

    // Constructors
    public NativeImageFormat() {
    }

    /**
     * Simple image layout for common case with
     * not optimized workflow.
     *
     * For hifi colorspaces with 5+ color channels imgData
     * should be <code>byte</code> array.
     *
     * For common colorspaces with up to 4 color channels it
     * should be <code>short</code> array.
     *
     * Alpha channel is handled by caller, not by CMS.
     *
     * Color channels are in their natural order (not BGR but RGB).
     *
     * @param imgData - array of <code>byte</code> or <code>short</code>
     * @param nChannels - number of channels
     * @param nRows - number of scanlines in the image
     * @param nCols - number of pixels in one row of the image
     */
    public NativeImageFormat(Object imgData, int nChannels, int nRows, int nCols) {
        if (imgData instanceof short[]) {
            cmmFormat |= bytesSh(2);
        }
        else if (imgData instanceof byte[]) {
            cmmFormat |= bytesSh(1);
        }
        else
            // awt.47=First argument should be byte or short array
            throw new IllegalArgumentException(Messages.getString("awt.47")); //$NON-NLS-1$

        cmmFormat |= channelsSh(nChannels);

        rows = nRows;
        cols = nCols;

        imageData = imgData;

        dataOffset = 0;
    }

    /**
     * Deduces image format from the buffered image type
     * or color and sample models.
     * @param bi - image
     * @return image format object
     */
    public static NativeImageFormat createNativeImageFormat(BufferedImage bi) {
        NativeImageFormat fmt = new NativeImageFormat();

        switch (bi.getType()) {
            case BufferedImage.TYPE_INT_RGB: {
                fmt.cmmFormat = INT_RGB_LCMS_FMT;
                break;
            }

            case BufferedImage.TYPE_INT_ARGB:
            case BufferedImage.TYPE_INT_ARGB_PRE: {
                fmt.cmmFormat = INT_ARGB_LCMS_FMT;
                fmt.alphaOffset = 3;
                break;
            }

            case BufferedImage.TYPE_INT_BGR: {
                fmt.cmmFormat = INT_BGR_LCMS_FMT;
                break;
            }

            case BufferedImage.TYPE_3BYTE_BGR: {
                fmt.cmmFormat = THREE_BYTE_BGR_LCMS_FMT;
                break;
            }

            case BufferedImage.TYPE_4BYTE_ABGR_PRE:
            case BufferedImage.TYPE_4BYTE_ABGR: {
                fmt.cmmFormat = FOUR_BYTE_ABGR_LCMS_FMT;
                fmt.alphaOffset = 0;
                break;
            }

            case BufferedImage.TYPE_BYTE_GRAY: {
                fmt.cmmFormat = BYTE_GRAY_LCMS_FMT;
                break;
            }

            case BufferedImage.TYPE_USHORT_GRAY: {
                fmt.cmmFormat = USHORT_GRAY_LCMS_FMT;
                break;
            }

            case BufferedImage.TYPE_BYTE_BINARY:
            case BufferedImage.TYPE_USHORT_565_RGB:
            case BufferedImage.TYPE_USHORT_555_RGB:
            case BufferedImage.TYPE_BYTE_INDEXED: {
                // A bunch of unsupported formats
                return null;
            }

            default:
                break; // Try to look at sample model and color model
        }


        if (fmt.cmmFormat == 0) {
            ColorModel cm = bi.getColorModel();
            SampleModel sm = bi.getSampleModel();

            if (sm instanceof ComponentSampleModel) {
                ComponentSampleModel csm = (ComponentSampleModel) sm;
                fmt.cmmFormat = getFormatFromComponentModel(csm, cm.hasAlpha());
                fmt.scanlineStride = calculateScanlineStrideCSM(csm, bi.getRaster());
            } else if (sm instanceof SinglePixelPackedSampleModel) {
                SinglePixelPackedSampleModel sppsm = (SinglePixelPackedSampleModel) sm;
                fmt.cmmFormat = getFormatFromSPPSampleModel(sppsm, cm.hasAlpha());
                fmt.scanlineStride = calculateScanlineStrideSPPSM(sppsm, bi.getRaster());
            }

            if (cm.hasAlpha())
                fmt.alphaOffset = calculateAlphaOffset(sm, bi.getRaster());
        }

        if (fmt.cmmFormat == 0)
            return null;

        if (!fmt.setImageData(bi.getRaster().getDataBuffer())) {
            return null;
        }

        fmt.rows = bi.getHeight();
        fmt.cols = bi.getWidth();

        fmt.dataOffset = bi.getRaster().getDataBuffer().getOffset();

        return fmt;
    }

    /**
     * Deduces image format from the raster sample model.
     * @param r - raster
     * @return image format object
     */
    public static NativeImageFormat createNativeImageFormat(Raster r) {
        NativeImageFormat fmt = new NativeImageFormat();
        SampleModel sm = r.getSampleModel();

        // Assume that there's no alpha
        if (sm instanceof ComponentSampleModel) {
            ComponentSampleModel csm = (ComponentSampleModel) sm;
            fmt.cmmFormat = getFormatFromComponentModel(csm, false);
            fmt.scanlineStride = calculateScanlineStrideCSM(csm, r);
        } else if (sm instanceof SinglePixelPackedSampleModel) {
            SinglePixelPackedSampleModel sppsm = (SinglePixelPackedSampleModel) sm;
            fmt.cmmFormat = getFormatFromSPPSampleModel(sppsm, false);
            fmt.scanlineStride = calculateScanlineStrideSPPSM(sppsm, r);
        }

        if (fmt.cmmFormat == 0)
            return null;

        fmt.cols = r.getWidth();
        fmt.rows = r.getHeight();
        fmt.dataOffset = r.getDataBuffer().getOffset();

        if (!fmt.setImageData(r.getDataBuffer()))
            return null;

        return fmt;
    }

    /**
     * Obtains LCMS format from the component sample model
     * @param sm - sample model
     * @param hasAlpha - true if there's an alpha channel
     * @return LCMS format
     */
    private static int getFormatFromComponentModel(ComponentSampleModel sm, boolean hasAlpha) {
        // Multiple data arrays (banks) not supported
        int bankIndex = sm.getBankIndices()[0];
        for (int i=1; i < sm.getNumBands(); i++) {
            if (sm.getBankIndices()[i] != bankIndex) {
                return 0;
            }
        }

        int channels = hasAlpha ? sm.getNumBands()-1 : sm.getNumBands();
        int extra = hasAlpha ? 1 : 0;
        int bytes = 1;
        switch (sm.getDataType()) {
            case DataBuffer.TYPE_BYTE:
                bytes = 1; break;
            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_USHORT:
                bytes = 2; break;
            case DataBuffer.TYPE_INT:
                bytes = 4; break;
            case DataBuffer.TYPE_DOUBLE:
                bytes = 0; break;
            default:
                return 0; // Unsupported data type
        }

        int doSwap = 0;
        int swapFirst = 0;
        boolean knownFormat = false;

        int i;

        // "RGBA"
        for (i=0; i < sm.getNumBands(); i++) {
            if (sm.getBandOffsets()[i] != i) break;
        }
        if (i == sm.getNumBands()) { // Ok, it is it
            doSwap = 0;
            swapFirst = 0;
            knownFormat = true;
        }

        // "ARGB"
        if (!knownFormat) {
            for (i=0; i < sm.getNumBands()-1; i++) {
                if (sm.getBandOffsets()[i] != i+1) break;
            }
            if (sm.getBandOffsets()[i] == 0) i++;
            if (i == sm.getNumBands()) { // Ok, it is it
                doSwap = 0;
                swapFirst = 1;
                knownFormat = true;
            }
        }

        // "BGRA"
        if (!knownFormat) {
            for (i=0; i < sm.getNumBands()-1; i++) {
                if (sm.getBandOffsets()[i] != sm.getNumBands() - 2 - i) break;
            }
            if (sm.getBandOffsets()[i] == sm.getNumBands()-1) i++;
            if (i == sm.getNumBands()) { // Ok, it is it
                doSwap = 1;
                swapFirst = 1;
                knownFormat = true;
            }
        }

        // "ABGR"
        if (!knownFormat) {
            for (i=0; i < sm.getNumBands(); i++) {
                if (sm.getBandOffsets()[i] != sm.getNumBands() - 1 - i) break;
            }
            if (i == sm.getNumBands()) { // Ok, it is it
                doSwap = 1;
                swapFirst = 0;
                knownFormat = true;
            }
        }

        // XXX - Planar formats are not supported yet
        if (!knownFormat)
            return 0;

        return
            channelsSh(channels) |
            bytesSh(bytes) |
            extraSh(extra) |
            doswapSh(doSwap) |
            swapfirstSh(swapFirst);
    }

    /**
     * Obtains LCMS format from the single pixel packed sample model
     * @param sm - sample model
     * @param hasAlpha - true if there's an alpha channel
     * @return LCMS format
     */
    private static int getFormatFromSPPSampleModel(SinglePixelPackedSampleModel sm,
            boolean hasAlpha) {
        // Can we extract bytes?
        int mask = sm.getBitMasks()[0] >>> sm.getBitOffsets()[0];
        if (!(mask == 0xFF || mask == 0xFFFF || mask == 0xFFFFFFFF))
            return 0;

        // All masks are same?
        for (int i = 1; i < sm.getNumBands(); i++) {
            if ((sm.getBitMasks()[i] >>> sm.getBitOffsets()[i]) != mask)
                return 0;
        }

        int pixelSize = 0;
        // Check if data type is supported
        if (sm.getDataType() == DataBuffer.TYPE_USHORT)
            pixelSize = 2;
        else if (sm.getDataType() == DataBuffer.TYPE_INT)
            pixelSize = 4;
        else
            return 0;


        int bytes = 0;
        switch (mask) {
            case 0xFF:
                bytes = 1;
                break;
            case 0xFFFF:
                bytes = 2;
                break;
            case 0xFFFFFFFF:
                bytes = 4;
                break;
            default: return 0;
        }


        int channels = hasAlpha ? sm.getNumBands()-1 : sm.getNumBands();
        int extra = hasAlpha ? 1 : 0;
        extra +=  pixelSize/bytes - sm.getNumBands(); // Unused bytes?

        // Form an ArrayList containing offset for each band
        ArrayList<Integer> offsetsLst = new ArrayList<Integer>();
        for (int k=0; k < sm.getNumBands(); k++) {
            offsetsLst.add(new Integer(sm.getBitOffsets()[k]/(bytes*8)));
        }

        // Add offsets for unused space
        for (int i=0; i<pixelSize/bytes; i++) {
            if (offsetsLst.indexOf(new Integer(i)) < 0)
                offsetsLst.add(new Integer(i));
        }

        int offsets[] = new int[pixelSize/bytes];
        for (int i=0; i<offsetsLst.size(); i++) {
            offsets[i] = offsetsLst.get(i).intValue();
        }

        int doSwap = 0;
        int swapFirst = 0;
        boolean knownFormat = false;

        int i;

        // "RGBA"
        for (i=0; i < pixelSize; i++) {
            if (offsets[i] != i) break;
        }
        if (i == pixelSize) { // Ok, it is it
            doSwap = 0;
            swapFirst = 0;
            knownFormat = true;
        }

        // "ARGB"
        if (!knownFormat) {
            for (i=0; i < pixelSize-1; i++) {
                if (offsets[i] != i+1) break;
            }
            if (offsets[i] == 0) i++;
            if (i == pixelSize) { // Ok, it is it
                doSwap = 0;
                swapFirst = 1;
                knownFormat = true;
            }
        }

        // "BGRA"
        if (!knownFormat) {
            for (i=0; i < pixelSize-1; i++) {
                if (offsets[i] != pixelSize - 2 - i) break;
            }
            if (offsets[i] == pixelSize-1) i++;
            if (i == pixelSize) { // Ok, it is it
                doSwap = 1;
                swapFirst = 1;
                knownFormat = true;
            }
        }

        // "ABGR"
        if (!knownFormat) {
            for (i=0; i < pixelSize; i++) {
                if (offsets[i] != pixelSize - 1 - i) break;
            }
            if (i == pixelSize) { // Ok, it is it
                doSwap = 1;
                swapFirst = 0;
                knownFormat = true;
            }
        }

        // XXX - Planar formats are not supported yet
        if (!knownFormat)
            return 0;

        return
            channelsSh(channels) |
            bytesSh(bytes) |
            extraSh(extra) |
            doswapSh(doSwap) |
            swapfirstSh(swapFirst);
    }

    /**
     * Obtains data array from the DataBuffer object
     * @param db - data buffer
     * @return - true if successful
     */
    private boolean setImageData(DataBuffer db) {
        AwtImageBackdoorAccessor dbAccess = AwtImageBackdoorAccessor.getInstance();
        try {
            imageData = dbAccess.getData(db);
        } catch (IllegalArgumentException e) {
            return false; // Unknown data buffer type
        }

        return true;
    }

    /**
     * Calculates scanline stride in bytes
     * @param csm - component sample model
     * @param r - raster
     * @return scanline stride in bytes
     */
    private static int calculateScanlineStrideCSM(ComponentSampleModel csm, Raster r) {
        if (csm.getScanlineStride() != csm.getPixelStride()*csm.getWidth()) {
            int dataTypeSize = DataBuffer.getDataTypeSize(r.getDataBuffer().getDataType()) / 8;
            return csm.getScanlineStride()*dataTypeSize;
        }
        return -1;
    }

    /**
     * Calculates scanline stride in bytes
     * @param sppsm - sample model
     * @param r - raster
     * @return scanline stride in bytes
     */
    private static int calculateScanlineStrideSPPSM(SinglePixelPackedSampleModel sppsm, Raster r) {
        if (sppsm.getScanlineStride() != sppsm.getWidth()) {
            int dataTypeSize = DataBuffer.getDataTypeSize(r.getDataBuffer().getDataType()) / 8;
            return sppsm.getScanlineStride()*dataTypeSize;
        }
        return -1;
    }

    /**
     * Calculates byte offset of the alpha channel from the beginning of the pixel data
     * @param sm - sample model
     * @param r - raster
     * @return byte offset of the alpha channel
     */
    private static int calculateAlphaOffset(SampleModel sm, Raster r) {
        if (sm instanceof ComponentSampleModel) {
            ComponentSampleModel csm = (ComponentSampleModel) sm;
            int dataTypeSize =
                DataBuffer.getDataTypeSize(r.getDataBuffer().getDataType()) / 8;
            return
                csm.getBandOffsets()[csm.getBandOffsets().length - 1] * dataTypeSize;
        } else if (sm instanceof SinglePixelPackedSampleModel) {
            SinglePixelPackedSampleModel sppsm = (SinglePixelPackedSampleModel) sm;
            return sppsm.getBitOffsets()[sppsm.getBitOffsets().length - 1] / 8;
        } else {
            return -1; // No offset, don't copy alpha
        }
    }
}
