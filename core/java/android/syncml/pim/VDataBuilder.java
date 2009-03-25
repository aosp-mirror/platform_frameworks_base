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

package android.syncml.pim;

import android.content.ContentValues;
import android.util.Log;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.net.QuotedPrintableCodec;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Vector;

/**
 * Store the parse result to custom datastruct: VNode, PropertyNode
 * Maybe several vcard instance, so use vNodeList to store.
 * VNode: standy by a vcard instance.
 * PropertyNode: standy by a property line of a card.
 */
public class VDataBuilder implements VBuilder {
    static private String LOG_TAG = "VDATABuilder"; 

    /** type=VNode */
    public List<VNode> vNodeList = new ArrayList<VNode>();
    private int mNodeListPos = 0;
    private VNode mCurrentVNode;
    private PropertyNode mCurrentPropNode;
    private String mCurrentParamType;
    
    /**
     * Assumes that each String can be encoded into byte array using this encoding.
     */
    private String mCharset;
    
    private boolean mStrictLineBreakParsing;
    
    public VDataBuilder() {
        mCharset = "ISO-8859-1";
        mStrictLineBreakParsing = false;
    }

    public VDataBuilder(String encoding, boolean strictLineBreakParsing) {
        mCharset = encoding;
        mStrictLineBreakParsing = strictLineBreakParsing;
    }
    
    public void start() {
    }

    public void end() {
    }

    public void startRecord(String type) {
        VNode vnode = new VNode();
        vnode.parseStatus = 1;
        vnode.VName = type;
        vNodeList.add(vnode);
        mNodeListPos = vNodeList.size()-1;
        mCurrentVNode = vNodeList.get(mNodeListPos);
    }

    public void endRecord() {
        VNode endNode = vNodeList.get(mNodeListPos);
        endNode.parseStatus = 0;
        while(mNodeListPos > 0){
            mNodeListPos--;
            if((vNodeList.get(mNodeListPos)).parseStatus == 1)
                break;
        }
        mCurrentVNode = vNodeList.get(mNodeListPos);
    }

    public void startProperty() {
        //  System.out.println("+ startProperty. ");
    }

    public void endProperty() {
        //  System.out.println("- endProperty. ");
    }
    
    public void propertyName(String name) {
        mCurrentPropNode = new PropertyNode();
        mCurrentPropNode.propName = name;
    }

    // Used only in VCard.
    public void propertyGroup(String group) {
        mCurrentPropNode.propGroupSet.add(group);
    }
    
    public void propertyParamType(String type) {
        mCurrentParamType = type;
    }

    public void propertyParamValue(String value) {
        if (mCurrentParamType == null ||
                mCurrentParamType.equalsIgnoreCase("TYPE")) {
            mCurrentPropNode.paramMap_TYPE.add(value);
        } else {
            mCurrentPropNode.paramMap.put(mCurrentParamType, value);
        }

        mCurrentParamType = null;
    }

    private String encodeString(String originalString, String targetEncoding) {
        Charset charset = Charset.forName(mCharset);
        ByteBuffer byteBuffer = charset.encode(originalString);
        // byteBuffer.array() "may" return byte array which is larger than
        // byteBuffer.remaining(). Here, we keep on the safe side.
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        try {
            return new String(bytes, targetEncoding);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }
    
    public void propertyValues(List<String> values) {
        ContentValues paramMap = mCurrentPropNode.paramMap;
        
        String charsetString = paramMap.getAsString("CHARSET"); 

        boolean setupParamValues = false;
        //decode value string to propValue_bytes
        if (paramMap.containsKey("ENCODING")) {
            String encoding = paramMap.getAsString("ENCODING"); 
            if (encoding.equalsIgnoreCase("BASE64") ||
                    encoding.equalsIgnoreCase("B")) {
                if (values.size() > 1) {
                    Log.e(LOG_TAG,
                            ("BASE64 encoding is used while " +
                             "there are multiple values (" + values.size()));
                }
                mCurrentPropNode.propValue_bytes =
                    Base64.decodeBase64(values.get(0).
                            replaceAll(" ","").replaceAll("\t","").
                            replaceAll("\r\n","").
                            getBytes());
            }

            if(encoding.equalsIgnoreCase("QUOTED-PRINTABLE")){
                // if CHARSET is defined, we translate each String into the Charset.
                List<String> tmpValues = new ArrayList<String>();
                Vector<byte[]> byteVector = new Vector<byte[]>();
                int size = 0;
                try{
                    for (String value : values) {                                    
                        String quotedPrintable = value
                        .replaceAll("= ", " ").replaceAll("=\t", "\t");
                        String[] lines;
                        if (mStrictLineBreakParsing) {
                            lines = quotedPrintable.split("\r\n");
                        } else {
                            lines = quotedPrintable
                            .replace("\r\n", "\n").replace("\r", "\n").split("\n");
                        }
                        StringBuilder builder = new StringBuilder();
                        for (String line : lines) {
                            if (line.endsWith("=")) {
                                line = line.substring(0, line.length() - 1);
                            }
                            builder.append(line);
                        }
                        byte[] bytes = QuotedPrintableCodec.decodeQuotedPrintable(
                                builder.toString().getBytes());
                        if (charsetString != null) {
                            try {
                                tmpValues.add(new String(bytes, charsetString));
                            } catch (UnsupportedEncodingException e) {
                                Log.e(LOG_TAG, "Failed to encode: charset=" + charsetString);
                                tmpValues.add(new String(bytes));
                            }
                        } else {
                            tmpValues.add(new String(bytes));
                        }
                        byteVector.add(bytes);
                        size += bytes.length;
                    }  // for (String value : values) {
                    mCurrentPropNode.propValue_vector = tmpValues;
                    mCurrentPropNode.propValue = listToString(tmpValues);

                    mCurrentPropNode.propValue_bytes = new byte[size];

                    {
                        byte[] tmpBytes = mCurrentPropNode.propValue_bytes;
                        int index = 0;
                        for (byte[] bytes : byteVector) {
                            int length = bytes.length;
                            for (int i = 0; i < length; i++, index++) {
                                tmpBytes[index] = bytes[i];
                            }
                        }
                    }
                    setupParamValues = true;
                } catch(Exception e) {
                    Log.e(LOG_TAG, "Failed to decode quoted-printable: " + e);
                }
            }  // QUOTED-PRINTABLE
        }  //  ENCODING
        
        if (!setupParamValues) {
            // if CHARSET is defined, we translate each String into the Charset.
            if (charsetString != null) {
                List<String> tmpValues = new ArrayList<String>();
                for (String value : values) {
                    String result = encodeString(value, charsetString);
                    if (result != null) {
                        tmpValues.add(result);
                    } else {
                        Log.e(LOG_TAG, "Failed to encode: charset=" + charsetString);
                        tmpValues.add(value);
                    }
                }
                values = tmpValues;
            }
            
            mCurrentPropNode.propValue_vector = values;
            mCurrentPropNode.propValue = listToString(values);
        }
        mCurrentVNode.propList.add(mCurrentPropNode);
    }

    private String listToString(Collection<String> list){
        StringBuilder typeListB = new StringBuilder();
        for (String type : list) {
            typeListB.append(type).append(";");
        }
        int len = typeListB.length();
        if (len > 0 && typeListB.charAt(len - 1) == ';') {
            return typeListB.substring(0, len - 1);
        }
        return typeListB.toString();
    }
    
    public String getResult(){
        return null;
    }
}

