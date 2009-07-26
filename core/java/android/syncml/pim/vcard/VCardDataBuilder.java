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

package android.syncml.pim.vcard;

import android.app.ProgressDialog;
import android.content.AbstractSyncableContentProvider;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.os.Handler;
import android.provider.Contacts;
import android.syncml.pim.PropertyNode;
import android.syncml.pim.VBuilder;
import android.syncml.pim.VNode;
import android.syncml.pim.VParser;
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
 * VBuilder for VCard. VCard may contain big photo images encoded by BASE64,
 * If we store all VNode entries in memory like VDataBuilder.java,
 * OutOfMemoryError may be thrown. Thus, this class push each VCard entry into
 * ContentResolver immediately.
 */
public class VCardDataBuilder implements VBuilder {
    static private String LOG_TAG = "VCardDataBuilder"; 
    
    /**
     * If there's no other information available, this class uses this charset for encoding
     * byte arrays.
     */
    static public String DEFAULT_CHARSET = "UTF-8"; 
    
    private class ProgressShower implements Runnable {
        private ContactStruct mContact;
        
        public ProgressShower(ContactStruct contact) {
            mContact = contact;
        }
        
        public void run () {
            mProgressDialog.setMessage(mProgressMessage + "\n" + 
                    mContact.displayString());
        }
    }
    
    /** type=VNode */
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
    private ContentResolver mContentResolver;
    
    // For letting VCardDataBuilder show the display name of VCard while handling it.
    private Handler mHandler;
    private ProgressDialog mProgressDialog;
    private String mProgressMessage;
    private Runnable mOnProgressRunnable;
    private boolean mLastNameComesBeforeFirstName;
    
    // Just for testing.
    private long mTimeCreateContactStruct;
    private long mTimePushIntoContentResolver;
    
    // Ideally, this should be ContactsProvider but it seems Class loader cannot find it,
    // even when it is subclass of ContactsProvider...
    private AbstractSyncableContentProvider mProvider;
    private long mMyContactsGroupId;
    
    public VCardDataBuilder(ContentResolver resolver) {
        mTargetCharset = DEFAULT_CHARSET;
        mContentResolver = resolver;
    }
    
    /**
     * Constructor which requires minimum requiredvariables.
     * 
     * @param resolver insert each data into this ContentResolver
     * @param progressDialog 
     * @param progressMessage
     * @param handler if this importer works on the different thread than main one,
     * set appropriate handler object. If not, it is ok to set this null.
     */
    public VCardDataBuilder(ContentResolver resolver,
            ProgressDialog progressDialog,
            String progressMessage,
            Handler handler) {
        this(resolver, progressDialog, progressMessage, handler,
                null, null, false, false);
    }

    public VCardDataBuilder(ContentResolver resolver,
            ProgressDialog progressDialog,
            String progressMessage,
            Handler handler,
            String charset,
            boolean strictLineBreakParsing,
            boolean lastNameComesBeforeFirstName) {
        this(resolver, progressDialog, progressMessage, handler,
                null, charset, strictLineBreakParsing,
                lastNameComesBeforeFirstName);
    }
    
    /**
     * @hide
     */
    public VCardDataBuilder(ContentResolver resolver,
            ProgressDialog progressDialog,
            String progressMessage,
            Handler handler,
            String sourceCharset,
            String targetCharset,
            boolean strictLineBreakParsing,
            boolean lastNameComesBeforeFirstName) {
        if (sourceCharset != null) {
            mSourceCharset = sourceCharset;
        } else {
            mSourceCharset = VParser.DEFAULT_CHARSET;
        }
        if (targetCharset != null) {
            mTargetCharset = targetCharset;
        } else {
            mTargetCharset = DEFAULT_CHARSET;
        }
        mContentResolver = resolver;
        mStrictLineBreakParsing = strictLineBreakParsing;
        mHandler = handler;
        mProgressDialog = progressDialog;
        mProgressMessage = progressMessage;
        mLastNameComesBeforeFirstName = lastNameComesBeforeFirstName;
        
        tryGetOriginalProvider();
    }
    
