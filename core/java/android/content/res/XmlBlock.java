/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.util.TypedValue;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Wrapper around a compiled XML file.
 * 
 * {@hide}
 */
final class XmlBlock {
    private static final boolean DEBUG=false;

    public XmlBlock(byte[] data) {
        mAssets = null;
        mNative = nativeCreate(data, 0, data.length);
        mStrings = new StringBlock(nativeGetStringBlock(mNative), false);
    }

    public XmlBlock(byte[] data, int offset, int size) {
        mAssets = null;
        mNative = nativeCreate(data, offset, size);
        mStrings = new StringBlock(nativeGetStringBlock(mNative), false);
    }

    public void close() {
        synchronized (this) {
            if (mOpen) {
                mOpen = false;
                decOpenCountLocked();
            }
        }
    }

    private void decOpenCountLocked() {
        mOpenCount--;
        if (mOpenCount == 0) {
            nativeDestroy(mNative);
            if (mAssets != null) {
                mAssets.xmlBlockGone(hashCode());
            }
        }
    }

    public XmlResourceParser newParser() {
        synchronized (this) {
            if (mNative != 0) {
                return new Parser(nativeCreateParseState(mNative), this);
            }
            return null;
        }
    }

    /*package*/ final class Parser implements XmlResourceParser {
        Parser(int parseState, XmlBlock block) {
            mParseState = parseState;
            mBlock = block;
            block.mOpenCount++;
        }

        public void setFeature(String name, boolean state) throws XmlPullParserException {
            if (FEATURE_PROCESS_NAMESPACES.equals(name) && state) {
                return;
            }
            if (FEATURE_REPORT_NAMESPACE_ATTRIBUTES.equals(name) && state) {
                return;
            }
            throw new XmlPullParserException("Unsupported feature: " + name);
        }
        public boolean getFeature(String name) {
            if (FEATURE_PROCESS_NAMESPACES.equals(name)) {
                return true;
            }
            if (FEATURE_REPORT_NAMESPACE_ATTRIBUTES.equals(name)) {
                return true;
            }
            return false;
        }
        public void setProperty(String name, Object value) throws XmlPullParserException {
            throw new XmlPullParserException("setProperty() not supported");
        }
        public Object getProperty(String name) {
            return null;
        }
        public void setInput(Reader in) throws XmlPullParserException {
            throw new XmlPullParserException("setInput() not supported");
        }
        public void setInput(InputStream inputStream, String inputEncoding) throws XmlPullParserException {
            throw new XmlPullParserException("setInput() not supported");
        }
        public void defineEntityReplacementText(String entityName, String replacementText) throws XmlPullParserException {
            throw new XmlPullParserException("defineEntityReplacementText() not supported");
        }
        public String getNamespacePrefix(int pos) throws XmlPullParserException {
            throw new XmlPullParserException("getNamespacePrefix() not supported");
        }
        public String getInputEncoding() {
            return null;
        }
        public String getNamespace(String prefix) {
            throw new RuntimeException("getNamespace() not supported");
        }
        public int getNamespaceCount(int depth) throws XmlPullParserException {
            throw new XmlPullParserException("getNamespaceCount() not supported");
        }
        public String getPositionDescription() {
            return "Binary XML file line #" + getLineNumber();
        }
        public String getNamespaceUri(int pos) throws XmlPullParserException {
            throw new XmlPullParserException("getNamespaceUri() not supported");
        }
        public int getColumnNumber() {
            return -1;
        }
        public int getDepth() {
            return mDepth;
        }
        public String getText() {
            int id = nativeGetText(mParseState);
            return id >= 0 ? mStrings.get(id).toString() : null;
        }
        public int getLineNumber() {
            return nativeGetLineNumber(mParseState);
        }
        public int getEventType() throws XmlPullParserException {
            return mEventType;
        }
        public boolean isWhitespace() throws XmlPullParserException {
            // whitespace was stripped by aapt.
            return false;
        }
        public String getPrefix() {
            throw new RuntimeException("getPrefix not supported");
        }
        public char[] getTextCharacters(int[] holderForStartAndLength) {
            String txt = getText();
            char[] chars = null;
            if (txt != null) {
                holderForStartAndLength[0] = 0;
                holderForStartAndLength[1] = txt.length();
                chars = new char[txt.length()];
                txt.getChars(0, txt.length(), chars, 0);
            }
            return chars;
        }
        public String getNamespace() {
            int id = nativeGetNamespace(mParseState);
            return id >= 0 ? mStrings.get(id).toString() : "";
        }
        public String getName() {
            int id = nativeGetName(mParseState);
            return id >= 0 ? mStrings.get(id).toString() : null;
        }
        public String getAttributeNamespace(int index) {
            int id = nativeGetAttributeNamespace(mParseState, index);
            if (DEBUG) System.out.println("getAttributeNamespace of " + index + " = " + id);
            if (id >= 0) return mStrings.get(id).toString();
            else if (id == -1) return "";
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }
        public String getAttributeName(int index) {
            int id = nativeGetAttributeName(mParseState, index);
            if (DEBUG) System.out.println("getAttributeName of " + index + " = " + id);
            if (id >= 0) return mStrings.get(id).toString();
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }
        public String getAttributePrefix(int index) {
            throw new RuntimeException("getAttributePrefix not supported");
        }
        public boolean isEmptyElementTag() throws XmlPullParserException {
            // XXX Need to detect this.
            return false;
        }
        public int getAttributeCount() {
            return mEventType == START_TAG ? nativeGetAttributeCount(mParseState) : -1;
        }
        public String getAttributeValue(int index) {
            int id = nativeGetAttributeStringValue(mParseState, index);
            if (DEBUG) System.out.println("getAttributeValue of " + index + " = " + id);
            if (id >= 0) return mStrings.get(id).toString();

            // May be some other type...  check and try to convert if so.
            int t = nativeGetAttributeDataType(mParseState, index);
            if (t == TypedValue.TYPE_NULL) {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }

            int v = nativeGetAttributeData(mParseState, index);
            return TypedValue.coerceToString(t, v);
        }
        public String getAttributeType(int index) {
            return "CDATA";
        }
        public boolean isAttributeDefault(int index) {
            return false;
        }
        public int nextToken() throws XmlPullParserException,IOException {
            return next();
        }
        public String getAttributeValue(String namespace, String name) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, name);
            if (idx >= 0) {
                if (DEBUG) System.out.println("getAttributeName of "
                        + namespace + ":" + name + " index = " + idx);
                if (DEBUG) System.out.println(
                        "Namespace=" + getAttributeNamespace(idx)
                        + "Name=" + getAttributeName(idx)
                        + ", Value=" + getAttributeValue(idx));
                return getAttributeValue(idx);
            }
            return null;
        }
        public int next() throws XmlPullParserException,IOException {
            if (!mStarted) {
                mStarted = true;
                return START_DOCUMENT;
            }
            if (mParseState == 0) {
                return END_DOCUMENT;
            }
            int ev = nativeNext(mParseState);
            if (mDecNextDepth) {
                mDepth--;
                mDecNextDepth = false;
            }
            switch (ev) {
            case START_TAG:
                mDepth++;
                break;
            case END_TAG:
                mDecNextDepth = true;
                break;
            }
            mEventType = ev;
            if (ev == END_DOCUMENT) {
                // Automatically close the parse when we reach the end of
                // a document, since the standard XmlPullParser interface
                // doesn't have such an API so most clients will leave us
                // dangling.
                close();
            }
            return ev;
        }
        public void require(int type, String namespace, String name) throws XmlPullParserException,IOException {
            if (type != getEventType()
                || (namespace != null && !namespace.equals( getNamespace () ) )
                || (name != null && !name.equals( getName() ) ) )
                throw new XmlPullParserException( "expected "+ TYPES[ type ]+getPositionDescription());
        }
        public String nextText() throws XmlPullParserException,IOException {
            if(getEventType() != START_TAG) {
               throw new XmlPullParserException(
                 getPositionDescription()
                 + ": parser must be on START_TAG to read next text", this, null);
            }
            int eventType = next();
            if(eventType == TEXT) {
               String result = getText();
               eventType = next();
               if(eventType != END_TAG) {
                 throw new XmlPullParserException(
                    getPositionDescription()
                    + ": event TEXT it must be immediately followed by END_TAG", this, null);
                }
                return result;
            } else if(eventType == END_TAG) {
               return "";
            } else {
               throw new XmlPullParserException(
                 getPositionDescription()
                 + ": parser must be on START_TAG or TEXT to read text", this, null);
            }
        }
        public int nextTag() throws XmlPullParserException,IOException {
            int eventType = next();
            if(eventType == TEXT && isWhitespace()) {   // skip whitespace
               eventType = next();
            }
            if (eventType != START_TAG && eventType != END_TAG) {
               throw new XmlPullParserException(
                   getPositionDescription() 
                   + ": expected start or end tag", this, null);
            }
            return eventType;
        }
    
        public int getAttributeNameResource(int index) {
            return nativeGetAttributeResource(mParseState, index);
        }
    
        public int getAttributeListValue(String namespace, String attribute,
                String[] options, int defaultValue) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeListValue(idx, options, defaultValue);
            }
            return defaultValue;
        }
        public boolean getAttributeBooleanValue(String namespace, String attribute,
                boolean defaultValue) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeBooleanValue(idx, defaultValue);
            }
            return defaultValue;
        }
        public int getAttributeResourceValue(String namespace, String attribute,
                int defaultValue) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeResourceValue(idx, defaultValue);
            }
            return defaultValue;
        }
        public int getAttributeIntValue(String namespace, String attribute,
                int defaultValue) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeIntValue(idx, defaultValue);
            }
            return defaultValue;
        }
        public int getAttributeUnsignedIntValue(String namespace, String attribute,
                                                int defaultValue)
        {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeUnsignedIntValue(idx, defaultValue);
            }
            return defaultValue;
        }
        public float getAttributeFloatValue(String namespace, String attribute,
                float defaultValue) {
            int idx = nativeGetAttributeIndex(mParseState, namespace, attribute);
            if (idx >= 0) {
                return getAttributeFloatValue(idx, defaultValue);
            }
            return defaultValue;
        }

        public int getAttributeListValue(int idx,
                String[] options, int defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            int v = nativeGetAttributeData(mParseState, idx);
            if (t == TypedValue.TYPE_STRING) {
                return XmlUtils.convertValueToList(
                    mStrings.get(v), options, defaultValue);
            }
            return v;
        }
        public boolean getAttributeBooleanValue(int idx,
                boolean defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            // Note: don't attempt to convert any other types, because
            // we want to count on appt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT &&
                t <= TypedValue.TYPE_LAST_INT) {
                return nativeGetAttributeData(mParseState, idx) != 0;
            }
            return defaultValue;
        }
        public int getAttributeResourceValue(int idx, int defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            // Note: don't attempt to convert any other types, because
            // we want to count on appt doing the conversion for us.
            if (t == TypedValue.TYPE_REFERENCE) {
                return nativeGetAttributeData(mParseState, idx);
            }
            return defaultValue;
        }
        public int getAttributeIntValue(int idx, int defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            // Note: don't attempt to convert any other types, because
            // we want to count on appt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT &&
                t <= TypedValue.TYPE_LAST_INT) {
                return nativeGetAttributeData(mParseState, idx);
            }
            return defaultValue;
        }
        public int getAttributeUnsignedIntValue(int idx, int defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            // Note: don't attempt to convert any other types, because
            // we want to count on appt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT &&
                t <= TypedValue.TYPE_LAST_INT) {
                return nativeGetAttributeData(mParseState, idx);
            }
            return defaultValue;
        }
        public float getAttributeFloatValue(int idx, float defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            // Note: don't attempt to convert any other types, because
            // we want to count on appt doing the conversion for us.
            if (t == TypedValue.TYPE_FLOAT) {
                return Float.intBitsToFloat(
                    nativeGetAttributeData(mParseState, idx));
            }
            throw new RuntimeException("not a float!");
        }

        public String getIdAttribute() {
            int id = nativeGetIdAttribute(mParseState);
            return id >= 0 ? mStrings.get(id).toString() : null;
        }
        public String getClassAttribute() {
            int id = nativeGetClassAttribute(mParseState);
            return id >= 0 ? mStrings.get(id).toString() : null;
        }

        public int getIdAttributeResourceValue(int defaultValue) {
            //todo: create and use native method
            return getAttributeResourceValue(null, "id", defaultValue);
        }

        public int getStyleAttribute() {
            return nativeGetStyleAttribute(mParseState);
        }

        public void close() {
            synchronized (mBlock) {
                if (mParseState != 0) {
                    nativeDestroyParseState(mParseState);
                    mParseState = 0;
                    mBlock.decOpenCountLocked();
                }
            }
        }
        
        protected void finalize() throws Throwable {
            close();
        }

        /*package*/ final CharSequence getPooledString(int id) {
            return mStrings.get(id);
        }

        /*package*/ int mParseState;
        private final XmlBlock mBlock;
        private boolean mStarted = false;
        private boolean mDecNextDepth = false;
        private int mDepth = 0;
        private int mEventType = START_DOCUMENT;
    }

    protected void finalize() throws Throwable {
        close();
    }

    /**
     * Create from an existing xml block native object.  This is
     * -extremely- dangerous -- only use it if you absolutely know what you
     *  are doing!  The given native object must exist for the entire lifetime
     *  of this newly creating XmlBlock.
     */
    XmlBlock(AssetManager assets, int xmlBlock) {
        mAssets = assets;
        mNative = xmlBlock;
        mStrings = new StringBlock(nativeGetStringBlock(xmlBlock), false);
    }

    private final AssetManager mAssets;
    private final int mNative;
    /*package*/ final StringBlock mStrings;
    private boolean mOpen = true;
    private int mOpenCount = 1;

    private static final native int nativeCreate(byte[] data,
                                                 int offset,
                                                 int size);
    private static final native int nativeGetStringBlock(int obj);

    private static final native int nativeCreateParseState(int obj);
    /*package*/ static final native int nativeNext(int state);
    private static final native int nativeGetNamespace(int state);
    /*package*/ static final native int nativeGetName(int state);
    private static final native int nativeGetText(int state);
    private static final native int nativeGetLineNumber(int state);
    private static final native int nativeGetAttributeCount(int state);
    private static final native int nativeGetAttributeNamespace(int state, int idx);
    private static final native int nativeGetAttributeName(int state, int idx);
    private static final native int nativeGetAttributeResource(int state, int idx);
    private static final native int nativeGetAttributeDataType(int state, int idx);
    private static final native int nativeGetAttributeData(int state, int idx);
    private static final native int nativeGetAttributeStringValue(int state, int idx);
    private static final native int nativeGetIdAttribute(int state);
    private static final native int nativeGetClassAttribute(int state);
    private static final native int nativeGetStyleAttribute(int state);
    private static final native int nativeGetAttributeIndex(int state, String namespace, String name);
    private static final native void nativeDestroyParseState(int state);

    private static final native void nativeDestroy(int obj);
}
