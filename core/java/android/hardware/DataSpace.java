/*
 * Copyright 2021 The Android Open Source Project
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
package android.hardware;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * DataSpace identifies three components of colors - standard (primaries), transfer and range.
 *
 * <p>A DataSpace describes how buffer data, such as from an {@link android.media.Image Image}
 * or a {@link android.hardware.HardwareBuffer HardwareBuffer}
 * should be interpreted by both applications and typical hardware.</p>
 *
 * <p>As buffer information is not guaranteed to be representative of color information,
 * while DataSpace is typically used to describe three aspects of interpreting colors,
 * some DataSpaces may describe other typical interpretations of buffer data
 * such as depth information.</p>
 *
 * <p>Note that while {@link android.graphics.ColorSpace ColorSpace} and {@code DataSpace}
 * are similar concepts, they are not equivalent. Not all ColorSpaces,
 * such as {@link android.graphics.ColorSpace.Named#ACES ColorSpace.Named.ACES},
 * are able to be understood by typical hardware blocks so they cannot be DataSpaces.</p>
 *
 * <h3>Standard aspect</h3>
 *
 * <p>Defines the chromaticity coordinates of the source primaries in terms of
 * the CIE 1931 definition of x and y specified in ISO 11664-1.</p>
 *
 * <h3>Transfer aspect</h3>
 *
 * <p>Transfer characteristics are the opto-electronic transfer characteristic
 * at the source as a function of linear optical intensity (luminance).</p>
 *
 * <p>For digital signals, E corresponds to the recorded value. Normally, the
 * transfer function is applied in RGB space to each of the R, G and B
 * components independently. This may result in color shift that can be
 * minized by applying the transfer function in Lab space only for the L
 * component. Implementation may apply the transfer function in RGB space
 * for all pixel formats if desired.</p>
 *
 * <h3>Range aspect</h3>
 *
 * <p>Defines the range of values corresponding to the unit range of {@code 0-1}.</p>
 */

