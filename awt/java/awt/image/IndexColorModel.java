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
import java.math.BigInteger;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class IndexColorModel represents a color model in which the color values
 * of the pixels are read from a palette.
 * 
 * @since Android 1.0
 */
public class IndexColorModel extends ColorModel {

    /**
     * The color map.
     */
    private int colorMap[]; // Color Map

    /**
     * The map size.
     */
    private int mapSize; // Color Map size

    /**
     * The transparent index.
     */
    private int transparentIndex; // Index of fully transparent pixel

    /**
     * The gray palette.
     */
    private boolean grayPalette; // Color Model has Color Map with Gray Pallete

    /**
     * The valid bits.
     */
    private BigInteger validBits; // Specify valid Color Map values

    /**
     * The Constant CACHESIZE.
     */
    private static final int CACHESIZE = 20; // Cache size. Cache used for

    // improving performace of selection
    // nearest color in Color Map

    /**
     * The cachetable.
     */
    private final int cachetable[] = new int[CACHESIZE * 2]; // Cache table -

    // used for

    // storing RGB values and that appropriate indices
    // in the Color Map

    /**
     * The next insert idx.
     */
    private int nextInsertIdx = 0; // Next index for insertion into Cache table

    /**
     * The total inserted.
     */
    private int totalInserted = 0; // Number of inserted values into Cache table

    /**
     * Instantiates a new index color model.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param cmap
     *            the array that gives the color mapping.
     * @param start
     *            the start index of the color mapping data within the cmap
     *            array.
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     * @param validBits
     *            a list of which bits represent valid colormap values, or null
     *            if all are valid.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     */
    public IndexColorModel(int bits, int size, int cmap[], int start, int transferType,
            BigInteger validBits) {

        super(bits, IndexColorModel.createBits(true), ColorSpace.getInstance(ColorSpace.CS_sRGB),
                true, false, Transparency.OPAQUE, validateTransferType(transferType));

        if (size < 1) {
            // awt.264=Size of the color map is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.264")); //$NON-NLS-1$
        }

        mapSize = size;
        colorMap = new int[mapSize];
        transparentIndex = -1;

        if (validBits != null) {
            for (int i = 0; i < mapSize; i++) {
                if (!validBits.testBit(i)) {
                    this.validBits = validBits;
                }
                break;
            }
        }

        transparency = Transparency.OPAQUE;
        int alphaMask = 0xff000000;
        int alpha = 0;

        for (int i = 0; i < mapSize; i++, start++) {
            colorMap[i] = cmap[start];
            alpha = cmap[start] & alphaMask;

            if (alpha == alphaMask) {
                continue;
            }
            if (alpha == 0) {
                if (transparentIndex < 0) {
                    transparentIndex = i;
                }
                if (transparency == Transparency.OPAQUE) {
                    transparency = Transparency.BITMASK;
                }
            } else if (alpha != alphaMask && transparency != Transparency.TRANSLUCENT) {
                transparency = Transparency.TRANSLUCENT;
            }

        }
        checkPalette();

    }

