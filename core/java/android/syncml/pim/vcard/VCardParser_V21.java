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

import android.syncml.pim.VParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is used to parse vcard. Please refer to vCard Specification 2.1
 */
public class VCardParser_V21 extends VParser {

    /** Store the known-type */
    private static final HashSet<String> mKnownTypeSet = new HashSet<String>(
            Arrays.asList("DOM", "INTL", "POSTAL", "PARCEL", "HOME", "WORK",
                    "PREF", "VOICE", "FAX", "MSG", "CELL", "PAGER", "BBS",
                    "MODEM", "CAR", "ISDN", "VIDEO", "AOL", "APPLELINK",
                    "ATTMAIL", "CIS", "EWORLD", "INTERNET", "IBMMAIL",
                    "MCIMAIL", "POWERSHARE", "PRODIGY", "TLX", "X400", "GIF",
                    "CGM", "WMF", "BMP", "MET", "PMB", "DIB", "PICT", "TIFF",
                    "PDF", "PS", "JPEG", "QTIME", "MPEG", "MPEG2", "AVI",
                    "WAVE", "AIFF", "PCM", "X509", "PGP"));

    /** Store the name */
    private static final HashSet<String> mName = new HashSet<String>(Arrays
            .asList("LOGO", "PHOTO", "LABEL", "FN", "TITLE", "SOUND",
                    "VERSION", "TEL", "EMAIL", "TZ", "GEO", "NOTE", "URL",
                    "BDAY", "ROLE", "REV", "UID", "KEY", "MAILER"));

    /**
     * Create a new VCard parser.
     */
    public VCardParser_V21() {
        super();
    }

    /**
     * Parse the file at the given position
     *
     * @param offset
     *            the given position to parse
     * @return vcard length
     */
    protected int parseVFile(int offset) {
        return parseVCardFile(offset);
    }

    /**
     * [wsls] vcard [wsls]
     */
    int parseVCardFile(int offset) {
        int ret = 0, sum = 0;

        /* remove \t \r\n */
        while ((ret = parseWsls(offset)) != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseVCard(offset); // BEGIN:VCARD ... END:VCARD
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        } else {
            return PARSE_ERROR;
        }

        /* remove \t \r\n */
        while ((ret = parseWsls(offset)) != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }
        return sum;
    }

