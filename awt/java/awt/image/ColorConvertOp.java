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

package java.awt.image;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

import org.apache.harmony.awt.gl.color.ColorConverter;
import org.apache.harmony.awt.gl.color.ColorScaler;
import org.apache.harmony.awt.gl.color.ICC_Transform;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The ColorConvertOp class converts the pixels of the data in the source image
 * with the specified ColorSpace objects or an array of ICC_Profile objects. The
 * result pixels are scaled to the precision of the destination image.
 * 
 * @since Android 1.0
 */
public class ColorConvertOp implements BufferedImageOp, RasterOp {
    // Unused but required by interfaces
    /**
     * The rendering hints.
     */
    RenderingHints renderingHints;

    // Sequence consisting of ColorSpace and ICC_Profile elements
    /**
     * The conversion sequence.
     */
    Object conversionSequence[] = new ICC_Profile[0]; // To eliminate checks for

    // null

    // Not null if ColorConvertOp is constructed from the array of ICC profiles
    /**
     * The mid profiles.
     */
    private ICC_Profile midProfiles[];

    /**
     * The cc.
     */
    private final ColorConverter cc = new ColorConverter();

    /**
     * The t creator.
     */
    private final ICC_TransfomCreator tCreator = new ICC_TransfomCreator();

    /**
     * The is icc.
     */
    private boolean isICC = true;

    // Cached ICC_Transform
    /**
     * The Class ICC_TransfomCreator.
     */
    private class ICC_TransfomCreator {

        /**
         * The transform.
         */
        private ICC_Transform transform;

        /**
         * The max components.
         */
        private int maxComponents;

        /**
         * For the full ICC case.
         * 
         * @param src
         *            the src.
         * @param dst
         *            the dst.
         * @param convSeq
         *            the conv seq.
         * @return the transform.
         */
        public ICC_Transform getTransform(ICC_Profile src, ICC_Profile dst, ICC_Profile convSeq[]) {
            if (transform != null && src == transform.getSrc() && dst == transform.getDst()) {
                return transform;
            }

            int length = convSeq.length;
            int srcFlg = 0, dstFlg = 0;

            if (length == 0 || src != convSeq[0]) {
                if (src != null) {
                    srcFlg = 1; // need src profile
                }
            }
            if (length == 0 || dst != convSeq[length - 1]) {
                if (dst != null) {
                    dstFlg = 1; // need dst profile
                }
            }

            ICC_Profile profiles[];
            int nProfiles = length + srcFlg + dstFlg;
            if (nProfiles == length) {
                profiles = convSeq;
            } else {
                profiles = new ICC_Profile[nProfiles];
                int pos = 0;
                if (srcFlg != 0) {
                    profiles[pos++] = src;
                }
                for (int i = 0; i < length; i++) {
                    profiles[pos++] = convSeq[i];
                }
                if (dstFlg != 0) {
                    profiles[pos++] = dst;
                }
            }

            return transform = new ICC_Transform(profiles);
        }

