/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import android.content.ContentValues;
import android.pim.vcard.VCardInterpreter;
import android.pim.vcard.VCardConfig;
import android.util.CharsetUtils;
import android.util.Log;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.net.QuotedPrintableCodec;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Store the parse result to custom datastruct: VNode, PropertyNode
 * Maybe several vcard instance, so use vNodeList to store.
 * VNode: standy by a vcard instance.
 * PropertyNode: standy by a property line of a card.
 *
 * Previously used in main vCard handling code but now exists only for testing.
 */
public class VNodeBuilder implements VCardInterpreter {
    static private String LOG_TAG = "VNodeBuilder"; 
    
    /**
     * If there's no other information available, this class uses this charset for encoding
     * byte arrays.
     */
    static public String TARGET_CHARSET = "UTF-8"; 
    
    /** type=VNode */
    public List<VNode> vNodeList = new ArrayList<VNode>();
    private int mNodeListPos = 0;
    private VNode mCurrentVNode;
    private PropertyNode mCurrentPropNode;
    private String mCurrentParamType;
    
    /**
     * The charset using which VParser parses the text.
     */
    private String mSourceCharset;
    
    /**
     * The charset with which byte array is encoded to String.
     */
    private String mTargetCharset;
    
    private boolean mStrictLineBreakParsing;
    
    public VNodeBuilder() {
        this(VCardConfig.DEFAULT_CHARSET, TARGET_CHARSET, false);
    }

    public VNodeBuilder(String charset, boolean strictLineBreakParsing) {
        this(null, charset, strictLineBreakParsing);
    }
    
    /**
     * @hide sourceCharset is temporal. 
     */
    public VNodeBuilder(String sourceCharset, String targetCharset,
            boolean strictLineBreakParsing) {
        if (sourceCharset != null) {
            mSourceCharset = sourceCharset;
        } else {
            mSourceCharset = VCardConfig.DEFAULT_CHARSET;
        }
        if (targetCharset != null) {
            mTargetCharset = targetCharset;
        } else {
            mTargetCharset = TARGET_CHARSET;
        }
        mStrictLineBreakParsing = strictLineBreakParsing;
    }

    public void start() {
    }

    public void end() {
    }

    // Note: I guess that this code assumes the Record may nest like this:
    // START:VPOS
    // ...
    // START:VPOS2
    // ...
    // END:VPOS2
    // ...
    // END:VPOS
    //
    // However the following code has a bug.
    // When error occurs after calling startRecord(), the entry which is probably
    // the cause of the error remains to be in vNodeList, while endRecord() is not called.
    //
    // I leave this code as is since I'm not familiar with vcalendar specification.
    // But I believe we should refactor this code in the future.
    // Until this, the last entry has to be removed when some error occurs.
    public void startEntry() {
        VNode vnode = new VNode();
        vnode.parseStatus = 1;
        vnode.VName = "VCARD";
        // I feel this should be done in endRecord(), but it cannot be done because of
        // the reason above.
        vNodeList.add(vnode);
        mNodeListPos = vNodeList.size() - 1;
        mCurrentVNode = vNodeList.get(mNodeListPos);
    }

    public void endEntry() {
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
        mCurrentPropNode = new PropertyNode();
    }

    public void endProperty() {
        mCurrentVNode.propList.add(mCurrentPropNode);
    }
    
