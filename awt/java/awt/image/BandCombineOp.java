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
 * @date: Sep 20, 2005
 */

package java.awt.image;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

import org.apache.harmony.awt.gl.AwtImageBackdoorAccessor;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The BandCombineOp class translates coordinates from coordinates in the source
 * Raster to coordinates in the destination Raster by an arbitrary linear
 * combination of the bands in a source Raster, using a specified matrix. The
 * number of bands in the matrix should equal to the number of bands in the
 * source Raster plus 1.
 * 
 * @since Android 1.0
 */
public class BandCombineOp implements RasterOp {

    /**
     * The Constant offsets3c.
     */
    static final int offsets3c[] = {
            16, 8, 0
    };

    /**
     * The Constant offsets4ac.
     */
    static final int offsets4ac[] = {
            16, 8, 0, 24
    };

    /**
     * The Constant masks3c.
     */
    static final int masks3c[] = {
            0xFF0000, 0xFF00, 0xFF
    };

    /**
     * The Constant masks4ac.
     */
    static final int masks4ac[] = {
            0xFF0000, 0xFF00, 0xFF, 0xFF000000
    };

    /**
     * The Constant piOffsets.
     */
    private static final int piOffsets[] = {
            0, 1, 2
    };

    /**
     * The Constant piInvOffsets.
     */
    private static final int piInvOffsets[] = {
            2, 1, 0
    };

    /**
     * The Constant TYPE_BYTE3C.
     */
    private static final int TYPE_BYTE3C = 0;

    /**
     * The Constant TYPE_BYTE4AC.
     */
    private static final int TYPE_BYTE4AC = 1;

    /**
     * The Constant TYPE_USHORT3C.
     */
    private static final int TYPE_USHORT3C = 2;

    /**
     * The Constant TYPE_SHORT3C.
     */
    private static final int TYPE_SHORT3C = 3;

    /**
     * The mx width.
     */
    private int mxWidth;

    /**
     * The mx height.
     */
    private int mxHeight;

    /**
     * The matrix.
     */
    private float matrix[][];

    /**
     * The r hints.
     */
    private RenderingHints rHints;

    static {
        // XXX - todo
        // System.loadLibrary("imageops");
    }

    /**
     * Instantiates a new BandCombineOp object with the specified matrix.
     * 
     * @param matrix
     *            the specified matrix for band combining.
     * @param hints
     *            the RenderingHints.
     */
    public BandCombineOp(float matrix[][], RenderingHints hints) {
        this.mxHeight = matrix.length;
        this.mxWidth = matrix[0].length;
        this.matrix = new float[mxHeight][mxWidth];

        for (int i = 0; i < mxHeight; i++) {
            System.arraycopy(matrix[i], 0, this.matrix[i], 0, mxWidth);
        }

        this.rHints = hints;
    }

    public final RenderingHints getRenderingHints() {
        return this.rHints;
    }

    /**
     * Gets the matrix associated with this BandCombineOp object.
     * 
     * @return the matrix associated with this BandCombineOp object.
     */
    public final float[][] getMatrix() {
        float res[][] = new float[mxHeight][mxWidth];

        for (int i = 0; i < mxHeight; i++) {
            System.arraycopy(matrix[i], 0, res[i], 0, mxWidth);
        }

        return res;
    }

    public final Point2D getPoint2D(Point2D srcPoint, Point2D dstPoint) {
        if (dstPoint == null) {
            dstPoint = new Point2D.Float();
        }

        dstPoint.setLocation(srcPoint);
        return dstPoint;
    }

    public final Rectangle2D getBounds2D(Raster src) {
        return src.getBounds();
    }

    public WritableRaster createCompatibleDestRaster(Raster src) {
        int numBands = src.getNumBands();
        if (mxWidth != numBands && mxWidth != (numBands + 1) || numBands != mxHeight) {
            // awt.254=Number of bands in the source raster ({0}) is
            // incompatible with the matrix [{1}x{2}]
            throw new IllegalArgumentException(Messages.getString("awt.254", //$NON-NLS-1$
                    new Object[] {
                            numBands, mxWidth, mxHeight
                    }));
        }

        return src.createCompatibleWritableRaster(src.getWidth(), src.getHeight());
    }

