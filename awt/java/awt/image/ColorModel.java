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

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.util.Arrays;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The class ColorModel.
 * 
 * @since Android 1.0
 */
public abstract class ColorModel implements Transparency {

    /**
     * The pixel_bits.
     */
    protected int pixel_bits; // Pixel length in bits

    /**
     * The transfer type.
     */
    protected int transferType;

    /**
     * The cs.
     */
    ColorSpace cs;

    /**
     * The has alpha.
     */
    boolean hasAlpha;

    /**
     * The is alpha premultiplied.
     */
    boolean isAlphaPremultiplied;

    /**
     * The transparency.
     */
    int transparency;

    /**
     * The num color components.
     */
    int numColorComponents;

    /**
     * The num components.
     */
    int numComponents;

    /**
     * The bits.
     */
    int[] bits; // Array of components masks

    /**
     * The max values.
     */
    int[] maxValues = null; // Max values that may be represent by color

    // components

    /**
     * The max bit length.
     */
    int maxBitLength; // Max length color components in bits

    /**
     * The RG bdefault.
     */
    private static ColorModel RGBdefault;

    /**
     * Instantiates a new color model with the specified values.
     * 
     * @param pixel_bits
     *            the pixel length in bits.
     * @param bits
     *            the array of component masks.
     * @param cspace
     *            the color space.
     * @param hasAlpha
     *            whether the color model has alpha.
     * @param isAlphaPremultiplied
     *            whether the alpha is pre-multiplied.
     * @param transparency
     *            the transparency strategy, @see java.awt.Transparency.
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     */
    protected ColorModel(int pixel_bits, int[] bits, ColorSpace cspace, boolean hasAlpha,
            boolean isAlphaPremultiplied, int transparency, int transferType) {

        if (pixel_bits < 1) {
            // awt.26B=The number of bits in the pixel values is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.26B")); //$NON-NLS-1$
        }

        if (bits == null) {
            // awt.26C=bits is null
            throw new NullPointerException(Messages.getString("awt.26C")); //$NON-NLS-1$
        }

        int sum = 0;
        for (int element : bits) {
            if (element < 0) {
                // awt.26D=The elements in bits is less than 0
                throw new IllegalArgumentException(Messages.getString("awt.26D")); //$NON-NLS-1$
            }
            sum += element;
        }

        if (sum < 1) {
            // awt.26E=The sum of the number of bits in bits is less than 1
            throw new NullPointerException(Messages.getString("awt.26E")); //$NON-NLS-1$
        }

        if (cspace == null) {
            // awt.26F=The cspace is null
            throw new IllegalArgumentException(Messages.getString("awt.26F")); //$NON-NLS-1$
        }

        if (transparency < Transparency.OPAQUE || transparency > Transparency.TRANSLUCENT) {
            // awt.270=The transparency is not a valid value
            throw new IllegalArgumentException(Messages.getString("awt.270")); //$NON-NLS-1$
        }

        this.pixel_bits = pixel_bits;
        this.bits = bits.clone();

        maxValues = new int[bits.length];
        maxBitLength = 0;
        for (int i = 0; i < maxValues.length; i++) {
            maxValues[i] = (1 << bits[i]) - 1;
            if (bits[i] > maxBitLength) {
                maxBitLength = bits[i];
            }
        }

        cs = cspace;
        this.hasAlpha = hasAlpha;
        this.isAlphaPremultiplied = isAlphaPremultiplied;
        numColorComponents = cs.getNumComponents();

        if (hasAlpha) {
            numComponents = numColorComponents + 1;
        } else {
            numComponents = numColorComponents;
        }

        this.transparency = transparency;
        this.transferType = transferType;

    }