    public void propertyName(String name) {
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

    private String encodeString(String originalString, String targetCharset) {
        if (mSourceCharset.equalsIgnoreCase(targetCharset)) {
            return originalString;
        }
        Charset charset = Charset.forName(mSourceCharset);
        ByteBuffer byteBuffer = charset.encode(originalString);
        // byteBuffer.array() "may" return byte array which is larger than
        // byteBuffer.remaining(). Here, we keep on the safe side.
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        try {
            return new String(bytes, targetCharset);
        } catch (UnsupportedEncodingException e) {
            Log.e(LOG_TAG, "Failed to encode: charset=" + targetCharset);
            return null;
        }
    }
    
    private String handleOneValue(String value, String targetCharset, String encoding) {
        if (encoding != null) {
            encoding = encoding.toUpperCase();
            if (encoding.equals("BASE64") || encoding.equals("B")) {
                // Assume BASE64 is used only when the number of values is 1.
                mCurrentPropNode.propValue_bytes =
                    Base64.decodeBase64(value.getBytes());
                return value;
            } else if (encoding.equals("QUOTED-PRINTABLE")) {
                String quotedPrintable = value
                .replaceAll("= ", " ").replaceAll("=\t", "\t");
                String[] lines;
                if (mStrictLineBreakParsing) {
                    lines = quotedPrintable.split("\r\n");
                } else {
                    StringBuilder builder = new StringBuilder();
                    int length = quotedPrintable.length();
                    ArrayList<String> list = new ArrayList<String>();
                    for (int i = 0; i < length; i++) {
                        char ch = quotedPrintable.charAt(i);
                        if (ch == '\n') {
                            list.add(builder.toString());
                            builder = new StringBuilder();
                        } else if (ch == '\r') {
                            list.add(builder.toString());
                            builder = new StringBuilder();
                            if (i < length - 1) {
                                char nextCh = quotedPrintable.charAt(i + 1);
                                if (nextCh == '\n') {
                                    i++;
                                }
                            }
                        } else {
                            builder.append(ch);
                        }
                    }
                    String finalLine = builder.toString();
                    if (finalLine.length() > 0) {
                        list.add(finalLine);
                    }
                    lines = list.toArray(new String[0]);
                }
                StringBuilder builder = new StringBuilder();
                for (String line : lines) {
                    if (line.endsWith("=")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    builder.append(line);
                }
                byte[] bytes;
                try {
                    bytes = builder.toString().getBytes(mSourceCharset);
                } catch (UnsupportedEncodingException e1) {
                    Log.e(LOG_TAG, "Failed to encode: charset=" + mSourceCharset);
                    bytes = builder.toString().getBytes();
                }
                
                try {
                    bytes = QuotedPrintableCodec.decodeQuotedPrintable(bytes);
                } catch (DecoderException e) {
                    Log.e(LOG_TAG, "Failed to decode quoted-printable: " + e);
                    return "";
                }

                try {
                    return new String(bytes, targetCharset);
                } catch (UnsupportedEncodingException e) {
                    Log.e(LOG_TAG, "Failed to encode: charset=" + targetCharset);
                    return new String(bytes);
                }
            }
            // Unknown encoding. Fall back to default.
        }
        return encodeString(value, targetCharset);
    }
    
    public void propertyValues(List<String> values) {
        if (values == null || values.size() == 0) {
            mCurrentPropNode.propValue_bytes = null;
            mCurrentPropNode.propValue_vector.clear();
            mCurrentPropNode.propValue_vector.add("");
            mCurrentPropNode.propValue = "";
            return;
        }
        
        ContentValues paramMap = mCurrentPropNode.paramMap;
        
        String targetCharset = CharsetUtils.nameForDefaultVendor(paramMap.getAsString("CHARSET")); 
        String encoding = paramMap.getAsString("ENCODING"); 
        
        if (targetCharset == null || targetCharset.length() == 0) {
            targetCharset = mTargetCharset;
        }
        
        for (String value : values) {
            mCurrentPropNode.propValue_vector.add(
                    handleOneValue(value, targetCharset, encoding));
        }

        mCurrentPropNode.propValue = listToString(mCurrentPropNode.propValue_vector);
    }

    private String listToString(List<String> list){
        int size = list.size();
        if (size > 1) {
            StringBuilder typeListB = new StringBuilder();
            for (String type : list) {
                typeListB.append(type).append(";");
            }
            int len = typeListB.length();
            if (len > 0 && typeListB.charAt(len - 1) == ';') {
                return typeListB.substring(0, len - 1);
            }
            return typeListB.toString();
        } else if (size == 1) {
            return list.get(0);
        } else {
            return "";
        }
    }
    
    public String getResult(){
        return null;
    }
}