    public WritableRaster filter(Raster src, WritableRaster dst) {
        int numBands = src.getNumBands();

        if (mxWidth != numBands && mxWidth != (numBands + 1)) {
            // awt.254=Number of bands in the source raster ({0}) is
            // incompatible with the matrix [{1}x{2}]
            throw new IllegalArgumentException(Messages.getString("awt.254", //$NON-NLS-1$
                    new Object[] {
                            numBands, mxWidth, mxHeight
                    }));
        }

        if (dst == null) {
            dst = createCompatibleDestRaster(src);
        } else if (dst.getNumBands() != mxHeight) {
            // awt.255=Number of bands in the destination raster ({0}) is
            // incompatible with the matrix [{1}x{2}]
            throw new IllegalArgumentException(Messages.getString("awt.255", //$NON-NLS-1$
                    new Object[] {
                            dst.getNumBands(), mxWidth, mxHeight
                    }));
        }

        // XXX - todo
        // if (ippFilter(src, dst) != 0)
        if (verySlowFilter(src, dst) != 0) {
            // awt.21F=Unable to transform source
            throw new ImagingOpException(Messages.getString("awt.21F")); //$NON-NLS-1$
        }

        return dst;
    }

    /**
     * The Class SampleModelInfo.
     */
    private static final class SampleModelInfo {

        /**
         * The channels.
         */
        int channels;

        /**
         * The channels order.
         */
        int channelsOrder[];

        /**
         * The stride.
         */
        int stride;
    }

    /**
     * Check sample model.
     * 
     * @param sm
     *            the sm.
     * @return the sample model info.
     */
    private final SampleModelInfo checkSampleModel(SampleModel sm) {
        SampleModelInfo ret = new SampleModelInfo();

        if (sm instanceof PixelInterleavedSampleModel) {
            // Check PixelInterleavedSampleModel
            if (sm.getDataType() != DataBuffer.TYPE_BYTE) {
                return null;
            }

            ret.channels = sm.getNumBands();
            ret.stride = ((ComponentSampleModel)sm).getScanlineStride();
            ret.channelsOrder = ((ComponentSampleModel)sm).getBandOffsets();

        } else if (sm instanceof SinglePixelPackedSampleModel) {
            // Check SinglePixelPackedSampleModel
            SinglePixelPackedSampleModel sppsm1 = (SinglePixelPackedSampleModel)sm;

            ret.channels = sppsm1.getNumBands();
            if (sppsm1.getDataType() != DataBuffer.TYPE_INT) {
                return null;
            }

            // Check sample models
            for (int i = 0; i < ret.channels; i++) {
                if (sppsm1.getSampleSize(i) != 8) {
                    return null;
                }
            }

            ret.channelsOrder = new int[ret.channels];
            int bitOffsets[] = sppsm1.getBitOffsets();
            for (int i = 0; i < ret.channels; i++) {
                if (bitOffsets[i] % 8 != 0) {
                    return null;
                }

                ret.channelsOrder[i] = bitOffsets[i] / 8;
            }

            ret.channels = 4;
            ret.stride = sppsm1.getScanlineStride() * 4;
        } else {
            return null;
        }

        return ret;
    }

