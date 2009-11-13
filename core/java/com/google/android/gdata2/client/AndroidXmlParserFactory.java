package com.google.android.gdata2.client;

import com.google.wireless.gdata2.parser.xml.XmlParserFactory;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.util.Xml;

/**
 * XmlParserFactory for the Android platform.
 */
public class AndroidXmlParserFactory implements XmlParserFactory {

    /*
     * (non-javadoc)
     * @see XmlParserFactory#createParser
     */
    public XmlPullParser createParser() throws XmlPullParserException {
        return Xml.newPullParser();
    }

    /*
     * (non-javadoc)
     * @see XmlParserFactory#createSerializer
     */
    public XmlSerializer createSerializer() throws XmlPullParserException {
        return Xml.newSerializer();
    }
}