    /**
     * Instantiates a new index color model.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param cmap
     *            the array that gives the color mapping.
     * @param start
     *            the start index of the color mapping data within the cmap
     *            array.
     * @param hasalpha
     *            whether this color model uses alpha.
     * @param trans
     *            the transparency supported, @see java.awt.Transparency.
     * @param transferType
     *            the transfer type (primitive java type to use for the
     *            components).
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     */
    public IndexColorModel(int bits, int size, int cmap[], int start, boolean hasalpha, int trans,
            int transferType) {

        super(bits, IndexColorModel.createBits(hasalpha || (trans >= 0)), ColorSpace
                .getInstance(ColorSpace.CS_sRGB), (hasalpha || (trans >= 0)), false,
                Transparency.OPAQUE, validateTransferType(transferType));

        if (size < 1) {
            // awt.264=Size of the color map is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.264")); //$NON-NLS-1$
        }

        mapSize = size;
        colorMap = new int[mapSize];
        if (trans >= 0 && trans < mapSize) {
            transparentIndex = trans;
            transparency = Transparency.BITMASK;
        } else {
            transparentIndex = -1;
            transparency = Transparency.OPAQUE;
        }

        int alphaMask = 0xff000000;
        int alpha = 0;

        for (int i = 0; i < mapSize; i++, start++) {
            if (transparentIndex == i) {
                colorMap[i] = cmap[start] & 0x00ffffff;
                continue;
            }
            if (hasalpha) {
                alpha = cmap[start] & alphaMask;
                colorMap[i] = cmap[start];

                if (alpha == alphaMask) {
                    continue;
                }
                if (alpha == 0) {
                    if (trans < 0) {
                        trans = i;
                    }
                    if (transparency == Transparency.OPAQUE) {
                        transparency = Transparency.BITMASK;
                    }
                } else if (alpha != 0 && transparency != Transparency.TRANSLUCENT) {
                    transparency = Transparency.TRANSLUCENT;
                }
            } else {
                colorMap[i] = alphaMask | cmap[start];
            }
        }
        checkPalette();

    }

    /**
     * Instantiates a new index color model by building the color map from
     * arrays of red, green, blue, and alpha values.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param r
     *            the array giving the red components of the entries in the
     *            color map.
     * @param g
     *            the array giving the green components of the entries in the
     *            color map.
     * @param b
     *            the array giving the blue components of the entries in the
     *            color map.
     * @param a
     *            the array giving the alpha components of the entries in the
     *            color map.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of one of the component arrays is less than the
     *             size of the color map.
     */
    public IndexColorModel(int bits, int size, byte r[], byte g[], byte b[], byte a[]) {

        super(bits, IndexColorModel.createBits(true), ColorSpace.getInstance(ColorSpace.CS_sRGB),
                true, false, Transparency.OPAQUE, validateTransferType(ColorModel
                        .getTransferType(bits)));

        createColorMap(size, r, g, b, a, -1);
        checkPalette();
    }

    /**
     * Instantiates a new index color model by building the color map from
     * arrays of red, green, and blue values.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param r
     *            the array giving the red components of the entries in the
     *            color map.
     * @param g
     *            the array giving the green components of the entries in the
     *            color map.
     * @param b
     *            the array giving the blue components of the entries in the
     *            color map.
     * @param trans
     *            the transparency supported, @see java.awt.Transparency.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of one of the component arrays is less than the
     *             size of the color map.
     */
    public IndexColorModel(int bits, int size, byte r[], byte g[], byte b[], int trans) {

        super(bits, IndexColorModel.createBits((trans >= 0)), ColorSpace
                .getInstance(ColorSpace.CS_sRGB), (trans >= 0), false, Transparency.OPAQUE,
                validateTransferType(ColorModel.getTransferType(bits)));

        createColorMap(size, r, g, b, null, trans);
        checkPalette();
    }

    /**
     * Instantiates a new index color model by building the color map from
     * arrays of red, green, and blue values.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param r
     *            the array giving the red components of the entries in the
     *            color map.
     * @param g
     *            the array giving the green components of the entries in the
     *            color map.
     * @param b
     *            the array giving the blue components of the entries in the
     *            color map.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of one of the component arrays is less than the
     *             size of the color map.
     */
    public IndexColorModel(int bits, int size, byte r[], byte g[], byte b[]) {
        super(bits, IndexColorModel.createBits(false), ColorSpace.getInstance(ColorSpace.CS_sRGB),
                false, false, Transparency.OPAQUE, validateTransferType(ColorModel
                        .getTransferType(bits)));

        createColorMap(size, r, g, b, null, -1);
        checkPalette();
    }

