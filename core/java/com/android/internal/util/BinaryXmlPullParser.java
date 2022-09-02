/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.internal.util.BinaryXmlSerializer.ATTRIBUTE;
import static com.android.internal.util.BinaryXmlSerializer.PROTOCOL_MAGIC_VERSION_0;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_BOOLEAN_FALSE;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_BOOLEAN_TRUE;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_BYTES_BASE64;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_BYTES_HEX;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_DOUBLE;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_FLOAT;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_INT;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_INT_HEX;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_LONG;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_LONG_HEX;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_NULL;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_STRING;
import static com.android.internal.util.BinaryXmlSerializer.TYPE_STRING_INTERNED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Parser that reads XML documents using a custom binary wire protocol which
 * benchmarking has shown to be 8.5x faster than {@link Xml.newFastPullParser()}
 * for a typical {@code packages.xml}.
 * <p>
 * The high-level design of the wire protocol is to directly serialize the event
 * stream, while efficiently and compactly writing strongly-typed primitives
 * delivered through the {@link TypedXmlSerializer} interface.
 * <p>
 * Each serialized event is a single byte where the lower half is a normal
 * {@link XmlPullParser} token and the upper half is an optional data type
 * signal, such as {@link #TYPE_INT}.
 * <p>
 * This parser has some specific limitations:
 * <ul>
 * <li>Only the UTF-8 encoding is supported.
 * <li>Variable length values, such as {@code byte[]} or {@link String}, are
 * limited to 65,535 bytes in length. Note that {@link String} values are stored
 * as UTF-8 on the wire.
 * <li>Namespaces, prefixes, properties, and options are unsupported.
 * </ul>
 */
public final class BinaryXmlPullParser implements TypedXmlPullParser {
    private FastDataInput mIn;

    private int mCurrentToken = START_DOCUMENT;
    private int mCurrentDepth = 0;
    private String mCurrentName;
    private String mCurrentText;

    /**
     * Pool of attributes parsed for the currently tag. All interactions should
     * be done via {@link #obtainAttribute()}, {@link #findAttribute(String)},
     * and {@link #resetAttributes()}.
     */
    private int mAttributeCount = 0;
    private Attribute[] mAttributes;

    @Override
    public void setInput(InputStream is, String encoding) throws XmlPullParserException {
        if (encoding != null && !StandardCharsets.UTF_8.name().equalsIgnoreCase(encoding)) {
            throw new UnsupportedOperationException();
        }

        if (mIn != null) {
            mIn.release();
            mIn = null;
        }

        mIn = FastDataInput.obtainUsing4ByteSequences(is);

        mCurrentToken = START_DOCUMENT;
        mCurrentDepth = 0;
        mCurrentName = null;
        mCurrentText = null;

        mAttributeCount = 0;
        mAttributes = new Attribute[8];
        for (int i = 0; i < mAttributes.length; i++) {
            mAttributes[i] = new Attribute();
        }

        try {
            final byte[] magic = new byte[4];
            mIn.readFully(magic);
            if (!Arrays.equals(magic, PROTOCOL_MAGIC_VERSION_0)) {
                throw new IOException("Unexpected magic " + bytesToHexString(magic));
            }

            // We're willing to immediately consume a START_DOCUMENT if present,
            // but we're okay if it's missing
            if (peekNextExternalToken() == START_DOCUMENT) {
                consumeToken();
            }
        } catch (IOException e) {
            throw new XmlPullParserException(e.toString());
        }
    }

    @Override
    public void setInput(Reader in) throws XmlPullParserException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int next() throws XmlPullParserException, IOException {
        while (true) {
            final int token = nextToken();
            switch (token) {
                case START_TAG:
                case END_TAG:
                case END_DOCUMENT:
                    return token;
                case TEXT:
                    consumeAdditionalText();
                    // Per interface docs, empty text regions are skipped
                    if (mCurrentText == null || mCurrentText.length() == 0) {
                        continue;
                    } else {
                        return TEXT;
                    }
            }
        }
    }

    @Override
    public int nextToken() throws XmlPullParserException, IOException {
        if (mCurrentToken == XmlPullParser.END_TAG) {
            mCurrentDepth--;
        }

        int token;
        try {
            token = peekNextExternalToken();
            consumeToken();
        } catch (EOFException e) {
            token = END_DOCUMENT;
        }
        switch (token) {
            case XmlPullParser.START_TAG:
                // We need to peek forward to find the next external token so
                // that we parse all pending INTERNAL_ATTRIBUTE tokens
                peekNextExternalToken();
                mCurrentDepth++;
                break;
        }
        mCurrentToken = token;
        return token;
    }

    /**
     * Peek at the next "external" token without consuming it.
     * <p>
     * External tokens, such as {@link #START_TAG}, are expected by typical
     * {@link XmlPullParser} clients. In contrast, internal tokens, such as
     * {@link #ATTRIBUTE}, are not expected by typical clients.
     * <p>
     * This method consumes any internal events until it reaches the next
     * external event.
     */
    private int peekNextExternalToken() throws IOException, XmlPullParserException {
        while (true) {
            final int token = peekNextToken();
            switch (token) {
                case ATTRIBUTE:
                    consumeToken();
                    continue;
                default:
                    return token;
            }
        }
    }

    /**
     * Peek at the next token in the underlying stream without consuming it.
     */
    private int peekNextToken() throws IOException {
        return mIn.peekByte() & 0x0f;
    }

    /**
     * Parse and consume the next token in the underlying stream.
     */
    private void consumeToken() throws IOException, XmlPullParserException {
        final int event = mIn.readByte();
        final int token = event & 0x0f;
        final int type = event & 0xf0;
        switch (token) {
            case ATTRIBUTE: {
                final Attribute attr = obtainAttribute();
                attr.name = mIn.readInternedUTF();
                attr.type = type;
                switch (type) {
                    case TYPE_NULL:
                    case TYPE_BOOLEAN_TRUE:
                    case TYPE_BOOLEAN_FALSE:
                        // Nothing extra to fill in
                        break;
                    case TYPE_STRING:
                        attr.valueString = mIn.readUTF();
                        break;
                    case TYPE_STRING_INTERNED:
                        attr.valueString = mIn.readInternedUTF();
                        break;
                    case TYPE_BYTES_HEX:
                    case TYPE_BYTES_BASE64:
                        final int len = mIn.readUnsignedShort();
                        final byte[] res = new byte[len];
                        mIn.readFully(res);
                        attr.valueBytes = res;
                        break;
                    case TYPE_INT:
                    case TYPE_INT_HEX:
                        attr.valueInt = mIn.readInt();
                        break;
                    case TYPE_LONG:
                    case TYPE_LONG_HEX:
                        attr.valueLong = mIn.readLong();
                        break;
                    case TYPE_FLOAT:
                        attr.valueFloat = mIn.readFloat();
                        break;
                    case TYPE_DOUBLE:
                        attr.valueDouble = mIn.readDouble();
                        break;
                    default:
                        throw new IOException("Unexpected data type " + type);
                }
                break;
            }
            case XmlPullParser.START_DOCUMENT: {
                mCurrentName = null;
                mCurrentText = null;
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            case XmlPullParser.END_DOCUMENT: {
                mCurrentName = null;
                mCurrentText = null;
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            case XmlPullParser.START_TAG: {
                mCurrentName = mIn.readInternedUTF();
                mCurrentText = null;
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            case XmlPullParser.END_TAG: {
                mCurrentName = mIn.readInternedUTF();
                mCurrentText = null;
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            case XmlPullParser.TEXT:
            case XmlPullParser.CDSECT:
            case XmlPullParser.PROCESSING_INSTRUCTION:
            case XmlPullParser.COMMENT:
            case XmlPullParser.DOCDECL:
            case XmlPullParser.IGNORABLE_WHITESPACE: {
                mCurrentName = null;
                mCurrentText = mIn.readUTF();
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            case XmlPullParser.ENTITY_REF: {
                mCurrentName = mIn.readUTF();
                mCurrentText = resolveEntity(mCurrentName);
                if (mAttributeCount > 0) resetAttributes();
                break;
            }
            default: {
                throw new IOException("Unknown token " + token + " with type " + type);
            }
        }
    }

    /**
     * When the current tag is {@link #TEXT}, consume all subsequent "text"
     * events, as described by {@link #next}. When finished, the current event
     * will still be {@link #TEXT}.
     */
    private void consumeAdditionalText() throws IOException, XmlPullParserException {
        String combinedText = mCurrentText;
        while (true) {
            final int token = peekNextExternalToken();
            switch (token) {
                case COMMENT:
                case PROCESSING_INSTRUCTION:
                    // Quietly consumed
                    consumeToken();
                    break;
                case TEXT:
                case CDSECT:
                case ENTITY_REF:
                    // Additional text regions collected
                    consumeToken();
                    combinedText += mCurrentText;
                    break;
                default:
                    // Next token is something non-text, so wrap things up
                    mCurrentToken = TEXT;
                    mCurrentName = null;
                    mCurrentText = combinedText;
                    return;
            }
        }
    }

    static @NonNull String resolveEntity(@NonNull String entity)
            throws XmlPullParserException {
        switch (entity) {
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "apos": return "'";
            case "quot": return "\"";
        }
        if (entity.length() > 1 && entity.charAt(0) == '#') {
            final char c = (char) Integer.parseInt(entity.substring(1));
            return new String(new char[] { c });
        }
        throw new XmlPullParserException("Unknown entity " + entity);
    }

    @Override
    public void require(int type, String namespace, String name)
            throws XmlPullParserException, IOException {
        if (namespace != null && !namespace.isEmpty()) throw illegalNamespace();
        if (mCurrentToken != type || !Objects.equals(mCurrentName, name)) {
            throw new XmlPullParserException(getPositionDescription());
        }
    }

    @Override
    public String nextText() throws XmlPullParserException, IOException {
        if (getEventType() != START_TAG) {
            throw new XmlPullParserException(getPositionDescription());
        }
        int eventType = next();
        if (eventType == TEXT) {
            String result = getText();
            eventType = next();
            if (eventType != END_TAG) {
                throw new XmlPullParserException(getPositionDescription());
            }
            return result;
        } else if (eventType == END_TAG) {
            return "";
        } else {
            throw new XmlPullParserException(getPositionDescription());
        }
    }

    @Override
    public int nextTag() throws XmlPullParserException, IOException {
        int eventType = next();
        if (eventType == TEXT && isWhitespace()) {
            eventType = next();
        }
        if (eventType != START_TAG && eventType != END_TAG) {
            throw new XmlPullParserException(getPositionDescription());
        }
        return eventType;
    }

    /**
     * Allocate and return a new {@link Attribute} associated with the tag being
     * currently processed. This will automatically grow the internal pool as
     * needed.
     */
    private @NonNull Attribute obtainAttribute() {
        if (mAttributeCount == mAttributes.length) {
            final int before = mAttributes.length;
            final int after = before + (before >> 1);
            mAttributes = Arrays.copyOf(mAttributes, after);
            for (int i = before; i < after; i++) {
                mAttributes[i] = new Attribute();
            }
        }
        return mAttributes[mAttributeCount++];
    }

    /**
     * Clear any {@link Attribute} instances that have been allocated by
     * {@link #obtainAttribute()}, returning them into the pool for recycling.
     */
    private void resetAttributes() {
        for (int i = 0; i < mAttributeCount; i++) {
            mAttributes[i].reset();
        }
        mAttributeCount = 0;
    }

    @Override
    public int getAttributeIndex(String namespace, String name) {
        if (namespace != null && !namespace.isEmpty()) throw illegalNamespace();
        for (int i = 0; i < mAttributeCount; i++) {
            if (Objects.equals(mAttributes[i].name, name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String getAttributeValue(String namespace, String name) {
        final int index = getAttributeIndex(namespace, name);
        if (index != -1) {
            return mAttributes[index].getValueString();
        } else {
            return null;
        }
    }

    @Override
    public String getAttributeValue(int index) {
        return mAttributes[index].getValueString();
    }

    @Override
    public byte[] getAttributeBytesHex(int index) throws XmlPullParserException {
        return mAttributes[index].getValueBytesHex();
    }

    @Override
    public byte[] getAttributeBytesBase64(int index) throws XmlPullParserException {
        return mAttributes[index].getValueBytesBase64();
    }

    @Override
    public int getAttributeInt(int index) throws XmlPullParserException {
        return mAttributes[index].getValueInt();
    }

    @Override
    public int getAttributeIntHex(int index) throws XmlPullParserException {
        return mAttributes[index].getValueIntHex();
    }

    @Override
    public long getAttributeLong(int index) throws XmlPullParserException {
        return mAttributes[index].getValueLong();
    }

    @Override
    public long getAttributeLongHex(int index) throws XmlPullParserException {
        return mAttributes[index].getValueLongHex();
    }

    @Override
    public float getAttributeFloat(int index) throws XmlPullParserException {
        return mAttributes[index].getValueFloat();
    }

    @Override
    public double getAttributeDouble(int index) throws XmlPullParserException {
        return mAttributes[index].getValueDouble();
    }

    @Override
    public boolean getAttributeBoolean(int index) throws XmlPullParserException {
        return mAttributes[index].getValueBoolean();
    }

    @Override
    public String getText() {
        return mCurrentText;
    }

    @Override
    public char[] getTextCharacters(int[] holderForStartAndLength) {
        final char[] chars = mCurrentText.toCharArray();
        holderForStartAndLength[0] = 0;
        holderForStartAndLength[1] = chars.length;
        return chars;
    }

    @Override
    public String getInputEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public int getDepth() {
        return mCurrentDepth;
    }

    @Override
    public String getPositionDescription() {
        // Not very helpful, but it's the best information we have
        return "Token " + mCurrentToken + " at depth " + mCurrentDepth;
    }

    @Override
    public int getLineNumber() {
        return -1;
    }

    @Override
    public int getColumnNumber() {
        return -1;
    }

    @Override
    public boolean isWhitespace() throws XmlPullParserException {
        switch (mCurrentToken) {
            case IGNORABLE_WHITESPACE:
                return true;
            case TEXT:
            case CDSECT:
                return !TextUtils.isGraphic(mCurrentText);
            default:
                throw new XmlPullParserException("Not applicable for token " + mCurrentToken);
        }
    }

    @Override
    public String getNamespace() {
        switch (mCurrentToken) {
            case START_TAG:
            case END_TAG:
                // Namespaces are unsupported
                return NO_NAMESPACE;
            default:
                return null;
        }
    }

    @Override
    public String getName() {
        return mCurrentName;
    }

    @Override
    public String getPrefix() {
        // Prefixes are not supported
        return null;
    }

    @Override
    public boolean isEmptyElementTag() throws XmlPullParserException {
        switch (mCurrentToken) {
            case START_TAG:
                try {
                    return (peekNextExternalToken() == END_TAG);
                } catch (IOException e) {
                    throw new XmlPullParserException(e.toString());
                }
            default:
                throw new XmlPullParserException("Not at START_TAG");
        }
    }

    @Override
    public int getAttributeCount() {
        return mAttributeCount;
    }

    @Override
    public String getAttributeNamespace(int index) {
        // Namespaces are unsupported
        return NO_NAMESPACE;
    }

    @Override
    public String getAttributeName(int index) {
        return mAttributes[index].name;
    }

    @Override
    public String getAttributePrefix(int index) {
        // Prefixes are not supported
        return null;
    }

    @Override
    public String getAttributeType(int index) {
        // Validation is not supported
        return "CDATA";
    }

    @Override
    public boolean isAttributeDefault(int index) {
        // Validation is not supported
        return false;
    }

    @Override
    public int getEventType() throws XmlPullParserException {
        return mCurrentToken;
    }

    @Override
    public int getNamespaceCount(int depth) throws XmlPullParserException {
        // Namespaces are unsupported
        return 0;
    }

    @Override
    public String getNamespacePrefix(int pos) throws XmlPullParserException {
        // Namespaces are unsupported
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNamespaceUri(int pos) throws XmlPullParserException {
        // Namespaces are unsupported
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNamespace(String prefix) {
        // Namespaces are unsupported
        throw new UnsupportedOperationException();
    }

    @Override
    public void defineEntityReplacementText(String entityName, String replacementText)
            throws XmlPullParserException {
        // Custom entities are not supported
        throw new UnsupportedOperationException();
    }

    @Override
    public void setFeature(String name, boolean state) throws XmlPullParserException {
        // Features are not supported
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getFeature(String name) {
        // Features are not supported
        throw new UnsupportedOperationException();
    }

    @Override
    public void setProperty(String name, Object value) throws XmlPullParserException {
        // Properties are not supported
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getProperty(String name) {
        // Properties are not supported
        throw new UnsupportedOperationException();
    }

    private static IllegalArgumentException illegalNamespace() {
        throw new IllegalArgumentException("Namespaces are not supported");
    }

    /**
     * Holder representing a single attribute. This design enables object
     * recycling without resorting to autoboxing.
     * <p>
     * To support conversion between human-readable XML and binary XML, the
     * various accessor methods will transparently convert from/to
     * human-readable values when needed.
     */
    private static class Attribute {
        public String name;
        public int type;

        public String valueString;
        public byte[] valueBytes;
        public int valueInt;
        public long valueLong;
        public float valueFloat;
        public double valueDouble;

        public void reset() {
            name = null;
            valueString = null;
            valueBytes = null;
        }

        public @Nullable String getValueString() {
            switch (type) {
                case TYPE_NULL:
                    return null;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    return valueString;
                case TYPE_BYTES_HEX:
                    return bytesToHexString(valueBytes);
                case TYPE_BYTES_BASE64:
                    return Base64.encodeToString(valueBytes, Base64.NO_WRAP);
                case TYPE_INT:
                    return Integer.toString(valueInt);
                case TYPE_INT_HEX:
                    return Integer.toString(valueInt, 16);
                case TYPE_LONG:
                    return Long.toString(valueLong);
                case TYPE_LONG_HEX:
                    return Long.toString(valueLong, 16);
                case TYPE_FLOAT:
                    return Float.toString(valueFloat);
                case TYPE_DOUBLE:
                    return Double.toString(valueDouble);
                case TYPE_BOOLEAN_TRUE:
                    return "true";
                case TYPE_BOOLEAN_FALSE:
                    return "false";
                default:
                    // Unknown data type; null is the best we can offer
                    return null;
            }
        }

        public @Nullable byte[] getValueBytesHex() throws XmlPullParserException {
            switch (type) {
                case TYPE_NULL:
                    return null;
                case TYPE_BYTES_HEX:
                case TYPE_BYTES_BASE64:
                    return valueBytes;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return hexStringToBytes(valueString);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public @Nullable byte[] getValueBytesBase64() throws XmlPullParserException {
            switch (type) {
                case TYPE_NULL:
                    return null;
                case TYPE_BYTES_HEX:
                case TYPE_BYTES_BASE64:
                    return valueBytes;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Base64.decode(valueString, Base64.NO_WRAP);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public int getValueInt() throws XmlPullParserException {
            switch (type) {
                case TYPE_INT:
                case TYPE_INT_HEX:
                    return valueInt;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Integer.parseInt(valueString);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public int getValueIntHex() throws XmlPullParserException {
            switch (type) {
                case TYPE_INT:
                case TYPE_INT_HEX:
                    return valueInt;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Integer.parseInt(valueString, 16);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public long getValueLong() throws XmlPullParserException {
            switch (type) {
                case TYPE_LONG:
                case TYPE_LONG_HEX:
                    return valueLong;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Long.parseLong(valueString);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public long getValueLongHex() throws XmlPullParserException {
            switch (type) {
                case TYPE_LONG:
                case TYPE_LONG_HEX:
                    return valueLong;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Long.parseLong(valueString, 16);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public float getValueFloat() throws XmlPullParserException {
            switch (type) {
                case TYPE_FLOAT:
                    return valueFloat;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Float.parseFloat(valueString);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public double getValueDouble() throws XmlPullParserException {
            switch (type) {
                case TYPE_DOUBLE:
                    return valueDouble;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    try {
                        return Double.parseDouble(valueString);
                    } catch (Exception e) {
                        throw new XmlPullParserException("Invalid attribute " + name + ": " + e);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }

        public boolean getValueBoolean() throws XmlPullParserException {
            switch (type) {
                case TYPE_BOOLEAN_TRUE:
                    return true;
                case TYPE_BOOLEAN_FALSE:
                    return false;
                case TYPE_STRING:
                case TYPE_STRING_INTERNED:
                    if ("true".equalsIgnoreCase(valueString)) {
                        return true;
                    } else if ("false".equalsIgnoreCase(valueString)) {
                        return false;
                    } else {
                        throw new XmlPullParserException(
                                "Invalid attribute " + name + ": " + valueString);
                    }
                default:
                    throw new XmlPullParserException("Invalid conversion from " + type);
            }
        }
    }

    // NOTE: To support unbundled clients, we include an inlined copy
    // of hex conversion logic from HexDump below
    private final static char[] HEX_DIGITS =
            { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private static int toByte(char c) {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);
        throw new IllegalArgumentException("Invalid hex char '" + c + "'");
    }

    static String bytesToHexString(byte[] value) {
        final int length = value.length;
        final char[] buf = new char[length * 2];
        int bufIndex = 0;
        for (int i = 0; i < length; i++) {
            byte b = value[i];
            buf[bufIndex++] = HEX_DIGITS[(b >>> 4) & 0x0F];
            buf[bufIndex++] = HEX_DIGITS[b & 0x0F];
        }
        return new String(buf);
    }

    static byte[] hexStringToBytes(String value) {
        final int length = value.length();
        if (length % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex length " + length);
        }
        byte[] buffer = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            buffer[i / 2] = (byte) ((toByte(value.charAt(i)) << 4)
                    | toByte(value.charAt(i + 1)));
        }
        return buffer;
    }
}