    /**
     * Instantiates a new color model with the specified pixel bit depth. The
     * transferType is chosen based on the pixel bits, and the other data fields
     * are given default values.
     * 
     * @param bits
     *            the array of component masks.
     */
    public ColorModel(int bits) {

        if (bits < 1) {
            // awt.271=The number of bits in bits is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.271")); //$NON-NLS-1$
        }

        pixel_bits = bits;
        transferType = getTransferType(bits);
        cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        hasAlpha = true;
        isAlphaPremultiplied = false;
        transparency = Transparency.TRANSLUCENT;

        numColorComponents = 3;
        numComponents = 4;

        this.bits = null;
    }

    /**
     * Gets the data elements from the specified component array, transforming
     * them according to rules of the color model.
     * 
     * @param components
     *            the components.
     * @param offset
     *            the offset in the normComponents array.
     * @param obj
     *            the array that the result is written to: an array of values
     *            whose length must be the number of components used by the
     *            color model and whose type depends on the transfer type (based
     *            on the pixel bit depth), or null to have the appropriate array
     *            created.
     * @return the array of data elements.
     */
    public Object getDataElements(int[] components, int offset, Object obj) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the data elements from the specified array of normalized components.
     * 
     * @param normComponents
     *            the array normalized components.
     * @param normOffset
     *            the offset in the normComponents array.
     * @param obj
     *            the array that the result is written to: an array of values
     *            whose length must be the number of components used by the
     *            color model and whose type depends on the transfer type (based
     *            on the pixel bit depth), or null to have the appropriate array
     *            created.
     * @return the array of data elements.
     */
    public Object getDataElements(float[] normComponents, int normOffset, Object obj) {
        int unnormComponents[] = getUnnormalizedComponents(normComponents, normOffset, null, 0);
        return getDataElements(unnormComponents, 0, obj);
    }

    /**
     * Gets the data elements corresponding to the pixel determined by the RGB
     * data.
     * 
     * @param rgb
     *            the RGB integer value that defines the pixel.
     * @param pixel
     *            the array that the result is written to: an array of values
     *            whose length must be the number of components used by the
     *            color model and whose type depends on the transfer type (based
     *            on the pixel bit depth), or null to have the appropriate array
     *            created.
     * @return the array of data elements.
     */
    public Object getDataElements(int rgb, Object pixel) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the child raster corresponding to the alpha channel of the specified
     * writable raster, or null if alpha is not supported.
     * 
     * @param raster
     *            the raster.
     * @return the alpha raster.
     */
    public WritableRaster getAlphaRaster(WritableRaster raster) {
        return null;
    }

    /**
     * Creates a new color model by coercing the data in the writable raster in
     * accordance with the alpha strategy of this color model.
     * 
     * @param raster
     *            the raster.
     * @param isAlphaPremultiplied
     *            whether the alpha is pre-multiplied in this color model
     * @return the new color model.
     */
    public ColorModel coerceData(WritableRaster raster, boolean isAlphaPremultiplied) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    @Override
    public String toString() {
        // The output format based on 1.5 release behavior.
        // It could be reveled such way:
        // ColorModel cm = new
        // ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB,
        // false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        // System.out.println(cm.toString());
        return "ColorModel: Color Space = " + cs.toString() + "; has alpha = " //$NON-NLS-1$ //$NON-NLS-2$
                + hasAlpha + "; is alpha premultipied = " //$NON-NLS-1$
                + isAlphaPremultiplied + "; transparency = " + transparency //$NON-NLS-1$
                + "; number color components = " + numColorComponents //$NON-NLS-1$
                + "; pixel bits = " + pixel_bits + "; transfer type = " //$NON-NLS-1$ //$NON-NLS-2$
                + transferType;
    }

