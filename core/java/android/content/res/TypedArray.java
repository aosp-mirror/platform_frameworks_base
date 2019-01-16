/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.res;

import android.annotation.AnyRes;
import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.annotation.StyleableRes;
import android.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.Config;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.StrictMode;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.android.internal.util.XmlUtils;

import dalvik.system.VMRuntime;

import java.util.Arrays;

/**
 * Container for an array of values that were retrieved with
 * {@link Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)}
 * or {@link Resources#obtainAttributes}.  Be
 * sure to call {@link #recycle} when done with them.
 *
 * The indices used to retrieve values from this structure correspond to
 * the positions of the attributes given to obtainStyledAttributes.
 */
public class TypedArray {

    static TypedArray obtain(Resources res, int len) {
        TypedArray attrs = res.mTypedArrayPool.acquire();
        if (attrs == null) {
            attrs = new TypedArray(res);
        }

        attrs.mRecycled = false;
        // Reset the assets, which may have changed due to configuration changes
        // or further resource loading.
        attrs.mAssets = res.getAssets();
        attrs.mMetrics = res.getDisplayMetrics();
        attrs.resize(len);
        return attrs;
    }

    // STYLE_ prefixed constants are offsets within the typed data array.
    // Keep this in sync with libs/androidfw/include/androidfw/AttributeResolution.h
    static final int STYLE_NUM_ENTRIES = 7;
    static final int STYLE_TYPE = 0;
    static final int STYLE_DATA = 1;
    static final int STYLE_ASSET_COOKIE = 2;
    static final int STYLE_RESOURCE_ID = 3;
    static final int STYLE_CHANGING_CONFIGURATIONS = 4;
    static final int STYLE_DENSITY = 5;
    static final int SYTLE_SOURCE_STYLE_RESOURCE_ID = 6;

    @UnsupportedAppUsage
    private final Resources mResources;
    @UnsupportedAppUsage
    private DisplayMetrics mMetrics;
    @UnsupportedAppUsage
    private AssetManager mAssets;

    @UnsupportedAppUsage
    private boolean mRecycled;

    @UnsupportedAppUsage
    /*package*/ XmlBlock.Parser mXml;
    @UnsupportedAppUsage
    /*package*/ Resources.Theme mTheme;
    /**
     * mData is used to hold the value/id and other metadata about each attribute.
     *
     * [type, data, asset cookie, resource id, changing configuration, density]
     *
     * type - type of this attribute, see TypedValue#TYPE_*
     *
     * data - can be used in various ways:
     *     a) actual value of the attribute if type is between #TYPE_FIRST_INT and #TYPE_LAST_INT
     *        1) color represented by an integer (#TYPE_INT_COLOR_*)
     *        2) boolean represented by an integer (#TYPE_INT_BOOLEAN)
     *        3) integer number (#TYPE_TYPE_INT_DEC or #TYPE_INT_HEX)
     *        4) float number where integer gets interpreted as float (#TYPE_FLOAT, #TYPE_FRACTION
     *            and #TYPE_DIMENSION)
     *     b) index into string block inside AssetManager (#TYPE_STRING)
     *     c) attribute resource id in the current theme/style (#TYPE_ATTRIBUTE)
     *
     * asset cookie - used in two ways:
     *     a) for strings, drawables, and fonts it specifies the set of apk assets to look at
     *     (multi-apk case)
     *     b) cookie + asset as a unique identifier for drawable caches
     *
     * resource id - id that was finally used to resolve this attribute
     *
     * changing configuration - a mask of the configuration parameters for which the values in this
     * attribute may change
     *
     * density - density of drawable pointed to by this attribute
     */
    @UnsupportedAppUsage
    /*package*/ int[] mData;
    /**
     * Pointer to the start of the memory address of mData. It is passed via JNI and used to write
     * to mData array directly from native code (AttributeResolution.cpp).
     */
    /*package*/ long mDataAddress;
    @UnsupportedAppUsage
    /*package*/ int[] mIndices;
    /**
     * Similar to mDataAddress, but instead it is a pointer to mIndices address.
     */
    /*package*/ long mIndicesAddress;
    @UnsupportedAppUsage
    /*package*/ int mLength;
    @UnsupportedAppUsage
    /*package*/ TypedValue mValue = new TypedValue();

