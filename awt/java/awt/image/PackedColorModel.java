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
 * The class PackedColorModel represents a color model where the components are
 * just the red, green, and blue bands, plus an alpha band if alpha is
 * supported.
 * 
 * @since Android 1.0
 */
public abstract class PackedColorModel extends ColorModel {

    /**
     * The component masks.
     */
    int componentMasks[];

    /**
     * The offsets.
     */
    int offsets[];

    /**
     * The scales.
     */
    float scales[];

    /**
     * Instantiates a new packed color model.
     * 
     * @param space
     *            the color space.
     * @param bits
     *            the array of component masks.
     * @param colorMaskArray
     *            the array that gives the bitmask corresponding to each color
     *            band (red, green, and blue).
     * @param alphaMask
     *            the bitmask corresponding to the alpha band.
     * @param isAlphaPremultiplied
     *            whether the alpha is pre-multiplied in this color model.
     * @param trans
     *            the transparency strategy, @see java.awt.Transparency.
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     * @throws IllegalArgumentException
     *             if the number of bits in the combined bitmasks for the color
     *             bands is less than one or greater than 32.
     */
    public PackedColorModel(ColorSpace space, int bits, int colorMaskArray[], int alphaMask,
            boolean isAlphaPremultiplied, int trans, int transferType) {

        super(bits, createBits(colorMaskArray, alphaMask), space, (alphaMask == 0 ? false : true),
                isAlphaPremultiplied, trans, validateTransferType(transferType));

        if (pixel_bits < 1 || pixel_bits > 32) {
            // awt.236=The bits is less than 1 or greater than 32
            throw new IllegalArgumentException(Messages.getString("awt.236")); //$NON-NLS-1$
        }

        componentMasks = new int[numComponents];
        for (int i = 0; i < numColorComponents; i++) {
            componentMasks[i] = colorMaskArray[i];
        }

        if (hasAlpha) {
            componentMasks[numColorComponents] = alphaMask;
            if (this.bits[numColorComponents] == 1) {
                transparency = Transparency.BITMASK;
            }
        }

        parseComponents();
    }

    /**
     * Instantiates a new packed color model.
     * 
     * @param space
     *            the color space.
     * @param bits
     *            the array of component masks.
     * @param rmask
     *            the bitmask corresponding to the red band.
     * @param gmask
     *            the bitmask corresponding to the green band.
     * @param bmask
     *            the bitmask corresponding to the blue band.
     * @param amask
     *            the bitmask corresponding to the alpha band.
     * @param isAlphaPremultiplied
     *            whether the alpha is pre-multiplied in this color model.
     * @param trans
     *            the transparency strategy, @see java.awt.Transparency.
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     * @throws IllegalArgumentException
     *             if the number of bits in the combined bitmasks for the color
     *             bands is less than one or greater than 32.
     */
    public PackedColorModel(ColorSpace space, int bits, int rmask, int gmask, int bmask, int amask,
            boolean isAlphaPremultiplied, int trans, int transferType) {

        super(bits, createBits(rmask, gmask, bmask, amask), space, (amask == 0 ? false : true),
                isAlphaPremultiplied, trans, validateTransferType(transferType));

        if (pixel_bits < 1 || pixel_bits > 32) {
            // awt.236=The bits is less than 1 or greater than 32
            throw new IllegalArgumentException(Messages.getString("awt.236")); //$NON-NLS-1$
        }

        if (cs.getType() != ColorSpace.TYPE_RGB) {
            // awt.239=The space is not a TYPE_RGB space
            throw new IllegalArgumentException(Messages.getString("awt.239")); //$NON-NLS-1$
        }

        for (int i = 0; i < numColorComponents; i++) {
            if (cs.getMinValue(i) != 0.0f || cs.getMaxValue(i) != 1.0f) {
                // awt.23A=The min/max normalized component values are not
                // 0.0/1.0
                throw new IllegalArgumentException(Messages.getString("awt.23A")); //$NON-NLS-1$
            }
        }
        componentMasks = new int[numComponents];
        componentMasks[0] = rmask;
        componentMasks[1] = gmask;
        componentMasks[2] = bmask;

        if (hasAlpha) {
            componentMasks[3] = amask;
            if (this.bits[3] == 1) {
                transparency = Transparency.BITMASK;
            }
        }

        parseComponents();
    }

