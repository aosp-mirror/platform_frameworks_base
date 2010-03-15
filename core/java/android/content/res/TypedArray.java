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

import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.util.XmlUtils;

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
    private final Resources mResources;
    /*package*/ XmlBlock.Parser mXml;
    /*package*/ int[] mRsrcs;
    /*package*/ int[] mData;
    /*package*/ int[] mIndices;
    /*package*/ int mLength;
    private TypedValue mValue = new TypedValue();
   
    /**
     * Return the number of values in this array.
     */
    public int length() {
        return mLength;
    }
    
    /**
     * Return the number of indices in the array that actually have data.
     */
    public int getIndexCount() {
        return mIndices[0];
    }
    
    /**
     * Return an index in the array that has data.
     * 
     * @param at The index you would like to returned, ranging from 0 to
     * {@link #getIndexCount()}.
     * 
     * @return The index at the given offset, which can be used with
     * {@link #getValue} and related APIs.
     */
    public int getIndex(int at) {
        return mIndices[1+at];
    }
    
    /**
     * Return the Resources object this array was loaded from.
     */
    public Resources getResources() {
        return mResources;
    }
    
    /**
     * Retrieve the styled string value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return CharSequence holding string data.  May be styled.  Returns 
     *         null if the attribute is not defined.
     */
    public CharSequence getText(int index) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index);
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to string: " + v);
            return v.coerceToString();
        }
        Log.w(Resources.TAG, "getString of bad type: 0x"
              + Integer.toHexString(type));
        return null;
    }

    /**
     * Retrieve the string value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return String holding string data.  Any styling information is
     * removed.  Returns null if the attribute is not defined.
     */
    public String getString(int index) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index).toString();
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to string: " + v);
            CharSequence cs = v.coerceToString();
            return cs != null ? cs.toString() : null;
        }
        Log.w(Resources.TAG, "getString of bad type: 0x"
              + Integer.toHexString(type));
        return null;
    }

    /**
     * Retrieve the string value for the attribute at <var>index</var>, but
     * only if that string comes from an immediate value in an XML file.  That
     * is, this does not allow references to string resources, string
     * attributes, or conversions from other types.  As such, this method
     * will only return strings for TypedArray objects that come from
     * attributes in an XML file.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return String holding string data.  Any styling information is
     * removed.  Returns null if the attribute is not defined or is not
     * an immediate string value.
     */
    public String getNonResourceString(int index) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_STRING) {
            final int cookie = data[index+AssetManager.STYLE_ASSET_COOKIE];
            if (cookie < 0) {
                return mXml.getPooledString(
                    data[index+AssetManager.STYLE_DATA]).toString();
            }
        }
        return null;
    }
    
    /**
     * @hide
     * Retrieve the string value for the attribute at <var>index</var> that is
     * not allowed to change with the given configurations.
     * 
     * @param index Index of attribute to retrieve.
     * @param allowedChangingConfigs Bit mask of configurations from
     * ActivityInfo that are allowed to change.
     * 
     * @return String holding string data.  Any styling information is
     * removed.  Returns null if the attribute is not defined.
     */
    public String getNonConfigurationString(int index, int allowedChangingConfigs) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if ((data[index+AssetManager.STYLE_CHANGING_CONFIGURATIONS]&~allowedChangingConfigs) != 0) {
            return null;
        }
        if (type == TypedValue.TYPE_NULL) {
            return null;
        } else if (type == TypedValue.TYPE_STRING) {
            return loadStringValueAt(index).toString();
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to string: " + v);
            CharSequence cs = v.coerceToString();
            return cs != null ? cs.toString() : null;
        }
        Log.w(Resources.TAG, "getString of bad type: 0x"
              + Integer.toHexString(type));
        return null;
    }

    /**
     * Retrieve the boolean value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined.
     * 
     * @return Attribute boolean value, or defValue if not defined.
     */
    public boolean getBoolean(int index, boolean defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
            && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA] != 0;
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to boolean: " + v);
            return XmlUtils.convertValueToBoolean(
                v.coerceToString(), defValue);
        }
        Log.w(Resources.TAG, "getBoolean of bad type: 0x"
              + Integer.toHexString(type));
        return defValue;
    }

    /**
     * Retrieve the integer value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined.
     * 
     * @return Attribute int value, or defValue if not defined.
     */
    public int getInt(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
            && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to int: " + v);
            return XmlUtils.convertValueToInt(
                v.coerceToString(), defValue);
        }
        Log.w(Resources.TAG, "getInt of bad type: 0x"
              + Integer.toHexString(type));
        return defValue;
    }

    /**
     * Retrieve the float value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return Attribute float value, or defValue if not defined..
     */
    public float getFloat(int index, float defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_FLOAT) {
            return Float.intBitsToFloat(data[index+AssetManager.STYLE_DATA]);
        } else if (type >= TypedValue.TYPE_FIRST_INT
            && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        }

        TypedValue v = mValue;
        if (getValueAt(index, v)) {
            Log.w(Resources.TAG, "Converting to float: " + v);
            CharSequence str = v.coerceToString();
            if (str != null) {
                return Float.parseFloat(str.toString());
            }
        }
        Log.w(Resources.TAG, "getFloat of bad type: 0x"
              + Integer.toHexString(type));
        return defValue;
    }
    
    /**
     * Retrieve the color value for the attribute at <var>index</var>.  If
     * the attribute references a color resource holding a complex
     * {@link android.content.res.ColorStateList}, then the default color from
     * the set is returned.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     * 
     * @return Attribute color value, or defValue if not defined.
     */
    public int getColor(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
            && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        } else if (type == TypedValue.TYPE_STRING) {
            final TypedValue value = mValue;
            if (getValueAt(index, value)) {
                ColorStateList csl = mResources.loadColorStateList(
                        value, value.resourceId);
                return csl.getDefaultColor();
            }
            return defValue;
        }

        throw new UnsupportedOperationException("Can't convert to color: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve the ColorStateList for the attribute at <var>index</var>.
     * The value may be either a single solid color or a reference to
     * a color or complex {@link android.content.res.ColorStateList} description.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return ColorStateList for the attribute, or null if not defined.
     */
    public ColorStateList getColorStateList(int index) {
        final TypedValue value = mValue;
        if (getValueAt(index*AssetManager.STYLE_NUM_ENTRIES, value)) {
            return mResources.loadColorStateList(value, value.resourceId);
        }
        return null;
    }

    /**
     * Retrieve the integer value for the attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     * 
     * @return Attribute integer value, or defValue if not defined.
     */
    public int getInteger(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type >= TypedValue.TYPE_FIRST_INT
            && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        }

        throw new UnsupportedOperationException("Can't convert to integer: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var>.  Unit 
     * conversions are based on the current {@link DisplayMetrics} 
     * associated with the resources this {@link TypedArray} object 
     * came from. 
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     * 
     * @return Attribute dimension value multiplied by the appropriate 
     * metric, or defValue if not defined.
     * 
     * @see #getDimensionPixelOffset
     * @see #getDimensionPixelSize
     */
    public float getDimension(int index, float defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimension(
                data[index+AssetManager.STYLE_DATA], mResources.mMetrics);
        }

        throw new UnsupportedOperationException("Can't convert to dimension: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var> for use
     * as an offset in raw pixels.  This is the same as
     * {@link #getDimension}, except the returned value is converted to
     * integer pixels for you.  An offset conversion involves simply
     * truncating the base value to an integer.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     * 
     * @return Attribute dimension value multiplied by the appropriate 
     * metric and truncated to integer pixels, or defValue if not defined.
     * 
     * @see #getDimension
     * @see #getDimensionPixelSize
     */
    public int getDimensionPixelOffset(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelOffset(
                data[index+AssetManager.STYLE_DATA], mResources.mMetrics);
        }

        throw new UnsupportedOperationException("Can't convert to dimension: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve a dimensional unit attribute at <var>index</var> for use
     * as a size in raw pixels.  This is the same as
     * {@link #getDimension}, except the returned value is converted to
     * integer pixels for use as a size.  A size conversion involves
     * rounding the base value, and ensuring that a non-zero base value
     * is at least one pixel in size.
     * 
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not defined or
     *                 not a resource.
     * 
     * @return Attribute dimension value multiplied by the appropriate 
     * metric and truncated to integer pixels, or defValue if not defined.
     *  
     * @see #getDimension
     * @see #getDimensionPixelOffset
     */
    public int getDimensionPixelSize(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(
                data[index+AssetManager.STYLE_DATA], mResources.mMetrics);
        }

        throw new UnsupportedOperationException("Can't convert to dimension: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Special version of {@link #getDimensionPixelSize} for retrieving
     * {@link android.view.ViewGroup}'s layout_width and layout_height
     * attributes.  This is only here for performance reasons; applications
     * should use {@link #getDimensionPixelSize}.
     * 
     * @param index Index of the attribute to retrieve.
     * @param name Textual name of attribute for error reporting.
     * 
     * @return Attribute dimension value multiplied by the appropriate 
     * metric and truncated to integer pixels.
     */
    public int getLayoutDimension(int index, String name) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(
                data[index+AssetManager.STYLE_DATA], mResources.mMetrics);
        }

        throw new RuntimeException(getPositionDescription()
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
     * default or contains the wrong type of data.
     * 
     * @return Attribute dimension value multiplied by the appropriate 
     * metric and truncated to integer pixels.
     */
    public int getLayoutDimension(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type >= TypedValue.TYPE_FIRST_INT
                && type <= TypedValue.TYPE_LAST_INT) {
            return data[index+AssetManager.STYLE_DATA];
        } else if (type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(
                data[index+AssetManager.STYLE_DATA], mResources.mMetrics);
        }

        return defValue;
    }

    /**
     * Retrieve a fractional unit attribute at <var>index</var>.
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
     * base value, or defValue if not defined. 
     */
    public float getFraction(int index, int base, int pbase, float defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return defValue;
        } else if (type == TypedValue.TYPE_FRACTION) {
            return TypedValue.complexToFraction(
                data[index+AssetManager.STYLE_DATA], base, pbase);
        }

        throw new UnsupportedOperationException("Can't convert to fraction: type=0x"
                + Integer.toHexString(type));
    }

    /**
     * Retrieve the resource identifier for the attribute at
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
     */
    public int getResourceId(int index, int defValue) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        if (data[index+AssetManager.STYLE_TYPE] != TypedValue.TYPE_NULL) {
            final int resid = data[index+AssetManager.STYLE_RESOURCE_ID];
            if (resid != 0) {
                return resid;
            }
        }
        return defValue;
    }

    /**
     * Retrieve the Drawable for the attribute at <var>index</var>.  This
     * gets the resource ID of the selected attribute, and uses
     * {@link Resources#getDrawable Resources.getDrawable} of the owning
     * Resources object to retrieve its Drawable.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return Drawable for the attribute, or null if not defined.
     */
    public Drawable getDrawable(int index) {
        final TypedValue value = mValue;
        if (getValueAt(index*AssetManager.STYLE_NUM_ENTRIES, value)) {
            if (false) {
                System.out.println("******************************************************************");
                System.out.println("Got drawable resource: type="
                                   + value.type
                                   + " str=" + value.string
                                   + " int=0x" + Integer.toHexString(value.data)
                                   + " cookie=" + value.assetCookie);
                System.out.println("******************************************************************");
            }
            return mResources.loadDrawable(value, value.resourceId);
        }
        return null;
    }

    /**
     * Retrieve the CharSequence[] for the attribute at <var>index</var>.
     * This gets the resource ID of the selected attribute, and uses
     * {@link Resources#getTextArray Resources.getTextArray} of the owning
     * Resources object to retrieve its String[].
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return CharSequence[] for the attribute, or null if not defined.
     */
    public CharSequence[] getTextArray(int index) {
        final TypedValue value = mValue;
        if (getValueAt(index*AssetManager.STYLE_NUM_ENTRIES, value)) {
            if (false) {
                System.out.println("******************************************************************");
                System.out.println("Got drawable resource: type="
                                   + value.type
                                   + " str=" + value.string
                                   + " int=0x" + Integer.toHexString(value.data)
                                   + " cookie=" + value.assetCookie);
                System.out.println("******************************************************************");
            }
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
     * @return Returns true if the value was retrieved, else false. 
     */
    public boolean getValue(int index, TypedValue outValue) {
        return getValueAt(index*AssetManager.STYLE_NUM_ENTRIES, outValue);
    }

    /**
     * Determines whether there is an attribute at <var>index</var>.
     * 
     * @param index Index of attribute to retrieve.
     * 
     * @return True if the attribute has a value, false otherwise.
     */
    public boolean hasValue(int index) {
        index *= AssetManager.STYLE_NUM_ENTRIES;
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        return type != TypedValue.TYPE_NULL;
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
     */
    public TypedValue peekValue(int index) {
        final TypedValue value = mValue;
        if (getValueAt(index*AssetManager.STYLE_NUM_ENTRIES, value)) {
            return value;
        }
        return null;
    }

    /**
     * Returns a message about the parser state suitable for printing error messages.
     */
    public String getPositionDescription() {
        return mXml != null ? mXml.getPositionDescription() : "<internal>";
    }

    /**
     * Give back a previously retrieved StyledAttributes, for later re-use.
     */
    public void recycle() {
        synchronized (mResources.mTmpValue) {
            TypedArray cached = mResources.mCachedStyledAttributes;
            if (cached == null || cached.mData.length < mData.length) {
                mXml = null;
                mResources.mCachedStyledAttributes = this;
            }
        }
    }

    private boolean getValueAt(int index, TypedValue outValue) {
        final int[] data = mData;
        final int type = data[index+AssetManager.STYLE_TYPE];
        if (type == TypedValue.TYPE_NULL) {
            return false;
        }
        outValue.type = type;
        outValue.data = data[index+AssetManager.STYLE_DATA];
        outValue.assetCookie = data[index+AssetManager.STYLE_ASSET_COOKIE];
        outValue.resourceId = data[index+AssetManager.STYLE_RESOURCE_ID];
        outValue.changingConfigurations = data[index+AssetManager.STYLE_CHANGING_CONFIGURATIONS];
        outValue.density = data[index+AssetManager.STYLE_DENSITY];
        if (type == TypedValue.TYPE_STRING) {
            outValue.string = loadStringValueAt(index);
        }
        return true;
    }

    private CharSequence loadStringValueAt(int index) {
        final int[] data = mData;
        final int cookie = data[index+AssetManager.STYLE_ASSET_COOKIE];
        if (cookie < 0) {
            if (mXml != null) {
                return mXml.getPooledString(
                    data[index+AssetManager.STYLE_DATA]);
            }
            return null;
        }
        //System.out.println("Getting pooled from: " + v);
        return mResources.mAssets.getPooledString(
            cookie, data[index+AssetManager.STYLE_DATA]);
    }

    /*package*/ TypedArray(Resources resources, int[] data, int[] indices, int len) {
        mResources = resources;
        mData = data;
        mIndices = indices;
        mLength = len;
    }

    public String toString() {
        return Arrays.toString(mData);
    }
}