    /**
     * Slow filter.
     * 
     * @param src
     *            the src.
     * @param dst
     *            the dst.
     * @return the int.
     */
    private final int slowFilter(Raster src, WritableRaster dst) {
        int res = 0;

        SampleModelInfo srcInfo, dstInfo;
        int offsets[] = null;

        srcInfo = checkSampleModel(src.getSampleModel());
        dstInfo = checkSampleModel(dst.getSampleModel());
        if (srcInfo == null || dstInfo == null) {
            return verySlowFilter(src, dst);
        }

        // Fill offsets if there's a child raster
        if (src.getParent() != null || dst.getParent() != null) {
            if (src.getSampleModelTranslateX() != 0 || src.getSampleModelTranslateY() != 0
                    || dst.getSampleModelTranslateX() != 0 || dst.getSampleModelTranslateY() != 0) {
                offsets = new int[4];
                offsets[0] = -src.getSampleModelTranslateX() + src.getMinX();
                offsets[1] = -src.getSampleModelTranslateY() + src.getMinY();
                offsets[2] = -dst.getSampleModelTranslateX() + dst.getMinX();
                offsets[3] = -dst.getSampleModelTranslateY() + dst.getMinY();
            }
        }

        int rmxWidth = (srcInfo.channels + 1); // width of the reordered matrix
        float reorderedMatrix[] = new float[rmxWidth * dstInfo.channels];
        for (int j = 0; j < dstInfo.channels; j++) {
            if (j >= dstInfo.channelsOrder.length) {
                continue;
            }

            for (int i = 0; i < srcInfo.channels; i++) {
                if (i >= srcInfo.channelsOrder.length) {
                    break;
                }

                reorderedMatrix[dstInfo.channelsOrder[j] * rmxWidth + srcInfo.channelsOrder[i]] = matrix[j][i];
            }
            if (mxWidth == rmxWidth) {
                reorderedMatrix[(dstInfo.channelsOrder[j] + 1) * rmxWidth - 1] = matrix[j][mxWidth - 1];
            }
        }

        Object srcData, dstData;
        AwtImageBackdoorAccessor dbAccess = AwtImageBackdoorAccessor.getInstance();
        try {
            srcData = dbAccess.getData(src.getDataBuffer());
            dstData = dbAccess.getData(dst.getDataBuffer());
        } catch (IllegalArgumentException e) {
            return -1; // Unknown data buffer type
        }

        simpleCombineBands(srcData, src.getWidth(), src.getHeight(), srcInfo.stride,
                srcInfo.channels, dstData, dstInfo.stride, dstInfo.channels, reorderedMatrix,
                offsets);

        return res;
    }

    /**
     * Very slow filter.
     * 
     * @param src
     *            the src.
     * @param dst
     *            the dst.
     * @return the int.
     */
    private int verySlowFilter(Raster src, WritableRaster dst) {
        int numBands = src.getNumBands();

        int srcMinX = src.getMinX();
        int srcY = src.getMinY();

        int dstMinX = dst.getMinX();
        int dstY = dst.getMinY();

        int dX = src.getWidth();// < dst.getWidth() ? src.getWidth() :
        // dst.getWidth();
        int dY = src.getHeight();// < dst.getHeight() ? src.getHeight() :
        // dst.getHeight();

        float sample;
        int srcPixels[] = new int[numBands * dX * dY];
        int dstPixels[] = new int[mxHeight * dX * dY];

        srcPixels = src.getPixels(srcMinX, srcY, dX, dY, srcPixels);

        if (numBands == mxWidth) {
            for (int i = 0, j = 0; i < srcPixels.length; i += numBands) {
                for (int dstB = 0; dstB < mxHeight; dstB++) {
                    sample = 0f;
                    for (int srcB = 0; srcB < numBands; srcB++) {
                        sample += matrix[dstB][srcB] * srcPixels[i + srcB];
                    }
                    dstPixels[j++] = (int)sample;
                }
            }
        } else {
            for (int i = 0, j = 0; i < srcPixels.length; i += numBands) {
                for (int dstB = 0; dstB < mxHeight; dstB++) {
                    sample = 0f;
                    for (int srcB = 0; srcB < numBands; srcB++) {
                        sample += matrix[dstB][srcB] * srcPixels[i + srcB];
                    }
                    dstPixels[j++] = (int)(sample + matrix[dstB][numBands]);
                }
            }
        }

        dst.setPixels(dstMinX, dstY, dX, dY, dstPixels);

        return 0;
    }