    /**
     * Instantiates a new index color model.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param cmap
     *            the array that gives the color mapping.
     * @param start
     *            the start index of the color mapping data within the cmap
     *            array.
     * @param hasalpha
     *            whether this color model uses alpha.
     * @param trans
     *            the transparency supported, @see java.awt.Transparency.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     */
    public IndexColorModel(int bits, int size, byte cmap[], int start, boolean hasalpha, int trans) {

        super(bits, IndexColorModel.createBits(hasalpha || (trans >= 0)), ColorSpace
                .getInstance(ColorSpace.CS_sRGB), (hasalpha || (trans >= 0)), false,
                Transparency.OPAQUE, validateTransferType(ColorModel.getTransferType(bits)));

        if (size < 1) {
            // awt.264=Size of the color map is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.264")); //$NON-NLS-1$
        }

        mapSize = size;
        colorMap = new int[mapSize];
        transparentIndex = -1;

        transparency = Transparency.OPAQUE;
        int alpha = 0xff000000;

        for (int i = 0; i < mapSize; i++) {
            colorMap[i] = (cmap[start++] & 0xff) << 16 | (cmap[start++] & 0xff) << 8
                    | (cmap[start++] & 0xff);
            if (trans == i) {
                if (transparency == Transparency.OPAQUE) {
                    transparency = Transparency.BITMASK;
                }
                if (hasalpha) {
                    start++;
                }
                continue;
            }
            if (hasalpha) {
                alpha = cmap[start++] & 0xff;
                if (alpha == 0) {
                    if (transparency == Transparency.OPAQUE) {
                        transparency = Transparency.BITMASK;
                        if (trans < 0) {
                            trans = i;
                        }
                    }
                } else {
                    if (alpha != 0xff && transparency != Transparency.TRANSLUCENT) {
                        transparency = Transparency.TRANSLUCENT;
                    }
                }
                alpha <<= 24;
            }
            colorMap[i] |= alpha;
        }

        if (trans >= 0 && trans < mapSize) {
            transparentIndex = trans;
        }
        checkPalette();

    }

    /**
     * Instantiates a new index color model.
     * 
     * @param bits
     *            the array of component masks.
     * @param size
     *            the size of the color map.
     * @param cmap
     *            the array that gives the color mapping.
     * @param start
     *            the start index of the color mapping data within the cmap
     *            array.
     * @param hasalpha
     *            whether this color model uses alpha.
     * @throws IllegalArgumentException
     *             if the size of the color map is less than one.
     */
    public IndexColorModel(int bits, int size, byte cmap[], int start, boolean hasalpha) {

        this(bits, size, cmap, start, hasalpha, -1);
    }

    @Override
    public Object getDataElements(int[] components, int offset, Object pixel) {
        int rgb = (components[offset] << 16) | (components[offset + 1]) << 8
                | components[offset + 2];
        if (hasAlpha) {
            rgb |= components[offset + 3] << 24;
        } else {
            rgb |= 0xff000000;
        }
        return getDataElements(rgb, pixel);
    }

    @Override
    public synchronized Object getDataElements(int rgb, Object pixel) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int alpha = rgb >>> 24;
        int pixIdx = 0;

        for (int i = 0; i < totalInserted; i++) {
            int idx = i * 2;
            if (rgb == cachetable[idx]) {
                return createDataObject(cachetable[idx + 1], pixel);
            }
        }

        if (!hasAlpha && grayPalette) {
            int grey = (red * 77 + green * 150 + blue * 29 + 128) >>> 8;
            int minError = 255;
            int error = 0;

            for (int i = 0; i < mapSize; i++) {
                error = Math.abs((colorMap[i] & 0xff) - grey);
                if (error < minError) {
                    pixIdx = i;
                    if (error == 0) {
                        break;
                    }
                    minError = error;
                }
            }
        } else if (alpha == 0 && transparentIndex > -1) {
            pixIdx = transparentIndex;
        } else {
            int minAlphaError = 255;
            int minError = 195075; // 255^2 + 255^2 + 255^2
            int alphaError;
            int error = 0;

            for (int i = 0; i < mapSize; i++) {
                int pix = colorMap[i];
                if (rgb == pix) {
                    pixIdx = i;
                    break;
                }
                alphaError = Math.abs(alpha - (pix >>> 24));
                if (alphaError <= minAlphaError) {
                    minAlphaError = alphaError;

                    int buf = ((pix >> 16) & 0xff) - red;
                    error = buf * buf;

                    if (error < minError) {
                        buf = ((pix >> 8) & 0xff) - green;
                        error += buf * buf;

                        if (error < minError) {
                            buf = (pix & 0xff) - blue;
                            error += buf * buf;

                            if (error < minError) {
                                pixIdx = i;
                                minError = error;
                            }
                        }
                    }
                }
            }
        }

