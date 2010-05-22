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
package com.android.vcard;

import android.util.Log;

import com.android.vcard.exception.VCardException;

import java.io.IOException;
import java.util.Set;

/**
 * <p>
 * Basic implementation achieving vCard 3.0 parsing.
 * </p>
 * <p>
 * This class inherits vCard 2.1 implementation since technically they are similar,
 * while specifically there's logical no relevance between them.
 * So that developers are not confused with the inheritance,
 * {@link VCardParser_V30} does not inherit {@link VCardParser_V21}, while
 * {@link VCardParserImpl_V30} inherits {@link VCardParserImpl_V21}.
 * </p>
 * @hide
 */
/* package */ class VCardParserImpl_V30 extends VCardParserImpl_V21 {
    private static final String LOG_TAG = "VCardParserImpl_V30";

    private String mPreviousLine;
    private boolean mEmittedAgentWarning = false;

    public VCardParserImpl_V30() {
        super();
    }

    public VCardParserImpl_V30(int vcardType) {
        super(vcardType);
    }

    @Override
    protected int getVersion() {
        return VCardConfig.FLAG_V30;
    }

    @Override
    protected String getVersionString() {
        return VCardConstants.VERSION_V30;
    }

    @Override
    protected String getLine() throws IOException {
        if (mPreviousLine != null) {
            String ret = mPreviousLine;
            mPreviousLine = null;
            return ret;
        } else {
            return mReader.readLine();
        }
    }

    /**
     * vCard 3.0 requires that the line with space at the beginning of the line
     * must be combined with previous line.
     */
    @Override
    protected String getNonEmptyLine() throws IOException, VCardException {
        String line;
        StringBuilder builder = null;
        while (true) {
            line = mReader.readLine();
            if (line == null) {
                if (builder != null) {
                    return builder.toString();
                } else if (mPreviousLine != null) {
                    String ret = mPreviousLine;
                    mPreviousLine = null;
                    return ret;
                }
                throw new VCardException("Reached end of buffer.");
            } else if (line.length() == 0) {
                if (builder != null) {
                    return builder.toString();
                } else if (mPreviousLine != null) {
                    String ret = mPreviousLine;
                    mPreviousLine = null;
                    return ret;
                }
            } else if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (builder != null) {
                    // See Section 5.8.1 of RFC 2425 (MIME-DIR document).
                    // Following is the excerpts from it.
                    //
                    // DESCRIPTION:This is a long description that exists on a long line.
                    //
                    // Can be represented as:
                    //
                    // DESCRIPTION:This is a long description
                    //  that exists on a long line.
                    //
                    // It could also be represented as:
                    //
                    // DESCRIPTION:This is a long descrip
                    //  tion that exists o
                    //  n a long line.
                    builder.append(line.substring(1));
                } else if (mPreviousLine != null) {
                    builder = new StringBuilder();
                    builder.append(mPreviousLine);
                    mPreviousLine = null;
                    builder.append(line.substring(1));
                } else {
                    throw new VCardException("Space exists at the beginning of the line");
                }
            } else {
                if (mPreviousLine == null) {
                    mPreviousLine = line;
                    if (builder != null) {
                        return builder.toString();
                    }
                } else {
                    String ret = mPreviousLine;
                    mPreviousLine = line;
                    return ret;
                }
            }
        }
    }

    /*
     * vcard = [group "."] "BEGIN" ":" "VCARD" 1 * CRLF
     *         1 * (contentline)
     *         ;A vCard object MUST include the VERSION, FN and N types.
     *         [group "."] "END" ":" "VCARD" 1 * CRLF
     */
    @Override
    protected boolean readBeginVCard(boolean allowGarbage) throws IOException, VCardException {
        // TODO: vCard 3.0 supports group.
        return super.readBeginVCard(allowGarbage);
    }

    @Override
    protected void readEndVCard(boolean useCache, boolean allowGarbage)
            throws IOException, VCardException {
        // TODO: vCard 3.0 supports group.
        super.readEndVCard(useCache, allowGarbage);
    }

    /**
     * vCard 3.0 allows iana-token as paramType, while vCard 2.1 does not.
     */
    @Override
    protected void handleParams(final String params) throws VCardException {
        try {
            super.handleParams(params);
        } catch (VCardException e) {
            // maybe IANA type
            String[] strArray = params.split("=", 2);
            if (strArray.length == 2) {
                handleAnyParam(strArray[0], strArray[1]);
            } else {
                // Must not come here in the current implementation.
                throw new VCardException(
                        "Unknown params value: " + params);
            }
        }
    }

    @Override
    protected void handleAnyParam(final String paramName, final String paramValue) {
        super.handleAnyParam(paramName, paramValue);
    }

    @Override
    protected void handleParamWithoutName(final String paramValue) throws VCardException {
        super.handleParamWithoutName(paramValue);
    }

    /*
     *  vCard 3.0 defines
     *
     *  param         = param-name "=" param-value *("," param-value)
     *  param-name    = iana-token / x-name
     *  param-value   = ptext / quoted-string
     *  quoted-string = DQUOTE QSAFE-CHAR DQUOTE
     */
    @Override
    protected void handleType(final String ptypevalues) {
        String[] ptypeArray = ptypevalues.split(",");
        mInterpreter.propertyParamType("TYPE");
        for (String value : ptypeArray) {
            int length = value.length();
            if (length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                mInterpreter.propertyParamValue(value.substring(1, value.length() - 1));
            } else {
                mInterpreter.propertyParamValue(value);
            }
        }
    }

    @Override
    protected void handleAgent(final String propertyValue) {
        // The way how vCard 3.0 supports "AGENT" is completely different from vCard 2.1.
        //
        // e.g.
        // AGENT:BEGIN:VCARD\nFN:Joe Friday\nTEL:+1-919-555-7878\n
        //  TITLE:Area Administrator\, Assistant\n EMAIL\;TYPE=INTERN\n
        //  ET:jfriday@host.com\nEND:VCARD\n
        //
        // TODO: fix this.
        //
        // issue:
        //  vCard 3.0 also allows this as an example.
        //
        // AGENT;VALUE=uri:
        //  CID:JQPUBLIC.part3.960129T083020.xyzMail@host3.com
        //
        // This is not vCard. Should we support this?
        //
        // Just ignore the line for now, since we cannot know how to handle it...
        if (!mEmittedAgentWarning) {
            Log.w(LOG_TAG, "AGENT in vCard 3.0 is not supported yet. Ignore it");
            mEmittedAgentWarning = true;
        }
    }

    /**
     * vCard 3.0 does not require two CRLF at the last of BASE64 data.
     * It only requires that data should be MIME-encoded.
     */
    @Override
    protected String getBase64(final String firstString)
            throws IOException, VCardException {
        final StringBuilder builder = new StringBuilder();
        builder.append(firstString);

        while (true) {
            final String line = getLine();
            if (line == null) {
                throw new VCardException("File ended during parsing BASE64 binary");
            }
            if (line.length() == 0) {
                break;
            } else if (!line.startsWith(" ") && !line.startsWith("\t")) {
                mPreviousLine = line;
                break;
            }
            builder.append(line);
        }

        return builder.toString();
    }

    /**
     * ESCAPED-CHAR = "\\" / "\;" / "\," / "\n" / "\N")
     *              ; \\ encodes \, \n or \N encodes newline
     *              ; \; encodes ;, \, encodes ,
     *
     * Note: Apple escapes ':' into '\:' while does not escape '\'
     */
    @Override
    protected String maybeUnescapeText(final String text) {
        return unescapeText(text);
    }

    public static String unescapeText(final String text) {
        StringBuilder builder = new StringBuilder();
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i < length - 1) {
                final char next_ch = text.charAt(++i);
                if (next_ch == 'n' || next_ch == 'N') {
                    builder.append("\n");
                } else {
                    builder.append(next_ch);
                }
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    @Override
    protected String maybeUnescapeCharacter(final char ch) {
        return unescapeCharacter(ch);
    }

    public static String unescapeCharacter(final char ch) {
        if (ch == 'n' || ch == 'N') {
            return "\n";
        } else {
            return String.valueOf(ch);
        }
    }

    @Override
    protected Set<String> getKnownPropertyNameSet() {
        return VCardParser_V30.sKnownPropertyNameSet;
    }
}