    private void resize(int len) {
        mLength = len;
        final int dataLen = len * STYLE_NUM_ENTRIES;
        final int indicesLen = len + 1;
        final VMRuntime runtime = VMRuntime.getRuntime();
        if (mDataAddress == 0 || mData.length < dataLen) {
            mData = (int[]) runtime.newNonMovableArray(int.class, dataLen);
            mDataAddress = runtime.addressOf(mData);
            mIndices = (int[]) runtime.newNonMovableArray(int.class, indicesLen);
            mIndicesAddress = runtime.addressOf(mIndices);
        }
    }

    /**
     * Returns the number of values in this array.
     *
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int length() {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return mLength;
    }

    /**
     * Returns the number of indices in the array that actually have data. Attributes with a value
     * of @empty are included, as this is an explicit indicator.
     *
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int getIndexCount() {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return mIndices[0];
    }

    /**
     * Returns an index in the array that has data. Attributes with a value of @empty are included,
     * as this is an explicit indicator.
     *
     * @param at The index you would like to returned, ranging from 0 to
     *           {@link #getIndexCount()}.
     *
     * @return The index at the given offset, which can be used with
     *         {@link #getValue} and related APIs.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int getIndex(int at) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return mIndices[1+at];
    }

    /**
     * Returns the Resources object this array was loaded from.
     *
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public Resources getResources() {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return mResources;
    }

    /**
     * Retrieves the styled string value for the attribute at <var>index</var>.
     * <p>
     * If the attribute is not a string, this method will attempt to coerce
     * it to a string.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return CharSequence holding string data. May be styled. Returns
     *         {@code null} if the attribute is not defined or could not be
     *         coerced to a string.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public CharSequence getText(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index);
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            return v.coerceToString();
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getText of bad type: 0x" + Integer.toHexString(type));
    }

    /**
     * Retrieves the string value for the attribute at <var>index</var>.
     * <p>
     * If the attribute is not a string, this method will attempt to coerce
     * it to a string.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return String holding string data. Any styling information is removed.
     *         Returns {@code null} if the attribute is not defined or could
     *         not be coerced to a string.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    @Nullable
    public String getString(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index).toString();
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            final CharSequence cs = v.coerceToString();
            return cs != null ? cs.toString() : null;
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getString of bad type: 0x" + Integer.toHexString(type));
    }

    /**
     * Retrieves the string value for the attribute at <var>index</var>, but
     * only if that string comes from an immediate value in an XML file.  That
     * is, this does not allow references to string resources, string
     * attributes, or conversions from other types.  As such, this method
     * will only return strings for TypedArray objects that come from
     * attributes in an XML file.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return String holding string data. Any styling information is removed.
     *         Returns {@code null} if the attribute is not defined or is not
     *         an immediate string value.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public String getNonResourceString(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_STRING) {
            final int cookie = data[index + STYLE_ASSET_COOKIE];
            if (cookie < 0) {
                return mXml.getPooledString(data[index + STYLE_DATA]).toString();
            }
        }
        return null;
    }

    /**
     * Retrieves the string value for the attribute at <var>index</var> that is
     * not allowed to change with the given configurations.
     *
     * @param index Index of attribute to retrieve.
     * @param allowedChangingConfigs Bit mask of configurations from
     *        {@link Configuration}.NATIVE_CONFIG_* that are allowed to change.
     *
     * @return String holding string data. Any styling information is removed.
     *         Returns {@code null} if the attribute is not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @hide
     */
    @UnsupportedAppUsage
    public String getNonConfigurationString(@StyleableRes int index,
            @Config int allowedChangingConfigs) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        final @Config int changingConfigs = ActivityInfo.activityInfoConfigNativeToJava(
                data[index + STYLE_CHANGING_CONFIGURATIONS]);
        if ((changingConfigs & ~allowedChangingConfigs) != 0) {
            return null;
        }
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index).toString();
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            final CharSequence cs = v.coerceToString();
            return cs != null ? cs.toString() : null;
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getNonConfigurationString of bad type: 0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve the boolean value for the attribute at <var>index</var>.
     * <p>
     * If the attribute is an integer value, this method will return whether
     * it is equal to zero. If the attribute is not a boolean or integer value,
     * this method will attempt to coerce it to an integer using
     * {@link Integer#decode(String)} and return whether it is equal to zero.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 cannot be coerced to an integer.
     *
     * @return Boolean value of the attribute, or defValue if the attribute was
     *         not defined or could not be coerced to an integer.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public boolean getBoolean(@StyleableRes int index, boolean defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA] != 0;
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            StrictMode.noteResourceMismatch(v);
            return XmlUtils.convertValueToBoolean(v.coerceToString(), defValue);
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getBoolean of bad type: 0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve the integer value for the attribute at <var>index</var>.
     * <p>
     * If the attribute is not an integer, this method will attempt to coerce
     * it to an integer using {@link Integer#decode(String)}.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 cannot be coerced to an integer.
     *
     * @return Integer value of the attribute, or defValue if the attribute was
     *         not defined or could not be coerced to an integer.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int getInt(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            StrictMode.noteResourceMismatch(v);
            return XmlUtils.convertValueToInt(v.coerceToString(), defValue);
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getInt of bad type: 0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve the float value for the attribute at <var>index</var>.
     * <p>
     * If the attribute is not a float or an integer, this method will attempt
     * to coerce it to a float using {@link Float#parseFloat(String)}.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return Attribute float value, or defValue if the attribute was
     *         not defined or could not be coerced to a float.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public float getFloat(@StyleableRes int index, float defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_FLOAT) {
            return Float.intBitsToFloat(data[index + STYLE_DATA]);
        } else if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        }

        final TypedValue v = mValue;
        if (getValueAt(index, v)) {
            final CharSequence str = v.coerceToString();
            if (str != null) {
                StrictMode.noteResourceMismatch(v);
                return Float.parseFloat(str.toString());
            }
        }

        // We already checked for TYPE_NULL. This should never happen.
        throw new RuntimeException("getFloat of bad type: 0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve the color value for the attribute at <var>index</var>.  If
     * the attribute references a color resource holding a complex
     * {@link android.content.res.ColorStateList}, then the default color from
     * the set is returned.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not an integer color or color state list.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute color value, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer color or color state list.
     */
    @ColorInt
    public int getColor(@StyleableRes int index, @ColorInt int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        } else if (type == TypedValue.TYPE_STRING) {
            final TypedValue value = mValue;
            if (getValueAt(index, value)) {
                final ColorStateList csl = mResources.loadColorStateList(
                        value, value.resourceId, mTheme);
                return csl.getDefaultColor();
            }
            return defValue;
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to color: type=0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve the ComplexColor for the attribute at <var>index</var>.
     * The value may be either a {@link android.content.res.ColorStateList} which can wrap a simple
     * color value or a {@link android.content.res.GradientColor}
     * <p>
     * This method will return {@code null} if the attribute is not defined or
     * is not an integer color, color state list or GradientColor.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return ComplexColor for the attribute, or {@code null} if not defined.
     * @throws RuntimeException if the attribute if the TypedArray has already
     *         been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer color, color state list or GradientColor.
     * @hide
     */
    @Nullable
    public ComplexColor getComplexColor(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            if (value.type == TypedValue.TYPE_ATTRIBUTE) {
                throw new UnsupportedOperationException(
                        "Failed to resolve attribute at index " + index + ": " + value);
            }
            return mResources.loadComplexColor(value, value.resourceId, mTheme);
        }
        return null;
    }

    /**
     * Retrieve the ColorStateList for the attribute at <var>index</var>.
     * The value may be either a single solid color or a reference to
     * a color or complex {@link android.content.res.ColorStateList}
     * description.
     * <p>
     * This method will return {@code null} if the attribute is not defined or
     * is not an integer color or color state list.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return ColorStateList for the attribute, or {@code null} if not
     *         defined.
     * @throws RuntimeException if the attribute if the TypedArray has already
     *         been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer color or color state list.
     */
    @Nullable
    public ColorStateList getColorStateList(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            if (value.type == TypedValue.TYPE_ATTRIBUTE) {
                throw new UnsupportedOperationException(
                        "Failed to resolve attribute at index " + index + ": " + value);
            }
            return mResources.loadColorStateList(value, value.resourceId, mTheme);
        }
        return null;
    }

    /**
     * Retrieve the integer value for the attribute at <var>index</var>.
     * <p>
     * Unlike {@link #getInt(int, int)}, this method will throw an exception if
     * the attribute is defined but is not an integer.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute integer value, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer.
     */
    public int getInteger(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to integer: type=0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var>. Unit
     * conversions are based on the current {@link DisplayMetrics}
     * associated with the resources this {@link TypedArray} object
     * came from.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a dimension.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute dimension value multiplied by the appropriate
     *         metric, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer.
     *
     * @see #getDimensionPixelOffset
     * @see #getDimensionPixelSize
     */
    public float getDimension(@StyleableRes int index, float defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimension(data[index + STYLE_DATA], mMetrics);
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to dimension: type=0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var> for use
     * as an offset in raw pixels.  This is the same as
     * {@link #getDimension}, except the returned value is converted to
     * integer pixels for you.  An offset conversion involves simply
     * truncating the base value to an integer.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a dimension.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute dimension value multiplied by the appropriate
     *         metric and truncated to integer pixels, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not an integer.
     *
     * @see #getDimension
     * @see #getDimensionPixelSize
     */
    public int getDimensionPixelOffset(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelOffset(data[index + STYLE_DATA], mMetrics);
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to dimension: type=0x" + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var> for use
     * as a size in raw pixels.  This is the same as
     * {@link #getDimension}, except the returned value is converted to
     * integer pixels for use as a size.  A size conversion involves
     * rounding the base value, and ensuring that a non-zero base value
     * is at least one pixel in size.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a dimension.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute dimension value multiplied by the appropriate
     *         metric and truncated to integer pixels, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not a dimension.
     *
     * @see #getDimension
     * @see #getDimensionPixelOffset
     */
    public int getDimensionPixelSize(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(data[index + STYLE_DATA], mMetrics);
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to dimension: type=0x" + Integer.toHexString(type));
    }

    /**
     * Special version of {@link #getDimensionPixelSize} for retrieving
     * {@link android.view.ViewGroup}'s layout_width and layout_height
     * attributes.  This is only here for performance reasons; applications
     * should use {@link #getDimensionPixelSize}.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a dimension or integer (enum).
     *
     * @param index Index of the attribute to retrieve.
     * @param name Textual name of attribute for error reporting.
     *
     * @return Attribute dimension value multiplied by the appropriate
     *         metric and truncated to integer pixels.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not a dimension or integer (enum).
     */
    public int getLayoutDimension(@StyleableRes int index, String name) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(data[index + STYLE_DATA], mMetrics);
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException(getPositionDescription()
                + ": You must supply a " + name + " attribute.");
    }

    /**
     * Special version of {@link #getDimensionPixelSize} for retrieving
     * {@link android.view.ViewGroup}'s layout_width and layout_height
     * attributes.  This is only here for performance reasons; applications
     * should use {@link #getDimensionPixelSize}.
     *
     * @param index Index of the attribute to retrieve.
     * @param defValue The default value to return if this attribute is not
     *                 default or contains the wrong type of data.
     *
     * @return Attribute dimension value multiplied by the appropriate
     *         metric and truncated to integer pixels.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int getLayoutDimension(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index + STYLE_DATA];
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(data[index + STYLE_DATA], mMetrics);
        }

        return defValue;
    }

    /**
     * Retrieves a fractional unit attribute at <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     * @param base The base value of this fraction.  In other words, a
     *             standard fraction is multiplied by this value.
     * @param pbase The parent base value of this fraction.  In other
     *             words, a parent fraction (nn%p) is multiplied by this
     *             value.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute fractional value multiplied by the appropriate
     *         base value, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not a fraction.
     */
    public float getFraction(@StyleableRes int index, int base, int pbase, float defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final int attrIndex = index;
        index *= STYLE_NUM_ENTRIES;

        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_FRACTION) {
            return TypedValue.complexToFraction(data[index + STYLE_DATA], base, pbase);
        } else if (type == TypedValue.TYPE_ATTRIBUTE) {
            final TypedValue value = mValue;
            getValueAt(index, value);
            throw new UnsupportedOperationException(
                    "Failed to resolve attribute at index " + attrIndex + ": " + value);
        }

        throw new UnsupportedOperationException("Can't convert value at index " + attrIndex
                + " to fraction: type=0x" + Integer.toHexString(type));
    }

    /**
     * Retrieves the resource identifier for the attribute at
     * <var>index</var>.  Note that attribute resource as resolved when
     * the overall {@link TypedArray} object is retrieved.  As a
     * result, this function will return the resource identifier of the
     * final resource value that was found, <em>not</em> necessarily the
     * original resource that was specified by the attribute.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute resource identifier, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    @AnyRes
    public int getResourceId(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        if (data[index + STYLE_TYPE] != TypedValue.TYPE_NULL) {
            final int resid = data[index + STYLE_RESOURCE_ID];
            if (resid != 0) {
                return resid;
            }
        }
        return defValue;
    }

    /**
     * Retrieves the theme attribute resource identifier for the attribute at
     * <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or not a
     *                 resource.
     *
     * @return Theme attribute resource identifier, or defValue if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @hide
     */
    public int getThemeAttributeId(@StyleableRes int index, int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        if (data[index + STYLE_TYPE] == TypedValue.TYPE_ATTRIBUTE) {
            return data[index + STYLE_DATA];
        }
        return defValue;
    }

    /**
     * Retrieve the Drawable for the attribute at <var>index</var>.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a color or drawable resource.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return Drawable for the attribute, or {@code null} if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not a color or drawable resource.
     */
    @Nullable
    public Drawable getDrawable(@StyleableRes int index) {
        return getDrawableForDensity(index, 0);
    }

    /**
     * Version of {@link #getDrawable(int)} that accepts an override density.
     * @hide
     */
    @Nullable
    public Drawable getDrawableForDensity(@StyleableRes int index, int density) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            if (value.type == TypedValue.TYPE_ATTRIBUTE) {
                throw new UnsupportedOperationException(
                        "Failed to resolve attribute at index " + index + ": " + value);
            }

            if (density > 0) {
                // If the density is overridden, the value in the TypedArray will not reflect this.
                // Do a separate lookup of the resourceId with the density override.
                mResources.getValueForDensity(value.resourceId, density, value, true);
            }
            return mResources.loadDrawable(value, value.resourceId, density, mTheme);
        }
        return null;
    }

    /**
     * Retrieve the Typeface for the attribute at <var>index</var>.
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a font.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return Typeface for the attribute, or {@code null} if not defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @throws UnsupportedOperationException if the attribute is defined but is
     *         not a font resource.
     */
    @Nullable
    public Typeface getFont(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            if (value.type == TypedValue.TYPE_ATTRIBUTE) {
                throw new UnsupportedOperationException(
                        "Failed to resolve attribute at index " + index + ": " + value);
            }
            return mResources.getFont(value, value.resourceId);
        }
        return null;
    }

    /**
     * Retrieve the CharSequence[] for the attribute at <var>index</var>.
     * This gets the resource ID of the selected attribute, and uses
     * {@link Resources#getTextArray Resources.getTextArray} of the owning
     * Resources object to retrieve its String[].
     * <p>
     * This method will throw an exception if the attribute is defined but is
     * not a text array resource.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return CharSequence[] for the attribute, or {@code null} if not
     *         defined.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public CharSequence[] getTextArray(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            return mResources.getTextArray(value.resourceId);
        }
        return null;
    }

    /**
     * Retrieve the raw TypedValue for the attribute at <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     * @param outValue TypedValue object in which to place the attribute's
     *                 data.
     *
     * @return {@code true} if the value was retrieved and not @empty, {@code false} otherwise.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public boolean getValue(@StyleableRes int index, TypedValue outValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return getValueAt(index * STYLE_NUM_ENTRIES, outValue);
    }

    /**
     * Returns the type of attribute at the specified index.
     *
     * @param index Index of attribute whose type to retrieve.
     *
     * @return Attribute type.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public int getType(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        return mData[index + STYLE_TYPE];
    }

    /**
     * Returns the resource ID of the style against which the specified attribute was resolved,
     * otherwise returns defValue.
     *
     * @param index Index of attribute whose source style to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     *
     * @return Attribute source style resource ID or defValue if it was not resolved in any style.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    @StyleRes
    public int getSourceStyleResourceId(@StyleableRes int index, @StyleRes int defValue) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int resid = mData[index + SYTLE_SOURCE_STYLE_RESOURCE_ID];
        if (resid != 0) {
            return resid;
        }
        return defValue;
    }

    /**
     * Determines whether there is an attribute at <var>index</var>.
     * <p>
     * <strong>Note:</strong> If the attribute was set to {@code @empty} or
     * {@code @undefined}, this method returns {@code false}.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return True if the attribute has a value, false otherwise.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public boolean hasValue(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        return type != TypedValue.TYPE_NULL;
    }

    /**
     * Determines whether there is an attribute at <var>index</var>, returning
     * {@code true} if the attribute was explicitly set to {@code @empty} and
     * {@code false} only if the attribute was undefined.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return True if the attribute has a value or is empty, false otherwise.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public boolean hasValueOrEmpty(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        index *= STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        return type != TypedValue.TYPE_NULL
                || data[index + STYLE_DATA] == TypedValue.DATA_NULL_EMPTY;
    }

    /**
     * Retrieve the raw TypedValue for the attribute at <var>index</var>
     * and return a temporary object holding its data.  This object is only
     * valid until the next call on to {@link TypedArray}.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return Returns a TypedValue object if the attribute is defined,
     *         containing its data; otherwise returns null.  (You will not
     *         receive a TypedValue whose type is TYPE_NULL.)
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public TypedValue peekValue(@StyleableRes int index) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        final TypedValue value = mValue;
        if (getValueAt(index * STYLE_NUM_ENTRIES, value)) {
            return value;
        }
        return null;
    }

    /**
     * Returns a message about the parser state suitable for printing error messages.
     *
     * @return Human-readable description of current parser state.
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public String getPositionDescription() {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        return mXml != null ? mXml.getPositionDescription() : "<internal>";
    }

    /**
     * Recycles the TypedArray, to be re-used by a later caller. After calling
     * this function you must not ever touch the typed array again.
     *
     * @throws RuntimeException if the TypedArray has already been recycled.
     */
    public void recycle() {
        if (mRecycled) {
            throw new RuntimeException(toString() + " recycled twice!");
        }

        mRecycled = true;

        // These may have been set by the client.
        mXml = null;
        mTheme = null;
        mAssets = null;

        mResources.mTypedArrayPool.release(this);
    }

    /**
     * Extracts theme attributes from a typed array for later resolution using
     * {@link android.content.res.Resources.Theme#resolveAttributes(int[], int[])}.
     * Removes the entries from the typed array so that subsequent calls to typed
     * getters will return the default value without crashing.
     *
     * @return an array of length {@link #getIndexCount()} populated with theme
     *         attributes, or null if there are no theme attributes in the typed
     *         array
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @hide
     */
    @Nullable
    @UnsupportedAppUsage
    public int[] extractThemeAttrs() {
        return extractThemeAttrs(null);
    }

    /**
     * @hide
     */
    @Nullable
    @UnsupportedAppUsage
    public int[] extractThemeAttrs(@Nullable int[] scrap) {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        int[] attrs = null;

        final int[] data = mData;
        final int N = length();
        for (int i = 0; i < N; i++) {
            final int index = i * STYLE_NUM_ENTRIES;
            if (data[index + STYLE_TYPE] != TypedValue.TYPE_ATTRIBUTE) {
                // Not an attribute, ignore.
                continue;
            }

            // Null the entry so that we can safely call getZzz().
            data[index + STYLE_TYPE] = TypedValue.TYPE_NULL;

            final int attr = data[index + STYLE_DATA];
            if (attr == 0) {
                // Useless data, ignore.
                continue;
            }

            // Ensure we have a usable attribute array.
            if (attrs == null) {
                if (scrap != null && scrap.length == N) {
                    attrs = scrap;
                    Arrays.fill(attrs, 0);
                } else {
                    attrs = new int[N];
                }
            }

            attrs[i] = attr;
        }

        return attrs;
    }

    /**
     * Return a mask of the configuration parameters for which the values in
     * this typed array may change.
     *
     * @return Returns a mask of the changing configuration parameters, as
     *         defined by {@link android.content.pm.ActivityInfo}.
     * @throws RuntimeException if the TypedArray has already been recycled.
     * @see android.content.pm.ActivityInfo
     */
    public @Config int getChangingConfigurations() {
        if (mRecycled) {
            throw new RuntimeException("Cannot make calls to a recycled instance!");
        }

        @Config int changingConfig = 0;

        final int[] data = mData;
        final int N = length();
        for (int i = 0; i < N; i++) {
            final int index = i * STYLE_NUM_ENTRIES;
            final int type = data[index + STYLE_TYPE];
            if (type == TypedValue.TYPE_NULL) {
                continue;
            }
            changingConfig |= ActivityInfo.activityInfoConfigNativeToJava(
                    data[index + STYLE_CHANGING_CONFIGURATIONS]);
        }
        return changingConfig;
    }

    @UnsupportedAppUsage
    private boolean getValueAt(int index, TypedValue outValue) {
        final int[] data = mData;
        final int type = data[index + STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return false;
        }
        outValue.type = type;
        outValue.data = data[index + STYLE_DATA];
        outValue.assetCookie = data[index + STYLE_ASSET_COOKIE];
        outValue.resourceId = data[index + STYLE_RESOURCE_ID];
        outValue.changingConfigurations = ActivityInfo.activityInfoConfigNativeToJava(
                data[index + STYLE_CHANGING_CONFIGURATIONS]);
        outValue.density = data[index + STYLE_DENSITY];
        outValue.string = (type == TypedValue.TYPE_STRING) ? loadStringValueAt(index) : null;
        outValue.sourceStyleResourceId = data[index + SYTLE_SOURCE_STYLE_RESOURCE_ID];
        return true;
    }

    private CharSequence loadStringValueAt(int index) {
        final int[] data = mData;
        final int cookie = data[index + STYLE_ASSET_COOKIE];
        if (cookie < 0) {
            if (mXml != null) {
                return mXml.getPooledString(data[index + STYLE_DATA]);
            }
            return null;
        }
        return mAssets.getPooledStringForCookie(cookie, data[index + STYLE_DATA]);
    }

    /** @hide */
    protected TypedArray(Resources resources) {
        mResources = resources;
        mMetrics = mResources.getDisplayMetrics();
        mAssets = mResources.getAssets();
    }

    @Override
    public String toString() {
        return Arrays.toString(mData);
    }
}