        cachetable[nextInsertIdx] = rgb;
        cachetable[nextInsertIdx + 1] = pixIdx;

        nextInsertIdx = (nextInsertIdx + 2) % (CACHESIZE * 2);
        if (totalInserted < CACHESIZE) {
            totalInserted++;
        }

        return createDataObject(pixIdx, pixel);
    }

    /**
     * Converts an image from indexed to RGB format.
     * 
     * @param raster
     *            the raster containing the source image.
     * @param forceARGB
     *            whether to use the default RGB color model.
     * @return the buffered image.
     * @throws IllegalArgumentException
     *             if the raster is not compatible with this color model.
     */
    public BufferedImage convertToIntDiscrete(Raster raster, boolean forceARGB) {

        if (!isCompatibleRaster(raster)) {
            // awt.265=The raster argument is not compatible with this
            // IndexColorModel
            throw new IllegalArgumentException(Messages.getString("awt.265")); //$NON-NLS-1$
        }

        ColorModel model;
        if (forceARGB || transparency == Transparency.TRANSLUCENT) {
            model = ColorModel.getRGBdefault();
        } else if (transparency == Transparency.BITMASK) {
            model = new DirectColorModel(25, 0x00ff0000, 0x0000ff00, 0x000000ff, 0x01000000);
        } else {
            model = new DirectColorModel(24, 0x00ff0000, 0x0000ff00, 0x000000ff);
        }

        int w = raster.getWidth();
        int h = raster.getHeight();

        WritableRaster distRaster = model.createCompatibleWritableRaster(w, h);

        int minX = raster.getMinX();
        int minY = raster.getMinY();

        Object obj = null;
        int pixels[] = null;

        for (int i = 0; i < h; i++, minY++) {
            obj = raster.getDataElements(minX, minY, w, 1, obj);
            if (obj instanceof byte[]) {
                byte ba[] = (byte[])obj;
                if (pixels == null) {
                    pixels = new int[ba.length];
                }
                for (int j = 0; j < ba.length; j++) {
                    pixels[j] = colorMap[ba[j] & 0xff];
                }
            } else if (obj instanceof short[]) {
                short sa[] = (short[])obj;
                if (pixels == null) {
                    pixels = new int[sa.length];
                }
                for (int j = 0; j < sa.length; j++) {
                    pixels[j] = colorMap[sa[j] & 0xffff];
                }
            }
            if (obj instanceof int[]) {
                int ia[] = (int[])obj;
                if (pixels == null) {
                    pixels = new int[ia.length];
                }
                for (int j = 0; j < ia.length; j++) {
                    pixels[j] = colorMap[ia[j]];
                }
            }

            distRaster.setDataElements(0, i, w, 1, pixels);
        }

        return new BufferedImage(model, distRaster, false, null);
    }

    /**
     * Gets the valid pixels.
     * 
     * @return the valid pixels.
     */
    public BigInteger getValidPixels() {
        return validBits;
    }

    @Override
    public String toString() {
        // The output format based on 1.5 release behaviour.
        // It could be reveled such way:
        // BufferedImage bi = new BufferedImage(1, 1,
        // BufferedImage.TYPE_BYTE_INDEXED);
        // ColorModel cm = bi.getColorModel();
        // System.out.println(cm.toString());
        String str = "IndexColorModel: #pixel_bits = " + pixel_bits + //$NON-NLS-1$
                " numComponents = " + numComponents + " color space = " + cs + //$NON-NLS-1$ //$NON-NLS-2$
                " transparency = "; //$NON-NLS-1$

        if (transparency == Transparency.OPAQUE) {
            str = str + "Transparency.OPAQUE"; //$NON-NLS-1$
        } else if (transparency == Transparency.BITMASK) {
            str = str + "Transparency.BITMASK"; //$NON-NLS-1$
        } else {
            str = str + "Transparency.TRANSLUCENT"; //$NON-NLS-1$
        }

        str = str + " transIndex = " + transparentIndex + " has alpha = " + //$NON-NLS-1$ //$NON-NLS-2$
                hasAlpha + " isAlphaPre = " + isAlphaPremultiplied; //$NON-NLS-1$

        return str;
    }

    @Override
    public int[] getComponents(Object pixel, int components[], int offset) {
        int pixIdx = -1;
        if (pixel instanceof byte[]) {
            byte ba[] = (byte[])pixel;
            pixIdx = ba[0] & 0xff;
        } else if (pixel instanceof short[]) {
            short sa[] = (short[])pixel;
            pixIdx = sa[0] & 0xffff;
        } else if (pixel instanceof int[]) {
            int ia[] = (int[])pixel;
            pixIdx = ia[0];
        } else {
            // awt.219=This transferType is not supported by this color model
            throw new UnsupportedOperationException(Messages.getString("awt.219")); //$NON-NLS-1$
        }

        return getComponents(pixIdx, components, offset);
    }

    @Override
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        WritableRaster raster;
        if (pixel_bits == 1 || pixel_bits == 2 || pixel_bits == 4) {
            raster = Raster.createPackedRaster(DataBuffer.TYPE_BYTE, w, h, 1, pixel_bits, null);
        } else if (pixel_bits <= 8) {
            raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, w, h, 1, null);
        } else if (pixel_bits <= 16) {
            raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, w, h, 1, null);
        } else {
            // awt.266=The number of bits in a pixel is greater than 16
            throw new UnsupportedOperationException(Messages.getString("awt.266")); //$NON-NLS-1$
        }

        return raster;
    }

    @Override
    public boolean isCompatibleSampleModel(SampleModel sm) {
        if (sm == null) {
            return false;
        }

        if (!(sm instanceof MultiPixelPackedSampleModel) && !(sm instanceof ComponentSampleModel)) {
            return false;
        }

        if (sm.getTransferType() != transferType) {
            return false;
        }
        if (sm.getNumBands() != 1) {
            return false;
        }

        return true;
    }

    @Override
    public SampleModel createCompatibleSampleModel(int w, int h) {
        if (pixel_bits == 1 || pixel_bits == 2 || pixel_bits == 4) {
            return new MultiPixelPackedSampleModel(DataBuffer.TYPE_BYTE, w, h, pixel_bits);
        }
        int bandOffsets[] = new int[1];
        bandOffsets[0] = 0;
        return new ComponentSampleModel(transferType, w, h, 1, w, bandOffsets);

    }

    @Override
    public boolean isCompatibleRaster(Raster raster) {
        int sampleSize = raster.getSampleModel().getSampleSize(0);
        return (raster.getTransferType() == transferType && raster.getNumBands() == 1 && (1 << sampleSize) >= mapSize);
    }

    @Override
    public int getDataElement(int components[], int offset) {
        int rgb = (components[offset] << 16) | (components[offset + 1]) << 8
                | components[offset + 2];

        if (hasAlpha) {
            rgb |= components[offset + 3] << 24;
        } else {
            rgb |= 0xff000000;
        }

        int pixel;

        switch (transferType) {
            case DataBuffer.TYPE_BYTE:
                byte ba[] = (byte[])getDataElements(rgb, null);
                pixel = ba[0] & 0xff;
                break;
            case DataBuffer.TYPE_USHORT:
                short sa[] = (short[])getDataElements(rgb, null);
                pixel = sa[0] & 0xffff;
                break;
            default:
                // awt.267=The transferType is invalid
                throw new UnsupportedOperationException(Messages.getString("awt.267")); //$NON-NLS-1$
        }

        return pixel;
    }

    /**
     * Gets the color map.
     * 
     * @param rgb
     *            the destination array where the color map is written.
     */
    public final void getRGBs(int rgb[]) {
        System.arraycopy(colorMap, 0, rgb, 0, mapSize);
    }

    /**
     * Gets the red component of the color map.
     * 
     * @param r
     *            the destination array.
     */
    public final void getReds(byte r[]) {
        for (int i = 0; i < mapSize; i++) {
            r[i] = (byte)(colorMap[i] >> 16);
        }
    }

    /**
     * Gets the green component of the color map.
     * 
     * @param g
     *            the destination array.
     */
    public final void getGreens(byte g[]) {
        for (int i = 0; i < mapSize; i++) {
            g[i] = (byte)(colorMap[i] >> 8);
        }
    }

    /**
     * Gets the blue component of the color map.
     * 
     * @param b
     *            the destination array.
     */
    public final void getBlues(byte b[]) {
        for (int i = 0; i < mapSize; i++) {
            b[i] = (byte)colorMap[i];
        }
    }

    /**
     * Gets the alpha component of the color map.
     * 
     * @param a
     *            the destination array.
     */
    public final void getAlphas(byte a[]) {
        for (int i = 0; i < mapSize; i++) {
            a[i] = (byte)(colorMap[i] >> 24);
        }
    }

    @Override
    public int[] getComponents(int pixel, int components[], int offset) {
        if (components == null) {
            components = new int[offset + numComponents];
        }

        components[offset + 0] = getRed(pixel);
        components[offset + 1] = getGreen(pixel);
        components[offset + 2] = getBlue(pixel);
        if (hasAlpha && (components.length - offset) > 3) {
            components[offset + 3] = getAlpha(pixel);
        }

        return components;
    }

    /**
     * Checks if the specified pixel is valid for this color model.
     * 
     * @param pixel
     *            the pixel.
     * @return true, if the pixel is valid.
     */
    public boolean isValid(int pixel) {
        if (validBits == null) {
            return (pixel >= 0 && pixel < mapSize);
        }
        return (pixel < mapSize && validBits.testBit(pixel));
    }

    @Override
    public final int getRed(int pixel) {
        return (colorMap[pixel] >> 16) & 0xff;
    }

    @Override
    public final int getRGB(int pixel) {
        return colorMap[pixel];
    }

    @Override
    public final int getGreen(int pixel) {
        return (colorMap[pixel] >> 8) & 0xff;
    }

    @Override
    public final int getBlue(int pixel) {
        return colorMap[pixel] & 0xff;
    }

    @Override
    public final int getAlpha(int pixel) {
        return (colorMap[pixel] >> 24) & 0xff;
    }

    @Override
    public int[] getComponentSize() {
        return bits.clone();
    }

    /**
     * Checks if this color model validates pixels.
     * 
     * @return true, if all pixels are valid, otherwise false.
     */
    public boolean isValid() {
        return (validBits == null);
    }

    @Override
    public void finalize() {
        // TODO: implement
        return;
    }

    /**
     * Gets the index that represents the transparent pixel.
     * 
     * @return the index that represents the transparent pixel.
     */
    public final int getTransparentPixel() {
        return transparentIndex;
    }

    @Override
    public int getTransparency() {
        return transparency;
    }

    /**
     * Gets the size of the color map.
     * 
     * @return the map size.
     */
    public final int getMapSize() {
        return mapSize;
    }

    /**
     * Creates the color map.
     * 
     * @param size
     *            the size.
     * @param r
     *            the r.
     * @param g
     *            the g.
     * @param b
     *            the b.
     * @param a
     *            the a.
     * @param trans
     *            the trans.
     */
    private void createColorMap(int size, byte r[], byte g[], byte b[], byte a[], int trans) {
        if (size < 1) {
            // awt.264=Size of the color map is less than 1
            throw new IllegalArgumentException(Messages.getString("awt.264")); //$NON-NLS-1$
        }

        mapSize = size;
        colorMap = new int[mapSize];
        if (trans >= 0 && trans < mapSize) {
            transparency = Transparency.BITMASK;
            transparentIndex = trans;
        } else {
            transparency = Transparency.OPAQUE;
            transparentIndex = -1;
        }
        int alpha = 0;

        for (int i = 0; i < mapSize; i++) {
            colorMap[i] = ((r[i] & 0xff) << 16) | ((g[i] & 0xff) << 8) | (b[i] & 0xff);

            if (trans == i) {
                continue;
            }

            if (a == null) {
                colorMap[i] |= 0xff000000;
            } else {
                alpha = a[i] & 0xff;
                if (alpha == 0xff) {
                    colorMap[i] |= 0xff000000;
                } else if (alpha == 0) {
                    if (transparency == Transparency.OPAQUE) {
                        transparency = Transparency.BITMASK;
                    }
                    if (transparentIndex < 0) {
                        transparentIndex = i;
                    }
                } else {
                    colorMap[i] |= (a[i] & 0xff) << 24;
                    if (transparency != Transparency.TRANSLUCENT) {
                        transparency = Transparency.TRANSLUCENT;
                    }
                }
            }

        }

    }

    /**
     * This method checking, if Color Map has Gray palette.
     */
    private void checkPalette() {
        grayPalette = false;
        if (transparency > Transparency.OPAQUE) {
            return;
        }
        int rgb = 0;

        for (int i = 0; i < mapSize; i++) {
            rgb = colorMap[i];
            if (((rgb >> 16) & 0xff) != ((rgb >> 8) & 0xff) || ((rgb >> 8) & 0xff) != (rgb & 0xff)) {
                return;
            }
        }
        grayPalette = true;
    }

    /**
     * Construction an array pixel representation.
     * 
     * @param colorMapIdx
     *            the index into Color Map.
     * @param pixel
     *            the pixel
     * @return the pixel representation array.
     */
    private Object createDataObject(int colorMapIdx, Object pixel) {
        if (pixel == null) {
            switch (transferType) {
                case DataBuffer.TYPE_BYTE:
                    byte[] ba = new byte[1];
                    ba[0] = (byte)colorMapIdx;
                    pixel = ba;
                    break;
                case DataBuffer.TYPE_USHORT:
                    short[] sa = new short[1];
                    sa[0] = (short)colorMapIdx;
                    pixel = sa;
                    break;
                default:
                    // awt.267=The transferType is invalid
                    throw new UnsupportedOperationException(Messages.getString("awt.267")); //$NON-NLS-1$
            }
        } else if (pixel instanceof byte[] && transferType == DataBuffer.TYPE_BYTE) {
            byte ba[] = (byte[])pixel;
            ba[0] = (byte)colorMapIdx;
            pixel = ba;
        } else if (pixel instanceof short[] && transferType == DataBuffer.TYPE_USHORT) {
            short[] sa = (short[])pixel;
            sa[0] = (short)colorMapIdx;
            pixel = sa;
        } else if (pixel instanceof int[]) {
            int ia[] = (int[])pixel;
            ia[0] = colorMapIdx;
            pixel = ia;
        } else {
            // awt.268=The pixel is not a primitive array of type transferType
            throw new ClassCastException(Messages.getString("awt.268")); //$NON-NLS-1$
        }
        return pixel;
    }

    /**
     * Creates the bits.
     * 
     * @param hasAlpha
     *            the has alpha.
     * @return the int[].
     */
    private static int[] createBits(boolean hasAlpha) {

        int numChannels;
        if (hasAlpha) {
            numChannels = 4;
        } else {
            numChannels = 3;
        }

        int bits[] = new int[numChannels];
        for (int i = 0; i < numChannels; i++) {
            bits[i] = 8;
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
        if (transferType != DataBuffer.TYPE_BYTE && transferType != DataBuffer.TYPE_USHORT) {
            // awt.269=The transferType is not one of DataBuffer.TYPE_BYTE or
            // DataBuffer.TYPE_USHORT
            throw new IllegalArgumentException(Messages.getString("awt.269")); //$NON-NLS-1$
        }
        return transferType;
    }

    /**
     * Checks if is gray palette.
     * 
     * @return true, if is gray palette.
     */
    boolean isGrayPallete() {
        return grayPalette;
    }

}
