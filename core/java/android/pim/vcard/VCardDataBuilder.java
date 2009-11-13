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

import android.accounts.Account;
import android.util.CharsetUtils;
import android.util.Log;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.net.QuotedPrintableCodec;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * VBuilder for VCard. VCard may contain big photo images encoded by BASE64,
 * If we store all VNode entries in memory like VDataBuilder.java,
 * OutOfMemoryError may be thrown. Thus, this class push each VCard entry into
 * ContentResolver immediately.
 */
public class VCardDataBuilder implements VCardBuilder {
    static private String LOG_TAG = "VCardDataBuilder"; 
    
    /**
     * If there's no other information available, this class uses this charset for encoding
     * byte arrays.
     */
    static public String TARGET_CHARSET = "UTF-8"; 
    
    private ContactStruct.Property mCurrentProperty = new ContactStruct.Property();
    private ContactStruct mCurrentContactStruct;
    private String mParamType;
    
    /**
     * The charset using which VParser parses the text.
     */
    private String mSourceCharset;
    
    /**
     * The charset with which byte array is encoded to String.
     */
    private String mTargetCharset;
    private boolean mStrictLineBreakParsing;
    
    final private int mVCardType;
    final private Account mAccount;
    
    // Just for testing.
    private long mTimePushIntoContentResolver;
    
    private List<EntryHandler> mEntryHandlers = new ArrayList<EntryHandler>();
    
    public VCardDataBuilder() {
        this(null, null, false, VCardConfig.VCARD_TYPE_V21_GENERIC, null);
    }

    /**
     * @hide 
     */
    public VCardDataBuilder(int vcardType) {
        this(null, null, false, vcardType, null);
    }

    /**
     * @hide 
     */
    public VCardDataBuilder(String charset,
            boolean strictLineBreakParsing, int vcardType, Account account) {
        this(null, charset, strictLineBreakParsing, vcardType, account);
    }
    
    /**
     * @hide
     */
    public VCardDataBuilder(String sourceCharset,
            String targetCharset,
            boolean strictLineBreakParsing,
            int vcardType,
            Account account) {
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
        mVCardType = vcardType;
        mAccount = account;
    }
    
    public void addEntryHandler(EntryHandler entryHandler) {
        mEntryHandlers.add(entryHandler);
    }
    
    public void start() {
        for (EntryHandler entryHandler : mEntryHandlers) {
            entryHandler.onParsingStart();
        }
    }

    public void end() {
        for (EntryHandler entryHandler : mEntryHandlers) {
            entryHandler.onParsingEnd();
        }
    }

    /**
     * Assume that VCard is not nested. In other words, this code does not accept 
     */
    public void startRecord(String type) {
        // TODO: add the method clear() instead of using null for reducing GC?
        if (mCurrentContactStruct != null) {
            // This means startRecord() is called inside startRecord() - endRecord() block.
            // TODO: should throw some Exception
            Log.e(LOG_TAG, "Nested VCard code is not supported now.");
        }
        if (!type.equalsIgnoreCase("VCARD")) {
            // TODO: add test case for this
            Log.e(LOG_TAG, "This is not VCARD!");
        }

        mCurrentContactStruct = new ContactStruct(mVCardType, mAccount);
    }

    public void endRecord() {
        mCurrentContactStruct.consolidateFields();
        for (EntryHandler entryHandler : mEntryHandlers) {
            entryHandler.onEntryCreated(mCurrentContactStruct);
        }
        mCurrentContactStruct = null;
    }

    public void startProperty() {
        mCurrentProperty.clear();
    }

    public void endProperty() {
        mCurrentContactStruct.addProperty(mCurrentProperty);
    }
    
    public void propertyName(String name) {
        mCurrentProperty.setPropertyName(name);
    }

    public void propertyGroup(String group) {
        // ContactStruct does not support Group.
    }
    
    public void propertyParamType(String type) {
        if (mParamType != null) {
            Log.e(LOG_TAG, "propertyParamType() is called more than once " +
                    "before propertyParamValue() is called");
        }
        mParamType = type;
    }

    public void propertyParamValue(String value) {
        if (mParamType == null) {
            // From vCard 2.1 specification. vCard 3.0 formally does not allow this case.
            mParamType = "TYPE";
        }
        mCurrentProperty.addParameter(mParamType, value);
        mParamType = null;
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
            if (encoding.equals("BASE64") || encoding.equals("B")) {
                mCurrentProperty.setPropertyBytes(Base64.decodeBase64(value.getBytes()));
                return value;
            } else if (encoding.equals("QUOTED-PRINTABLE")) {
                // "= " -> " ", "=\t" -> "\t".
                // Previous code had done this replacement. Keep on the safe side.
                StringBuilder builder = new StringBuilder();
                int length = value.length();
                for (int i = 0; i < length; i++) {
                    char ch = value.charAt(i);
                    if (ch == '=' && i < length - 1) {
                        char nextCh = value.charAt(i + 1);
                        if (nextCh == ' ' || nextCh == '\t') {

                            builder.append(nextCh);
                            i++;
                            continue;
                        }
                    }
                    builder.append(ch);
                }
                String quotedPrintable = builder.toString();
                
                String[] lines;
                if (mStrictLineBreakParsing) {
                    lines = quotedPrintable.split("\r\n");
                } else {
                    builder = new StringBuilder();
                    length = quotedPrintable.length();
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
                
                builder = new StringBuilder();
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
            return;
        }

        final Collection<String> charsetCollection = mCurrentProperty.getParameters("CHARSET");
        String charset =
            ((charsetCollection != null) ? charsetCollection.iterator().next() : null);
        String targetCharset = CharsetUtils.nameForDefaultVendor(charset); 
        
        final Collection<String> encodingCollection = mCurrentProperty.getParameters("ENCODING");
        String encoding =
            ((encodingCollection != null) ? encodingCollection.iterator().next() : null);
        
        if (targetCharset == null || targetCharset.length() == 0) {
            targetCharset = mTargetCharset;
        }
        
        for (String value : values) {
            mCurrentProperty.addToPropertyValueList(
                    handleOneValue(value, targetCharset, encoding));
        }
    }

    public void showPerformanceInfo() {
        Log.d(LOG_TAG, "time for insert ContactStruct to database: " + 
                mTimePushIntoContentResolver + " ms");
    }
}
