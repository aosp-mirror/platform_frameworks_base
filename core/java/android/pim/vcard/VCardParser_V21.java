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

import android.pim.vcard.exception.VCardAgentNotSupportedException;
import android.pim.vcard.exception.VCardException;
import android.pim.vcard.exception.VCardInvalidCommentLineException;
import android.pim.vcard.exception.VCardInvalidLineException;
import android.pim.vcard.exception.VCardNestedException;
import android.pim.vcard.exception.VCardVersionException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * </p>
 * vCard parser implementation mostly for vCard 2.1. See the specification for more detail
 * about the spec itself.
 * </p>
 * <p>
 * The spec is written in 1996, and currently various types of "vCard 2.1" exist.
 * To handle real the world vCard formats appropriately and effectively, this class does not
 * obey with strict vCard 2.1. In stead, not only vCard spec but also real world
 * vCard is considered.
 * </p>
 * e.g. A lot of devices and softwares let vCard importer/exporter to use
 * the PNG format to determine the type of image, while it is not allowed in
 * the original specification. As of 2010, we can see even the FLV format
 * (possible in Japanese mobile phones).
 * </p>
 */
public class VCardParser_V21 extends VCardParser {
    private static final String LOG_TAG = "VCardParser_V21";

    private static final class CustomBufferedReader extends BufferedReader {
        private long mTime;

        public CustomBufferedReader(Reader in) {
            super(in);
        }

        @Override
        public String readLine() throws IOException {
            long start = System.currentTimeMillis();
            String ret = super.readLine();
            long end = System.currentTimeMillis();
            mTime += end - start;
            return ret;
        }

        public long getTotalmillisecond() {
            return mTime;
        }
    }

    /** Store the known-type */
    private static final HashSet<String> sKnownTypeSet = new HashSet<String>(
            Arrays.asList("DOM", "INTL", "POSTAL", "PARCEL", "HOME", "WORK",
                    "PREF", "VOICE", "FAX", "MSG", "CELL", "PAGER", "BBS",
                    "MODEM", "CAR", "ISDN", "VIDEO", "AOL", "APPLELINK",
                    "ATTMAIL", "CIS", "EWORLD", "INTERNET", "IBMMAIL",
                    "MCIMAIL", "POWERSHARE", "PRODIGY", "TLX", "X400", "GIF",
                    "CGM", "WMF", "BMP", "MET", "PMB", "DIB", "PICT", "TIFF",
                    "PDF", "PS", "JPEG", "QTIME", "MPEG", "MPEG2", "AVI",
                    "WAVE", "AIFF", "PCM", "X509", "PGP"));

    /** Store the known-value */
    private static final HashSet<String> sKnownValueSet = new HashSet<String>(
            Arrays.asList("INLINE", "URL", "CONTENT-ID", "CID"));

    /** Store the property names available in vCard 2.1 */
    private static final HashSet<String> sAvailablePropertyNameSetV21 =
        new HashSet<String>(Arrays.asList(
                "BEGIN", "LOGO", "PHOTO", "LABEL", "FN", "TITLE", "SOUND",
                "VERSION", "TEL", "EMAIL", "TZ", "GEO", "NOTE", "URL",
                "BDAY", "ROLE", "REV", "UID", "KEY", "MAILER"));

    /**
     * Though vCard 2.1 specification does not allow "B" encoding, some data may have it.
     * We allow it for safety...
     */
    private static final HashSet<String> sAvailableEncodingV21 =
        new HashSet<String>(Arrays.asList(
                "7BIT", "8BIT", "QUOTED-PRINTABLE", "BASE64", "B"));

    private static final String sDefaultEncoding = "8BIT";


    //// Protected members

    protected VCardInterpreter mInterpreter;

