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

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.internal.util.XmlUtils;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.resources.ResourceType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater_Delegate;
import android.view.ViewGroup.LayoutParams;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

/**
 * Custom implementation of TypedArray to handle non compiled resources.
 */
public final class BridgeTypedArray extends TypedArray {

    private final BridgeResources mBridgeResources;
    private final BridgeContext mContext;
    private final boolean mPlatformFile;

    private final ResourceValue[] mResourceData;
    private final String[] mNames;
    private final boolean[] mIsFramework;

    public BridgeTypedArray(BridgeResources resources, BridgeContext context, int len,
            boolean platformFile) {
        super(resources, null, null, 0);
        mBridgeResources = resources;
        mContext = context;
        mPlatformFile = platformFile;
        mResourceData = new ResourceValue[len];
        mNames = new String[len];
        mIsFramework = new boolean[len];
    }

    /**
     * A bridge-specific method that sets a value in the type array
     * @param index the index of the value in the TypedArray
     * @param name the name of the attribute
     * @param isFramework whether the attribute is in the android namespace.
     * @param value the value of the attribute
     */
    public void bridgeSetValue(int index, String name, boolean isFramework, ResourceValue value) {
        mResourceData[index] = value;
        mNames[index] = name;
        mIsFramework[index] = isFramework;
    }

    /**
     * Seals the array after all calls to
     * {@link #bridgeSetValue(int, String, boolean, ResourceValue)} have been done.
     * <p/>This allows to compute the list of non default values, permitting
     * {@link #getIndexCount()} to return the proper value.
     */
    public void sealArray() {
        // fills TypedArray.mIndices which is used to implement getIndexCount/getIndexAt
        // first count the array size
        int count = 0;
        for (int i = 0; i < mResourceData.length; i++) {
            ResourceValue data = mResourceData[i];
            if (data != null) {
                if (RenderResources.REFERENCE_NULL.equals(data.getValue())) {
                    // No need to store this resource value. This saves needless checking for
                    // "@null" every time  an attribute is requested.
                    mResourceData[i] = null;
                } else {
                    count++;
                }
            }
        }

        // allocate the table with an extra to store the size
        mIndices = new int[count+1];
        mIndices[0] = count;

        // fill the array with the indices.
        int index = 1;
        for (int i = 0 ; i < mResourceData.length ; i++) {
            if (mResourceData[i] != null) {
                mIndices[index++] = i;
            }
        }
    }

    /**
     * Return the number of values in this array.
     */
    @Override
    public int length() {
        return mResourceData.length;
    }

    /**
     * Return the Resources object this array was loaded from.
     */
    @Override
    public Resources getResources() {
        return mBridgeResources;
    }