        /**
         * Used only when there are non-ICC color spaces. Returns sequence of
         * non-ICC color spaces and ICC transforms made from src, dst and
         * conversionSequence.
         * 
         * @param src
         *            the src.
         * @param dst
         *            the dst.
         * @return the sequence.
         */
        public Object[] getSequence(Object src, Object dst) {
            ArrayList<Object> profiles = new ArrayList<Object>(10);
            ArrayList<Object> sequence = new ArrayList<Object>(10);

            // We need this profile anyway
            ICC_Profile xyzProfile = ICC_Profile.getInstance(ColorSpace.CS_CIEXYZ);

            Object conversionFirst = null, conversionLast = null;
            int conversionLength = conversionSequence.length;
            if (conversionLength > 0) {
                conversionFirst = conversionSequence[0];
                conversionLast = conversionSequence[conversionLength - 1];
            }

            boolean iccSequenceStarted = false;

            if (src != conversionFirst && src != null) {
                if (src instanceof ICC_Profile) {
                    profiles.add(src);
                    iccSequenceStarted = true;
                } else {
                    profiles.add(xyzProfile);
                    sequence.add(src); // Add non-ICC color space to the
                    // sequence
                }
            } else {
                profiles.add(xyzProfile);
            }

            for (int i = 0; i < conversionLength; i++) {
                if (conversionSequence[i] instanceof ICC_Profile) {
                    profiles.add(conversionSequence[i]);
                    iccSequenceStarted = true;
                } else if (iccSequenceStarted) {
                    profiles.add(xyzProfile);

                    // Eliminate same profiles if there are any
                    // (e.g. xyzProfile may occur several times)
                    Object prev = profiles.get(0);
                    for (int k = 1; k < profiles.size(); k++) {
                        if (prev == profiles.get(k)) {
                            k--;
                            profiles.remove(k);
                        }
                        prev = profiles.get(k);
                    }

                    // If only one profile left we skip the transform -
                    // it can be only CIEXYZ
                    if (profiles.size() > 1) {
                        sequence.add(new ICC_Transform(profiles.toArray(new ICC_Profile[0])));

                        // Add non-ICC color space to the sequence
                        sequence.add(conversionSequence[i]);
                    }

                    profiles.clear();
                    profiles.add(xyzProfile);
                    iccSequenceStarted = false; // Sequence of ICC profiles is
                    // processed
                } else { // Add non-ICC color space to the sequence
                    sequence.add(conversionSequence[i]);
                }
            }

            if (dst != conversionLast && dst != null) { // Add last profile if
                // needed
                if (dst instanceof ICC_Profile) {
                    profiles.add(dst);
                    iccSequenceStarted = true;
                } else if (iccSequenceStarted) {
                    profiles.add(xyzProfile);
                } else {
                    sequence.add(dst); // Add last non-ICC color space to the
                    // sequence
                }
            }

            if (iccSequenceStarted) { // Make last transform if needed
                sequence.add(new ICC_Transform(profiles.toArray(new ICC_Profile[0])));
                if (dst != null && !(dst instanceof ICC_Profile)) {
                    sequence.add(dst); // Add last non-ICC color space to the
                    // sequence
                }
            }

            // Calculate max number of components
            // This number will be used for memory allocation
            maxComponents = 0;
            Object o;
            for (int i = 0, size = sequence.size(); i < size; i++) {
                o = sequence.get(i);
                if (o instanceof ICC_Transform) {
                    ICC_Transform t = (ICC_Transform)o;
                    maxComponents = (maxComponents > t.getNumInputChannels() + 1) ? maxComponents
                            : t.getNumInputChannels() + 1;
                    maxComponents = (maxComponents > t.getNumOutputChannels() + 1) ? maxComponents
                            : t.getNumOutputChannels() + 1;
                } else {
                    ColorSpace cs = (ColorSpace)o;
                    maxComponents = (maxComponents > cs.getNumComponents() + 1) ? maxComponents
                            : cs.getNumComponents() + 1;
                }
            }

            return sequence.toArray();
        }
    }

    /**
     * Instantiates a new ColorConvertOp object using two specified ColorSpace
     * objects.
     * 
     * @param srcCS
     *            the source ColorSpace.
     * @param dstCS
     *            the destination ColorSpace.
     * @param hints
     *            the RenderingHints object used for the color conversion, or
     *            null.
     */
    public ColorConvertOp(ColorSpace srcCS, ColorSpace dstCS, RenderingHints hints) {
        if (srcCS == null || dstCS == null) {
            throw new NullPointerException(Messages.getString("awt.25B")); //$NON-NLS-1$
        }

        renderingHints = hints;

        boolean srcICC = srcCS instanceof ICC_ColorSpace;
        boolean dstICC = dstCS instanceof ICC_ColorSpace;

        if (srcICC && dstICC) {
            conversionSequence = new ICC_Profile[2];
        } else {
            conversionSequence = new Object[2];
            isICC = false;
        }

        if (srcICC) {
            conversionSequence[0] = ((ICC_ColorSpace)srcCS).getProfile();
        } else {
            conversionSequence[0] = srcCS;
        }

        if (dstICC) {
            conversionSequence[1] = ((ICC_ColorSpace)dstCS).getProfile();
        } else {
            conversionSequence[1] = dstCS;
        }
    }

    /**
     * Instantiates a new ColorConvertOp object from the specified ICC_Profile
     * objects.
     * 
     * @param profiles
     *            the array of ICC_Profile objects.
     * @param hints
     *            the RenderingHints object used for the color conversion, or
     *            null.
     */
    public ColorConvertOp(ICC_Profile profiles[], RenderingHints hints) {
        if (profiles == null) {
            throw new NullPointerException(Messages.getString("awt.25C")); //$NON-NLS-1$
        }

        renderingHints = hints;

        // This array is not used in the program logic, so don't need to copy it
        // Store it only to return back
        midProfiles = profiles;

        conversionSequence = new ICC_Profile[midProfiles.length];

        // Add profiles to the conversion sequence
        for (int i = 0, length = midProfiles.length; i < length; i++) {
            conversionSequence[i] = midProfiles[i];
        }
    }

