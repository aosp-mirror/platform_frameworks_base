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

package android.content;

import android.net.Uri;
import android.util.Xml;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Stack;

/**
 * Inserts default data from InputStream, should be in XML format.
 * If the provider syncs data to the server, the imported data will be synced to the server.
 * <p>Samples:</p>
 * <br/>
 *  Insert one row:
 * <pre>
 * &lt;row uri="content://contacts/people">
 *  &lt;Col column = "name" value = "foo feebe "/>
 *  &lt;Col column = "addr" value = "Tx"/>
 * &lt;/row></pre>
 * <br/>
 * Delete, it must be in order of uri, select, and arg:
 * <pre>
 * &lt;del uri="content://contacts/people" select="name=? and addr=?" 
 *  arg1 = "foo feebe" arg2 ="Tx"/></pre>
 * <br/>
 *  Use first row's uri to insert into another table,
 *  content://contacts/people/1/phones:
 * <pre>
 * &lt;row uri="content://contacts/people">
 *  &lt;col column = "name" value = "foo feebe"/>
 *  &lt;col column = "addr" value = "Tx"/>
 *  &lt;row postfix="phones">
 *    &lt;col column="number" value="512-514-6535"/>
 *  &lt;/row>
 *  &lt;row postfix="phones">
 *    &lt;col column="cell" value="512-514-6535"/>
 *  &lt;/row>  
 * &lt;/row></pre>
 * <br/>
 *  Insert multiple rows in to same table and same attributes:
 * <pre>
 * &lt;row uri="content://contacts/people" >
 *  &lt;row>
 *   &lt;col column= "name" value = "foo feebe"/>
 *   &lt;col column= "addr" value = "Tx"/>
 *  &lt;/row>
 *  &lt;row>
 *  &lt;/row>
 * &lt;/row></pre>
 *
 * @hide
 */ 
public class DefaultDataHandler implements ContentInsertHandler {
    private final static String ROW = "row";
    private final static String COL = "col";
    private final static String URI_STR = "uri";
    private final static String POSTFIX = "postfix";
    private final static String DEL = "del";
    private final static String SELECT = "select";
    private final static String ARG = "arg";   
   
    private Stack<Uri> mUris = new Stack<Uri>();
    private ContentValues mValues;
    private ContentResolver mContentResolver;   
    
    public void insert(ContentResolver contentResolver, InputStream in)
            throws IOException, SAXException {
        mContentResolver = contentResolver;
        Xml.parse(in, Xml.Encoding.UTF_8, this);
    }
    
    public void insert(ContentResolver contentResolver, String in)
        throws SAXException {
        mContentResolver = contentResolver;
        Xml.parse(in, this);
    }
    
    private void parseRow(Attributes atts) throws SAXException {
        String uriStr = atts.getValue(URI_STR);
        Uri uri;
        if (uriStr != null) {
            // case 1
            uri = Uri.parse(uriStr);
            if (uri == null) {
                throw new SAXException("attribute " +
                        atts.getValue(URI_STR) + " parsing failure"); 
            }
            
        } else if (mUris.size() > 0){
            // case 2
            String postfix = atts.getValue(POSTFIX);
            if (postfix != null) {
                uri = Uri.withAppendedPath(mUris.lastElement(),
                        postfix);
            } else {
                uri = mUris.lastElement();
            } 
        } else {
            throw new SAXException("attribute parsing failure"); 
        }
        
        mUris.push(uri);
        
    }
    
    private Uri insertRow() {
        Uri u = mContentResolver.insert(mUris.lastElement(), mValues);
        mValues = null;
        return u;
    }
    
    public void startElement(String uri, String localName, String name,
            Attributes atts) throws SAXException {
        if (ROW.equals(localName)) {            
            if (mValues != null) {
                // case 2, <Col> before <Row> insert last uri
                if (mUris.empty()) {
                    throw new SAXException("uri is empty");
                }
                Uri nextUri = insertRow();
                if (nextUri == null) {
                    throw new SAXException("insert to uri " + 
                            mUris.lastElement().toString() + " failure");
                } else {
                    // make sure the stack lastElement save uri for more than one row
                    mUris.pop();
                    mUris.push(nextUri);
                    parseRow(atts);
                }
            } else {
                int attrLen = atts.getLength();
                if (attrLen == 0) {
                    // case 3, share same uri as last level
                    mUris.push(mUris.lastElement());
                } else {
                    parseRow(atts);
                }
            }                
        } else if (COL.equals(localName)) {
            int attrLen = atts.getLength();
            if (attrLen != 2) {
                throw new SAXException("illegal attributes number " + attrLen);
            }
            String key = atts.getValue(0);
            String value = atts.getValue(1);
            if (key != null && key.length() > 0 && value != null && value.length() > 0) {
                if (mValues == null) {
                    mValues = new ContentValues();
                }
                mValues.put(key, value);
            } else {
                throw new SAXException("illegal attributes value");
            }            
        } else if (DEL.equals(localName)){
            Uri u = Uri.parse(atts.getValue(URI_STR));
            if (u == null) {
                throw new SAXException("attribute " +
                        atts.getValue(URI_STR) + " parsing failure"); 
            }
            int attrLen = atts.getLength() - 2;
            if (attrLen > 0) {
                String[] selectionArgs = new String[attrLen];
                for (int i = 0; i < attrLen; i++) {
                    selectionArgs[i] = atts.getValue(i+2);
                }
                mContentResolver.delete(u, atts.getValue(1), selectionArgs);
            } else if (attrLen == 0){
                mContentResolver.delete(u, atts.getValue(1), null);
            } else {
                mContentResolver.delete(u, null, null);
            }
            
        } else {
            throw new SAXException("unknown element: " + localName);
        }
    }
    
    public void endElement(String uri, String localName, String name)
            throws SAXException {
        if (ROW.equals(localName)) {
            if (mUris.empty()) {
                throw new SAXException("uri mismatch"); 
            }
            if (mValues != null) {
                insertRow();
            }
            mUris.pop();                
        } 
    }


    public void characters(char[] ch, int start, int length)
            throws SAXException {
        // TODO Auto-generated method stub

    }

    public void endDocument() throws SAXException {
        // TODO Auto-generated method stub

    }

    public void endPrefixMapping(String prefix) throws SAXException {
        // TODO Auto-generated method stub

    }

    public void ignorableWhitespace(char[] ch, int start, int length)
            throws SAXException {
        // TODO Auto-generated method stub

    }

    public void processingInstruction(String target, String data)
            throws SAXException {
        // TODO Auto-generated method stub

    }

    public void setDocumentLocator(Locator locator) {
        // TODO Auto-generated method stub

    }

    public void skippedEntity(String name) throws SAXException {
        // TODO Auto-generated method stub

    }

    public void startDocument() throws SAXException {
        // TODO Auto-generated method stub

    }

    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        // TODO Auto-generated method stub

    }
    
}