    /**
     * Retrieve the styled string value for the attribute at <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return CharSequence holding string data.  May be styled.  Returns
     *         null if the attribute is not defined.
     */
    @Override
    public CharSequence getText(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (mResourceData[index] != null) {
            // FIXME: handle styled strings!
            return mResourceData[index].getValue();
        }

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
    @Override
    public String getString(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (mResourceData[index] != null) {
            return mResourceData[index].getValue();
        }

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
    @Override
    public boolean getBoolean(int index, boolean defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        String s = mResourceData[index].getValue();
        if (s != null) {
            return XmlUtils.convertValueToBoolean(s, defValue);
        }

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
    @Override
    public int getInt(int index, int defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        String s = mResourceData[index].getValue();

        if (s == null || s.length() == 0) {
            return defValue;
        }

        try {
            return XmlUtils.convertValueToInt(s, defValue);
        } catch (NumberFormatException e) {
            // pass
        }

        // Field is not null and is not an integer.
        // Check for possible constants and try to find them.
        return (int) resolveEnumAttribute(index, defValue);
    }

    /**
     * Searches for the string in the attributes (flag or enums) and returns the integer.
     * If found, it will return an integer matching the value. However, if the value is not found,
     * it returns {@code defValue} which may be a float.
     *
     * @param index Index of attribute to retrieve.
     * @param defValue Value to return if the attribute is not found.
     *
     * @return Attribute int value, or defValue if not defined.
     */
    private float resolveEnumAttribute(int index, float defValue) {
        // Get the map of attribute-constant -> IntegerValue
        Map<String, Integer> map = null;
        if (mIsFramework[index]) {
            map = Bridge.getEnumValues(mNames[index]);
        } else {
            // get the styleable matching the resolved name
            RenderResources res = mContext.getRenderResources();
            ResourceValue attr = res.getProjectResource(ResourceType.ATTR, mNames[index]);
            if (attr instanceof AttrResourceValue) {
                map = ((AttrResourceValue) attr).getAttributeValues();
            }
        }

        if (map != null) {
            // accumulator to store the value of the 1+ constants.
            int result = 0;

            // split the value in case this is a mix of several flags.
            String[] keywords = mResourceData[index].getValue().split("\\|");
            for (String keyword : keywords) {
                Integer i = map.get(keyword.trim());
                if (i != null) {
                    result |= i;
                } else {
                    Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                            String.format(
                                "\"%s\" in attribute \"%2$s\" is not a valid value",
                                keyword, mNames[index]), null);
                }
            }
            return result;
        }

        return defValue;
    }

    /**
     * Retrieve the float value for the attribute at <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return Attribute float value, or defValue if not defined..
     */
    @Override
    public float getFloat(int index, float defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        String s = mResourceData[index].getValue();

        if (s != null) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException e) {
                Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                        String.format(
                            "\"%s\" in attribute \"%2$s\" cannot be converted to float.",
                            s, mNames[index]), null);

                // we'll return the default value below.
            }
        }
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
    @Override
    public int getColor(int index, int defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        ColorStateList colorStateList = ResourceHelper.getColorStateList(
                mResourceData[index], mContext);
        if (colorStateList != null) {
            return colorStateList.getDefaultColor();
        }

        return defValue;
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
    @Override
    public ColorStateList getColorStateList(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (mResourceData[index] == null) {
            return null;
        }

        ResourceValue resValue = mResourceData[index];
        String value = resValue.getValue();

        if (value == null) {
            return null;
        }

        // let the framework inflate the ColorStateList from the XML file.
        File f = new File(value);
        if (f.isFile()) {
            try {
                XmlPullParser parser = ParserFactory.create(f);

                BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(
                        parser, mContext, resValue.isFramework());
                try {
                    return ColorStateList.createFromXml(mContext.getResources(), blockParser);
                } finally {
                    blockParser.ensurePopped();
                }
            } catch (XmlPullParserException e) {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        "Failed to configure parser for " + value, e, null);
                return null;
            } catch (Exception e) {
                // this is an error and not warning since the file existence is checked before
                // attempting to parse it.
                Bridge.getLog().error(LayoutLog.TAG_RESOURCES_READ,
                        "Failed to parse file " + value, e, null);

                return null;
            }
        }

        try {
            int color = ResourceHelper.getColor(value);
            return ColorStateList.valueOf(color);
        } catch (NumberFormatException e) {
            Bridge.getLog().error(LayoutLog.TAG_RESOURCES_FORMAT, e.getMessage(), e, null);
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
    @Override
    public int getInteger(int index, int defValue) {
        return getInt(index, defValue);
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
    @Override
    public float getDimension(int index, float defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        String s = mResourceData[index].getValue();

        if (s == null) {
            return defValue;
        }

        if (ResourceHelper.parseFloatAttribute(mNames[index], s, mValue, true)) {
            return mValue.getDimension(mBridgeResources.getDisplayMetrics());
        }

        // looks like we were unable to resolve the dimension value. Check if it is an attribute
        // constant.
        return resolveEnumAttribute(index, defValue);
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
    @Override
    public int getDimensionPixelOffset(int index, int defValue) {
        return (int) getDimension(index, defValue);
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
    @Override
    public int getDimensionPixelSize(int index, int defValue) {
        try {
            return getDimension(index);
        } catch (RuntimeException e) {
            if (mResourceData[index] != null) {
                String s = mResourceData[index].getValue();

                if (s != null) {
                    // looks like we were unable to resolve the dimension value
                    Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                            String.format(
                                "\"%1$s\" in attribute \"%2$s\" is not a valid format.",
                                s, mNames[index]), null);
                }
            }

            return defValue;
        }
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
    @Override
    public int getLayoutDimension(int index, String name) {
        try {
            // this will throw an exception
            return getDimension(index);
        } catch (RuntimeException e) {

            if (LayoutInflater_Delegate.sIsInInclude) {
                throw new RuntimeException();
            }

            Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                    "You must supply a " + name + " attribute.", null);

            return 0;
        }
    }

    @Override
    public int getLayoutDimension(int index, int defValue) {
        return getDimensionPixelSize(index, defValue);
    }

    private int getDimension(int index) {
        if (mResourceData[index] == null) {
            throw new RuntimeException();
        }

        String s = mResourceData[index].getValue();

        if (s == null) {
            throw new RuntimeException();
        } else if (s.equals(BridgeConstants.MATCH_PARENT) ||
                s.equals(BridgeConstants.FILL_PARENT)) {
            return LayoutParams.MATCH_PARENT;
        } else if (s.equals(BridgeConstants.WRAP_CONTENT)) {
            return LayoutParams.WRAP_CONTENT;
        }

        if (ResourceHelper.parseFloatAttribute(mNames[index], s, mValue, true)) {
            float f = mValue.getDimension(mBridgeResources.getDisplayMetrics());

            final int res = (int)(f+0.5f);
            if (res != 0) return res;
            if (f == 0) return 0;
            if (f > 0) return 1;
        }

        throw new RuntimeException();
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
    @Override
    public float getFraction(int index, int base, int pbase, float defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        if (mResourceData[index] == null) {
            return defValue;
        }

        String value = mResourceData[index].getValue();
        if (value == null) {
            return defValue;
        }

        if (ResourceHelper.parseFloatAttribute(mNames[index], value, mValue, false)) {
            return mValue.getFraction(base, pbase);
        }

        // looks like we were unable to resolve the fraction value
        Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                String.format(
                        "\"%1$s\" in attribute \"%2$s\" cannot be converted to a fraction.",
                        value, mNames[index]), null);

        return defValue;
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
    @Override
    public int getResourceId(int index, int defValue) {
        if (index < 0 || index >= mResourceData.length) {
            return defValue;
        }

        // get the Resource for this index
        ResourceValue resValue = mResourceData[index];

        // no data, return the default value.
        if (resValue == null) {
            return defValue;
        }

        // check if this is a style resource
        if (resValue instanceof StyleResourceValue) {
            // get the id that will represent this style.
            return mContext.getDynamicIdByStyle((StyleResourceValue)resValue);
        }

        // if the attribute was a reference to a resource, and not a declaration of an id (@+id),
        // then the xml attribute value was "resolved" which leads us to a ResourceValue with a
        // valid getType() and getName() returning a resource name.
        // (and getValue() returning null!). We need to handle this!
        if (resValue.getResourceType() != null) {
            // if this is a framework id
            if (mPlatformFile || resValue.isFramework()) {
                // look for idName in the android R classes
                return mContext.getFrameworkResourceValue(
                        resValue.getResourceType(), resValue.getName(), defValue);
            }

            // look for idName in the project R class.
            return mContext.getProjectResourceValue(
                    resValue.getResourceType(), resValue.getName(), defValue);
        }

        // else, try to get the value, and resolve it somehow.
        String value = resValue.getValue();
        if (value == null) {
            return defValue;
        }

        // if the value is just an integer, return it.
        try {
            int i = Integer.parseInt(value);
            if (Integer.toString(i).equals(value)) {
                return i;
            }
        } catch (NumberFormatException e) {
            // pass
        }

        // Handle the @id/<name>, @+id/<name> and @android:id/<name>
        // We need to return the exact value that was compiled (from the various R classes),
        // as these values can be reused internally with calls to findViewById().
        // There's a trick with platform layouts that not use "android:" but their IDs are in
        // fact in the android.R and com.android.internal.R classes.
        // The field mPlatformFile will indicate that all IDs are to be looked up in the android R
        // classes exclusively.

        // if this is a reference to an id, find it.
        if (value.startsWith("@id/") || value.startsWith("@+") ||
                value.startsWith("@android:id/")) {

            int pos = value.indexOf('/');
            String idName = value.substring(pos + 1);

            // if this is a framework id
            if (mPlatformFile || value.startsWith("@android") || value.startsWith("@+android")) {
                // look for idName in the android R classes
                return mContext.getFrameworkResourceValue(ResourceType.ID, idName, defValue);
            }

            // look for idName in the project R class.
            return mContext.getProjectResourceValue(ResourceType.ID, idName, defValue);
        }

        // not a direct id valid reference? resolve it
        Integer idValue;

        if (resValue.isFramework()) {
            idValue = Bridge.getResourceId(resValue.getResourceType(),
                    resValue.getName());
        } else {
            idValue = mContext.getProjectCallback().getResourceId(
                    resValue.getResourceType(), resValue.getName());
        }

        if (idValue != null) {
            return idValue;
        }

        Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_RESOLVE,
                String.format(
                    "Unable to resolve id \"%1$s\" for attribute \"%2$s\"", value, mNames[index]),
                    resValue);

        return defValue;
    }

    @Override
    public int getThemeAttributeId(int index, int defValue) {
        // TODO: Get the right Theme Attribute ID to enable caching of the drawables.
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
    @Override
    public Drawable getDrawable(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (mResourceData[index] == null) {
            return null;
        }

        ResourceValue value = mResourceData[index];
        String stringValue = value.getValue();
        if (stringValue == null) {
            return null;
        }

        return ResourceHelper.getDrawable(value, mContext);
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
    @Override
    public CharSequence[] getTextArray(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (mResourceData[index] == null) {
            return null;
        }

        String value = mResourceData[index].getValue();
        if (value != null) {
            return new CharSequence[] { value };
        }

        Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                String.format(
                    String.format("Unknown value for getTextArray(%d) => %s", //DEBUG
                    index, mResourceData[index].getName())), null);

        return null;
    }

    @Override
    public int[] extractThemeAttrs() {
        // The drawables are always inflated with a Theme and we don't care about caching. So,
        // just return.
        return null;
    }

    @Override
    public int getChangingConfigurations() {
        // We don't care about caching. Any change in configuration is a fresh render. So,
        // just return.
        return 0;
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
    @Override
    public boolean getValue(int index, TypedValue outValue) {
        if (index < 0 || index >= mResourceData.length) {
            return false;
        }

        if (mResourceData[index] == null) {
            return false;
        }

        String s = mResourceData[index].getValue();

        return ResourceHelper.parseFloatAttribute(mNames[index], s, outValue, false);
    }

    /**
     * Determines whether there is an attribute at <var>index</var>.
     *
     * @param index Index of attribute to retrieve.
     *
     * @return True if the attribute has a value, false otherwise.
     */
    @Override
    public boolean hasValue(int index) {
        //noinspection SimplifiableIfStatement
        if (index < 0 || index >= mResourceData.length) {
            return false;
        }

        return mResourceData[index] != null;
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
    @Override
    public TypedValue peekValue(int index) {
        if (index < 0 || index >= mResourceData.length) {
            return null;
        }

        if (getValue(index, mValue)) {
            return mValue;
        }

        return null;
    }

    /**
     * Returns a message about the parser state suitable for printing error messages.
     */
    @Override
    public String getPositionDescription() {
        return "<internal -- stub if needed>";
    }

    /**
     * Give back a previously retrieved TypedArray, for later re-use.
     */
    @Override
    public void recycle() {
        // pass
    }

    @Override
    public String toString() {
        return Arrays.toString(mResourceData);
    }

    static TypedArray obtain(Resources res, int len) {
        return res instanceof BridgeResources ?
                new BridgeTypedArray(((BridgeResources) res), null, len, true) : null;
    }
}
