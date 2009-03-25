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

package android.syncml.pim.vcard;

import android.syncml.pim.VBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * This class is used to parse vcard. Please refer to vCard Specification 2.1
 */
public class VCardParser_V21 {

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
        
    /** Store the property name available in vCard 2.1 */
    // NICKNAME is not supported in vCard 2.1, but some vCard may contain.
    private static final HashSet<String> sAvailablePropertyNameV21 =
        new HashSet<String>(Arrays.asList(
                "LOGO", "PHOTO", "LABEL", "FN", "TITLE", "SOUND",
                "VERSION", "TEL", "EMAIL", "TZ", "GEO", "NOTE", "URL",
                "BDAY", "ROLE", "REV", "UID", "KEY", "MAILER",
                "NICKNAME"));

    // Though vCard 2.1 specification does not allow "B" encoding, some data may have it.
    // We allow it for safety...
    private static final HashSet<String> sAvailableEncodingV21 =
        new HashSet<String>(Arrays.asList(
                "7BIT", "8BIT", "QUOTED-PRINTABLE", "BASE64", "B"));
    
    // Used only for parsing END:VCARD.
    private String mPreviousLine;
    
    /** The builder to build parsed data */
    protected VBuilder mBuilder = null;

    /** The encoding type */
    protected String mEncoding = null;
    
    protected final String sDefaultEncoding = "8BIT";
    
    // Should not directly read a line from this. Use getLine() instead.
    protected BufferedReader mReader;
    
    /**
     * Create a new VCard parser.
     */
    public VCardParser_V21() {
        super();
    }

    /**
     * Parse the file at the given position
     * vcard_file   = [wsls] vcard [wsls]
     */
    protected void parseVCardFile() throws IOException, VCardException {
        while (parseOneVCard()) {
        }
    }

    protected String getVersion() {
        return "2.1";
    }
    
