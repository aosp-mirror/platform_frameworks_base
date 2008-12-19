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

package java.awt;

import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.Serializable;
import java.util.Arrays;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Color class defines colors in the default sRGB color space or in the
 * specified ColorSpace. Every Color contains alpha value. The alpha value
 * defines the transparency of a color and can be represented by a float value
 * in the range 0.0 - 1.0 or 0 - 255.
 * 
 * @since Android 1.0
 */
public class Color implements Paint, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 118526816881161077L;

    /*
     * The values of the following colors are based on 1.5 release behavior
     * which can be revealed using the following or similar code: Color c =
     * Color.white; System.out.println(c);
     */

    /**
     * The color white.
     */
    public static final Color white = new Color(255, 255, 255);

    /**
     * The color white.
     */
    public static final Color WHITE = white;

    /**
     * The color light gray.
     */
    public static final Color lightGray = new Color(192, 192, 192);

    /**
     * The color light gray.
     */
    public static final Color LIGHT_GRAY = lightGray;

    /**
     * The color gray.
     */
    public static final Color gray = new Color(128, 128, 128);

    /**
     * The color gray.
     */
    public static final Color GRAY = gray;

    /**
     * The color dark gray.
     */
    public static final Color darkGray = new Color(64, 64, 64);

    /**
     * The color dark gray.
     */
    public static final Color DARK_GRAY = darkGray;

    /**
     * The color black.
     */
    public static final Color black = new Color(0, 0, 0);

    /**
     * The color black.
     */
    public static final Color BLACK = black;

    /**
     * The color red.
     */
    public static final Color red = new Color(255, 0, 0);

    /**
     * The color red.
     */
    public static final Color RED = red;

    /**
     * The color pink.
     */
    public static final Color pink = new Color(255, 175, 175);

    /**
     * The color pink.
     */
    public static final Color PINK = pink;

    /**
     * The color orange.
     */
    public static final Color orange = new Color(255, 200, 0);

    /**
     * The color orange.
     */
    public static final Color ORANGE = orange;

    /**
     * The color yellow.
     */
    public static final Color yellow = new Color(255, 255, 0);

    /**
     * The color yellow.
     */
    public static final Color YELLOW = yellow;

    /**
     * The color green.
     */
    public static final Color green = new Color(0, 255, 0);

    /**
     * The color green.
     */
    public static final Color GREEN = green;

    /**
     * The color magenta.
     */
    public static final Color magenta = new Color(255, 0, 255);

    /**
     * The color magenta.
     */
    public static final Color MAGENTA = magenta;

    /**
     * The color cyan.
     */
    public static final Color cyan = new Color(0, 255, 255);

    /**
     * The color cyan.
     */
    public static final Color CYAN = cyan;

    /**
     * The color blue.
     */
    public static final Color blue = new Color(0, 0, 255);

    /**
     * The color blue.
     */
    public static final Color BLUE = blue;

    /**
     * integer RGB value.
     */
    int value;

    /**
     * Float sRGB value.
     */
    private float[] frgbvalue;

    /**
     * Color in an arbitrary color space with <code>float</code> components. If
     * null, other value should be used.
     */
    private float fvalue[];

    /**
     * Float alpha value. If frgbvalue is null, this is not valid data.
     */
    private float falpha;

    /**
     * The color's color space if applicable.
     */
    private ColorSpace cs;

    /*
     * The value of the SCALE_FACTOR is based on 1.5 release behavior which can
     * be revealed using the following code: Color c = new Color(100, 100, 100);
     * Color bc = c.brighter(); System.out.println("Brighter factor: " +
     * ((float)c.getRed())/((float)bc.getRed())); Color dc = c.darker();
     * System.out.println("Darker factor: " +
     * ((float)dc.getRed())/((float)c.getRed())); The result is the same for
     * brighter and darker methods, so we need only one scale factor for both.
     */
    /**
     * The Constant SCALE_FACTOR.
     */
    private static final double SCALE_FACTOR = 0.7;

    /**
     * The Constant MIN_SCALABLE.
     */
    private static final int MIN_SCALABLE = 3; // should increase when

    // multiplied by SCALE_FACTOR

    /**
     * The current paint context.
     */
    transient private PaintContext currentPaintContext;

    /**
     * Creates a color in the specified ColorSpace, the specified color
     * components and the specified alpha.
     * 
     * @param cspace
     *            the ColorSpace to be used to define the components.
     * @param components
     *            the components.
     * @param alpha
     *            the alpha.
     */
    public Color(ColorSpace cspace, float[] components, float alpha) {
        int nComps = cspace.getNumComponents();
        float comp;
        fvalue = new float[nComps];

        for (int i = 0; i < nComps; i++) {
            comp = components[i];
            if (comp < 0.0f || comp > 1.0f) {
                // awt.107=Color parameter outside of expected range: component
                // {0}.
                throw new IllegalArgumentException(Messages.getString("awt.107", i)); //$NON-NLS-1$
            }
            fvalue[i] = components[i];
        }

        if (alpha < 0.0f || alpha > 1.0f) {
            // awt.108=Alpha value outside of expected range.
            throw new IllegalArgumentException(Messages.getString("awt.108")); //$NON-NLS-1$
        }
        falpha = alpha;

        cs = cspace;

        frgbvalue = cs.toRGB(fvalue);

        value = ((int)(frgbvalue[2] * 255 + 0.5)) | (((int)(frgbvalue[1] * 255 + 0.5)) << 8)
                | (((int)(frgbvalue[0] * 255 + 0.5)) << 16) | (((int)(falpha * 255 + 0.5)) << 24);
    }

    /**
     * Instantiates a new sRGB color with the specified combined RGBA value
     * consisting of the alpha component in bits 24-31, the red component in
     * bits 16-23, the green component in bits 8-15, and the blue component in
     * bits 0-7. If the hasalpha argument is false, the alpha has default value
     * - 255.
     * 
     * @param rgba
     *            the RGBA components.
     * @param hasAlpha
     *            the alpha parameter is true if alpha bits are valid, false
     *            otherwise.
     */
    public Color(int rgba, boolean hasAlpha) {
        if (!hasAlpha) {
            value = rgba | 0xFF000000;
        } else {
            value = rgba;
        }
    }

    /**
     * Instantiates a new color with the specified red, green, blue and alpha
     * components.
     * 
     * @param r
     *            the red component.
     * @param g
     *            the green component.
     * @param b
     *            the blue component.
     * @param a
     *            the alpha component.
     */
    public Color(int r, int g, int b, int a) {
        if ((r & 0xFF) != r || (g & 0xFF) != g || (b & 0xFF) != b || (a & 0xFF) != a) {
            // awt.109=Color parameter outside of expected range.
            throw new IllegalArgumentException(Messages.getString("awt.109")); //$NON-NLS-1$
        }
        value = b | (g << 8) | (r << 16) | (a << 24);
    }

    /**
     * Instantiates a new opaque sRGB color with the specified red, green, and
     * blue values. The Alpha component is set to the default - 1.0.
     * 
     * @param r
     *            the red component.
     * @param g
     *            the green component.
     * @param b
     *            the blue component.
     */
    public Color(int r, int g, int b) {
        if ((r & 0xFF) != r || (g & 0xFF) != g || (b & 0xFF) != b) {
            // awt.109=Color parameter outside of expected range.
            throw new IllegalArgumentException(Messages.getString("awt.109")); //$NON-NLS-1$
        }
        // 0xFF for alpha channel
        value = b | (g << 8) | (r << 16) | 0xFF000000;
    }

    /**
     * Instantiates a new sRGB color with the specified RGB value consisting of
     * the red component in bits 16-23, the green component in bits 8-15, and
     * the blue component in bits 0-7. Alpha has default value - 255.
     * 
     * @param rgb
     *            the RGB components.
     */
    public Color(int rgb) {
        value = rgb | 0xFF000000;
    }

    /**
     * Instantiates a new color with the specified red, green, blue and alpha
     * components.
     * 
     * @param r
     *            the red component.
     * @param g
     *            the green component.
     * @param b
     *            the blue component.
     * @param a
     *            the alpha component.
     */
    public Color(float r, float g, float b, float a) {
        this((int)(r * 255 + 0.5), (int)(g * 255 + 0.5), (int)(b * 255 + 0.5), (int)(a * 255 + 0.5));
        falpha = a;
        fvalue = new float[3];
        fvalue[0] = r;
        fvalue[1] = g;
        fvalue[2] = b;
        frgbvalue = fvalue;
    }

    /**
     * Instantiates a new color with the specified red, green, and blue
     * components and default alpha value - 1.0.
     * 
     * @param r
     *            the red component.
     * @param g
     *            the green component.
     * @param b
     *            the blue component.
     */
    public Color(float r, float g, float b) {
        this(r, g, b, 1.0f);
    }

    public PaintContext createContext(ColorModel cm, Rectangle r, Rectangle2D r2d,
            AffineTransform xform, RenderingHints rhs) {
        if (currentPaintContext != null) {
            return currentPaintContext;
        }
        currentPaintContext = new Color.ColorPaintContext(value);
        return currentPaintContext;
    }

    /**
     * Returns a string representation of the Color object.
     * 
     * @return the string representation of the Color object.
     */
    @Override
    public String toString() {
        /*
         * The format of the string is based on 1.5 release behavior which can
         * be revealed using the following code: Color c = new Color(1, 2, 3);
         * System.out.println(c);
         */

        return getClass().getName() + "[r=" + getRed() + //$NON-NLS-1$
                ",g=" + getGreen() + //$NON-NLS-1$
                ",b=" + getBlue() + //$NON-NLS-1$
                "]"; //$NON-NLS-1$
    }

    /**
     * Compares the specified Object to the Color.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified Object is a Color whose value is equal to
     *         this Color, false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Color) {
            return ((Color)obj).value == this.value;
        }
        return false;
    }

    /**
     * Returns a float array containing the color and alpha components of the
     * Color in the specified ColorSpace.
     * 
     * @param colorSpace
     *            the specified ColorSpace.
     * @param components
     *            the results of this method will be written to this float
     *            array. If null, a float array will be created.
     * @return the color and alpha components in a float array.
     */
    public float[] getComponents(ColorSpace colorSpace, float[] components) {
        int nComps = colorSpace.getNumComponents();
        if (components == null) {
            components = new float[nComps + 1];
        }

        getColorComponents(colorSpace, components);

        if (frgbvalue != null) {
            components[nComps] = falpha;
        } else {
            components[nComps] = getAlpha() / 255f;
        }

        return components;
    }

    /**
     * Returns a float array containing the color components of the Color in the
     * specified ColorSpace.
     * 
     * @param colorSpace
     *            the specified ColorSpace.
     * @param components
     *            the results of this method will be written to this float
     *            array. If null, a float array will be created.
     * @return the color components in a float array.
     */
    public float[] getColorComponents(ColorSpace colorSpace, float[] components) {
        float[] cieXYZComponents = getColorSpace().toCIEXYZ(getColorComponents(null));
        float[] csComponents = colorSpace.fromCIEXYZ(cieXYZComponents);

        if (components == null) {
            return csComponents;
        }

        for (int i = 0; i < csComponents.length; i++) {
            components[i] = csComponents[i];
        }

        return components;
    }

    /**
     * Gets the ColorSpace of this Color.
     * 
     * @return the ColorSpace object.
     */
    public ColorSpace getColorSpace() {
        if (cs == null) {
            cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        }

        return cs;
    }

    /**
     * Creates a new Color which is a darker than this Color according to a
     * fixed scale factor.
     * 
     * @return the darker Color.
     */
    public Color darker() {
        return new Color((int)(getRed() * SCALE_FACTOR), (int)(getGreen() * SCALE_FACTOR),
                (int)(getBlue() * SCALE_FACTOR));
    }

    /**
     * Creates a new Color which is a brighter than this Color.
     * 
     * @return the brighter Color.
     */
    public Color brighter() {

        int r = getRed();
        int b = getBlue();
        int g = getGreen();

        if (r == 0 && b == 0 && g == 0) {
            return new Color(MIN_SCALABLE, MIN_SCALABLE, MIN_SCALABLE);
        }

        if (r < MIN_SCALABLE && r != 0) {
            r = MIN_SCALABLE;
        } else {
            r = (int)(r / SCALE_FACTOR);
            r = (r > 255) ? 255 : r;
        }

        if (b < MIN_SCALABLE && b != 0) {
            b = MIN_SCALABLE;
        } else {
            b = (int)(b / SCALE_FACTOR);
            b = (b > 255) ? 255 : b;
        }

        if (g < MIN_SCALABLE && g != 0) {
            g = MIN_SCALABLE;
        } else {
            g = (int)(g / SCALE_FACTOR);
            g = (g > 255) ? 255 : g;
        }

        return new Color(r, g, b);
    }

    /**
     * Returns a float array containing the color and alpha components of the
     * Color in the default sRGB color space.
     * 
     * @param components
     *            the results of this method will be written to this float
     *            array. A new float array will be created if this argument is
     *            null.
     * @return the RGB color and alpha components in a float array.
     */
    public float[] getRGBComponents(float[] components) {
        if (components == null) {
            components = new float[4];
        }

        if (frgbvalue != null) {
            components[3] = falpha;
        } else {
            components[3] = getAlpha() / 255f;
        }

        getRGBColorComponents(components);

        return components;
    }

    /**
     * Returns a float array containing the color components of the Color in the
     * default sRGB color space.
     * 
     * @param components
     *            the results of this method will be written to this float
     *            array. A new float array will be created if this argument is
     *            null.
     * @return the RGB color components in a float array.
     */
    public float[] getRGBColorComponents(float[] components) {
        if (components == null) {
            components = new float[3];
        }

        if (frgbvalue != null) {
            components[2] = frgbvalue[2];
            components[1] = frgbvalue[1];
            components[0] = frgbvalue[0];
        } else {
            components[2] = getBlue() / 255f;
            components[1] = getGreen() / 255f;
            components[0] = getRed() / 255f;
        }

        return components;
    }

    /**
     * Returns a float array which contains the color and alpha components of
     * the Color in the ColorSpace of the Color.
     * 
     * @param components
     *            the results of this method will be written to this float
     *            array. A new float array will be created if this argument is
     *            null.
     * @return the color and alpha components in a float array.
     */
    public float[] getComponents(float[] components) {
        if (fvalue == null) {
            return getRGBComponents(components);
        }

        int nColorComps = fvalue.length;

        if (components == null) {
            components = new float[nColorComps + 1];
        }

        getColorComponents(components);

        components[nColorComps] = falpha;

        return components;
    }

    /**
     * Returns a float array which contains the color components of the Color in
     * the ColorSpace of the Color.
     * 
     * @param components
     *            the results of this method will be written to this float
     *            array. A new float array will be created if this argument is
     *            null.
     * @return the color components in a float array.
     */
    public float[] getColorComponents(float[] components) {
        if (fvalue == null) {
            return getRGBColorComponents(components);
        }

        if (components == null) {
            components = new float[fvalue.length];
        }

        for (int i = 0; i < fvalue.length; i++) {
            components[i] = fvalue[i];
        }

        return components;
    }

    /**
     * Returns a hash code of this Color object.
     * 
     * @return a hash code of this Color object.
     */
    @Override
    public int hashCode() {
        return value;
    }

    public int getTransparency() {
        switch (getAlpha()) {
            case 0xff:
                return Transparency.OPAQUE;
            case 0:
                return Transparency.BITMASK;
            default:
                return Transparency.TRANSLUCENT;
        }
    }

    /**
     * Gets the red component of the Color in the range 0-255.
     * 
     * @return the red component of the Color.
     */
    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    /**
     * Gets the RGB value that represents the color in the default sRGB
     * ColorModel.
     * 
     * @return the RGB color value in the default sRGB ColorModel.
     */
    public int getRGB() {
        return value;
    }

    /**
     * Gets the green component of the Color in the range 0-255.
     * 
     * @return the green component of the Color.
     */
    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    /**
     * Gets the blue component of the Color in the range 0-255.
     * 
     * @return the blue component of the Color.
     */
    public int getBlue() {
        return value & 0xFF;
    }

    /**
     * Gets the alpha component of the Color in the range 0-255.
     * 
     * @return the alpha component of the Color.
     */
    public int getAlpha() {
        return (value >> 24) & 0xFF;
    }

    /**
     * Gets the Color from the specified string, or returns the Color specified
     * by the second parameter.
     * 
     * @param nm
     *            the specified string.
     * @param def
     *            the default Color.
     * @return the color from the specified string, or the Color specified by
     *         the second parameter.
     */
    public static Color getColor(String nm, Color def) {
        Integer integer = Integer.getInteger(nm);

        if (integer == null) {
            return def;
        }

        return new Color(integer.intValue());
    }

    /**
     * Gets the Color from the specified string, or returns the Color converted
     * from the second parameter.
     * 
     * @param nm
     *            the specified string.
     * @param def
     *            the default Color.
     * @return the color from the specified string, or the Color converted from
     *         the second parameter.
     */
    public static Color getColor(String nm, int def) {
        Integer integer = Integer.getInteger(nm);

        if (integer == null) {
            return new Color(def);
        }

        return new Color(integer.intValue());
    }

    /**
     * Gets the Color from the specified String.
     * 
     * @param nm
     *            the specified string.
     * @return the Color object, or null.
     */
    public static Color getColor(String nm) {
        Integer integer = Integer.getInteger(nm);

        if (integer == null) {
            return null;
        }

        return new Color(integer.intValue());
    }

    /**
     * Decodes a String to an integer and returns the specified opaque Color.
     * 
     * @param nm
     *            the String which represents an opaque color as a 24-bit
     *            integer.
     * @return the Color object from the given String.
     * @throws NumberFormatException
     *             if the specified string can not be converted to an integer.
     */
    public static Color decode(String nm) throws NumberFormatException {
        Integer integer = Integer.decode(nm);
        return new Color(integer.intValue());
    }

    /**
     * Gets a Color object using the specified values of the HSB color model.
     * 
     * @param h
     *            the hue component of the Color.
     * @param s
     *            the saturation of the Color.
     * @param b
     *            the brightness of the Color.
     * @return a color object with the specified hue, saturation and brightness
     *         values.
     */
    public static Color getHSBColor(float h, float s, float b) {
        return new Color(HSBtoRGB(h, s, b));
    }

    /**
     * Converts the Color specified by the RGB model to an equivalent color in
     * the HSB model.
     * 
     * @param r
     *            the red component.
     * @param g
     *            the green component.
     * @param b
     *            the blue component.
     * @param hsbvals
     *            the array of result hue, saturation, brightness values or
     *            null.
     * @return the float array of hue, saturation, brightness values.
     */
    public static float[] RGBtoHSB(int r, int g, int b, float[] hsbvals) {
        if (hsbvals == null) {
            hsbvals = new float[3];
        }

        int V = Math.max(b, Math.max(r, g));
        int temp = Math.min(b, Math.min(r, g));

        float H, S, B;

        B = V / 255.f;

        if (V == temp) {
            H = S = 0;
        } else {
            S = (V - temp) / ((float)V);

            float Cr = (V - r) / (float)(V - temp);
            float Cg = (V - g) / (float)(V - temp);
            float Cb = (V - b) / (float)(V - temp);

            if (r == V) {
                H = Cb - Cg;
            } else if (g == V) {
                H = 2 + Cr - Cb;
            } else {
                H = 4 + Cg - Cr;
            }

            H /= 6.f;
            if (H < 0) {
                H++;
            }
        }

        hsbvals[0] = H;
        hsbvals[1] = S;
        hsbvals[2] = B;

        return hsbvals;
    }

    /**
     * Converts the Color specified by the HSB model to an equivalent color in
     * the default RGB model.
     * 
     * @param hue
     *            the hue component of the Color.
     * @param saturation
     *            the saturation of the Color.
     * @param brightness
     *            the brightness of the Color.
     * @return the RGB value of the color with the specified hue, saturation and
     *         brightness.
     */
    public static int HSBtoRGB(float hue, float saturation, float brightness) {
        float fr, fg, fb;

        if (saturation == 0) {
            fr = fg = fb = brightness;
        } else {
            float H = (hue - (float)Math.floor(hue)) * 6;
            int I = (int)Math.floor(H);
            float F = H - I;
            float M = brightness * (1 - saturation);
            float N = brightness * (1 - saturation * F);
            float K = brightness * (1 - saturation * (1 - F));

            switch (I) {
                case 0:
                    fr = brightness;
                    fg = K;
                    fb = M;
                    break;
                case 1:
                    fr = N;
                    fg = brightness;
                    fb = M;
                    break;
                case 2:
                    fr = M;
                    fg = brightness;
                    fb = K;
                    break;
                case 3:
                    fr = M;
                    fg = N;
                    fb = brightness;
                    break;
                case 4:
                    fr = K;
                    fg = M;
                    fb = brightness;
                    break;
                case 5:
                    fr = brightness;
                    fg = M;
                    fb = N;
                    break;
                default:
                    fr = fb = fg = 0; // impossible, to supress compiler error
            }
        }

        int r = (int)(fr * 255. + 0.5);
        int g = (int)(fg * 255. + 0.5);
        int b = (int)(fb * 255. + 0.5);

        return (r << 16) | (g << 8) | b | 0xFF000000;
    }

    /**
     * The Class ColorPaintContext.
     */
    class ColorPaintContext implements PaintContext {

        /**
         * The RGB value.
         */
        int rgbValue;

        /**
         * The saved raster.
         */
        WritableRaster savedRaster = null;

        /**
         * Instantiates a new color paint context.
         * 
         * @param rgb
         *            the RGB value.
         */
        protected ColorPaintContext(int rgb) {
            rgbValue = rgb;
        }

        public void dispose() {
            savedRaster = null;
        }

        public ColorModel getColorModel() {
            return ColorModel.getRGBdefault();
        }

        public Raster getRaster(int x, int y, int w, int h) {
            if (savedRaster == null || w != savedRaster.getWidth() || h != savedRaster.getHeight()) {
                savedRaster = getColorModel().createCompatibleWritableRaster(w, h);

                // Suppose we have here simple INT/RGB color/sample model
                DataBufferInt intBuffer = (DataBufferInt)savedRaster.getDataBuffer();
                int rgbValues[] = intBuffer.getData();
                int rgbFillValue = rgbValue;
                Arrays.fill(rgbValues, rgbFillValue);
            }

            return savedRaster;
        }
    }
}
