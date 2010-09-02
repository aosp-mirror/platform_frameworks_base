/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.pim.vcard.exception.VCardAgentNotSupportedException;
import android.pim.vcard.exception.VCardException;
import android.pim.vcard.exception.VCardInvalidCommentLineException;
import android.pim.vcard.exception.VCardInvalidLineException;
import android.pim.vcard.exception.VCardNestedException;
import android.pim.vcard.exception.VCardVersionException;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * Basic implementation achieving vCard parsing. Based on vCard 2.1,
 * </p>
 * @hide
 */
/* package */ class VCardParserImpl_V21 {
    private static final String LOG_TAG = "VCardParserImpl_V21";

    private static final class EmptyInterpreter implements VCardInterpreter {
        @Override
        public void end() {
        }
        @Override
        public void endEntry() {
        }
        @Override
        public void endProperty() {
        }
        @Override
        public void propertyGroup(String group) {
        }
        @Override
        public void propertyName(String name) {
        }
        @Override
        public void propertyParamType(String type) {
        }
        @Override
        public void propertyParamValue(String value) {
        }
        @Override
        public void propertyValues(List<String> values) {
        }
        @Override
        public void start() {
        }
        @Override
        public void startEntry() {
        }
        @Override
        public void startProperty() {
        }
    }

    protected static final class CustomBufferedReader extends BufferedReader {
        private long mTime;

        /**
         * Needed since "next line" may be null due to end of line.
         */
        private boolean mNextLineIsValid;
        private String mNextLine;

        public CustomBufferedReader(Reader in) {
            super(in);
        }

        @Override
        public String readLine() throws IOException {
            if (mNextLineIsValid) {
                final String ret = mNextLine;
                mNextLine = null;
                mNextLineIsValid = false;
                return ret;
            }

            long start = System.currentTimeMillis();
            final String line = super.readLine();
            long end = System.currentTimeMillis();
            mTime += end - start;
            return line;
        }

        /**
         * Read one line, but make this object store it in its queue.
         */
        public String peekLine() throws IOException {
            if (!mNextLineIsValid) {
                long start = System.currentTimeMillis();
                final String line = super.readLine();
                long end = System.currentTimeMillis();
                mTime += end - start;

                mNextLine = line;
                mNextLineIsValid = true;
            }

            return mNextLine;
        }

        public long getTotalmillisecond() {
            return mTime;
        }
    }

    private static final String DEFAULT_ENCODING = "8BIT";

    protected boolean mCanceled;
    protected VCardInterpreter mInterpreter;

    protected final String mIntermediateCharset;

    /**
     * <p>
     * The encoding type for deconding byte streams. This member variable is
     * reset to a default encoding every time when a new item comes.
     * </p>
     * <p>
     * "Encoding" in vCard is different from "Charset". It is mainly used for
     * addresses, notes, images. "7BIT", "8BIT", "BASE64", and
     * "QUOTED-PRINTABLE" are known examples.
     * </p>
     */
    protected String mCurrentEncoding;

    /**
     * <p>
     * The reader object to be used internally.
     * </p>
     * <p>
     * Developers should not directly read a line from this object. Use
     * getLine() unless there some reason.
     * </p>
     */
    protected CustomBufferedReader mReader;

    /**
     * <p>
     * Set for storing unkonwn TYPE attributes, which is not acceptable in vCard
     * specification, but happens to be seen in real world vCard.
     * </p>
     */
    protected final Set<String> mUnknownTypeSet = new HashSet<String>();

    /**
     * <p>
     * Set for storing unkonwn VALUE attributes, which is not acceptable in
     * vCard specification, but happens to be seen in real world vCard.
     * </p>
     */
    protected final Set<String> mUnknownValueSet = new HashSet<String>();


    // In some cases, vCard is nested. Currently, we only consider the most
    // interior vCard data.
    // See v21_foma_1.vcf in test directory for more information.
    // TODO: Don't ignore by using count, but read all of information outside vCard.
    private int mNestCount;

    // Used only for parsing END:VCARD.
    private String mPreviousLine;

    // For measuring performance.
    private long mTimeTotal;
    private long mTimeReadStartRecord;
    private long mTimeReadEndRecord;
    private long mTimeStartProperty;
    private long mTimeEndProperty;
    private long mTimeParseItems;
    private long mTimeParseLineAndHandleGroup;
    private long mTimeParsePropertyValues;
    private long mTimeParseAdrOrgN;
    private long mTimeHandleMiscPropertyValue;
    private long mTimeHandleQuotedPrintable;
    private long mTimeHandleBase64;

    public VCardParserImpl_V21() {
        this(VCardConfig.VCARD_TYPE_DEFAULT);
    }

    public VCardParserImpl_V21(int vcardType) {
        if ((vcardType & VCardConfig.FLAG_TORELATE_NEST) != 0) {
            mNestCount = 1;
        }

        mIntermediateCharset =  VCardConfig.DEFAULT_INTERMEDIATE_CHARSET;
    }

    /**
     * <p>
     * Parses the file at the given position.
     * </p>
     */
    // <pre class="prettyprint">vcard_file = [wsls] vcard [wsls]</pre>
    protected void parseVCardFile() throws IOException, VCardException {
        boolean readingFirstFile = true;
        while (true) {
            if (mCanceled) {
                break;
            }
            if (!parseOneVCard(readingFirstFile)) {
                break;
            }
            readingFirstFile = false;
        }

        if (mNestCount > 0) {
            boolean useCache = true;
            for (int i = 0; i < mNestCount; i++) {
                readEndVCard(useCache, true);
                useCache = false;
            }
        }
    }

    /**
     * @return true when a given property name is a valid property name.
     */
    protected boolean isValidPropertyName(final String propertyName) {
        if (!(getKnownPropertyNameSet().contains(propertyName.toUpperCase()) ||
                propertyName.startsWith("X-"))
                && !mUnknownTypeSet.contains(propertyName)) {
            mUnknownTypeSet.add(propertyName);
            Log.w(LOG_TAG, "Property name unsupported by vCard 2.1: " + propertyName);
        }
        return true;
    }

    /**
     * @return String. It may be null, or its length may be 0
     * @throws IOException
     */
    protected String getLine() throws IOException {
        return mReader.readLine();
    }

    protected String peekLine() throws IOException {
        return mReader.peekLine();
    }

    /**
     * @return String with it's length > 0
     * @throws IOException
     * @throws VCardException when the stream reached end of line
     */
    protected String getNonEmptyLine() throws IOException, VCardException {
        String line;
        while (true) {
            line = getLine();
            if (line == null) {
                throw new VCardException("Reached end of buffer.");
            } else if (line.trim().length() > 0) {
                return line;
            }
        }
    }

    /*
     * vcard = "BEGIN" [ws] ":" [ws] "VCARD" [ws] 1*CRLF
     *         items *CRLF
     *         "END" [ws] ":" [ws] "VCARD"
     */
    private boolean parseOneVCard(boolean firstRead) throws IOException, VCardException {
        boolean allowGarbage = false;
        if (firstRead) {
            if (mNestCount > 0) {
                for (int i = 0; i < mNestCount; i++) {
                    if (!readBeginVCard(allowGarbage)) {
                        return false;
                    }
                    allowGarbage = true;
                }
            }
        }

        if (!readBeginVCard(allowGarbage)) {
            return false;
        }
        final long beforeStartEntry = System.currentTimeMillis();
        mInterpreter.startEntry();
        mTimeReadStartRecord += System.currentTimeMillis() - beforeStartEntry;

        final long beforeParseItems = System.currentTimeMillis();
        parseItems();
        mTimeParseItems += System.currentTimeMillis() - beforeParseItems;

        readEndVCard(true, false);

        final long beforeEndEntry = System.currentTimeMillis();
        mInterpreter.endEntry();
        mTimeReadEndRecord += System.currentTimeMillis() - beforeEndEntry;
        return true;
    }

    /**
     * @return True when successful. False when reaching the end of line
     * @throws IOException
     * @throws VCardException
     */
    protected boolean readBeginVCard(boolean allowGarbage) throws IOException, VCardException {
        String line;
        do {
            while (true) {
                line = getLine();
                if (line == null) {
                    return false;
                } else if (line.trim().length() > 0) {
                    break;
                }
            }
            final String[] strArray = line.split(":", 2);
            final int length = strArray.length;

            // Although vCard 2.1/3.0 specification does not allow lower cases,
            // we found vCard file emitted by some external vCard expoter have such
            // invalid Strings.
            // So we allow it.
            // e.g.
            // BEGIN:vCard
            if (length == 2 && strArray[0].trim().equalsIgnoreCase("BEGIN")
                    && strArray[1].trim().equalsIgnoreCase("VCARD")) {
                return true;
            } else if (!allowGarbage) {
                if (mNestCount > 0) {
                    mPreviousLine = line;
                    return false;
                } else {
                    throw new VCardException("Expected String \"BEGIN:VCARD\" did not come "
                            + "(Instead, \"" + line + "\" came)");
                }
            }
        } while (allowGarbage);

        throw new VCardException("Reached where must not be reached.");
    }

    /**
     * <p>
     * The arguments useCache and allowGarbase are usually true and false
     * accordingly when this function is called outside this function itself.
     * </p>
     * 
     * @param useCache When true, line is obtained from mPreviousline.
     *            Otherwise, getLine() is used.
     * @param allowGarbage When true, ignore non "END:VCARD" line.
     * @throws IOException
     * @throws VCardException
     */
    protected void readEndVCard(boolean useCache, boolean allowGarbage) throws IOException,
            VCardException {
        String line;
        do {
            if (useCache) {
                // Though vCard specification does not allow lower cases,
                // some data may have them, so we allow it.
                line = mPreviousLine;
            } else {
                while (true) {
                    line = getLine();
                    if (line == null) {
                        throw new VCardException("Expected END:VCARD was not found.");
                    } else if (line.trim().length() > 0) {
                        break;
                    }
                }
            }

            String[] strArray = line.split(":", 2);
            if (strArray.length == 2 && strArray[0].trim().equalsIgnoreCase("END")
                    && strArray[1].trim().equalsIgnoreCase("VCARD")) {
                return;
            } else if (!allowGarbage) {
                throw new VCardException("END:VCARD != \"" + mPreviousLine + "\"");
            }
            useCache = false;
        } while (allowGarbage);
    }

    /*
     * items = *CRLF item / item
     */
    protected void parseItems() throws IOException, VCardException {
        boolean ended = false;

        final long beforeBeginProperty = System.currentTimeMillis();
        mInterpreter.startProperty();
        mTimeStartProperty += System.currentTimeMillis() - beforeBeginProperty;
        ended = parseItem();
        if (!ended) {
            final long beforeEndProperty = System.currentTimeMillis();
            mInterpreter.endProperty();
            mTimeEndProperty += System.currentTimeMillis() - beforeEndProperty;
        }

        while (!ended) {
            final long beforeStartProperty = System.currentTimeMillis();
            mInterpreter.startProperty();
            mTimeStartProperty += System.currentTimeMillis() - beforeStartProperty;
            try {
                ended = parseItem();
            } catch (VCardInvalidCommentLineException e) {
                Log.e(LOG_TAG, "Invalid line which looks like some comment was found. Ignored.");
                ended = false;
            }

            if (!ended) {
                final long beforeEndProperty = System.currentTimeMillis();
                mInterpreter.endProperty();
                mTimeEndProperty += System.currentTimeMillis() - beforeEndProperty;
            }
        }
    }

    /*
     * item = [groups "."] name [params] ":" value CRLF / [groups "."] "ADR"
     * [params] ":" addressparts CRLF / [groups "."] "ORG" [params] ":" orgparts
     * CRLF / [groups "."] "N" [params] ":" nameparts CRLF / [groups "."]
     * "AGENT" [params] ":" vcard CRLF
     */
    protected boolean parseItem() throws IOException, VCardException {
        mCurrentEncoding = DEFAULT_ENCODING;

        final String line = getNonEmptyLine();
        long start = System.currentTimeMillis();

        String[] propertyNameAndValue = separateLineAndHandleGroup(line);
        if (propertyNameAndValue == null) {
            return true;
        }
        if (propertyNameAndValue.length != 2) {
            throw new VCardInvalidLineException("Invalid line \"" + line + "\"");
        }
        String propertyName = propertyNameAndValue[0].toUpperCase();
        String propertyValue = propertyNameAndValue[1];

        mTimeParseLineAndHandleGroup += System.currentTimeMillis() - start;

        if (propertyName.equals("ADR") || propertyName.equals("ORG") || propertyName.equals("N")) {
            start = System.currentTimeMillis();
            handleMultiplePropertyValue(propertyName, propertyValue);
            mTimeParseAdrOrgN += System.currentTimeMillis() - start;
            return false;
        } else if (propertyName.equals("AGENT")) {
            handleAgent(propertyValue);
            return false;
        } else if (isValidPropertyName(propertyName)) {
            if (propertyName.equals("BEGIN")) {
                if (propertyValue.equals("VCARD")) {
                    throw new VCardNestedException("This vCard has nested vCard data in it.");
                } else {
                    throw new VCardException("Unknown BEGIN type: " + propertyValue);
                }
            } else if (propertyName.equals("VERSION") && !propertyValue.equals(getVersionString())) {
                throw new VCardVersionException("Incompatible version: " + propertyValue + " != "
                        + getVersionString());
            }
            start = System.currentTimeMillis();
            handlePropertyValue(propertyName, propertyValue);
            mTimeParsePropertyValues += System.currentTimeMillis() - start;
            return false;
        }

        throw new VCardException("Unknown property name: \"" + propertyName + "\"");
    }

    // For performance reason, the states for group and property name are merged into one.
    static private final int STATE_GROUP_OR_PROPERTY_NAME = 0;
    static private final int STATE_PARAMS = 1;
    // vCard 3.0 specification allows double-quoted parameters, while vCard 2.1 does not.
    static private final int STATE_PARAMS_IN_DQUOTE = 2;

    protected String[] separateLineAndHandleGroup(String line) throws VCardException {
        final String[] propertyNameAndValue = new String[2];
        final int length = line.length();
        if (length > 0 && line.charAt(0) == '#') {
            throw new VCardInvalidCommentLineException();
        }

        int state = STATE_GROUP_OR_PROPERTY_NAME;
        int nameIndex = 0;

        // This loop is developed so that we don't have to take care of bottle neck here.
        // Refactor carefully when you need to do so.
        for (int i = 0; i < length; i++) {
            final char ch = line.charAt(i);
            switch (state) {
                case STATE_GROUP_OR_PROPERTY_NAME: {
                    if (ch == ':') {  // End of a property name.
                        final String propertyName = line.substring(nameIndex, i);
                        if (propertyName.equalsIgnoreCase("END")) {
                            mPreviousLine = line;
                            return null;
                        }
                        mInterpreter.propertyName(propertyName);
                        propertyNameAndValue[0] = propertyName;
                        if (i < length - 1) {
                            propertyNameAndValue[1] = line.substring(i + 1);
                        } else {
                            propertyNameAndValue[1] = "";
                        }
                        return propertyNameAndValue;
                    } else if (ch == '.') {  // Each group is followed by the dot.
                        final String groupName = line.substring(nameIndex, i);
                        if (groupName.length() == 0) {
                            Log.w(LOG_TAG, "Empty group found. Ignoring.");
                        } else {
                            mInterpreter.propertyGroup(groupName);
                        }
                        nameIndex = i + 1;  // Next should be another group or a property name.
                    } else if (ch == ';') {  // End of property name and beginneng of parameters.  
                        final String propertyName = line.substring(nameIndex, i);
                        if (propertyName.equalsIgnoreCase("END")) {
                            mPreviousLine = line;
                            return null;
                        }
                        mInterpreter.propertyName(propertyName);
                        propertyNameAndValue[0] = propertyName;
                        nameIndex = i + 1;
                        state = STATE_PARAMS;  // Start parameter parsing.
                    }
                    // TODO: comma support (in vCard 3.0 and 4.0).
                    break;
                }
                case STATE_PARAMS: {
                    if (ch == '"') {
                        if (VCardConstants.VERSION_V21.equalsIgnoreCase(getVersionString())) {
                            Log.w(LOG_TAG, "Double-quoted params found in vCard 2.1. " +
                                    "Silently allow it");
                        }
                        state = STATE_PARAMS_IN_DQUOTE;
                    } else if (ch == ';') {  // Starts another param.
                        handleParams(line.substring(nameIndex, i));
                        nameIndex = i + 1;
                    } else if (ch == ':') {  // End of param and beginenning of values.
                        handleParams(line.substring(nameIndex, i));
                        if (i < length - 1) {
                            propertyNameAndValue[1] = line.substring(i + 1);
                        } else {
                            propertyNameAndValue[1] = "";
                        }
                        return propertyNameAndValue;
                    }
                    break;
                }
                case STATE_PARAMS_IN_DQUOTE: {
                    if (ch == '"') {
                        if (VCardConstants.VERSION_V21.equalsIgnoreCase(getVersionString())) {
                            Log.w(LOG_TAG, "Double-quoted params found in vCard 2.1. " +
                                    "Silently allow it");
                        }
                        state = STATE_PARAMS;
                    }
                    break;
                }
            }
        }

        throw new VCardInvalidLineException("Invalid line: \"" + line + "\"");
    }

    /*
     * params = ";" [ws] paramlist paramlist = paramlist [ws] ";" [ws] param /
     * param param = "TYPE" [ws] "=" [ws] ptypeval / "VALUE" [ws] "=" [ws]
     * pvalueval / "ENCODING" [ws] "=" [ws] pencodingval / "CHARSET" [ws] "="
     * [ws] charsetval / "LANGUAGE" [ws] "=" [ws] langval / "X-" word [ws] "="
     * [ws] word / knowntype
     */
    protected void handleParams(String params) throws VCardException {
        final String[] strArray = params.split("=", 2);
        if (strArray.length == 2) {
            final String paramName = strArray[0].trim().toUpperCase();
            String paramValue = strArray[1].trim();
            if (paramName.equals("TYPE")) {
                handleType(paramValue);
            } else if (paramName.equals("VALUE")) {
                handleValue(paramValue);
            } else if (paramName.equals("ENCODING")) {
                handleEncoding(paramValue);
            } else if (paramName.equals("CHARSET")) {
                handleCharset(paramValue);
            } else if (paramName.equals("LANGUAGE")) {
                handleLanguage(paramValue);
            } else if (paramName.startsWith("X-")) {
                handleAnyParam(paramName, paramValue);
            } else {
                throw new VCardException("Unknown type \"" + paramName + "\"");
            }
        } else {
            handleParamWithoutName(strArray[0]);
        }
    }

    /**
     * vCard 3.0 parser implementation may throw VCardException.
     */
    @SuppressWarnings("unused")
    protected void handleParamWithoutName(final String paramValue) throws VCardException {
        handleType(paramValue);
    }

    /*
     * ptypeval = knowntype / "X-" word
     */
    protected void handleType(final String ptypeval) {
        if (!(getKnownTypeSet().contains(ptypeval.toUpperCase())
                || ptypeval.startsWith("X-"))
                && !mUnknownTypeSet.contains(ptypeval)) {
            mUnknownTypeSet.add(ptypeval);
            Log.w(LOG_TAG, String.format("TYPE unsupported by %s: ", getVersion(), ptypeval));
        }
        mInterpreter.propertyParamType("TYPE");
        mInterpreter.propertyParamValue(ptypeval);
    }

    /*
     * pvalueval = "INLINE" / "URL" / "CONTENT-ID" / "CID" / "X-" word
     */
    protected void handleValue(final String pvalueval) {
        if (!(getKnownValueSet().contains(pvalueval.toUpperCase())
                || pvalueval.startsWith("X-")
                || mUnknownValueSet.contains(pvalueval))) {
            mUnknownValueSet.add(pvalueval);
            Log.w(LOG_TAG, String.format(
                    "The value unsupported by TYPE of %s: ", getVersion(), pvalueval));
        }
        mInterpreter.propertyParamType("VALUE");
        mInterpreter.propertyParamValue(pvalueval);
    }

    /*
     * pencodingval = "7BIT" / "8BIT" / "QUOTED-PRINTABLE" / "BASE64" / "X-" word
     */
    protected void handleEncoding(String pencodingval) throws VCardException {
        if (getAvailableEncodingSet().contains(pencodingval) ||
                pencodingval.startsWith("X-")) {
            mInterpreter.propertyParamType("ENCODING");
            mInterpreter.propertyParamValue(pencodingval);
            mCurrentEncoding = pencodingval;
        } else {
            throw new VCardException("Unknown encoding \"" + pencodingval + "\"");
        }
    }

    /**
     * <p>
     * vCard 2.1 specification only allows us-ascii and iso-8859-xxx (See RFC 1521),
     * but recent vCard files often contain other charset like UTF-8, SHIFT_JIS, etc.
     * We allow any charset.
     * </p>
     */
    protected void handleCharset(String charsetval) {
        mInterpreter.propertyParamType("CHARSET");
        mInterpreter.propertyParamValue(charsetval);
    }

    /**
     * See also Section 7.1 of RFC 1521
     */
    protected void handleLanguage(String langval) throws VCardException {
        String[] strArray = langval.split("-");
        if (strArray.length != 2) {
            throw new VCardException("Invalid Language: \"" + langval + "\"");
        }
        String tmp = strArray[0];
        int length = tmp.length();
        for (int i = 0; i < length; i++) {
            if (!isAsciiLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        tmp = strArray[1];
        length = tmp.length();
        for (int i = 0; i < length; i++) {
            if (!isAsciiLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        mInterpreter.propertyParamType(VCardConstants.PARAM_LANGUAGE);
        mInterpreter.propertyParamValue(langval);
    }

    private boolean isAsciiLetter(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
            return true;
        }
        return false;
    }

    /**
     * Mainly for "X-" type. This accepts any kind of type without check.
     */
    protected void handleAnyParam(String paramName, String paramValue) {
        mInterpreter.propertyParamType(paramName);
        mInterpreter.propertyParamValue(paramValue);
    }

    protected void handlePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        final String upperEncoding = mCurrentEncoding.toUpperCase();
        if (upperEncoding.equals(VCardConstants.PARAM_ENCODING_QP)) {
            final long start = System.currentTimeMillis();
            final String result = getQuotedPrintable(propertyValue);
            final ArrayList<String> v = new ArrayList<String>();
            v.add(result);
            mInterpreter.propertyValues(v);
            mTimeHandleQuotedPrintable += System.currentTimeMillis() - start;
        } else if (upperEncoding.equals(VCardConstants.PARAM_ENCODING_BASE64)
                || upperEncoding.equals(VCardConstants.PARAM_ENCODING_B)) {
            final long start = System.currentTimeMillis();
            // It is very rare, but some BASE64 data may be so big that
            // OutOfMemoryError occurs. To ignore such cases, use try-catch.
            try {
                final ArrayList<String> arrayList = new ArrayList<String>();
                arrayList.add(getBase64(propertyValue));
                mInterpreter.propertyValues(arrayList);
            } catch (OutOfMemoryError error) {
                Log.e(LOG_TAG, "OutOfMemoryError happened during parsing BASE64 data!");
                mInterpreter.propertyValues(null);
            }
            mTimeHandleBase64 += System.currentTimeMillis() - start;
        } else {
            if (!(upperEncoding.equals("7BIT") || upperEncoding.equals("8BIT") ||
                    upperEncoding.startsWith("X-"))) {
                Log.w(LOG_TAG,
                        String.format("The encoding \"%s\" is unsupported by vCard %s",
                                mCurrentEncoding, getVersionString()));
            }

            // Some device uses line folding defined in RFC 2425, which is not allowed
            // in vCard 2.1 (while needed in vCard 3.0).
            //
            // e.g.
            // BEGIN:VCARD
            // VERSION:2.1
            // N:;Omega;;;
            // EMAIL;INTERNET:"Omega"
            //   <omega@example.com>
            // FN:Omega
            // END:VCARD
            //
            // The vCard above assumes that email address should become:
            // "Omega" <omega@example.com>
            //
            // But vCard 2.1 requires Quote-Printable when a line contains line break(s).
            //
            // For more information about line folding,
            // see "5.8.1. Line delimiting and folding" in RFC 2425.
            //
            // We take care of this case more formally in vCard 3.0, so we only need to
            // do this in vCard 2.1.
            if (getVersion() == VCardConfig.VERSION_21) {
                StringBuilder builder = null;
                while (true) {
                    final String nextLine = peekLine();
                    // We don't need to care too much about this exceptional case,
                    // but we should not wrongly eat up "END:VCARD", since it critically
                    // breaks this parser's state machine.
                    // Thus we roughly look over the next line and confirm it is at least not
                    // "END:VCARD". This extra fee is worth paying. This is exceptional
                    // anyway.
                    if (!TextUtils.isEmpty(nextLine) &&
                            nextLine.charAt(0) == ' ' &&
                            !"END:VCARD".contains(nextLine.toUpperCase())) {
                        getLine();  // Drop the next line.

                        if (builder == null) {
                            builder = new StringBuilder();
                            builder.append(propertyValue);
                        }
                        builder.append(nextLine.substring(1));
                    } else {
                        break;
                    }
                }
                if (builder != null) {
                    propertyValue = builder.toString();
                }
            }

            final long start = System.currentTimeMillis();
            ArrayList<String> v = new ArrayList<String>();
            v.add(maybeUnescapeText(propertyValue));
            mInterpreter.propertyValues(v);
            mTimeHandleMiscPropertyValue += System.currentTimeMillis() - start;
        }
    }

    /**
     * <p>
     * Parses and returns Quoted-Printable.
     * </p>
     *
     * @param firstString The string following a parameter name and attributes.
     *            Example: "string" in
     *            "ADR:ENCODING=QUOTED-PRINTABLE:string\n\r".
     * @return whole Quoted-Printable string, including a given argument and
     *         following lines. Excludes the last empty line following to Quoted
     *         Printable lines.
     * @throws IOException
     * @throws VCardException
     */
    private String getQuotedPrintable(String firstString) throws IOException, VCardException {
        // Specifically, there may be some padding between = and CRLF.
        // See the following:
        //
        // qp-line := *(qp-segment transport-padding CRLF)
        // qp-part transport-padding
        // qp-segment := qp-section *(SPACE / TAB) "="
        // ; Maximum length of 76 characters
        //
        // e.g. (from RFC 2045)
        // Now's the time =
        // for all folk to come=
        // to the aid of their country.
        if (firstString.trim().endsWith("=")) {
            // remove "transport-padding"
            int pos = firstString.length() - 1;
            while (firstString.charAt(pos) != '=') {
            }
            StringBuilder builder = new StringBuilder();
            builder.append(firstString.substring(0, pos + 1));
            builder.append("\r\n");
            String line;
            while (true) {
                line = getLine();
                if (line == null) {
                    throw new VCardException("File ended during parsing a Quoted-Printable String");
                }
                if (line.trim().endsWith("=")) {
                    // remove "transport-padding"
                    pos = line.length() - 1;
                    while (line.charAt(pos) != '=') {
                    }
                    builder.append(line.substring(0, pos + 1));
                    builder.append("\r\n");
                } else {
                    builder.append(line);
                    break;
                }
            }
            return builder.toString();
        } else {
            return firstString;
        }
    }

    protected String getBase64(String firstString) throws IOException, VCardException {
        StringBuilder builder = new StringBuilder();
        builder.append(firstString);

        while (true) {
            String line = getLine();
            if (line == null) {
                throw new VCardException("File ended during parsing BASE64 binary");
            }
            if (line.length() == 0) {
                break;
            }
            builder.append(line);
        }

        return builder.toString();
    }

    /**
     * <p>
     * Mainly for "ADR", "ORG", and "N"
     * </p>
     */
    /*
     * addressparts = 0*6(strnosemi ";") strnosemi ; PO Box, Extended Addr,
     * Street, Locality, Region, Postal Code, Country Name orgparts =
     * *(strnosemi ";") strnosemi ; First is Organization Name, remainder are
     * Organization Units. nameparts = 0*4(strnosemi ";") strnosemi ; Family,
     * Given, Middle, Prefix, Suffix. ; Example:Public;John;Q.;Reverend Dr.;III,
     * Esq. strnosemi = *(*nonsemi ("\;" / "\" CRLF)) *nonsemi ; To include a
     * semicolon in this string, it must be escaped ; with a "\" character. We
     * do not care the number of "strnosemi" here. We are not sure whether we
     * should add "\" CRLF to each value. We exclude them for now.
     */
    protected void handleMultiplePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        // vCard 2.1 does not allow QUOTED-PRINTABLE here, but some
        // softwares/devices
        // emit such data.
        if (mCurrentEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            propertyValue = getQuotedPrintable(propertyValue);
        }

        mInterpreter.propertyValues(VCardUtils.constructListFromValue(propertyValue,
                getVersion()));
    }

    /*
     * vCard 2.1 specifies AGENT allows one vcard entry. Currently we emit an
     * error toward the AGENT property.
     * // TODO: Support AGENT property.
     * item =
     * ... / [groups "."] "AGENT" [params] ":" vcard CRLF vcard = "BEGIN" [ws]
     * ":" [ws] "VCARD" [ws] 1*CRLF items *CRLF "END" [ws] ":" [ws] "VCARD"
     */
    protected void handleAgent(final String propertyValue) throws VCardException {
        if (!propertyValue.toUpperCase().contains("BEGIN:VCARD")) {
            // Apparently invalid line seen in Windows Mobile 6.5. Ignore them.
            return;
        } else {
            throw new VCardAgentNotSupportedException("AGENT Property is not supported now.");
        }
    }

    /**
     * For vCard 3.0.
     */
    protected String maybeUnescapeText(final String text) {
        return text;
    }

    /**
     * Returns unescaped String if the character should be unescaped. Return
     * null otherwise. e.g. In vCard 2.1, "\;" should be unescaped into ";"
     * while "\x" should not be.
     */
    protected String maybeUnescapeCharacter(final char ch) {
        return unescapeCharacter(ch);
    }

    /* package */ static String unescapeCharacter(final char ch) {
        // Original vCard 2.1 specification does not allow transformation
        // "\:" -> ":", "\," -> ",", and "\\" -> "\", but previous
        // implementation of
        // this class allowed them, so keep it as is.
        if (ch == '\\' || ch == ';' || ch == ':' || ch == ',') {
            return String.valueOf(ch);
        } else {
            return null;
        }
    }

    private void showPerformanceInfo() {
        Log.d(LOG_TAG, "Total parsing time:  " + mTimeTotal + " ms");
        Log.d(LOG_TAG, "Total readLine time: " + mReader.getTotalmillisecond() + " ms");
        Log.d(LOG_TAG, "Time for handling the beggining of the record: " + mTimeReadStartRecord
                + " ms");
        Log.d(LOG_TAG, "Time for handling the end of the record: " + mTimeReadEndRecord + " ms");
        Log.d(LOG_TAG, "Time for parsing line, and handling group: " + mTimeParseLineAndHandleGroup
                + " ms");
        Log.d(LOG_TAG, "Time for parsing ADR, ORG, and N fields:" + mTimeParseAdrOrgN + " ms");
        Log.d(LOG_TAG, "Time for parsing property values: " + mTimeParsePropertyValues + " ms");
        Log.d(LOG_TAG, "Time for handling normal property values: " + mTimeHandleMiscPropertyValue
                + " ms");
        Log.d(LOG_TAG, "Time for handling Quoted-Printable: " + mTimeHandleQuotedPrintable + " ms");
        Log.d(LOG_TAG, "Time for handling Base64: " + mTimeHandleBase64 + " ms");
    }

    /**
     * @return {@link VCardConfig#VERSION_21}
     */
    protected int getVersion() {
        return VCardConfig.VERSION_21;
    }

    /**
     * @return {@link VCardConfig#VERSION_30}
     */
    protected String getVersionString() {
        return VCardConstants.VERSION_V21;
    }

    protected Set<String> getKnownPropertyNameSet() {
        return VCardParser_V21.sKnownPropertyNameSet;
    }

    protected Set<String> getKnownTypeSet() {
        return VCardParser_V21.sKnownTypeSet;
    }

    protected Set<String> getKnownValueSet() {
        return VCardParser_V21.sKnownValueSet;
    }

    protected Set<String> getAvailableEncodingSet() {
        return VCardParser_V21.sAvailableEncoding;
    }

    protected String getDefaultEncoding() {
        return DEFAULT_ENCODING;
    }


    public void parse(InputStream is, VCardInterpreter interpreter)
            throws IOException, VCardException {
        if (is == null) {
            throw new NullPointerException("InputStream must not be null.");
        }

        final InputStreamReader tmpReader = new InputStreamReader(is, mIntermediateCharset);
        mReader = new CustomBufferedReader(tmpReader);

        mInterpreter = (interpreter != null ? interpreter : new EmptyInterpreter());

        final long start = System.currentTimeMillis();
        if (mInterpreter != null) {
            mInterpreter.start();
        }
        parseVCardFile();
        if (mInterpreter != null) {
            mInterpreter.end();
        }
        mTimeTotal += System.currentTimeMillis() - start;

        if (VCardConfig.showPerformanceLog()) {
            showPerformanceInfo();
        }
    }

    public final void cancel() {
        mCanceled = true;
    }
}
