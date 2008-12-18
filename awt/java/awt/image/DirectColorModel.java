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

import java.awt.color.ColorSpace;
import java.awt.Transparency;
import java.util.Arrays;

import org.apache.harmony.awt.gl.color.LUTColorConverter;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class DirectColorModel represents a standard (packed) RGB color model
 * with additional support for converting between sRGB color space and 8 or 16
 * bit linear RGB color space using lookup tables.
 * 
 * @since Android 1.0
 */
public class DirectColorModel extends PackedColorModel {

    /**
     * The from_ linea r_ rg b_ lut.
     */
    private byte from_LINEAR_RGB_LUT[]; // Lookup table for conversion from

    // Linear RGB Color Space into sRGB

    /**
     * The to_ linea r_8 rg b_ lut.
     */
    private byte to_LINEAR_8RGB_LUT[]; // Lookup table for conversion from

    // sRGB Color Space into Linear RGB
    // 8 bit

    /**
     * The to_ linea r_16 rg b_ lut.
     */
    private short to_LINEAR_16RGB_LUT[]; // Lookup table for conversion from

    // sRGB Color Space into Linear RGB
    // 16 bit

    /**
     * The alpha lut.
     */
    private byte alphaLUT[]; // Lookup table for scale alpha value

    /**
     * The color lu ts.
     */
    private byte colorLUTs[][]; // Lookup tables for scale color values

    /**
     * The is_s rgb.
     */
    private boolean is_sRGB; // ColorModel has sRGB ColorSpace

    /**
     * The is_ linea r_ rgb.
     */
    private boolean is_LINEAR_RGB; // Color Model has Linear RGB Color

    // Space

    /**
     * The LINEA r_ rg b_ length.
     */
    private int LINEAR_RGB_Length; // Linear RGB bit length

    /**
     * The factor.
     */
    private float fFactor; // Scale factor

    /**
     * Instantiates a new direct color model.
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
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     * @throws IllegalArgumentException
     *             if the number of bits in the combined bitmasks for the color
     *             bands is less than one or greater than 32.
     */
    public DirectColorModel(ColorSpace space, int bits, int rmask, int gmask, int bmask, int amask,
            boolean isAlphaPremultiplied, int transferType) {

        super(space, bits, rmask, gmask, bmask, amask, isAlphaPremultiplied,
                (amask == 0 ? Transparency.OPAQUE : Transparency.TRANSLUCENT), transferType);

        initLUTs();
    }

    /**
     * Instantiates a new direct color model, determining the transfer type from
     * the bits array, the transparency from the alpha mask, and the default
     * color space {@link ColorSpace#CS_sRGB}.
     * 
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
     */
    public DirectColorModel(int bits, int rmask, int gmask, int bmask, int amask) {

        super(ColorSpace.getInstance(ColorSpace.CS_sRGB), bits, rmask, gmask, bmask, amask, false,
                (amask == 0 ? Transparency.OPAQUE : Transparency.TRANSLUCENT), ColorModel
                        .getTransferType(bits));

        initLUTs();
    }

    /**
     * Instantiates a new direct color model with no alpha channel, determining
     * the transfer type from the bits array, the default color space
     * {@link ColorSpace#CS_sRGB}, and with the transparency set to
     * {@link Transparency#OPAQUE}.
     * 
     * @param bits
     *            the array of component masks.
     * @param rmask
     *            the bitmask corresponding to the red band.
     * @param gmask
     *            the bitmask corresponding to the green band.
     * @param bmask
     *            the bitmask corresponding to the blue band.
     */
    public DirectColorModel(int bits, int rmask, int gmask, int bmask) {
        this(bits, rmask, gmask, bmask, 0);
    }

    @Override
    public Object getDataElements(int components[], int offset, Object obj) {
        int pixel = 0;
        for (int i = 0; i < numComponents; i++) {
            pixel |= (components[offset + i] << offsets[i]) & componentMasks[i];
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[];
                if (obj == null) {
                    ba = new byte[1];
                } else {
                    ba = (byte[])obj;
                }
                ba[0] = (byte)pixel;
                obj = ba;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[];
                if (obj == null) {
                    sa = new short[1];
                } else {
                    sa = (short[])obj;
                }
                sa[0] = (short)pixel;
                obj = sa;
                break;

            case DataBuffer.TYPE_INT:
                int ia[];
                if (obj == null) {
                    ia = new int[1];
                } else {
                    ia = (int[])obj;
                }
                ia[0] = pixel;
                obj = ia;
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }

        return obj;
    }