    /**
     * @return true when the propertyName is a valid property name.
     */
    protected boolean isValidPropertyName(String propertyName) {
        return sAvailablePropertyNameV21.contains(propertyName.toUpperCase());
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
    
    /**
     *  vcard        = "BEGIN" [ws] ":" [ws] "VCARD" [ws] 1*CRLF
     *                 items *CRLF
     *                 "END" [ws] ":" [ws] "VCARD"
     */
    private boolean parseOneVCard() throws IOException, VCardException {
        if (!readBeginVCard()) {
            return false;
        }
        parseItems();
        readEndVCard();
        return true;
    }
    
    /**
     * @return True when successful. False when reaching the end of line  
     * @throws IOException
     * @throws VCardException
     */
    protected boolean readBeginVCard() throws IOException, VCardException {
        String line;
        while (true) {
            line = getLine();
            if (line == null) {
                return false;
            } else if (line.trim().length() > 0) {
                break;
            }
        }
        String[] strArray = line.split(":", 2);
        
        // Though vCard specification does not allow lower cases,
        // some data may have them, so we allow it.
        if (!(strArray.length == 2 &&
                strArray[0].trim().equalsIgnoreCase("BEGIN") && 
                strArray[1].trim().equalsIgnoreCase("VCARD"))) {
            throw new VCardException("BEGIN:VCARD != \"" + line + "\"");
        }
        
        if (mBuilder != null) {
            mBuilder.startRecord("VCARD");
        }

        return true;
    }
    
    protected void readEndVCard() throws VCardException {
        // Though vCard specification does not allow lower cases,
        // some data may have them, so we allow it.
        String[] strArray = mPreviousLine.split(":", 2);
        if (!(strArray.length == 2 &&
                strArray[0].trim().equalsIgnoreCase("END") &&
                strArray[1].trim().equalsIgnoreCase("VCARD"))) {
            throw new VCardException("END:VCARD != \"" + mPreviousLine + "\"");
        }
        
        if (mBuilder != null) {
            mBuilder.endRecord();
        }
    }
    
    /**
     * items = *CRLF item 
     *       / item
     */
    protected void parseItems() throws IOException, VCardException {
        /* items *CRLF item / item */
        boolean ended = false;
        
        if (mBuilder != null) {
            mBuilder.startProperty();
        }

        try {
            ended = parseItem();
        } finally {
            if (mBuilder != null) {
                mBuilder.endProperty();
            }
        }

        while (!ended) {
            // follow VCARD ,it wont reach endProperty
            if (mBuilder != null) {
                mBuilder.startProperty();
            }
            try {
                ended = parseItem();
            } finally {
                if (mBuilder != null) {
                    mBuilder.endProperty();
                }
            }
        }
    }

    /**
     * item      = [groups "."] name    [params] ":" value CRLF
     *           / [groups "."] "ADR"   [params] ":" addressparts CRLF
     *           / [groups "."] "ORG"   [params] ":" orgparts CRLF
     *           / [groups "."] "N"     [params] ":" nameparts CRLF
     *           / [groups "."] "AGENT" [params] ":" vcard CRLF 
     */
    protected boolean parseItem() throws IOException, VCardException {
        mEncoding = sDefaultEncoding;

        // params    = ";" [ws] paramlist
        String line = getNonEmptyLine();
        String[] strArray = line.split(":", 2);
        if (strArray.length < 2) {
            throw new VCardException("Invalid line(\":\" does not exist): " + line);
        }
        String propertyValue = strArray[1];
        String[] groupNameParamsArray = strArray[0].split(";");
        String groupAndName = groupNameParamsArray[0].trim();
        String[] groupNameArray = groupAndName.split("\\.");
        int length = groupNameArray.length;
        String propertyName = groupNameArray[length - 1];
        if (mBuilder != null) {
            mBuilder.propertyName(propertyName);
            for (int i = 0; i < length - 1; i++) {
                mBuilder.propertyGroup(groupNameArray[i]);
            }
        }
        if (propertyName.equalsIgnoreCase("END")) {
            mPreviousLine = line;
            return true;
        }
        
        length = groupNameParamsArray.length;
        for (int i = 1; i < length; i++) {
            handleParams(groupNameParamsArray[i]);
        }
        
        if (isValidPropertyName(propertyName) ||
                propertyName.startsWith("X-")) {
            if (propertyName.equals("VERSION") &&
                    !propertyValue.equals(getVersion())) {
                throw new VCardVersionException("Incompatible version: " + 
                        propertyValue + " != " + getVersion());
            }
            handlePropertyValue(propertyName, propertyValue);
            return false;
        } else if (propertyName.equals("ADR") ||
                propertyName.equals("ORG") ||
                propertyName.equals("N")) {
            handleMultiplePropertyValue(propertyName, propertyValue);
            return false;
        } else if (propertyName.equals("AGENT")) {
            handleAgent(propertyValue);
            return false;
        }
        
        throw new VCardException("Unknown property name: \"" + 
                propertyName + "\"");
    }

    /**
     * params      = ";" [ws] paramlist
     * paramlist   = paramlist [ws] ";" [ws] param
     *             / param
     * param       = "TYPE" [ws] "=" [ws] ptypeval
     *             / "VALUE" [ws] "=" [ws] pvalueval
     *             / "ENCODING" [ws] "=" [ws] pencodingval
     *             / "CHARSET" [ws] "=" [ws] charsetval
     *             / "LANGUAGE" [ws] "=" [ws] langval
     *             / "X-" word [ws] "=" [ws] word
     *             / knowntype
     */
    protected void handleParams(String params) throws VCardException {
        String[] strArray = params.split("=", 2);
        if (strArray.length == 2) {
            String paramName = strArray[0].trim();
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
            handleType(strArray[0]);
        }
    }
    
    /**
     * typeval  = knowntype / "X-" word
     */
    protected void handleType(String ptypeval) throws VCardException {
        if (sKnownTypeSet.contains(ptypeval.toUpperCase()) ||
                ptypeval.startsWith("X-")) {
            if (mBuilder != null) {
                mBuilder.propertyParamType("TYPE");
                mBuilder.propertyParamValue(ptypeval.toUpperCase());
            }
        } else {
            throw new VCardException("Unknown type: \"" + ptypeval + "\"");
        }        
    }
    
    /**
     * pvalueval = "INLINE" / "URL" / "CONTENT-ID" / "CID" / "X-" word
     */
    protected void handleValue(String pvalueval) throws VCardException {
        if (sKnownValueSet.contains(pvalueval.toUpperCase()) ||
                pvalueval.startsWith("X-")) {
            if (mBuilder != null) {
                mBuilder.propertyParamType("VALUE");
                mBuilder.propertyParamValue(pvalueval);
            }
        } else {
            throw new VCardException("Unknown value \"" + pvalueval + "\"");
        }
    }
    
    /**
     * pencodingval = "7BIT" / "8BIT" / "QUOTED-PRINTABLE" / "BASE64" / "X-" word
     */
    protected void handleEncoding(String pencodingval) throws VCardException {
        if (isValidEncoding(pencodingval) ||
                pencodingval.startsWith("X-")) {
            if (mBuilder != null) {
                mBuilder.propertyParamType("ENCODING");
                mBuilder.propertyParamValue(pencodingval);
            }
            mEncoding = pencodingval;
        } else {
            throw new VCardException("Unknown encoding \"" + pencodingval + "\"");
        }
    }
    
    /**
     * vCard specification only allows us-ascii and iso-8859-xxx (See RFC 1521),
     * but some vCard contains other charset, so we allow them. 
     */
    protected void handleCharset(String charsetval) {
        if (mBuilder != null) {
            mBuilder.propertyParamType("CHARSET");
            mBuilder.propertyParamValue(charsetval);
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
            if (!isLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        tmp = strArray[1];
        length = tmp.length();
        for (int i = 0; i < length; i++) {
            if (!isLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        if (mBuilder != null) {
            mBuilder.propertyParamType("LANGUAGE");
            mBuilder.propertyParamValue(langval);
        }
    }

    /**
     * Mainly for "X-" type. This accepts any kind of type without check.
     */
    protected void handleAnyParam(String paramName, String paramValue) {
        if (mBuilder != null) {
            mBuilder.propertyParamType(paramName);
            mBuilder.propertyParamValue(paramValue);
        }
    }
    
    protected void handlePropertyValue(
            String propertyName, String propertyValue) throws
            IOException, VCardException {
        if (mEncoding == null || mEncoding.equalsIgnoreCase("7BIT")
                || mEncoding.equalsIgnoreCase("8BIT")
                || mEncoding.toUpperCase().startsWith("X-")) {
            if (mBuilder != null) {
                ArrayList<String> v = new ArrayList<String>();
                v.add(maybeUnescapeText(propertyValue));
                mBuilder.propertyValues(v);
            }
        } else if (mEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            String result = getQuotedPrintable(propertyValue);
            if (mBuilder != null) {
                ArrayList<String> v = new ArrayList<String>();
                v.add(result);
                mBuilder.propertyValues(v);
            }
        } else if (mEncoding.equalsIgnoreCase("BASE64") ||
                mEncoding.equalsIgnoreCase("B")) {
            String result = getBase64(propertyValue);
            if (mBuilder != null) {
                ArrayList<String> v = new ArrayList<String>();
                v.add(result);
                mBuilder.propertyValues(v);
            }            
        } else {
            throw new VCardException("Unknown encoding: \"" + mEncoding + "\"");
        }
    }
    
    protected String getQuotedPrintable(String firstString) throws IOException, VCardException {
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
                            "File ended during parsing quoted-printable String");
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
     * Mainly for "ADR", "ORG", and "N"
     * We do not care the number of strnosemi here.
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
     * We are not sure whether we should add "\" CRLF to each value.
     * For now, we exclude them.               
     */
    protected void handleMultiplePropertyValue(
            String propertyName, String propertyValue) throws IOException, VCardException {
        // vCard 2.1 does not allow QUOTED-PRINTABLE here, but some data have it.
        if (mEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            propertyValue = getQuotedPrintable(propertyValue);
        }
        
        if (propertyValue.endsWith("\\")) {
            StringBuilder builder = new StringBuilder();
            // builder.append(propertyValue);
            builder.append(propertyValue.substring(0, propertyValue.length() - 1));
            try {
                String line;
                while (true) {
                    line = getNonEmptyLine();
                    // builder.append("\r\n");
                    // builder.append(line);
                    if (!line.endsWith("\\")) {
                        builder.append(line);
                        break;
                    } else {
                        builder.append(line.substring(0, line.length() - 1));
                    }
                }
            } catch (IOException e) {
                throw new VCardException(
                        "IOException is throw during reading propertyValue" + e);
            }
            // Now, propertyValue may contain "\r\n"
            propertyValue = builder.toString();
        }

        if (mBuilder != null) {
            // In String#replaceAll() and Pattern class, "\\\\" means single slash. 

            final String IMPOSSIBLE_STRING = "\0";
            // First replace two backslashes with impossible strings.
            propertyValue = propertyValue.replaceAll("\\\\\\\\", IMPOSSIBLE_STRING);

            // Now, split propertyValue with ; whose previous char is not back slash.
            Pattern pattern = Pattern.compile("(?<!\\\\);");
            // TODO: limit should be set in accordance with propertyName?
            String[] strArray = pattern.split(propertyValue, -1); 
            ArrayList<String> arrayList = new ArrayList<String>();
            for (String str : strArray) {
                // Replace impossible strings with original two backslashes
                arrayList.add(
                        unescapeText(str.replaceAll(IMPOSSIBLE_STRING, "\\\\\\\\")));
            }
            mBuilder.propertyValues(arrayList);
        }
    }
    
    /**
     * vCard 2.1 specifies AGENT allows one vcard entry. It is not encoded at all.
     */
    protected void handleAgent(String propertyValue) throws IOException, VCardException {
        String[] strArray = propertyValue.split(":", 2);
        if (!(strArray.length == 2 ||
                strArray[0].trim().equalsIgnoreCase("BEGIN") && 
                strArray[1].trim().equalsIgnoreCase("VCARD"))) {
            throw new VCardException("BEGIN:VCARD != \"" + propertyValue + "\"");
        }
        parseItems();
        readEndVCard();
    }
    
    /**
     * For vCard 3.0.
     */
    protected String maybeUnescapeText(String text) {
        return text;
    }
    
    /**
     * Convert escaped text into unescaped text.
     */
    protected String unescapeText(String text) {
        // Original vCard 2.1 specification does not allow transformation
        // "\:" -> ":", "\," -> ",", and "\\" -> "\", but previous implementation of
        // this class allowed them, so keep it as is.
        // In String#replaceAll(), "\\\\" means single slash. 
        return text.replaceAll("\\\\;", ";")
            .replaceAll("\\\\:", ":")
            .replaceAll("\\\\,", ",")
            .replaceAll("\\\\\\\\", "\\\\");
    }
    
    /**
     * Parse the given stream and constructs VCardDataBuilder object.
     * Note that vCard 2.1 specification allows "CHARSET" parameter, and some career sets
     * local encoding to it. For example, Japanese phone career uses Shift_JIS, which
     * is not formally allowed in vCard specification.
     * As a result, there is a case where the encoding given here does not do well with
     * the "CHARSET".
     * 
     * In order to avoid such cases, It may be fine to use "ISO-8859-1" as an encoding,
     * and to encode each localized String afterward.
     * 
     * RFC 2426 "recommends" (not forces) to use UTF-8, so it may be OK to use
     * UTF-8 as an encoding when parsing vCard 3.0. But note that some Japanese
     * phone uses Shift_JIS as a charset (e.g. W61SH), and another uses
     * "CHARSET=SHIFT_JIS", which is explicitly prohibited in vCard 3.0 specification
     * (e.g. W53K). 
     *      
     * @param is
     *            The source to parse.
     * @param charset
     *            The charset.
     * @param builder
     *            The v builder which used to construct data.
     * @return Return true for success, otherwise false.
     * @throws IOException
     */
    public boolean parse(InputStream is, String charset, VBuilder builder)
            throws IOException, VCardException {
        // TODO: If we really need to allow only CRLF as line break,
        // we will have to develop our own BufferedReader().
        mReader = new BufferedReader(new InputStreamReader(is, charset));
        
        mBuilder = builder;

        if (mBuilder != null) {
            mBuilder.start();
        }
        parseVCardFile();
        if (mBuilder != null) {
            mBuilder.end();
        }
        return true;
    }
    
    private boolean isLetter(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
            return true;
        }
        return false;
    }
}