    /** 
     * <p>
     * The encoding type for deconding byte streams. This member variable is reset to
     * {{@link #sDefaultEncoding} every time when a new item comes.
     * </p>
     * <p>
     * "Encoding" in vCard is different from "Charset".
     * It is mainly used for addresses, notes, images. "7BIT", "8BIT", "BASE64", and
     * "QUOTED-PRINTABLE" are known examples.
     * </p>
     */
    protected String mCurrentEncoding;

    /**
     * <p>
     * The reader object to be used internally.
     * </p>
     * <p>
     * Developers should not directly read a line from this object. Use getLine() unless
     * there some reason.
     * </p>
     */
    protected BufferedReader mReader;

    /**
     * <p>
     * Set for storing unkonwn TYPE attributes, which is not acceptable in vCard specification,
     * but happens to be seen in real world vCard.
     * </p>
     */
    protected final Set<String> mUnknownTypeSet = new HashSet<String>();

    /**
     * <p>
     * Set for storing unkonwn VALUE attributes, which is not acceptable in vCard specification,
     * but happens to be seen in real world vCard.
     * </p>
     */
    protected final Set<String> mUnknownValueSet = new HashSet<String>();


    //// Private members

    // In some cases, vCard is nested. Currently, we only consider the most interior vCard data.
    // See v21_foma_1.vcf in test directory for more information.
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

    /**
     * <p>
     * Same as {{@link #VCardParser_V21(VCardSourceDetector)} with
     * {@link VCardConfig#PARSE_TYPE_UNKNOWN}.
     * </p>
     */
    public VCardParser_V21() {
        this(VCardConfig.PARSE_TYPE_UNKNOWN);
    }

    /**
     * <p>
     * The constructor which uses the estimated type available from a given detector.
     * </p>
     */
    public VCardParser_V21(VCardSourceDetector detector) {
        this(detector != null ? detector.getEstimatedType() : VCardConfig.PARSE_TYPE_UNKNOWN);
    }

    /**
     * <p>
     * The constructor which uses a given parse type like
     * {@link VCardConfig#PARSE_TYPE_UNKNOWN}.
     * </p>
     * <p>
     * This should be used only when you already know the exact type to be used.
     * </p>
     */
    public VCardParser_V21(int parseType) {
        super(parseType);
        if (parseType == VCardConfig.PARSE_TYPE_FOMA) {
            mNestCount = 1;
        }
    }

