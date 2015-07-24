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

package com.android.layoutlib.bridge.impl;

import com.android.SdkConstants;
import com.android.ide.common.rendering.api.DensityBasedResourceValue;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.internal.util.XmlUtils;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.ninepatch.NinePatch;
import com.android.ninepatch.NinePatchChunk;
import com.android.resources.Density;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.content.res.ColorStateList;
import android.content.res.Resources.Theme;
import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.NinePatch_Delegate;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.util.TypedValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to provide various conversion method used in handling android resources.
 */
public final class ResourceHelper {

    private final static Pattern sFloatPattern = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)(.*)");
    private final static float[] sFloatOut = new float[1];

    private final static TypedValue mValue = new TypedValue();

    /**
     * Returns the color value represented by the given string value
     * @param value the color value
     * @return the color as an int
     * @throws NumberFormatException if the conversion failed.
     */
    public static int getColor(String value) {
        if (value != null) {
            if (!value.startsWith("#")) {
                if (value.startsWith(SdkConstants.PREFIX_THEME_REF)) {
                    throw new NumberFormatException(String.format(
                            "Attribute '%s' not found. Are you using the right theme?", value));
                }
                throw new NumberFormatException(
                        String.format("Color value '%s' must start with #", value));
            }

            value = value.substring(1);

            // make sure it's not longer than 32bit
            if (value.length() > 8) {
                throw new NumberFormatException(String.format(
                        "Color value '%s' is too long. Format is either" +
                        "#AARRGGBB, #RRGGBB, #RGB, or #ARGB",
                        value));
            }

            if (value.length() == 3) { // RGB format
                char[] color = new char[8];
                color[0] = color[1] = 'F';
                color[2] = color[3] = value.charAt(0);
                color[4] = color[5] = value.charAt(1);
                color[6] = color[7] = value.charAt(2);
                value = new String(color);
            } else if (value.length() == 4) { // ARGB format
                char[] color = new char[8];
                color[0] = color[1] = value.charAt(0);
                color[2] = color[3] = value.charAt(1);
                color[4] = color[5] = value.charAt(2);
                color[6] = color[7] = value.charAt(3);
                value = new String(color);
            } else if (value.length() == 6) {
                value = "FF" + value;
            }

            // this is a RRGGBB or AARRGGBB value

            // Integer.parseInt will fail to parse strings like "ff191919", so we use
            // a Long, but cast the result back into an int, since we know that we're only
            // dealing with 32 bit values.
            return (int)Long.parseLong(value, 16);
        }

        throw new NumberFormatException();
    }

    public static ColorStateList getColorStateList(ResourceValue resValue, BridgeContext context) {
        String value = resValue.getValue();
        if (value != null && !RenderResources.REFERENCE_NULL.equals(value)) {
            // first check if the value is a file (xml most likely)
            File f = new File(value);
            if (f.isFile()) {
                try {
                    // let the framework inflate the ColorStateList from the XML file, by
                    // providing an XmlPullParser
                    XmlPullParser parser = ParserFactory.create(f);

                    BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(
                            parser, context, resValue.isFramework());
                    try {
                        return ColorStateList.createFromXml(context.getResources(), blockParser);
                    } finally {
                        blockParser.ensurePopped();
                    }
                } catch (XmlPullParserException e) {
                    Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                            "Failed to configure parser for " + value, e, null /*data*/);
                    // we'll return null below.
                } catch (Exception e) {
                    // this is an error and not warning since the file existence is
                    // checked before attempting to parse it.
                    Bridge.getLog().error(LayoutLog.TAG_RESOURCES_READ,
                            "Failed to parse file " + value, e, null /*data*/);

                    return null;
                }
            } else {
                // try to load the color state list from an int
                try {
                    int color = ResourceHelper.getColor(value);
                    return ColorStateList.valueOf(color);
                } catch (NumberFormatException e) {
                    Bridge.getLog().error(LayoutLog.TAG_RESOURCES_FORMAT,
                            "Failed to convert " + value + " into a ColorStateList", e,
                            null /*data*/);
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Returns a drawable from the given value.
     * @param value The value that contains a path to a 9 patch, a bitmap or a xml based drawable,
     * or an hexadecimal color
     * @param context the current context
     */
    public static Drawable getDrawable(ResourceValue value, BridgeContext context) {
        return getDrawable(value, context, null);
    }

    /**
     * Returns a drawable from the given value.
     * @param value The value that contains a path to a 9 patch, a bitmap or a xml based drawable,
     * or an hexadecimal color
     * @param context the current context
     * @param theme the theme to be used to inflate the drawable.
     */
    public static Drawable getDrawable(ResourceValue value, BridgeContext context, Theme theme) {
        if (value == null) {
            return null;
        }
        String stringValue = value.getValue();
        if (RenderResources.REFERENCE_NULL.equals(stringValue)) {
            return null;
        }

        String lowerCaseValue = stringValue.toLowerCase();

        Density density = Density.MEDIUM;
        if (value instanceof DensityBasedResourceValue) {
            density =
                ((DensityBasedResourceValue)value).getResourceDensity();
        }


        if (lowerCaseValue.endsWith(NinePatch.EXTENSION_9PATCH)) {
            File file = new File(stringValue);
            if (file.isFile()) {
                try {
                    return getNinePatchDrawable(
                            new FileInputStream(file), density, value.isFramework(),
                            stringValue, context);
                } catch (IOException e) {
                    // failed to read the file, we'll return null below.
                    Bridge.getLog().error(LayoutLog.TAG_RESOURCES_READ,
                            "Failed lot load " + file.getAbsolutePath(), e, null /*data*/);
                }
            }

            return null;
        } else if (lowerCaseValue.endsWith(".xml")) {
            // create a block parser for the file
            File f = new File(stringValue);
            if (f.isFile()) {
                try {
                    // let the framework inflate the Drawable from the XML file.
                    XmlPullParser parser = ParserFactory.create(f);

                    BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(
                            parser, context, value.isFramework());
                    try {
                        return Drawable.createFromXml(context.getResources(), blockParser, theme);
                    } finally {
                        blockParser.ensurePopped();
                    }
                } catch (Exception e) {
                    // this is an error and not warning since the file existence is checked before
                    // attempting to parse it.
                    Bridge.getLog().error(null, "Failed to parse file " + stringValue,
                            e, null /*data*/);
                }
            } else {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        String.format("File %s does not exist (or is not a file)", stringValue),
                        null /*data*/);
            }

            return null;
        } else {
            File bmpFile = new File(stringValue);
            if (bmpFile.isFile()) {
                try {
                    Bitmap bitmap = Bridge.getCachedBitmap(stringValue,
                            value.isFramework() ? null : context.getProjectKey());

                    if (bitmap == null) {
                        bitmap = Bitmap_Delegate.createBitmap(bmpFile, false /*isMutable*/,
                                density);
                        Bridge.setCachedBitmap(stringValue, bitmap,
                                value.isFramework() ? null : context.getProjectKey());
                    }

                    return new BitmapDrawable(context.getResources(), bitmap);
                } catch (IOException e) {
                    // we'll return null below
                    Bridge.getLog().error(LayoutLog.TAG_RESOURCES_READ,
                            "Failed lot load " + bmpFile.getAbsolutePath(), e, null /*data*/);
                }
            } else {
                // attempt to get a color from the value
                try {
                    int color = getColor(stringValue);
                    return new ColorDrawable(color);
                } catch (NumberFormatException e) {
                    // we'll return null below.
                    Bridge.getLog().error(LayoutLog.TAG_RESOURCES_FORMAT,
                            "Failed to convert " + stringValue + " into a drawable", e,
                            null /*data*/);
                }
            }
        }

        return null;
    }

    private static Drawable getNinePatchDrawable(InputStream inputStream, Density density,
            boolean isFramework, String cacheKey, BridgeContext context) throws IOException {
        // see if we still have both the chunk and the bitmap in the caches
        NinePatchChunk chunk = Bridge.getCached9Patch(cacheKey,
                isFramework ? null : context.getProjectKey());
        Bitmap bitmap = Bridge.getCachedBitmap(cacheKey,
                isFramework ? null : context.getProjectKey());

        // if either chunk or bitmap is null, then we reload the 9-patch file.
        if (chunk == null || bitmap == null) {
            try {
                NinePatch ninePatch = NinePatch.load(inputStream, true /*is9Patch*/,
                        false /* convert */);
                if (ninePatch != null) {
                    if (chunk == null) {
                        chunk = ninePatch.getChunk();

                        Bridge.setCached9Patch(cacheKey, chunk,
                                isFramework ? null : context.getProjectKey());
                    }

                    if (bitmap == null) {
                        bitmap = Bitmap_Delegate.createBitmap(ninePatch.getImage(),
                                false /*isMutable*/,
                                density);

                        Bridge.setCachedBitmap(cacheKey, bitmap,
                                isFramework ? null : context.getProjectKey());
                    }
                }
            } catch (MalformedURLException e) {
                // URL is wrong, we'll return null below
            }
        }

        if (chunk != null && bitmap != null) {
            int[] padding = chunk.getPadding();
            Rect paddingRect = new Rect(padding[0], padding[1], padding[2], padding[3]);

            return new NinePatchDrawable(context.getResources(), bitmap,
                    NinePatch_Delegate.serialize(chunk),
                    paddingRect, null);
        }

        return null;
    }

    /**
     * Looks for an attribute in the current theme.
     *
     * @param resources the render resources
     * @param name the name of the attribute
     * @param defaultValue the default value.
     * @param isFrameworkAttr if the attribute is in android namespace
     * @return the value of the attribute or the default one if not found.
     */
    public static boolean getBooleanThemeValue(@NonNull RenderResources resources, String name,
            boolean isFrameworkAttr, boolean defaultValue) {
        ResourceValue value = resources.findItemInTheme(name, isFrameworkAttr);
        value = resources.resolveResValue(value);
        if (value == null) {
            return defaultValue;
        }
        return XmlUtils.convertValueToBoolean(value.getValue(), defaultValue);
    }

    // ------- TypedValue stuff
    // This is taken from //device/libs/utils/ResourceTypes.cpp

    private static final class UnitEntry {
        String name;
        int type;
        int unit;
        float scale;

        UnitEntry(String name, int type, int unit, float scale) {
            this.name = name;
            this.type = type;
            this.unit = unit;
            this.scale = scale;
        }
    }

    private final static UnitEntry[] sUnitNames = new UnitEntry[] {
        new UnitEntry("px", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_PX, 1.0f),
        new UnitEntry("dip", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_DIP, 1.0f),
        new UnitEntry("dp", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_DIP, 1.0f),
        new UnitEntry("sp", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_SP, 1.0f),
        new UnitEntry("pt", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_PT, 1.0f),
        new UnitEntry("in", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_IN, 1.0f),
        new UnitEntry("mm", TypedValue.TYPE_DIMENSION, TypedValue.COMPLEX_UNIT_MM, 1.0f),
        new UnitEntry("%", TypedValue.TYPE_FRACTION, TypedValue.COMPLEX_UNIT_FRACTION, 1.0f/100),
        new UnitEntry("%p", TypedValue.TYPE_FRACTION, TypedValue.COMPLEX_UNIT_FRACTION_PARENT, 1.0f/100),
    };

    /**
     * Returns the raw value from the given attribute float-type value string.
     * This object is only valid until the next call on to {@link ResourceHelper}.
     */
    public static TypedValue getValue(String attribute, String value, boolean requireUnit) {
        if (parseFloatAttribute(attribute, value, mValue, requireUnit)) {
            return mValue;
        }

        return null;
    }

    /**
     * Parse a float attribute and return the parsed value into a given TypedValue.
     * @param attribute the name of the attribute. Can be null if <var>requireUnit</var> is false.
     * @param value the string value of the attribute
     * @param outValue the TypedValue to receive the parsed value
     * @param requireUnit whether the value is expected to contain a unit.
     * @return true if success.
     */
    public static boolean parseFloatAttribute(String attribute, @NonNull String value,
            TypedValue outValue, boolean requireUnit) {
        assert !requireUnit || attribute != null;

        // remove the space before and after
        value = value.trim();
        int len = value.length();

        if (len <= 0) {
            return false;
        }

        // check that there's no non ascii characters.
        char[] buf = value.toCharArray();
        for (int i = 0 ; i < len ; i++) {
            if (buf[i] > 255) {
                return false;
            }
        }

        // check the first character
        if ((buf[0] < '0' || buf[0] > '9') && buf[0] != '.' && buf[0] != '-' && buf[0] != '+') {
            return false;
        }

        // now look for the string that is after the float...
        Matcher m = sFloatPattern.matcher(value);
        if (m.matches()) {
            String f_str = m.group(1);
            String end = m.group(2);

            float f;
            try {
                f = Float.parseFloat(f_str);
            } catch (NumberFormatException e) {
                // this shouldn't happen with the regexp above.
                return false;
            }

            if (end.length() > 0 && end.charAt(0) != ' ') {
                // Might be a unit...
                if (parseUnit(end, outValue, sFloatOut)) {
                    computeTypedValue(outValue, f, sFloatOut[0]);
                    return true;
                }
                return false;
            }

            // make sure it's only spaces at the end.
            end = end.trim();

            if (end.length() == 0) {
                if (outValue != null) {
                    if (!requireUnit) {
                        outValue.type = TypedValue.TYPE_FLOAT;
                        outValue.data = Float.floatToIntBits(f);
                    } else {
                        // no unit when required? Use dp and out an error.
                        applyUnit(sUnitNames[1], outValue, sFloatOut);
                        computeTypedValue(outValue, f, sFloatOut[0]);

                        Bridge.getLog().error(LayoutLog.TAG_RESOURCES_RESOLVE,
                                String.format(
                                        "Dimension \"%1$s\" in attribute \"%2$s\" is missing unit!",
                                        value, attribute),
                                null);
                    }
                    return true;
                }
            }
        }

        return false;
    }

    private static void computeTypedValue(TypedValue outValue, float value, float scale) {
        value *= scale;
        boolean neg = value < 0;
        if (neg) {
            value = -value;
        }
        long bits = (long)(value*(1<<23)+.5f);
        int radix;
        int shift;
        if ((bits&0x7fffff) == 0) {
            // Always use 23p0 if there is no fraction, just to make
            // things easier to read.
            radix = TypedValue.COMPLEX_RADIX_23p0;
            shift = 23;
        } else if ((bits&0xffffffffff800000L) == 0) {
            // Magnitude is zero -- can fit in 0 bits of precision.
            radix = TypedValue.COMPLEX_RADIX_0p23;
            shift = 0;
        } else if ((bits&0xffffffff80000000L) == 0) {
            // Magnitude can fit in 8 bits of precision.
            radix = TypedValue.COMPLEX_RADIX_8p15;
            shift = 8;
        } else if ((bits&0xffffff8000000000L) == 0) {
            // Magnitude can fit in 16 bits of precision.
            radix = TypedValue.COMPLEX_RADIX_16p7;
            shift = 16;
        } else {
            // Magnitude needs entire range, so no fractional part.
            radix = TypedValue.COMPLEX_RADIX_23p0;
            shift = 23;
        }
        int mantissa = (int)(
            (bits>>shift) & TypedValue.COMPLEX_MANTISSA_MASK);
        if (neg) {
            mantissa = (-mantissa) & TypedValue.COMPLEX_MANTISSA_MASK;
        }
        outValue.data |=
            (radix<<TypedValue.COMPLEX_RADIX_SHIFT)
            | (mantissa<<TypedValue.COMPLEX_MANTISSA_SHIFT);
    }

    private static boolean parseUnit(String str, TypedValue outValue, float[] outScale) {
        str = str.trim();

        for (UnitEntry unit : sUnitNames) {
            if (unit.name.equals(str)) {
                applyUnit(unit, outValue, outScale);
                return true;
            }
        }

        return false;
    }

    private static void applyUnit(UnitEntry unit, TypedValue outValue, float[] outScale) {
        outValue.type = unit.type;
        // COMPLEX_UNIT_SHIFT is 0 and hence intelliJ complains about it. Suppress the warning.
        //noinspection PointlessBitwiseExpression
        outValue.data = unit.unit << TypedValue.COMPLEX_UNIT_SHIFT;
        outScale[0] = unit.scale;
    }
}