    /**
     * Gets the components of the pixel determined by the data array.
     * 
     * @param pixel
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @param components
     *            the the array where the resulting components are written (or
     *            null to prompt the method to create the return array).
     * @param offset
     *            the offset that tells where the results should be written in
     *            the return array.
     * @return the array of components.
     */
    public int[] getComponents(Object pixel, int[] components, int offset) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the normalized components of the pixel determined by the data array.
     * 
     * @param pixel
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @param normComponents
     *            the array where the resulting normalized components are
     *            written (or null to prompt the method to create the return
     *            array).
     * @param normOffset
     *            the offset that tells where the results should be written in
     *            the return array.
     * @return the array of normalized components.
     */
    public float[] getNormalizedComponents(Object pixel, float[] normComponents, int normOffset) {

        if (pixel == null) {
            // awt.294=pixel is null
            throw new NullPointerException(Messages.getString("awt.294")); //$NON-NLS-1$
        }

        int unnormComponents[] = getComponents(pixel, null, 0);
        return getNormalizedComponents(unnormComponents, 0, normComponents, normOffset);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ColorModel)) {
            return false;
        }
        ColorModel cm = (ColorModel)obj;

        return (pixel_bits == cm.getPixelSize() && transferType == cm.getTransferType()
                && cs.getType() == cm.getColorSpace().getType() && hasAlpha == cm.hasAlpha()
                && isAlphaPremultiplied == cm.isAlphaPremultiplied()
                && transparency == cm.getTransparency()
                && numColorComponents == cm.getNumColorComponents()
                && numComponents == cm.getNumComponents() && Arrays.equals(bits, cm
                .getComponentSize()));
    }

    /**
     * Gets the red component of the pixel determined by the data array.
     * 
     * @param inData
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @return the red.
     */
    public int getRed(Object inData) {
        return getRed(constructPixel(inData));
    }

    /**
     * Gets the RGB integer value corresponding to the pixel defined by the data
     * array.
     * 
     * @param inData
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @return the integer value that gives the pixel's colors in RGB format.
     */
    public int getRGB(Object inData) {
        return (getAlpha(inData) << 24 | getRed(inData) << 16 | getGreen(inData) << 8 | getBlue(inData));
    }

    /**
     * Gets the green component of the pixel defined by the data array.
     * 
     * @param inData
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @return the green.
     */
    public int getGreen(Object inData) {
        return getGreen(constructPixel(inData));
    }

    /**
     * Gets the blue component of the pixel defined by the data array.
     * 
     * @param inData
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @return the blue.
     */
    public int getBlue(Object inData) {
        return getBlue(constructPixel(inData));
    }

    /**
     * Gets the alpha component of the pixel defined by the data array.
     * 
     * @param inData
     *            the data array that defines the pixel (whose primitive type
     *            corresponds to the pixel length in bits.
     * @see ColorModel#getTransferType()
     * @return the alpha.
     */
    public int getAlpha(Object inData) {
        return getAlpha(constructPixel(inData));
    }

    /**
     * Creates a compatible writable raster.
     * 
     * @param w
     *            the width of the desired writable raster.
     * @param h
     *            the height of the desired writable raster.
     * @return the writable raster.
     */
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Checks if the sample model is compatible with this color model.
     * 
     * @param sm
     *            the sample model.
     * @return true, if the sample model is compatible with this color model.
     */
    public boolean isCompatibleSampleModel(SampleModel sm) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Creates the compatible sample model.
     * 
     * @param w
     *            the width of the desired sample model.
     * @param h
     *            the height of the desired sample model.
     * @return the sample model.
     */
    public SampleModel createCompatibleSampleModel(int w, int h) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Checks if the specified raster is compatible with this color model.
     * 
     * @param raster
     *            the raster to inspect.
     * @return true, if the raster is compatible with this color model.
     */
    public boolean isCompatibleRaster(Raster raster) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the color space of this color model.
     * 
     * @return the color space.
     */
    public final ColorSpace getColorSpace() {
        return cs;
    }

    /**
     * Gets the normalized components corresponding to the specified
     * unnormalized components.
     * 
     * @param components
     *            the array of unnormalized components.
     * @param offset
     *            the offset where the components should be read from the array
     *            of unnormalized components.
     * @param normComponents
     *            the array where the resulting normalized components are
     *            written (or null to prompt the method to create the return
     *            array).
     * @param normOffset
     *            the offset that tells where the results should be written in
     *            the return array.
     * @return the normalized components.
     */
    public float[] getNormalizedComponents(int[] components, int offset, float normComponents[],
            int normOffset) {
        if (bits == null) {
            // awt.26C=bits is null
            throw new UnsupportedOperationException(Messages.getString("awt.26C")); //$NON-NLS-1$
        }

        if (normComponents == null) {
            normComponents = new float[numComponents + normOffset];
        }

        if (hasAlpha && isAlphaPremultiplied) {
            float normAlpha = (float)components[offset + numColorComponents]
                    / maxValues[numColorComponents];
            if (normAlpha != 0.0f) {
                for (int i = 0; i < numColorComponents; i++) {
                    normComponents[normOffset + i] = components[offset + i]
                            / (normAlpha * maxValues[i]);
                }
                normComponents[normOffset + numColorComponents] = normAlpha;
            } else {
                for (int i = 0; i < numComponents; i++) {
                    normComponents[normOffset + i] = 0.0f;
                }
            }
        } else {
            for (int i = 0; i < numComponents; i++) {
                normComponents[normOffset + i] = (float)components[offset + i] / maxValues[i];
            }
        }

        return normComponents;
    }

    /**
     * Gets the data element corresponding to the unnormalized components.
     * 
     * @param components
     *            the components.
     * @param offset
     *            the offset to start reading the components from the array of
     *            components.
     * @return the data element.
     */
    public int getDataElement(int[] components, int offset) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the unnormalized components corresponding to the specified
     * normalized components.
     * 
     * @param normComponents
     *            the array of normalized components.
     * @param normOffset
     *            the offset where the components should be read from the array
     *            of normalized components.
     * @param components
     *            the array where the resulting unnormalized components are
     *            written (or null to prompt the method to create the return
     *            array).
     * @param offset
     *            the offset that tells where the results should be written in
     *            the return array.
     * @return the unnormalized components.
     */
    public int[] getUnnormalizedComponents(float normComponents[], int normOffset,
            int components[], int offset) {

        if (bits == null) {
            // awt.26C=bits is null
            throw new UnsupportedOperationException(Messages.getString("awt.26C")); //$NON-NLS-1$
        }

        if (normComponents.length - normOffset < numComponents) {
            // awt.273=The length of normComponents minus normOffset is less
            // than numComponents
            throw new IllegalArgumentException(Messages.getString("awt.273")); //$NON-NLS-1$
        }

        if (components == null) {
            components = new int[numComponents + offset];
        } else {
            if (components.length - offset < numComponents) {
                // awt.272=The length of components minus offset is less than
                // numComponents
                throw new IllegalArgumentException(Messages.getString("awt.272")); //$NON-NLS-1$
            }
        }

        if (hasAlpha && isAlphaPremultiplied) {
            float alpha = normComponents[normOffset + numColorComponents];
            for (int i = 0; i < numColorComponents; i++) {
                components[offset + i] = (int)(normComponents[normOffset + i] * maxValues[i]
                        * alpha + 0.5f);
            }
            components[offset + numColorComponents] = (int)(normComponents[normOffset
                    + numColorComponents]
                    * maxValues[numColorComponents] + 0.5f);
        } else {
            for (int i = 0; i < numComponents; i++) {
                components[offset + i] = (int)(normComponents[normOffset + i] * maxValues[i] + 0.5f);
            }
        }

        return components;
    }

    /**
     * Gets the data element corresponding to the normalized components.
     * 
     * @param normComponents
     *            the normalized components.
     * @param normOffset
     *            the offset where the normalized components should be read from
     *            the normalized component array.
     * @return the data element.
     */
    public int getDataElement(float normComponents[], int normOffset) {
        int unnormComponents[] = getUnnormalizedComponents(normComponents, normOffset, null, 0);
        return getDataElement(unnormComponents, 0);
    }

    /**
     * Takes a pixel whose data is defined by an integer, and writes the
     * corresponding components into the components array, starting from the
     * index offset.
     * 
     * @param pixel
     *            the pixel data.
     * @param components
     *            the data array to write the components to (or null to have the
     *            method create the return array).
     * @param offset
     *            the offset that determines where the results are written in
     *            the components array.
     * @return the array of components corresponding to the pixel.
     */
    public int[] getComponents(int pixel, int components[], int offset) {
        throw new UnsupportedOperationException("This method is not " + //$NON-NLS-1$
                "supported by this ColorModel"); //$NON-NLS-1$
    }

    /**
     * Gets the red component of the pixel determined by the pixel data.
     * 
     * @param pixel
     *            the pixel.
     * @return the red component of the given pixel.
     */
    public abstract int getRed(int pixel);

    /**
     * Takes the pixel data and returns the integer value corresponding to the
     * pixel's color in RGB format.
     * 
     * @param pixel
     *            the pixel data.
     * @return the corresponding RGB integer value.
     */
    public int getRGB(int pixel) {
        return (getAlpha(pixel) << 24 | getRed(pixel) << 16 | getGreen(pixel) << 8 | getBlue(pixel));
    }

    /**
     * Gets the green component of the pixel determined by the pixel data.
     * 
     * @param pixel
     *            the pixel.
     * @return the green component of the given pixel.
     */
    public abstract int getGreen(int pixel);

    /**
     * Gets the size of the desired component of this color model.
     * 
     * @param componentIdx
     *            the index that determines which component size to get.
     * @return the component size corresponding to the index.
     * @throws NullPointerException
     *             if this color model doesn't support an array of separate
     *             components.
     * @throws ArrayIndexOutOfBoundsException
     *             if the index is negative or greater than or equal to the
     *             number of components.
     */
    public int getComponentSize(int componentIdx) {
        if (bits == null) {
            // awt.26C=bits is null
            throw new NullPointerException(Messages.getString("awt.26C")); //$NON-NLS-1$
        }

        if (componentIdx < 0 || componentIdx >= bits.length) {
            // awt.274=componentIdx is greater than the number of components or
            // less than zero
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.274")); //$NON-NLS-1$
        }

        return bits[componentIdx];
    }

    /**
     * Gets the blue component of the pixel determined by the pixel data.
     * 
     * @param pixel
     *            the pixel.
     * @return the blue component of the given pixel.
     */
    public abstract int getBlue(int pixel);

    /**
     * Gets the alpha component of the pixel determined by the pixel data.
     * 
     * @param pixel
     *            the pixel.
     * @return the alpha component of the given pixel.
     */
    public abstract int getAlpha(int pixel);

    /**
     * Gets the array of sizes of the different components.
     * 
     * @return the array of sizes of the different components.
     */
    public int[] getComponentSize() {
        if (bits != null) {
            return bits.clone();
        }
        return null;
    }

    /**
     * Checks if the alpha component is pre-multiplied.
     * 
     * @return true, if the alpha component is pre-multiplied.
     */
    public final boolean isAlphaPremultiplied() {
        return isAlphaPremultiplied;
    }

    /**
     * Checks whether this color model supports alpha.
     * 
     * @return true, if this color model has alpha.
     */
    public final boolean hasAlpha() {
        return hasAlpha;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        int tmp;

        if (hasAlpha) {
            hash ^= 1;
            hash <<= 8;
        }
        if (isAlphaPremultiplied) {
            hash ^= 1;
            hash <<= 8;
        }

        tmp = hash >>> 24;
        hash ^= numColorComponents;
        hash <<= 8;
        hash |= tmp;

        tmp = hash >>> 24;
        hash ^= transparency;
        hash <<= 8;
        hash |= tmp;

        tmp = hash >>> 24;
        hash ^= cs.getType();
        hash <<= 8;
        hash |= tmp;

        tmp = hash >>> 24;
        hash ^= pixel_bits;
        hash <<= 8;
        hash |= tmp;

        tmp = hash >>> 24;
        hash ^= transferType;
        hash <<= 8;
        hash |= tmp;

        if (bits != null) {

            for (int element : bits) {
                tmp = hash >>> 24;
                hash ^= element;
                hash <<= 8;
                hash |= tmp;
            }

        }

        return hash;
    }

    public int getTransparency() {
        return transparency;
    }

    /**
     * Gets the transfer type, which is the type of Java primitive value that
     * corresponds to the bit length per pixel: either
     * {@link DataBuffer#TYPE_BYTE}, {@link DataBuffer#TYPE_USHORT},
     * {@link DataBuffer#TYPE_INT}, or {@link DataBuffer#TYPE_UNDEFINED}.
     * 
     * @return the transfer type.
     */
    public final int getTransferType() {
        return transferType;
    }

    /**
     * Gets the pixel size in bits.
     * 
     * @return the pixel size.
     */
    public int getPixelSize() {
        return pixel_bits;
    }

    /**
     * Gets the number of components of this color model.
     * 
     * @return the number of components.
     */
    public int getNumComponents() {
        return numComponents;
    }

    /**
     * Gets the number of color components of this color model.
     * 
     * @return the number color components.
     */
    public int getNumColorComponents() {
        return numColorComponents;
    }

    /**
     * Gets the default RGB color model.
     * 
     * @return the default RGB color model.
     */
    public static ColorModel getRGBdefault() {
        if (RGBdefault == null) {
            RGBdefault = new DirectColorModel(32, 0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000);
        }
        return RGBdefault;
    }

    /*
     * Construct INT pixel representation from Object
     * @param obj
     * @return
     */
    /**
     * Construct pixel.
     * 
     * @param obj
     *            the obj.
     * @return the int.
     */
    private int constructPixel(Object obj) {
        int pixel = 0;

        switch (getTransferType()) {

            case DataBuffer.TYPE_BYTE:
                byte[] bPixel = (byte[])obj;
                if (bPixel.length > 1) {
                    // awt.275=This pixel representation is not suuported by tis
                    // Color Model
                    throw new UnsupportedOperationException(Messages.getString("awt.275")); //$NON-NLS-1$
                }
                pixel = bPixel[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short[] sPixel = (short[])obj;
                if (sPixel.length > 1) {
                    // awt.275=This pixel representation is not suuported by tis
                    // Color Model
                    throw new UnsupportedOperationException(Messages.getString("awt.275")); //$NON-NLS-1$
                }
                pixel = sPixel[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int[] iPixel = (int[])obj;
                if (iPixel.length > 1) {
                    // awt.275=This pixel representation is not suuported by tis
                    // Color Model
                    throw new UnsupportedOperationException(Messages.getString("awt.275")); //$NON-NLS-1$
                }
                pixel = iPixel[0];
                break;

            default:
                // awt.22D=This transferType ( {0} ) is not supported by this
                // color model
                throw new UnsupportedOperationException(Messages.getString("awt.22D", //$NON-NLS-1$
                        transferType));

        }
        return pixel;
    }

    /**
     * Gets the transfer type, which is the type of Java primitive value that
     * corresponds to the bit length per pixel: either
     * {@link DataBuffer#TYPE_BYTE}, {@link DataBuffer#TYPE_USHORT},
     * {@link DataBuffer#TYPE_INT}, or {@link DataBuffer#TYPE_UNDEFINED}.
     * 
     * @param bits
     *            the array of component masks.
     * @return the transfer type.
     */
    static int getTransferType(int bits) {
        if (bits <= 8) {
            return DataBuffer.TYPE_BYTE;
        } else if (bits <= 16) {
            return DataBuffer.TYPE_USHORT;
        } else if (bits <= 32) {
            return DataBuffer.TYPE_INT;
        } else {
            return DataBuffer.TYPE_UNDEFINED;
        }
    }

    @Override
    public void finalize() {
        // This method is added for the API compatibility
        // Don't need to call super since Object's finalize is always empty
    }
}
