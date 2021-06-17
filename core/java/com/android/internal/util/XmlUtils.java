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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import libcore.util.HexEncoding;

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

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    private static class ForcedTypedXmlSerializer extends XmlSerializerWrapper
            implements TypedXmlSerializer {
        public ForcedTypedXmlSerializer(XmlSerializer wrapped) {
            super(wrapped);
        }

        @Override
        public XmlSerializer attributeInterned(String namespace, String name, String value)
                throws IOException {
            return attribute(namespace, name, value);
        }

        @Override
        public XmlSerializer attributeBytesHex(String namespace, String name, byte[] value)
                throws IOException {
            return attribute(namespace, name, HexDump.toHexString(value));
        }

        @Override
        public XmlSerializer attributeBytesBase64(String namespace, String name, byte[] value)
                throws IOException {
            return attribute(namespace, name, Base64.encodeToString(value, Base64.NO_WRAP));
        }

        @Override
        public XmlSerializer attributeInt(String namespace, String name, int value)
                throws IOException {
            return attribute(namespace, name, Integer.toString(value));
        }

        @Override
        public XmlSerializer attributeIntHex(String namespace, String name, int value)
                throws IOException {
            return attribute(namespace, name, Integer.toString(value, 16));
        }

        @Override
        public XmlSerializer attributeLong(String namespace, String name, long value)
                throws IOException {
            return attribute(namespace, name, Long.toString(value));
        }

        @Override
        public XmlSerializer attributeLongHex(String namespace, String name, long value)
                throws IOException {
            return attribute(namespace, name, Long.toString(value, 16));
        }

        @Override
        public XmlSerializer attributeFloat(String namespace, String name, float value)
                throws IOException {
            return attribute(namespace, name, Float.toString(value));
        }

        @Override
        public XmlSerializer attributeDouble(String namespace, String name, double value)
                throws IOException {
            return attribute(namespace, name, Double.toString(value));
        }

        @Override
        public XmlSerializer attributeBoolean(String namespace, String name, boolean value)
                throws IOException {
            return attribute(namespace, name, Boolean.toString(value));
        }
    }

    /**
     * Return a specialization of the given {@link XmlSerializer} which has
     * explicit methods to support consistent and efficient conversion of
     * primitive data types.
     */
    public static @NonNull TypedXmlSerializer makeTyped(@NonNull XmlSerializer xml) {
        if (xml instanceof TypedXmlSerializer) {
            return (TypedXmlSerializer) xml;
        } else {
            return new ForcedTypedXmlSerializer(xml);
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    private static class ForcedTypedXmlPullParser extends XmlPullParserWrapper
            implements TypedXmlPullParser {
        public ForcedTypedXmlPullParser(XmlPullParser wrapped) {
            super(wrapped);
        }

        @Override
        public byte[] getAttributeBytesHex(int index)
                throws XmlPullParserException {
            try {
                return HexDump.hexStringToByteArray(getAttributeValue(index));
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public byte[] getAttributeBytesBase64(int index)
                throws XmlPullParserException {
            try {
                return Base64.decode(getAttributeValue(index), Base64.NO_WRAP);
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public int getAttributeInt(int index)
                throws XmlPullParserException {
            try {
                return Integer.parseInt(getAttributeValue(index));
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public int getAttributeIntHex(int index)
                throws XmlPullParserException {
            try {
                return Integer.parseInt(getAttributeValue(index), 16);
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public long getAttributeLong(int index)
                throws XmlPullParserException {
            try {
                return Long.parseLong(getAttributeValue(index));
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public long getAttributeLongHex(int index)
                throws XmlPullParserException {
            try {
                return Long.parseLong(getAttributeValue(index), 16);
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public float getAttributeFloat(int index)
                throws XmlPullParserException {
            try {
                return Float.parseFloat(getAttributeValue(index));
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public double getAttributeDouble(int index)
                throws XmlPullParserException {
            try {
                return Double.parseDouble(getAttributeValue(index));
            } catch (Exception e) {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + e);
            }
        }

        @Override
        public boolean getAttributeBoolean(int index)
                throws XmlPullParserException {
            final String value = getAttributeValue(index);
            if ("true".equalsIgnoreCase(value)) {
                return true;
            } else if ("false".equalsIgnoreCase(value)) {
                return false;
            } else {
                throw new XmlPullParserException(
                        "Invalid attribute " + getAttributeName(index) + ": " + value);
            }
        }
    }

    /**
     * Return a specialization of the given {@link XmlPullParser} which has
     * explicit methods to support consistent and efficient conversion of
     * primitive data types.
     */
    public static @NonNull TypedXmlPullParser makeTyped(@NonNull XmlPullParser xml) {
        if (xml instanceof TypedXmlPullParser) {
            return (TypedXmlPullParser) xml;
        } else {
            return new ForcedTypedXmlPullParser(xml);
        }
    }

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
        TypedXmlSerializer serializer = Xml.newFastSerializer();
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
        TypedXmlSerializer serializer = Xml.newFastSerializer();
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
    public static final void writeMapXml(Map val, String name, TypedXmlSerializer out)
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
    public static final void writeMapXml(Map val, String name, TypedXmlSerializer out,
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
    public static final void writeMapXml(Map val, TypedXmlSerializer out,
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
    public static final void writeListXml(List val, String name, TypedXmlSerializer out)
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
    
    public static final void writeSetXml(Set val, String name, TypedXmlSerializer out)
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
            TypedXmlSerializer out)
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
        out.attributeInt(null, "num", N);

        out.text(HexEncoding.encodeToString(val).toLowerCase());

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
            TypedXmlSerializer out)
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
        out.attributeInt(null, "num", N);

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attributeInt(null, "value", val[i]);
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
    public static final void writeLongArrayXml(long[] val, String name, TypedXmlSerializer out)
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
        out.attributeInt(null, "num", N);

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attributeLong(null, "value", val[i]);
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
    public static final void writeDoubleArrayXml(double[] val, String name, TypedXmlSerializer out)
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
        out.attributeInt(null, "num", N);

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attributeDouble(null, "value", val[i]);
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
    public static final void writeStringArrayXml(String[] val, String name, TypedXmlSerializer out)
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
        out.attributeInt(null, "num", N);

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
    public static final void writeBooleanArrayXml(boolean[] val, String name,
            TypedXmlSerializer out) throws XmlPullParserException, java.io.IOException {

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
        out.attributeInt(null, "num", N);

        for (int i=0; i<N; i++) {
            out.startTag(null, "item");
            out.attributeBoolean(null, "value", val[i]);
            out.endTag(null, "item");
        }

        out.endTag(null, "boolean-array");
    }

    @Deprecated
    public static final void writeValueXml(Object v, String name, XmlSerializer out)
            throws XmlPullParserException, java.io.IOException {
        writeValueXml(v, name, XmlUtils.makeTyped(out));
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
    public static final void writeValueXml(Object v, String name, TypedXmlSerializer out)
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
    private static final void writeValueXml(Object v, String name, TypedXmlSerializer out,
            WriteMapCallback callback)  throws XmlPullParserException, java.io.IOException {
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
            out.startTag(null, "int");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.attributeInt(null, "value", (int) v);
            out.endTag(null, "int");
        } else if (v instanceof Long) {
            out.startTag(null, "long");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.attributeLong(null, "value", (long) v);
            out.endTag(null, "long");
        } else if (v instanceof Float) {
            out.startTag(null, "float");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.attributeFloat(null, "value", (float) v);
            out.endTag(null, "float");
        } else if (v instanceof Double) {
            out.startTag(null, "double");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.attributeDouble(null, "value", (double) v);
            out.endTag(null, "double");
        } else if (v instanceof Boolean) {
            out.startTag(null, "boolean");
            if (name != null) {
                out.attribute(null, "name", name);
            }
            out.attributeBoolean(null, "value", (boolean) v);
            out.endTag(null, "boolean");
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
            throws XmlPullParserException, java.io.IOException {
        TypedXmlPullParser parser = Xml.newFastPullParser();
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
            throws XmlPullParserException, java.io.IOException {
        TypedXmlPullParser parser = Xml.newFastPullParser();
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
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(in, StandardCharsets.UTF_8.name());
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
    public static final HashMap<String, ?> readThisMapXml(TypedXmlPullParser parser, String endTag,
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
    public static final HashMap<String, ?> readThisMapXml(TypedXmlPullParser parser, String endTag,
            String[] name, ReadMapCallback callback)
            throws XmlPullParserException, java.io.IOException {
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
    public static final ArrayMap<String, ?> readThisArrayMapXml(TypedXmlPullParser parser,
            String endTag, String[] name, ReadMapCallback callback)
            throws XmlPullParserException, java.io.IOException {
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
    public static final ArrayList readThisListXml(TypedXmlPullParser parser, String endTag,
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
    private static final ArrayList readThisListXml(TypedXmlPullParser parser, String endTag,
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
    public static final HashSet readThisSetXml(TypedXmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {
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
    private static final HashSet readThisSetXml(TypedXmlPullParser parser, String endTag,
            String[] name, ReadMapCallback callback, boolean arrayMap)
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
    public static final byte[] readThisByteArrayXml(TypedXmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");

        // 0 len byte array does not have a text in the XML tag. So, initialize to 0 len array.
        // For all other array lens, HexEncoding.decode() below overrides the array.
        byte[] array = new byte[0];

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.TEXT) {
                if (num > 0) {
                    String values = parser.getText();
                    if (values == null || values.length() != num * 2) {
                        throw new XmlPullParserException(
                                "Invalid value found in byte-array: " + values);
                    }
                    array = HexEncoding.decode(values);
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
    public static final int[] readThisIntArrayXml(TypedXmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");
        parser.next();

        int[] array = new int[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    array[i] = parser.getAttributeInt(null, "value");
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
    public static final long[] readThisLongArrayXml(TypedXmlPullParser parser,
            String endTag, String[] name)
            throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");
        parser.next();

        long[] array = new long[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    array[i] = parser.getAttributeLong(null, "value");
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
    public static final double[] readThisDoubleArrayXml(TypedXmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");
        parser.next();

        double[] array = new double[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    array[i] = parser.getAttributeDouble(null, "value");
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
    public static final String[] readThisStringArrayXml(TypedXmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");
        parser.next();

        String[] array = new String[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    array[i] = parser.getAttributeValue(null, "value");
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
    public static final boolean[] readThisBooleanArrayXml(TypedXmlPullParser parser, String endTag,
            String[] name) throws XmlPullParserException, java.io.IOException {

        int num = parser.getAttributeInt(null, "num");
        parser.next();

        boolean[] array = new boolean[num];
        int i = 0;

        int eventType = parser.getEventType();
        do {
            if (eventType == parser.START_TAG) {
                if (parser.getName().equals("item")) {
                    array[i] = parser.getAttributeBoolean(null, "value");
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
    public static final Object readValueXml(TypedXmlPullParser parser, String[] name)
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

    private static final Object readThisValueXml(TypedXmlPullParser parser, String[] name,
            ReadMapCallback callback, boolean arrayMap)
            throws XmlPullParserException, java.io.IOException {
        final String valueName = parser.getAttributeValue(null, "name");
        final String tagName = parser.getName();

        //System.out.println("Reading this value tag: " + tagName + ", name=" + valueName);

        Object res;

        if (tagName.equals("null")) {
            res = null;
        } else if (tagName.equals("string")) {
            final StringBuilder value = new StringBuilder();
            int eventType;
            while ((eventType = parser.next()) != parser.END_DOCUMENT) {
                if (eventType == parser.END_TAG) {
                    if (parser.getName().equals("string")) {
                        name[0] = valueName;
                        //System.out.println("Returning value for " + valueName + ": " + value);
                        return value.toString();
                    }
                    throw new XmlPullParserException(
                        "Unexpected end tag in <string>: " + parser.getName());
                } else if (eventType == parser.TEXT) {
                    value.append(parser.getText());
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

    private static final Object readThisPrimitiveValueXml(TypedXmlPullParser parser, String tagName)
            throws XmlPullParserException, java.io.IOException {
        if (tagName.equals("int")) {
            return parser.getAttributeInt(null, "value");
        } else if (tagName.equals("long")) {
            return parser.getAttributeLong(null, "value");
        } else if (tagName.equals("float")) {
            return parser.getAttributeFloat(null, "value");
        } else if (tagName.equals("double")) {
            return parser.getAttributeDouble(null, "value");
        } else if (tagName.equals("boolean")) {
            return parser.getAttributeBoolean(null, "value");
        } else {
            return null;
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

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static int readIntAttribute(XmlPullParser in, String name, int defaultValue) {
        if (in instanceof TypedXmlPullParser) {
            return ((TypedXmlPullParser) in).getAttributeInt(null, name, defaultValue);
        }
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

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static int readIntAttribute(XmlPullParser in, String name) throws IOException {
        if (in instanceof TypedXmlPullParser) {
            try {
                return ((TypedXmlPullParser) in).getAttributeInt(null, name);
            } catch (XmlPullParserException e) {
                throw new ProtocolException(e.getMessage());
            }
        }
        final String value = in.getAttributeValue(null, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as int");
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static void writeIntAttribute(XmlSerializer out, String name, int value)
            throws IOException {
        if (out instanceof TypedXmlSerializer) {
            ((TypedXmlSerializer) out).attributeInt(null, name, value);
            return;
        }
        out.attribute(null, name, Integer.toString(value));
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static long readLongAttribute(XmlPullParser in, String name, long defaultValue) {
        if (in instanceof TypedXmlPullParser) {
            return ((TypedXmlPullParser) in).getAttributeLong(null, name, defaultValue);
        }
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

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static long readLongAttribute(XmlPullParser in, String name) throws IOException {
        if (in instanceof TypedXmlPullParser) {
            try {
                return ((TypedXmlPullParser) in).getAttributeLong(null, name);
            } catch (XmlPullParserException e) {
                throw new ProtocolException(e.getMessage());
            }
        }
        final String value = in.getAttributeValue(null, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as long");
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static void writeLongAttribute(XmlSerializer out, String name, long value)
            throws IOException {
        if (out instanceof TypedXmlSerializer) {
            ((TypedXmlSerializer) out).attributeLong(null, name, value);
            return;
        }
        out.attribute(null, name, Long.toString(value));
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static float readFloatAttribute(XmlPullParser in, String name) throws IOException {
        if (in instanceof TypedXmlPullParser) {
            try {
                return ((TypedXmlPullParser) in).getAttributeFloat(null, name);
            } catch (XmlPullParserException e) {
                throw new ProtocolException(e.getMessage());
            }
        }
        final String value = in.getAttributeValue(null, name);
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as long");
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static void writeFloatAttribute(XmlSerializer out, String name, float value)
            throws IOException {
        if (out instanceof TypedXmlSerializer) {
            ((TypedXmlSerializer) out).attributeFloat(null, name, value);
            return;
        }
        out.attribute(null, name, Float.toString(value));
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static boolean readBooleanAttribute(XmlPullParser in, String name) {
        return readBooleanAttribute(in, name, false);
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static boolean readBooleanAttribute(XmlPullParser in, String name,
            boolean defaultValue) {
        if (in instanceof TypedXmlPullParser) {
            return ((TypedXmlPullParser) in).getAttributeBoolean(null, name, defaultValue);
        }
        final String value = in.getAttributeValue(null, name);
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static void writeBooleanAttribute(XmlSerializer out, String name, boolean value)
            throws IOException {
        if (out instanceof TypedXmlSerializer) {
            ((TypedXmlSerializer) out).attributeBoolean(null, name, value);
            return;
        }
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

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static byte[] readByteArrayAttribute(XmlPullParser in, String name) {
        if (in instanceof TypedXmlPullParser) {
            try {
                return ((TypedXmlPullParser) in).getAttributeBytesBase64(null, name);
            } catch (XmlPullParserException e) {
                return null;
            }
        }
        final String value = in.getAttributeValue(null, name);
        if (!TextUtils.isEmpty(value)) {
            return Base64.decode(value, Base64.DEFAULT);
        } else {
            return null;
        }
    }

    @SuppressWarnings("AndroidFrameworkEfficientXml")
    public static void writeByteArrayAttribute(XmlSerializer out, String name, byte[] value)
            throws IOException {
        if (value != null) {
            if (out instanceof TypedXmlSerializer) {
                ((TypedXmlSerializer) out).attributeBytesBase64(null, name, value);
                return;
            }
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
         public void writeUnknownObject(Object v, String name, TypedXmlSerializer out)
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
        public Object readThisUnknownObjectXml(TypedXmlPullParser in, String tag)
                throws XmlPullParserException, IOException;
    }
}
