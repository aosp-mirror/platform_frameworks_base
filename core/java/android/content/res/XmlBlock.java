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

import static android.content.res.Resources.ID_NULL;
import static android.system.OsConstants.EINVAL;

import android.annotation.AnyRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.ravenwood.annotation.RavenwoodClassLoadHook;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.TypedValue;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Wrapper around a compiled XML file.
 * 
 * {@hide}
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
@RavenwoodKeepWholeClass
@RavenwoodClassLoadHook(RavenwoodClassLoadHook.LIBANDROID_LOADING_HOOK)
public final class XmlBlock implements AutoCloseable {
    private static final boolean DEBUG=false;

    @UnsupportedAppUsage
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

    @Override
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
            mStrings.close();
            nativeDestroy(mNative);
            mNative = 0;
            if (mAssets != null) {
                mAssets.xmlBlockGone(hashCode());
            }
        }
    }

    @UnsupportedAppUsage
    public XmlResourceParser newParser() {
        return newParser(ID_NULL);
    }

    public XmlResourceParser newParser(@AnyRes int resId) {
        synchronized (this) {
            if (mNative != 0) {
                return new Parser(nativeCreateParseState(mNative, resId), this);
            }
            return null;
        }
    }

    /**
     * Returns a XmlResourceParser that validates the xml using the given validator.
     */
    public XmlResourceParser newParser(@AnyRes int resId, Validator validator) {
        synchronized (this) {
            if (mNative != 0) {
                return new Parser(nativeCreateParseState(mNative, resId), this, validator);
            }
            return null;
        }
    }

    /**
     * Reference Error.h UNEXPECTED_NULL
     */
    private static final int ERROR_NULL_DOCUMENT = Integer.MIN_VALUE + 8;
    /**
     * The reason not to ResXMLParser::BAD_DOCUMENT which is -1 is that other places use the same
     * value. Reference Error.h BAD_VALUE = -EINVAL
     */
    private static final int ERROR_BAD_DOCUMENT = -EINVAL;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public final class Parser implements XmlResourceParser {
        Validator mValidator;

        Parser(long parseState, XmlBlock block) {
            mParseState = parseState;
            mBlock = block;
            block.mOpenCount++;
        }

        Parser(long parseState, XmlBlock block, Validator validator) {
            this(parseState, block);
            mValidator = validator;
        }

        @AnyRes
        public int getSourceResId() {
            return nativeGetSourceResId(mParseState);
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
        @Nullable
        public String getText() {
            int id = nativeGetText(mParseState);
            return id >= 0 ? getSequenceString(mStrings.getSequence(id)) : null;
        }
        public int getLineNumber() {
            final int lineNumber = nativeGetLineNumber(mParseState);
            if (lineNumber == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            return lineNumber;
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
        @Nullable
        public String getNamespace() {
            int id = nativeGetNamespace(mParseState);
            return id >= 0 ? getSequenceString(mStrings.getSequence(id)) : "";
        }
        @Nullable
        public String getName() {
            int id = nativeGetName(mParseState);
            return id >= 0 ? getSequenceString(mStrings.getSequence(id)) : null;
        }
        @NonNull
        public String getAttributeNamespace(int index) {
            final int id = nativeGetAttributeNamespace(mParseState, index);
            if (id == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            if (DEBUG) System.out.println("getAttributeNamespace of " + index + " = " + id);
            if (id >= 0) return getSequenceString(mStrings.getSequence(id));
            else if (id == -1) return "";
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }
        @NonNull
        public String getAttributeName(int index) {
            final int id = nativeGetAttributeName(mParseState, index);
            if (DEBUG) System.out.println("getAttributeName of " + index + " = " + id);
            if (id == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            if (id >= 0) return getSequenceString(mStrings.getSequence(id));
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
            if (mEventType == START_TAG) {
                final int count = nativeGetAttributeCount(mParseState);
                if (count == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return count;
            } else {
                return -1;
            }
        }
        @NonNull
        public String getAttributeValue(int index) {
            final int id = nativeGetAttributeStringValue(mParseState, index);
            if (id == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            if (DEBUG) System.out.println("getAttributeValue of " + index + " = " + id);
            if (id >= 0) return getSequenceString(mStrings.getSequence(id));

            // May be some other type...  check and try to convert if so.
            final int t = nativeGetAttributeDataType(mParseState, index);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            if (t == TypedValue.TYPE_NULL) {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }

            final int v = nativeGetAttributeData(mParseState, index);
            if (v == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
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
                String value = getAttributeValue(idx);
                if (mValidator != null) {
                    mValidator.validateStrAttr(this, name, value);
                }
                return value;
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
            if (ev == ERROR_BAD_DOCUMENT) {
                throw new XmlPullParserException("Corrupt XML binary file");
            }
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
            if (mValidator != null) {
                mValidator.validate(this);
            }
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
            final int resourceNameId = nativeGetAttributeResource(mParseState, index);
            if (resourceNameId == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            return resourceNameId;
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
            final int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            final int v = nativeGetAttributeData(mParseState, idx);
            if (v == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            if (t == TypedValue.TYPE_STRING) {
                return XmlUtils.convertValueToList(
                    mStrings.getSequence(v), options, defaultValue);
            }
            return v;
        }
        public boolean getAttributeBooleanValue(int idx,
                boolean defaultValue) {
            final int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            // Note: don't attempt to convert any other types, because
            // we want to count on aapt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT && t <= TypedValue.TYPE_LAST_INT) {
                final int v = nativeGetAttributeData(mParseState, idx);
                if (v == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return v != 0;
            }
            return defaultValue;
        }
        public int getAttributeResourceValue(int idx, int defaultValue) {
            final int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            // Note: don't attempt to convert any other types, because
            // we want to count on aapt doing the conversion for us.
            if (t == TypedValue.TYPE_REFERENCE) {
                final int v = nativeGetAttributeData(mParseState, idx);
                if (v == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return v;
            }
            return defaultValue;
        }
        public int getAttributeIntValue(int idx, int defaultValue) {
            final int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            // Note: don't attempt to convert any other types, because
            // we want to count on aapt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT && t <= TypedValue.TYPE_LAST_INT) {
                final int v = nativeGetAttributeData(mParseState, idx);
                if (v == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return v;
            }
            return defaultValue;
        }
        public int getAttributeUnsignedIntValue(int idx, int defaultValue) {
            int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            // Note: don't attempt to convert any other types, because
            // we want to count on aapt doing the conversion for us.
            if (t >= TypedValue.TYPE_FIRST_INT && t <= TypedValue.TYPE_LAST_INT) {
                final int v = nativeGetAttributeData(mParseState, idx);
                if (v == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return v;
            }
            return defaultValue;
        }
        public float getAttributeFloatValue(int idx, float defaultValue) {
            final int t = nativeGetAttributeDataType(mParseState, idx);
            if (t == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            // Note: don't attempt to convert any other types, because
            // we want to count on aapt doing the conversion for us.
            if (t == TypedValue.TYPE_FLOAT) {
                final int v = nativeGetAttributeData(mParseState, idx);
                if (v == ERROR_NULL_DOCUMENT) {
                    throw new NullPointerException("Null document");
                }
                return Float.intBitsToFloat(v);
            }
            throw new RuntimeException("not a float!");
        }
        @Nullable
        public String getIdAttribute() {
            final int id = nativeGetIdAttribute(mParseState);
            if (id == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            return id >= 0 ? getSequenceString(mStrings.getSequence(id)) : null;
        }
        @Nullable
        public String getClassAttribute() {
            final int id = nativeGetClassAttribute(mParseState);
            if (id == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            return id >= 0 ? getSequenceString(mStrings.getSequence(id)) : null;
        }

        public int getIdAttributeResourceValue(int defaultValue) {
            //todo: create and use native method
            return getAttributeResourceValue(null, "id", defaultValue);
        }

        public int getStyleAttribute() {
            final int styleAttributeId = nativeGetStyleAttribute(mParseState);
            if (styleAttributeId == ERROR_NULL_DOCUMENT) {
                throw new NullPointerException("Null document");
            }
            return styleAttributeId;
        }

        private String getSequenceString(@Nullable CharSequence str) {
            if (str == null) {
                // A value of null retrieved from a StringPool indicates that retrieval of the
                // string failed due to incremental installation. The presence of all the XmlBlock
                // data is verified when it is created, so this exception must not be possible.
                throw new IllegalStateException("Retrieving a string from the StringPool of an"
                        + " XmlBlock should never fail");
            }
            return str.toString();
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

        @Nullable
        /*package*/ final CharSequence getPooledString(int id) {
            return mStrings.getSequence(id);
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /*package*/ long mParseState;
        @UnsupportedAppUsage
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
    XmlBlock(@Nullable AssetManager assets, long xmlBlock) {
        mAssets = assets;
        mNative = xmlBlock;
        mStrings = new StringBlock(nativeGetStringBlock(xmlBlock), false);
    }

    private @Nullable final AssetManager mAssets;
    private long mNative;   // final, but gets reset on close
    /*package*/ final StringBlock mStrings;
    private boolean mOpen = true;
    private int mOpenCount = 1;

    private static final native long nativeCreate(byte[] data,
                                                 int offset,
                                                 int size);
    private static final native long nativeGetStringBlock(long obj);
    private static final native long nativeCreateParseState(long obj, int resId);
    private static final native void nativeDestroyParseState(long state);
    private static final native void nativeDestroy(long obj);

    // ----------- @FastNative ------------------

    @FastNative
    private static native int nativeGetAttributeIndex(
            long state, String namespace, String name);

    // ----------- @CriticalNative ------------------
    @CriticalNative
    /*package*/ static final native int nativeNext(long state);

    @CriticalNative
    private static final native int nativeGetNamespace(long state);

    @CriticalNative
    /*package*/ static final native int nativeGetName(long state);

    @CriticalNative
    private static final native int nativeGetText(long state);

    @CriticalNative
    private static final native int nativeGetLineNumber(long state);

    @CriticalNative
    private static final native int nativeGetAttributeCount(long state);

    @CriticalNative
    private static final native int nativeGetAttributeNamespace(long state, int idx);

    @CriticalNative
    private static final native int nativeGetAttributeName(long state, int idx);

    @CriticalNative
    private static final native int nativeGetAttributeResource(long state, int idx);

    @CriticalNative
    private static final native int nativeGetAttributeDataType(long state, int idx);

    @CriticalNative
    private static final native int nativeGetAttributeData(long state, int idx);

    @CriticalNative
    private static final native int nativeGetAttributeStringValue(long state, int idx);

    @CriticalNative
    private static final native int nativeGetIdAttribute(long state);

    @CriticalNative
    private static final native int nativeGetClassAttribute(long state);

    @CriticalNative
    private static final native int nativeGetStyleAttribute(long state);

    @CriticalNative
    private static final native int nativeGetSourceResId(long state);
}