    // TODO remove when method is used
    /**
     * Ipp filter.
     * 
     * @param src
     *            the src.
     * @param dst
     *            the dst.
     * @return the int.
     */
    @SuppressWarnings("unused")
    private int ippFilter(Raster src, WritableRaster dst) {
        boolean invertChannels;
        boolean inPlace = (src == dst);
        int type;
        int srcStride, dstStride;
        int offsets[] = null;

        int srcBands = src.getNumBands();
        int dstBands = dst.getNumBands();

        if (dstBands != 3
                || (srcBands != 3 && !(srcBands == 4 && matrix[0][3] == 0 && matrix[1][3] == 0 && matrix[2][3] == 0))) {
            return slowFilter(src, dst);
        }

        SampleModel srcSM = src.getSampleModel();
        SampleModel dstSM = dst.getSampleModel();

        if (srcSM instanceof SinglePixelPackedSampleModel
                && dstSM instanceof SinglePixelPackedSampleModel) {
            // Check SinglePixelPackedSampleModel
            SinglePixelPackedSampleModel sppsm1 = (SinglePixelPackedSampleModel)srcSM;
            SinglePixelPackedSampleModel sppsm2 = (SinglePixelPackedSampleModel)dstSM;

            if (sppsm1.getDataType() != DataBuffer.TYPE_INT
                    || sppsm2.getDataType() != DataBuffer.TYPE_INT) {
                return slowFilter(src, dst);
            }

            // Check sample models
            if (!Arrays.equals(sppsm2.getBitOffsets(), offsets3c)
                    || !Arrays.equals(sppsm2.getBitMasks(), masks3c)) {
                return slowFilter(src, dst);
            }

            if (srcBands == 3) {
                if (!Arrays.equals(sppsm1.getBitOffsets(), offsets3c)
                        || !Arrays.equals(sppsm1.getBitMasks(), masks3c)) {
                    return slowFilter(src, dst);
                }
            } else if (srcBands == 4) {
                if (!Arrays.equals(sppsm1.getBitOffsets(), offsets4ac)
                        || !Arrays.equals(sppsm1.getBitMasks(), masks4ac)) {
                    return slowFilter(src, dst);
                }
            }

            type = TYPE_BYTE4AC;
            invertChannels = true;

            srcStride = sppsm1.getScanlineStride() * 4;
            dstStride = sppsm2.getScanlineStride() * 4;
        } else if (srcSM instanceof PixelInterleavedSampleModel
                && dstSM instanceof PixelInterleavedSampleModel) {
            if (srcBands != 3) {
                return slowFilter(src, dst);
            }

            int srcDataType = srcSM.getDataType();

            switch (srcDataType) {
                case DataBuffer.TYPE_BYTE:
                    type = TYPE_BYTE3C;
                    break;
                case DataBuffer.TYPE_USHORT:
                    type = TYPE_USHORT3C;
                    break;
                case DataBuffer.TYPE_SHORT:
                    type = TYPE_SHORT3C;
                    break;
                default:
                    return slowFilter(src, dst);
            }

            // Check PixelInterleavedSampleModel
            PixelInterleavedSampleModel pism1 = (PixelInterleavedSampleModel)srcSM;
            PixelInterleavedSampleModel pism2 = (PixelInterleavedSampleModel)dstSM;

            if (srcDataType != pism2.getDataType() || pism1.getPixelStride() != 3
                    || pism2.getPixelStride() != 3
                    || !Arrays.equals(pism1.getBandOffsets(), pism2.getBandOffsets())) {
                return slowFilter(src, dst);
            }

            if (Arrays.equals(pism1.getBandOffsets(), piInvOffsets)) {
                invertChannels = true;
            } else if (Arrays.equals(pism1.getBandOffsets(), piOffsets)) {
                invertChannels = false;
            } else {
                return slowFilter(src, dst);
            }

            int dataTypeSize = DataBuffer.getDataTypeSize(srcDataType) / 8;

            srcStride = pism1.getScanlineStride() * dataTypeSize;
            dstStride = pism2.getScanlineStride() * dataTypeSize;
        } else { // XXX - todo - IPP allows support for planar data also
            return slowFilter(src, dst);
        }

        // Fill offsets if there's a child raster
        if (src.getParent() != null || dst.getParent() != null) {
            if (src.getSampleModelTranslateX() != 0 || src.getSampleModelTranslateY() != 0
                    || dst.getSampleModelTranslateX() != 0 || dst.getSampleModelTranslateY() != 0) {
                offsets = new int[4];
                offsets[0] = -src.getSampleModelTranslateX() + src.getMinX();
                offsets[1] = -src.getSampleModelTranslateY() + src.getMinY();
                offsets[2] = -dst.getSampleModelTranslateX() + dst.getMinX();
                offsets[3] = -dst.getSampleModelTranslateY() + dst.getMinY();
            }
        }

        Object srcData, dstData;
        AwtImageBackdoorAccessor dbAccess = AwtImageBackdoorAccessor.getInstance();
        try {
            srcData = dbAccess.getData(src.getDataBuffer());
            dstData = dbAccess.getData(dst.getDataBuffer());
        } catch (IllegalArgumentException e) {
            return -1; // Unknown data buffer type
        }

        float ippMatrix[] = new float[12];

        if (invertChannels) {
            // IPP treats big endian integers like BGR, so we have to
            // swap columns 1 and 3 and rows 1 and 3
            for (int i = 0; i < mxHeight; i++) {
                ippMatrix[i * 4] = matrix[2 - i][2];
                ippMatrix[i * 4 + 1] = matrix[2 - i][1];
                ippMatrix[i * 4 + 2] = matrix[2 - i][0];

                if (mxWidth == 4) {
                    ippMatrix[i * 4 + 3] = matrix[2 - i][3];
                } else if (mxWidth == 5) {
                    ippMatrix[i * 4 + 3] = matrix[2 - i][4];
                }
            }
        } else {
            for (int i = 0; i < mxHeight; i++) {
                ippMatrix[i * 4] = matrix[i][0];
                ippMatrix[i * 4 + 1] = matrix[i][1];
                ippMatrix[i * 4 + 2] = matrix[i][2];

                if (mxWidth == 4) {
                    ippMatrix[i * 4 + 3] = matrix[i][3];
                } else if (mxWidth == 5) {
                    ippMatrix[i * 4 + 3] = matrix[i][4];
                }
            }
        }

        return ippColorTwist(srcData, src.getWidth(), src.getHeight(), srcStride, dstData, dst
                .getWidth(), dst.getHeight(), dstStride, ippMatrix, type, offsets, inPlace);
    }

