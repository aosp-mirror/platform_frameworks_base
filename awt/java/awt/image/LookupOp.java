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
 *
 * @date: Oct 14, 2005
 */

package java.awt.image;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Point2D;
import java.util.Arrays;

import org.apache.harmony.awt.gl.AwtImageBackdoorAccessor;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The LookupOp class performs a lookup operation which transforms a source
 * image by filtering each band using a table of data. The table may contain a
 * single array or it may contain a different data array for each band of the
 * image.
 * 
 * @since Android 1.0
 */
public class LookupOp implements BufferedImageOp, RasterOp {

    /**
     * The lut.
     */
    private final LookupTable lut;

    /**
     * The hints.
     */
    private RenderingHints hints;

    // TODO remove when this field is used
    /**
     * The can use ipp.
     */
    @SuppressWarnings("unused")
    private final boolean canUseIpp;

    // We don't create levels/values when it is possible to reuse old
    /**
     * The cached levels.
     */
    private int cachedLevels[];

    /**
     * The cached values.
     */
    private int cachedValues[];

    // Number of channels for which cache is valid.
    // If negative number of channels is same as positive but skipAlpha was
    // specified
    /**
     * The valid for channels.
     */
    private int validForChannels;

    /**
     * The level initializer.
     */
    static int levelInitializer[] = new int[0x10000];

    static {
        // TODO
        // System.loadLibrary("imageops");

        for (int i = 1; i <= 0x10000; i++) {
            levelInitializer[i - 1] = i;
        }
    }

    /**
     * Instantiates a new LookupOp object from the specified LookupTable object
     * and a RenderingHints object.
     * 
     * @param lookup
     *            the specified LookupTable object.
     * @param hints
     *            the RenderingHints object or null.
     */
    public LookupOp(LookupTable lookup, RenderingHints hints) {
        if (lookup == null) {
            throw new NullPointerException(Messages.getString("awt.01", "lookup")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        lut = lookup;
        this.hints = hints;
        canUseIpp = lut instanceof ByteLookupTable || lut instanceof ShortLookupTable;
    }

    /**
     * Gets the LookupTable of the specified Object.
     * 
     * @return the LookupTable of the specified Object.
     */
    public final LookupTable getTable() {
        return lut;
    }

    public final RenderingHints getRenderingHints() {
        return hints;
    }

    public final Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt == null) {
            dstPt = new Point2D.Float();
        }

        dstPt.setLocation(srcPt);
        return dstPt;
    }

    public final Rectangle2D getBounds2D(Raster src) {
        return src.getBounds();
    }

    public final Rectangle2D getBounds2D(BufferedImage src) {
        return getBounds2D(src.getRaster());
    }

