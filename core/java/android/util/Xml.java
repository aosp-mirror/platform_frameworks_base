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

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;

import org.apache.harmony.xml.ExpatPullParser;
import org.apache.harmony.xml.ExpatReader;

/**
 * XML utility methods.
 */
public class Xml {

    /**
     * {@link org.xmlpull.v1.XmlPullParser} "relaxed" feature name.
     *
     * @see <a href="http://xmlpull.org/v1/doc/features.html#relaxed">
     *  specification</a>
     */
    public static String FEATURE_RELAXED = ExpatPullParser.FEATURE_RELAXED;

    /**
     * Parses the given xml string and fires events on the given SAX handler.
     */
    public static void parse(String xml, ContentHandler contentHandler)
            throws SAXException {
        try {
            XMLReader reader = new ExpatReader();
            reader.setContentHandler(contentHandler);
            reader.parse(new InputSource(new StringReader(xml)));
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Parses xml from the given reader and fires events on the given SAX
     * handler.
     */
    public static void parse(Reader in, ContentHandler contentHandler)
            throws IOException, SAXException {
        XMLReader reader = new ExpatReader();
        reader.setContentHandler(contentHandler);
        reader.parse(new InputSource(in));
    }

    /**
     * Parses xml from the given input stream and fires events on the given SAX
     * handler.
     */
    public static void parse(InputStream in, Encoding encoding,
            ContentHandler contentHandler) throws IOException, SAXException {
        try {
            XMLReader reader = new ExpatReader();
            reader.setContentHandler(contentHandler);
            InputSource source = new InputSource(in);
            source.setEncoding(encoding.expatName);
            reader.parse(source);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Creates a new pull parser with namespace support.
     *
     * <p><b>Note:</b> This is actually slower than the SAX parser, and it's not
     *   fully implemented. If you need a fast, mostly implemented pull parser,
     *   use this. If you need a complete implementation, use KXML.
     */
    public static XmlPullParser newPullParser() {
        ExpatPullParser parser = new ExpatPullParser();
        parser.setNamespaceProcessingEnabled(true);
        return parser;
    }

    /**
     * Creates a new xml serializer.
     */
    public static XmlSerializer newSerializer() {
        try {
            return XmlSerializerFactory.instance.newSerializer();
        } catch (XmlPullParserException e) {
            throw new AssertionError(e);
        }
    }

    /** Factory for xml serializers. Initialized on demand. */
    static class XmlSerializerFactory {
        static final String TYPE
                = "org.kxml2.io.KXmlParser,org.kxml2.io.KXmlSerializer";
        static final XmlPullParserFactory instance;
        static {
            try {
                instance = XmlPullParserFactory.newInstance(TYPE, null);
            } catch (XmlPullParserException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Supported character encodings.
     */
    public enum Encoding {

        US_ASCII("US-ASCII"),
        UTF_8("UTF-8"),
        UTF_16("UTF-16"),
        ISO_8859_1("ISO-8859-1");

        final String expatName;

        Encoding(String expatName) {
            this.expatName = expatName;
        }
    }

    /**
     * Finds an encoding by name. Returns UTF-8 if you pass {@code null}.
     */
    public static Encoding findEncodingByName(String encodingName)
            throws UnsupportedEncodingException {
        if (encodingName == null) {
            return Encoding.UTF_8;
        }

        for (Encoding encoding : Encoding.values()) {
            if (encoding.expatName.equalsIgnoreCase(encodingName))
                return encoding;
        }
        throw new UnsupportedEncodingException(encodingName);
    }
    
    /**
     * Return an AttributeSet interface for use with the given XmlPullParser.
     * If the given parser itself implements AttributeSet, that implementation
     * is simply returned.  Otherwise a wrapper class is
     * instantiated on top of the XmlPullParser, as a proxy for retrieving its
     * attributes, and returned to you.
     * 
     * @param parser The existing parser for which you would like an
     *               AttributeSet.
     * 
     * @return An AttributeSet you can use to retrieve the
     *         attribute values at each of the tags as the parser moves
     *         through its XML document.
     *         
     * @see AttributeSet
     */
    public static AttributeSet asAttributeSet(XmlPullParser parser) {
        return (parser instanceof AttributeSet)
                ? (AttributeSet) parser
                : new XmlPullAttributes(parser);
    }
}