    @Override
    public WritableRaster getAlphaRaster(WritableRaster raster) {
        if (!hasAlpha) {
            return null;
        }

        int x = raster.getMinX();
        int y = raster.getMinY();
        int w = raster.getWidth();
        int h = raster.getHeight();
        int band[] = new int[1];
        band[0] = raster.getNumBands() - 1;
        return raster.createWritableChild(x, y, w, h, x, y, band);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof PackedColorModel)) {
            return false;
        }
        PackedColorModel cm = (PackedColorModel)obj;

        return (pixel_bits == cm.getPixelSize() && transferType == cm.getTransferType()
                && cs.getType() == cm.getColorSpace().getType() && hasAlpha == cm.hasAlpha()
                && isAlphaPremultiplied == cm.isAlphaPremultiplied()
                && transparency == cm.getTransparency()
                && numColorComponents == cm.getNumColorComponents()
                && numComponents == cm.getNumComponents()
                && Arrays.equals(bits, cm.getComponentSize()) && Arrays.equals(componentMasks, cm
                .getMasks()));
    }

    @Override
    public boolean isCompatibleSampleModel(SampleModel sm) {
        if (sm == null) {
            return false;
        }
        if (!(sm instanceof SinglePixelPackedSampleModel)) {
            return false;
        }
        SinglePixelPackedSampleModel esm = (SinglePixelPackedSampleModel)sm;

        return ((esm.getNumBands() == numComponents) && (esm.getTransferType() == transferType) && Arrays
                .equals(esm.getBitMasks(), componentMasks));
    }

    @Override
    public SampleModel createCompatibleSampleModel(int w, int h) {
        return new SinglePixelPackedSampleModel(transferType, w, h, componentMasks);
    }

    /**
     * Gets the bitmask corresponding to the specified color component.
     * 
     * @param index
     *            the index of the desired color.
     * @return the mask.
     */
    public final int getMask(int index) {
        return componentMasks[index];
    }

    /**
     * Gets the bitmasks of the components.
     * 
     * @return the masks.
     */
    public final int[] getMasks() {
        return (componentMasks.clone());
    }

    /**
     * Creates the bits.
     * 
     * @param colorMaskArray
     *            the color mask array.
     * @param alphaMask
     *            the alpha mask.
     * @return the int[].
     */
    private static int[] createBits(int colorMaskArray[], int alphaMask) {
        int bits[];
        int numComp;
        if (alphaMask == 0) {
            numComp = colorMaskArray.length;
        } else {
            numComp = colorMaskArray.length + 1;
        }

        bits = new int[numComp];
        int i = 0;
        for (; i < colorMaskArray.length; i++) {
            bits[i] = countCompBits(colorMaskArray[i]);
            if (bits[i] < 0) {
                // awt.23B=The mask of the {0} component is not contiguous
                throw new IllegalArgumentException(Messages.getString("awt.23B", i)); //$NON-NLS-1$
            }
        }

        if (i < numComp) {
            bits[i] = countCompBits(alphaMask);

            if (bits[i] < 0) {
                // awt.23C=The mask of the alpha component is not contiguous
                throw new IllegalArgumentException(Messages.getString("awt.23C")); //$NON-NLS-1$
            }
        }

        return bits;
    }

    /**
     * Creates the bits.
     * 
     * @param rmask
     *            the rmask.
     * @param gmask
     *            the gmask.
     * @param bmask
     *            the bmask.
     * @param amask
     *            the amask.
     * @return the int[].
     */
    private static int[] createBits(int rmask, int gmask, int bmask, int amask) {

        int numComp;
        if (amask == 0) {
            numComp = 3;
        } else {
            numComp = 4;
        }
        int bits[] = new int[numComp];

        bits[0] = countCompBits(rmask);
        if (bits[0] < 0) {
            // awt.23D=The mask of the red component is not contiguous
            throw new IllegalArgumentException(Messages.getString("awt.23D")); //$NON-NLS-1$
        }

        bits[1] = countCompBits(gmask);
        if (bits[1] < 0) {
            // awt.23E=The mask of the green component is not contiguous
            throw new IllegalArgumentException(Messages.getString("awt.23E")); //$NON-NLS-1$
        }

        bits[2] = countCompBits(bmask);
        if (bits[2] < 0) {
            // awt.23F=The mask of the blue component is not contiguous
            throw new IllegalArgumentException(Messages.getString("awt.23F")); //$NON-NLS-1$
        }

        if (amask != 0) {
            bits[3] = countCompBits(amask);
            if (bits[3] < 0) {
                // awt.23C=The mask of the alpha component is not contiguous
                throw new IllegalArgumentException(Messages.getString("awt.23C")); //$NON-NLS-1$
            }
        }

        return bits;
    }

    /**
     * Count comp bits.
     * 
     * @param compMask
     *            the comp mask.
     * @return the int.
     */
    private static int countCompBits(int compMask) {
        int bits = 0;
        if (compMask != 0) {
            // Deleting final zeros
            while ((compMask & 1) == 0) {
                compMask >>>= 1;
            }
            // Counting component bits
            while ((compMask & 1) == 1) {
                compMask >>>= 1;
                bits++;
            }
        }

        if (compMask != 0) {
            return -1;
        }

        return bits;
    }

    /**
     * Validate transfer type.
     * 
     * @param transferType
     *            the transfer type.
     * @return the int.
     */
    private static int validateTransferType(int transferType) {
        if (transferType != DataBuffer.TYPE_BYTE && transferType != DataBuffer.TYPE_USHORT
                && transferType != DataBuffer.TYPE_INT) {
            // awt.240=The transferType not is one of DataBuffer.TYPE_BYTE,
            // DataBuffer.TYPE_USHORT or DataBuffer.TYPE_INT
            throw new IllegalArgumentException(Messages.getString("awt.240")); //$NON-NLS-1$
        }
        return transferType;
    }

    /**
     * Parses the components.
     */
    private void parseComponents() {
        offsets = new int[numComponents];
        scales = new float[numComponents];
        for (int i = 0; i < numComponents; i++) {
            int off = 0;
            int mask = componentMasks[i];
            while ((mask & 1) == 0) {
                mask >>>= 1;
                off++;
            }
            offsets[i] = off;
            if (bits[i] == 0) {
                scales[i] = 256.0f; // May be any value different from zero,
                // because will dividing by zero
            } else {
                scales[i] = 255.0f / maxValues[i];
            }
        }

    }

}
