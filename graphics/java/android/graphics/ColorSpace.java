/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import android.annotation.AnyThread;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.SuppressAutoDoc;

import libcore.util.NativeAllocationRegistry;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * {@usesMathJax}
 *
 * <p>A {@link ColorSpace} is used to identify a specific organization of colors.
 * Each color space is characterized by a {@link Model color model} that defines
 * how a color value is represented (for instance the {@link Model#RGB RGB} color
 * model defines a color value as a triplet of numbers).</p>
 *
 * <p>Each component of a color must fall within a valid range, specific to each
 * color space, defined by {@link #getMinValue(int)} and {@link #getMaxValue(int)}
 * This range is commonly \([0..1]\). While it is recommended to use values in the
 * valid range, a color space always clamps input and output values when performing
 * operations such as converting to a different color space.</p>
 *
 * <h3>Using color spaces</h3>
 *
 * <p>This implementation provides a pre-defined set of common color spaces
 * described in the {@link Named} enum. To obtain an instance of one of the
 * pre-defined color spaces, simply invoke {@link #get(Named)}:</p>
 *
 * <pre class="prettyprint">
 * ColorSpace sRgb = ColorSpace.get(ColorSpace.Named.SRGB);
 * </pre>
 *
 * <p>The {@link #get(Named)} method always returns the same instance for a given
 * name. Color spaces with an {@link Model#RGB RGB} color model can be safely
 * cast to {@link Rgb}. Doing so gives you access to more APIs to query various
 * properties of RGB color models: color gamut primaries, transfer functions,
 * conversions to and from linear space, etc. Please refer to {@link Rgb} for
 * more information.</p>
 *
 * <p>The documentation of {@link Named} provides a detailed description of the
 * various characteristics of each available color space.</p>
 *
 * <h3>Color space conversions</h3>

 * <p>To allow conversion between color spaces, this implementation uses the CIE
 * XYZ profile connection space (PCS). Color values can be converted to and from
 * this PCS using {@link #toXyz(float[])} and {@link #fromXyz(float[])}.</p>
 *
 * <p>For color space with a non-RGB color model, the white point of the PCS
 * <em>must be</em> the CIE standard illuminant D50. RGB color spaces use their
 * native white point (D65 for {@link Named#SRGB sRGB} for instance and must
 * undergo {@link Adaptation chromatic adaptation} as necessary.</p>
 *
 * <p>Since the white point of the PCS is not defined for RGB color space, it is
 * highly recommended to use the variants of the {@link #connect(ColorSpace, ColorSpace)}
 * method to perform conversions between color spaces. A color space can be
 * manually adapted to a specific white point using {@link #adapt(ColorSpace, float[])}.
 * Please refer to the documentation of {@link Rgb RGB color spaces} for more
 * information. Several common CIE standard illuminants are provided in this
 * class as reference (see {@link #ILLUMINANT_D65} or {@link #ILLUMINANT_D50}
 * for instance).</p>
 *
 * <p>Here is an example of how to convert from a color space to another:</p>
 *
 * <pre class="prettyprint">
 * // Convert from DCI-P3 to Rec.2020
 * ColorSpace.Connector connector = ColorSpace.connect(
 *         ColorSpace.get(ColorSpace.Named.DCI_P3),
 *         ColorSpace.get(ColorSpace.Named.BT2020));
 *
 * float[] bt2020 = connector.transform(p3r, p3g, p3b);
 * </pre>
 *
 * <p>You can easily convert to {@link Named#SRGB sRGB} by omitting the second
 * parameter:</p>
 *
 * <pre class="prettyprint">
 * // Convert from DCI-P3 to sRGB
 * ColorSpace.Connector connector = ColorSpace.connect(ColorSpace.get(ColorSpace.Named.DCI_P3));
 *
 * float[] sRGB = connector.transform(p3r, p3g, p3b);
 * </pre>
 *
 * <p>Conversions also work between color spaces with different color models:</p>
 *
 * <pre class="prettyprint">
 * // Convert from CIE L*a*b* (color model Lab) to Rec.709 (color model RGB)
 * ColorSpace.Connector connector = ColorSpace.connect(
 *         ColorSpace.get(ColorSpace.Named.CIE_LAB),
 *         ColorSpace.get(ColorSpace.Named.BT709));
 * </pre>
 *
 * <h3>Color spaces and multi-threading</h3>
 *
 * <p>Color spaces and other related classes ({@link Connector} for instance)
 * are immutable and stateless. They can be safely used from multiple concurrent
 * threads.</p>
 *
 * <p>Public static methods provided by this class, such as {@link #get(Named)}
 * and {@link #connect(ColorSpace, ColorSpace)}, are also guaranteed to be
 * thread-safe.</p>
 *
 * @see #get(Named)
 * @see Named
 * @see Model
 * @see Connector
 * @see Adaptation
 */
@AnyThread
@SuppressWarnings("StaticInitializerReferencesSubClass")
@SuppressAutoDoc
public abstract class ColorSpace {
    /**
     * Standard CIE 1931 2° illuminant A, encoded in xyY.
     * This illuminant has a color temperature of 2856K.
     */
    public static final float[] ILLUMINANT_A   = { 0.44757f, 0.40745f };
    /**
     * Standard CIE 1931 2° illuminant B, encoded in xyY.
     * This illuminant has a color temperature of 4874K.
     */
    public static final float[] ILLUMINANT_B   = { 0.34842f, 0.35161f };
    /**
     * Standard CIE 1931 2° illuminant C, encoded in xyY.
     * This illuminant has a color temperature of 6774K.
     */
    public static final float[] ILLUMINANT_C   = { 0.31006f, 0.31616f };
    /**
     * Standard CIE 1931 2° illuminant D50, encoded in xyY.
     * This illuminant has a color temperature of 5003K. This illuminant
     * is used by the profile connection space in ICC profiles.
     */
    public static final float[] ILLUMINANT_D50 = { 0.34567f, 0.35850f };
    /**
     * Standard CIE 1931 2° illuminant D55, encoded in xyY.
     * This illuminant has a color temperature of 5503K.
     */
    public static final float[] ILLUMINANT_D55 = { 0.33242f, 0.34743f };
    /**
     * Standard CIE 1931 2° illuminant D60, encoded in xyY.
     * This illuminant has a color temperature of 6004K.
     */
    public static final float[] ILLUMINANT_D60 = { 0.32168f, 0.33767f };
    /**
     * Standard CIE 1931 2° illuminant D65, encoded in xyY.
     * This illuminant has a color temperature of 6504K. This illuminant
     * is commonly used in RGB color spaces such as sRGB, BT.209, etc.
     */
    public static final float[] ILLUMINANT_D65 = { 0.31271f, 0.32902f };
    /**
     * Standard CIE 1931 2° illuminant D75, encoded in xyY.
     * This illuminant has a color temperature of 7504K.
     */
    public static final float[] ILLUMINANT_D75 = { 0.29902f, 0.31485f };
    /**
     * Standard CIE 1931 2° illuminant E, encoded in xyY.
     * This illuminant has a color temperature of 5454K.
     */
    public static final float[] ILLUMINANT_E   = { 0.33333f, 0.33333f };

    /**
     * The minimum ID value a color space can have.
     *
     * @see #getId()
     */
    public static final int MIN_ID = -1; // Do not change
    /**
     * The maximum ID value a color space can have.
     *
     * @see #getId()
     */
    public static final int MAX_ID = 63; // Do not change, used to encode in longs

    private static final float[] SRGB_PRIMARIES = { 0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f };
    private static final float[] NTSC_1953_PRIMARIES = { 0.67f, 0.33f, 0.21f, 0.71f, 0.14f, 0.08f };
    /**
     * A gray color space does not have meaningful primaries, so we use this arbitrary set.
     */
    private static final float[] GRAY_PRIMARIES = { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };

    private static final float[] ILLUMINANT_D50_XYZ = { 0.964212f, 1.0f, 0.825188f };

    private static final Rgb.TransferParameters SRGB_TRANSFER_PARAMETERS =
            new Rgb.TransferParameters(1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 2.4);

    // See static initialization block next to #get(Named)
    private static final ColorSpace[] sNamedColorSpaces = new ColorSpace[Named.values().length];

    @NonNull private final String mName;
    @NonNull private final Model mModel;
    @IntRange(from = MIN_ID, to = MAX_ID) private final int mId;

    /**
     * {@usesMathJax}
     *
     * <p>List of common, named color spaces. A corresponding instance of
     * {@link ColorSpace} can be obtained by calling {@link ColorSpace#get(Named)}:</p>
     *
     * <pre class="prettyprint">
     * ColorSpace cs = ColorSpace.get(ColorSpace.Named.DCI_P3);
     * </pre>
     *
     * <p>The properties of each color space are described below (see {@link #SRGB sRGB}
     * for instance). When applicable, the color gamut of each color space is compared
     * to the color gamut of sRGB using a CIE 1931 xy chromaticity diagram. This diagram
     * shows the location of the color space's primaries and white point.</p>
     *
     * @see ColorSpace#get(Named)
     */
    public enum Named {
        // NOTE: Do NOT change the order of the enum
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space sRGB standardized as IEC 61966-2.1:1999.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.640</td><td>0.300</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.330</td><td>0.600</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">sRGB IEC61966-2.1</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{sRGB} = \begin{cases} 12.92 \times C_{linear} & C_{linear} \lt 0.0031308 \\\
         *             1.055 \times C_{linear}^{\frac{1}{2.4}} - 0.055 & C_{linear} \ge 0.0031308 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{sRGB}}{12.92} & C_{sRGB} \lt 0.04045 \\\
         *             \left( \frac{C_{sRGB} + 0.055}{1.055} \right) ^{2.4} & C_{sRGB} \ge 0.04045 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_srgb.png" />
         *     <figcaption style="text-align: center;">sRGB</figcaption>
         * </p>
         */
        SRGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space sRGB standardized as IEC 61966-2.1:1999.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.640</td><td>0.300</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.330</td><td>0.600</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">sRGB IEC61966-2.1 (Linear)</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{sRGB} = C_{linear}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{sRGB}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_srgb.png" />
         *     <figcaption style="text-align: center;">sRGB</figcaption>
         * </p>
         */
        LINEAR_SRGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space scRGB-nl standardized as IEC 61966-2-2:2003.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.640</td><td>0.300</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.330</td><td>0.600</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">scRGB-nl IEC 61966-2-2:2003</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{scRGB} = \begin{cases} sign(C_{linear}) 12.92 \times \left| C_{linear} \right| &
         *                      \left| C_{linear} \right| \lt 0.0031308 \\\
         *             sign(C_{linear}) 1.055 \times \left| C_{linear} \right| ^{\frac{1}{2.4}} - 0.055 &
         *                      \left| C_{linear} \right| \ge 0.0031308 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}sign(C_{scRGB}) \frac{\left| C_{scRGB} \right|}{12.92} &
         *                  \left| C_{scRGB} \right| \lt 0.04045 \\\
         *             sign(C_{scRGB}) \left( \frac{\left| C_{scRGB} \right| + 0.055}{1.055} \right) ^{2.4} &
         *                  \left| C_{scRGB} \right| \ge 0.04045 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([-0.799..2.399[\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_scrgb.png" />
         *     <figcaption style="text-align: center;">Extended sRGB (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        EXTENDED_SRGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space scRGB standardized as IEC 61966-2-2:2003.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.640</td><td>0.300</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.330</td><td>0.600</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">scRGB IEC 61966-2-2:2003</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{scRGB} = C_{linear}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{scRGB}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([-0.5..7.499[\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_scrgb.png" />
         *     <figcaption style="text-align: center;">Extended sRGB (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        LINEAR_EXTENDED_SRGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space BT.709 standardized as Rec. ITU-R BT.709-5.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.640</td><td>0.300</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.330</td><td>0.600</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Rec. ITU-R BT.709-5</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{BT709} = \begin{cases} 4.5 \times C_{linear} & C_{linear} \lt 0.018 \\\
         *             1.099 \times C_{linear}^{\frac{1}{2.2}} - 0.099 & C_{linear} \ge 0.018 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{BT709}}{4.5} & C_{BT709} \lt 0.081 \\\
         *             \left( \frac{C_{BT709} + 0.099}{1.099} \right) ^{2.2} & C_{BT709} \ge 0.081 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_bt709.png" />
         *     <figcaption style="text-align: center;">BT.709</figcaption>
         * </p>
         */
        BT709,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space BT.2020 standardized as Rec. ITU-R BT.2020-1.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.708</td><td>0.170</td><td>0.131</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.292</td><td>0.797</td><td>0.046</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Rec. ITU-R BT.2020-1</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{BT2020} = \begin{cases} 4.5 \times C_{linear} & C_{linear} \lt 0.0181 \\\
         *             1.0993 \times C_{linear}^{\frac{1}{2.2}} - 0.0993 & C_{linear} \ge 0.0181 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{BT2020}}{4.5} & C_{BT2020} \lt 0.08145 \\\
         *             \left( \frac{C_{BT2020} + 0.0993}{1.0993} \right) ^{2.2} & C_{BT2020} \ge 0.08145 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_bt2020.png" />
         *     <figcaption style="text-align: center;">BT.2020 (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        BT2020,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space DCI-P3 standardized as SMPTE RP 431-2-2007.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.680</td><td>0.265</td><td>0.150</td><td>0.314</td></tr>
         *     <tr><td>y</td><td>0.320</td><td>0.690</td><td>0.060</td><td>0.351</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">SMPTE RP 431-2-2007 DCI (P3)</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">N/A</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{P3} = C_{linear}^{\frac{1}{2.6}}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{P3}^{2.6}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_dci_p3.png" />
         *     <figcaption style="text-align: center;">DCI-P3 (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        DCI_P3,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space Display P3 based on SMPTE RP 431-2-2007 and IEC 61966-2.1:1999.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.680</td><td>0.265</td><td>0.150</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.320</td><td>0.690</td><td>0.060</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Display P3</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{DisplayP3} = \begin{cases} 12.92 \times C_{linear} & C_{linear} \lt 0.0030186 \\\
         *             1.055 \times C_{linear}^{\frac{1}{2.4}} - 0.055 & C_{linear} \ge 0.0030186 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{DisplayP3}}{12.92} & C_{sRGB} \lt 0.04045 \\\
         *             \left( \frac{C_{DisplayP3} + 0.055}{1.055} \right) ^{2.4} & C_{sRGB} \ge 0.04045 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_display_p3.png" />
         *     <figcaption style="text-align: center;">Display P3 (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        DISPLAY_P3,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space NTSC, 1953 standard.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.67</td><td>0.21</td><td>0.14</td><td>0.310</td></tr>
         *     <tr><td>y</td><td>0.33</td><td>0.71</td><td>0.08</td><td>0.316</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">NTSC (1953)</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">C</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{BT709} = \begin{cases} 4.5 \times C_{linear} & C_{linear} \lt 0.018 \\\
         *             1.099 \times C_{linear}^{\frac{1}{2.2}} - 0.099 & C_{linear} \ge 0.018 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{BT709}}{4.5} & C_{BT709} \lt 0.081 \\\
         *             \left( \frac{C_{BT709} + 0.099}{1.099} \right) ^{2.2} & C_{BT709} \ge 0.081 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_ntsc_1953.png" />
         *     <figcaption style="text-align: center;">NTSC 1953 (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        NTSC_1953,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space SMPTE C.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.630</td><td>0.310</td><td>0.155</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.340</td><td>0.595</td><td>0.070</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">SMPTE-C RGB</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{BT709} = \begin{cases} 4.5 \times C_{linear} & C_{linear} \lt 0.018 \\\
         *             1.099 \times C_{linear}^{\frac{1}{2.2}} - 0.099 & C_{linear} \ge 0.018 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{BT709}}{4.5} & C_{BT709} \lt 0.081 \\\
         *             \left( \frac{C_{BT709} + 0.099}{1.099} \right) ^{2.2} & C_{BT709} \ge 0.081 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_smpte_c.png" />
         *     <figcaption style="text-align: center;">SMPTE-C (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        SMPTE_C,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space Adobe RGB (1998).</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.64</td><td>0.21</td><td>0.15</td><td>0.3127</td></tr>
         *     <tr><td>y</td><td>0.33</td><td>0.71</td><td>0.06</td><td>0.3290</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Adobe RGB (1998)</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D65</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{RGB} = C_{linear}^{\frac{1}{2.2}}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{RGB}^{2.2}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_adobe_rgb.png" />
         *     <figcaption style="text-align: center;">Adobe RGB (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        ADOBE_RGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space ProPhoto RGB standardized as ROMM RGB ISO 22028-2:2013.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.7347</td><td>0.1596</td><td>0.0366</td><td>0.3457</td></tr>
         *     <tr><td>y</td><td>0.2653</td><td>0.8404</td><td>0.0001</td><td>0.3585</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">ROMM RGB ISO 22028-2:2013</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D50</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{ROMM} = \begin{cases} 16 \times C_{linear} & C_{linear} \lt 0.001953 \\\
         *             C_{linear}^{\frac{1}{1.8}} & C_{linear} \ge 0.001953 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(\begin{equation}
         *             C_{linear} = \begin{cases}\frac{C_{ROMM}}{16} & C_{ROMM} \lt 0.031248 \\\
         *             C_{ROMM}^{1.8} & C_{ROMM} \ge 0.031248 \end{cases}
         *             \end{equation}\)
         *         </td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([0..1]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_pro_photo_rgb.png" />
         *     <figcaption style="text-align: center;">ProPhoto RGB (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        PRO_PHOTO_RGB,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space ACES standardized as SMPTE ST 2065-1:2012.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.73470</td><td>0.00000</td><td>0.00010</td><td>0.32168</td></tr>
         *     <tr><td>y</td><td>0.26530</td><td>1.00000</td><td>-0.07700</td><td>0.33767</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">SMPTE ST 2065-1:2012 ACES</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D60</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{ACES} = C_{linear}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{ACES}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([-65504.0, 65504.0]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_aces.png" />
         *     <figcaption style="text-align: center;">ACES (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        ACES,
        /**
         * <p>{@link ColorSpace.Rgb RGB} color space ACEScg standardized as Academy S-2014-004.</p>
         * <table summary="Color space definition">
         *     <tr>
         *         <th>Chromaticity</th><th>Red</th><th>Green</th><th>Blue</th><th>White point</th>
         *     </tr>
         *     <tr><td>x</td><td>0.713</td><td>0.165</td><td>0.128</td><td>0.32168</td></tr>
         *     <tr><td>y</td><td>0.293</td><td>0.830</td><td>0.044</td><td>0.33767</td></tr>
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Academy S-2014-004 ACEScg</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D60</td></tr>
         *     <tr>
         *         <td>Opto-electronic transfer function (OETF)</td>
         *         <td colspan="4">\(C_{ACEScg} = C_{linear}\)</td>
         *     </tr>
         *     <tr>
         *         <td>Electro-optical transfer function (EOTF)</td>
         *         <td colspan="4">\(C_{linear} = C_{ACEScg}\)</td>
         *     </tr>
         *     <tr><td>Range</td><td colspan="4">\([-65504.0, 65504.0]\)</td></tr>
         * </table>
         * <p>
         *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_acescg.png" />
         *     <figcaption style="text-align: center;">ACEScg (orange) vs sRGB (white)</figcaption>
         * </p>
         */
        ACESCG,
        /**
         * <p>{@link Model#XYZ XYZ} color space CIE XYZ. This color space assumes standard
         * illuminant D50 as its white point.</p>
         * <table summary="Color space definition">
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Generic XYZ</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D50</td></tr>
         *     <tr><td>Range</td><td colspan="4">\([-2.0, 2.0]\)</td></tr>
         * </table>
         */
        CIE_XYZ,
        /**
         * <p>{@link Model#LAB Lab} color space CIE L*a*b*. This color space uses CIE XYZ D50
         * as a profile conversion space.</p>
         * <table summary="Color space definition">
         *     <tr><th>Property</th><th colspan="4">Value</th></tr>
         *     <tr><td>Name</td><td colspan="4">Generic L*a*b*</td></tr>
         *     <tr><td>CIE standard illuminant</td><td colspan="4">D50</td></tr>
         *     <tr><td>Range</td><td colspan="4">\(L: [0.0, 100.0], a: [-128, 128], b: [-128, 128]\)</td></tr>
         * </table>
         */
        CIE_LAB
        // Update the initialization block next to #get(Named) when adding new values
    }

    /**
     * <p>A render intent determines how a {@link ColorSpace.Connector connector}
     * maps colors from one color space to another. The choice of mapping is
     * important when the source color space has a larger color gamut than the
     * destination color space.</p>
     *
     * @see ColorSpace#connect(ColorSpace, ColorSpace, RenderIntent)
     */
    public enum RenderIntent {
        /**
         * <p>Compresses the source gamut into the destination gamut.
         * This render intent affects all colors, inside and outside
         * of destination gamut. The goal of this render intent is
         * to preserve the visual relationship between colors.</p>
         *
         * <p class="note">This render intent is currently not
         * implemented and behaves like {@link #RELATIVE}.</p>
         */
        PERCEPTUAL,
        /**
         * Similar to the {@link #ABSOLUTE} render intent, this render
         * intent matches the closest color in the destination gamut
         * but makes adjustments for the destination white point.
         */
        RELATIVE,
        /**
         * <p>Attempts to maintain the relative saturation of colors
         * from the source gamut to the destination gamut, to keep
         * highly saturated colors as saturated as possible.</p>
         *
         * <p class="note">This render intent is currently not
         * implemented and behaves like {@link #RELATIVE}.</p>
         */
        SATURATION,
        /**
         * Colors that are in the destination gamut are left unchanged.
         * Colors that fall outside of the destination gamut are mapped
         * to the closest possible color within the gamut of the destination
         * color space (they are clipped).
         */
        ABSOLUTE
    }

    /**
     * {@usesMathJax}
     *
     * <p>List of adaptation matrices that can be used for chromatic adaptation
     * using the von Kries transform. These matrices are used to convert values
     * in the CIE XYZ space to values in the LMS space (Long Medium Short).</p>
     *
     * <p>Given an adaptation matrix \(A\), the conversion from XYZ to
     * LMS is straightforward:</p>
     *
     * $$\left[ \begin{array}{c} L\\ M\\ S \end{array} \right] =
     * A \left[ \begin{array}{c} X\\ Y\\ Z \end{array} \right]$$
     *
     * <p>The complete von Kries transform \(T\) uses a diagonal matrix
     * noted \(D\) to perform the adaptation in LMS space. In addition
     * to \(A\) and \(D\), the source white point \(W1\) and the destination
     * white point \(W2\) must be specified:</p>
     *
     * $$\begin{align*}
     * \left[ \begin{array}{c} L_1\\ M_1\\ S_1 \end{array} \right] &=
     *      A \left[ \begin{array}{c} W1_X\\ W1_Y\\ W1_Z \end{array} \right] \\\
     * \left[ \begin{array}{c} L_2\\ M_2\\ S_2 \end{array} \right] &=
     *      A \left[ \begin{array}{c} W2_X\\ W2_Y\\ W2_Z \end{array} \right] \\\
     * D &= \left[ \begin{matrix} \frac{L_2}{L_1} & 0 & 0 \\\
     *      0 & \frac{M_2}{M_1} & 0 \\\
     *      0 & 0 & \frac{S_2}{S_1} \end{matrix} \right] \\\
     * T &= A^{-1}.D.A
     * \end{align*}$$
     *
     * <p>As an example, the resulting matrix \(T\) can then be used to
     * perform the chromatic adaptation of sRGB XYZ transform from D65
     * to D50:</p>
     *
     * $$sRGB_{D50} = T.sRGB_{D65}$$
     *
     * @see ColorSpace.Connector
     * @see ColorSpace#connect(ColorSpace, ColorSpace)
     */
    public enum Adaptation {
        /**
         * Bradford chromatic adaptation transform, as defined in the
         * CIECAM97s color appearance model.
         */
        BRADFORD(new float[] {
                 0.8951f, -0.7502f,  0.0389f,
                 0.2664f,  1.7135f, -0.0685f,
                -0.1614f,  0.0367f,  1.0296f
        }),
        /**
         * von Kries chromatic adaptation transform.
         */
        VON_KRIES(new float[] {
                 0.40024f, -0.22630f, 0.00000f,
                 0.70760f,  1.16532f, 0.00000f,
                -0.08081f,  0.04570f, 0.91822f
        }),
        /**
         * CIECAT02 chromatic adaption transform, as defined in the
         * CIECAM02 color appearance model.
         */
        CIECAT02(new float[] {
                 0.7328f, -0.7036f,  0.0030f,
                 0.4296f,  1.6975f,  0.0136f,
                -0.1624f,  0.0061f,  0.9834f
        });

        final float[] mTransform;

        Adaptation(@NonNull @Size(9) float[] transform) {
            mTransform = transform;
        }
    }

    /**
     * A color model is required by a {@link ColorSpace} to describe the
     * way colors can be represented as tuples of numbers. A common color
     * model is the {@link #RGB RGB} color model which defines a color
     * as represented by a tuple of 3 numbers (red, green and blue).
     */
    public enum Model {
        /**
         * The RGB model is a color model with 3 components that
         * refer to the three additive primiaries: red, green
         * andd blue.
         */
        RGB(3),
        /**
         * The XYZ model is a color model with 3 components that
         * are used to model human color vision on a basic sensory
         * level.
         */
        XYZ(3),
        /**
         * The Lab model is a color model with 3 components used
         * to describe a color space that is more perceptually
         * uniform than XYZ.
         */
        LAB(3),
        /**
         * The CMYK model is a color model with 4 components that
         * refer to four inks used in color printing: cyan, magenta,
         * yellow and black (or key). CMYK is a subtractive color
         * model.
         */
        CMYK(4);

        private final int mComponentCount;

        Model(@IntRange(from = 1, to = 4) int componentCount) {
            mComponentCount = componentCount;
        }

        /**
         * Returns the number of components for this color model.
         *
         * @return An integer between 1 and 4
         */
        @IntRange(from = 1, to = 4)
        public int getComponentCount() {
            return mComponentCount;
        }
    }

    /*package*/ ColorSpace(
            @NonNull String name,
            @NonNull Model model,
            @IntRange(from = MIN_ID, to = MAX_ID) int id) {

        if (name == null || name.length() < 1) {
            throw new IllegalArgumentException("The name of a color space cannot be null and " +
                    "must contain at least 1 character");
        }

        if (model == null) {
            throw new IllegalArgumentException("A color space must have a model");
        }

        if (id < MIN_ID || id > MAX_ID) {
            throw new IllegalArgumentException("The id must be between " +
                    MIN_ID + " and " + MAX_ID);
        }

        mName = name;
        mModel = model;
        mId = id;
    }

    /**
     * <p>Returns the name of this color space. The name is never null
     * and contains always at least 1 character.</p>
     *
     * <p>Color space names are recommended to be unique but are not
     * guaranteed to be. There is no defined format but the name usually
     * falls in one of the following categories:</p>
     * <ul>
     *     <li>Generic names used to identify color spaces in non-RGB
     *     color models. For instance: {@link Named#CIE_LAB Generic L*a*b*}.</li>
     *     <li>Names tied to a particular specification. For instance:
     *     {@link Named#SRGB sRGB IEC61966-2.1} or
     *     {@link Named#ACES SMPTE ST 2065-1:2012 ACES}.</li>
     *     <li>Ad-hoc names, often generated procedurally or by the user
     *     during a calibration workflow. These names often contain the
     *     make and model of the display.</li>
     * </ul>
     *
     * <p>Because the format of color space names is not defined, it is
     * not recommended to programmatically identify a color space by its
     * name alone. Names can be used as a first approximation.</p>
     *
     * <p>It is however perfectly acceptable to display color space names to
     * users in a UI, or in debuggers and logs. When displaying a color space
     * name to the user, it is recommended to add extra information to avoid
     * ambiguities: color model, a representation of the color space's gamut,
     * white point, etc.</p>
     *
     * @return A non-null String of length >= 1
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the ID of this color space. Positive IDs match the color
     * spaces enumerated in {@link Named}. A negative ID indicates a
     * color space created by calling one of the public constructors.
     *
     * @return An integer between {@link #MIN_ID} and {@link #MAX_ID}
     */
    @IntRange(from = MIN_ID, to = MAX_ID)
    public int getId() {
        return mId;
    }

    /**
     * Return the color model of this color space.
     *
     * @return A non-null {@link Model}
     *
     * @see Model
     * @see #getComponentCount()
     */
    @NonNull
    public Model getModel() {
        return mModel;
    }

    /**
     * Returns the number of components that form a color value according
     * to this color space's color model.
     *
     * @return An integer between 1 and 4
     *
     * @see Model
     * @see #getModel()
     */
    @IntRange(from = 1, to = 4)
    public int getComponentCount() {
        return mModel.getComponentCount();
    }

    /**
     * Returns whether this color space is a wide-gamut color space.
     * An RGB color space is wide-gamut if its gamut entirely contains
     * the {@link Named#SRGB sRGB} gamut and if the area of its gamut is
     * 90% of greater than the area of the {@link Named#NTSC_1953 NTSC}
     * gamut.
     *
     * @return True if this color space is a wide-gamut color space,
     *         false otherwise
     */
    public abstract boolean isWideGamut();

    /**
     * <p>Indicates whether this color space is the sRGB color space or
     * equivalent to the sRGB color space.</p>
     * <p>A color space is considered sRGB if it meets all the following
     * conditions:</p>
     * <ul>
     *     <li>Its color model is {@link Model#RGB}.</li>
     *     <li>
     *         Its primaries are within 1e-3 of the true
     *         {@link Named#SRGB sRGB} primaries.
     *     </li>
     *     <li>
     *         Its white point is within 1e-3 of the CIE standard
     *         illuminant {@link #ILLUMINANT_D65 D65}.
     *     </li>
     *     <li>Its opto-electronic transfer function is not linear.</li>
     *     <li>Its electro-optical transfer function is not linear.</li>
     *     <li>Its transfer functions yield values within 1e-3 of {@link Named#SRGB}.</li>
     *     <li>Its range is \([0..1]\).</li>
     * </ul>
     * <p>This method always returns true for {@link Named#SRGB}.</p>
     *
     * @return True if this color space is the sRGB color space (or a
     *         close approximation), false otherwise
     */
    public boolean isSrgb() {
        return false;
    }

    /**
     * Returns the minimum valid value for the specified component of this
     * color space's color model.
     *
     * @param component The index of the component
     * @return A floating point value less than {@link #getMaxValue(int)}
     *
     * @see #getMaxValue(int)
     * @see Model#getComponentCount()
     */
    public abstract float getMinValue(@IntRange(from = 0, to = 3) int component);

    /**
     * Returns the maximum valid value for the specified component of this
     * color space's color model.
     *
     * @param component The index of the component
     * @return A floating point value greater than {@link #getMinValue(int)}
     *
     * @see #getMinValue(int)
     * @see Model#getComponentCount()
     */
    public abstract float getMaxValue(@IntRange(from = 0, to = 3) int component);

    /**
     * <p>Converts a color value from this color space's model to
     * tristimulus CIE XYZ values. If the color model of this color
     * space is not {@link Model#RGB RGB}, it is assumed that the
     * target CIE XYZ space uses a {@link #ILLUMINANT_D50 D50}
     * standard illuminant.</p>
     *
     * <p>This method is a convenience for color spaces with a model
     * of 3 components ({@link Model#RGB RGB} or {@link Model#LAB}
     * for instance). With color spaces using fewer or more components,
     * use {@link #toXyz(float[])} instead</p>.
     *
     * @param r The first component of the value to convert from (typically R in RGB)
     * @param g The second component of the value to convert from (typically G in RGB)
     * @param b The third component of the value to convert from (typically B in RGB)
     * @return A new array of 3 floats, containing tristimulus XYZ values
     *
     * @see #toXyz(float[])
     * @see #fromXyz(float, float, float)
     */
    @NonNull
    @Size(3)
    public float[] toXyz(float r, float g, float b) {
        return toXyz(new float[] { r, g, b });
    }

    /**
     * <p>Converts a color value from this color space's model to
     * tristimulus CIE XYZ values. If the color model of this color
     * space is not {@link Model#RGB RGB}, it is assumed that the
     * target CIE XYZ space uses a {@link #ILLUMINANT_D50 D50}
     * standard illuminant.</p>
     *
     * <p class="note">The specified array's length  must be at least
     * equal to to the number of color components as returned by
     * {@link Model#getComponentCount()}.</p>
     *
     * @param v An array of color components containing the color space's
     *          color value to convert to XYZ, and large enough to hold
     *          the resulting tristimulus XYZ values
     * @return The array passed in parameter
     *
     * @see #toXyz(float, float, float)
     * @see #fromXyz(float[])
     */
    @NonNull
    @Size(min = 3)
    public abstract float[] toXyz(@NonNull @Size(min = 3) float[] v);

    /**
     * <p>Converts tristimulus values from the CIE XYZ space to this
     * color space's color model.</p>
     *
     * @param x The X component of the color value
     * @param y The Y component of the color value
     * @param z The Z component of the color value
     * @return A new array whose size is equal to the number of color
     *         components as returned by {@link Model#getComponentCount()}
     *
     * @see #fromXyz(float[])
     * @see #toXyz(float, float, float)
     */
    @NonNull
    @Size(min = 3)
    public float[] fromXyz(float x, float y, float z) {
        float[] xyz = new float[mModel.getComponentCount()];
        xyz[0] = x;
        xyz[1] = y;
        xyz[2] = z;
        return fromXyz(xyz);
    }

    /**
     * <p>Converts tristimulus values from the CIE XYZ space to this color
     * space's color model. The resulting value is passed back in the specified
     * array.</p>
     *
     * <p class="note">The specified array's length  must be at least equal to
     * to the number of color components as returned by
     * {@link Model#getComponentCount()}, and its first 3 values must
     * be the XYZ components to convert from.</p>
     *
     * @param v An array of color components containing the XYZ values
     *          to convert from, and large enough to hold the number
     *          of components of this color space's model
     * @return The array passed in parameter
     *
     * @see #fromXyz(float, float, float)
     * @see #toXyz(float[])
     */
    @NonNull
    @Size(min = 3)
    public abstract float[] fromXyz(@NonNull @Size(min = 3) float[] v);

    /**
     * <p>Returns a string representation of the object. This method returns
     * a string equal to the value of:</p>
     *
     * <pre class="prettyprint">
     * getName() + "(id=" + getId() + ", model=" + getModel() + ")"
     * </pre>
     *
     * <p>For instance, the string representation of the {@link Named#SRGB sRGB}
     * color space is equal to the following value:</p>
     *
     * <pre>
     * sRGB IEC61966-2.1 (id=0, model=RGB)
     * </pre>
     *
     * @return A string representation of the object
     */
    @Override
    @NonNull
    public String toString() {
        return mName + " (id=" + mId + ", model=" + mModel + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ColorSpace that = (ColorSpace) o;

        if (mId != that.mId) return false;
        //noinspection SimplifiableIfStatement
        if (!mName.equals(that.mName)) return false;
        return mModel == that.mModel;

    }

    @Override
    public int hashCode() {
        int result = mName.hashCode();
        result = 31 * result + mModel.hashCode();
        result = 31 * result + mId;
        return result;
    }

    /**
     * <p>Connects two color spaces to allow conversion from the source color
     * space to the destination color space. If the source and destination
     * color spaces do not have the same profile connection space (CIE XYZ
     * with the same white point), they are chromatically adapted to use the
     * CIE standard illuminant {@link #ILLUMINANT_D50 D50} as needed.</p>
     *
     * <p>If the source and destination are the same, an optimized connector
     * is returned to avoid unnecessary computations and loss of precision.</p>
     *
     * <p>Colors are mapped from the source color space to the destination color
     * space using the {@link RenderIntent#PERCEPTUAL perceptual} render intent.</p>
     *
     * @param source The color space to convert colors from
     * @param destination The color space to convert colors to
     * @return A non-null connector between the two specified color spaces
     *
     * @see #connect(ColorSpace)
     * @see #connect(ColorSpace, RenderIntent)
     * @see #connect(ColorSpace, ColorSpace, RenderIntent)
     */
    @NonNull
    public static Connector connect(@NonNull ColorSpace source, @NonNull ColorSpace destination) {
        return connect(source, destination, RenderIntent.PERCEPTUAL);
    }

    /**
     * <p>Connects two color spaces to allow conversion from the source color
     * space to the destination color space. If the source and destination
     * color spaces do not have the same profile connection space (CIE XYZ
     * with the same white point), they are chromatically adapted to use the
     * CIE standard illuminant {@link #ILLUMINANT_D50 D50} as needed.</p>
     *
     * <p>If the source and destination are the same, an optimized connector
     * is returned to avoid unnecessary computations and loss of precision.</p>
     *
     * @param source The color space to convert colors from
     * @param destination The color space to convert colors to
     * @param intent The render intent to map colors from the source to the destination
     * @return A non-null connector between the two specified color spaces
     *
     * @see #connect(ColorSpace)
     * @see #connect(ColorSpace, RenderIntent)
     * @see #connect(ColorSpace, ColorSpace)
     */
    @NonNull
    @SuppressWarnings("ConstantConditions")
    public static Connector connect(@NonNull ColorSpace source, @NonNull ColorSpace destination,
            @NonNull RenderIntent intent) {
        if (source.equals(destination)) return Connector.identity(source);

        if (source.getModel() == Model.RGB && destination.getModel() == Model.RGB) {
            return new Connector.Rgb((Rgb) source, (Rgb) destination, intent);
        }

        return new Connector(source, destination, intent);
    }

    /**
     * <p>Connects the specified color spaces to sRGB.
     * If the source color space does not use CIE XYZ D65 as its profile
     * connection space, the two spaces are chromatically adapted to use the
     * CIE standard illuminant {@link #ILLUMINANT_D50 D50} as needed.</p>
     *
     * <p>If the source is the sRGB color space, an optimized connector
     * is returned to avoid unnecessary computations and loss of precision.</p>
     *
     * <p>Colors are mapped from the source color space to the destination color
     * space using the {@link RenderIntent#PERCEPTUAL perceptual} render intent.</p>
     *
     * @param source The color space to convert colors from
     * @return A non-null connector between the specified color space and sRGB
     *
     * @see #connect(ColorSpace, RenderIntent)
     * @see #connect(ColorSpace, ColorSpace)
     * @see #connect(ColorSpace, ColorSpace, RenderIntent)
     */
    @NonNull
    public static Connector connect(@NonNull ColorSpace source) {
        return connect(source, RenderIntent.PERCEPTUAL);
    }

    /**
     * <p>Connects the specified color spaces to sRGB.
     * If the source color space does not use CIE XYZ D65 as its profile
     * connection space, the two spaces are chromatically adapted to use the
     * CIE standard illuminant {@link #ILLUMINANT_D50 D50} as needed.</p>
     *
     * <p>If the source is the sRGB color space, an optimized connector
     * is returned to avoid unnecessary computations and loss of precision.</p>
     *
     * @param source The color space to convert colors from
     * @param intent The render intent to map colors from the source to the destination
     * @return A non-null connector between the specified color space and sRGB
     *
     * @see #connect(ColorSpace)
     * @see #connect(ColorSpace, ColorSpace)
     * @see #connect(ColorSpace, ColorSpace, RenderIntent)
     */
    @NonNull
    public static Connector connect(@NonNull ColorSpace source, @NonNull RenderIntent intent) {
        if (source.isSrgb()) return Connector.identity(source);

        if (source.getModel() == Model.RGB) {
            return new Connector.Rgb((Rgb) source, (Rgb) get(Named.SRGB), intent);
        }

        return new Connector(source, get(Named.SRGB), intent);
    }

    /**
     * <p>Performs the chromatic adaptation of a color space from its native
     * white point to the specified white point.</p>
     *
     * <p>The chromatic adaptation is performed using the
     * {@link Adaptation#BRADFORD} matrix.</p>
     *
     * <p class="note">The color space returned by this method always has
     * an ID of {@link #MIN_ID}.</p>
     *
     * @param colorSpace The color space to chromatically adapt
     * @param whitePoint The new white point
     * @return A {@link ColorSpace} instance with the same name, primaries,
     *         transfer functions and range as the specified color space
     *
     * @see Adaptation
     * @see #adapt(ColorSpace, float[], Adaptation)
     */
    @NonNull
    public static ColorSpace adapt(@NonNull ColorSpace colorSpace,
            @NonNull @Size(min = 2, max = 3) float[] whitePoint) {
        return adapt(colorSpace, whitePoint, Adaptation.BRADFORD);
    }

    /**
     * <p>Performs the chromatic adaptation of a color space from its native
     * white point to the specified white point. If the specified color space
     * does not have an {@link Model#RGB RGB} color model, or if the color
     * space already has the target white point, the color space is returned
     * unmodified.</p>
     *
     * <p>The chromatic adaptation is performed using the von Kries method
     * described in the documentation of {@link Adaptation}.</p>
     *
     * <p class="note">The color space returned by this method always has
     * an ID of {@link #MIN_ID}.</p>
     *
     * @param colorSpace The color space to chromatically adapt
     * @param whitePoint The new white point
     * @param adaptation The adaptation matrix
     * @return A new color space if the specified color space has an RGB
     *         model and a white point different from the specified white
     *         point; the specified color space otherwise
     *
     * @see Adaptation
     * @see #adapt(ColorSpace, float[])
     */
    @NonNull
    public static ColorSpace adapt(@NonNull ColorSpace colorSpace,
            @NonNull @Size(min = 2, max = 3) float[] whitePoint,
            @NonNull Adaptation adaptation) {
        if (colorSpace.getModel() == Model.RGB) {
            ColorSpace.Rgb rgb = (ColorSpace.Rgb) colorSpace;
            if (compare(rgb.mWhitePoint, whitePoint)) return colorSpace;

            float[] xyz = whitePoint.length == 3 ?
                    Arrays.copyOf(whitePoint, 3) : xyYToXyz(whitePoint);
            float[] adaptationTransform = chromaticAdaptation(adaptation.mTransform,
                    xyYToXyz(rgb.getWhitePoint()), xyz);
            float[] transform = mul3x3(adaptationTransform, rgb.mTransform);

            return new ColorSpace.Rgb(rgb, transform, whitePoint);
        }
        return colorSpace;
    }

    /**
     * Helper method for creating native SkColorSpace.
     *
     * This essentially calls adapt on a ColorSpace that has not been fully
     * created. It also does not fully create the adapted ColorSpace, but
     * just returns the transform.
     */
    @NonNull @Size(9)
    private static float[] adaptToIlluminantD50(
            @NonNull @Size(2) float[] origWhitePoint,
            @NonNull @Size(9) float[] origTransform) {
        float[] desired = ILLUMINANT_D50;
        if (compare(origWhitePoint, desired)) return origTransform;

        float[] xyz = xyYToXyz(desired);
        float[] adaptationTransform = chromaticAdaptation(Adaptation.BRADFORD.mTransform,
                    xyYToXyz(origWhitePoint), xyz);
        return mul3x3(adaptationTransform, origTransform);
    }

    /**
     * <p>Returns an instance of {@link ColorSpace} whose ID matches the
     * specified ID.</p>
     *
     * <p>This method always returns the same instance for a given ID.</p>
     *
     * <p>This method is thread-safe.</p>
     *
     * @param index An integer ID between {@link #MIN_ID} and {@link #MAX_ID}
     * @return A non-null {@link ColorSpace} instance
     * @throws IllegalArgumentException If the ID does not match the ID of one of the
     *         {@link Named named color spaces}
     */
    @NonNull
    static ColorSpace get(@IntRange(from = MIN_ID, to = MAX_ID) int index) {
        if (index < 0 || index >= sNamedColorSpaces.length) {
            throw new IllegalArgumentException("Invalid ID, must be in the range [0.." +
                    sNamedColorSpaces.length + ")");
        }
        return sNamedColorSpaces[index];
    }

    /**
     * <p>Returns an instance of {@link ColorSpace} identified by the specified
     * name. The list of names provided in the {@link Named} enum gives access
     * to a variety of common RGB color spaces.</p>
     *
     * <p>This method always returns the same instance for a given name.</p>
     *
     * <p>This method is thread-safe.</p>
     *
     * @param name The name of the color space to get an instance of
     * @return A non-null {@link ColorSpace} instance
     */
    @NonNull
    public static ColorSpace get(@NonNull Named name) {
        return sNamedColorSpaces[name.ordinal()];
    }

    /**
     * <p>Returns a {@link Named} instance of {@link ColorSpace} that matches
     * the specified RGB to CIE XYZ transform and transfer functions. If no
     * instance can be found, this method returns null.</p>
     *
     * <p>The color transform matrix is assumed to target the CIE XYZ space
     * a {@link #ILLUMINANT_D50 D50} standard illuminant.</p>
     *
     * @param toXYZD50 3x3 column-major transform matrix from RGB to the profile
     *                 connection space CIE XYZ as an array of 9 floats, cannot be null
     * @param function Parameters for the transfer functions
     * @return A non-null {@link ColorSpace} if a match is found, null otherwise
     */
    @Nullable
    public static ColorSpace match(
            @NonNull @Size(9) float[] toXYZD50,
            @NonNull Rgb.TransferParameters function) {

        for (ColorSpace colorSpace : sNamedColorSpaces) {
            if (colorSpace.getModel() == Model.RGB) {
                ColorSpace.Rgb rgb = (ColorSpace.Rgb) adapt(colorSpace, ILLUMINANT_D50_XYZ);
                if (compare(toXYZD50, rgb.mTransform) &&
                        compare(function, rgb.mTransferParameters)) {
                    return colorSpace;
                }
            }
        }

        return null;
    }

    static {
        sNamedColorSpaces[Named.SRGB.ordinal()] = new ColorSpace.Rgb(
                "sRGB IEC61966-2.1",
                SRGB_PRIMARIES,
                ILLUMINANT_D65,
                null,
                SRGB_TRANSFER_PARAMETERS,
                Named.SRGB.ordinal()
        );
        sNamedColorSpaces[Named.LINEAR_SRGB.ordinal()] = new ColorSpace.Rgb(
                "sRGB IEC61966-2.1 (Linear)",
                SRGB_PRIMARIES,
                ILLUMINANT_D65,
                1.0,
                0.0f, 1.0f,
                Named.LINEAR_SRGB.ordinal()
        );
        sNamedColorSpaces[Named.EXTENDED_SRGB.ordinal()] = new ColorSpace.Rgb(
                "scRGB-nl IEC 61966-2-2:2003",
                SRGB_PRIMARIES,
                ILLUMINANT_D65,
                null,
                x -> absRcpResponse(x, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 2.4),
                x -> absResponse(x, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 2.4),
                -0.799f, 2.399f,
                SRGB_TRANSFER_PARAMETERS,
                Named.EXTENDED_SRGB.ordinal()
        );
        sNamedColorSpaces[Named.LINEAR_EXTENDED_SRGB.ordinal()] = new ColorSpace.Rgb(
                "scRGB IEC 61966-2-2:2003",
                SRGB_PRIMARIES,
                ILLUMINANT_D65,
                1.0,
                -0.5f, 7.499f,
                Named.LINEAR_EXTENDED_SRGB.ordinal()
        );
        sNamedColorSpaces[Named.BT709.ordinal()] = new ColorSpace.Rgb(
                "Rec. ITU-R BT.709-5",
                new float[] { 0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f },
                ILLUMINANT_D65,
                null,
                new Rgb.TransferParameters(1 / 1.099, 0.099 / 1.099, 1 / 4.5, 0.081, 1 / 0.45),
                Named.BT709.ordinal()
        );
        sNamedColorSpaces[Named.BT2020.ordinal()] = new ColorSpace.Rgb(
                "Rec. ITU-R BT.2020-1",
                new float[] { 0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f },
                ILLUMINANT_D65,
                null,
                new Rgb.TransferParameters(1 / 1.0993, 0.0993 / 1.0993, 1 / 4.5, 0.08145, 1 / 0.45),
                Named.BT2020.ordinal()
        );
        sNamedColorSpaces[Named.DCI_P3.ordinal()] = new ColorSpace.Rgb(
                "SMPTE RP 431-2-2007 DCI (P3)",
                new float[] { 0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f },
                new float[] { 0.314f, 0.351f },
                2.6,
                0.0f, 1.0f,
                Named.DCI_P3.ordinal()
        );
        sNamedColorSpaces[Named.DISPLAY_P3.ordinal()] = new ColorSpace.Rgb(
                "Display P3",
                new float[] { 0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f },
                ILLUMINANT_D65,
                null,
                SRGB_TRANSFER_PARAMETERS,
                Named.DISPLAY_P3.ordinal()
        );
        sNamedColorSpaces[Named.NTSC_1953.ordinal()] = new ColorSpace.Rgb(
                "NTSC (1953)",
                NTSC_1953_PRIMARIES,
                ILLUMINANT_C,
                null,
                new Rgb.TransferParameters(1 / 1.099, 0.099 / 1.099, 1 / 4.5, 0.081, 1 / 0.45),
                Named.NTSC_1953.ordinal()
        );
        sNamedColorSpaces[Named.SMPTE_C.ordinal()] = new ColorSpace.Rgb(
                "SMPTE-C RGB",
                new float[] { 0.630f, 0.340f, 0.310f, 0.595f, 0.155f, 0.070f },
                ILLUMINANT_D65,
                null,
                new Rgb.TransferParameters(1 / 1.099, 0.099 / 1.099, 1 / 4.5, 0.081, 1 / 0.45),
                Named.SMPTE_C.ordinal()
        );
        sNamedColorSpaces[Named.ADOBE_RGB.ordinal()] = new ColorSpace.Rgb(
                "Adobe RGB (1998)",
                new float[] { 0.64f, 0.33f, 0.21f, 0.71f, 0.15f, 0.06f },
                ILLUMINANT_D65,
                2.2,
                0.0f, 1.0f,
                Named.ADOBE_RGB.ordinal()
        );
        sNamedColorSpaces[Named.PRO_PHOTO_RGB.ordinal()] = new ColorSpace.Rgb(
                "ROMM RGB ISO 22028-2:2013",
                new float[] { 0.7347f, 0.2653f, 0.1596f, 0.8404f, 0.0366f, 0.0001f },
                ILLUMINANT_D50,
                null,
                new Rgb.TransferParameters(1.0, 0.0, 1 / 16.0, 0.031248, 1.8),
                Named.PRO_PHOTO_RGB.ordinal()
        );
        sNamedColorSpaces[Named.ACES.ordinal()] = new ColorSpace.Rgb(
                "SMPTE ST 2065-1:2012 ACES",
                new float[] { 0.73470f, 0.26530f, 0.0f, 1.0f, 0.00010f, -0.0770f },
                ILLUMINANT_D60,
                1.0,
                -65504.0f, 65504.0f,
                Named.ACES.ordinal()
        );
        sNamedColorSpaces[Named.ACESCG.ordinal()] = new ColorSpace.Rgb(
                "Academy S-2014-004 ACEScg",
                new float[] { 0.713f, 0.293f, 0.165f, 0.830f, 0.128f, 0.044f },
                ILLUMINANT_D60,
                1.0,
                -65504.0f, 65504.0f,
                Named.ACESCG.ordinal()
        );
        sNamedColorSpaces[Named.CIE_XYZ.ordinal()] = new Xyz(
                "Generic XYZ",
                Named.CIE_XYZ.ordinal()
        );
        sNamedColorSpaces[Named.CIE_LAB.ordinal()] = new ColorSpace.Lab(
                "Generic L*a*b*",
                Named.CIE_LAB.ordinal()
        );
    }

    // Reciprocal piecewise gamma response
    private static double rcpResponse(double x, double a, double b, double c, double d, double g) {
        return x >= d * c ? (Math.pow(x, 1.0 / g) - b) / a : x / c;
    }

    // Piecewise gamma response
    private static double response(double x, double a, double b, double c, double d, double g) {
        return x >= d ? Math.pow(a * x + b, g) : c * x;
    }

    // Reciprocal piecewise gamma response
    private static double rcpResponse(double x, double a, double b, double c, double d,
            double e, double f, double g) {
        return x >= d * c ? (Math.pow(x - e, 1.0 / g) - b) / a : (x - f) / c;
    }

    // Piecewise gamma response
    private static double response(double x, double a, double b, double c, double d,
            double e, double f, double g) {
        return x >= d ? Math.pow(a * x + b, g) + e : c * x + f;
    }

    // Reciprocal piecewise gamma response, encoded as sign(x).f(abs(x)) for color
    // spaces that allow negative values
    @SuppressWarnings("SameParameterValue")
    private static double absRcpResponse(double x, double a, double b, double c, double d, double g) {
        return Math.copySign(rcpResponse(x < 0.0 ? -x : x, a, b, c, d, g), x);
    }

    // Piecewise gamma response, encoded as sign(x).f(abs(x)) for color spaces that
    // allow negative values
    @SuppressWarnings("SameParameterValue")
    private static double absResponse(double x, double a, double b, double c, double d, double g) {
        return Math.copySign(response(x < 0.0 ? -x : x, a, b, c, d, g), x);
    }

    /**
     * Compares two sets of parametric transfer functions parameters with a precision of 1e-3.
     *
     * @param a The first set of parameters to compare
     * @param b The second set of parameters to compare
     * @return True if the two sets are equal, false otherwise
     */
    private static boolean compare(
            @Nullable Rgb.TransferParameters a,
            @Nullable Rgb.TransferParameters b) {
        //noinspection SimplifiableIfStatement
        if (a == null && b == null) return true;
        return a != null && b != null &&
                Math.abs(a.a - b.a) < 1e-3 &&
                Math.abs(a.b - b.b) < 1e-3 &&
                Math.abs(a.c - b.c) < 1e-3 &&
                Math.abs(a.d - b.d) < 2e-3 && // Special case for variations in sRGB OETF/EOTF
                Math.abs(a.e - b.e) < 1e-3 &&
                Math.abs(a.f - b.f) < 1e-3 &&
                Math.abs(a.g - b.g) < 1e-3;
    }

    /**
     * Compares two arrays of float with a precision of 1e-3.
     *
     * @param a The first array to compare
     * @param b The second array to compare
     * @return True if the two arrays are equal, false otherwise
     */
    private static boolean compare(@NonNull float[] a, @NonNull float[] b) {
        if (a == b) return true;
        for (int i = 0; i < a.length; i++) {
            if (Float.compare(a[i], b[i]) != 0 && Math.abs(a[i] - b[i]) > 1e-3f) return false;
        }
        return true;
    }

    /**
     * Inverts a 3x3 matrix. This method assumes the matrix is invertible.
     *
     * @param m A 3x3 matrix as a non-null array of 9 floats
     * @return A new array of 9 floats containing the inverse of the input matrix
     */
    @NonNull
    @Size(9)
    private static float[] inverse3x3(@NonNull @Size(9) float[] m) {
        float a = m[0];
        float b = m[3];
        float c = m[6];
        float d = m[1];
        float e = m[4];
        float f = m[7];
        float g = m[2];
        float h = m[5];
        float i = m[8];

        float A = e * i - f * h;
        float B = f * g - d * i;
        float C = d * h - e * g;

        float det = a * A + b * B + c * C;

        float inverted[] = new float[m.length];
        inverted[0] = A / det;
        inverted[1] = B / det;
        inverted[2] = C / det;
        inverted[3] = (c * h - b * i) / det;
        inverted[4] = (a * i - c * g) / det;
        inverted[5] = (b * g - a * h) / det;
        inverted[6] = (b * f - c * e) / det;
        inverted[7] = (c * d - a * f) / det;
        inverted[8] = (a * e - b * d) / det;
        return inverted;
    }

    /**
     * Multiplies two 3x3 matrices, represented as non-null arrays of 9 floats.
     *
     * @param lhs 3x3 matrix, as a non-null array of 9 floats
     * @param rhs 3x3 matrix, as a non-null array of 9 floats
     * @return A new array of 9 floats containing the result of the multiplication
     *         of rhs by lhs
     */
    @NonNull
    @Size(9)
    private static float[] mul3x3(@NonNull @Size(9) float[] lhs, @NonNull @Size(9) float[] rhs) {
        float[] r = new float[9];
        r[0] = lhs[0] * rhs[0] + lhs[3] * rhs[1] + lhs[6] * rhs[2];
        r[1] = lhs[1] * rhs[0] + lhs[4] * rhs[1] + lhs[7] * rhs[2];
        r[2] = lhs[2] * rhs[0] + lhs[5] * rhs[1] + lhs[8] * rhs[2];
        r[3] = lhs[0] * rhs[3] + lhs[3] * rhs[4] + lhs[6] * rhs[5];
        r[4] = lhs[1] * rhs[3] + lhs[4] * rhs[4] + lhs[7] * rhs[5];
        r[5] = lhs[2] * rhs[3] + lhs[5] * rhs[4] + lhs[8] * rhs[5];
        r[6] = lhs[0] * rhs[6] + lhs[3] * rhs[7] + lhs[6] * rhs[8];
        r[7] = lhs[1] * rhs[6] + lhs[4] * rhs[7] + lhs[7] * rhs[8];
        r[8] = lhs[2] * rhs[6] + lhs[5] * rhs[7] + lhs[8] * rhs[8];
        return r;
    }

    /**
     * Multiplies a vector of 3 components by a 3x3 matrix and stores the
     * result in the input vector.
     *
     * @param lhs 3x3 matrix, as a non-null array of 9 floats
     * @param rhs Vector of 3 components, as a non-null array of 3 floats
     * @return The array of 3 passed as the rhs parameter
     */
    @NonNull
    @Size(min = 3)
    private static float[] mul3x3Float3(
            @NonNull @Size(9) float[] lhs, @NonNull @Size(min = 3) float[] rhs) {
        float r0 = rhs[0];
        float r1 = rhs[1];
        float r2 = rhs[2];
        rhs[0] = lhs[0] * r0 + lhs[3] * r1 + lhs[6] * r2;
        rhs[1] = lhs[1] * r0 + lhs[4] * r1 + lhs[7] * r2;
        rhs[2] = lhs[2] * r0 + lhs[5] * r1 + lhs[8] * r2;
        return rhs;
    }

    /**
     * Multiplies a diagonal 3x3 matrix lhs, represented as an array of 3 floats,
     * by a 3x3 matrix represented as an array of 9 floats.
     *
     * @param lhs Diagonal 3x3 matrix, as a non-null array of 3 floats
     * @param rhs 3x3 matrix, as a non-null array of 9 floats
     * @return A new array of 9 floats containing the result of the multiplication
     *         of rhs by lhs
     */
    @NonNull
    @Size(9)
    private static float[] mul3x3Diag(
            @NonNull @Size(3) float[] lhs, @NonNull @Size(9) float[] rhs) {
        return new float[] {
                lhs[0] * rhs[0], lhs[1] * rhs[1], lhs[2] * rhs[2],
                lhs[0] * rhs[3], lhs[1] * rhs[4], lhs[2] * rhs[5],
                lhs[0] * rhs[6], lhs[1] * rhs[7], lhs[2] * rhs[8]
        };
    }

    /**
     * Converts a value from CIE xyY to CIE XYZ. Y is assumed to be 1 so the
     * input xyY array only contains the x and y components.
     *
     * @param xyY The xyY value to convert to XYZ, cannot be null, length must be 2
     * @return A new float array of length 3 containing XYZ values
     */
    @NonNull
    @Size(3)
    private static float[] xyYToXyz(@NonNull @Size(2) float[] xyY) {
        return new float[] { xyY[0] / xyY[1], 1.0f, (1 - xyY[0] - xyY[1]) / xyY[1] };
    }

    /**
     * <p>Computes the chromatic adaptation transform from the specified
     * source white point to the specified destination white point.</p>
     *
     * <p>The transform is computed using the von Kries method, described
     * in more details in the documentation of {@link Adaptation}. The
     * {@link Adaptation} enum provides different matrices that can be
     * used to perform the adaptation.</p>
     *
     * @param matrix The adaptation matrix
     * @param srcWhitePoint The white point to adapt from, *will be modified*
     * @param dstWhitePoint The white point to adapt to, *will be modified*
     * @return A 3x3 matrix as a non-null array of 9 floats
     */
    @NonNull
    @Size(9)
    private static float[] chromaticAdaptation(@NonNull @Size(9) float[] matrix,
            @NonNull @Size(3) float[] srcWhitePoint, @NonNull @Size(3) float[] dstWhitePoint) {
        float[] srcLMS = mul3x3Float3(matrix, srcWhitePoint);
        float[] dstLMS = mul3x3Float3(matrix, dstWhitePoint);
        // LMS is a diagonal matrix stored as a float[3]
        float[] LMS = { dstLMS[0] / srcLMS[0], dstLMS[1] / srcLMS[1], dstLMS[2] / srcLMS[2] };
        return mul3x3(inverse3x3(matrix), mul3x3Diag(LMS, matrix));
    }

    /**
     * <p>Computes the chromaticity coordinates of a specified correlated color
     * temperature (CCT) on the Planckian locus. The specified CCT must be
     * greater than 0. A meaningful CCT range is [1667, 25000].</p>
     *
     * <p>The transform is computed using the methods in Kang et
     * al., <i>Design of Advanced Color - Temperature Control System for HDTV
     * Applications</i>, Journal of Korean Physical Society 41, 865-871
     * (2002).</p>
     *
     * @param cct The correlated color temperature, in Kelvin
     * @return Corresponding XYZ values
     * @throws IllegalArgumentException If cct is invalid
     */
    @NonNull
    @Size(3)
    public static float[] cctToXyz(@IntRange(from = 1) int cct) {
        if (cct < 1) {
            throw new IllegalArgumentException("Temperature must be greater than 0");
        }

        final float icct = 1e3f / cct;
        final float icct2 = icct * icct;
        final float x = cct <= 4000.0f ?
            0.179910f + 0.8776956f * icct - 0.2343589f * icct2 - 0.2661239f * icct2 * icct :
            0.240390f + 0.2226347f * icct + 2.1070379f * icct2 - 3.0258469f * icct2 * icct;

        final float x2 = x * x;
        final float y = cct <= 2222.0f ?
            -0.20219683f + 2.18555832f * x - 1.34811020f * x2 - 1.1063814f * x2 * x :
            cct <= 4000.0f ?
            -0.16748867f + 2.09137015f * x - 1.37418593f * x2 - 0.9549476f * x2 * x :
            -0.37001483f + 3.75112997f * x - 5.8733867f * x2 + 3.0817580f * x2 * x;

        return xyYToXyz(new float[] {x, y});
    }

    /**
     * <p>Computes the chromatic adaptation transform from the specified
     * source white point to the specified destination white point.</p>
     *
     * <p>The transform is computed using the von Kries method, described
     * in more details in the documentation of {@link Adaptation}. The
     * {@link Adaptation} enum provides different matrices that can be
     * used to perform the adaptation.</p>
     *
     * @param adaptation The adaptation method
     * @param srcWhitePoint The white point to adapt from
     * @param dstWhitePoint The white point to adapt to
     * @return A 3x3 matrix as a non-null array of 9 floats
     */
    @NonNull
    @Size(9)
    public static float[] chromaticAdaptation(@NonNull Adaptation adaptation,
            @NonNull @Size(min = 2, max = 3) float[] srcWhitePoint,
            @NonNull @Size(min = 2, max = 3) float[] dstWhitePoint) {
        if ((srcWhitePoint.length != 2 && srcWhitePoint.length != 3)
                || (dstWhitePoint.length != 2 && dstWhitePoint.length != 3)) {
            throw new IllegalArgumentException("A white point array must have 2 or 3 floats");
        }
        float[] srcXyz = srcWhitePoint.length == 3 ?
            Arrays.copyOf(srcWhitePoint, 3) : xyYToXyz(srcWhitePoint);
        float[] dstXyz = dstWhitePoint.length == 3 ?
            Arrays.copyOf(dstWhitePoint, 3) : xyYToXyz(dstWhitePoint);

        if (compare(srcXyz, dstXyz)) {
            return new float[] {
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
            };
        }
        return chromaticAdaptation(adaptation.mTransform, srcXyz, dstXyz);
    }

    /**
     * Implementation of the CIE XYZ color space. Assumes the white point is D50.
     */
    @AnyThread
    private static final class Xyz extends ColorSpace {
        private Xyz(@NonNull String name, @IntRange(from = MIN_ID, to = MAX_ID) int id) {
            super(name, Model.XYZ, id);
        }

        @Override
        public boolean isWideGamut() {
            return true;
        }

        @Override
        public float getMinValue(@IntRange(from = 0, to = 3) int component) {
            return -2.0f;
        }

        @Override
        public float getMaxValue(@IntRange(from = 0, to = 3) int component) {
            return 2.0f;
        }

        @Override
        public float[] toXyz(@NonNull @Size(min = 3) float[] v) {
            v[0] = clamp(v[0]);
            v[1] = clamp(v[1]);
            v[2] = clamp(v[2]);
            return v;
        }

        @Override
        public float[] fromXyz(@NonNull @Size(min = 3) float[] v) {
            v[0] = clamp(v[0]);
            v[1] = clamp(v[1]);
            v[2] = clamp(v[2]);
            return v;
        }

        private static float clamp(float x) {
            return x < -2.0f ? -2.0f : x > 2.0f ? 2.0f : x;
        }
    }

    /**
     * Implementation of the CIE L*a*b* color space. Its PCS is CIE XYZ
     * with a white point of D50.
     */
    @AnyThread
    private static final class Lab extends ColorSpace {
        private static final float A = 216.0f / 24389.0f;
        private static final float B = 841.0f / 108.0f;
        private static final float C = 4.0f / 29.0f;
        private static final float D = 6.0f / 29.0f;

        private Lab(@NonNull String name, @IntRange(from = MIN_ID, to = MAX_ID) int id) {
            super(name, Model.LAB, id);
        }

        @Override
        public boolean isWideGamut() {
            return true;
        }

        @Override
        public float getMinValue(@IntRange(from = 0, to = 3) int component) {
            return component == 0 ? 0.0f : -128.0f;
        }

        @Override
        public float getMaxValue(@IntRange(from = 0, to = 3) int component) {
            return component == 0 ? 100.0f : 128.0f;
        }

        @Override
        public float[] toXyz(@NonNull @Size(min = 3) float[] v) {
            v[0] = clamp(v[0], 0.0f, 100.0f);
            v[1] = clamp(v[1], -128.0f, 128.0f);
            v[2] = clamp(v[2], -128.0f, 128.0f);

            float fy = (v[0] + 16.0f) / 116.0f;
            float fx = fy + (v[1] * 0.002f);
            float fz = fy - (v[2] * 0.005f);
            float X = fx > D ? fx * fx * fx : (1.0f / B) * (fx - C);
            float Y = fy > D ? fy * fy * fy : (1.0f / B) * (fy - C);
            float Z = fz > D ? fz * fz * fz : (1.0f / B) * (fz - C);

            v[0] = X * ILLUMINANT_D50_XYZ[0];
            v[1] = Y * ILLUMINANT_D50_XYZ[1];
            v[2] = Z * ILLUMINANT_D50_XYZ[2];

            return v;
        }

        @Override
        public float[] fromXyz(@NonNull @Size(min = 3) float[] v) {
            float X = v[0] / ILLUMINANT_D50_XYZ[0];
            float Y = v[1] / ILLUMINANT_D50_XYZ[1];
            float Z = v[2] / ILLUMINANT_D50_XYZ[2];

            float fx = X > A ? (float) Math.pow(X, 1.0 / 3.0) : B * X + C;
            float fy = Y > A ? (float) Math.pow(Y, 1.0 / 3.0) : B * Y + C;
            float fz = Z > A ? (float) Math.pow(Z, 1.0 / 3.0) : B * Z + C;

            float L = 116.0f * fy - 16.0f;
            float a = 500.0f * (fx - fy);
            float b = 200.0f * (fy - fz);

            v[0] = clamp(L, 0.0f, 100.0f);
            v[1] = clamp(a, -128.0f, 128.0f);
            v[2] = clamp(b, -128.0f, 128.0f);

            return v;
        }

        private static float clamp(float x, float min, float max) {
            return x < min ? min : x > max ? max : x;
        }
    }

    /**
     * Retrieve the native SkColorSpace object for passing to native.
     *
     * Only valid on ColorSpace.Rgb.
     */
    long getNativeInstance() {
        throw new IllegalArgumentException("colorSpace must be an RGB color space");
    }

    /**
     * {@usesMathJax}
     *
     * <p>An RGB color space is an additive color space using the
     * {@link Model#RGB RGB} color model (a color is therefore represented
     * by a tuple of 3 numbers).</p>
     *
     * <p>A specific RGB color space is defined by the following properties:</p>
     * <ul>
     *     <li>Three chromaticities of the red, green and blue primaries, which
     *     define the gamut of the color space.</li>
     *     <li>A white point chromaticity that defines the stimulus to which
     *     color space values are normalized (also just called "white").</li>
     *     <li>An opto-electronic transfer function, also called opto-electronic
     *     conversion function or often, and approximately, gamma function.</li>
     *     <li>An electro-optical transfer function, also called electo-optical
     *     conversion function or often, and approximately, gamma function.</li>
     *     <li>A range of valid RGB values (most commonly \([0..1]\)).</li>
     * </ul>
     *
     * <p>The most commonly used RGB color space is {@link Named#SRGB sRGB}.</p>
     *
     * <h3>Primaries and white point chromaticities</h3>
     * <p>In this implementation, the chromaticity of the primaries and the white
     * point of an RGB color space is defined in the CIE xyY color space. This
     * color space separates the chromaticity of a color, the x and y components,
     * and its luminance, the Y component. Since the primaries and the white
     * point have full brightness, the Y component is assumed to be 1 and only
     * the x and y components are needed to encode them.</p>
     * <p>For convenience, this implementation also allows to define the
     * primaries and white point in the CIE XYZ space. The tristimulus XYZ values
     * are internally converted to xyY.</p>
     *
     * <p>
     *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_srgb.png" />
     *     <figcaption style="text-align: center;">sRGB primaries and white point</figcaption>
     * </p>
     *
     * <h3>Transfer functions</h3>
     * <p>A transfer function is a color component conversion function, defined as
     * a single variable, monotonic mathematical function. It is applied to each
     * individual component of a color. They are used to perform the mapping
     * between linear tristimulus values and non-linear electronic signal value.</p>
     * <p>The <em>opto-electronic transfer function</em> (OETF or OECF) encodes
     * tristimulus values in a scene to a non-linear electronic signal value.
     * An OETF is often expressed as a power function with an exponent between
     * 0.38 and 0.55 (the reciprocal of 1.8 to 2.6).</p>
     * <p>The <em>electro-optical transfer function</em> (EOTF or EOCF) decodes
     * a non-linear electronic signal value to a tristimulus value at the display.
     * An EOTF is often expressed as a power function with an exponent between
     * 1.8 and 2.6.</p>
     * <p>Transfer functions are used as a compression scheme. For instance,
     * linear sRGB values would normally require 11 to 12 bits of precision to
     * store all values that can be perceived by the human eye. When encoding
     * sRGB values using the appropriate OETF (see {@link Named#SRGB sRGB} for
     * an exact mathematical description of that OETF), the values can be
     * compressed to only 8 bits precision.</p>
     * <p>When manipulating RGB values, particularly sRGB values, it is safe
     * to assume that these values have been encoded with the appropriate
     * OETF (unless noted otherwise). Encoded values are often said to be in
     * "gamma space". They are therefore defined in a non-linear space. This
     * in turns means that any linear operation applied to these values is
     * going to yield mathematically incorrect results (any linear interpolation
     * such as gradient generation for instance, most image processing functions
     * such as blurs, etc.).</p>
     * <p>To properly process encoded RGB values you must first apply the
     * EOTF to decode the value into linear space. After processing, the RGB
     * value must be encoded back to non-linear ("gamma") space. Here is a
     * formal description of the process, where \(f\) is the processing
     * function to apply:</p>
     *
     * $$RGB_{out} = OETF(f(EOTF(RGB_{in})))$$
     *
     * <p>If the transfer functions of the color space can be expressed as an
     * ICC parametric curve as defined in ICC.1:2004-10, the numeric parameters
     * can be retrieved by calling {@link #getTransferParameters()}. This can
     * be useful to match color spaces for instance.</p>
     *
     * <p class="note">Some RGB color spaces, such as {@link Named#ACES} and
     * {@link Named#LINEAR_EXTENDED_SRGB scRGB}, are said to be linear because
     * their transfer functions are the identity function: \(f(x) = x\).
     * If the source and/or destination are known to be linear, it is not
     * necessary to invoke the transfer functions.</p>
     *
     * <h3>Range</h3>
     * <p>Most RGB color spaces allow RGB values in the range \([0..1]\). There
     * are however a few RGB color spaces that allow much larger ranges. For
     * instance, {@link Named#EXTENDED_SRGB scRGB} is used to manipulate the
     * range \([-0.5..7.5]\) while {@link Named#ACES ACES} can be used throughout
     * the range \([-65504, 65504]\).</p>
     *
     * <p>
     *     <img style="display: block; margin: 0 auto;" src="{@docRoot}reference/android/images/graphics/colorspace_scrgb.png" />
     *     <figcaption style="text-align: center;">Extended sRGB and its large range</figcaption>
     * </p>
     *
     * <h3>Converting between RGB color spaces</h3>
     * <p>Conversion between two color spaces is achieved by using an intermediate
     * color space called the profile connection space (PCS). The PCS used by
     * this implementation is CIE XYZ. The conversion operation is defined
     * as such:</p>
     *
     * $$RGB_{out} = OETF(T_{dst}^{-1} \cdot T_{src} \cdot EOTF(RGB_{in}))$$
     *
     * <p>Where \(T_{src}\) is the {@link #getTransform() RGB to XYZ transform}
     * of the source color space and \(T_{dst}^{-1}\) the {@link #getInverseTransform()
     * XYZ to RGB transform} of the destination color space.</p>
     * <p>Many RGB color spaces commonly used with electronic devices use the
     * standard illuminant {@link #ILLUMINANT_D65 D65}. Care must be take however
     * when converting between two RGB color spaces if their white points do not
     * match. This can be achieved by either calling
     * {@link #adapt(ColorSpace, float[])} to adapt one or both color spaces to
     * a single common white point. This can be achieved automatically by calling
     * {@link ColorSpace#connect(ColorSpace, ColorSpace)}, which also handles
     * non-RGB color spaces.</p>
     * <p>To learn more about the white point adaptation process, refer to the
     * documentation of {@link Adaptation}.</p>
     */
    @AnyThread
    public static class Rgb extends ColorSpace {
        /**
         * {@usesMathJax}
         *
         * <p>Defines the parameters for the ICC parametric curve type 4, as
         * defined in ICC.1:2004-10, section 10.15.</p>
         *
         * <p>The EOTF is of the form:</p>
         *
         * \(\begin{equation}
         * Y = \begin{cases}c X + f & X \lt d \\\
         * \left( a X + b \right) ^{g} + e & X \ge d \end{cases}
         * \end{equation}\)
         *
         * <p>The corresponding OETF is simply the inverse function.</p>
         *
         * <p>The parameters defined by this class form a valid transfer
         * function only if all the following conditions are met:</p>
         * <ul>
         *     <li>No parameter is a {@link Double#isNaN(double) Not-a-Number}</li>
         *     <li>\(d\) is in the range \([0..1]\)</li>
         *     <li>The function is not constant</li>
         *     <li>The function is positive and increasing</li>
         * </ul>
         */
        public static class TransferParameters {
            /** Variable \(a\) in the equation of the EOTF described above. */
            public final double a;
            /** Variable \(b\) in the equation of the EOTF described above. */
            public final double b;
            /** Variable \(c\) in the equation of the EOTF described above. */
            public final double c;
            /** Variable \(d\) in the equation of the EOTF described above. */
            public final double d;
            /** Variable \(e\) in the equation of the EOTF described above. */
            public final double e;
            /** Variable \(f\) in the equation of the EOTF described above. */
            public final double f;
            /** Variable \(g\) in the equation of the EOTF described above. */
            public final double g;

            /**
             * <p>Defines the parameters for the ICC parametric curve type 3, as
             * defined in ICC.1:2004-10, section 10.15.</p>
             *
             * <p>The EOTF is of the form:</p>
             *
             * \(\begin{equation}
             * Y = \begin{cases}c X & X \lt d \\\
             * \left( a X + b \right) ^{g} & X \ge d \end{cases}
             * \end{equation}\)
             *
             * <p>This constructor is equivalent to setting  \(e\) and \(f\) to 0.</p>
             *
             * @param a The value of \(a\) in the equation of the EOTF described above
             * @param b The value of \(b\) in the equation of the EOTF described above
             * @param c The value of \(c\) in the equation of the EOTF described above
             * @param d The value of \(d\) in the equation of the EOTF described above
             * @param g The value of \(g\) in the equation of the EOTF described above
             *
             * @throws IllegalArgumentException If the parameters form an invalid transfer function
             */
            public TransferParameters(double a, double b, double c, double d, double g) {
                this(a, b, c, d, 0.0, 0.0, g);
            }

            /**
             * <p>Defines the parameters for the ICC parametric curve type 4, as
             * defined in ICC.1:2004-10, section 10.15.</p>
             *
             * @param a The value of \(a\) in the equation of the EOTF described above
             * @param b The value of \(b\) in the equation of the EOTF described above
             * @param c The value of \(c\) in the equation of the EOTF described above
             * @param d The value of \(d\) in the equation of the EOTF described above
             * @param e The value of \(e\) in the equation of the EOTF described above
             * @param f The value of \(f\) in the equation of the EOTF described above
             * @param g The value of \(g\) in the equation of the EOTF described above
             *
             * @throws IllegalArgumentException If the parameters form an invalid transfer function
             */
            public TransferParameters(double a, double b, double c, double d, double e,
                    double f, double g) {

                if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) ||
                        Double.isNaN(d) || Double.isNaN(e) || Double.isNaN(f) ||
                        Double.isNaN(g)) {
                    throw new IllegalArgumentException("Parameters cannot be NaN");
                }

                // Next representable float after 1.0
                // We use doubles here but the representation inside our native code is often floats
                if (!(d >= 0.0 && d <= 1.0f + Math.ulp(1.0f))) {
                    throw new IllegalArgumentException("Parameter d must be in the range [0..1], " +
                            "was " + d);
                }

                if (d == 0.0 && (a == 0.0 || g == 0.0)) {
                    throw new IllegalArgumentException(
                            "Parameter a or g is zero, the transfer function is constant");
                }

                if (d >= 1.0 && c == 0.0) {
                    throw new IllegalArgumentException(
                            "Parameter c is zero, the transfer function is constant");
                }

                if ((a == 0.0 || g == 0.0) && c == 0.0) {
                    throw new IllegalArgumentException("Parameter a or g is zero," +
                            " and c is zero, the transfer function is constant");
                }

                if (c < 0.0) {
                    throw new IllegalArgumentException("The transfer function must be increasing");
                }

                if (a < 0.0 || g < 0.0) {
                    throw new IllegalArgumentException("The transfer function must be " +
                            "positive or increasing");
                }

                this.a = a;
                this.b = b;
                this.c = c;
                this.d = d;
                this.e = e;
                this.f = f;
                this.g = g;
            }

            @SuppressWarnings("SimplifiableIfStatement")
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                TransferParameters that = (TransferParameters) o;

                if (Double.compare(that.a, a) != 0) return false;
                if (Double.compare(that.b, b) != 0) return false;
                if (Double.compare(that.c, c) != 0) return false;
                if (Double.compare(that.d, d) != 0) return false;
                if (Double.compare(that.e, e) != 0) return false;
                if (Double.compare(that.f, f) != 0) return false;
                return Double.compare(that.g, g) == 0;
            }

            @Override
            public int hashCode() {
                int result;
                long temp;
                temp = Double.doubleToLongBits(a);
                result = (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(b);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(c);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(d);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(e);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(f);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                temp = Double.doubleToLongBits(g);
                result = 31 * result + (int) (temp ^ (temp >>> 32));
                return result;
            }
        }

        @NonNull private final float[] mWhitePoint;
        @NonNull private final float[] mPrimaries;
        @NonNull private final float[] mTransform;
        @NonNull private final float[] mInverseTransform;

        @NonNull private final DoubleUnaryOperator mOetf;
        @NonNull private final DoubleUnaryOperator mEotf;
        @NonNull private final DoubleUnaryOperator mClampedOetf;
        @NonNull private final DoubleUnaryOperator mClampedEotf;

        private final float mMin;
        private final float mMax;

        private final boolean mIsWideGamut;
        private final boolean mIsSrgb;

        @Nullable private final TransferParameters mTransferParameters;
        private final long mNativePtr;

        @Override
        long getNativeInstance() {
            if (mNativePtr == 0) {
                // If this object has TransferParameters, it must have a native object.
                throw new IllegalArgumentException("ColorSpace must use an ICC "
                        + "parametric transfer function! used " + this);
            }
            return mNativePtr;
        }

        private static native long nativeGetNativeFinalizer();
        private static native long nativeCreate(float a, float b, float c, float d,
                float e, float f, float g, float[] xyz);

        /**
         * <p>Creates a new RGB color space using a 3x3 column-major transform matrix.
         * The transform matrix must convert from the RGB space to the profile connection
         * space CIE XYZ.</p>
         *
         * <p class="note">The range of the color space is imposed to be \([0..1]\).</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param toXYZ 3x3 column-major transform matrix from RGB to the profile
         *              connection space CIE XYZ as an array of 9 floats, cannot be null
         * @param oetf Opto-electronic transfer function, cannot be null
         * @param eotf Electro-optical transfer function, cannot be null
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The OETF is null or the EOTF is null.</li>
         *     <li>The minimum valid value is >= the maximum valid value.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(9) float[] toXYZ,
                @NonNull DoubleUnaryOperator oetf,
                @NonNull DoubleUnaryOperator eotf) {
            this(name, computePrimaries(toXYZ), computeWhitePoint(toXYZ), null,
                    oetf, eotf, 0.0f, 1.0f, null, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * <p class="note">The ID, areturned by {@link #getId()}, of an object created by
         * this constructor is always {@link #MIN_ID}.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param oetf Opto-electronic transfer function, cannot be null
         * @param eotf Electro-optical transfer function, cannot be null
         * @param min The minimum valid value in this color space's RGB range
         * @param max The maximum valid value in this color space's RGB range
         *
         * @throws IllegalArgumentException <p>If any of the following conditions is met:</p>
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>The OETF is null or the EOTF is null.</li>
         *     <li>The minimum valid value is >= the maximum valid value.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                @NonNull DoubleUnaryOperator oetf,
                @NonNull DoubleUnaryOperator eotf,
                float min,
                float max) {
            this(name, primaries, whitePoint, null, oetf, eotf, min, max, null, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a 3x3 column-major transform matrix.
         * The transform matrix must convert from the RGB space to the profile connection
         * space CIE XYZ.</p>
         *
         * <p class="note">The range of the color space is imposed to be \([0..1]\).</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param toXYZ 3x3 column-major transform matrix from RGB to the profile
         *              connection space CIE XYZ as an array of 9 floats, cannot be null
         * @param function Parameters for the transfer functions
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>Gamma is negative.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(9) float[] toXYZ,
                @NonNull TransferParameters function) {
            // Note: when isGray() returns false, this passes null for the transform for
            // consistency with other constructors, which compute the transform from the primaries
            // and white point.
            this(name, isGray(toXYZ) ? GRAY_PRIMARIES : computePrimaries(toXYZ),
                    computeWhitePoint(toXYZ), isGray(toXYZ) ? toXYZ : null, function, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param function Parameters for the transfer functions
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>The transfer parameters are invalid.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                @NonNull TransferParameters function) {
            this(name, primaries, whitePoint, null, function, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param transform Computed transform matrix that converts from RGB to XYZ, or
         *      {@code null} to compute it from {@code primaries} and {@code whitePoint}.
         * @param function Parameters for the transfer functions
         * @param id ID of this color space as an integer between {@link #MIN_ID} and {@link #MAX_ID}
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>The ID is not between {@link #MIN_ID} and {@link #MAX_ID}.</li>
         *     <li>The transfer parameters are invalid.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        private Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                @Nullable @Size(9) float[] transform,
                @NonNull TransferParameters function,
                @IntRange(from = MIN_ID, to = MAX_ID) int id) {
            this(name, primaries, whitePoint, transform,
                    function.e == 0.0 && function.f == 0.0 ?
                            x -> rcpResponse(x, function.a, function.b,
                                    function.c, function.d, function.g) :
                            x -> rcpResponse(x, function.a, function.b, function.c,
                                    function.d, function.e, function.f, function.g),
                    function.e == 0.0 && function.f == 0.0 ?
                            x -> response(x, function.a, function.b,
                                    function.c, function.d, function.g) :
                            x -> response(x, function.a, function.b, function.c,
                                    function.d, function.e, function.f, function.g),
                    0.0f, 1.0f, function, id);
        }

        /**
         * <p>Creates a new RGB color space using a 3x3 column-major transform matrix.
         * The transform matrix must convert from the RGB space to the profile connection
         * space CIE XYZ.</p>
         *
         * <p class="note">The range of the color space is imposed to be \([0..1]\).</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param toXYZ 3x3 column-major transform matrix from RGB to the profile
         *              connection space CIE XYZ as an array of 9 floats, cannot be null
         * @param gamma Gamma to use as the transfer function
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>Gamma is negative.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(9) float[] toXYZ,
                double gamma) {
            this(name, computePrimaries(toXYZ), computeWhitePoint(toXYZ), gamma, 0.0f, 1.0f, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param gamma Gamma to use as the transfer function
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>Gamma is negative.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        public Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                double gamma) {
            this(name, primaries, whitePoint, gamma, 0.0f, 1.0f, MIN_ID);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param gamma Gamma to use as the transfer function
         * @param min The minimum valid value in this color space's RGB range
         * @param max The maximum valid value in this color space's RGB range
         * @param id ID of this color space as an integer between {@link #MIN_ID} and {@link #MAX_ID}
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>The minimum valid value is >= the maximum valid value.</li>
         *     <li>The ID is not between {@link #MIN_ID} and {@link #MAX_ID}.</li>
         *     <li>Gamma is negative.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        private Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                double gamma,
                float min,
                float max,
                @IntRange(from = MIN_ID, to = MAX_ID) int id) {
            this(name, primaries, whitePoint, null,
                    gamma == 1.0 ? DoubleUnaryOperator.identity() :
                            x -> Math.pow(x < 0.0 ? 0.0 : x, 1 / gamma),
                    gamma == 1.0 ? DoubleUnaryOperator.identity() :
                            x -> Math.pow(x < 0.0 ? 0.0 : x, gamma),
                    min, max, new TransferParameters(1.0, 0.0, 0.0, 0.0, gamma), id);
        }

        /**
         * <p>Creates a new RGB color space using a specified set of primaries
         * and a specified white point.</p>
         *
         * <p>The primaries and white point can be specified in the CIE xyY space
         * or in CIE XYZ. The length of the arrays depends on the chosen space:</p>
         *
         * <table summary="Parameters length">
         *     <tr><th>Space</th><th>Primaries length</th><th>White point length</th></tr>
         *     <tr><td>xyY</td><td>6</td><td>2</td></tr>
         *     <tr><td>XYZ</td><td>9</td><td>3</td></tr>
         * </table>
         *
         * <p>When the primaries and/or white point are specified in xyY, the Y component
         * does not need to be specified and is assumed to be 1.0. Only the xy components
         * are required.</p>
         *
         * @param name Name of the color space, cannot be null, its length must be >= 1
         * @param primaries RGB primaries as an array of 6 (xy) or 9 (XYZ) floats
         * @param whitePoint Reference white as an array of 2 (xy) or 3 (XYZ) floats
         * @param transform Computed transform matrix that converts from RGB to XYZ, or
         *      {@code null} to compute it from {@code primaries} and {@code whitePoint}.
         * @param oetf Opto-electronic transfer function, cannot be null
         * @param eotf Electro-optical transfer function, cannot be null
         * @param min The minimum valid value in this color space's RGB range
         * @param max The maximum valid value in this color space's RGB range
         * @param transferParameters Parameters for the transfer functions
         * @param id ID of this color space as an integer between {@link #MIN_ID} and {@link #MAX_ID}
         *
         * @throws IllegalArgumentException If any of the following conditions is met:
         * <ul>
         *     <li>The name is null or has a length of 0.</li>
         *     <li>The primaries array is null or has a length that is neither 6 or 9.</li>
         *     <li>The white point array is null or has a length that is neither 2 or 3.</li>
         *     <li>The OETF is null or the EOTF is null.</li>
         *     <li>The minimum valid value is >= the maximum valid value.</li>
         *     <li>The ID is not between {@link #MIN_ID} and {@link #MAX_ID}.</li>
         * </ul>
         *
         * @see #get(Named)
         */
        private Rgb(
                @NonNull @Size(min = 1) String name,
                @NonNull @Size(min = 6, max = 9) float[] primaries,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint,
                @Nullable @Size(9) float[] transform,
                @NonNull DoubleUnaryOperator oetf,
                @NonNull DoubleUnaryOperator eotf,
                float min,
                float max,
                @Nullable TransferParameters transferParameters,
                @IntRange(from = MIN_ID, to = MAX_ID) int id) {

            super(name, Model.RGB, id);

            if (primaries == null || (primaries.length != 6 && primaries.length != 9)) {
                throw new IllegalArgumentException("The color space's primaries must be " +
                        "defined as an array of 6 floats in xyY or 9 floats in XYZ");
            }

            if (whitePoint == null || (whitePoint.length != 2 && whitePoint.length != 3)) {
                throw new IllegalArgumentException("The color space's white point must be " +
                        "defined as an array of 2 floats in xyY or 3 float in XYZ");
            }

            if (oetf == null || eotf == null) {
                throw new IllegalArgumentException("The transfer functions of a color space " +
                        "cannot be null");
            }

            if (min >= max) {
                throw new IllegalArgumentException("Invalid range: min=" + min + ", max=" + max +
                        "; min must be strictly < max");
            }

            mWhitePoint = xyWhitePoint(whitePoint);
            mPrimaries =  xyPrimaries(primaries);

            if (transform == null) {
                mTransform = computeXYZMatrix(mPrimaries, mWhitePoint);
            } else {
                if (transform.length != 9) {
                    throw new IllegalArgumentException("Transform must have 9 entries! Has "
                            + transform.length);
                }
                mTransform = transform;
            }
            mInverseTransform = inverse3x3(mTransform);

            mOetf = oetf;
            mEotf = eotf;

            mMin = min;
            mMax = max;

            DoubleUnaryOperator clamp = this::clamp;
            mClampedOetf = oetf.andThen(clamp);
            mClampedEotf = clamp.andThen(eotf);

            mTransferParameters = transferParameters;

            // A color space is wide-gamut if its area is >90% of NTSC 1953 and
            // if it entirely contains the Color space definition in xyY
            mIsWideGamut = isWideGamut(mPrimaries, min, max);
            mIsSrgb = isSrgb(mPrimaries, mWhitePoint, oetf, eotf, min, max, id);

            if (mTransferParameters != null) {
                if (mWhitePoint == null || mTransform == null) {
                    throw new IllegalStateException(
                            "ColorSpace (" + this + ") cannot create native object! mWhitePoint: "
                            + mWhitePoint + " mTransform: " + mTransform);
                }

                // This mimics the old code that was in native.
                float[] nativeTransform = adaptToIlluminantD50(mWhitePoint, mTransform);
                mNativePtr = nativeCreate((float) mTransferParameters.a,
                                          (float) mTransferParameters.b,
                                          (float) mTransferParameters.c,
                                          (float) mTransferParameters.d,
                                          (float) mTransferParameters.e,
                                          (float) mTransferParameters.f,
                                          (float) mTransferParameters.g,
                                          nativeTransform);
                NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mNativePtr);
            } else {
                mNativePtr = 0;
            }
        }

        private static class NoImagePreloadHolder {
            public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                ColorSpace.Rgb.class.getClassLoader(), nativeGetNativeFinalizer(), 0);
        }

        /**
         * Creates a copy of the specified color space with a new transform.
         *
         * @param colorSpace The color space to create a copy of
         */
        private Rgb(Rgb colorSpace,
                @NonNull @Size(9) float[] transform,
                @NonNull @Size(min = 2, max = 3) float[] whitePoint) {
            this(colorSpace.getName(), colorSpace.mPrimaries, whitePoint, transform,
                    colorSpace.mOetf, colorSpace.mEotf, colorSpace.mMin, colorSpace.mMax,
                    colorSpace.mTransferParameters, MIN_ID);
        }

        /**
         * Copies the non-adapted CIE xyY white point of this color space in
         * specified array. The Y component is assumed to be 1 and is therefore
         * not copied into the destination. The x and y components are written
         * in the array at positions 0 and 1 respectively.
         *
         * @param whitePoint The destination array, cannot be null, its length
         *                   must be >= 2
         *
         * @return The destination array passed as a parameter
         *
         * @see #getWhitePoint()
         */
        @NonNull
        @Size(min = 2)
        public float[] getWhitePoint(@NonNull @Size(min = 2) float[] whitePoint) {
            whitePoint[0] = mWhitePoint[0];
            whitePoint[1] = mWhitePoint[1];
            return whitePoint;
        }

        /**
         * Returns the non-adapted CIE xyY white point of this color space as
         * a new array of 2 floats. The Y component is assumed to be 1 and is
         * therefore not copied into the destination. The x and y components
         * are written in the array at positions 0 and 1 respectively.
         *
         * @return A new non-null array of 2 floats
         *
         * @see #getWhitePoint(float[])
         */
        @NonNull
        @Size(2)
        public float[] getWhitePoint() {
            return Arrays.copyOf(mWhitePoint, mWhitePoint.length);
        }

        /**
         * Copies the primaries of this color space in specified array. The Y
         * component is assumed to be 1 and is therefore not copied into the
         * destination. The x and y components of the first primary are written
         * in the array at positions 0 and 1 respectively.
         *
         * <p>Note: Some ColorSpaces represent gray profiles. The concept of
         * primaries for such a ColorSpace does not make sense, so we use a special
         * set of primaries that are all 1s.</p>
         *
         * @param primaries The destination array, cannot be null, its length
         *                  must be >= 6
         *
         * @return The destination array passed as a parameter
         *
         * @see #getPrimaries()
         */
        @NonNull
        @Size(min = 6)
        public float[] getPrimaries(@NonNull @Size(min = 6) float[] primaries) {
            System.arraycopy(mPrimaries, 0, primaries, 0, mPrimaries.length);
            return primaries;
        }

        /**
         * Returns the primaries of this color space as a new array of 6 floats.
         * The Y component is assumed to be 1 and is therefore not copied into
         * the destination. The x and y components of the first primary are
         * written in the array at positions 0 and 1 respectively.
         *
         * <p>Note: Some ColorSpaces represent gray profiles. The concept of
         * primaries for such a ColorSpace does not make sense, so we use a special
         * set of primaries that are all 1s.</p>
         *
         * @return A new non-null array of 2 floats
         *
         * @see #getPrimaries(float[])
         */
        @NonNull
        @Size(6)
        public float[] getPrimaries() {
            return Arrays.copyOf(mPrimaries, mPrimaries.length);
        }

        /**
         * <p>Copies the transform of this color space in specified array. The
         * transform is used to convert from RGB to XYZ (with the same white
         * point as this color space). To connect color spaces, you must first
         * {@link ColorSpace#adapt(ColorSpace, float[]) adapt} them to the
         * same white point.</p>
         * <p>It is recommended to use {@link ColorSpace#connect(ColorSpace, ColorSpace)}
         * to convert between color spaces.</p>
         *
         * @param transform The destination array, cannot be null, its length
         *                  must be >= 9
         *
         * @return The destination array passed as a parameter
         *
         * @see #getTransform()
         */
        @NonNull
        @Size(min = 9)
        public float[] getTransform(@NonNull @Size(min = 9) float[] transform) {
            System.arraycopy(mTransform, 0, transform, 0, mTransform.length);
            return transform;
        }

        /**
         * <p>Returns the transform of this color space as a new array. The
         * transform is used to convert from RGB to XYZ (with the same white
         * point as this color space). To connect color spaces, you must first
         * {@link ColorSpace#adapt(ColorSpace, float[]) adapt} them to the
         * same white point.</p>
         * <p>It is recommended to use {@link ColorSpace#connect(ColorSpace, ColorSpace)}
         * to convert between color spaces.</p>
         *
         * @return A new array of 9 floats
         *
         * @see #getTransform(float[])
         */
        @NonNull
        @Size(9)
        public float[] getTransform() {
            return Arrays.copyOf(mTransform, mTransform.length);
        }

        /**
         * <p>Copies the inverse transform of this color space in specified array.
         * The inverse transform is used to convert from XYZ to RGB (with the
         * same white point as this color space). To connect color spaces, you
         * must first {@link ColorSpace#adapt(ColorSpace, float[]) adapt} them
         * to the same white point.</p>
         * <p>It is recommended to use {@link ColorSpace#connect(ColorSpace, ColorSpace)}
         * to convert between color spaces.</p>
         *
         * @param inverseTransform The destination array, cannot be null, its length
         *                  must be >= 9
         *
         * @return The destination array passed as a parameter
         *
         * @see #getInverseTransform()
         */
        @NonNull
        @Size(min = 9)
        public float[] getInverseTransform(@NonNull @Size(min = 9) float[] inverseTransform) {
            System.arraycopy(mInverseTransform, 0, inverseTransform, 0, mInverseTransform.length);
            return inverseTransform;
        }

        /**
         * <p>Returns the inverse transform of this color space as a new array.
         * The inverse transform is used to convert from XYZ to RGB (with the
         * same white point as this color space). To connect color spaces, you
         * must first {@link ColorSpace#adapt(ColorSpace, float[]) adapt} them
         * to the same white point.</p>
         * <p>It is recommended to use {@link ColorSpace#connect(ColorSpace, ColorSpace)}
         * to convert between color spaces.</p>
         *
         * @return A new array of 9 floats
         *
         * @see #getInverseTransform(float[])
         */
        @NonNull
        @Size(9)
        public float[] getInverseTransform() {
            return Arrays.copyOf(mInverseTransform, mInverseTransform.length);
        }

        /**
         * <p>Returns the opto-electronic transfer function (OETF) of this color space.
         * The inverse function is the electro-optical transfer function (EOTF) returned
         * by {@link #getEotf()}. These functions are defined to satisfy the following
         * equality for \(x \in [0..1]\):</p>
         *
         * $$OETF(EOTF(x)) = EOTF(OETF(x)) = x$$
         *
         * <p>For RGB colors, this function can be used to convert from linear space
         * to "gamma space" (gamma encoded). The terms gamma space and gamma encoded
         * are frequently used because many OETFs can be closely approximated using
         * a simple power function of the form \(x^{\frac{1}{\gamma}}\) (the
         * approximation of the {@link Named#SRGB sRGB} OETF uses \(\gamma=2.2\)
         * for instance).</p>
         *
         * @return A transfer function that converts from linear space to "gamma space"
         *
         * @see #getEotf()
         * @see #getTransferParameters()
         */
        @NonNull
        public DoubleUnaryOperator getOetf() {
            return mClampedOetf;
        }

        /**
         * <p>Returns the electro-optical transfer function (EOTF) of this color space.
         * The inverse function is the opto-electronic transfer function (OETF)
         * returned by {@link #getOetf()}. These functions are defined to satisfy the
         * following equality for \(x \in [0..1]\):</p>
         *
         * $$OETF(EOTF(x)) = EOTF(OETF(x)) = x$$
         *
         * <p>For RGB colors, this function can be used to convert from "gamma space"
         * (gamma encoded) to linear space. The terms gamma space and gamma encoded
         * are frequently used because many EOTFs can be closely approximated using
         * a simple power function of the form \(x^\gamma\) (the approximation of the
         * {@link Named#SRGB sRGB} EOTF uses \(\gamma=2.2\) for instance).</p>
         *
         * @return A transfer function that converts from "gamma space" to linear space
         *
         * @see #getOetf()
         * @see #getTransferParameters()
         */
        @NonNull
        public DoubleUnaryOperator getEotf() {
            return mClampedEotf;
        }

        /**
         * <p>Returns the parameters used by the {@link #getEotf() electro-optical}
         * and {@link #getOetf() opto-electronic} transfer functions. If the transfer
         * functions do not match the ICC parametric curves defined in ICC.1:2004-10
         * (section 10.15), this method returns null.</p>
         *
         * <p>See {@link TransferParameters} for a full description of the transfer
         * functions.</p>
         *
         * @return An instance of {@link TransferParameters} or null if this color
         *         space's transfer functions do not match the equation defined in
         *         {@link TransferParameters}
         */
        @Nullable
        public TransferParameters getTransferParameters() {
            return mTransferParameters;
        }

        @Override
        public boolean isSrgb() {
            return mIsSrgb;
        }

        @Override
        public boolean isWideGamut() {
            return mIsWideGamut;
        }

        @Override
        public float getMinValue(int component) {
            return mMin;
        }

        @Override
        public float getMaxValue(int component) {
            return mMax;
        }

        /**
         * <p>Decodes an RGB value to linear space. This is achieved by
         * applying this color space's electro-optical transfer function
         * to the supplied values.</p>
         *
         * <p>Refer to the documentation of {@link ColorSpace.Rgb} for
         * more information about transfer functions and their use for
         * encoding and decoding RGB values.</p>
         *
         * @param r The red component to decode to linear space
         * @param g The green component to decode to linear space
         * @param b The blue component to decode to linear space
         * @return A new array of 3 floats containing linear RGB values
         *
         * @see #toLinear(float[])
         * @see #fromLinear(float, float, float)
         */
        @NonNull
        @Size(3)
        public float[] toLinear(float r, float g, float b) {
            return toLinear(new float[] { r, g, b });
        }

        /**
         * <p>Decodes an RGB value to linear space. This is achieved by
         * applying this color space's electro-optical transfer function
         * to the first 3 values of the supplied array. The result is
         * stored back in the input array.</p>
         *
         * <p>Refer to the documentation of {@link ColorSpace.Rgb} for
         * more information about transfer functions and their use for
         * encoding and decoding RGB values.</p>
         *
         * @param v A non-null array of non-linear RGB values, its length
         *          must be at least 3
         * @return The specified array
         *
         * @see #toLinear(float, float, float)
         * @see #fromLinear(float[])
         */
        @NonNull
        @Size(min = 3)
        public float[] toLinear(@NonNull @Size(min = 3) float[] v) {
            v[0] = (float) mClampedEotf.applyAsDouble(v[0]);
            v[1] = (float) mClampedEotf.applyAsDouble(v[1]);
            v[2] = (float) mClampedEotf.applyAsDouble(v[2]);
            return v;
        }

        /**
         * <p>Encodes an RGB value from linear space to this color space's
         * "gamma space". This is achieved by applying this color space's
         * opto-electronic transfer function to the supplied values.</p>
         *
         * <p>Refer to the documentation of {@link ColorSpace.Rgb} for
         * more information about transfer functions and their use for
         * encoding and decoding RGB values.</p>
         *
         * @param r The red component to encode from linear space
         * @param g The green component to encode from linear space
         * @param b The blue component to encode from linear space
         * @return A new array of 3 floats containing non-linear RGB values
         *
         * @see #fromLinear(float[])
         * @see #toLinear(float, float, float)
         */
        @NonNull
        @Size(3)
        public float[] fromLinear(float r, float g, float b) {
            return fromLinear(new float[] { r, g, b });
        }

        /**
         * <p>Encodes an RGB value from linear space to this color space's
         * "gamma space". This is achieved by applying this color space's
         * opto-electronic transfer function to the first 3 values of the
         * supplied array. The result is stored back in the input array.</p>
         *
         * <p>Refer to the documentation of {@link ColorSpace.Rgb} for
         * more information about transfer functions and their use for
         * encoding and decoding RGB values.</p>
         *
         * @param v A non-null array of linear RGB values, its length
         *          must be at least 3
         * @return A new array of 3 floats containing non-linear RGB values
         *
         * @see #fromLinear(float[])
         * @see #toLinear(float, float, float)
         */
        @NonNull
        @Size(min = 3)
        public float[] fromLinear(@NonNull @Size(min = 3) float[] v) {
            v[0] = (float) mClampedOetf.applyAsDouble(v[0]);
            v[1] = (float) mClampedOetf.applyAsDouble(v[1]);
            v[2] = (float) mClampedOetf.applyAsDouble(v[2]);
            return v;
        }

        @Override
        @NonNull
        @Size(min = 3)
        public float[] toXyz(@NonNull @Size(min = 3) float[] v) {
            v[0] = (float) mClampedEotf.applyAsDouble(v[0]);
            v[1] = (float) mClampedEotf.applyAsDouble(v[1]);
            v[2] = (float) mClampedEotf.applyAsDouble(v[2]);
            return mul3x3Float3(mTransform, v);
        }

        @Override
        @NonNull
        @Size(min = 3)
        public float[] fromXyz(@NonNull @Size(min = 3) float[] v) {
            mul3x3Float3(mInverseTransform, v);
            v[0] = (float) mClampedOetf.applyAsDouble(v[0]);
            v[1] = (float) mClampedOetf.applyAsDouble(v[1]);
            v[2] = (float) mClampedOetf.applyAsDouble(v[2]);
            return v;
        }

        private double clamp(double x) {
            return x < mMin ? mMin : x > mMax ? mMax : x;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Rgb rgb = (Rgb) o;

            if (Float.compare(rgb.mMin, mMin) != 0) return false;
            if (Float.compare(rgb.mMax, mMax) != 0) return false;
            if (!Arrays.equals(mWhitePoint, rgb.mWhitePoint)) return false;
            if (!Arrays.equals(mPrimaries, rgb.mPrimaries)) return false;
            if (mTransferParameters != null) {
                return mTransferParameters.equals(rgb.mTransferParameters);
            } else if (rgb.mTransferParameters == null) {
                return true;
            }
            //noinspection SimplifiableIfStatement
            if (!mOetf.equals(rgb.mOetf)) return false;
            return mEotf.equals(rgb.mEotf);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + Arrays.hashCode(mWhitePoint);
            result = 31 * result + Arrays.hashCode(mPrimaries);
            result = 31 * result + (mMin != +0.0f ? Float.floatToIntBits(mMin) : 0);
            result = 31 * result + (mMax != +0.0f ? Float.floatToIntBits(mMax) : 0);
            result = 31 * result +
                    (mTransferParameters != null ? mTransferParameters.hashCode() : 0);
            if (mTransferParameters == null) {
                result = 31 * result + mOetf.hashCode();
                result = 31 * result + mEotf.hashCode();
            }
            return result;
        }

        /**
         * Computes whether a color space is the sRGB color space or at least
         * a close approximation.
         *
         * @param primaries The set of RGB primaries in xyY as an array of 6 floats
         * @param whitePoint The white point in xyY as an array of 2 floats
         * @param OETF The opto-electronic transfer function
         * @param EOTF The electro-optical transfer function
         * @param min The minimum value of the color space's range
         * @param max The minimum value of the color space's range
         * @param id The ID of the color space
         * @return True if the color space can be considered as the sRGB color space
         *
         * @see #isSrgb()
         */
        @SuppressWarnings("RedundantIfStatement")
        private static boolean isSrgb(
                @NonNull @Size(6) float[] primaries,
                @NonNull @Size(2) float[] whitePoint,
                @NonNull DoubleUnaryOperator OETF,
                @NonNull DoubleUnaryOperator EOTF,
                float min,
                float max,
                @IntRange(from = MIN_ID, to = MAX_ID) int id) {
            if (id == 0) return true;
            if (!ColorSpace.compare(primaries, SRGB_PRIMARIES)) {
                return false;
            }
            if (!ColorSpace.compare(whitePoint, ILLUMINANT_D65)) {
                return false;
            }

            if (min != 0.0f) return false;
            if (max != 1.0f) return false;

            // We would have already returned true if this was SRGB itself, so
            // it is safe to reference it here.
            ColorSpace.Rgb srgb = (ColorSpace.Rgb) get(Named.SRGB);

            for (double x = 0.0; x <= 1.0; x += 1 / 255.0) {
                if (!compare(x, OETF, srgb.mOetf)) return false;
                if (!compare(x, EOTF, srgb.mEotf)) return false;
            }

            return true;
        }

        /**
         * Report whether this matrix is a special gray matrix.
         * @param toXYZ A XYZD50 matrix. Skia uses a special form for a gray profile.
         * @return true if this is a special gray matrix.
         */
        private static boolean isGray(@NonNull @Size(9) float[] toXYZ) {
            return toXYZ.length == 9 && toXYZ[1] == 0 && toXYZ[2] == 0 && toXYZ[3] == 0
                    && toXYZ[5] == 0 && toXYZ[6] == 0 && toXYZ[7] == 0;
        }

        private static boolean compare(double point, @NonNull DoubleUnaryOperator a,
                @NonNull DoubleUnaryOperator b) {
            double rA = a.applyAsDouble(point);
            double rB = b.applyAsDouble(point);
            return Math.abs(rA - rB) <= 1e-3;
        }

        /**
         * Computes whether the specified CIE xyY or XYZ primaries (with Y set to 1) form
         * a wide color gamut. A color gamut is considered wide if its area is &gt; 90%
         * of the area of NTSC 1953 and if it contains the sRGB color gamut entirely.
         * If the conditions above are not met, the color space is considered as having
         * a wide color gamut if its range is larger than [0..1].
         *
         * @param primaries RGB primaries in CIE xyY as an array of 6 floats
         * @param min The minimum value of the color space's range
         * @param max The minimum value of the color space's range
         * @return True if the color space has a wide gamut, false otherwise
         *
         * @see #isWideGamut()
         * @see #area(float[])
         */
        private static boolean isWideGamut(@NonNull @Size(6) float[] primaries,
                float min, float max) {
            return (area(primaries) / area(NTSC_1953_PRIMARIES) > 0.9f &&
                            contains(primaries, SRGB_PRIMARIES)) || (min < 0.0f && max > 1.0f);
        }

        /**
         * Computes the area of the triangle represented by a set of RGB primaries
         * in the CIE xyY space.
         *
         * @param primaries The triangle's vertices, as RGB primaries in an array of 6 floats
         * @return The area of the triangle
         *
         * @see #isWideGamut(float[], float, float)
         */
        private static float area(@NonNull @Size(6) float[] primaries) {
            float Rx = primaries[0];
            float Ry = primaries[1];
            float Gx = primaries[2];
            float Gy = primaries[3];
            float Bx = primaries[4];
            float By = primaries[5];
            float det = Rx * Gy + Ry * Bx + Gx * By - Gy * Bx - Ry * Gx - Rx * By;
            float r = 0.5f * det;
            return r < 0.0f ? -r : r;
        }

        /**
         * Computes the cross product of two 2D vectors.
         *
         * @param ax The x coordinate of the first vector
         * @param ay The y coordinate of the first vector
         * @param bx The x coordinate of the second vector
         * @param by The y coordinate of the second vector
         * @return The result of a x b
         */
        private static float cross(float ax, float ay, float bx, float by) {
            return ax * by - ay * bx;
        }

        /**
         * Decides whether a 2D triangle, identified by the 6 coordinates of its
         * 3 vertices, is contained within another 2D triangle, also identified
         * by the 6 coordinates of its 3 vertices.
         *
         * In the illustration below, we want to test whether the RGB triangle
         * is contained within the triangle XYZ formed by the 3 vertices at
         * the "+" locations.
         *
         *                                     Y     .
         *                                 .   +    .
         *                                  .     ..
         *                                   .   .
         *                                    . .
         *                                     .  G
         *                                     *
         *                                    * *
         *                                  **   *
         *                                 *      **
         *                                *         *
         *                              **           *
         *                             *              *
         *                            *                *
         *                          **                  *
         *                         *                     *
         *                        *                       **
         *                      **                          *   R    ...
         *                     *                             *  .....
         *                    *                         ***** ..
         *                  **              ************       .   +
         *              B  *    ************                    .   X
         *           ......*****                                 .
         *     ......    .                                        .
         *             ..
         *        +   .
         *      Z    .
         *
         * RGB is contained within XYZ if all the following conditions are true
         * (with "x" the cross product operator):
         *
         *   -->  -->
         *   GR x RX >= 0
         *   -->  -->
         *   RX x BR >= 0
         *   -->  -->
         *   RG x GY >= 0
         *   -->  -->
         *   GY x RG >= 0
         *   -->  -->
         *   RB x BZ >= 0
         *   -->  -->
         *   BZ x GB >= 0
         *
         * @param p1 The enclosing triangle
         * @param p2 The enclosed triangle
         * @return True if the triangle p1 contains the triangle p2
         *
         * @see #isWideGamut(float[], float, float)
         */
        @SuppressWarnings("RedundantIfStatement")
        private static boolean contains(@NonNull @Size(6) float[] p1, @NonNull @Size(6) float[] p2) {
            // Translate the vertices p1 in the coordinates system
            // with the vertices p2 as the origin
            float[] p0 = new float[] {
                    p1[0] - p2[0], p1[1] - p2[1],
                    p1[2] - p2[2], p1[3] - p2[3],
                    p1[4] - p2[4], p1[5] - p2[5],
            };
            // Check the first vertex of p1
            if (cross(p0[0], p0[1], p2[0] - p2[4], p2[1] - p2[5]) < 0 ||
                    cross(p2[0] - p2[2], p2[1] - p2[3], p0[0], p0[1]) < 0) {
                return false;
            }
            // Check the second vertex of p1
            if (cross(p0[2], p0[3], p2[2] - p2[0], p2[3] - p2[1]) < 0 ||
                    cross(p2[2] - p2[4], p2[3] - p2[5], p0[2], p0[3]) < 0) {
                return false;
            }
            // Check the third vertex of p1
            if (cross(p0[4], p0[5], p2[4] - p2[2], p2[5] - p2[3]) < 0 ||
                    cross(p2[4] - p2[0], p2[5] - p2[1], p0[4], p0[5]) < 0) {
                return false;
            }
            return true;
        }

        /**
         * Computes the primaries  of a color space identified only by
         * its RGB->XYZ transform matrix. This method assumes that the
         * range of the color space is [0..1].
         *
         * @param toXYZ The color space's 3x3 transform matrix to XYZ
         * @return A new array of 6 floats containing the color space's
         *         primaries in CIE xyY
         */
        @NonNull
        @Size(6)
        private static float[] computePrimaries(@NonNull @Size(9) float[] toXYZ) {
            float[] r = mul3x3Float3(toXYZ, new float[] { 1.0f, 0.0f, 0.0f });
            float[] g = mul3x3Float3(toXYZ, new float[] { 0.0f, 1.0f, 0.0f });
            float[] b = mul3x3Float3(toXYZ, new float[] { 0.0f, 0.0f, 1.0f });

            float rSum = r[0] + r[1] + r[2];
            float gSum = g[0] + g[1] + g[2];
            float bSum = b[0] + b[1] + b[2];

            return new float[] {
                    r[0] / rSum, r[1] / rSum,
                    g[0] / gSum, g[1] / gSum,
                    b[0] / bSum, b[1] / bSum,
            };
        }

        /**
         * Computes the white point of a color space identified only by
         * its RGB->XYZ transform matrix. This method assumes that the
         * range of the color space is [0..1].
         *
         * @param toXYZ The color space's 3x3 transform matrix to XYZ
         * @return A new array of 2 floats containing the color space's
         *         white point in CIE xyY
         */
        @NonNull
        @Size(2)
        private static float[] computeWhitePoint(@NonNull @Size(9) float[] toXYZ) {
            float[] w = mul3x3Float3(toXYZ, new float[] { 1.0f, 1.0f, 1.0f });
            float sum = w[0] + w[1] + w[2];
            return new float[] { w[0] / sum, w[1] / sum };
        }

        /**
         * Converts the specified RGB primaries point to xyY if needed. The primaries
         * can be specified as an array of 6 floats (in CIE xyY) or 9 floats
         * (in CIE XYZ). If no conversion is needed, the input array is copied.
         *
         * @param primaries The primaries in xyY or XYZ
         * @return A new array of 6 floats containing the primaries in xyY
         */
        @NonNull
        @Size(6)
        private static float[] xyPrimaries(@NonNull @Size(min = 6, max = 9) float[] primaries) {
            float[] xyPrimaries = new float[6];

            // XYZ to xyY
            if (primaries.length == 9) {
                float sum;

                sum = primaries[0] + primaries[1] + primaries[2];
                xyPrimaries[0] = primaries[0] / sum;
                xyPrimaries[1] = primaries[1] / sum;

                sum = primaries[3] + primaries[4] + primaries[5];
                xyPrimaries[2] = primaries[3] / sum;
                xyPrimaries[3] = primaries[4] / sum;

                sum = primaries[6] + primaries[7] + primaries[8];
                xyPrimaries[4] = primaries[6] / sum;
                xyPrimaries[5] = primaries[7] / sum;
            } else {
                System.arraycopy(primaries, 0, xyPrimaries, 0, 6);
            }

            return xyPrimaries;
        }

        /**
         * Converts the specified white point to xyY if needed. The white point
         * can be specified as an array of 2 floats (in CIE xyY) or 3 floats
         * (in CIE XYZ). If no conversion is needed, the input array is copied.
         *
         * @param whitePoint The white point in xyY or XYZ
         * @return A new array of 2 floats containing the white point in xyY
         */
        @NonNull
        @Size(2)
        private static float[] xyWhitePoint(@Size(min = 2, max = 3) float[] whitePoint) {
            float[] xyWhitePoint = new float[2];

            // XYZ to xyY
            if (whitePoint.length == 3) {
                float sum = whitePoint[0] + whitePoint[1] + whitePoint[2];
                xyWhitePoint[0] = whitePoint[0] / sum;
                xyWhitePoint[1] = whitePoint[1] / sum;
            } else {
                System.arraycopy(whitePoint, 0, xyWhitePoint, 0, 2);
            }

            return xyWhitePoint;
        }

        /**
         * Computes the matrix that converts from RGB to XYZ based on RGB
         * primaries and a white point, both specified in the CIE xyY space.
         * The Y component of the primaries and white point is implied to be 1.
         *
         * @param primaries The RGB primaries in xyY, as an array of 6 floats
         * @param whitePoint The white point in xyY, as an array of 2 floats
         * @return A 3x3 matrix as a new array of 9 floats
         */
        @NonNull
        @Size(9)
        private static float[] computeXYZMatrix(
                @NonNull @Size(6) float[] primaries,
                @NonNull @Size(2) float[] whitePoint) {
            float Rx = primaries[0];
            float Ry = primaries[1];
            float Gx = primaries[2];
            float Gy = primaries[3];
            float Bx = primaries[4];
            float By = primaries[5];
            float Wx = whitePoint[0];
            float Wy = whitePoint[1];

            float oneRxRy = (1 - Rx) / Ry;
            float oneGxGy = (1 - Gx) / Gy;
            float oneBxBy = (1 - Bx) / By;
            float oneWxWy = (1 - Wx) / Wy;

            float RxRy = Rx / Ry;
            float GxGy = Gx / Gy;
            float BxBy = Bx / By;
            float WxWy = Wx / Wy;

            float BY =
                    ((oneWxWy - oneRxRy) * (GxGy - RxRy) - (WxWy - RxRy) * (oneGxGy - oneRxRy)) /
                    ((oneBxBy - oneRxRy) * (GxGy - RxRy) - (BxBy - RxRy) * (oneGxGy - oneRxRy));
            float GY = (WxWy - RxRy - BY * (BxBy - RxRy)) / (GxGy - RxRy);
            float RY = 1 - GY - BY;

            float RYRy = RY / Ry;
            float GYGy = GY / Gy;
            float BYBy = BY / By;

            return new float[] {
                    RYRy * Rx, RY, RYRy * (1 - Rx - Ry),
                    GYGy * Gx, GY, GYGy * (1 - Gx - Gy),
                    BYBy * Bx, BY, BYBy * (1 - Bx - By)
            };
        }
    }

    /**
     * {@usesMathJax}
     *
     * <p>A connector transforms colors from a source color space to a destination
     * color space.</p>
     *
     * <p>A source color space is connected to a destination color space using the
     * color transform \(C\) computed from their respective transforms noted
     * \(T_{src}\) and \(T_{dst}\) in the following equation:</p>
     *
     * $$C = T^{-1}_{dst} . T_{src}$$
     *
     * <p>The transform \(C\) shown above is only valid when the source and
     * destination color spaces have the same profile connection space (PCS).
     * We know that instances of {@link ColorSpace} always use CIE XYZ as their
     * PCS but their white points might differ. When they do, we must perform
     * a chromatic adaptation of the color spaces' transforms. To do so, we
     * use the von Kries method described in the documentation of {@link Adaptation},
     * using the CIE standard illuminant {@link ColorSpace#ILLUMINANT_D50 D50}
     * as the target white point.</p>
     *
     * <p>Example of conversion from {@link Named#SRGB sRGB} to
     * {@link Named#DCI_P3 DCI-P3}:</p>
     *
     * <pre class="prettyprint">
     * ColorSpace.Connector connector = ColorSpace.connect(
     *         ColorSpace.get(ColorSpace.Named.SRGB),
     *         ColorSpace.get(ColorSpace.Named.DCI_P3));
     * float[] p3 = connector.transform(1.0f, 0.0f, 0.0f);
     * // p3 contains { 0.9473, 0.2740, 0.2076 }
     * </pre>
     *
     * @see Adaptation
     * @see ColorSpace#adapt(ColorSpace, float[], Adaptation)
     * @see ColorSpace#adapt(ColorSpace, float[])
     * @see ColorSpace#connect(ColorSpace, ColorSpace, RenderIntent)
     * @see ColorSpace#connect(ColorSpace, ColorSpace)
     * @see ColorSpace#connect(ColorSpace, RenderIntent)
     * @see ColorSpace#connect(ColorSpace)
     */
    @AnyThread
    public static class Connector {
        @NonNull private final ColorSpace mSource;
        @NonNull private final ColorSpace mDestination;
        @NonNull private final ColorSpace mTransformSource;
        @NonNull private final ColorSpace mTransformDestination;
        @NonNull private final RenderIntent mIntent;
        @NonNull @Size(3) private final float[] mTransform;

        /**
         * Creates a new connector between a source and a destination color space.
         *
         * @param source The source color space, cannot be null
         * @param destination The destination color space, cannot be null
         * @param intent The render intent to use when compressing gamuts
         */
        Connector(@NonNull ColorSpace source, @NonNull ColorSpace destination,
                @NonNull RenderIntent intent) {
            this(source, destination,
                    source.getModel() == Model.RGB ? adapt(source, ILLUMINANT_D50_XYZ) : source,
                    destination.getModel() == Model.RGB ?
                            adapt(destination, ILLUMINANT_D50_XYZ) : destination,
                    intent, computeTransform(source, destination, intent));
        }

        /**
         * To connect between color spaces, we might need to use adapted transforms.
         * This should be transparent to the user so this constructor takes the
         * original source and destinations (returned by the getters), as well as
         * possibly adapted color spaces used by transform().
         */
        private Connector(
                @NonNull ColorSpace source, @NonNull ColorSpace destination,
                @NonNull ColorSpace transformSource, @NonNull ColorSpace transformDestination,
                @NonNull RenderIntent intent, @Nullable @Size(3) float[] transform) {
            mSource = source;
            mDestination = destination;
            mTransformSource = transformSource;
            mTransformDestination = transformDestination;
            mIntent = intent;
            mTransform = transform;
        }

        /**
         * Computes an extra transform to apply in XYZ space depending on the
         * selected rendering intent.
         */
        @Nullable
        private static float[] computeTransform(@NonNull ColorSpace source,
                @NonNull ColorSpace destination, @NonNull RenderIntent intent) {
            if (intent != RenderIntent.ABSOLUTE) return null;

            boolean srcRGB = source.getModel() == Model.RGB;
            boolean dstRGB = destination.getModel() == Model.RGB;

            if (srcRGB && dstRGB) return null;

            if (srcRGB || dstRGB) {
                ColorSpace.Rgb rgb = (ColorSpace.Rgb) (srcRGB ? source : destination);
                float[] srcXYZ = srcRGB ? xyYToXyz(rgb.mWhitePoint) : ILLUMINANT_D50_XYZ;
                float[] dstXYZ = dstRGB ? xyYToXyz(rgb.mWhitePoint) : ILLUMINANT_D50_XYZ;
                return new float[] {
                        srcXYZ[0] / dstXYZ[0],
                        srcXYZ[1] / dstXYZ[1],
                        srcXYZ[2] / dstXYZ[2],
                };
            }

            return null;
        }

        /**
         * Returns the source color space this connector will convert from.
         *
         * @return A non-null instance of {@link ColorSpace}
         *
         * @see #getDestination()
         */
        @NonNull
        public ColorSpace getSource() {
            return mSource;
        }

        /**
         * Returns the destination color space this connector will convert to.
         *
         * @return A non-null instance of {@link ColorSpace}
         *
         * @see #getSource()
         */
        @NonNull
        public ColorSpace getDestination() {
            return mDestination;
        }

        /**
         * Returns the render intent this connector will use when mapping the
         * source color space to the destination color space.
         *
         * @return A non-null {@link RenderIntent}
         *
         * @see RenderIntent
         */
        public RenderIntent getRenderIntent() {
            return mIntent;
        }

        /**
         * <p>Transforms the specified color from the source color space
         * to a color in the destination color space. This convenience
         * method assumes a source color model with 3 components
         * (typically RGB). To transform from color models with more than
         * 3 components, such as {@link Model#CMYK CMYK}, use
         * {@link #transform(float[])} instead.</p>
         *
         * @param r The red component of the color to transform
         * @param g The green component of the color to transform
         * @param b The blue component of the color to transform
         * @return A new array of 3 floats containing the specified color
         *         transformed from the source space to the destination space
         *
         * @see #transform(float[])
         */
        @NonNull
        @Size(3)
        public float[] transform(float r, float g, float b) {
            return transform(new float[] { r, g, b });
        }

        /**
         * <p>Transforms the specified color from the source color space
         * to a color in the destination color space.</p>
         *
         * @param v A non-null array of 3 floats containing the value to transform
         *            and that will hold the result of the transform
         * @return The v array passed as a parameter, containing the specified color
         *         transformed from the source space to the destination space
         *
         * @see #transform(float, float, float)
         */
        @NonNull
        @Size(min = 3)
        public float[] transform(@NonNull @Size(min = 3) float[] v) {
            float[] xyz = mTransformSource.toXyz(v);
            if (mTransform != null) {
                xyz[0] *= mTransform[0];
                xyz[1] *= mTransform[1];
                xyz[2] *= mTransform[2];
            }
            return mTransformDestination.fromXyz(xyz);
        }

        /**
         * Optimized connector for RGB->RGB conversions.
         */
        private static class Rgb extends Connector {
            @NonNull private final ColorSpace.Rgb mSource;
            @NonNull private final ColorSpace.Rgb mDestination;
            @NonNull private final float[] mTransform;

            Rgb(@NonNull ColorSpace.Rgb source, @NonNull ColorSpace.Rgb destination,
                    @NonNull RenderIntent intent) {
                super(source, destination, source, destination, intent, null);
                mSource = source;
                mDestination = destination;
                mTransform = computeTransform(source, destination, intent);
            }

            @Override
            public float[] transform(@NonNull @Size(min = 3) float[] rgb) {
                rgb[0] = (float) mSource.mClampedEotf.applyAsDouble(rgb[0]);
                rgb[1] = (float) mSource.mClampedEotf.applyAsDouble(rgb[1]);
                rgb[2] = (float) mSource.mClampedEotf.applyAsDouble(rgb[2]);
                mul3x3Float3(mTransform, rgb);
                rgb[0] = (float) mDestination.mClampedOetf.applyAsDouble(rgb[0]);
                rgb[1] = (float) mDestination.mClampedOetf.applyAsDouble(rgb[1]);
                rgb[2] = (float) mDestination.mClampedOetf.applyAsDouble(rgb[2]);
                return rgb;
            }

            /**
             * <p>Computes the color transform that connects two RGB color spaces.</p>
             *
             * <p>We can only connect color spaces if they use the same profile
             * connection space. We assume the connection space is always
             * CIE XYZ but we maye need to perform a chromatic adaptation to
             * match the white points. If an adaptation is needed, we use the
             * CIE standard illuminant D50. The unmatched color space is adapted
             * using the von Kries transform and the {@link Adaptation#BRADFORD}
             * matrix.</p>
             *
             * @param source The source color space, cannot be null
             * @param destination The destination color space, cannot be null
             * @param intent The render intent to use when compressing gamuts
             * @return An array of 9 floats containing the 3x3 matrix transform
             */
            @NonNull
            @Size(9)
            private static float[] computeTransform(
                    @NonNull ColorSpace.Rgb source,
                    @NonNull ColorSpace.Rgb destination,
                    @NonNull RenderIntent intent) {
                if (compare(source.mWhitePoint, destination.mWhitePoint)) {
                    // RGB->RGB using the PCS of both color spaces since they have the same
                    return mul3x3(destination.mInverseTransform, source.mTransform);
                } else {
                    // RGB->RGB using CIE XYZ D50 as the PCS
                    float[] transform = source.mTransform;
                    float[] inverseTransform = destination.mInverseTransform;

                    float[] srcXYZ = xyYToXyz(source.mWhitePoint);
                    float[] dstXYZ = xyYToXyz(destination.mWhitePoint);

                    if (!compare(source.mWhitePoint, ILLUMINANT_D50)) {
                        float[] srcAdaptation = chromaticAdaptation(
                                Adaptation.BRADFORD.mTransform, srcXYZ,
                                Arrays.copyOf(ILLUMINANT_D50_XYZ, 3));
                        transform = mul3x3(srcAdaptation, source.mTransform);
                    }

                    if (!compare(destination.mWhitePoint, ILLUMINANT_D50)) {
                        float[] dstAdaptation = chromaticAdaptation(
                                Adaptation.BRADFORD.mTransform, dstXYZ,
                                Arrays.copyOf(ILLUMINANT_D50_XYZ, 3));
                        inverseTransform = inverse3x3(mul3x3(dstAdaptation, destination.mTransform));
                    }

                    if (intent == RenderIntent.ABSOLUTE) {
                        transform = mul3x3Diag(
                                new float[] {
                                        srcXYZ[0] / dstXYZ[0],
                                        srcXYZ[1] / dstXYZ[1],
                                        srcXYZ[2] / dstXYZ[2],
                                }, transform);
                    }

                    return mul3x3(inverseTransform, transform);
                }
            }
        }

        /**
         * Returns the identity connector for a given color space.
         *
         * @param source The source and destination color space
         * @return A non-null connector that does not perform any transform
         *
         * @see ColorSpace#connect(ColorSpace, ColorSpace)
         */
        static Connector identity(ColorSpace source) {
            return new Connector(source, source, RenderIntent.RELATIVE) {
                @Override
                public float[] transform(@NonNull @Size(min = 3) float[] v) {
                    return v;
                }
            };
        }
    }
}