    public WritableRaster createCompatibleDestRaster(Raster src) {
        return src.createCompatibleWritableRaster();
    }

    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel dstCM) {
        if (dstCM == null) {
            dstCM = src.getColorModel();

            // Sync transfer type with LUT for component color model
            if (dstCM instanceof ComponentColorModel) {
                int transferType = dstCM.getTransferType();
                if (lut instanceof ByteLookupTable) {
                    transferType = DataBuffer.TYPE_BYTE;
                } else if (lut instanceof ShortLookupTable) {
                    transferType = DataBuffer.TYPE_SHORT;
                }

                dstCM = new ComponentColorModel(dstCM.cs, dstCM.hasAlpha(),
                        dstCM.isAlphaPremultiplied, dstCM.transparency, transferType);
            }
        }

        WritableRaster r = dstCM.isCompatibleSampleModel(src.getSampleModel()) ? src.getRaster()
                .createCompatibleWritableRaster(src.getWidth(), src.getHeight()) : dstCM
                .createCompatibleWritableRaster(src.getWidth(), src.getHeight());

        return new BufferedImage(dstCM, r, dstCM.isAlphaPremultiplied(), null);
    }

    public final WritableRaster filter(Raster src, WritableRaster dst) {
        if (dst == null) {
            dst = createCompatibleDestRaster(src);
        } else {
            if (src.getNumBands() != dst.getNumBands()) {
                throw new IllegalArgumentException(Messages.getString("awt.237")); //$NON-NLS-1$            }
            }
            if (src.getWidth() != dst.getWidth()) {
                throw new IllegalArgumentException(Messages.getString("awt.28F")); //$NON-NLS-1$            }
            }
            if (src.getHeight() != dst.getHeight()) {
                throw new IllegalArgumentException(Messages.getString("awt.290")); //$NON-NLS-1$            }
            }
        }

        if (lut.getNumComponents() != 1 && lut.getNumComponents() != src.getNumBands()) {
            // awt.238=The number of arrays in the LookupTable does not meet the
            // restrictions
            throw new IllegalArgumentException(Messages.getString("awt.238")); //$NON-NLS-1$
        }

        // TODO
        // if (!canUseIpp || ippFilter(src, dst, BufferedImage.TYPE_CUSTOM,
        // false) != 0)
        if (slowFilter(src, dst, false) != 0) {
            // awt.21F=Unable to transform source
            throw new ImagingOpException(Messages.getString("awt.21F")); //$NON-NLS-1$
        }

        return dst;
    }

    public final BufferedImage filter(BufferedImage src, BufferedImage dst) {
        ColorModel srcCM = src.getColorModel();

        if (srcCM instanceof IndexColorModel) {
            // awt.220=Source should not have IndexColorModel
            throw new IllegalArgumentException(Messages.getString("awt.220")); //$NON-NLS-1$
        }

        // Check if the number of scaling factors matches the number of bands
        int nComponents = srcCM.getNumComponents();
        int nLUTComponents = lut.getNumComponents();
        boolean skipAlpha;
        if (srcCM.hasAlpha()) {
            if (nLUTComponents == 1 || nLUTComponents == nComponents - 1) {
                skipAlpha = true;
            } else if (nLUTComponents == nComponents) {
                skipAlpha = false;
            } else {
                // awt.229=Number of components in the LUT does not match the
                // number of bands
                throw new IllegalArgumentException(Messages.getString("awt.229")); //$NON-NLS-1$
            }
        } else if (nLUTComponents == 1 || nLUTComponents == nComponents) {
            skipAlpha = false;
        } else {
            // awt.229=Number of components in the LUT does not match the number
            // of bands
            throw new IllegalArgumentException(Messages.getString("awt.229")); //$NON-NLS-1$
        }

        BufferedImage finalDst = null;
        if (dst == null) {
            finalDst = dst;
            dst = createCompatibleDestImage(src, null);
        } else {
            if (src.getWidth() != dst.getWidth()) {
                throw new IllegalArgumentException(Messages.getString("awt.291")); //$NON-NLS-1$
            }

            if (src.getHeight() != dst.getHeight()) {
                throw new IllegalArgumentException(Messages.getString("awt.292")); //$NON-NLS-1$
            }

            if (!srcCM.equals(dst.getColorModel())) {
                // Treat BufferedImage.TYPE_INT_RGB and
                // BufferedImage.TYPE_INT_ARGB as same
                if (!((src.getType() == BufferedImage.TYPE_INT_RGB || src.getType() == BufferedImage.TYPE_INT_ARGB) && (dst
                        .getType() == BufferedImage.TYPE_INT_RGB || dst.getType() == BufferedImage.TYPE_INT_ARGB))) {
                    finalDst = dst;
                    dst = createCompatibleDestImage(src, null);
                }
            }
        }

        // TODO
        // if (!canUseIpp || ippFilter(src.getRaster(), dst.getRaster(),
        // src.getType(), skipAlpha) != 0)
        if (slowFilter(src.getRaster(), dst.getRaster(), skipAlpha) != 0) {
            // awt.21F=Unable to transform source
            throw new ImagingOpException(Messages.getString("awt.21F")); //$NON-NLS-1$
        }

        if (finalDst != null) {
            Graphics2D g = finalDst.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(dst, 0, 0, null);
        } else {
            finalDst = dst;
        }

        return dst;
    }

    /**
     * Slow filter.
     * 
     * @param src
     *            the src.
     * @param dst
     *            the dst.
     * @param skipAlpha
     *            the skip alpha.
     * @return the int.
     */
    private final int slowFilter(Raster src, WritableRaster dst, boolean skipAlpha) {
        int minSrcX = src.getMinX();
        int minDstX = dst.getMinX();
        int minSrcY = src.getMinY();
        int minDstY = dst.getMinY();

        int skippingChannels = skipAlpha ? 1 : 0;
        int numBands2Process = src.getNumBands() - skippingChannels;

        int numBands = src.getNumBands();
        int srcHeight = src.getHeight();
        int srcWidth = src.getWidth();

        int[] pixels = null;
        int offset = lut.getOffset();

        if (lut instanceof ByteLookupTable) {
            byte[][] byteData = ((ByteLookupTable)lut).getTable();
            pixels = src.getPixels(minSrcX, minSrcY, srcWidth, srcHeight, pixels);

            if (lut.getNumComponents() != 1) {
                for (int i = 0; i < pixels.length; i += numBands) {
                    for (int b = 0; b < numBands2Process; b++) {
                        pixels[i + b] = byteData[b][pixels[i + b] - offset] & 0xFF;
                    }
                }
            } else {
                for (int i = 0; i < pixels.length; i += numBands) {
                    for (int b = 0; b < numBands2Process; b++) {
                        pixels[i + b] = byteData[0][pixels[i + b] - offset] & 0xFF;
                    }
                }
            }

            dst.setPixels(minDstX, minDstY, srcWidth, srcHeight, pixels);
        } else if (lut instanceof ShortLookupTable) {
            short[][] shortData = ((ShortLookupTable)lut).getTable();
            pixels = src.getPixels(minSrcX, minSrcY, srcWidth, srcHeight, pixels);

            if (lut.getNumComponents() != 1) {
                for (int i = 0; i < pixels.length; i += numBands) {
                    for (int b = 0; b < numBands2Process; b++) {
                        pixels[i + b] = shortData[b][pixels[i + b] - offset] & 0xFFFF;
                    }
                }
            } else {
                for (int i = 0; i < pixels.length; i += numBands) {
                    for (int b = 0; b < numBands2Process; b++) {
                        pixels[i + b] = shortData[0][pixels[i + b] - offset] & 0xFFFF;
                    }
                }
            }

            dst.setPixels(minDstX, minDstY, srcWidth, srcHeight, pixels);
        } else {
            int pixel[] = new int[src.getNumBands()];
            int maxY = minSrcY + srcHeight;
            int maxX = minSrcX + srcWidth;
            for (int srcY = minSrcY, dstY = minDstY; srcY < maxY; srcY++, dstY++) {
                for (int srcX = minSrcX, dstX = minDstX; srcX < maxX; srcX++, dstX++) {
                    src.getPixel(srcX, srcY, pixel);
                    lut.lookupPixel(pixel, pixel);
                    dst.setPixel(dstX, dstY, pixel);
                }
            }
        }

        return 0;
    }

    /**
     * Creates the byte levels.
     * 
     * @param channels
     *            the channels.
     * @param skipAlpha
     *            the skip alpha.
     * @param levels
     *            the levels.
     * @param values
     *            the values.
     * @param channelsOrder
     *            the channels order.
     */
    private final void createByteLevels(int channels, boolean skipAlpha, int levels[],
            int values[], int channelsOrder[]) {
        byte data[][] = ((ByteLookupTable)lut).getTable();
        int nLevels = data[0].length;
        int offset = lut.getOffset();

        // Use one data array for all channels or use several data arrays
        int dataIncrement = data.length > 1 ? 1 : 0;

        for (int ch = 0, dataIdx = 0; ch < channels; dataIdx += dataIncrement, ch++) {
            int channelOffset = channelsOrder == null ? ch : channelsOrder[ch];
            int channelBase = nLevels * channelOffset;

            // Skip last channel if needed, zero values are OK -
            // no changes to the channel information will be done in IPP
            if ((channelOffset == channels - 1 && skipAlpha) || (dataIdx >= data.length)) {
                continue;
            }

            System.arraycopy(levelInitializer, offset, levels, channelBase, nLevels);
            for (int from = 0, to = channelBase; from < nLevels; from++, to++) {
                values[to] = data[dataIdx][from] & 0xFF;
            }
        }
    }

    /**
     * Creates the short levels.
     * 
     * @param channels
     *            the channels.
     * @param skipAlpha
     *            the skip alpha.
     * @param levels
     *            the levels.
     * @param values
     *            the values.
     * @param channelsOrder
     *            the channels order.
     */
    private final void createShortLevels(int channels, boolean skipAlpha, int levels[],
            int values[], int channelsOrder[]) {
        short data[][] = ((ShortLookupTable)lut).getTable();
        int nLevels = data[0].length;
        int offset = lut.getOffset();

        // Use one data array for all channels or use several data arrays
        int dataIncrement = data.length > 1 ? 1 : 0;

        for (int ch = 0, dataIdx = 0; ch < channels; dataIdx += dataIncrement, ch++) {
            int channelOffset = channelsOrder == null ? ch : channelsOrder[ch];

            // Skip last channel if needed, zero values are OK -
            // no changes to the channel information will be done in IPP
            if ((channelOffset == channels - 1 && skipAlpha) || (dataIdx >= data.length)) {
                continue;
            }

            int channelBase = nLevels * channelOffset;
            System.arraycopy(levelInitializer, offset, levels, channelBase, nLevels);
            for (int from = 0, to = channelBase; from < nLevels; from++, to++) {
                values[to] = data[dataIdx][from] & 0xFFFF;
            }
        }
    }

    // TODO remove when this method is used
    /**
     * Ipp filter.
     * 
     * @param src
     *            the src.
     * @param dst
     *            the dst.
     * @param imageType
     *            the image type.
     * @param skipAlpha
     *            the skip alpha.
     * @return the int.
     */
    @SuppressWarnings("unused")
    private final int ippFilter(Raster src, WritableRaster dst, int imageType, boolean skipAlpha) {
        int res;

        int srcStride, dstStride;
        int channels;
        int offsets[] = null;
        int channelsOrder[] = null;

        switch (imageType) {
            case BufferedImage.TYPE_INT_ARGB:
            case BufferedImage.TYPE_INT_ARGB_PRE:
            case BufferedImage.TYPE_INT_RGB: {
                channels = 4;
                srcStride = src.getWidth() * 4;
                dstStride = dst.getWidth() * 4;
                channelsOrder = new int[] {
                        2, 1, 0, 3
                };
                break;
            }

            case BufferedImage.TYPE_4BYTE_ABGR:
            case BufferedImage.TYPE_4BYTE_ABGR_PRE:
            case BufferedImage.TYPE_INT_BGR: {
                channels = 4;
                srcStride = src.getWidth() * 4;
                dstStride = dst.getWidth() * 4;
                break;
            }

            case BufferedImage.TYPE_BYTE_GRAY: {
                channels = 1;
                srcStride = src.getWidth();
                dstStride = dst.getWidth();
                break;
            }

            case BufferedImage.TYPE_3BYTE_BGR: {
                channels = 3;
                srcStride = src.getWidth() * 3;
                dstStride = dst.getWidth() * 3;
                channelsOrder = new int[] {
                        2, 1, 0
                };
                break;
            }

            case BufferedImage.TYPE_USHORT_GRAY:
            case BufferedImage.TYPE_USHORT_565_RGB:
            case BufferedImage.TYPE_USHORT_555_RGB:
            case BufferedImage.TYPE_BYTE_BINARY: {
                return slowFilter(src, dst, skipAlpha);
            }

            default: {
                SampleModel srcSM = src.getSampleModel();
                SampleModel dstSM = dst.getSampleModel();

                if (srcSM instanceof PixelInterleavedSampleModel
                        && dstSM instanceof PixelInterleavedSampleModel) {
                    // Check PixelInterleavedSampleModel
                    if (srcSM.getDataType() != DataBuffer.TYPE_BYTE
                            || dstSM.getDataType() != DataBuffer.TYPE_BYTE) {
                        return slowFilter(src, dst, skipAlpha);
                    }

                    // Have IPP functions for 1, 3 and 4 channels
                    channels = srcSM.getNumBands();
                    if (!(channels == 1 || channels == 3 || channels == 4)) {
                        return slowFilter(src, dst, skipAlpha);
                    }

                    srcStride = ((ComponentSampleModel)srcSM).getScanlineStride();
                    dstStride = ((ComponentSampleModel)dstSM).getScanlineStride();

                    channelsOrder = ((ComponentSampleModel)srcSM).getBandOffsets();
                } else if (srcSM instanceof SinglePixelPackedSampleModel
                        && dstSM instanceof SinglePixelPackedSampleModel) {
                    // Check SinglePixelPackedSampleModel
                    SinglePixelPackedSampleModel sppsm1 = (SinglePixelPackedSampleModel)srcSM;
                    SinglePixelPackedSampleModel sppsm2 = (SinglePixelPackedSampleModel)dstSM;

                    channels = sppsm1.getNumBands();

                    // TYPE_INT_RGB, TYPE_INT_ARGB...
                    if (sppsm1.getDataType() != DataBuffer.TYPE_INT
                            || sppsm2.getDataType() != DataBuffer.TYPE_INT
                            || !(channels == 3 || channels == 4)) {
                        return slowFilter(src, dst, skipAlpha);
                    }

                    // Check compatibility of sample models
                    if (!Arrays.equals(sppsm1.getBitOffsets(), sppsm2.getBitOffsets())
                            || !Arrays.equals(sppsm1.getBitMasks(), sppsm2.getBitMasks())) {
                        return slowFilter(src, dst, skipAlpha);
                    }

                    for (int i = 0; i < channels; i++) {
                        if (sppsm1.getSampleSize(i) != 8) {
                            return slowFilter(src, dst, skipAlpha);
                        }
                    }

                    channelsOrder = new int[channels];
                    int bitOffsets[] = sppsm1.getBitOffsets();
                    for (int i = 0; i < channels; i++) {
                        channelsOrder[i] = bitOffsets[i] / 8;
                    }

                    if (channels == 3) { // Don't skip channel now, could be
                        // optimized
                        channels = 4;
                    }

                    srcStride = sppsm1.getScanlineStride() * 4;
                    dstStride = sppsm2.getScanlineStride() * 4;
                } else {
                    return slowFilter(src, dst, skipAlpha);
                }

                // Fill offsets if there's a child raster
                if (src.getParent() != null || dst.getParent() != null) {
                    if (src.getSampleModelTranslateX() != 0 || src.getSampleModelTranslateY() != 0
                            || dst.getSampleModelTranslateX() != 0
                            || dst.getSampleModelTranslateY() != 0) {
                        offsets = new int[4];
                        offsets[0] = -src.getSampleModelTranslateX() + src.getMinX();
                        offsets[1] = -src.getSampleModelTranslateY() + src.getMinY();
                        offsets[2] = -dst.getSampleModelTranslateX() + dst.getMinX();
                        offsets[3] = -dst.getSampleModelTranslateY() + dst.getMinY();
                    }
                }
            }
        }

        int levels[] = null, values[] = null;
        int channelMultiplier = skipAlpha ? -1 : 1;
        if (channelMultiplier * channels == validForChannels) { // use existing
            // levels/values
            levels = cachedLevels;
            values = cachedValues;
        } else { // create new levels/values
            if (lut instanceof ByteLookupTable) {
                byte data[][] = ((ByteLookupTable)lut).getTable();
                levels = new int[channels * data[0].length];
                values = new int[channels * data[0].length];
                createByteLevels(channels, skipAlpha, levels, values, channelsOrder);
            } else if (lut instanceof ShortLookupTable) {
                short data[][] = ((ShortLookupTable)lut).getTable();
                levels = new int[channels * data[0].length];
                values = new int[channels * data[0].length];
                createShortLevels(channels, skipAlpha, levels, values, channelsOrder);
            }

            // cache levels/values
            validForChannels = channelMultiplier * channels;
            cachedLevels = levels;
            cachedValues = values;
        }

        Object srcData, dstData;
        AwtImageBackdoorAccessor dbAccess = AwtImageBackdoorAccessor.getInstance();
        try {
            srcData = dbAccess.getData(src.getDataBuffer());
            dstData = dbAccess.getData(dst.getDataBuffer());
        } catch (IllegalArgumentException e) {
            return -1; // Unknown data buffer type
        }

        res = ippLUT(srcData, src.getWidth(), src.getHeight(), srcStride, dstData, dst.getWidth(),
                dst.getHeight(), dstStride, levels, values, channels, offsets, false);

        return res;
    }

    /**
     * Ipp lut.
     * 
     * @param src
     *            the src.
     * @param srcWidth
     *            the src width.
     * @param srcHeight
     *            the src height.
     * @param srcStride
     *            the src stride.
     * @param dst
     *            the dst.
     * @param dstWidth
     *            the dst width.
     * @param dstHeight
     *            the dst height.
     * @param dstStride
     *            the dst stride.
     * @param levels
     *            the levels.
     * @param values
     *            the values.
     * @param channels
     *            the channels.
     * @param offsets
     *            the offsets.
     * @param linear
     *            the linear.
     * @return the int.
     */
    final static native int ippLUT(Object src, int srcWidth, int srcHeight, int srcStride,
            Object dst, int dstWidth, int dstHeight, int dstStride, int levels[], int values[],
            int channels, int offsets[], boolean linear);
}