public final class DataSpace {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
        STANDARD_UNSPECIFIED,
        STANDARD_BT709,
        STANDARD_BT601_625,
        STANDARD_BT601_625_UNADJUSTED,
        STANDARD_BT601_525,
        STANDARD_BT601_525_UNADJUSTED,
        STANDARD_BT2020,
        STANDARD_BT2020_CONSTANT_LUMINANCE,
        STANDARD_BT470M,
        STANDARD_FILM,
        STANDARD_DCI_P3,
        STANDARD_ADOBE_RGB
    })
    public @interface DataSpaceStandard {};

    private static final int STANDARD_MASK = 63 << 16;

    /**
     * Chromacity coordinates are unknown or are determined by the application.
     */
    public static final int STANDARD_UNSPECIFIED  = 0 << 16;
    /**
     * Use the unadjusted {@code KR = 0.2126}, {@code KB = 0.0722} luminance interpretation
     * for RGB conversion.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.300   0.600
     *  blue            0.150   0.060
     *  red             0.640   0.330
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT709 = 1 << 16;
    /**
     * Use the adjusted {@code KR = 0.299}, {@code KB = 0.114} luminance interpretation
     * for RGB conversion from the one purely determined by the primaries
     * to minimize the color shift into RGB space that uses BT.709
     * primaries.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.290   0.600
     *  blue            0.150   0.060
     *  red             0.640   0.330
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT601_625 = 2 << 16;
    /**
     * Use the unadjusted {@code KR = 0.222}, {@code KB = 0.071} luminance interpretation
     * for RGB conversion.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.290   0.600
     *  blue            0.150   0.060
     *  red             0.640   0.330
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT601_625_UNADJUSTED = 3 << 16;
    /**
     * Use the adjusted {@code KR = 0.299}, {@code KB = 0.114} luminance interpretation
     * for RGB conversion from the one purely determined by the primaries
     * to minimize the color shift into RGB space that uses BT.709
     * primaries.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.310   0.595
     *  blue            0.155   0.070
     *  red             0.630   0.340
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT601_525 = 4 << 16;
    /**
     * Use the unadjusted {@code KR = 0.212}, {@code KB = 0.087} luminance interpretation
     * for RGB conversion (as in SMPTE 240M).
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.310   0.595
     *  blue            0.155   0.070
     *  red             0.630   0.340
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT601_525_UNADJUSTED = 5 << 16;
    /**
     * Use the unadjusted {@code KR = 0.2627}, {@code KB = 0.0593} luminance interpretation
     * for RGB conversion.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.170   0.797
     *  blue            0.131   0.046
     *  red             0.708   0.292
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT2020 = 6 << 16;
    /**
     * Use the unadjusted {@code KR = 0.2627}, {@code KB = 0.0593} luminance interpretation
     * for RGB conversion using the linear domain.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.170   0.797
     *  blue            0.131   0.046
     *  red             0.708   0.292
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_BT2020_CONSTANT_LUMINANCE = 7 << 16;
    /**
     * Use the unadjusted {@code KR = 0.30}, {@code KB = 0.11} luminance interpretation
     * for RGB conversion.
     *
     * <pre>
     * Primaries:       x      y
     *  green           0.21   0.71
     *  blue            0.14   0.08
     *  red             0.67   0.33
     *  white (C)       0.310  0.316 </pre>
     */
    public static final int STANDARD_BT470M = 8 << 16;
    /**
     * Use the unadjusted {@code KR = 0.254}, {@code KB = 0.068} luminance interpretation
     * for RGB conversion.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.243   0.692
     *  blue            0.145   0.049
     *  red             0.681   0.319
     *  white (C)       0.310   0.316 </pre>
     */
    public static final int STANDARD_FILM = 9 << 16;
    /**
     * SMPTE EG 432-1 and SMPTE RP 431-2.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.265   0.690
     *  blue            0.150   0.060
     *  red             0.680   0.320
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_DCI_P3 = 10 << 16;
    /**
     * Adobe RGB primaries.
     *
     * <pre>
     * Primaries:       x       y
     *  green           0.210   0.710
     *  blue            0.150   0.060
     *  red             0.640   0.330
     *  white (D65)     0.3127  0.3290 </pre>
     */
    public static final int STANDARD_ADOBE_RGB = 11 << 16;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
        TRANSFER_UNSPECIFIED,
        TRANSFER_LINEAR,
        TRANSFER_SRGB,
        TRANSFER_SMPTE_170M,
        TRANSFER_GAMMA2_2,
        TRANSFER_GAMMA2_6,
        TRANSFER_GAMMA2_8,
        TRANSFER_ST2084,
        TRANSFER_HLG
    })
    public @interface DataSpaceTransfer {};

    private static final int TRANSFER_MASK = 31 << 22;

    /**
     * Transfer characteristics are unknown or are determined by the
     * application.
     */
    public static final int TRANSFER_UNSPECIFIED = 0 << 22;
    /**
     * Linear transfer.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     *  E = L
     *      L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *      E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_LINEAR = 1 << 22;
    /**
     * sRGB transfer.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = 1.055 * L^(1/2.4) - 0.055  for 0.0031308 <= L <= 1
     *   = 12.92 * L                  for 0 <= L < 0.0031308
     *     L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *     E - corresponding electrical signal}</pre>
     *
     * Use for RGB formats.
     */
    public static final int TRANSFER_SRGB = 2 << 22;
    /**
     * SMPTE 170M transfer.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = 1.099 * L ^ 0.45 - 0.099  for 0.018 <= L <= 1
     *   = 4.500 * L                 for 0 <= L < 0.018
     *     L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *     E - corresponding electrical signal}</pre>
     *
     * Use for YCbCr formats.
     */
    public static final int TRANSFER_SMPTE_170M = 3 << 22;
    /**
     * Display gamma 2.2.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = L ^ (1/2.2)
     *     L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *     E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_GAMMA2_2 = 4 << 22;
    /**
     *  Display gamma 2.6.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = L ^ (1/2.6)
     *     L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *     E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_GAMMA2_6 = 5 << 22;
    /**
     *  Display gamma 2.8.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = L ^ (1/2.8)
     *     L - luminance of image 0 <= L <= 1 for conventional colorimetry
     *     E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_GAMMA2_8 = 6 << 22;
    /**
     * SMPTE ST 2084 (Dolby Perceptual Quantizer).
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = ((c1 + c2 * L^n) / (1 + c3 * L^n)) ^ m
     * c1 = c3 - c2 + 1 = 3424 / 4096 = 0.8359375
     * c2 = 32 * 2413 / 4096 = 18.8515625
     * c3 = 32 * 2392 / 4096 = 18.6875
     * m = 128 * 2523 / 4096 = 78.84375
     * n = 0.25 * 2610 / 4096 = 0.1593017578125
     *     L - luminance of image 0 <= L <= 1 for HDR colorimetry.
     *         L = 1 corresponds to 10000 cd/m2
     *     E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_ST2084 = 7 << 22;
    /**
     * ARIB STD-B67 Hybrid Log Gamma.
     *
     * <pre>{@code
     * Transfer characteristic curve:
     * E = r * L^0.5                 for 0 <= L <= 1
     *   = a * ln(L - b) + c         for 1 < L
     * a = 0.17883277
     * b = 0.28466892
     * c = 0.55991073
     * r = 0.5
     *     L - luminance of image 0 <= L for HDR colorimetry. L = 1 corresponds
     *         to reference white level of 100 cd/m2
     *     E - corresponding electrical signal}</pre>
     */
    public static final int TRANSFER_HLG = 8 << 22;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
        RANGE_UNSPECIFIED,
        RANGE_FULL,
        RANGE_LIMITED,
        RANGE_EXTENDED
    })
    public @interface DataSpaceRange {};

    private static final int RANGE_MASK = 7 << 27;

    /**
     * Range characteristics are unknown or are determined by the application.
     */
    public static final int RANGE_UNSPECIFIED = 0 << 27;
    /**
     * Full range uses all values for Y, Cb and Cr from
     * {@code 0} to {@code 2^b-1}, where b is the bit depth of the color format.
     */
    public static final int RANGE_FULL = 1 << 27;
    /**
     * Limited range uses values {@code 16/256*2^b} to {@code 235/256*2^b} for Y, and
     * {@code 1/16*2^b} to {@code 15/16*2^b} for Cb, Cr, R, G and B, where b is the bit depth of
     * the color format.
     *
     * <p>E.g. For 8-bit-depth formats:
     * Luma (Y) samples should range from 16 to 235, inclusive
     * Chroma (Cb, Cr) samples should range from 16 to 240, inclusive
     *
     * For 10-bit-depth formats:
     * Luma (Y) samples should range from 64 to 940, inclusive
     * Chroma (Cb, Cr) samples should range from 64 to 960, inclusive. </p>
     */
    public static final int RANGE_LIMITED = 2 << 27;
    /**
     * Extended range is used for scRGB only.
     *
     * <p>Intended for use with floating point pixel formats. [0.0 - 1.0] is the standard
     * sRGB space. Values outside the range [0.0 - 1.0] can encode
     * color outside the sRGB gamut. [-0.5, 7.5] is the scRGB range.
     * Used to blend/merge multiple dataspaces on a single display.</p>
     */
    public static final int RANGE_EXTENDED = 3 << 27;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
        DATASPACE_UNKNOWN,
        DATASPACE_SCRGB_LINEAR,
        DATASPACE_SRGB,
        DATASPACE_SCRGB,
        DATASPACE_DISPLAY_P3,
        DATASPACE_BT2020_PQ,
        DATASPACE_ADOBE_RGB,
        DATASPACE_JFIF,
        DATASPACE_BT601_625,
        DATASPACE_BT601_525,
        DATASPACE_BT2020,
        DATASPACE_BT709,
        DATASPACE_DCI_P3,
        DATASPACE_SRGB_LINEAR
    })
    public @interface NamedDataSpace {};

    /**
     * Default-assumption data space, when not explicitly specified.
     *
     * <p>It is safest to assume a buffer is an image with sRGB primaries and
     * encoding ranges, but the consumer and/or the producer of the data may
     * simply be using defaults. No automatic gamma transform should be
     * expected, except for a possible display gamma transform when drawn to a
     * screen.</p>
     */
    public static final int DATASPACE_UNKNOWN = 0;
    /**
     * scRGB linear encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT709
     *   Transfer: TRANSFER_LINEAR
     *   Range: RANGE_EXTENDED</pre>
     *
     * The values are floating point.
     * A pixel value of 1.0, 1.0, 1.0 corresponds to sRGB white (D65) at 80 nits.
     * Values beyond the range [0.0 - 1.0] would correspond to other colors
     * spaces and/or HDR content.
     */
    public static final int DATASPACE_SCRGB_LINEAR = 406913024;
    /**
     * sRGB gamma encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT709
     *   Transfer: TRANSFER_SRGB
     *   Range: RANGE_FULL</pre>
     *
     * When written, the inverse transformation is performed.
     *
     * The alpha component, if present, is always stored in linear space and
     * is left unmodified when read or written.
     */
    public static final int DATASPACE_SRGB = 142671872;
    /**
     * scRGB gamma encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT709
     *   Transfer: TRANSFER_SRGB
     *   Range: RANGE_EXTENDED</pre>
     *
     * The values are floating point.
     *
     * A pixel value of 1.0, 1.0, 1.0 corresponds to sRGB white (D65) at 80 nits.
     * Values beyond the range [0.0 - 1.0] would correspond to other colors
     * spaces and/or HDR content.
     */
    public static final int DATASPACE_SCRGB = 411107328;
    /**
     * Display P3 encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_DCI_P3
     *   Transfer: TRANSFER_SRGB
     *   Range: RANGE_FULL</pre>
     */
    public static final int DATASPACE_DISPLAY_P3 = 143261696;
    /**
     * ITU-R Recommendation 2020 (BT.2020)
     *
     * Ultra High-definition television.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT2020
     *   Transfer: TRANSFER_ST2084
     *   Range: RANGE_FULL</pre>
     */
    public static final int DATASPACE_BT2020_PQ = 163971072;
    /**
     * Adobe RGB encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_ADOBE_RGB
     *   Transfer: TRANSFER_GAMMA2_2
     *   Range: RANGE_FULL</pre>
     *
     * Note: Application is responsible for gamma encoding the data.
     */
    public static final int DATASPACE_ADOBE_RGB = 151715840;
    /**
     * JPEG File Interchange Format (JFIF).
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT601_625
     *   Transfer: TRANSFER_SMPTE_170M
     *   Range: RANGE_FULL</pre>
     *
     * Same model as BT.601-625, but all values (Y, Cb, Cr) range from {@code 0} to {@code 255}
     */
    public static final int DATASPACE_JFIF = 146931712;
    /**
     * ITU-R Recommendation 601 (BT.601) - 525-line
     *
     * Standard-definition television, 525 Lines (NTSC).
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT601_625
     *   Transfer: TRANSFER_SMPTE_170M
     *   Range: RANGE_LIMITED</pre>
     */
    public static final int DATASPACE_BT601_625 = 281149440;
    /**
     * ITU-R Recommendation 709 (BT.709)
     *
     * High-definition television.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT601_525
     *   Transfer: TRANSFER_SMPTE_170M
     *   Range: RANGE_LIMITED</pre>
     */
    public static final int DATASPACE_BT601_525 = 281280512;
    /**
     * ITU-R Recommendation 2020 (BT.2020)
     *
     * Ultra High-definition television.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT2020
     *   Transfer: TRANSFER_SMPTE_170M
     *   Range: RANGE_FULL</pre>
     */
    public static final int DATASPACE_BT2020 = 147193856;
    /**
     * ITU-R Recommendation 709 (BT.709)
     *
     * High-definition television.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT709
     *   Transfer: TRANSFER_SMPTE_170M
     *   Range: RANGE_LIMITED</pre>
     */
    public static final int DATASPACE_BT709 = 281083904;
    /**
     * SMPTE EG 432-1 and SMPTE RP 431-2
     *
     * Digital Cinema DCI-P3.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_DCI_P3
     *   Transfer: TRANSFER_GAMMA2_6
     *   Range: RANGE_FULL</pre>
     *
     * Note: Application is responsible for gamma encoding the data as
     * a 2.6 gamma encoding is not supported in HW.
     */
    public static final int DATASPACE_DCI_P3 = 155844608;
    /**
     * sRGB linear encoding.
     *
     * <p>Composed of the following -</p>
     * <pre>
     *   Primaries: STANDARD_BT709
     *   Transfer: TRANSFER_LINEAR
     *   Range: RANGE_FULL</pre>
     *
     * The values are encoded using the full range ([0,255] for 8-bit) for all
     * components.
     */
    public static final int DATASPACE_SRGB_LINEAR = 138477568;

    private DataSpace() {}

    /**
     * Pack the dataSpace value using standard, transfer and range field value.
     * Field values should be in the correct bits place.
     *
     * @param standard Chromaticity coordinates of source primaries
     * @param transfer Opto-electronic transfer characteristic at the source
     * @param range The range of values
     *
     * @return The int dataspace packed by standard, transfer and range value
     */
    public static @NamedDataSpace int pack(@DataSpaceStandard int standard,
                                        @DataSpaceTransfer int transfer,
                                        @DataSpaceRange int range) {
        if ((standard & STANDARD_MASK) != standard) {
            throw new IllegalArgumentException("Invalid standard " + standard);
        }
        if ((transfer & TRANSFER_MASK) != transfer) {
            throw new IllegalArgumentException("Invalid transfer " + transfer);
        }
        if ((range & RANGE_MASK) != range) {
            throw new IllegalArgumentException("Invalid range " + range);
        }
        return standard | transfer | range;
    }

    /**
     * Unpack the standard field value from the packed dataSpace value.
     *
     * @param dataSpace The packed dataspace value
     *
     * @return The standard aspect
     */
    public static @DataSpaceStandard int getStandard(@NamedDataSpace int dataSpace) {
        @DataSpaceStandard int standard = dataSpace & STANDARD_MASK;
        return standard;
    }

    /**
     * Unpack the transfer field value from the packed dataSpace value
     *
     * @param dataSpace The packed dataspace value
     *
     * @return The transfer aspect
     */
    public static @DataSpaceTransfer int getTransfer(@NamedDataSpace int dataSpace) {
        @DataSpaceTransfer int transfer = dataSpace & TRANSFER_MASK;
        return transfer;
    }

    /**
     * Unpack the range field value from the packed dataSpace value
     *
     * @param dataSpace The packed dataspace value
     *
     * @return The range aspect
     */
    public static @DataSpaceRange int getRange(@NamedDataSpace int dataSpace) {
        @DataSpaceRange int range = dataSpace & RANGE_MASK;
        return range;
    }
}
