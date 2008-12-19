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

import org.apache.harmony.awt.gl.color.LUTColorConverter;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class ComponentColorModel represents a color model that is defined in
 * terms of its components.
 * 
 * @since Android 1.0
 */
public class ComponentColorModel extends ColorModel {

    /**
     * The signed.
     */
    private boolean signed; // Pixel samples are signed.

    // Samples with TransferType DataBuffer.TYPE_BYTE,
    // DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT -
    // unsigned. Samples with others TransferType -
    // signed.

    /**
     * The integral.
     */
    private boolean integral; // Pixel samples are integral.

    // Samples with TransferType DataBuffer.TYPE_BYTE,
    // DataBuffer.TYPE_USHORT, DataBuffer.Short and
    // DataBuffer.TYPE_INT - integral.

    /**
     * The scale factors.
     */
    private float scaleFactors[]; // Array of factors for reduction components

    // values into the form scaled from 0 to 255

    /**
     * The donot support unnormalized.
     */
    private boolean donotSupportUnnormalized; // This Color Model don't support

    // unnormolized form

    /**
     * The need alpha divide.
     */
    private boolean needAlphaDivide; // hasAlpha && isAlphaPremultiplied

    /**
     * The calc value.
     */
    private boolean calcValue; // Value was culculated

    /**
     * The need scale.
     */
    private boolean needScale; // Normalized value need to scale

    /**
     * The min vals.
     */
    private float minVals[]; // Array of Min normalized values

    /**
     * The ranges.
     */
    private float ranges[]; // Array of range normalized values

    /**
     * The alpha lut.
     */
    private byte alphaLUT[]; // Lookup table for scale alpha value

    /**
     * The color lu ts.
     */
    private byte colorLUTs[][]; // Lookup tables for scale color values

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
     * The LINEA r_ rg b_ length.
     */
    private int LINEAR_RGB_Length; // Linear RGB bit length

    /**
     * The factor.
     */
    private float fFactor; // Scale factor

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
     * Instantiates a new component color model.
     * 
     * @param colorSpace
     *            the color space.
     * @param bits
     *            the array of component masks.
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
    public ComponentColorModel(ColorSpace colorSpace, int bits[], boolean hasAlpha,
            boolean isAlphaPremultiplied, int transparency, int transferType) {
        super(createPixelBits(colorSpace, hasAlpha, transferType), validateBits(bits, colorSpace,
                hasAlpha, transferType), colorSpace, hasAlpha, isAlphaPremultiplied, transparency,
                transferType);

        needScale = false;
        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
            case DataBuffer.TYPE_USHORT:
            case DataBuffer.TYPE_INT:
                signed = false;
                integral = true;
                donotSupportUnnormalized = false;
                scaleFactors = new float[numComponents];
                for (int i = 0; i < numColorComponents; i++) {
                    scaleFactors[i] = 1.0f / maxValues[i];
                    if (cs.getMinValue(i) != 0.0f || cs.getMaxValue(i) != 1.0f) {
                        donotSupportUnnormalized = true;
                    }
                }
                if (hasAlpha) {
                    maxValues[numColorComponents] = (1 << bits[numColorComponents]) - 1;
                    scaleFactors[numColorComponents] = 1.0f / maxValues[numColorComponents];
                }
                break;
            case DataBuffer.TYPE_SHORT:
                signed = true;
                integral = true;
                donotSupportUnnormalized = true;
                scaleFactors = new float[numComponents];
                for (int i = 0; i < numComponents; i++) {
                    maxValues[i] = Short.MAX_VALUE;
                    scaleFactors[i] = 1.0f / maxValues[i];
                    if (cs.getMinValue(i) != 0.0f || cs.getMaxValue(i) != 1.0f) {
                        needScale = true;
                    }
                }
                if (needScale) {
                    minVals = new float[numColorComponents];
                    ranges = new float[numColorComponents];
                    for (int i = 0; i < numColorComponents; i++) {
                        minVals[i] = cs.getMinValue(i);
                        ranges[i] = cs.getMaxValue(i) - minVals[i];
                    }
                }
                break;
            case DataBuffer.TYPE_FLOAT:
            case DataBuffer.TYPE_DOUBLE:
                signed = true;
                integral = false;
                donotSupportUnnormalized = true;
                break;
            default:
                // awt.215=transferType is not one of DataBuffer.TYPE_BYTE,
                // DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT,
                // DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or
                // DataBuffer.TYPE_DOUBLE
                throw new IllegalArgumentException(Messages.getString("awt.215")); //$NON-NLS-1$
        }

