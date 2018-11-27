/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@hide} */
public class XmlUtils {

    private static final String STRING_ARRAY_SEPARATOR = ":";

    @UnsupportedAppUsage
    public static void skipCurrentTag(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
        }
    }

    public static final int
    convertValueToList(CharSequence value, String[] options, int defaultValue)
    {
        if (!TextUtils.isEmpty(value)) {
            for (int i = 0; i < options.length; i++) {
                if (value.equals(options[i]))
                    return i;
            }
        }

        return defaultValue;
    }

    @UnsupportedAppUsage
    public static final boolean
    convertValueToBoolean(CharSequence value, boolean defaultValue)
    {
        boolean result = false;

        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        if (value.equals("1")
        ||  value.equals("true")
        ||  value.equals("TRUE"))
            result = true;

        return result;
    }

    @UnsupportedAppUsage
    public static final int
    convertValueToInt(CharSequence charSeq, int defaultValue)
    {
        if (TextUtils.isEmpty(charSeq)) {
            return defaultValue;
        }

        String nm = charSeq.toString();

        // XXX This code is copied from Integer.decode() so we don't
        // have to instantiate an Integer!

        int value;
        int sign = 1;
        int index = 0;
        int len = nm.length();
        int base = 10;

        if ('-' == nm.charAt(0)) {
            sign = -1;
            index++;
        }

        if ('0' == nm.charAt(index)) {
            //  Quick check for a zero by itself
            if (index == (len - 1))
                return 0;

            char    c = nm.charAt(index + 1);

            if ('x' == c || 'X' == c) {
                index += 2;
                base = 16;
            } else {
                index++;
                base = 8;
            }
        }
        else if ('#' == nm.charAt(index))
        {
            index++;
            base = 16;
        }

        return Integer.parseInt(nm.substring(index), base) * sign;
    }

    public static int convertValueToUnsignedInt(String value, int defaultValue) {
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        return parseUnsignedIntAttribute(value);
    }

    public static int parseUnsignedIntAttribute(CharSequence charSeq) {
        String  value = charSeq.toString();

        long    bits;
        int     index = 0;
        int     len = value.length();
        int     base = 10;

        if ('0' == value.charAt(index)) {
            //  Quick check for zero by itself
            if (index == (len - 1))
                return 0;

            char    c = value.charAt(index + 1);

            if ('x' == c || 'X' == c) {     //  check for hex
                index += 2;
                base = 16;
            } else {                        //  check for octal
                index++;
                base = 8;
            }
        } else if ('#' == value.charAt(index)) {
            index++;
            base = 16;
        }

        return (int) Long.parseLong(value.substring(index), base);
    }

    /**
     * Flatten a Map into an output stream as XML.  The map can later be
     * read back with readMapXml().
     *
     * @param val The map to be flattened.
     * @param out Where to write the XML data.
     *
     * @see #writeMapXml(Map, String, XmlSerializer)
     * @see #writeListXml
     * @see #writeValueXml
     * @see #readMapXml
     */
    @UnsupportedAppUsage
    public static final void writeMapXml(Map val, OutputStream out)
            throws XmlPullParserException, java.io.IOException {
        XmlSerializer serializer = new FastXmlSerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        writeMapXml(val, null, serializer);
        serializer.endDocument();
    }

    /**
     * Flatten a List into an output stream as XML.  The list can later be
     * read back with readListXml().
     *
     * @param val The list to be flattened.
     * @param out Where to write the XML data.
     *
     * @see #writeListXml(List, String, XmlSerializer)
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readListXml
     */
    public static final void writeListXml(List val, OutputStream out)
    throws XmlPullParserException, java.io.IOException
    {
        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        writeListXml(val, null, serializer);
        serializer.endDocument();
    }

    /**
     * Flatten a Map into an XmlSerializer.  The map can later be read back
     * with readThisMapXml().
     *
     * @param val The map to be flattened.
     * @param name Name attribute to include with this list's tag, or null for
     *             none.
     * @param out XmlSerializer to write the map into.
     *
     * @see #writeMapXml(Map, OutputStream)
     * @see #writeListXml
     * @see #writeValueXml
     * @see #readMapXml
     */
    public static final void writeMapXml(Map val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {
        writeMapXml(val, name, out, null);
    }

    /**
     * Flatten a Map into an XmlSerializer.  The map can later be read back
     * with readThisMapXml().
     *
     * @param val The map to be flattened.
     * @param name Name attribute to include with this list's tag, or null for
     *             none.
     * @param out XmlSerializer to write the map into.
     * @param callback Method to call when an Object type is not recognized.
     *
     * @see #writeMapXml(Map, OutputStream)
     * @see #writeListXml
     * @see #writeValueXml
     * @see #readMapXml
     *
     * @hide
     */
    public static final void writeMapXml(Map val, String name, XmlSerializer out,
            WriteMapCallback callback) throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "map");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        writeMapXml(val, out, callback);

        out.endTag(null, "map");
    }

    /**
     * Flatten a Map into an XmlSerializer.  The map can later be read back
     * with readThisMapXml(). This method presumes that the start tag and
     * name attribute have already been written and does not write an end tag.
     *
     * @param val The map to be flattened.
     * @param out XmlSerializer to write the map into.
     *
     * @see #writeMapXml(Map, OutputStream)
     * @see #writeListXml
     * @see #writeValueXml
     * @see #readMapXml
     *
     * @hide
     */
    public static final void writeMapXml(Map val, XmlSerializer out,
            WriteMapCallback callback) throws XmlPullParserException, java.io.IOException {
        if (val == null) {
            return;
        }

        Set s = val.entrySet();
        Iterator i = s.iterator();

        while (i.hasNext()) {
            Map.Entry e = (Map.Entry)i.next();
            writeValueXml(e.getValue(), (String)e.getKey(), out, callback);
        }
    }

    /**
     * Flatten a List into an XmlSerializer.  The list can later be read back
     * with readThisListXml().
     *
     * @param val The list to be flattened.
     * @param name Name attribute to include with this list's tag, or null for
     *             none.
     * @param out XmlSerializer to write the list into.
     *
     * @see #writeListXml(List, OutputStream)
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readListXml
     */
    public static final void writeListXml(List val, String name, XmlSerializer out)
    throws XmlPullParserException, java.io.IOException
    {
        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "list");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        int N = val.size();
        int i=0;
        while (i < N) {
            writeValueXml(val.get(i), null, out);
            i++;
        }

        out.endTag(null, "list");
    }
    
    public static final void writeSetXml(Set val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {
        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }
        
        out.startTag(null, "set");
        if (name != null) {
            out.attribute(null, "name", name);
        }
        
        for (Object v : val) {
            writeValueXml(v, null, out);
        }
        
        out.endTag(null, "set");
    }

    /**
     * Flatten a byte[] into an XmlSerializer.  The list can later be read back
     * with readThisByteArrayXml().
     *
     * @param val The byte array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     */
    public static final void writeByteArrayXml(byte[] val, String name,
            XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "byte-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        StringBuilder sb = new StringBuilder(val.length*2);
        for (int i=0; i<N; i++) {
            int b = val[i];
            int h = (b >> 4) & 0x0f;
            sb.append((char)(h >= 10 ? ('a'+h-10) : ('0'+h)));
            h = b & 0x0f;
            sb.append((char)(h >= 10 ? ('a'+h-10) : ('0'+h)));
        }

        out.text(sb.toString());

        out.endTag(null, "byte-array");
    }

    /**
     * Flatten an int[] into an XmlSerializer.  The list can later be read back
     * with readThisIntArrayXml().
     *
     * @param val The int array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readThisIntArrayXml
     */
    public static final void writeIntArrayXml(int[] val, String name,
            XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "int-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attribute(null, "value", Integer.toString(val[i]));
            out.endTag(null, "item");
        }

        out.endTag(null, "int-array");
    }

    /**
     * Flatten a long[] into an XmlSerializer.  The list can later be read back
     * with readThisLongArrayXml().
     *
     * @param val The long array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readThisIntArrayXml
     */
    public static final void writeLongArrayXml(long[] val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "long-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attribute(null, "value", Long.toString(val[i]));
            out.endTag(null, "item");
        }

        out.endTag(null, "long-array");
    }

    /**
     * Flatten a double[] into an XmlSerializer.  The list can later be read back
     * with readThisDoubleArrayXml().
     *
     * @param val The double array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readThisIntArrayXml
     */
    public static final void writeDoubleArrayXml(double[] val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "double-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attribute(null, "value", Double.toString(val[i]));
            out.endTag(null, "item");
        }

        out.endTag(null, "double-array");
    }

    /**
     * Flatten a String[] into an XmlSerializer.  The list can later be read back
     * with readThisStringArrayXml().
     *
     * @param val The String array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readThisIntArrayXml
     */
    public static final void writeStringArrayXml(String[] val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "string-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attribute(null, "value", val[i]);
            out.endTag(null, "item");
        }

        out.endTag(null, "string-array");
    }

    /**
     * Flatten a boolean[] into an XmlSerializer.  The list can later be read back
     * with readThisBooleanArrayXml().
     *
     * @param val The boolean array to be flattened.
     * @param name Name attribute to include with this array's tag, or null for
     *             none.
     * @param out XmlSerializer to write the array into.
     *
     * @see #writeMapXml
     * @see #writeValueXml
     * @see #readThisIntArrayXml
     */
    public static final void writeBooleanArrayXml(boolean[] val, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {

        if (val == null) {
            out.startTag(null, "null");
            out.endTag(null, "null");
            return;
        }

        out.startTag(null, "boolean-array");
        if (name != null) {
            out.attribute(null, "name", name);
        }

        final int N = val.length;
        out.attribute(null, "num", Integer.toString(N));

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attribute(null, "value", Boolean.toString(val[i]));
            out.endTag(null, "item");
        }

        out.endTag(null, "boolean-array");
    }

    /**
     * Flatten an object's value into an XmlSerializer.  The value can later
     * be read back with readThisValueXml().
     *
     * Currently supported value types are: null, String, Integer, Long,
     * Float, Double Boolean, Map, List.
     *
     * @param v The object to be flattened.
     * @param name Name attribute to include with this value's tag, or null
     *             for none.
     * @param out XmlSerializer to write the object into.
     *
     * @see #writeMapXml
     * @see #writeListXml
     * @see #readValueXml
     */
    public static final void writeValueXml(Object v, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {
        writeValueXml(v, name, out, null);
    }

    /**
     * Flatten an object's value into an XmlSerializer.  The value can later
     * be read back with readThisValueXml().
     *
     * Currently supported value types are: null, String, Integer, Long,
     * Float, Double Boolean, Map, List.
     *
     * @param v The object to be flattened.
     * @param name Name attribute to include with this value's tag, or null
     *             for none.
     * @param out XmlSerializer to write the object into.
     * @param callback Handler for Object types not recognized.
     *
     * @see #writeMapXml
     * @see #writeListXml
     * @see #readValueXml
     */
    private static final void writeValueXml(Object v, String name, XmlSerializer out,
            WriteMapCallback callback)  throws XmlPullParserException, java.io.IOException {
        String typeStr;
        if (v == null) {
            out.startTag(null, "null");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.endTag(null, "null");
            return;
        } else if (v instanceof String) {
            out.startTag(null, "string");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.text(v.toString());
            out.endTag(null, "string");
            return;
        } else if (v instanceof Integer) {
            typeStr = "int";
        } else if (v instanceof Long) {
            typeStr = "long";
        } else if (v instanceof Float) {
            typeStr = "float";
        } else if (v instanceof Double) {
            typeStr = "double";
        } else if (v instanceof Boolean) {
            typeStr = "boolean";
        } else if (v instanceof byte[]) {
            writeByteArrayXml((byte[])v, name, out);
            return;
        } else if (v instanceof int[]) {
            writeIntArrayXml((int[])v, name, out);
            return;
        } else if (v instanceof long[]) {
            writeLongArrayXml((long[])v, name, out);
            return;
        } else if (v instanceof double[]) {
            writeDoubleArrayXml((double[])v, name, out);
            return;
        } else if (v instanceof String[]) {
            writeStringArrayXml((String[])v, name, out);
            return;
        } else if (v instanceof boolean[]) {
            writeBooleanArrayXml((boolean[])v, name, out);
            return;
        } else if (v instanceof Map) {
            writeMapXml((Map)v, name, out);
            return;
        } else if (v instanceof List) {
            writeListXml((List) v, name, out);
            return;
        } else if (v instanceof Set) {
            writeSetXml((Set) v, name, out);
            return;
        } else if (v instanceof CharSequence) {
            // XXX This is to allow us to at least write something if
            // we encounter styled text...  but it means we will drop all
            // of the styling information. :(
            out.startTag(null, "string");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.text(v.toString());
            out.endTag(null, "string");
            return;
        } else if (callback != null) {
            callback.writeUnknownObject(v, name, out);
            return;
        } else {
            throw new RuntimeException("writeValueXml: unable to write value " + v);
        }

        out.startTag(null, typeStr);
        if (name != null) {
            out.attribute(null, "name", name);
        }
        out.attribute(null, "value", v.toString());
        out.endTag(null, typeStr);
    }

    /**
     * Read a HashMap from an InputStream containing XML.  The stream can
     * previously have been written by writeMapXml().
     *
     * @param in The InputStream from which to read.
     *
     * @return HashMap The resulting map.
     *
     * @see #readListXml
     * @see #readValueXml
     * @see #readThisMapXml
     * #see #writeMapXml
     */
    @SuppressWarnings("unchecked")
    @UnsupportedAppUsage
    public static final HashMap<String, ?> readMapXml(InputStream in)
    throws XmlPullParserException, java.io.IOException
    {
        XmlPullParser   parser = Xml.newPullParser();
        parser.setInput(in, StandardCharsets.UTF_8.name());
        return (HashMap<String, ?>) readValueXml(parser, new String[1]);
    }

    /**
     * Read an ArrayList from an InputStream containing XML.  The stream can
     * previously have been written by writeListXml().
     *
     * @param in The InputStream from which to read.
     *
     * @return ArrayList The resulting list.
     *
     * @see #readMapXml
     * @see #readValueXml
     * @see #readThisListXml
     * @see #writeListXml
     */
    public static final ArrayList readListXml(InputStream in)
    throws XmlPullParserException, java.io.IOException
    {
        XmlPullParser   parser = Xml.newPullParser();
        parser.setInput(in, StandardCharsets.UTF_8.name());
        return (ArrayList)readValueXml(parser, new String[1]);
    }
    
    
    /**
     * Read a HashSet from an InputStream containing XML. The stream can
     * previously have been written by writeSetXml().
     * 
     * @param in The InputStream from which to read.
     * 
     * @return HashSet The resulting set.
     * 
     * @throws XmlPullParserException
     * @throws java.io.IOException
     * 
     * @see #readValueXml
     * @see #readThisSetXml
     * @see #writeSetXml
     */
    public static final HashSet readSetXml(InputStream in)
            throws XmlPullParserException, java.io.IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        return (HashSet) readValueXml(parser, new String[1]);
    }

    /**
     * Read a HashMap object from an XmlPullParser.  The XML data could
     * previously have been generated by writeMapXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the map.
     *
     * @param parser The XmlPullParser from which to read the map data.
     * @param endTag Name of the tag that will end the map, usually "map".
     * @param name An array of one string, used to return the name attribute
     *             of the map's tag.
     *
     * @return HashMap The newly generated map.
     *
     * @see #readMapXml
     */
    public static final HashMap<String, ?> readThisMapXml(XmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {
        return readThisMapXml(parser, endTag, name, null);
    }

    /**
     * Read a HashMap object from an XmlPullParser.  The XML data could
     * previously have been generated by writeMapXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the map.
     *
     * @param parser The XmlPullParser from which to read the map data.
     * @param endTag Name of the tag that will end the map, usually "map".
     * @param name An array of one string, used to return the name attribute
     *             of the map's tag.
     *
     * @return HashMap The newly generated map.
     *
     * @see #readMapXml
     * @hide
     */
    public static final HashMap<String, ?> readThisMapXml(XmlPullParser parser, String endTag,
            String[] name, ReadMapCallback callback)
            throws XmlPullParserException, java.io.IOException
    {
        HashMap<String, Object> map = new HashMap<String, Object>();

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                Object val = readThisValueXml(parser, name, callback, false);
                map.put(name[0], val);
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return map;
                }
                throw new XmlPullParserException(
                    "Expected " + endTag + " end tag at: " + parser.getName());
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
            "Document ended before " + endTag + " end tag");
    }

    /**
     * Like {@link #readThisMapXml}, but returns an ArrayMap instead of HashMap.
     * @hide
     */
    public static final ArrayMap<String, ?> readThisArrayMapXml(XmlPullParser parser, String endTag,
            String[] name, ReadMapCallback callback)
            throws XmlPullParserException, java.io.IOException
    {
        ArrayMap<String, Object> map = new ArrayMap<>();

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                Object val = readThisValueXml(parser, name, callback, true);
                map.put(name[0], val);
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return map;
                }
                throw new XmlPullParserException(
                    "Expected " + endTag + " end tag at: " + parser.getName());
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
            "Document ended before " + endTag + " end tag");
    }

    /**
     * Read an ArrayList object from an XmlPullParser.  The XML data could
     * previously have been generated by writeListXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "list".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return HashMap The newly generated list.
     *
     * @see #readListXml
     */
    public static final ArrayList readThisListXml(XmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {
        return readThisListXml(parser, endTag, name, null, false);
    }

    /**
     * Read an ArrayList object from an XmlPullParser.  The XML data could
     * previously have been generated by writeListXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "list".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return HashMap The newly generated list.
     *
     * @see #readListXml
     */
    private static final ArrayList readThisListXml(XmlPullParser parser, String endTag,
            String[] name, ReadMapCallback callback, boolean arrayMap)
            throws XmlPullParserException, java.io.IOException {
        ArrayList list = new ArrayList();

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                Object val = readThisValueXml(parser, name, callback, arrayMap);
                list.add(val);
                //System.out.println("Adding to list: " + val);
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return list;
                }
                throw new XmlPullParserException(
                    "Expected " + endTag + " end tag at: " + parser.getName());
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
            "Document ended before " + endTag + " end tag");
    }

    /**
     * Read a HashSet object from an XmlPullParser. The XML data could previously
     * have been generated by writeSetXml(). The XmlPullParser must be positioned
     * <em>after</em> the tag that begins the set.
     *
     * @param parser The XmlPullParser from which to read the set data.
     * @param endTag Name of the tag that will end the set, usually "set".
     * @param name An array of one string, used to return the name attribute
     *             of the set's tag.
     *
     * @return HashSet The newly generated set.
     *
     * @throws XmlPullParserException
     * @throws java.io.IOException
     *
     * @see #readSetXml
     */
    public static final HashSet readThisSetXml(XmlPullParser parser, String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {
        return readThisSetXml(parser, endTag, name, null, false);
    }

    /**
     * Read a HashSet object from an XmlPullParser. The XML data could previously
     * have been generated by writeSetXml(). The XmlPullParser must be positioned
     * <em>after</em> the tag that begins the set.
     * 
     * @param parser The XmlPullParser from which to read the set data.
     * @param endTag Name of the tag that will end the set, usually "set".
     * @param name An array of one string, used to return the name attribute
     *             of the set's tag.
     *
     * @return HashSet The newly generated set.
     * 
     * @throws XmlPullParserException
     * @throws java.io.IOException
     * 
     * @see #readSetXml
     * @hide
     */
    private static final HashSet readThisSetXml(XmlPullParser parser, String endTag, String[] name,
            ReadMapCallback callback, boolean arrayMap)
            throws XmlPullParserException, java.io.IOException {
        HashSet set = new HashSet();
        
        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                Object val = readThisValueXml(parser, name, callback, arrayMap);
                set.add(val);
                //System.out.println("Adding to set: " + val);
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return set;
                }
                throw new XmlPullParserException(
                        "Expected " + endTag + " end tag at: " + parser.getName());
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);
        
        throw new XmlPullParserException(
                "Document ended before " + endTag + " end tag");
    }

    /**
     * Read a byte[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeByteArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "list".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated byte[].
     *
     * @see #writeByteArrayXml
     */
    public static final byte[] readThisByteArrayXml(XmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException(
                    "Need num attribute in byte-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException(
                    "Not a number in num attribute in byte-array");
        }

        byte[] array = new byte[num];

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.TEXT) {
                if (num > 0) {
                    String values = parser.getText();
                    if (values == null || values.length() != num * 2) {
                        throw new XmlPullParserException(
                                "Invalid value found in byte-array: " + values);
                    }
                    // This is ugly, but keeping it to mirror the logic in #writeByteArrayXml.
                    for (int i = 0; i < num; i ++) {
                        char nibbleHighChar = values.charAt(2 * i);
                        char nibbleLowChar = values.charAt(2 * i + 1);
                        int nibbleHigh = nibbleHighChar > 'a' ? (nibbleHighChar - 'a' + 10)
                                : (nibbleHighChar - '0');
                        int nibbleLow = nibbleLowChar > 'a' ? (nibbleLowChar - 'a' + 10)
                                : (nibbleLowChar - '0');
                        array[i] = (byte) ((nibbleHigh & 0x0F) << 4 | (nibbleLow & 0x0F));
                    }
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else {
                    throw new XmlPullParserException(
                            "Expected " + endTag + " end tag at: "
                                    + parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
                "Document ended before " + endTag + " end tag");
    }

    /**
     * Read an int[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeIntArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "list".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated int[].
     *
     * @see #readListXml
     */
    public static final int[] readThisIntArrayXml(XmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException(
                    "Need num attribute in int-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException(
                    "Not a number in num attribute in int-array");
        }
        parser.next();

        int[] array = new int[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    try {
                        array[i] = Integer.parseInt(
                                parser.getAttributeValue(null, "value"));
                    } catch (NullPointerException e) {
                        throw new XmlPullParserException(
                                "Need value attribute in item");
                    } catch (NumberFormatException e) {
                        throw new XmlPullParserException(
                                "Not a number in value attribute in item");
                    }
                } else {
                    throw new XmlPullParserException(
                            "Expected item tag at: " + parser.getName());
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else if (parser.getName().equals("item")) {
                    i++;
                } else {
                    throw new XmlPullParserException(
                        "Expected " + endTag + " end tag at: "
                        + parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
            "Document ended before " + endTag + " end tag");
    }

    /**
     * Read a long[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeLongArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "list".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated long[].
     *
     * @see #readListXml
     */
    public static final long[] readThisLongArrayXml(XmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException("Need num attribute in long-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Not a number in num attribute in long-array");
        }
        parser.next();

        long[] array = new long[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    try {
                        array[i] = Long.parseLong(parser.getAttributeValue(null, "value"));
                    } catch (NullPointerException e) {
                        throw new XmlPullParserException("Need value attribute in item");
                    } catch (NumberFormatException e) {
                        throw new XmlPullParserException("Not a number in value attribute in item");
                    }
                } else {
                    throw new XmlPullParserException("Expected item tag at: " + parser.getName());
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else if (parser.getName().equals("item")) {
                    i++;
                } else {
                    throw new XmlPullParserException("Expected " + endTag + " end tag at: " +
                            parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException("Document ended before " + endTag + " end tag");
    }

    /**
     * Read a double[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeDoubleArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "double-array".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated double[].
     *
     * @see #readListXml
     */
    public static final double[] readThisDoubleArrayXml(XmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException("Need num attribute in double-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Not a number in num attribute in double-array");
        }
        parser.next();

        double[] array = new double[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    try {
                        array[i] = Double.parseDouble(parser.getAttributeValue(null, "value"));
                    } catch (NullPointerException e) {
                        throw new XmlPullParserException("Need value attribute in item");
                    } catch (NumberFormatException e) {
                        throw new XmlPullParserException("Not a number in value attribute in item");
                    }
                } else {
                    throw new XmlPullParserException("Expected item tag at: " + parser.getName());
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else if (parser.getName().equals("item")) {
                    i++;
                } else {
                    throw new XmlPullParserException("Expected " + endTag + " end tag at: " +
                            parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException("Document ended before " + endTag + " end tag");
    }

    /**
     * Read a String[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeStringArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "string-array".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated String[].
     *
     * @see #readListXml
     */
    public static final String[] readThisStringArrayXml(XmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException("Need num attribute in string-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Not a number in num attribute in string-array");
        }
        parser.next();

        String[] array = new String[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    try {
                        array[i] = parser.getAttributeValue(null, "value");
                    } catch (NullPointerException e) {
                        throw new XmlPullParserException("Need value attribute in item");
                    } catch (NumberFormatException e) {
                        throw new XmlPullParserException("Not a number in value attribute in item");
                    }
                } else {
                    throw new XmlPullParserException("Expected item tag at: " + parser.getName());
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else if (parser.getName().equals("item")) {
                    i++;
                } else {
                    throw new XmlPullParserException("Expected " + endTag + " end tag at: " +
                            parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException("Document ended before " + endTag + " end tag");
    }

    /**
     * Read a boolean[] object from an XmlPullParser.  The XML data could
     * previously have been generated by writeBooleanArrayXml().  The XmlPullParser
     * must be positioned <em>after</em> the tag that begins the list.
     *
     * @param parser The XmlPullParser from which to read the list data.
     * @param endTag Name of the tag that will end the list, usually "string-array".
     * @param name An array of one string, used to return the name attribute
     *             of the list's tag.
     *
     * @return Returns a newly generated boolean[].
     *
     * @see #readListXml
     */
    public static final boolean[] readThisBooleanArrayXml(XmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num;
        try {
            num = Integer.parseInt(parser.getAttributeValue(null, "num"));
        } catch (NullPointerException e) {
            throw new XmlPullParserException("Need num attribute in string-array");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Not a number in num attribute in string-array");
        }
        parser.next();

        boolean[] array = new boolean[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    try {
                        array[i] = Boolean.parseBoolean(parser.getAttributeValue(null, "value"));
                    } catch (NullPointerException e) {
                        throw new XmlPullParserException("Need value attribute in item");
                    } catch (NumberFormatException e) {
                        throw new XmlPullParserException("Not a number in value attribute in item");
                    }
                } else {
                    throw new XmlPullParserException("Expected item tag at: " + parser.getName());
                }
            } else if (eventType == parser.END_TAG) {
                if (parser.getName().equals(endTag)) {
                    return array;
                } else if (parser.getName().equals("item")) {
                    i++;
                } else {
                    throw new XmlPullParserException("Expected " + endTag + " end tag at: " +
                            parser.getName());
                }
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException("Document ended before " + endTag + " end tag");
    }

    /**
     * Read a flattened object from an XmlPullParser.  The XML data could
     * previously have been written with writeMapXml(), writeListXml(), or
     * writeValueXml().  The XmlPullParser must be positioned <em>at</em> the
     * tag that defines the value.
     *
     * @param parser The XmlPullParser from which to read the object.
     * @param name An array of one string, used to return the name attribute
     *             of the value's tag.
     *
     * @return Object The newly generated value object.
     *
     * @see #readMapXml
     * @see #readListXml
     * @see #writeValueXml
     */
    public static final Object readValueXml(XmlPullParser parser, String[] name)
    throws XmlPullParserException, java.io.IOException
    {
        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                return readThisValueXml(parser, name, null, false);
            } else if (eventType == parser.END_TAG) {
                throw new XmlPullParserException(
                    "Unexpected end tag at: " + parser.getName());
            } else if (eventType == parser.TEXT) {
                throw new XmlPullParserException(
                    "Unexpected text: " + parser.getText());
            }
            eventType = parser.next();
        } while (eventType != parser.END_DOCUMENT);

        throw new XmlPullParserException(
            "Unexpected end of document");
    }

    private static final Object readThisValueXml(XmlPullParser parser, String[] name,
            ReadMapCallback callback, boolean arrayMap)
            throws XmlPullParserException, java.io.IOException {
        final String valueName = parser.getAttributeValue(null, "name");
        final String tagName = parser.getName();

        //System.out.println("Reading this value tag: " + tagName + ", name=" + valueName);

        Object res;

        if (tagName.equals("null")) {
            res = null;
        } else if (tagName.equals("string")) {
            String value = "";
            int eventType;
            while ((eventType = parser.next()) != parser.END_DOCUMENT) {
                if (eventType == parser.END_TAG) {
                    if (parser.getName().equals("string")) {
                        name[0] = valueName;
                        //System.out.println("Returning value for " + valueName + ": " + value);
                        return value;
                    }
                    throw new XmlPullParserException(
                        "Unexpected end tag in <string>: " + parser.getName());
                } else if (eventType == parser.TEXT) {
                    value += parser.getText();
                } else if (eventType == parser.START_TAG) {
                    throw new XmlPullParserException(
                        "Unexpected start tag in <string>: " + parser.getName());
                }
            }
            throw new XmlPullParserException(
                "Unexpected end of document in <string>");
        } else if ((res = readThisPrimitiveValueXml(parser, tagName)) != null) {
            // all work already done by readThisPrimitiveValueXml
        } else if (tagName.equals("byte-array")) {
            res = readThisByteArrayXml(parser, "byte-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("int-array")) {
            res = readThisIntArrayXml(parser, "int-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("long-array")) {
            res = readThisLongArrayXml(parser, "long-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("double-array")) {
            res = readThisDoubleArrayXml(parser, "double-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("string-array")) {
            res = readThisStringArrayXml(parser, "string-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("boolean-array")) {
            res = readThisBooleanArrayXml(parser, "boolean-array", name);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("map")) {
            parser.next();
            res = arrayMap
                    ? readThisArrayMapXml(parser, "map", name, callback)
                    : readThisMapXml(parser, "map", name, callback);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("list")) {
            parser.next();
            res = readThisListXml(parser, "list", name, callback, arrayMap);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (tagName.equals("set")) {
            parser.next();
            res = readThisSetXml(parser, "set", name, callback, arrayMap);
            name[0] = valueName;
            //System.out.println("Returning value for " + valueName + ": " + res);
            return res;
        } else if (callback != null) {
            res = callback.readThisUnknownObjectXml(parser, tagName);
            name[0] = valueName;
            return res;
        } else {
            throw new XmlPullParserException("Unknown tag: " + tagName);
        }

        // Skip through to end tag.
        int eventType;
        while ((eventType = parser.next()) != parser.END_DOCUMENT) {
            if (eventType == parser.END_TAG) {
                if (parser.getName().equals(tagName)) {
                    name[0] = valueName;
                    //System.out.println("Returning value for " + valueName + ": " + res);
                    return res;
                }
                throw new XmlPullParserException(
                    "Unexpected end tag in <" + tagName + ">: " + parser.getName());
            } else if (eventType == parser.TEXT) {
                throw new XmlPullParserException(
                "Unexpected text in <" + tagName + ">: " + parser.getName());
            } else if (eventType == parser.START_TAG) {
                throw new XmlPullParserException(
                    "Unexpected start tag in <" + tagName + ">: " + parser.getName());
            }
        }
        throw new XmlPullParserException(
            "Unexpected end of document in <" + tagName + ">");
    }

    private static final Object readThisPrimitiveValueXml(XmlPullParser parser, String tagName)
    throws XmlPullParserException, java.io.IOException
    {
        try {
            if (tagName.equals("int")) {
                return Integer.parseInt(parser.getAttributeValue(null, "value"));
            } else if (tagName.equals("long")) {
                return Long.valueOf(parser.getAttributeValue(null, "value"));
            } else if (tagName.equals("float")) {
                return new Float(parser.getAttributeValue(null, "value"));
            } else if (tagName.equals("double")) {
                return new Double(parser.getAttributeValue(null, "value"));
            } else if (tagName.equals("boolean")) {
                return Boolean.valueOf(parser.getAttributeValue(null, "value"));
            } else {
                return null;
            }
        } catch (NullPointerException e) {
            throw new XmlPullParserException("Need value attribute in <" + tagName + ">");
        } catch (NumberFormatException e) {
            throw new XmlPullParserException(
                    "Not a number in value attribute in <" + tagName + ">");
        }
    }

    @UnsupportedAppUsage
    public static final void beginDocument(XmlPullParser parser, String firstElementName) throws XmlPullParserException, IOException
    {
        int type;
        while ((type=parser.next()) != parser.START_TAG
                   && type != parser.END_DOCUMENT) {
            ;
        }

        if (type != parser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }

        if (!parser.getName().equals(firstElementName)) {
            throw new XmlPullParserException("Unexpected start tag: found " + parser.getName() +
                    ", expected " + firstElementName);
        }
    }

    @UnsupportedAppUsage
    public static final void nextElement(XmlPullParser parser) throws XmlPullParserException, IOException
    {
        int type;
        while ((type=parser.next()) != parser.START_TAG
                   && type != parser.END_DOCUMENT) {
            ;
        }
    }

    public static boolean nextElementWithin(XmlPullParser parser, int outerDepth)
            throws IOException, XmlPullParserException {
        for (;;) {
            int type = parser.next();
            if (type == XmlPullParser.END_DOCUMENT
                    || (type == XmlPullParser.END_TAG && parser.getDepth() == outerDepth)) {
                return false;
            }
            if (type == XmlPullParser.START_TAG
                    && parser.getDepth() == outerDepth + 1) {
                return true;
            }
        }
    }

    public static int readIntAttribute(XmlPullParser in, String name, int defaultValue) {
        final String value = in.getAttributeValue(null, name);
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int readIntAttribute(XmlPullParser in, String name) throws IOException {
        final String value = in.getAttributeValue(null, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as int");
        }
    }

    public static void writeIntAttribute(XmlSerializer out, String name, int value)
            throws IOException {
        out.attribute(null, name, Integer.toString(value));
    }

    public static long readLongAttribute(XmlPullParser in, String name, long defaultValue) {
        final String value = in.getAttributeValue(null, name);
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long readLongAttribute(XmlPullParser in, String name) throws IOException {
        final String value = in.getAttributeValue(null, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as long");
        }
    }

    public static void writeLongAttribute(XmlSerializer out, String name, long value)
            throws IOException {
        out.attribute(null, name, Long.toString(value));
    }

    public static float readFloatAttribute(XmlPullParser in, String name) throws IOException {
        final String value = in.getAttributeValue(null, name);
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as long");
        }
    }

    public static void writeFloatAttribute(XmlSerializer out, String name, float value)
            throws IOException {
        out.attribute(null, name, Float.toString(value));
    }

    public static boolean readBooleanAttribute(XmlPullParser in, String name) {
        final String value = in.getAttributeValue(null, name);
        return Boolean.parseBoolean(value);
    }

    public static boolean readBooleanAttribute(XmlPullParser in, String name,
            boolean defaultValue) {
        final String value = in.getAttributeValue(null, name);
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    public static void writeBooleanAttribute(XmlSerializer out, String name, boolean value)
            throws IOException {
        out.attribute(null, name, Boolean.toString(value));
    }

    public static Uri readUriAttribute(XmlPullParser in, String name) {
        final String value = in.getAttributeValue(null, name);
        return (value != null) ? Uri.parse(value) : null;
    }

    public static void writeUriAttribute(XmlSerializer out, String name, Uri value)
            throws IOException {
        if (value != null) {
            out.attribute(null, name, value.toString());
        }
    }

    public static String readStringAttribute(XmlPullParser in, String name) {
        return in.getAttributeValue(null, name);
    }

    public static void writeStringAttribute(XmlSerializer out, String name, CharSequence value)
            throws IOException {
        if (value != null) {
            out.attribute(null, name, value.toString());
        }
    }

    public static byte[] readByteArrayAttribute(XmlPullParser in, String name) {
        final String value = in.getAttributeValue(null, name);
        if (!TextUtils.isEmpty(value)) {
            return Base64.decode(value, Base64.DEFAULT);
        } else {
            return null;
        }
    }

    public static void writeByteArrayAttribute(XmlSerializer out, String name, byte[] value)
            throws IOException {
        if (value != null) {
            out.attribute(null, name, Base64.encodeToString(value, Base64.DEFAULT));
        }
    }

    public static Bitmap readBitmapAttribute(XmlPullParser in, String name) {
        final byte[] value = readByteArrayAttribute(in, name);
        if (value != null) {
            return BitmapFactory.decodeByteArray(value, 0, value.length);
        } else {
            return null;
        }
    }

    @Deprecated
    public static void writeBitmapAttribute(XmlSerializer out, String name, Bitmap value)
            throws IOException {
        if (value != null) {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            value.compress(CompressFormat.PNG, 90, os);
            writeByteArrayAttribute(out, name, os.toByteArray());
        }
    }

    /** @hide */
    public interface WriteMapCallback {
        /**
         * Called from writeMapXml when an Object type is not recognized. The implementer
         * must write out the entire element including start and end tags.
         *
         * @param v The object to be written out
         * @param name The mapping key for v. Must be written into the "name" attribute of the
         *             start tag.
         * @param out The XML output stream.
         * @throws XmlPullParserException on unrecognized Object type.
         * @throws IOException on XmlSerializer serialization errors.
         * @hide
         */
         public void writeUnknownObject(Object v, String name, XmlSerializer out)
                 throws XmlPullParserException, IOException;
    }

    /** @hide */
    public interface ReadMapCallback {
        /**
         * Called from readThisMapXml when a START_TAG is not recognized. The input stream
         * is positioned within the start tag so that attributes can be read using in.getAttribute.
         *
         * @param in the XML input stream
         * @param tag the START_TAG that was not recognized.
         * @return the Object parsed from the stream which will be put into the map.
         * @throws XmlPullParserException if the START_TAG is not recognized.
         * @throws IOException on XmlPullParser serialization errors.
         * @hide
         */
        public Object readThisUnknownObjectXml(XmlPullParser in, String tag)
                throws XmlPullParserException, IOException;
    }
}
