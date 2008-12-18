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

package com.android.layoutlib.bridge;

import com.android.ninepatch.NinePatch;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to provide various convertion method used in handling android resources.
 */
public final class ResourceHelper {
    
    private final static Pattern sFloatPattern = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)(.*)");
    private final static float[] sFloatOut = new float[1];

    private final static TypedValue mValue = new TypedValue();

    /**
     * Returns the color value represented by the given string value
     * @param value the color value
     * @return the color as an int
     * @throw NumberFormatException if the conversion failed.
     */
    static int getColor(String value) {
        if (value != null) {
            if (value.startsWith("#") == false) {
                throw new NumberFormatException();
            }

            value = value.substring(1);
            
            // make sure it's not longer than 32bit
            if (value.length() > 8) {
                throw new NumberFormatException();
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

    /**
     * Returns a drawable from the given value.
     * @param value The value. A path to a 9 patch, a bitmap or a xml based drawable,
     * or an hexadecimal color
     * @param context 
     * @param isFramework indicates whether the resource is a framework resources.
     * Framework resources are cached, and loaded only once.
     */
    public static Drawable getDrawable(String value, BridgeContext context, boolean isFramework) {
        Drawable d = null;
        
        String lowerCaseValue = value.toLowerCase();

        if (lowerCaseValue.endsWith(NinePatch.EXTENSION_9PATCH)) {
            File f = new File(value);
            if (f.isFile()) {
                NinePatch ninePatch = Bridge.getCached9Patch(value,
                        isFramework ? null : context.getProjectKey());
                
                if (ninePatch == null) {
                    try {
                        ninePatch = NinePatch.load(new File(value).toURL(), false /* convert */);
                        
                        Bridge.setCached9Patch(value, ninePatch,
                                isFramework ? null : context.getProjectKey());
                    } catch (MalformedURLException e) {
                        // URL is wrong, we'll return null below
                    } catch (IOException e) {
                        // failed to read the file, we'll return null below.
                    }
                }
                
                if (ninePatch != null) {
                    return new NinePatchDrawable(ninePatch);
                }
            }
            
            return null;
        } else if (lowerCaseValue.endsWith(".xml")) {
            // create a blockparser for the file
            File f = new File(value);
            if (f.isFile()) {
                try {
                    // let the framework inflate the Drawable from the XML file.
                    KXmlParser parser = new KXmlParser();
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                    parser.setInput(new FileReader(f));
                    
                    d = Drawable.createFromXml(context.getResources(),
                            // FIXME: we need to know if this resource is platform or not
                            new BridgeXmlBlockParser(parser, context, false));
                    return d;
                } catch (XmlPullParserException e) {
                    context.getLogger().error(e);
                } catch (FileNotFoundException e) {
                    // will not happen, since we pre-check
                } catch (IOException e) {
                    context.getLogger().error(e);
                }
            }

            return null;
        } else {
            File bmpFile = new File(value);
            if (bmpFile.isFile()) {
                try {
                    Bitmap bitmap = Bridge.getCachedBitmap(value,
                            isFramework ? null : context.getProjectKey());
                    
                    if (bitmap == null) {
                        bitmap = new Bitmap(bmpFile);
                        Bridge.setCachedBitmap(value, bitmap,
                                isFramework ? null : context.getProjectKey());
                    }
                    
                    return new BitmapDrawable(bitmap);
                } catch (IOException e) {
                    // we'll return null below
                    // TODO: log the error.
                }
            } else {
                // attempt to get a color from the value
                try {
                    int color = getColor(value);
                    return new ColorDrawable(color);
                } catch (NumberFormatException e) {
                    // we'll return null below.
                    // TODO: log the error
                }
            }
        }
        
        return null;
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
     * Returns the raw value from the given string.
     * This object is only valid until the next call on to {@link ResourceHelper}.
     */
    public static TypedValue getValue(String s) {
        if (stringToFloat(s, mValue)) {
            return mValue;
        }
        
        return null;
    }
    
    /**
     * Convert the string into a {@link TypedValue}.
     * @param s
     * @param outValue
     * @return true if success.
     */
    public static boolean stringToFloat(String s, TypedValue outValue) {
        // remove the space before and after
        s.trim();
        int len = s.length();

        if (len <= 0) {
            return false;
        }

        // check that there's no non ascii characters.
        char[] buf = s.toCharArray();
        for (int i = 0 ; i < len ; i++) {
            if (buf[i] > 255) {
                return false;
            }
        }

        // check the first character
        if (buf[0] < '0' && buf[0] > '9' && buf[0] != '.') {
            return false;
        }
        
        // now look for the string that is after the float...
        Matcher m = sFloatPattern.matcher(s);
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
                     
                    f *= sFloatOut[0];
                    boolean neg = f < 0;
                    if (neg) {
                        f = -f;
                    }
                    long bits = (long)(f*(1<<23)+.5f);
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
                    return true;
                }
                return false;
            }
            
            // make sure it's only spaces at the end.
            end = end.trim();
    
            if (end.length() == 0) {
                if (outValue != null) {
                    outValue.type = TypedValue.TYPE_FLOAT;
                    outValue.data = Float.floatToIntBits(f);
                    return true;
                }
            }
        }

        return false;
    }
    
    private static boolean parseUnit(String str, TypedValue outValue, float[] outScale) {
        str = str.trim();

        for (UnitEntry unit : sUnitNames) {
            if (unit.name.equals(str)) {
                outValue.type = unit.type;
                outValue.data = unit.unit << TypedValue.COMPLEX_UNIT_SHIFT;
                outScale[0] = unit.scale;
                
                return true;
            }
        }

        return false;
    }
}