    /**
     * Instantiates a new ColorConvertOp object using the specified ColorSpace
     * object.
     * 
     * @param cs
     *            the destination ColorSpace or an intermediate ColorSpace.
     * @param hints
     *            the RenderingHints object used for the color conversion, or
     *            null.
     */
    public ColorConvertOp(ColorSpace cs, RenderingHints hints) {
        if (cs == null) {
            throw new NullPointerException(Messages.getString("awt.25B")); //$NON-NLS-1$
        }

        renderingHints = hints;

        if (cs instanceof ICC_ColorSpace) {
            conversionSequence = new ICC_Profile[1];
            conversionSequence[0] = ((ICC_ColorSpace)cs).getProfile();
        } else {
            conversionSequence = new Object[1];
            conversionSequence[0] = cs;
            isICC = false;
        }
    }

    /**
     * Instantiates a new ColorConvertOp object which converts from a source
     * color space to a destination color space.
     * 
     * @param hints
     *            the RenderingHints object used for the color conversion, or
     *            null.
     */
    public ColorConvertOp(RenderingHints hints) {
        renderingHints = hints;
    }

    public final WritableRaster filter(Raster src, WritableRaster dst) {
        if (conversionSequence.length < 2) {
            throw new IllegalArgumentException(Messages.getString("awt.25D")); //$NON-NLS-1$
        }

        ICC_Profile srcPf = null, dstPf = null; // unused if isICC is false
        int nSrcColorComps, nDstColorComps;
        Object first = conversionSequence[0];
        Object last = conversionSequence[conversionSequence.length - 1];

        // Get the number of input/output color components
        if (isICC) {
            srcPf = (ICC_Profile)first;
            dstPf = (ICC_Profile)last;
            nSrcColorComps = srcPf.getNumComponents();
            nDstColorComps = dstPf.getNumComponents();
        } else {
            if (first instanceof ICC_Profile) {
                srcPf = (ICC_Profile)first;
                nSrcColorComps = srcPf.getNumComponents();
            } else {
                nSrcColorComps = ((ColorSpace)first).getNumComponents();
            }

            if (last instanceof ICC_Profile) {
                dstPf = (ICC_Profile)last;
                nDstColorComps = dstPf.getNumComponents();
            } else {
                nDstColorComps = ((ColorSpace)last).getNumComponents();
            }
        }

        // Check that source and destination rasters are compatible with
        // transforms and with each other
        if (src.getNumBands() != nSrcColorComps) {
            // awt.25E=Incorrect number of source raster bands. Should be equal
            // to the number of color components of source colorspace.
            throw new IllegalArgumentException(Messages.getString("awt.25E")); //$NON-NLS-1$
        }

        if (dst != null) { // Check destination raster
            if (dst.getNumBands() != nDstColorComps) {
                // awt.25F=Incorrect number of destination raster bands. Should
                // be equal to the number of color components of destination
                // colorspace.
                throw new IllegalArgumentException(Messages.getString("awt.25F")); //$NON-NLS-1$
            }

            if (src.getWidth() != dst.getWidth() || src.getHeight() != dst.getHeight()) {
                throw new IllegalArgumentException(Messages.getString("awt.260")); //$NON-NLS-1$
            }

        } else {
            dst = createCompatibleDestRaster(src);
        }

        if (isICC) {
            // Create transform
            ICC_Transform t = tCreator
                    .getTransform(srcPf, dstPf, (ICC_Profile[])conversionSequence);
            cc.translateColor(t, src, dst);
        } else {
            Object[] sequence = tCreator.getSequence(null, null);

            // Get data from the source raster
            ColorScaler scaler = new ColorScaler();
            scaler.loadScalingData(src, null);
            float tmpData[][] = scaler.scaleNormalize(src);

            // Get source and destination color spaces
            ColorSpace srcCS = (srcPf == null) ? (ColorSpace)first : new ICC_ColorSpace(srcPf);
            ColorSpace dstCS = (dstPf == null) ? (ColorSpace)last : new ICC_ColorSpace(dstPf);

            applySequence(sequence, tmpData, srcCS, dstCS);

            scaler.loadScalingData(dst, null);
            scaler.unscaleNormalized(dst, tmpData);
        }

        return dst;
    }

    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel destCM) {
        // If destination color model is passed only one line needed
        if (destCM != null) {
            return new BufferedImage(destCM, destCM.createCompatibleWritableRaster(src.getWidth(),
                    src.getHeight()), destCM.isAlphaPremultiplied(), null);
        }

        int nSpaces = conversionSequence.length;

        if (nSpaces < 1) {
            throw new IllegalArgumentException(Messages.getString("awt.261")); //$NON-NLS-1$
        }

        // Get destination color space
        Object destination = conversionSequence[nSpaces - 1];
        ColorSpace dstCS = (destination instanceof ColorSpace) ? (ColorSpace)destination
                : new ICC_ColorSpace((ICC_Profile)destination);

        ColorModel srcCM = src.getColorModel();
        ColorModel dstCM = new ComponentColorModel(dstCS, srcCM.hasAlpha(), srcCM
                .isAlphaPremultiplied(), srcCM.getTransparency(), srcCM.getTransferType());

        return new BufferedImage(dstCM, destCM.createCompatibleWritableRaster(src.getWidth(), src
                .getHeight()), destCM.isAlphaPremultiplied(), null);
    }

    public final BufferedImage filter(BufferedImage src, BufferedImage dst) {
        if (dst == null && conversionSequence.length < 1) {
            throw new IllegalArgumentException(Messages.getString("awt.262")); //$NON-NLS-1$
        }

        ColorModel srcCM = src.getColorModel();
        // First handle index color model
        if (srcCM instanceof IndexColorModel) {
            src = ((IndexColorModel)srcCM).convertToIntDiscrete(src.getRaster(), false);
        }
        ColorSpace srcCS = srcCM.getColorSpace();

        BufferedImage res;
        boolean isDstIndex = false;
        if (dst != null) {

            if (src.getWidth() != dst.getWidth() || src.getHeight() != dst.getHeight()) {
                throw new IllegalArgumentException(Messages.getString("awt.263")); //$NON-NLS-1$
            }

            if (dst.getColorModel() instanceof IndexColorModel) {
                isDstIndex = true;
                res = createCompatibleDestImage(src, null);
            } else {
                res = dst;
            }
        } else {
            res = createCompatibleDestImage(src, null);
        }
        ColorModel dstCM = res.getColorModel();
        ColorSpace dstCS = dstCM.getColorSpace();

        ICC_Profile srcPf = null, dstPf = null;
        if (srcCS instanceof ICC_ColorSpace) {
            srcPf = ((ICC_ColorSpace)srcCS).getProfile();
        }
        if (dstCS instanceof ICC_ColorSpace) {
            dstPf = ((ICC_ColorSpace)dstCS).getProfile();
        }

        boolean isFullICC = isICC && srcPf != null && dstPf != null;

        if (isFullICC) {
            ICC_Transform t = tCreator
                    .getTransform(srcPf, dstPf, (ICC_Profile[])conversionSequence);
            cc.translateColor(t, src, res);
        } else { // Perform non-ICC transform
            Object sequence[] = tCreator.getSequence(srcPf == null ? (Object)srcCS : srcPf,
                    dstPf == null ? (Object)dstCS : dstPf);

            int srcW = src.getWidth();
            int srcH = src.getHeight();
            int numPixels = srcW * srcH;

            // Load all pixel data into array tmpData
            float tmpData[][] = new float[numPixels][tCreator.maxComponents];
            for (int row = 0, dataPos = 0; row < srcW; row++) {
                for (int col = 0; col < srcH; col++) {
                    tmpData[dataPos] = srcCM.getNormalizedComponents(src.getRaster()
                            .getDataElements(row, col, null), tmpData[dataPos], 0);
                    dataPos++;
                }
            }

            // Copy alpha channel if needed
            float alpha[] = null;
            int alphaIdx = srcCM.numComponents - 1;
            if (srcCM.hasAlpha() && dstCM.hasAlpha()) {
                alpha = new float[numPixels];
                for (int i = 0; i < numPixels; i++) {
                    alpha[i] = tmpData[i][alphaIdx];
                }
            }

            // Translate colors
            applySequence(sequence, tmpData, srcCS, dstCS);

            // Copy alpha if needed
            if (dstCM.hasAlpha()) {
                alphaIdx = dstCM.numComponents - 1;
                if (alpha != null) {
                    for (int i = 0; i < numPixels; i++) {
                        tmpData[i][alphaIdx] = alpha[i];
                    }
                } else {
                    for (int i = 0; i < numPixels; i++) {
                        tmpData[i][alphaIdx] = 1f;
                    }
                }
            }

            // Store data back to the image
            for (int row = 0, dataPos = 0; row < srcW; row++) {
                for (int col = 0; col < srcH; col++) {
                    res.getRaster().setDataElements(row, col,
                            dstCM.getDataElements(tmpData[dataPos++], 0, null));
                }
            }
        }

        if (isDstIndex) { // Convert image into indexed color
            Graphics2D g2d = dst.createGraphics();
            g2d.drawImage(res, 0, 0, null);
            g2d.dispose();
            return dst;
        }

        return res;
    }

    /**
     * Apply sequence.
     * 
     * @param sequence
     *            the sequence.
     * @param tmpData
     *            the tmp data.
     * @param srcCS
     *            the src cs.
     * @param dstCS
     *            the dst cs.
     */
    private void applySequence(Object sequence[], float tmpData[][], ColorSpace srcCS,
            ColorSpace dstCS) {
        ColorSpace xyzCS = ColorSpace.getInstance(ColorSpace.CS_CIEXYZ);

        int numPixels = tmpData.length;

        // First transform...
        if (sequence[0] instanceof ICC_Transform) { // ICC
            ICC_Transform t = (ICC_Transform)sequence[0];
            cc.translateColor(t, tmpData, srcCS, xyzCS, numPixels);
        } else { // non ICC
            for (int k = 0; k < numPixels; k++) {
                tmpData[k] = srcCS.toCIEXYZ(tmpData[k]);
            }
            cc.loadScalingData(xyzCS); // prepare for scaling XYZ
        }

        for (Object element : sequence) {
            if (element instanceof ICC_Transform) {
                ICC_Transform t = (ICC_Transform)element;
                cc.translateColor(t, tmpData, null, null, numPixels);
            } else {
                ColorSpace cs = (ColorSpace)element;
                for (int k = 0; k < numPixels; k++) {
                    tmpData[k] = cs.fromCIEXYZ(tmpData[k]);
                    tmpData[k] = cs.toCIEXYZ(tmpData[k]);
                }
            }
        }

        // Last transform...
        if (sequence[sequence.length - 1] instanceof ICC_Transform) { // ICC
            ICC_Transform t = (ICC_Transform)sequence[sequence.length - 1];
            cc.translateColor(t, tmpData, xyzCS, dstCS, numPixels);
        } else { // non ICC
            for (int k = 0; k < numPixels; k++) {
                tmpData[k] = dstCS.fromCIEXYZ(tmpData[k]);
            }
        }
    }

    public final Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt != null) {
            dstPt.setLocation(srcPt);
            return dstPt;
        }
        return new Point2D.Float((float)srcPt.getX(), (float)srcPt.getY());
    }

    public WritableRaster createCompatibleDestRaster(Raster src) {
        int nComps = 0;
        int nSpaces = conversionSequence.length;

        if (nSpaces < 2) {
            throw new IllegalArgumentException(Messages.getString("awt.261")); //$NON-NLS-1$
        }

        Object lastCS = conversionSequence[nSpaces - 1];
        if (lastCS instanceof ColorSpace) {
            nComps = ((ColorSpace)lastCS).getNumComponents();
        } else {
            nComps = ((ICC_Profile)lastCS).getNumComponents();
        }

        // Calculate correct data type
        int dstDataType = src.getDataBuffer().getDataType();
        if (dstDataType != DataBuffer.TYPE_BYTE && dstDataType != DataBuffer.TYPE_SHORT) {
            dstDataType = DataBuffer.TYPE_SHORT;
        }

        return Raster.createInterleavedRaster(dstDataType, src.getWidth(), src.getHeight(), nComps,
                new Point(src.getMinX(), src.getMinY()));
    }

    public final Rectangle2D getBounds2D(Raster src) {
        return src.getBounds();
    }

    public final Rectangle2D getBounds2D(BufferedImage src) {
        return src.getRaster().getBounds();
    }

    /**
     * Gets an array of ICC_Profiles objects which constructs this
     * ColorConvertOp object or returns null if this ColorConvertOp is not
     * constructed from array of ICC_Profiles.
     * 
     * @return an array of ICC_Profiles objects which constructs this
     *         ColorConvertOp object or returns null if this ColorConvertOp is
     *         not constructed from array of ICC_Profiles.
     */
    public final ICC_Profile[] getICC_Profiles() {
        if (midProfiles != null) {
            return midProfiles;
        }
        return null;
    }

    public final RenderingHints getRenderingHints() {
        return renderingHints;
    }
}