    @Override
    public Object getDataElements(int rgb, Object pixel) {
        if (equals(ColorModel.getRGBdefault())) {
            int ia[];
            if (pixel == null) {
                ia = new int[1];
            } else {
                ia = (int[])pixel;
            }
            ia[0] = rgb;
            return ia;
        }

        int alpha = (rgb >> 24) & 0xff;
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;

        float comp[] = new float[numColorComponents];
        float normComp[] = null;

        if (is_sRGB || is_LINEAR_RGB) {
            if (is_LINEAR_RGB) {
                if (LINEAR_RGB_Length == 8) {
                    red = to_LINEAR_8RGB_LUT[red] & 0xff;
                    green = to_LINEAR_8RGB_LUT[green] & 0xff;
                    blue = to_LINEAR_8RGB_LUT[blue] & 0xff;
                } else {
                    red = to_LINEAR_16RGB_LUT[red] & 0xffff;
                    green = to_LINEAR_16RGB_LUT[green] & 0xffff;
                    blue = to_LINEAR_16RGB_LUT[blue] & 0xffff;
                }
            }
            comp[0] = red / fFactor;
            comp[1] = green / fFactor;
            comp[2] = blue / fFactor;
            if (!hasAlpha) {
                normComp = comp;
            } else {
                float normAlpha = alpha / 255.0f;
                normComp = new float[numComponents];
                for (int i = 0; i < numColorComponents; i++) {
                    normComp[i] = comp[i];
                }
                normComp[numColorComponents] = normAlpha;
            }
        } else {
            comp[0] = red / fFactor;
            comp[1] = green / fFactor;
            comp[2] = blue / fFactor;
            float rgbComp[] = cs.fromRGB(comp);
            if (!hasAlpha) {
                normComp = rgbComp;
            } else {
                float normAlpha = alpha / 255.0f;
                normComp = new float[numComponents];
                for (int i = 0; i < numColorComponents; i++) {
                    normComp[i] = rgbComp[i];
                }
                normComp[numColorComponents] = normAlpha;
            }
        }

        int pxl = 0;
        if (hasAlpha) {
            float normAlpha = normComp[numColorComponents];
            alpha = (int)(normAlpha * maxValues[numColorComponents] + 0.5f);
            if (isAlphaPremultiplied) {
                red = (int)(normComp[0] * normAlpha * maxValues[0] + 0.5f);
                green = (int)(normComp[1] * normAlpha * maxValues[1] + 0.5f);
                blue = (int)(normComp[2] * normAlpha * maxValues[2] + 0.5f);
            } else {
                red = (int)(normComp[0] * maxValues[0] + 0.5f);
                green = (int)(normComp[1] * maxValues[1] + 0.5f);
                blue = (int)(normComp[2] * maxValues[2] + 0.5f);
            }
            pxl = (alpha << offsets[3]) & componentMasks[3];
        } else {
            red = (int)(normComp[0] * maxValues[0] + 0.5f);
            green = (int)(normComp[1] * maxValues[1] + 0.5f);
            blue = (int)(normComp[2] * maxValues[2] + 0.5f);
        }

        pxl |= ((red << offsets[0]) & componentMasks[0])
                | ((green << offsets[1]) & componentMasks[1])
                | ((blue << offsets[2]) & componentMasks[2]);

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[];
                if (pixel == null) {
                    ba = new byte[1];
                } else {
                    ba = (byte[])pixel;
                }
                ba[0] = (byte)pxl;
                return ba;

            case DataBuffer.TYPE_USHORT:
                short sa[];
                if (pixel == null) {
                    sa = new short[1];
                } else {
                    sa = (short[])pixel;
                }
                sa[0] = (short)pxl;
                return sa;

            case DataBuffer.TYPE_INT:
                int ia[];
                if (pixel == null) {
                    ia = new int[1];
                } else {
                    ia = (int[])pixel;
                }
                ia[0] = pxl;
                return ia;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
    }

