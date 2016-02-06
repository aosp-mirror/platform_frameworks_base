package com.android.hotspot2.omadm;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Parses an OMA-DM XML tree.
 */
public class OMAParser extends DefaultHandler {
    private XMLNode mRoot;
    private XMLNode mCurrent;

    public OMAParser() {
        mRoot = null;
        mCurrent = null;
    }

    public MOTree parse(String text, String urn) throws IOException, SAXException {
        try {
            SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            parser.parse(new InputSource(new StringReader(text)), this);
            return new MOTree(mRoot, urn);
        } catch (ParserConfigurationException pce) {
            throw new SAXException(pce);
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        XMLNode parent = mCurrent;

        mCurrent = new XMLNode(mCurrent, qName, attributes);

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
