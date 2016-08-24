package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.XMLNode;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class XMLParser extends DefaultHandler {
    private final SAXParser mParser;
    private final InputSource mInputSource;

    private XMLNode mRoot;
    private XMLNode mCurrent;

    public XMLParser(InputStream in) throws ParserConfigurationException, SAXException {
        mParser = SAXParserFactory.newInstance().newSAXParser();
        mInputSource = new InputSource(new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public XMLNode getRoot() throws SAXException, IOException {
        mParser.parse(mInputSource, this);
        return mRoot;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        XMLNode parent = mCurrent;

        mCurrent = new XMLNode(mCurrent, qName, attributes);
        //System.out.println("Added " + mCurrent.getTag() + ", atts " + mCurrent.getAttributes());

        if (mRoot == null)
            mRoot = mCurrent;
        else
            parent.addChild(mCurrent);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (!qName.equals(mCurrent.getTag()))
            throw new SAXException("End tag '" + qName + "' doesn't match current node: " +
                    mCurrent);

        try {
            mCurrent.close();
        } catch (IOException ioe) {
            throw new SAXException("Failed to close element", ioe);
        }

        mCurrent = mCurrent.getParent();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        mCurrent.addText(ch, start, length);
    }
}