    /**
     * "BEGIN" [ws] ":" [ws] "VCARD" [ws] 1*CRLF items *CRLF "END" [ws] ":"
     * "VCARD"
     */
    private int parseVCard(int offset) {
        int ret = 0, sum = 0;

        /* BEGIN */
        ret = parseString(offset, "BEGIN", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        /* colon */
        ret = parseString(offset, ":", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        /* VCARD */
        ret = parseString(offset, "VCARD", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.startRecord("VCARD");
        }

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        /* 1*CRLF */
        ret = parseCrlf(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while ((ret = parseCrlf(offset)) != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseItems(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        /* *CRLF */
        while ((ret = parseCrlf(offset)) != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        /* END */
        ret = parseString(offset, "END", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        /* colon */
        ret = parseString(offset, ":", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        /* VCARD */
        ret = parseString(offset, "VCARD", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        // offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endRecord();
        }

        return sum;
    }

    /**
     * items *CRLF item / item
     */
    private int parseItems(int offset) {
        /* items *CRLF item / item */
        int ret = 0, sum = 0;

        if (mBuilder != null) {
            mBuilder.startProperty();
        }
        ret = parseItem(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endProperty();
        }

        for (;;) {
            while ((ret = parseCrlf(offset)) != PARSE_ERROR) {
                offset += ret;
                sum += ret;
            }
            // follow VCARD ,it wont reach endProperty
            if (mBuilder != null) {
                mBuilder.startProperty();
            }

            ret = parseItem(offset);
            if (ret == PARSE_ERROR) {
                break;
            }
            offset += ret;
            sum += ret;
            if (mBuilder != null) {
                mBuilder.endProperty();
            }
        }

        return sum;
    }

    /**
     * item0 / item1 / item2
     */
    private int parseItem(int offset) {
        int ret = 0, sum = 0;
        mEncoding = mDefaultEncoding;

        ret = parseItem0(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseItem1(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseItem2(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        return PARSE_ERROR;
    }

    /** [groups "."] name [params] ":" value CRLF */
    private int parseItem0(int offset) {
        int ret = 0, sum = 0, start = offset;
        String proName = "", proValue = "";

        ret = parseGroupsWithDot(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseName(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            proName = mBuffer.substring(start, offset).trim();
            mBuilder.propertyName(proName);
        }

        ret = parseParams(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseValue(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        proValue = mBuffer.substring(start, offset);
        if (proName.equals("VERSION") && !proValue.equals("2.1")) {
            return PARSE_ERROR;
        }
        if (mBuilder != null) {
            ArrayList<String> v = new ArrayList<String>();
            v.add(proValue);
            mBuilder.propertyValues(v);
        }

        ret = parseCrlf(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /** "ADR" "ORG" "N" with semi-colon separated content */
    private int parseItem1(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseGroupsWithDot(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        if ((ret = parseString(offset, "ADR", true)) == PARSE_ERROR
                && (ret = parseString(offset, "ORG", true)) == PARSE_ERROR
                && (ret = parseString(offset, "N", true)) == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName(mBuffer.substring(start, offset).trim());
        }

        ret = parseParams(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseValue(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            int end = 0;
            ArrayList<String> v = new ArrayList<String>();
            Pattern p = Pattern
                    .compile("([^;\\\\]*(\\\\[\\\\;:,])*[^;\\\\]*)(;?)");
            Matcher m = p.matcher(mBuffer.substring(start, offset));
            while (m.find()) {
                String s = escapeTranslator(m.group(1));
                v.add(s);
                end = m.end();
                if (offset == start + end) {
                    String endValue = m.group(3);
                    if (";".equals(endValue)) {
                        v.add("");
                    }
                    break;
                }
            }
            mBuilder.propertyValues(v);
        }

        ret = parseCrlf(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /** [groups] "." "AGENT" [params] ":" vcard CRLF */
    private int parseItem2(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseGroupsWithDot(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, "AGENT", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName(mBuffer.substring(start, offset));
        }

        ret = parseParams(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = parseCrlf(offset);
        if (ret != PARSE_ERROR) {
            offset += ret;
            sum += ret;
        }

        ret = parseVCard(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyValues(new ArrayList<String>());
        }

        ret = parseCrlf(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    private int parseGroupsWithDot(int offset) {
        int ret = 0, sum = 0;
        /* [groups "."] */
        ret = parseGroups(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = parseString(offset, ".", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /** ";" [ws] paramlist */
    private int parseParams(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, ";", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseParamList(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /**
     * paramlist [ws] ";" [ws] param / param
     */
    private int parseParamList(int offset) {
        int ret = 0, sum = 0;

        ret = parseParam(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        int offsetTemp = offset;
        int sumTemp = sum;
        for (;;) {
            ret = removeWs(offsetTemp);
            offsetTemp += ret;
            sumTemp += ret;

            ret = parseString(offsetTemp, ";", false);
            if (ret == PARSE_ERROR) {
                return sum;
            }
            offsetTemp += ret;
            sumTemp += ret;

            ret = removeWs(offsetTemp);
            offsetTemp += ret;
            sumTemp += ret;

            ret = parseParam(offsetTemp);
            if (ret == PARSE_ERROR) {
                break;
            }
            offsetTemp += ret;
            sumTemp += ret;

            // offset = offsetTemp;
            sum = sumTemp;
        }
        return sum;
    }

    /**
     * param0 / param1 / param2 / param3 / param4 / param5 / knowntype<BR>
     * TYPE / VALUE / ENDCODING / CHARSET / LANGUAGE ...
     */
    private int parseParam(int offset) {
        int ret = 0, sum = 0;

        ret = parseParam0(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseParam1(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseParam2(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseParam3(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseParam4(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseParam5(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        int start = offset;
        ret = parseKnownType(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(null);
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;
    }

    /** "TYPE" [ws] "=" [ws] ptypeval */
    private int parseParam0(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "TYPE", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePTypeVal(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;

    }

    /** "VALUE" [ws] "=" [ws] pvalueval */
    private int parseParam1(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "VALUE", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePValueVal(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;
    }

    /** "ENCODING" [ws] "=" [ws] pencodingval */
    private int parseParam2(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "ENCODING", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePEncodingVal(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;

    }

    /** "CHARSET" [ws] "=" [ws] charsetval */
    private int parseParam3(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "CHARSET", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseCharsetVal(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;
    }

    /** "LANGUAGE" [ws] "=" [ws] langval */
    private int parseParam4(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "LANGUAGE", true);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseLangVal(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;

    }

    /** "X-" word [ws] "=" [ws] word */
    private int parseParam5(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseXWord(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", false);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseWord(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;
    }

    /**
     * knowntype: "DOM" / "INTL" / ...
     */
    private int parseKnownType(int offset) {
        String word = getWord(offset);

        if (mKnownTypeSet.contains(word.toUpperCase())) {
            return word.length();
        }
        return PARSE_ERROR;
    }

    /** knowntype / "X-" word */
    private int parsePTypeVal(int offset) {
        int ret = 0, sum = 0;

        ret = parseKnownType(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseXWord(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }
        sum += ret;

        return sum;
    }

    /** "LOGO" /.../ XWord, case insensitive */
    private int parseName(int offset) {
        int ret = 0;
        ret = parseXWord(offset);
        if (ret != PARSE_ERROR) {
            return ret;
        }
        String word = getWord(offset).toUpperCase();
        if (mName.contains(word)) {
            return word.length();
        }
        return PARSE_ERROR;
    }

    /** groups "." word / word */
    private int parseGroups(int offset) {
        int ret = 0, sum = 0;

        ret = parseWord(offset);
        if (ret == PARSE_ERROR) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        for (;;) {
            ret = parseString(offset, ".", false);
            if (ret == PARSE_ERROR) {
                break;
            }

            int ret1 = parseWord(offset);
            if (ret1 == PARSE_ERROR) {
                break;
            }
            offset += ret + ret1;
            sum += ret + ret1;
        }
        return sum;
    }

    /**
     * Translate escape characters("\\", "\;") which define in vcard2.1 spec.
     * But for fault tolerance, we will translate "\:" and "\,", which isn't
     * define in vcard2.1 explicitly, as the same behavior as other client.
     *
     * @param str:
     *            the string will be translated.
     * @return the string which do not contain any escape character in vcard2.1
     */
    private String escapeTranslator(String str) {
        if (null == str)
            return null;

        String tmp = str.replace("\\\\", "\n\r\n");
        tmp = tmp.replace("\\;", ";");
        tmp = tmp.replace("\\:", ":");
        tmp = tmp.replace("\\,", ",");
        tmp = tmp.replace("\n\r\n", "\\");
        return tmp;
    }
}