    private void tryGetOriginalProvider() {
        final ContentResolver resolver = mContentResolver;
        
        if ((mMyContactsGroupId = Contacts.People.tryGetMyContactsGroupId(resolver)) == 0) {
            Log.e(LOG_TAG, "Could not get group id of MyContact");
            return;
        }

        IContentProvider iProviderForName = resolver.acquireProvider(Contacts.CONTENT_URI);
        ContentProvider contentProvider =
            ContentProvider.coerceToLocalContentProvider(iProviderForName);
        if (contentProvider == null) {
            Log.e(LOG_TAG, "Fail to get ContentProvider object.");
            return;
        }
        
        if (!(contentProvider instanceof AbstractSyncableContentProvider)) {
            Log.e(LOG_TAG,
                    "Acquired ContentProvider object is not AbstractSyncableContentProvider.");
            return;
        }
        
        mProvider = (AbstractSyncableContentProvider)contentProvider; 
    }
    
    public void setOnProgressRunnable(Runnable runnable) {
        mOnProgressRunnable = runnable;
    }
    
    public void start() {
    }

    public void end() {
    }

    /**
     * Assume that VCard is not nested. In other words, this code does not accept 
     */
    public void startRecord(String type) {
        if (mCurrentVNode != null) {
            // This means startRecord() is called inside startRecord() - endRecord() block.
            // TODO: should throw some Exception
            Log.e(LOG_TAG, "Nested VCard code is not supported now.");
        }
        mCurrentVNode = new VNode();
        mCurrentVNode.parseStatus = 1;
        mCurrentVNode.VName = type;
    }

    public void endRecord() {
        mCurrentVNode.parseStatus = 0;
        long start = System.currentTimeMillis();
        ContactStruct contact = ContactStruct.constructContactFromVNode(mCurrentVNode,
                mLastNameComesBeforeFirstName ? ContactStruct.NAME_ORDER_TYPE_JAPANESE :
                    ContactStruct.NAME_ORDER_TYPE_ENGLISH);
        mTimeCreateContactStruct += System.currentTimeMillis() - start;
        if (!contact.isIgnorable()) {
            if (mProgressDialog != null && mProgressMessage != null) {
                if (mHandler != null) {
                    mHandler.post(new ProgressShower(contact));
                } else {
                    mProgressDialog.setMessage(mProgressMessage + "\n" + 
                            contact.displayString());
                }
            }
            start = System.currentTimeMillis();
            if (mProvider != null) {
                contact.pushIntoAbstractSyncableContentProvider(
                        mProvider, mMyContactsGroupId);
            } else {
                contact.pushIntoContentResolver(mContentResolver);
            }
            mTimePushIntoContentResolver += System.currentTimeMillis() - start;
        }
        if (mOnProgressRunnable != null) {
            mOnProgressRunnable.run();
        }
        mCurrentVNode = null;
    }

    public void startProperty() {
        mCurrentPropNode = new PropertyNode();
    }

    public void endProperty() {
        mCurrentVNode.propList.add(mCurrentPropNode);
        mCurrentPropNode = null;
    }
    
    public void propertyName(String name) {
        mCurrentPropNode.propName = name;
    }

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
            return new String(bytes);
        }
    }
    
    private String handleOneValue(String value, String targetCharset, String encoding) {
        if (encoding != null) {
            if (encoding.equals("BASE64") || encoding.equals("B")) {
                mCurrentPropNode.propValue_bytes =
                    Base64.decodeBase64(value.getBytes());
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

    public void showDebugInfo() {
        Log.d(LOG_TAG, "time for creating ContactStruct: " + mTimeCreateContactStruct + " ms");
        Log.d(LOG_TAG, "time for insert ContactStruct to database: " + 
                mTimePushIntoContentResolver + " ms");
    }
    
    private String listToString(List<String> list){
        int size = list.size();
        if (size > 1) {
            StringBuilder builder = new StringBuilder();
            int i = 0;
            for (String type : list) {
                builder.append(type);
                if (i < size - 1) {
                    builder.append(";");
                }
            }
            return builder.toString();
        } else if (size == 1) {
            return list.get(0);
        } else {
            return "";
        }
    }
}
