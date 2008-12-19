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

package javax.imageio;

import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.color.ColorSpace;

/**
 * The ImageTypeSpecifier class performs conversion operations on the
 * SampleModel and the ColorModel of an image.
 * 
 * @since Android 1.0
 */
public class ImageTypeSpecifier {

    /**
     * The ColorModel of this ImageTypeSpecifier.
     */
    protected ColorModel colorModel;

    /**
     * The SampleModel of this ImageTypeSpecifier.
     */
    protected SampleModel sampleModel;

    /**
     * Instantiates a new ImageTypeSpecifier with the specified ColorModel and
     * SampleModel objects.
     * 
     * @param colorModel
     *            the ColorModel.
     * @param sampleModel
     *            the SampleModel.
     */
    public ImageTypeSpecifier(ColorModel colorModel, SampleModel sampleModel) {
        if (colorModel == null) {
            throw new IllegalArgumentException("color model should not be NULL");
        }
        if (sampleModel == null) {
            throw new IllegalArgumentException("sample model should not be NULL");
        }
        if (!colorModel.isCompatibleSampleModel(sampleModel)) {
            throw new IllegalArgumentException("color and sample models are not compatible");
        }

        this.colorModel = colorModel;
        this.sampleModel = sampleModel;
    }

    /**
     * Instantiates a new ImageTypeSpecifier using the specified RenderedImage.
     * 
     * @param renderedImage
     *            the RenderedImage.
     */
    public ImageTypeSpecifier(RenderedImage renderedImage) {
        if (renderedImage == null) {
            throw new IllegalArgumentException("image should not be NULL");
        }
        this.colorModel = renderedImage.getColorModel();
        this.sampleModel = renderedImage.getSampleModel();
    }