    /**
     * Ipp color twist.
     * 
     * @param srcData
     *            the src data.
     * @param srcWidth
     *            the src width.
     * @param srcHeight
     *            the src height.
     * @param srcStride
     *            the src stride.
     * @param dstData
     *            the dst data.
     * @param dstWidth
     *            the dst width.
     * @param dstHeight
     *            the dst height.
     * @param dstStride
     *            the dst stride.
     * @param ippMatrix
     *            the ipp matrix.
     * @param type
     *            the type.
     * @param offsets
     *            the offsets.
     * @param inPlace
     *            the in place.
     * @return the int.
     */
    private final native int ippColorTwist(Object srcData, int srcWidth, int srcHeight,
            int srcStride, Object dstData, int dstWidth, int dstHeight, int dstStride,
            float ippMatrix[], int type, int offsets[], boolean inPlace);

    /**
     * Simple combine bands.
     * 
     * @param srcData
     *            the src data.
     * @param srcWidth
     *            the src width.
     * @param srcHeight
     *            the src height.
     * @param srcStride
     *            the src stride.
     * @param srcChannels
     *            the src channels.
     * @param dstData
     *            the dst data.
     * @param dstStride
     *            the dst stride.
     * @param dstChannels
     *            the dst channels.
     * @param m
     *            the m.
     * @param offsets
     *            the offsets.
     * @return the int.
     */
    private final native int simpleCombineBands(Object srcData, int srcWidth, int srcHeight,
            int srcStride, int srcChannels, Object dstData, int dstStride, int dstChannels,
            float m[], int offsets[]);
}