    @Override
    public final ColorModel coerceData(WritableRaster raster, boolean isAlphaPremultiplied) {

        if (!hasAlpha || this.isAlphaPremultiplied == isAlphaPremultiplied) {
            return this;
        }

        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int w = raster.getWidth();
        int h = raster.getHeight();

        int components[] = null;
        int transparentComponents[] = new int[numComponents];

        float alphaFactor = maxValues[numColorComponents];

        if (isAlphaPremultiplied) {
            switch (transferType) {
                case DataBuffer.TYPE_BYTE:
                case DataBuffer.TYPE_USHORT:
                case DataBuffer.TYPE_INT:
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            components = raster.getPixel(x, minY, components);
                            if (components[numColorComponents] == 0) {
                                raster.setPixel(x, minY, transparentComponents);
                            } else {
                                float alpha = components[numColorComponents] / alphaFactor;
                                for (int n = 0; n < numColorComponents; n++) {
                                    components[n] = (int)(alpha * components[n] + 0.5f);
                                }
                                raster.setPixel(x, minY, components);
                            }
                        }

                    }
                    break;

                default:
                    // awt.214=This Color Model doesn't support this
                    // transferType
                    throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
            }
        } else {
            switch (transferType) {
                case DataBuffer.TYPE_BYTE:
                case DataBuffer.TYPE_USHORT:
                case DataBuffer.TYPE_INT:
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            components = raster.getPixel(x, minY, components);
                            if (components[numColorComponents] != 0) {
                                float alpha = alphaFactor / components[numColorComponents];
                                for (int n = 0; n < numColorComponents; n++) {
                                    components[n] = (int)(alpha * components[n] + 0.5f);
                                }
                                raster.setPixel(x, minY, components);
                            }
                        }

                    }
                    break;

                default:
                    // awt.214=This Color Model doesn't support this
                    // transferType
                    throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
            }

        }

        return new DirectColorModel(cs, pixel_bits, componentMasks[0], componentMasks[1],
                componentMasks[2], componentMasks[3], isAlphaPremultiplied, transferType);
    }

    @Override
    public String toString() {
        // The output format based on 1.5 release behaviour.
        // It could be reveled such way:
        // BufferedImage bi = new BufferedImage(1, 1,
        // BufferedImage.TYPE_INT_ARGB);
        // ColorModel cm = bi.getColorModel();
        // System.out.println(cm.toString());
        String str = "DirectColorModel:" + " rmask = " + //$NON-NLS-1$ //$NON-NLS-2$
                Integer.toHexString(componentMasks[0]) + " gmask = " + //$NON-NLS-1$
                Integer.toHexString(componentMasks[1]) + " bmask = " + //$NON-NLS-1$
                Integer.toHexString(componentMasks[2]) + " amask = " + //$NON-NLS-1$
                (!hasAlpha ? "0" : Integer.toHexString(componentMasks[3])); //$NON-NLS-1$

        return str;
    }

    @Override
    public final int[] getComponents(Object pixel, int components[], int offset) {

        if (components == null) {
            components = new int[numComponents + offset];
        }

        int intPixel = 0;

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])pixel;
                intPixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])pixel;
                intPixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])pixel;
                intPixel = ia[0];
                break;

            default:
                // awt.22D=This transferType ( {0} ) is not supported by this
                // color model
                throw new UnsupportedOperationException(Messages.getString("awt.22D", //$NON-NLS-1$
                        transferType));
        }

        return getComponents(intPixel, components, offset);
    }

    @Override
    public int getRed(Object inData) {
        int pixel = 0;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])inData;
                pixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])inData;
                pixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])inData;
                pixel = ia[0];
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
        return getRed(pixel);
    }

    @Override
    public int getRGB(Object inData) {
        int pixel = 0;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])inData;
                pixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])inData;
                pixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])inData;
                pixel = ia[0];
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
        return getRGB(pixel);
    }

    @Override
    public int getGreen(Object inData) {
        int pixel = 0;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])inData;
                pixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])inData;
                pixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])inData;
                pixel = ia[0];
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
        return getGreen(pixel);
    }

    @Override
    public int getBlue(Object inData) {
        int pixel = 0;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])inData;
                pixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])inData;
                pixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])inData;
                pixel = ia[0];
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
        return getBlue(pixel);
    }

    @Override
    public int getAlpha(Object inData) {
        int pixel = 0;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])inData;
                pixel = ba[0] & 0xff;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])inData;
                pixel = sa[0] & 0xffff;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])inData;
                pixel = ia[0];
                break;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
        return getAlpha(pixel);
    }

    @Override
    public final WritableRaster createCompatibleWritableRaster(int w, int h) {
        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new IllegalArgumentException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        int bandMasks[] = componentMasks.clone();

        if (pixel_bits > 16) {
            return Raster.createPackedRaster(DataBuffer.TYPE_INT, w, h, bandMasks, null);
        } else if (pixel_bits > 8) {
            return Raster.createPackedRaster(DataBuffer.TYPE_USHORT, w, h, bandMasks, null);
        } else {
            return Raster.createPackedRaster(DataBuffer.TYPE_BYTE, w, h, bandMasks, null);
        }
    }

    @Override
    public boolean isCompatibleRaster(Raster raster) {
        SampleModel sm = raster.getSampleModel();
        if (!(sm instanceof SinglePixelPackedSampleModel)) {
            return false;
        }

        SinglePixelPackedSampleModel sppsm = (SinglePixelPackedSampleModel)sm;

        if (sppsm.getNumBands() != numComponents) {
            return false;
        }
        if (raster.getTransferType() != transferType) {
            return false;
        }

        int maskBands[] = sppsm.getBitMasks();
        return Arrays.equals(maskBands, componentMasks);
    }

    @Override
    public int getDataElement(int components[], int offset) {
        int pixel = 0;
        for (int i = 0; i < numComponents; i++) {
            pixel |= (components[offset + i] << offsets[i]) & componentMasks[i];
        }
        return pixel;
    }

    @Override
    public final int[] getComponents(int pixel, int components[], int offset) {
        if (components == null) {
            components = new int[numComponents + offset];
        }
        for (int i = 0; i < numComponents; i++) {
            components[offset + i] = (pixel & componentMasks[i]) >> offsets[i];
        }
        return components;
    }

    @Override
    public final int getRed(int pixel) {
        if (is_sRGB) {
            return getComponentFrom_sRGB(pixel, 0);
        }
        if (is_LINEAR_RGB) {
            return getComponentFrom_LINEAR_RGB(pixel, 0);
        }
        return getComponentFrom_RGB(pixel, 0);
    }

    @Override
    public final int getRGB(int pixel) {
        return (getAlpha(pixel) << 24) | (getRed(pixel) << 16) | (getGreen(pixel) << 8)
                | getBlue(pixel);
    }

    @Override
    public final int getGreen(int pixel) {
        if (is_sRGB) {
            return getComponentFrom_sRGB(pixel, 1);
        }
        if (is_LINEAR_RGB) {
            return getComponentFrom_LINEAR_RGB(pixel, 1);
        }
        return getComponentFrom_RGB(pixel, 1);
    }

    @Override
    public final int getBlue(int pixel) {
        if (is_sRGB) {
            return getComponentFrom_sRGB(pixel, 2);
        }
        if (is_LINEAR_RGB) {
            return getComponentFrom_LINEAR_RGB(pixel, 2);
        }
        return getComponentFrom_RGB(pixel, 2);
    }

    @Override
    public final int getAlpha(int pixel) {
        if (!hasAlpha) {
            return 255;
        }
        int a = (pixel & componentMasks[3]) >>> offsets[3];
        if (bits[3] == 8) {
            return a;
        }
        return alphaLUT[a] & 0xff;
    }

    /**
     * Gets the red mask.
     * 
     * @return the red mask.
     */
    public final int getRedMask() {
        return componentMasks[0];
    }

    /**
     * Gets the green mask.
     * 
     * @return the green mask.
     */
    public final int getGreenMask() {
        return componentMasks[1];
    }

    /**
     * Gets the blue mask.
     * 
     * @return the blue mask.
     */
    public final int getBlueMask() {
        return componentMasks[2];
    }

    /**
     * Gets the alpha mask.
     * 
     * @return the alpha mask.
     */
    public final int getAlphaMask() {
        if (hasAlpha) {
            return componentMasks[3];
        }
        return 0;
    }

    /**
     * Initialization of Lookup tables.
     */
    private void initLUTs() {
        is_sRGB = cs.isCS_sRGB();
        is_LINEAR_RGB = (cs == LUTColorConverter.LINEAR_RGB_CS);

        if (is_LINEAR_RGB) {
            if (maxBitLength > 8) {
                LINEAR_RGB_Length = 16;
                from_LINEAR_RGB_LUT = LUTColorConverter.getFrom16lRGBtosRGB_LUT();
                to_LINEAR_16RGB_LUT = LUTColorConverter.getFromsRGBto16lRGB_LUT();
            } else {
                LINEAR_RGB_Length = 8;
                from_LINEAR_RGB_LUT = LUTColorConverter.getFrom8lRGBtosRGB_LUT();
                to_LINEAR_8RGB_LUT = LUTColorConverter.getFromsRGBto8lRGB_LUT();
            }
            fFactor = ((1 << LINEAR_RGB_Length) - 1);
        } else {
            fFactor = 255.0f;
        }

        if (hasAlpha && bits[3] != 8) {
            alphaLUT = new byte[maxValues[3] + 1];
            for (int i = 0; i <= maxValues[3]; i++) {
                alphaLUT[i] = (byte)(scales[3] * i + 0.5f);
            }

        }

        if (!isAlphaPremultiplied) {
            colorLUTs = new byte[3][];

            if (is_sRGB) {
                for (int i = 0; i < numColorComponents; i++) {
                    if (bits[i] != 8) {
                        for (int j = 0; j < i; j++) {
                            if (bits[i] == bits[j]) {
                                colorLUTs[i] = colorLUTs[j];
                                break;
                            }
                        }
                        colorLUTs[i] = new byte[maxValues[i] + 1];
                        for (int j = 0; j <= maxValues[i]; j++) {
                            colorLUTs[i][j] = (byte)(scales[i] * j + 0.5f);
                        }
                    }
                }
            }

            if (is_LINEAR_RGB) {
                for (int i = 0; i < numColorComponents; i++) {
                    if (bits[i] != LINEAR_RGB_Length) {
                        for (int j = 0; j < i; j++) {
                            if (bits[i] == bits[j]) {
                                colorLUTs[i] = colorLUTs[j];
                                break;
                            }
                        }
                        colorLUTs[i] = new byte[maxValues[i] + 1];
                        for (int j = 0; j <= maxValues[0]; j++) {
                            int idx;
                            if (LINEAR_RGB_Length == 8) {
                                idx = (int)(scales[i] * j + 0.5f);
                            } else {
                                idx = (int)(scales[i] * j * 257.0f + 0.5f);
                            }
                            colorLUTs[i][j] = from_LINEAR_RGB_LUT[idx];
                        }
                    }
                }
            }

        }
    }

    /**
     * This method return RGB component value if Color Model has sRGB
     * ColorSpace.
     * 
     * @param pixel
     *            the integer representation of the pixel.
     * @param idx
     *            the index of the pixel component.
     * @return the value of the pixel component scaled from 0 to 255.
     */
    private int getComponentFrom_sRGB(int pixel, int idx) {
        int comp = (pixel & componentMasks[idx]) >> offsets[idx];
        if (isAlphaPremultiplied) {
            int alpha = (pixel & componentMasks[3]) >>> offsets[3];
            comp = alpha == 0 ? 0 : (int)(scales[idx] * comp * 255.0f / (scales[3] * alpha) + 0.5f);
        } else if (bits[idx] != 8) {
            comp = colorLUTs[idx][comp] & 0xff;
        }
        return comp;
    }

    /**
     * This method return RGB component value if Color Model has Linear RGB
     * ColorSpace.
     * 
     * @param pixel
     *            the integer representation of the pixel.
     * @param idx
     *            the index of the pixel component.
     * @return the value of the pixel component scaled from 0 to 255.
     */
    private int getComponentFrom_LINEAR_RGB(int pixel, int idx) {
        int comp = (pixel & componentMasks[idx]) >> offsets[idx];
        if (isAlphaPremultiplied) {
            float factor = ((1 << LINEAR_RGB_Length) - 1);
            int alpha = (pixel & componentMasks[3]) >> offsets[3];
            comp = alpha == 0 ? 0 : (int)(scales[idx] * comp * factor / (scales[3] * alpha) + 0.5f);
        } else if (bits[idx] != LINEAR_RGB_Length) {
            comp = colorLUTs[idx][comp] & 0xff;
        } else {
            comp = from_LINEAR_RGB_LUT[comp] & 0xff;
        }
        return comp;
    }

    /**
     * This method return RGB component value if Color Model has arbitrary RGB
     * ColorSapce.
     * 
     * @param pixel
     *            the integer representation of the pixel.
     * @param idx
     *            the index of the pixel component.
     * @return the value of the pixel component scaled from 0 to 255.
     */
    private int getComponentFrom_RGB(int pixel, int idx) {
        int components[] = getComponents(pixel, null, 0);
        float[] normComponents = getNormalizedComponents(components, 0, null, 0);
        float[] sRGBcomponents = cs.toRGB(normComponents);
        return (int)(sRGBcomponents[idx] * 255.0f + 0.5f);
    }

}