    /**
     * Creates an ImageTypeSpecifier with the specified DirectColorModel and a
     * packed SampleModel.
     * 
     * @param colorSpace
     *            the ColorSpace.
     * @param redMask
     *            the red mask.
     * @param greenMask
     *            the green mask.
     * @param blueMask
     *            the blue mask.
     * @param alphaMask
     *            the alpha mask.
     * @param transferType
     *            the transfer type.
     * @param isAlphaPremultiplied
     *            the parameter indicates if the color channel is pre-multiplied
     *            by alpha.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createPacked(ColorSpace colorSpace, int redMask,
            int greenMask, int blueMask, int alphaMask, int transferType,
            boolean isAlphaPremultiplied) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates an ImageTypeSpecifier with specified ComponentColorModel and a
     * PixelInterleavedSampleModel.
     * 
     * @param colorSpace
     *            the ColorSpace.
     * @param bandOffsets
     *            the band offsets.
     * @param dataType
     *            the data type.
     * @param hasAlpha
     *            the parameter indicates if alpha channel is needed.
     * @param isAlphaPremultiplied
     *            the parameter indicates if the color channel is pre-multiplied
     *            by alpha.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createInterleaved(ColorSpace colorSpace, int[] bandOffsets,
            int dataType, boolean hasAlpha, boolean isAlphaPremultiplied) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates a ImageTypeSpecifier for a image with a BandedSampleModel and a
     * ComponentColorModel.
     * 
     * @param colorSpace
     *            the ColorSpace.
     * @param bankIndices
     *            the bank indices.
     * @param bandOffsets
     *            the band offsets.
     * @param dataType
     *            the data type.
     * @param hasAlpha
     *            the parameter indicates a presence of alpha channel.
     * @param isAlphaPremultiplied
     *            the parameter indicates whether or not color channel is alpha
     *            pre-multiplied.
     * @return the image type specifier
     */
    public static ImageTypeSpecifier createBanded(ColorSpace colorSpace, int[] bankIndices,
            int[] bandOffsets, int dataType, boolean hasAlpha, boolean isAlphaPremultiplied) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates a ImageTypeSpecifier for a grayscale image.
     * 
     * @param bits
     *            the number of bits per gray value.
     * @param dataType
     *            the data type.
     * @param isSigned
     *            a signed flag.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createGrayscale(int bits, int dataType, boolean isSigned) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates a ImageTypeSpecifier for a grayscale image.
     * 
     * @param bits
     *            the number of bits per gray value.
     * @param dataType
     *            the data type.
     * @param isSigned
     *            a signed flag.
     * @param isAlphaPremultiplied
     *            the parameter indicates if color channel is pre-multiplied by
     *            alpha, or not.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createGrayscale(int bits, int dataType, boolean isSigned,
            boolean isAlphaPremultiplied) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates a ImageTypeSpecifier with the indexed image format.
     * 
     * @param redLUT
     *            the red values of indices.
     * @param greenLUT
     *            the green values of indices.
     * @param blueLUT
     *            the blue values of indices.
     * @param alphaLUT
     *            the alpha values of indices.
     * @param bits
     *            the bits number for each index.
     * @param dataType
     *            the data type.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createIndexed(byte[] redLUT, byte[] greenLUT, byte[] blueLUT,
            byte[] alphaLUT, int bits, int dataType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates the ImageTypeSpecifier from the specified buffered image type.
     * 
     * @param bufferedImageType
     *            the buffered image type.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createFromBufferedImageType(int bufferedImageType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Creates the ImageTypeSpecifier from the specified RenderedImage.
     * 
     * @param image
     *            the RenderedImage.
     * @return the ImageTypeSpecifier.
     */
    public static ImageTypeSpecifier createFromRenderedImage(RenderedImage image) {
        if (null == image) {
            throw new IllegalArgumentException("image should not be NULL");
        }
        return new ImageTypeSpecifier(image);
    }

    /**
     * Gets the BufferedImage type.
     * 
     * @return the BufferedImage type.
     */
    public int getBufferedImageType() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the number of components.
     * 
     * @return the number of components.
     */
    public int getNumComponents() {
        return colorModel.getNumComponents();
    }

    /**
     * Gets the number of bands.
     * 
     * @return the number of bands.
     */
    public int getNumBands() {
        return sampleModel.getNumBands();
    }

    /**
     * Gets the number of bits per the specified band.
     * 
     * @param band
     *            the index of band.
     * @return the number of bits per the specified band.
     */
    public int getBitsPerBand(int band) {
        if (band < 0 || band >= getNumBands()) {
            throw new IllegalArgumentException();
        }
        return sampleModel.getSampleSize(band);
    }

    /**
     * Gets the SampleModel associated with this ImageTypeSpecifier.
     * 
     * @return the SampleModel associated with this ImageTypeSpecifier.
     */
    public SampleModel getSampleModel() {
        return sampleModel;
    }

    /**
     * Gets a compatible SampleModel with the specified width and height.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @return the SampleModel.
     */
    public SampleModel getSampleModel(int width, int height) {
        if ((long)width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("width * height > Integer.MAX_VALUE");
        }
        return sampleModel.createCompatibleSampleModel(width, height);
    }

    /**
     * Gets the ColorModel associated with this ImageTypeSpecifier.
     * 
     * @return the ColorModel associated with this ImageTypeSpecifier.
     */
    public ColorModel getColorModel() {
        return colorModel;
    }

    /**
     * Creates the BufferedImage with the specified width and height and the
     * ColorMadel and SampleModel which are specified by this
     * ImageTypeSpecifier.
     * 
     * @param width
     *            the width of the BufferedImage.
     * @param height
     *            the height of the BufferedImage.
     * @return the BufferedImage.
     */
    public BufferedImage createBufferedImage(int width, int height) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Compares this ImageTypeSpecifier object with the specified object.
     * 
     * @param o
     *            the Object to be compared.
     * @return true, if the object is an ImageTypeSpecifier with the same data
     *         as this ImageTypeSpecifier, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        boolean rt = false;
        if (o instanceof ImageTypeSpecifier) {
            ImageTypeSpecifier ts = (ImageTypeSpecifier)o;
            rt = colorModel.equals(ts.colorModel) && sampleModel.equals(ts.sampleModel);
        }
        return rt;
    }
}