    /**
     * <p>
     * Parses the file at the given position.
     * </p>
     */
    // <pre class="prettyprint">vcard_file = [wsls] vcard [wsls]</pre>
    protected void parseVCardFile() throws IOException, VCardException {
        boolean firstReading = true;
        while (true) {
            if (mCanceled) {
                break;
            }
            if (!parseOneVCard(firstReading)) {
                break;
            }
            firstReading = false;
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
     * @return {@link VCardConfig#FLAG_V21}
     */
    protected int getVersion() {
        return VCardConfig.FLAG_V21;
    }

    /**
     * @return {@link VCardConfig#FLAG_V30}
     */
    protected String getVersionString() {
        return VCardConstants.VERSION_V21;
    }

    /**
     * @return true when a given property name is a valid property name.
     */
    protected boolean isValidPropertyName(final String propertyName) {
        if (!(sAvailablePropertyNameSetV21.contains(propertyName.toUpperCase()) ||
                propertyName.startsWith("X-")) && 
                !mUnknownTypeSet.contains(propertyName)) {
            mUnknownTypeSet.add(propertyName);
            Log.w(LOG_TAG, "Property name unsupported by vCard 2.1: " + propertyName);
        }
        return true;
    }

    /**
     * @return true when the encoding is a valid encoding.
     */
    protected boolean isValidEncoding(String encoding) {
        return sAvailableEncodingV21.contains(encoding.toUpperCase());
    }
    
    /**
     * @return String. It may be null, or its length may be 0
     * @throws IOException
     */
    protected String getLine() throws IOException {
        return mReader.readLine();
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
    private boolean parseOneVCard(boolean firstReading) throws IOException, VCardException {
        boolean allowGarbage = false;
        if (firstReading) {
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
        long start;
        if (mInterpreter != null) {
            start = System.currentTimeMillis();
            mInterpreter.startEntry();
            mTimeReadStartRecord += System.currentTimeMillis() - start;
        }
        start = System.currentTimeMillis();
        parseItems();
        mTimeParseItems += System.currentTimeMillis() - start;
        readEndVCard(true, false);
        if (mInterpreter != null) {
            start = System.currentTimeMillis();
            mInterpreter.endEntry();
            mTimeReadEndRecord += System.currentTimeMillis() - start;
        }
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
            String[] strArray = line.split(":", 2);
            int length = strArray.length;

            // Though vCard 2.1/3.0 specification does not allow lower cases,
            // vCard file emitted by some external vCard expoter have such invalid Strings.
            // So we allow it.
            // e.g. BEGIN:vCard
            if (length == 2 &&
                    strArray[0].trim().equalsIgnoreCase("BEGIN") &&
                    strArray[1].trim().equalsIgnoreCase("VCARD")) {
                return true;
            } else if (!allowGarbage) {
                if (mNestCount > 0) {
                    mPreviousLine = line;
                    return false;
                } else {
                    throw new VCardException(
                            "Expected String \"BEGIN:VCARD\" did not come "
                            + "(Instead, \"" + line + "\" came)");
                }
            }
        } while(allowGarbage);

        throw new VCardException("Reached where must not be reached.");
    }

    /**
     * <p>
     * The arguments useCache and allowGarbase are usually true and false accordingly when
     * this function is called outside this function itself.
     * </p>
     *
     * @param useCache When true, line is obtained from mPreviousline. Otherwise, getLine()
     * is used.
     * @param allowGarbage When true, ignore non "END:VCARD" line.
     * @throws IOException
     * @throws VCardException
     */
    protected void readEndVCard(boolean useCache, boolean allowGarbage)
            throws IOException, VCardException {
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
            if (strArray.length == 2 &&
                    strArray[0].trim().equalsIgnoreCase("END") &&
                    strArray[1].trim().equalsIgnoreCase("VCARD")) {
                return;
            } else if (!allowGarbage) {
                throw new VCardException("END:VCARD != \"" + mPreviousLine + "\"");
            }
            useCache = false;
        } while (allowGarbage);
    }
    
    /*
     * items = *CRLF item 
     *       / item
     */
    protected void parseItems() throws IOException, VCardException {
        boolean ended = false;
        
        if (mInterpreter != null) {
            long start = System.currentTimeMillis();
            mInterpreter.startProperty();
            mTimeStartProperty += System.currentTimeMillis() - start;
        }
        ended = parseItem();
        if (mInterpreter != null && !ended) {
            long start = System.currentTimeMillis();
            mInterpreter.endProperty();
            mTimeEndProperty += System.currentTimeMillis() - start;
        }

        while (!ended) {
            // follow VCARD ,it wont reach endProperty
            if (mInterpreter != null) {
                long start = System.currentTimeMillis();
                mInterpreter.startProperty();
                mTimeStartProperty += System.currentTimeMillis() - start;
            }
            try {
                ended = parseItem();
            } catch (VCardInvalidCommentLineException e) {
                Log.e(LOG_TAG, "Invalid line which looks like some comment was found. Ignored.");
                ended = false;
            }
            if (mInterpreter != null && !ended) {
                long start = System.currentTimeMillis();
                mInterpreter.endProperty();
                mTimeEndProperty += System.currentTimeMillis() - start;
            }
        }
    }
    
    /*
     * item = [groups "."] name    [params] ":" value CRLF
     *      / [groups "."] "ADR"   [params] ":" addressparts CRLF
     *      / [groups "."] "ORG"   [params] ":" orgparts CRLF
     *      / [groups "."] "N"     [params] ":" nameparts CRLF
     *      / [groups "."] "AGENT" [params] ":" vcard CRLF
     */
    protected boolean parseItem() throws IOException, VCardException {
        mCurrentEncoding = sDefaultEncoding;

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

        if (propertyName.equals("ADR") || propertyName.equals("ORG") ||
                propertyName.equals("N")) {
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
            } else if (propertyName.equals("VERSION") &&
                    !propertyValue.equals(getVersionString())) {
                throw new VCardVersionException("Incompatible version: " + 
                        propertyValue + " != " + getVersionString());
            }
            start = System.currentTimeMillis();
            handlePropertyValue(propertyName, propertyValue);
            mTimeParsePropertyValues += System.currentTimeMillis() - start;
            return false;
        }
        
        throw new VCardException("Unknown property name: \"" + propertyName + "\"");
    }

    static private final int STATE_GROUP_OR_PROPNAME = 0;
    static private final int STATE_PARAMS = 1;
    // vCard 3.0 specification allows double-quoted param-value, while vCard 2.1 does not.
    // This is just for safety.
    static private final int STATE_PARAMS_IN_DQUOTE = 2;

    protected String[] separateLineAndHandleGroup(String line) throws VCardException {
        int state = STATE_GROUP_OR_PROPNAME;
        int nameIndex = 0;

        final String[] propertyNameAndValue = new String[2];

        final int length = line.length();
        if (length > 0 && line.charAt(0) == '#') {
            throw new VCardInvalidCommentLineException();
        }

        // This loop is developed so that we don't have to take care of bottle neck here.
        // Refactor carefully when you need to do so.
        for (int i = 0; i < length; i++) {
            final char ch = line.charAt(i);
            switch (state) {
                case STATE_GROUP_OR_PROPNAME: {
                    if (ch == ':') {
                        final String propertyName = line.substring(nameIndex, i);
                        if (propertyName.equalsIgnoreCase("END")) {
                            mPreviousLine = line;
                            return null;
                        }
                        if (mInterpreter != null) {
                            mInterpreter.propertyName(propertyName);
                        }
                        propertyNameAndValue[0] = propertyName;
                        if (i < length - 1) {
                            propertyNameAndValue[1] = line.substring(i + 1);
                        } else {
                            propertyNameAndValue[1] = "";
                        }
                        return propertyNameAndValue;
                    } else if (ch == '.') {
                        String groupName = line.substring(nameIndex, i);
                        if (mInterpreter != null) {
                            mInterpreter.propertyGroup(groupName);
                        }
                        nameIndex = i + 1;
                    } else if (ch == ';') {
                        String propertyName = line.substring(nameIndex, i);
                        if (propertyName.equalsIgnoreCase("END")) {
                            mPreviousLine = line;
                            return null;
                        }
                        if (mInterpreter != null) {
                            mInterpreter.propertyName(propertyName);
                        }
                        propertyNameAndValue[0] = propertyName;
                        nameIndex = i + 1;
                        state = STATE_PARAMS;
                    }
                    break;
                }
                case STATE_PARAMS: {
                    if (ch == '"') {
                        state = STATE_PARAMS_IN_DQUOTE;
                    } else if (ch == ';') {
                        handleParams(line.substring(nameIndex, i));
                        nameIndex = i + 1;
                    } else if (ch == ':') {
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
                        state = STATE_PARAMS;
                    }
                    break;
                }
            }
        }
        
        throw new VCardInvalidLineException("Invalid line: \"" + line + "\"");
    }

    /*
     * params     = ";" [ws] paramlist
     * paramlist  = paramlist [ws] ";" [ws] param
     *            / param
     * param      = "TYPE" [ws] "=" [ws] ptypeval
     *            / "VALUE" [ws] "=" [ws] pvalueval
     *            / "ENCODING" [ws] "=" [ws] pencodingval
     *            / "CHARSET" [ws] "=" [ws] charsetval
     *            / "LANGUAGE" [ws] "=" [ws] langval
     *            / "X-" word [ws] "=" [ws] word
     *            / knowntype
     */
    protected void handleParams(String params) throws VCardException {
        String[] strArray = params.split("=", 2);
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
     * vCard 3.0 parser may throw VCardException.
     */
    @SuppressWarnings("unused")
    protected void handleParamWithoutName(final String paramValue) throws VCardException {
        handleType(paramValue);
    }

    /**
     * ptypeval = knowntype / "X-" word
     */
    protected void handleType(final String ptypeval) {
        String upperTypeValue = ptypeval;
        if (!(sKnownTypeSet.contains(upperTypeValue) || upperTypeValue.startsWith("X-")) && 
                !mUnknownTypeSet.contains(ptypeval)) {
            mUnknownTypeSet.add(ptypeval);
            Log.w(LOG_TAG, "TYPE unsupported by vCard 2.1: " + ptypeval);
        }
        if (mInterpreter != null) {
            mInterpreter.propertyParamType("TYPE");
            mInterpreter.propertyParamValue(upperTypeValue);
        }
    }
    
    /**
     * pvalueval = "INLINE" / "URL" / "CONTENT-ID" / "CID" / "X-" word
     */
    protected void handleValue(final String pvalueval) {
        if (!sKnownValueSet.contains(pvalueval.toUpperCase()) &&
                pvalueval.startsWith("X-") &&
                !mUnknownValueSet.contains(pvalueval)) {
            mUnknownValueSet.add(pvalueval);
            Log.w(LOG_TAG, "VALUE unsupported by vCard 2.1: " + pvalueval);
        }
        if (mInterpreter != null) {
            mInterpreter.propertyParamType("VALUE");
            mInterpreter.propertyParamValue(pvalueval);
        }
    }
    
    /**
     * pencodingval = "7BIT" / "8BIT" / "QUOTED-PRINTABLE" / "BASE64" / "X-" word
     */
    protected void handleEncoding(String pencodingval) throws VCardException {
        if (isValidEncoding(pencodingval) ||
                pencodingval.startsWith("X-")) {
            if (mInterpreter != null) {
                mInterpreter.propertyParamType("ENCODING");
                mInterpreter.propertyParamValue(pencodingval);
            }
            mCurrentEncoding = pencodingval;
        } else {
            throw new VCardException("Unknown encoding \"" + pencodingval + "\"");
        }
    }
    
    /**
     * vCard 2.1 specification only allows us-ascii and iso-8859-xxx (See RFC 1521),
     * but today's vCard often contains other charset, so we allow them.
     */
    protected void handleCharset(String charsetval) {
        if (mInterpreter != null) {
            mInterpreter.propertyParamType("CHARSET");
            mInterpreter.propertyParamValue(charsetval);
        }
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
        if (mInterpreter != null) {
            mInterpreter.propertyParamType("LANGUAGE");
            mInterpreter.propertyParamValue(langval);
        }
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
        if (mInterpreter != null) {
            mInterpreter.propertyParamType(paramName);
            mInterpreter.propertyParamValue(paramValue);
        }
    }

    protected void handlePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        if (mCurrentEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            final long start = System.currentTimeMillis();
            final String result = getQuotedPrintable(propertyValue);
            if (mInterpreter != null) {
                ArrayList<String> v = new ArrayList<String>();
                v.add(result);
                mInterpreter.propertyValues(v);
            }
            mTimeHandleQuotedPrintable += System.currentTimeMillis() - start;
        } else if (mCurrentEncoding.equalsIgnoreCase("BASE64") ||
                mCurrentEncoding.equalsIgnoreCase("B")) {
            final long start = System.currentTimeMillis();
            // It is very rare, but some BASE64 data may be so big that
            // OutOfMemoryError occurs. To ignore such cases, use try-catch.
            try {
                final String result = getBase64(propertyValue);
                if (mInterpreter != null) {
                    ArrayList<String> v = new ArrayList<String>();
                    v.add(result);
                    mInterpreter.propertyValues(v);
                }
            } catch (OutOfMemoryError error) {
                Log.e(LOG_TAG, "OutOfMemoryError happened during parsing BASE64 data!");
                if (mInterpreter != null) {
                    mInterpreter.propertyValues(null);
                }
            }
            mTimeHandleBase64 += System.currentTimeMillis() - start;
        } else {
            if (!(mCurrentEncoding == null || mCurrentEncoding.equalsIgnoreCase("7BIT")
                    || mCurrentEncoding.equalsIgnoreCase("8BIT")
                    || mCurrentEncoding.toUpperCase().startsWith("X-"))) {
                Log.w(LOG_TAG, "The encoding unsupported by vCard spec: \"" + mCurrentEncoding + "\".");
            }

            final long start = System.currentTimeMillis();
            if (mInterpreter != null) {
                ArrayList<String> v = new ArrayList<String>();
                v.add(maybeUnescapeText(propertyValue));
                mInterpreter.propertyValues(v);
            }
            mTimeHandleMiscPropertyValue += System.currentTimeMillis() - start;
        }
    }
    
    /**
     * <p>
     * Parses and returns Quoted-Printable.
     * </p>
     *
     * @param firstString The string following a parameter name and attributes.
     * Example: "string" in "ADR:ENCODING=QUOTED-PRINTABLE:string\n\r".
     * @return whole Quoted-Printable string, including a given argument and following lines.
     * Excludes the last empty line following to Quoted Printable lines.
     * @throws IOException
     * @throws VCardException
     */
    private String getQuotedPrintable(String firstString) throws IOException, VCardException {
        // Specifically, there may be some padding between = and CRLF.
        // See the following:
        //
        // qp-line := *(qp-segment transport-padding CRLF)
        //            qp-part transport-padding
        // qp-segment := qp-section *(SPACE / TAB) "="
        //             ; Maximum length of 76 characters
        //
        // e.g. (from RFC 2045)
        // Now's the time =
        // for all folk to come=
        //  to the aid of their country.
        if (firstString.trim().endsWith("=")) {
            // remove "transport-padding"
            int pos = firstString.length() - 1;
            while(firstString.charAt(pos) != '=') {
            }
            StringBuilder builder = new StringBuilder();
            builder.append(firstString.substring(0, pos + 1));
            builder.append("\r\n");
            String line;
            while (true) {
                line = getLine();
                if (line == null) {
                    throw new VCardException(
                            "File ended during parsing a Quoted-Printable String");
                }
                if (line.trim().endsWith("=")) {
                    // remove "transport-padding"
                    pos = line.length() - 1;
                    while(line.charAt(pos) != '=') {
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
                throw new VCardException(
                        "File ended during parsing BASE64 binary");
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
     * 
     * addressparts = 0*6(strnosemi ";") strnosemi
     *              ; PO Box, Extended Addr, Street, Locality, Region,
     *                Postal Code, Country Name
     * orgparts     = *(strnosemi ";") strnosemi
     *              ; First is Organization Name,
     *                remainder are Organization Units.
     * nameparts    = 0*4(strnosemi ";") strnosemi
     *              ; Family, Given, Middle, Prefix, Suffix.
     *              ; Example:Public;John;Q.;Reverend Dr.;III, Esq.
     * strnosemi    = *(*nonsemi ("\;" / "\" CRLF)) *nonsemi
     *              ; To include a semicolon in this string, it must be escaped
     *              ; with a "\" character.
     *
     * We do not care the number of "strnosemi" here.
     *
     * We are not sure whether we should add "\" CRLF to each value. We exclude them for now.
     */
    protected void handleMultiplePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        // vCard 2.1 does not allow QUOTED-PRINTABLE here, but some softwares/devices
        // emit such data.
        if (mCurrentEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            propertyValue = getQuotedPrintable(propertyValue);
        }

        if (mInterpreter != null) {
            mInterpreter.propertyValues(VCardUtils.constructListFromValue(
                    propertyValue, (getVersion() == VCardConfig.FLAG_V30)));
        }
    }

    /*
     * vCard 2.1 specifies AGENT allows one vcard entry. Currently we emit an error
     * toward the AGENT property.
     *
     * // TODO: Support AGENT property.
     *
     * item  = ...
     *       / [groups "."] "AGENT"
     *         [params] ":" vcard CRLF
     * vcard = "BEGIN" [ws] ":" [ws] "VCARD" [ws] 1*CRLF
     *         items *CRLF "END" [ws] ":" [ws] "VCARD"
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
     * Returns unescaped String if the character should be unescaped. Return null otherwise.
     * e.g. In vCard 2.1, "\;" should be unescaped into ";" while "\x" should not be.
     */
    protected String maybeUnescapeCharacter(final char ch) {
        return unescapeCharacter(ch);
    }

    public static String unescapeCharacter(final char ch) {
        // Original vCard 2.1 specification does not allow transformation
        // "\:" -> ":", "\," -> ",", and "\\" -> "\", but previous implementation of
        // this class allowed them, so keep it as is.
        if (ch == '\\' || ch == ';' || ch == ':' || ch == ',') {
            return String.valueOf(ch);
        } else {
            return null;
        }
    }

    @Override
    public boolean parse(InputStream is, String inputCharset, VCardInterpreter interpreter)
            throws IOException, VCardException {
        if (is == null) {
            throw new NullPointerException("InputStream must not be null.");
        }
        if (inputCharset == null) {
            inputCharset = VCardConfig.DEFAULT_TEMPORARY_CHARSET;
        }

        final InputStreamReader tmpReader = new InputStreamReader(is, inputCharset);
        if (VCardConfig.showPerformanceLog()) {
            mReader = new CustomBufferedReader(tmpReader);
        } else {
            mReader = new BufferedReader(tmpReader);
        }
        
        mInterpreter = interpreter;

        long start = System.currentTimeMillis();
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
        
        return true;
    }

    @Override
    public void parse(InputStream is, String charset, VCardInterpreter builder, boolean canceled)
            throws IOException, VCardException {
        mCanceled = canceled;
        parse(is, charset, builder);
    }

    private void showPerformanceInfo() {
        Log.d(LOG_TAG, "Total parsing time:  " + mTimeTotal + " ms");
        if (mReader instanceof CustomBufferedReader) {
            Log.d(LOG_TAG, "Total readLine time: " +
                    ((CustomBufferedReader)mReader).getTotalmillisecond() + " ms");
        }
        Log.d(LOG_TAG, "Time for handling the beggining of the record: " +
                mTimeReadStartRecord + " ms");
        Log.d(LOG_TAG, "Time for handling the end of the record: " +
                mTimeReadEndRecord + " ms");
        Log.d(LOG_TAG, "Time for parsing line, and handling group: " +
                mTimeParseLineAndHandleGroup + " ms");
        Log.d(LOG_TAG, "Time for parsing ADR, ORG, and N fields:" + mTimeParseAdrOrgN + " ms");
        Log.d(LOG_TAG, "Time for parsing property values: " + mTimeParsePropertyValues + " ms");
        Log.d(LOG_TAG, "Time for handling normal property values: " +
                mTimeHandleMiscPropertyValue + " ms");
        Log.d(LOG_TAG, "Time for handling Quoted-Printable: " +
                mTimeHandleQuotedPrintable + " ms");
        Log.d(LOG_TAG, "Time for handling Base64: " + mTimeHandleBase64 + " ms");
    }
}