        needAlphaDivide = hasAlpha && isAlphaPremultiplied;
        initLUTs();
    }

    /**
     * Instantiates a new component color model.
     * 
     * @param colorSpace
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
    public ComponentColorModel(ColorSpace colorSpace, boolean hasAlpha,
            boolean isAlphaPremultiplied, int transparency, int transferType) {

        this(colorSpace, createPixelBitsArray(colorSpace, hasAlpha, transferType), hasAlpha,
                isAlphaPremultiplied, transparency, transferType);
    }

    /**
     * Validate bits.
     * 
     * @param bits
     *            the bits.
     * @param colorSpace
     *            the color space.
     * @param hasAlpha
     *            the has alpha.
     * @param transferType
     *            the transfer type.
     * @return the int[].
     */
    private static int[] validateBits(int bits[], ColorSpace colorSpace, boolean hasAlpha,
            int transferType) {
        if (bits != null) {
            return bits;
        }

        int numComponents = colorSpace.getNumComponents();
        if (hasAlpha) {
            numComponents++;
        }
        bits = new int[numComponents];

        int componentLength = DataBuffer.getDataTypeSize(transferType);

        for (int i = 0; i < numComponents; i++) {
            bits[i] = componentLength;
        }

        return bits;
    }

    /**
     * Creates the pixel bits.
     * 
     * @param colorSpace
     *            the color space.
     * @param hasAlpha
     *            the has alpha.
     * @param transferType
     *            the transfer type.
     * @return the int.
     */
    private static int createPixelBits(ColorSpace colorSpace, boolean hasAlpha, int transferType) {
        int numComponents = colorSpace.getNumComponents();
        if (hasAlpha) {
            numComponents++;
        }
        int componentLength = DataBuffer.getDataTypeSize(transferType);
        return numComponents * componentLength;
    }

    /**
     * Creates the pixel bits array.
     * 
     * @param colorSpace
     *            the color space.
     * @param hasAlpha
     *            the has alpha.
     * @param transferType
     *            the transfer type.
     * @return the int[].
     */
    private static int[] createPixelBitsArray(ColorSpace colorSpace, boolean hasAlpha,
            int transferType) {

        int numComponents = colorSpace.getNumComponents();
        if (hasAlpha) {
            numComponents++;
        }

        int bits[] = new int[numComponents];
        for (int i = 0; i < numComponents; i++) {
            bits[i] = DataBuffer.getDataTypeSize(transferType);
        }
        return bits;
    }

    @Override
    public Object getDataElements(int components[], int offset, Object obj) {
        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }

        if (offset + numComponents > components.length) {
            // awt.216=The components array is not large enough to hold all the
            // color and alpha components
            throw new IllegalArgumentException(Messages.getString("awt.216")); //$NON-NLS-1$
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[];
                if (obj == null) {
                    ba = new byte[numComponents];
                } else {
                    ba = (byte[])obj;
                }
                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    ba[i] = (byte)components[idx];
                }
                return ba;
            case DataBuffer.TYPE_USHORT:
                short sa[];
                if (obj == null) {
                    sa = new short[numComponents];
                } else {
                    sa = (short[])obj;
                }
                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    sa[i] = (short)components[idx];
                }
                return sa;
            case DataBuffer.TYPE_INT:
                int ia[];
                if (obj == null) {
                    ia = new int[numComponents];
                } else {
                    ia = (int[])obj;
                }
                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    ia[i] = components[idx];
                }
                return ia;
            default:
                // awt.217=The transfer type of this ComponentColorModel is not
                // one
                // of the following transfer types: DataBuffer.TYPE_BYTE,
                // DataBuffer.TYPE_USHORT, or DataBuffer.TYPE_INT
                throw new UnsupportedOperationException(Messages.getString("awt.217")); //$NON-NLS-1$
        }
    }

    @Override
    public Object getDataElements(float normComponents[], int normOffset, Object obj) {
        if (needScale) {
            for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                normComponents[idx] = (normComponents[idx] - minVals[i]) / ranges[i];
            }
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[];
                if (obj == null) {
                    ba = new byte[numComponents];
                } else {
                    ba = (byte[])obj;
                }

                if (needAlphaDivide) {
                    float alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = normOffset; i < numColorComponents; i++, idx++) {
                        ba[i] = (byte)(normComponents[idx] * alpha * maxValues[i] + 0.5f);
                    }
                    ba[numColorComponents] = (byte)(normComponents[normOffset + numColorComponents]
                            * maxValues[numColorComponents] + 0.5f);
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        ba[idx] = (byte)(normComponents[idx] * maxValues[i] + 0.5f);
                    }
                }
                return ba;

            case DataBuffer.TYPE_USHORT:
                short usa[];
                if (obj == null) {
                    usa = new short[numComponents];
                } else {
                    usa = (short[])obj;
                }

                if (needAlphaDivide) {
                    float alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                        usa[i] = (short)(normComponents[idx] * alpha * maxValues[i] + 0.5f);
                    }
                    usa[numColorComponents] = (short)(alpha * maxValues[numColorComponents] + 0.5f);
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        usa[i] = (short)(normComponents[idx] * maxValues[i] + 0.5f);
                    }
                }
                return usa;

            case DataBuffer.TYPE_INT:
                int ia[];
                if (obj == null) {
                    ia = new int[numComponents];
                } else {
                    ia = (int[])obj;
                }

                if (needAlphaDivide) {
                    float alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                        ia[i] = (int)(normComponents[idx] * alpha * maxValues[i] + 0.5f);
                    }
                    ia[numColorComponents] = (int)(alpha * maxValues[numColorComponents] + 0.5f);
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        ia[i] = (int)(normComponents[idx] * maxValues[i] + 0.5f);
                    }
                }
                return ia;

            case DataBuffer.TYPE_SHORT:
                short sa[];
                if (obj == null) {
                    sa = new short[numComponents];
                } else {
                    sa = (short[])obj;
                }

                if (needAlphaDivide) {
                    float alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                        sa[i] = (short)(normComponents[idx] * alpha * maxValues[i] + 0.5f);
                    }
                    sa[numColorComponents] = (short)(alpha * maxValues[numColorComponents] + 0.5f);
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        sa[i] = (short)(normComponents[idx] * maxValues[i] + 0.5f);
                    }
                }
                return sa;

            case DataBuffer.TYPE_FLOAT:
                float fa[];
                if (obj == null) {
                    fa = new float[numComponents];
                } else {
                    fa = (float[])obj;
                }

                if (needAlphaDivide) {
                    float alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                        fa[i] = normComponents[idx] * alpha;
                    }
                    fa[numColorComponents] = alpha;
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        fa[i] = normComponents[idx];
                    }
                }
                return fa;

            case DataBuffer.TYPE_DOUBLE:
                double da[];
                if (obj == null) {
                    da = new double[numComponents];
                } else {
                    da = (double[])obj;
                }

                if (needAlphaDivide) {
                    double alpha = normComponents[normOffset + numColorComponents];
                    for (int i = 0, idx = 0; i < numColorComponents; i++, idx++) {
                        da[i] = normComponents[idx] * alpha;
                    }
                    da[numColorComponents] = alpha;
                } else {
                    for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                        da[i] = normComponents[idx];
                    }
                }
                return da;

            default:
                // awt.213=This ComponentColorModel does not support the
                // unnormalized form
                throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }
    }

    @Override
    public Object getDataElements(int rgb, Object pixel) {
        float normComp[];
        float comp[];

        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int alpha = (rgb >> 24) & 0xff;

        comp = new float[3];
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
            float[] defComp = cs.fromRGB(comp);
            if (!hasAlpha) {
                normComp = defComp;
            } else {
                float normAlpha = alpha / 255.0f;
                normComp = new float[numComponents];
                for (int i = 0; i < numColorComponents; i++) {
                    normComp[i] = defComp[i];
                }
                normComp[numColorComponents] = normAlpha;
            }
        }
        if (hasAlpha && isAlphaPremultiplied) {
            normComp[0] *= normComp[numColorComponents];
            normComp[1] *= normComp[numColorComponents];
            normComp[2] *= normComp[numColorComponents];
        }

        return getDataElements(normComp, 0, pixel);
    }

    @Override
    public WritableRaster getAlphaRaster(WritableRaster raster) {
        if (!hasAlpha) {
            return null;
        }

        int x = raster.getMinX();
        int y = raster.getMinY();
        int bandList[] = new int[1];
        bandList[0] = raster.getNumBands() - 1;

        return raster.createWritableChild(x, y, raster.getWidth(), raster.getHeight(), x, y,
                bandList);
    }

    @Override
    public ColorModel coerceData(WritableRaster raster, boolean isAlphaPremultiplied) {
        if (!hasAlpha || this.isAlphaPremultiplied == isAlphaPremultiplied) {
            return this;
        }

        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int w = raster.getWidth();
        int h = raster.getHeight();

        if (isAlphaPremultiplied) {
            switch (transferType) {
                case DataBuffer.TYPE_BYTE:
                case DataBuffer.TYPE_USHORT:
                case DataBuffer.TYPE_INT:
                    float alphaFactor = maxValues[numColorComponents];
                    int iComponents[] = null;
                    int iTransparentComponents[] = new int[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            iComponents = raster.getPixel(x, minY, iComponents);
                            if (iComponents[numColorComponents] == 0) {
                                raster.setPixel(x, minY, iTransparentComponents);
                            } else {
                                float alpha = iComponents[numColorComponents] / alphaFactor;
                                for (int n = 0; n < numColorComponents; n++) {
                                    iComponents[n] = (int)(alpha * iComponents[n] + 0.5f);
                                }
                                raster.setPixel(x, minY, iComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_SHORT:
                    float sAlphaFactor = maxValues[numColorComponents];
                    short sComponents[] = null;
                    short sTransparentComponents[] = new short[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            sComponents = (short[])raster.getDataElements(x, minY, sComponents);
                            if (sComponents[numColorComponents] == 0) {
                                raster.setDataElements(x, minY, sTransparentComponents);
                            } else {
                                float alpha = sComponents[numColorComponents] / sAlphaFactor;
                                for (int n = 0; n < numColorComponents; n++) {
                                    sComponents[n] = (byte)(alpha * sComponents[n] + 0.5f);
                                }
                                raster.setDataElements(x, minY, sComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_FLOAT:
                    float fComponents[] = null;
                    float fTransparentComponents[] = new float[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            fComponents = raster.getPixel(x, minY, fComponents);
                            if (fComponents[numColorComponents] == 0.0f) {
                                raster.setDataElements(x, minY, fTransparentComponents);
                            } else {
                                float alpha = fComponents[numColorComponents];
                                for (int n = 0; n < numColorComponents; n++) {
                                    fComponents[n] = fComponents[n] * alpha;
                                }
                                raster.setPixel(x, minY, fComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_DOUBLE:
                    double dComponents[] = null;
                    double dTransparentComponents[] = new double[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            dComponents = raster.getPixel(x, minY, dComponents);
                            if (dComponents[numColorComponents] == 0.0) {
                                raster.setPixel(x, minY, dTransparentComponents);
                            } else {
                                double alpha = dComponents[numColorComponents];
                                for (int n = 0; n < numColorComponents; n++) {
                                    dComponents[n] = dComponents[n] * alpha;
                                }
                                raster.setPixel(x, minY, dComponents);
                            }
                        }

                    }
                    break;

                default:
                    // awt.219=This transferType is not supported by this color
                    // model
                    throw new UnsupportedOperationException(Messages.getString("awt.219")); //$NON-NLS-1$
            }
        } else {
            switch (transferType) {
                case DataBuffer.TYPE_BYTE:
                case DataBuffer.TYPE_USHORT:
                case DataBuffer.TYPE_INT:
                    float alphaFactor = maxValues[numColorComponents];
                    int iComponents[] = null;
                    int iTransparentComponents[] = new int[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            iComponents = raster.getPixel(x, minY, iComponents);
                            if (iComponents[numColorComponents] == 0) {
                                raster.setPixel(x, minY, iTransparentComponents);
                            } else {
                                float alpha = iComponents[numColorComponents] / alphaFactor;
                                for (int n = 0; n < numColorComponents; n++) {
                                    iComponents[n] = (int)(iComponents[n] / alpha + 0.5f);
                                }
                                raster.setPixel(x, minY, iComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_SHORT:
                    float sAlphaFactor = maxValues[numColorComponents];
                    short sComponents[] = null;
                    short sTransparentComponents[] = new short[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            sComponents = (short[])raster.getDataElements(x, minY, sComponents);
                            if (sComponents[numColorComponents] == 0) {
                                raster.setDataElements(x, minY, sTransparentComponents);
                            } else {
                                float alpha = sComponents[numColorComponents] / sAlphaFactor;
                                for (int n = 0; n < numColorComponents; n++) {
                                    sComponents[n] = (byte)(sComponents[n] / alpha + 0.5f);
                                }
                                raster.setDataElements(x, minY, sComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_FLOAT:
                    float fComponents[] = null;
                    float fTransparentComponents[] = new float[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            fComponents = raster.getPixel(x, minY, fComponents);
                            if (fComponents[numColorComponents] == 0.0f) {
                                raster.setDataElements(x, minY, fTransparentComponents);
                            } else {
                                float alpha = fComponents[numColorComponents];
                                for (int n = 0; n < numColorComponents; n++) {
                                    fComponents[n] = fComponents[n] / alpha;
                                }
                                raster.setPixel(x, minY, fComponents);
                            }
                        }

                    }
                    break;

                case DataBuffer.TYPE_DOUBLE:
                    double dComponents[] = null;
                    double dTransparentComponents[] = new double[numComponents];
                    for (int i = 0; i < h; i++, minY++) {
                        for (int j = 0, x = minX; j < w; j++, x++) {
                            dComponents = raster.getPixel(x, minY, dComponents);
                            if (dComponents[numColorComponents] == 0.0) {
                                raster.setPixel(x, minY, dTransparentComponents);
                            } else {
                                double alpha = dComponents[numColorComponents];
                                for (int n = 0; n < numColorComponents; n++) {
                                    dComponents[n] = dComponents[n] / alpha;
                                }
                                raster.setPixel(x, minY, dComponents);
                            }
                        }

                    }
                    break;
                default:
                    // awt.219=This transferType is not supported by this color
                    // model
                    throw new UnsupportedOperationException(Messages.getString("awt.219")); //$NON-NLS-1$
            }
        }

        if (!signed) {
            return new ComponentColorModel(cs, bits, hasAlpha, isAlphaPremultiplied, transparency,
                    transferType);
        }

        return new ComponentColorModel(cs, null, hasAlpha, isAlphaPremultiplied, transparency,
                transferType);
    }

    @Override
    public int[] getComponents(Object pixel, int[] components, int offset) {
        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }

        if (components == null) {
            components = new int[offset + numComponents];
        } else if (offset + numComponents > components.length) {
            // awt.218=The components array is not large enough to hold all the
            // color and alpha components
            throw new IllegalArgumentException(Messages.getString("awt.218")); //$NON-NLS-1$
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])pixel;

                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    components[idx] = ba[i] & 0xff;
                }
                return components;

            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])pixel;
                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    components[idx] = sa[i] & 0xffff;
                }
                return components;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])pixel;
                for (int i = 0, idx = offset; i < numComponents; i++, idx++) {
                    components[idx] = ia[i];
                }
                return components;

            default:
                // awt.217=The transfer type of this ComponentColorModel is not
                // one
                // of the following transfer types: DataBuffer.TYPE_BYTE,
                // DataBuffer.TYPE_USHORT, or DataBuffer.TYPE_INT
                throw new UnsupportedOperationException(Messages.getString("awt.217")); //$NON-NLS-1$
        }

    }

    @Override
    public float[] getNormalizedComponents(Object pixel, float normComponents[], int normOffset) {

        if (normComponents == null) {
            normComponents = new float[numComponents + normOffset];
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = (ba[i] & 0xff) * scaleFactors[i];
                }
                break;

            case DataBuffer.TYPE_USHORT:
                short usa[] = (short[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = (usa[i] & 0xffff) * scaleFactors[i];
                }
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = ia[i] * scaleFactors[i];
                }
                break;

            case DataBuffer.TYPE_SHORT:
                short sa[] = (short[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = sa[i] * scaleFactors[i];
                }
                break;

            case DataBuffer.TYPE_FLOAT:
                float fa[] = (float[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = fa[i];
                }
                break;

            case DataBuffer.TYPE_DOUBLE:
                double da[] = (double[])pixel;
                for (int i = 0, idx = normOffset; i < numComponents; i++, idx++) {
                    normComponents[idx] = (float)da[i];
                }
                break;

            default:
                // awt.21A=This ComponentColorModel does not support this
                // transferType
                throw new IllegalArgumentException(Messages.getString("awt.21A")); //$NON-NLS-1$
        }

        if (needAlphaDivide) {
            float alpha = normComponents[normOffset + numColorComponents];
            for (int i = 0, idx = normOffset; i < numColorComponents; i++, idx++) {
                normComponents[idx] /= alpha;
            }
        }

        if (needScale) {
            for (int i = 0, idx = normOffset; i < numColorComponents; i++, idx++) {
                normComponents[idx] = minVals[i] + ranges[i] * normComponents[idx];
            }
        }
        return normComponents;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ComponentColorModel)) {
            return false;
        }
        return super.equals(obj);
    }

    @Override
    public int getRed(Object inData) {
        return getRGBComponent(inData, 0);
    }

    @Override
    public int getRGB(Object inData) {
        int alpha = getAlpha(inData);
        if (cs.getType() == ColorSpace.TYPE_GRAY) {
            int gray = getRed(inData);
            return (alpha << 24 | gray << 16 | gray << 8 | gray);
        }
        return (alpha << 24 | getRed(inData) << 16 | getGreen(inData) << 8 | getBlue(inData));
    }

    @Override
    public int getGreen(Object inData) {
        return getRGBComponent(inData, 1);
    }

    @Override
    public int getBlue(Object inData) {
        return getRGBComponent(inData, 2);
    }

    @Override
    public int getAlpha(Object inData) {
        if (!hasAlpha) {
            return 255;
        }
        int alpha = 0;

        switch (transferType) {
            case DataBuffer.TYPE_BYTE: {
                byte ba[] = (byte[])inData;
                alpha = ba[numColorComponents] & 0xff;
                if (bits[numColorComponents] != 8) {
                    return alphaLUT[alpha] & 0xff;
                }
                return alpha;
            }
            case DataBuffer.TYPE_USHORT: {
                short usa[] = (short[])inData;
                alpha = usa[numColorComponents] & 0xffff;
                if (bits[numColorComponents] != 8) {
                    return alphaLUT[alpha] & 0xff;
                }
                return alpha;
            }
            case DataBuffer.TYPE_INT: {
                int ia[] = (int[])inData;
                alpha = ia[numColorComponents];
                if (bits[numColorComponents] != 8) {
                    return alphaLUT[alpha] & 0xff;
                }
                return alpha;
            }
            case DataBuffer.TYPE_SHORT: {
                short sa[] = (short[])inData;
                alpha = sa[numColorComponents];
                if (bits[numColorComponents] != 8) {
                    return alphaLUT[alpha] & 0xff;
                }
                return alpha;
            }
            case DataBuffer.TYPE_FLOAT: {
                float fa[] = (float[])inData;
                return (int)(fa[numColorComponents] * 255.0f + 0.5f);
            }
            case DataBuffer.TYPE_DOUBLE: {
                double da[] = (double[])inData;
                return (int)(da[numColorComponents] * 255.0 + 0.5);
            }
            default: {
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
            }
        }
    }

    @Override
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        SampleModel sm = createCompatibleSampleModel(w, h);
        DataBuffer db = sm.createDataBuffer();
        return Raster.createWritableRaster(sm, db, null);
    }

    @Override
    public boolean isCompatibleSampleModel(SampleModel sm) {
        if (!(sm instanceof ComponentSampleModel)) {
            return false;
        }
        if (numComponents != sm.getNumBands()) {
            return false;
        }
        if (transferType != sm.getTransferType()) {
            return false;
        }
        return true;
    }

    @Override
    public SampleModel createCompatibleSampleModel(int w, int h) {
        int bandOffsets[] = new int[numComponents];
        for (int i = 0; i < numComponents; i++) {
            bandOffsets[i] = i;
        }

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
            case DataBuffer.TYPE_USHORT:
                return new PixelInterleavedSampleModel(transferType, w, h, numComponents, w
                        * numComponents, bandOffsets);

            default:
                return new ComponentSampleModel(transferType, w, h, numComponents, w
                        * numComponents, bandOffsets);
        }
    }

    @Override
    public boolean isCompatibleRaster(Raster raster) {
        SampleModel sm = raster.getSampleModel();
        if (!(sm instanceof ComponentSampleModel)) {
            return false;
        }

        if (sm.getNumBands() != numComponents) {
            return false;
        }
        if (raster.getTransferType() != transferType) {
            return false;
        }

        int sampleSizes[] = sm.getSampleSize();
        for (int i = 0; i < numComponents; i++) {
            if (bits[i] != sampleSizes[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public float[] getNormalizedComponents(int components[], int offset, float normComponents[],
            int normOffset) {
        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }

        return super.getNormalizedComponents(components, offset, normComponents, normOffset);
    }

    @Override
    public int getDataElement(int[] components, int offset) {
        if (numComponents > 1) {
            // awt.212=There is more than one component in this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.212")); //$NON-NLS-1$
        }
        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }
        return components[offset];
    }

    @Override
    public int[] getUnnormalizedComponents(float[] normComponents, int normOffset,
            int[] components, int offset) {

        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }

        if (normComponents.length - normOffset < numComponents) {
            // awt.21B=The length of normComponents minus normOffset is less
            // than numComponents
            throw new IllegalArgumentException(Messages.getString("awt.21B")); //$NON-NLS-1$
        }

        return super.getUnnormalizedComponents(normComponents, normOffset, components, offset);
    }

    @Override
    public int getDataElement(float normComponents[], int normOffset) {
        if (numComponents > 1) {
            // awt.212=There is more than one component in this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.212")); //$NON-NLS-1$
        }
        if (signed) {
            // awt.210=The component value for this ColorModel is signed
            throw new IllegalArgumentException(Messages.getString("awt.210")); //$NON-NLS-1$
        }

        Object pixel = getDataElements(normComponents, normOffset, null);

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])pixel;
                return ba[0] & 0xff;
            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])pixel;
                return sa[0] & 0xffff;
            case DataBuffer.TYPE_INT:
                int ia[] = (int[])pixel;
                return ia[0];
            default:
                // awt.211=Pixel values for this ColorModel are not conveniently
                // representable as a single int
                throw new IllegalArgumentException(Messages.getString("awt.211")); //$NON-NLS-1$
        }
    }

    @Override
    public int[] getComponents(int pixel, int components[], int offset) {
        if (numComponents > 1) {
            // awt.212=There is more than one component in this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.212")); //$NON-NLS-1$
        }
        if (donotSupportUnnormalized) {
            // awt.213=This ComponentColorModel does not support the
            // unnormalized form
            throw new IllegalArgumentException(Messages.getString("awt.213")); //$NON-NLS-1$
        }

        if (components == null) {
            components = new int[offset + 1];
        }

        components[offset] = pixel & maxValues[0];
        return components;
    }

    @Override
    public int getRed(int pixel) {
        float rgb[] = toRGB(pixel);
        return (int)(rgb[0] * 255.0f + 0.5f);
    }

    @Override
    public int getRGB(int pixel) {
        return (getAlpha(pixel) << 24) | (getRed(pixel) << 16) | (getGreen(pixel) << 8)
                | getBlue(pixel);
    }

    @Override
    public int getGreen(int pixel) {
        float rgb[] = toRGB(pixel);
        return (int)(rgb[1] * 255.0f + 0.5f);
    }

    @Override
    public int getBlue(int pixel) {
        float rgb[] = toRGB(pixel);
        return (int)(rgb[2] * 255.0f + 0.5f);
    }

    @Override
    public int getAlpha(int pixel) {

        // This method throw IllegalArgumentException according to
        // Java API Spacification
        if (signed) {
            // awt.210=The component value for this ColorModel is signed
            throw new IllegalArgumentException(Messages.getString("awt.210")); //$NON-NLS-1$
        }

        if (numComponents > 1) {
            // awt.212=There is more than one component in this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.212")); //$NON-NLS-1$
        }

        return 255;
    }

    /**
     * Initialization of Lookup tables.
     */
    private void initLUTs() {
        is_sRGB = cs.isCS_sRGB();
        is_LINEAR_RGB = (cs == LUTColorConverter.LINEAR_RGB_CS);

        if (hasAlpha && bits[numColorComponents] != 8 && integral) {
            alphaLUT = new byte[maxValues[numColorComponents] + 1];
            for (int i = 0; i <= maxValues[numColorComponents]; i++) {
                alphaLUT[i] = (byte)(scaleFactors[numColorComponents] * i + 0.5f);
            }
        }

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

        if (!isAlphaPremultiplied && integral) {
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
                        for (int j = 0; j <= maxValues[0]; j++) {
                            colorLUTs[i][j] = (byte)(scaleFactors[i] * j + 0.5f);
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
                                idx = (int)(scaleFactors[i] * j + 0.5f);
                            } else {
                                idx = (int)(scaleFactors[i] * j * 257.0f + 0.5f);
                            }
                            colorLUTs[i][j] = from_LINEAR_RGB_LUT[idx];
                        }
                    }
                }
            }

        }
    }

    /**
     * To rgb.
     * 
     * @param pixel
     *            the integer representation of the pixel.
     * @return the array of normalized sRGB components.
     */
    private float[] toRGB(int pixel) {

        // This method throw IllegalArgumentException according to
        // Java API Spacification
        if (signed) {
            // awt.210=The component value for this ColorModel is signed
            throw new IllegalArgumentException(Messages.getString("awt.210")); //$NON-NLS-1$
        }

        if (numComponents > 1) {
            // awt.212=There is more than one component in this ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.212")); //$NON-NLS-1$
        }

        Object obj = null;

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = new byte[1];
                ba[0] = (byte)pixel;
                obj = ba;
                break;

            case DataBuffer.TYPE_USHORT:
                short sa[] = new short[1];
                sa[0] = (short)pixel;
                obj = sa;
                break;

            case DataBuffer.TYPE_INT:
                int ia[] = new int[1];
                ia[0] = pixel;
                obj = ia;
                break;

        }

        return cs.toRGB(getNormalizedComponents(obj, null, 0));
    }

    /**
     * Gets the RGB component.
     * 
     * @param pixel
     *            the pixel.
     * @param idx
     *            the index of component.
     * @return the RGB value from 0 to 255 pixel's component.
     */
    private int getRGBComponent(Object pixel, int idx) {
        if (is_sRGB) {
            int comp = getDefComponent(pixel, idx);
            if (calcValue || bits[idx] == 8) {
                return comp;
            }
            return colorLUTs[idx][comp] & 0xff;
        } else if (is_LINEAR_RGB) {
            int comp = getDefComponent(pixel, idx);
            if (calcValue || bits[idx] == LINEAR_RGB_Length) {
                return from_LINEAR_RGB_LUT[comp] & 0xff;
            }
            return colorLUTs[idx][comp] & 0xff;
        }

        float normComp[] = getNormalizedComponents(pixel, null, 0);
        float rgbComp[] = cs.toRGB(normComp);
        return (int)(rgbComp[idx] * 255.0f + 0.5f);
    }

    /**
     * Gets the def component.
     * 
     * @param pixel
     *            the pixel.
     * @param idx
     *            the index of component.
     * @return the tentative value of the pixel component.
     */
    private int getDefComponent(Object pixel, int idx) {
        int comp = 0;
        calcValue = false;

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])pixel;
                comp = ba[idx] & 0xff;
                if (needAlphaDivide) {
                    int alpha = ba[numColorComponents] & 0xff;
                    if (alpha == 0) {
                        comp = 0;
                    } else {
                        float normAlpha = scaleFactors[numColorComponents] * alpha;
                        comp = (int)(comp * fFactor / normAlpha + 0.5f);
                    }
                    calcValue = true;
                }
                return comp;

            case DataBuffer.TYPE_USHORT:
                short usa[] = (short[])pixel;
                comp = usa[idx] & 0xffff;
                if (needAlphaDivide) {
                    int alpha = usa[numColorComponents] & 0xffff;
                    if (alpha == 0) {
                        comp = 0;
                    } else {
                        float normAlpha = scaleFactors[numColorComponents] * alpha;
                        comp = (int)(comp * fFactor / normAlpha + 0.5f);
                    }
                    calcValue = true;
                }
                return comp;

            case DataBuffer.TYPE_INT:
                int ia[] = (int[])pixel;
                comp = ia[idx];
                if (needAlphaDivide) {
                    int alpha = ia[numColorComponents];
                    if (alpha == 0) {
                        comp = 0;
                    } else {
                        float normAlpha = scaleFactors[numColorComponents] * alpha;
                        comp = (int)(comp * fFactor / normAlpha + 0.5f);
                    }
                    calcValue = true;
                }
                return comp;

            case DataBuffer.TYPE_SHORT:
                short sa[] = (short[])pixel;
                comp = sa[idx];
                if (needAlphaDivide) {
                    int alpha = sa[numColorComponents];
                    if (alpha == 0) {
                        comp = 0;
                    } else {
                        float normAlpha = scaleFactors[numColorComponents] * alpha;
                        comp = (int)(comp * fFactor / normAlpha + 0.5f);
                    }
                    calcValue = true;
                }
                return comp;

            case DataBuffer.TYPE_FLOAT:
                float fa[] = (float[])pixel;
                if (needAlphaDivide) {
                    float alpha = fa[numColorComponents];
                    if (fa[numColorComponents] == 0.0f) {
                        comp = 0;
                    } else {
                        comp = (int)(fa[idx] * fFactor / alpha + 0.5f);
                    }
                } else {
                    comp = (int)(fa[idx] * fFactor + 0.5f);
                }
                calcValue = true;
                return comp;

            case DataBuffer.TYPE_DOUBLE:
                double da[] = (double[])pixel;
                if (needAlphaDivide) {
                    if (da[numColorComponents] == 0.0) {
                        comp = 0;
                    } else {
                        comp = (int)(da[idx] * fFactor / da[numColorComponents] + 0.5);
                    }
                } else {
                    comp = (int)(da[idx] * fFactor + 0.5);
                }
                calcValue = true;
                return comp;

            default:
                // awt.214=This Color Model doesn't support this transferType
                throw new UnsupportedOperationException(Messages.getString("awt.214")); //$NON-NLS-1$
        }
    }

}
