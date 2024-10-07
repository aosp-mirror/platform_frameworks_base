/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import android.annotation.AnyRes;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.content.pm.ActivityInfo.Config;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Container for a dynamically typed data value.  Primarily used with
 * {@link android.content.res.Resources} for holding resource values.
 */
@RavenwoodKeepWholeClass
public class TypedValue {
    /** The value contains no data. */
    public static final int TYPE_NULL = 0x00;

    /** The <var>data</var> field holds a resource identifier. */
    public static final int TYPE_REFERENCE = 0x01;
    /** The <var>data</var> field holds an attribute resource
     *  identifier (referencing an attribute in the current theme
     *  style, not a resource entry). */
    public static final int TYPE_ATTRIBUTE = 0x02;
    /** The <var>string</var> field holds string data.  In addition, if
     *  <var>data</var> is non-zero then it is the string block
     *  index of the string and <var>assetCookie</var> is the set of
     *  assets the string came from. */
    public static final int TYPE_STRING = 0x03;
    /** The <var>data</var> field holds an IEEE 754 floating point number. */
    public static final int TYPE_FLOAT = 0x04;
    /** The <var>data</var> field holds a complex number encoding a
     *  dimension value. */
    public static final int TYPE_DIMENSION = 0x05;
    /** The <var>data</var> field holds a complex number encoding a fraction
     *  of a container. */
    public static final int TYPE_FRACTION = 0x06;

    /** Identifies the start of plain integer values.  Any type value
     *  from this to {@link #TYPE_LAST_INT} means the
     *  <var>data</var> field holds a generic integer value. */
    public static final int TYPE_FIRST_INT = 0x10;

    /** The <var>data</var> field holds a number that was
     *  originally specified in decimal. */
    public static final int TYPE_INT_DEC = 0x10;
    /** The <var>data</var> field holds a number that was
     *  originally specified in hexadecimal (0xn). */
    public static final int TYPE_INT_HEX = 0x11;
    /**
     * {@link #data} holds 0 to represent {@code false}, or a value different from 0 to represent
     * {@code true}.
     */
    public static final int TYPE_INT_BOOLEAN = 0x12;
    /** Identifies the start of integer values that were specified as
     *  color constants (starting with '#'). */
    public static final int TYPE_FIRST_COLOR_INT = 0x1c;

    /** The <var>data</var> field holds a color that was originally
     *  specified as #aarrggbb. */
    public static final int TYPE_INT_COLOR_ARGB8 = 0x1c;
    /** The <var>data</var> field holds a color that was originally
     *  specified as #rrggbb. */
    public static final int TYPE_INT_COLOR_RGB8 = 0x1d;
    /** The <var>data</var> field holds a color that was originally
     *  specified as #argb. */
    public static final int TYPE_INT_COLOR_ARGB4 = 0x1e;
    /** The <var>data</var> field holds a color that was originally
     *  specified as #rgb. */
    public static final int TYPE_INT_COLOR_RGB4 = 0x1f;

    /** Identifies the end of integer values that were specified as color
     *  constants. */
    public static final int TYPE_LAST_COLOR_INT = 0x1f;

    /** Identifies the end of plain integer values. */
    public static final int TYPE_LAST_INT = 0x1f;

    /* ------------------------------------------------------------ */

    /** Complex data: bit location of unit information. */
    public static final int COMPLEX_UNIT_SHIFT = 0;
    /** Complex data: mask to extract unit information (after shifting by
     *  {@link #COMPLEX_UNIT_SHIFT}). This gives us 16 possible types, as
     *  defined below. */
    public static final int COMPLEX_UNIT_MASK = 0xf;

    private static final float INCHES_PER_PT = (1.0f / 72);
    private static final float INCHES_PER_MM = (1.0f / 25.4f);

    /** @hide **/
    @IntDef(prefix = "COMPLEX_UNIT_", value = {
            COMPLEX_UNIT_PX,
            COMPLEX_UNIT_DIP,
            COMPLEX_UNIT_SP,
            COMPLEX_UNIT_PT,
            COMPLEX_UNIT_IN,
            COMPLEX_UNIT_MM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComplexDimensionUnit {}

    /** {@link #TYPE_DIMENSION} complex unit: Value is raw pixels. */
    public static final int COMPLEX_UNIT_PX = 0;
    /** {@link #TYPE_DIMENSION} complex unit: Value is Device Independent
     *  Pixels. */
    public static final int COMPLEX_UNIT_DIP = 1;
    /** {@link #TYPE_DIMENSION} complex unit: Value is a scaled pixel. */
    public static final int COMPLEX_UNIT_SP = 2;
    /** {@link #TYPE_DIMENSION} complex unit: Value is in points. */
    public static final int COMPLEX_UNIT_PT = 3;
    /** {@link #TYPE_DIMENSION} complex unit: Value is in inches. */
    public static final int COMPLEX_UNIT_IN = 4;
    /** {@link #TYPE_DIMENSION} complex unit: Value is in millimeters. */
    public static final int COMPLEX_UNIT_MM = 5;

    /** {@link #TYPE_FRACTION} complex unit: A basic fraction of the overall
     *  size. */
    public static final int COMPLEX_UNIT_FRACTION = 0;
    /** {@link #TYPE_FRACTION} complex unit: A fraction of the parent size. */
    public static final int COMPLEX_UNIT_FRACTION_PARENT = 1;

    /** Complex data: where the radix information is, telling where the decimal
     *  place appears in the mantissa. */
    public static final int COMPLEX_RADIX_SHIFT = 4;
    /** Complex data: mask to extract radix information (after shifting by
     * {@link #COMPLEX_RADIX_SHIFT}). This give us 4 possible fixed point 
     * representations as defined below. */ 
    public static final int COMPLEX_RADIX_MASK = 0x3;

    /** Complex data: the mantissa is an integral number -- i.e., 0xnnnnnn.0 */
    public static final int COMPLEX_RADIX_23p0 = 0;
    /** Complex data: the mantissa magnitude is 16 bits -- i.e, 0xnnnn.nn */
    public static final int COMPLEX_RADIX_16p7 = 1;
    /** Complex data: the mantissa magnitude is 8 bits -- i.e, 0xnn.nnnn */
    public static final int COMPLEX_RADIX_8p15 = 2;
    /** Complex data: the mantissa magnitude is 0 bits -- i.e, 0x0.nnnnnn */
    public static final int COMPLEX_RADIX_0p23 = 3;

    /** Complex data: bit location of mantissa information. */
    public static final int COMPLEX_MANTISSA_SHIFT = 8;
    /** Complex data: mask to extract mantissa information (after shifting by
     *  {@link #COMPLEX_MANTISSA_SHIFT}). This gives us 23 bits of precision;
     *  the top bit is the sign. */
    public static final int COMPLEX_MANTISSA_MASK = 0xffffff;

    /* ------------------------------------------------------------ */

    /**
     * {@link #TYPE_NULL} data indicating the value was not specified.
     */
    public static final int DATA_NULL_UNDEFINED = 0;
    /**
     * {@link #TYPE_NULL} data indicating the value was explicitly set to null.
     */
    public static final int DATA_NULL_EMPTY = 1;

    /* ------------------------------------------------------------ */

    /**
     * If {@link #density} is equal to this value, then the density should be
     * treated as the system's default density value: {@link DisplayMetrics#DENSITY_DEFAULT}.
     */
    public static final int DENSITY_DEFAULT = 0;

    /**
     * If {@link #density} is equal to this value, then there is no density
     * associated with the resource and it should not be scaled.
     */
    public static final int DENSITY_NONE = 0xffff;

    /* ------------------------------------------------------------ */

    /** The type held by this value, as defined by the constants here.
     *  This tells you how to interpret the other fields in the object. */
    public int type;

    /** If the value holds a string, this is it. */
    public CharSequence string;

    /** Basic data in the value, interpreted according to {@link #type} */
    public int data;

    /** Additional information about where the value came from; only
     *  set for strings. */
    public int assetCookie;

    /** If Value came from a resource, this holds the corresponding resource id. */
    @AnyRes
    public int resourceId;

    /**
     * If the value came from a resource, these are the configurations for
     * which its contents can change.
     *
     * <p>For example, if a resource has a value defined for the -land resource qualifier,
     * this field will have the {@link android.content.pm.ActivityInfo#CONFIG_ORIENTATION} bit set.
     * </p>
     *
     * @see android.content.pm.ActivityInfo#CONFIG_MCC
     * @see android.content.pm.ActivityInfo#CONFIG_MNC
     * @see android.content.pm.ActivityInfo#CONFIG_LOCALE
     * @see android.content.pm.ActivityInfo#CONFIG_TOUCHSCREEN
     * @see android.content.pm.ActivityInfo#CONFIG_KEYBOARD
     * @see android.content.pm.ActivityInfo#CONFIG_KEYBOARD_HIDDEN
     * @see android.content.pm.ActivityInfo#CONFIG_NAVIGATION
     * @see android.content.pm.ActivityInfo#CONFIG_ORIENTATION
     * @see android.content.pm.ActivityInfo#CONFIG_SCREEN_LAYOUT
     * @see android.content.pm.ActivityInfo#CONFIG_UI_MODE
     * @see android.content.pm.ActivityInfo#CONFIG_SCREEN_SIZE
     * @see android.content.pm.ActivityInfo#CONFIG_SMALLEST_SCREEN_SIZE
     * @see android.content.pm.ActivityInfo#CONFIG_DENSITY
     * @see android.content.pm.ActivityInfo#CONFIG_LAYOUT_DIRECTION
     * @see android.content.pm.ActivityInfo#CONFIG_COLOR_MODE
     *
     */
    public @Config int changingConfigurations = -1;

    /**
     * If the Value came from a resource, this holds the corresponding pixel density.
     * */
    public int density;

    /**
     * If the Value came from a style resource or a layout resource (set in an XML layout), this
     * holds the corresponding style or layout resource id against which the attribute was resolved.
     */
    public int sourceResourceId;

    /* ------------------------------------------------------------ */

    /** Return the data for this value as a float.  Only use for values
     *  whose type is {@link #TYPE_FLOAT}. */
    public final float getFloat() {
        return Float.intBitsToFloat(data);
    }

    private static final float MANTISSA_MULT =
        1.0f / (1<<TypedValue.COMPLEX_MANTISSA_SHIFT);
    private static final float[] RADIX_MULTS = new float[] {
        1.0f*MANTISSA_MULT, 1.0f/(1<<7)*MANTISSA_MULT,
        1.0f/(1<<15)*MANTISSA_MULT, 1.0f/(1<<23)*MANTISSA_MULT
    };

    /**
     * Determine if a value is a color.
     *
     * This works by comparing {@link #type} to {@link #TYPE_FIRST_COLOR_INT}
     * and {@link #TYPE_LAST_COLOR_INT}.
     *
     * @return true if this value is a color
     */
    public boolean isColorType() {
        return (type >= TYPE_FIRST_COLOR_INT && type <= TYPE_LAST_COLOR_INT);
    }

    /**
     * Retrieve the base value from a complex data integer.  This uses the 
     * {@link #COMPLEX_MANTISSA_MASK} and {@link #COMPLEX_RADIX_MASK} fields of 
     * the data to compute a floating point representation of the number they 
     * describe.  The units are ignored. 
     *  
     * @param complex A complex data value.
     * 
     * @return A floating point value corresponding to the complex data.
     */
    public static float complexToFloat(int complex)
    {
        return (complex&(TypedValue.COMPLEX_MANTISSA_MASK
                   <<TypedValue.COMPLEX_MANTISSA_SHIFT))
            * RADIX_MULTS[(complex>>TypedValue.COMPLEX_RADIX_SHIFT)
                            & TypedValue.COMPLEX_RADIX_MASK];
    }

    /**
     * Converts a complex data value holding a dimension to its final floating 
     * point value. The given <var>data</var> must be structured as a 
     * {@link #TYPE_DIMENSION}.
     *  
     * @param data A complex data value holding a unit, magnitude, and 
     *             mantissa.
     * @param metrics Current display metrics to use in the conversion -- 
     *                supplies display density and scaling information.
     * 
     * @return The complex floating point value multiplied by the appropriate 
     * metrics depending on its unit. 
     */
    public static float complexToDimension(int data, DisplayMetrics metrics)
    {
        return applyDimension(
            (data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK,
            complexToFloat(data),
            metrics);
    }

    /**
     * Converts a complex data value holding a dimension to its final value
     * as an integer pixel offset.  This is the same as
     * {@link #complexToDimension}, except the raw floating point value is
     * truncated to an integer (pixel) value.
     * The given <var>data</var> must be structured as a 
     * {@link #TYPE_DIMENSION}.
     *  
     * @param data A complex data value holding a unit, magnitude, and 
     *             mantissa.
     * @param metrics Current display metrics to use in the conversion -- 
     *                supplies display density and scaling information.
     * 
     * @return The number of pixels specified by the data and its desired
     * multiplier and units.
     */
    public static int complexToDimensionPixelOffset(int data,
            DisplayMetrics metrics)
    {
        return (int)applyDimension(
                (data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK,
                complexToFloat(data),
                metrics);
    }

    /**
     * Converts a complex data value holding a dimension to its final value
     * as an integer pixel size.  This is the same as
     * {@link #complexToDimension}, except the raw floating point value is
     * converted to an integer (pixel) value for use as a size.  A size
     * conversion involves rounding the base value, and ensuring that a
     * non-zero base value is at least one pixel in size.
     * The given <var>data</var> must be structured as a 
     * {@link #TYPE_DIMENSION}.
     *  
     * @param data A complex data value holding a unit, magnitude, and 
     *             mantissa.
     * @param metrics Current display metrics to use in the conversion -- 
     *                supplies display density and scaling information.
     * 
     * @return The number of pixels specified by the data and its desired
     * multiplier and units.
     */
    public static int complexToDimensionPixelSize(int data,
            DisplayMetrics metrics)
    {
        final float value = complexToFloat(data);
        final float f = applyDimension(
                (data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK,
                value,
                metrics);
        final int res = (int) ((f >= 0) ? (f + 0.5f) : (f - 0.5f));
        if (res != 0) return res;
        if (value == 0) return 0;
        if (value > 0) return 1;
        return -1;
    }

    /**
     * @hide Was accidentally exposed in API level 1 for debugging purposes.
     * Kept for compatibility just in case although the debugging code has been removed.
     */
    @Deprecated
    public static float complexToDimensionNoisy(int data, DisplayMetrics metrics)
    {
        return complexToDimension(data, metrics);
    }

    /**
     * Return the complex unit type for this value. For example, a dimen type
     * with value 12sp will return {@link #COMPLEX_UNIT_SP}. Only use for values
     * whose type is {@link #TYPE_DIMENSION}.
     *
     * @return The complex unit type.
     */
    public int getComplexUnit() {
        return getUnitFromComplexDimension(data);
    }

    /**
     * Return the complex unit type for the given complex dimension. For example, a dimen type
     * with value 12sp will return {@link #COMPLEX_UNIT_SP}. Use with values created with {@link
     * #createComplexDimension(int, int)} etc.
     *
     * @return The complex unit type.
     *
     * @hide
     */
    public static int getUnitFromComplexDimension(int complexDimension) {
        return COMPLEX_UNIT_MASK & (complexDimension >> TypedValue.COMPLEX_UNIT_SHIFT);
    }

    /**
     * Converts an unpacked complex data value holding a dimension to its final floating point pixel
     * value. The two parameters <var>unit</var> and <var>value</var> are as in {@link
     * #TYPE_DIMENSION}.
     *
     * <p>To convert the other way, e.g. from pixels to DP, use {@link #deriveDimension(int, float,
     * DisplayMetrics)}.
     *
     * @param unit The unit to convert from.
     * @param value The value to apply the unit to.
     * @param metrics Current display metrics to use in the conversion -- 
     *                supplies display density and scaling information.
     * 
     * @return The equivalent pixel value—i.e. the complex floating point value multiplied by the
     * appropriate metrics depending on its unit—or zero if unit is not valid.
     */
    public static float applyDimension(@ComplexDimensionUnit int unit, float value,
                                       DisplayMetrics metrics)
    {
        switch (unit) {
            case COMPLEX_UNIT_PX:
                return value;
            case COMPLEX_UNIT_DIP:
                return value * metrics.density;
            case COMPLEX_UNIT_SP:
                if (metrics.fontScaleConverter != null) {
                    return applyDimension(
                            COMPLEX_UNIT_DIP,
                            metrics.fontScaleConverter.convertSpToDp(value),
                            metrics);
                } else {
                    return value * metrics.scaledDensity;
                }
            case COMPLEX_UNIT_PT:
                return value * metrics.xdpi * INCHES_PER_PT;
            case COMPLEX_UNIT_IN:
                return value * metrics.xdpi;
            case COMPLEX_UNIT_MM:
                return value * metrics.xdpi * INCHES_PER_MM;
        }
        return 0;
    }


    /**
     * Converts a pixel value to the given dimension, e.g. PX to DP.
     *
     * <p>This is the inverse of {@link #applyDimension(int, float, DisplayMetrics)}
     *
     * @param unitToConvertTo The unit to convert to.
     * @param pixelValue The raw pixels value to convert from.
     * @param metrics Current display metrics to use in the conversion --
     *                supplies display density and scaling information.
     *
     * @return A dimension value equivalent to the given number of pixels
     * @throws IllegalArgumentException if unitToConvertTo is not valid.
     */
    public static float deriveDimension(
            @ComplexDimensionUnit int unitToConvertTo,
            float pixelValue,
            @NonNull DisplayMetrics metrics) {
        switch (unitToConvertTo) {
            case COMPLEX_UNIT_PX:
                return pixelValue;
            case COMPLEX_UNIT_DIP: {
                // Avoid divide-by-zero, and return 0 since that's what the inverse function will do
                if (metrics.density == 0) {
                    return 0;
                }
                return pixelValue / metrics.density;
            }
            case COMPLEX_UNIT_SP:
                if (metrics.fontScaleConverter != null) {
                    final float dpValue = deriveDimension(COMPLEX_UNIT_DIP, pixelValue, metrics);
                    return metrics.fontScaleConverter.convertDpToSp(dpValue);
                } else {
                    if (metrics.scaledDensity == 0) {
                        return 0;
                    }
                    return pixelValue / metrics.scaledDensity;
                }
            case COMPLEX_UNIT_PT: {
                if (metrics.xdpi == 0) {
                    return 0;
                }
                return pixelValue / metrics.xdpi / INCHES_PER_PT;
            }
            case COMPLEX_UNIT_IN: {
                if (metrics.xdpi == 0) {
                    return 0;
                }
                return pixelValue / metrics.xdpi;
            }
            case COMPLEX_UNIT_MM: {
                if (metrics.xdpi == 0) {
                    return 0;
                }
                return pixelValue / metrics.xdpi / INCHES_PER_MM;
            }
            default:
                throw new IllegalArgumentException("Invalid unitToConvertTo " + unitToConvertTo);
        }
    }

    /**
     * Converts a pixel value to the given dimension, e.g. PX to DP.
     *
     * <p>This is just an alias of {@link #deriveDimension(int, float, DisplayMetrics)} with an
     * easier-to-find name.
     *
     * @param unitToConvertTo The unit to convert to.
     * @param pixelValue The raw pixels value to convert from.
     * @param metrics Current display metrics to use in the conversion --
     *                supplies display density and scaling information.
     *
     * @return A dimension value equivalent to the given number of pixels
     * @throws IllegalArgumentException if unitToConvertTo is not valid.
     */
    public static float convertPixelsToDimension(
            @ComplexDimensionUnit int unitToConvertTo,
            float pixelValue,
            @NonNull DisplayMetrics metrics) {
        return deriveDimension(unitToConvertTo, pixelValue, metrics);
    }

    /**
     * Converts a dimension value to raw pixels, e.g. DP to PX.
     *
     * <p>This is just an alias of {@link #applyDimension(int, float, DisplayMetrics)} with an
     * easier-to-find name.
     *
     * @param unitToConvertFrom The unit to convert from.
     * @param value The dimension value to apply the unit to.
     * @param metrics Current display metrics to use in the conversion --
     *                supplies display density and scaling information.
     *
     * @return The equivalent pixel value—i.e. the complex floating point value multiplied by the
     * appropriate metrics depending on its unit—or zero if unit is not valid.
     */
    public static float convertDimensionToPixels(
            @ComplexDimensionUnit int unitToConvertFrom,
            float value,
            @NonNull DisplayMetrics metrics) {
        return applyDimension(unitToConvertFrom, value, metrics);
    }

    /**
     * Return the data for this value as a dimension.  Only use for values 
     * whose type is {@link #TYPE_DIMENSION}. 
     * 
     * @param metrics Current display metrics to use in the conversion -- 
     *                supplies display density and scaling information.
     * 
     * @return The complex floating point value multiplied by the appropriate 
     * metrics depending on its unit. 
     */
    public float getDimension(DisplayMetrics metrics)
    {
        return complexToDimension(data, metrics);
    }

    /**
     * Construct a complex data integer.  This validates the radix and the magnitude of the
     * mantissa, and sets the {@link TypedValue#COMPLEX_MANTISSA_MASK} and
     * {@link TypedValue#COMPLEX_RADIX_MASK} components as provided. The units are not set.
     **
     * @param mantissa an integer representing the mantissa.
     * @param radix a radix option, e.g. {@link TypedValue#COMPLEX_RADIX_23p0}.
     * @return A complex data integer representing the value.
     * @hide
     */
    private static int createComplex(@IntRange(from = -0x800000, to = 0x7FFFFF) int mantissa,
            int radix) {
        if (mantissa < -0x800000 || mantissa >= 0x800000) {
            throw new IllegalArgumentException("Magnitude of mantissa is too large: " + mantissa);
        }
        if (radix < TypedValue.COMPLEX_RADIX_23p0 || radix > TypedValue.COMPLEX_RADIX_0p23) {
            throw new IllegalArgumentException("Invalid radix: " + radix);
        }
        return ((mantissa & TypedValue.COMPLEX_MANTISSA_MASK) << TypedValue.COMPLEX_MANTISSA_SHIFT)
                | (radix << TypedValue.COMPLEX_RADIX_SHIFT);
    }

    /**
     * Convert a base value to a complex data integer.  This sets the {@link
     * TypedValue#COMPLEX_MANTISSA_MASK} and {@link TypedValue#COMPLEX_RADIX_MASK} fields of the
     * data to create a floating point representation of the given value. The units are not set.
     *
     * <p>This is the inverse of {@link TypedValue#complexToFloat(int)}.
     *
     * @param value An integer value.
     * @return A complex data integer representing the value.
     * @hide
     */
    public static int intToComplex(int value) {
        if (value < -0x800000 || value >= 0x800000) {
            throw new IllegalArgumentException("Magnitude of the value is too large: " + value);
        }
        return createComplex(value, TypedValue.COMPLEX_RADIX_23p0);
    }

    /**
     * Convert a base value to a complex data integer.  This sets the {@link
     * TypedValue#COMPLEX_MANTISSA_MASK} and {@link TypedValue#COMPLEX_RADIX_MASK} fields of the
     * data to create a floating point representation of the given value. The units are not set.
     *
     * <p>This is the inverse of {@link TypedValue#complexToFloat(int)}.
     *
     * @param value A floating point value.
     * @return A complex data integer representing the value.
     * @hide
     */
    public static int floatToComplex(@FloatRange(from = -0x800000, to = 0x7FFFFF) float value) {
        // validate that the magnitude fits in this representation
        if (value < (float) -0x800000 - .5f || value >= (float) 0x800000 - .5f) {
            throw new IllegalArgumentException("Magnitude of the value is too large: " + value);
        }
        try {
            // If there's no fraction, use integer representation, as that's clearer
            if (value == (float) (int) value) {
                return createComplex((int) value, TypedValue.COMPLEX_RADIX_23p0);
            }
            float absValue = Math.abs(value);
            // If the magnitude is 0, we don't need any magnitude digits
            if (absValue < 1f) {
                return createComplex(Math.round(value * (1 << 23)), TypedValue.COMPLEX_RADIX_0p23);
            }
            // If the magnitude is less than 2^8, use 8 magnitude digits
            if (absValue < (float) (1 << 8)) {
                return createComplex(Math.round(value * (1 << 15)), TypedValue.COMPLEX_RADIX_8p15);
            }
            // If the magnitude is less than 2^16, use 16 magnitude digits
            if (absValue < (float) (1 << 16)) {
                return createComplex(Math.round(value * (1 << 7)), TypedValue.COMPLEX_RADIX_16p7);
            }
            // The magnitude requires all 23 digits
            return createComplex(Math.round(value), TypedValue.COMPLEX_RADIX_23p0);
        } catch (IllegalArgumentException ex) {
            // Wrap exception so as to include the value argument in the message.
            throw new IllegalArgumentException("Unable to convert value to complex: " + value, ex);
        }
    }

    /**
     * <p>Creates a complex data integer that stores a dimension value and units.
     *
     * <p>The resulting value can be passed to e.g.
     * {@link TypedValue#complexToDimensionPixelOffset(int, DisplayMetrics)} to calculate the pixel
     * value for the dimension.
     *
     * @param value the value of the dimension
     * @param units the units of the dimension, e.g. {@link TypedValue#COMPLEX_UNIT_DIP}
     * @return A complex data integer representing the value and units of the dimension.
     * @hide
     */
    public static int createComplexDimension(
            @IntRange(from = -0x800000, to = 0x7FFFFF) int value,
            @ComplexDimensionUnit int units) {
        if (units < TypedValue.COMPLEX_UNIT_PX || units > TypedValue.COMPLEX_UNIT_MM) {
            throw new IllegalArgumentException("Must be a valid COMPLEX_UNIT_*: " + units);
        }
        return intToComplex(value) | units;
    }

    /**
     * <p>Creates a complex data integer that stores a dimension value and units.
     *
     * <p>The resulting value can be passed to e.g.
     * {@link TypedValue#complexToDimensionPixelOffset(int, DisplayMetrics)} to calculate the pixel
     * value for the dimension.
     *
     * @param value the value of the dimension
     * @param units the units of the dimension, e.g. {@link TypedValue#COMPLEX_UNIT_DIP}
     * @return A complex data integer representing the value and units of the dimension.
     * @hide
     */
    public static int createComplexDimension(
            @FloatRange(from = -0x800000, to = 0x7FFFFF) float value,
            @ComplexDimensionUnit int units) {
        if (units < TypedValue.COMPLEX_UNIT_PX || units > TypedValue.COMPLEX_UNIT_MM) {
            throw new IllegalArgumentException("Must be a valid COMPLEX_UNIT_*: " + units);
        }
        return floatToComplex(value) | units;
    }

    /**
     * Converts a complex data value holding a fraction to its final floating 
     * point value. The given <var>data</var> must be structured as a 
     * {@link #TYPE_FRACTION}.
     * 
     * @param data A complex data value holding a unit, magnitude, and 
     *             mantissa.
     * @param base The base value of this fraction.  In other words, a 
     *             standard fraction is multiplied by this value.
     * @param pbase The parent base value of this fraction.  In other 
     *             words, a parent fraction (nn%p) is multiplied by this
     *             value.
     * 
     * @return The complex floating point value multiplied by the appropriate 
     * base value depending on its unit. 
     */
    public static float complexToFraction(int data, float base, float pbase)
    {
        switch ((data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK) {
        case COMPLEX_UNIT_FRACTION:
            return complexToFloat(data) * base;
        case COMPLEX_UNIT_FRACTION_PARENT:
            return complexToFloat(data) * pbase;
        }
        return 0;
    }

    /**
     * Return the data for this value as a fraction.  Only use for values whose 
     * type is {@link #TYPE_FRACTION}. 
     * 
     * @param base The base value of this fraction.  In other words, a 
     *             standard fraction is multiplied by this value.
     * @param pbase The parent base value of this fraction.  In other 
     *             words, a parent fraction (nn%p) is multiplied by this
     *             value.
     * 
     * @return The complex floating point value multiplied by the appropriate 
     * base value depending on its unit. 
     */
    public float getFraction(float base, float pbase)
    {
        return complexToFraction(data, base, pbase);
    }

    /**
     * Regardless of the actual type of the value, try to convert it to a
     * string value.  For example, a color type will be converted to a
     * string of the form #aarrggbb.
     * 
     * @return CharSequence The coerced string value.  If the value is
     *         null or the type is not known, null is returned.
     */
    public final CharSequence coerceToString()
    {
        int t = type;
        if (t == TYPE_STRING) {
            return string;
        }
        return coerceToString(t, data);
    }

    private static final String[] DIMENSION_UNIT_STRS = new String[] {
        "px", "dip", "sp", "pt", "in", "mm"
    };
    private static final String[] FRACTION_UNIT_STRS = new String[] {
        "%", "%p"
    };

    /**
     * Perform type conversion as per {@link #coerceToString()} on an
     * explicitly supplied type and data.
     * 
     * @param type The data type identifier.
     * @param data The data value.
     * 
     * @return String The coerced string value.  If the value is
     *         null or the type is not known, null is returned.
     */
    public static final String coerceToString(int type, int data)
    {
        switch (type) {
        case TYPE_NULL:
            return null;
        case TYPE_REFERENCE:
            return "@" + data;
        case TYPE_ATTRIBUTE:
            return "?" + data;
        case TYPE_FLOAT:
            return Float.toString(Float.intBitsToFloat(data));
        case TYPE_DIMENSION:
            return Float.toString(complexToFloat(data)) + DIMENSION_UNIT_STRS[
                (data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK];
        case TYPE_FRACTION:
            return Float.toString(complexToFloat(data)*100) + FRACTION_UNIT_STRS[
                (data>>COMPLEX_UNIT_SHIFT)&COMPLEX_UNIT_MASK];
        case TYPE_INT_HEX:
            return "0x" + Integer.toHexString(data);
        case TYPE_INT_BOOLEAN:
            return data != 0 ? "true" : "false";
        }

        if (type >= TYPE_FIRST_COLOR_INT && type <= TYPE_LAST_COLOR_INT) {
            return "#" + Integer.toHexString(data);
        } else if (type >= TYPE_FIRST_INT && type <= TYPE_LAST_INT) {
            return Integer.toString(data);
        }

        return null;
    }

    public void setTo(TypedValue other)
    {
        type = other.type;
        string = other.string;
        data = other.data;
        assetCookie = other.assetCookie;
        resourceId = other.resourceId;
        density = other.density;
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("TypedValue{t=0x").append(Integer.toHexString(type));
        sb.append("/d=0x").append(Integer.toHexString(data));
        if (type == TYPE_STRING) {
            sb.append(" \"").append(string != null ? string : "<null>").append("\"");
        }
        if (assetCookie != 0) {
            sb.append(" a=").append(assetCookie);
        }
        if (resourceId != 0) {
            sb.append(" r=0x").append(Integer.toHexString(resourceId));
        }
        sb.append("}");
        return sb.toString();
    }
}
