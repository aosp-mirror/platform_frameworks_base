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

package android.syncml.pim.vcalendar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.syncml.pim.VParser;

public class VCalParser_V10 extends VParser {

    /*
     * The names of the properties whose value are not separated by ";"
     */
    private static final HashSet<String> mEvtPropNameGroup1 = new HashSet<String>(
            Arrays.asList("ATTACH", "ATTENDEE", "DCREATED", "COMPLETED",
                    "DESCRIPTION", "DUE", "DTEND", "EXRULE", "LAST-MODIFIED",
                    "LOCATION", "RNUM", "PRIORITY", "RELATED-TO", "RRULE",
                    "SEQUENCE", "DTSTART", "SUMMARY", "TRANSP", "URL", "UID",
                    // above belong to simprop
                    "CLASS", "STATUS"));

    /*
     * The names of properties whose value are separated by ";"
     */
    private static final HashSet<String> mEvtPropNameGroup2 = new HashSet<String>(
            Arrays.asList("AALARM", "CATEGORIES", "DALARM", "EXDATE", "MALARM",
                    "PALARM", "RDATE", "RESOURCES"));

    private static final HashSet<String> mValueCAT = new HashSet<String>(Arrays
            .asList("APPOINTMENT", "BUSINESS", "EDUCATION", "HOLIDAY",
                    "MEETING", "MISCELLANEOUS", "PERSONAL", "PHONE CALL",
                    "SICK DAY", "SPECIAL OCCASION", "TRAVEL", "VACATION"));

    private static final HashSet<String> mValueCLASS = new HashSet<String>(Arrays
            .asList("PUBLIC", "PRIVATE", "CONFIDENTIAL"));

    private static final HashSet<String> mValueRES = new HashSet<String>(Arrays
            .asList("CATERING", "CHAIRS", "EASEL", "PROJECTOR", "VCR",
                    "VEHICLE"));

    private static final HashSet<String> mValueSTAT = new HashSet<String>(Arrays
            .asList("ACCEPTED", "NEEDS ACTION", "SENT", "TENTATIVE",
                    "CONFIRMED", "DECLINED", "COMPLETED", "DELEGATED"));

    /*
     * The names of properties whose value can contain escape characters
     */
    private static final HashSet<String> mEscAllowedProps = new HashSet<String>(
            Arrays.asList("DESCRIPTION", "SUMMARY", "AALARM", "DALARM",
                    "MALARM", "PALARM"));

    private static final HashMap<String, HashSet<String>> mSpecialValueSetMap =
        new HashMap<String, HashSet<String>>();

    static {
        mSpecialValueSetMap.put("CATEGORIES", mValueCAT);
        mSpecialValueSetMap.put("CLASS", mValueCLASS);
        mSpecialValueSetMap.put("RESOURCES", mValueRES);
        mSpecialValueSetMap.put("STATUS", mValueSTAT);
    }

    public VCalParser_V10() {
    }

    protected int parseVFile(int offset) {
        return parseVCalFile(offset);
    }

    private int parseVCalFile(int offset) {
        int ret = 0, sum = 0;

        /* remove wsls */
        while (PARSE_ERROR != (ret = parseWsls(offset))) {
            offset += ret;
            sum += ret;
        }

        ret = parseVCal(offset); // BEGIN:VCAL ... END:VCAL
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
        } else {
            return PARSE_ERROR;
        }

        /* remove wsls */
        while (PARSE_ERROR != (ret = parseWsls(offset))) {
            offset += ret;
            sum += ret;
        }
        return sum;
    }

    /**
     * "BEGIN" [ws] ":" [ws] "VCALENDAR" [ws] 1*crlf calprop calentities [ws]
     * *crlf "END" [ws] ":" [ws] "VCALENDAR" [ws] 1*CRLF
     */
    private int parseVCal(int offset) {
        int ret = 0, sum = 0;

        /* BEGIN */
        ret = parseString(offset, "BEGIN", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VCALENDAR
        ret = parseString(offset, "VCALENDAR", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.startRecord("VCALENDAR");
        }

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // 1*CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        // calprop
        ret = parseCalprops(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // calentities
        ret = parseCalentities(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // *CRLF
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        // "END"
        ret = parseString(offset, "END", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VCALENDAR"
        ret = parseString(offset, "VCALENDAR", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endRecord();
        }

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // 1 * CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        return sum;
    }

    /**
     * calprops * CRLF calprop / calprop
     */
    private int parseCalprops(int offset) {
        int ret = 0, sum = 0;

        if (mBuilder != null) {
            mBuilder.startProperty();
        }
        ret = parseCalprop(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endProperty();
        }

        for (;;) {
            /* *CRLF */
            while (PARSE_ERROR != (ret = parseCrlf(offset))) {
                offset += ret;
                sum += ret;
            }
            // follow VEVENT ,it wont reach endProperty
            if (mBuilder != null) {
                mBuilder.startProperty();
            }
            ret = parseCalprop(offset);
            if (PARSE_ERROR == ret) {
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
     * calentities *CRLF calentity / calentity
     */
    private int parseCalentities(int offset) {
        int ret = 0, sum = 0;

        ret = parseCalentity(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        for (;;) {
            /* *CRLF */
            while (PARSE_ERROR != (ret = parseCrlf(offset))) {
                offset += ret;
                sum += ret;
            }

            ret = parseCalentity(offset);
            if (PARSE_ERROR == ret) {
                break;
            }
            offset += ret;
            sum += ret;
        }

        return sum;
    }

    /**
     * calprop = DAYLIGHT/ GEO/ PRODID/ TZ/ VERSION
     */
    private int parseCalprop(int offset) {
        int ret = 0;

        ret = parseCalprop0(offset, "DAYLIGHT");
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseCalprop0(offset, "GEO");
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseCalprop0(offset, "PRODID");
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseCalprop0(offset, "TZ");
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseCalprop1(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        return PARSE_ERROR;
    }

    /**
     * evententity / todoentity
     */
    private int parseCalentity(int offset) {
        int ret = 0;

        ret = parseEvententity(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseTodoentity(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        return PARSE_ERROR;

    }

    /**
     * propName [params] ":" value CRLF
     */
    private int parseCalprop0(int offset, String propName) {
        int ret = 0, sum = 0, start = 0;

        ret = parseString(offset, propName, true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName(propName);
        }

        ret = parseParams(offset);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseValue(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            ArrayList<String> v = new ArrayList<String>();
            v.add(mBuffer.substring(start, offset));
            mBuilder.propertyValues(v);
        }

        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /**
     * "VERSION" [params] ":" "1.0" CRLF
     */
    private int parseCalprop1(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "VERSION", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName("VERSION");
        }

        ret = parseParams(offset);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = parseString(offset, "1.0", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            ArrayList<String> v = new ArrayList<String>();
            v.add("1.0");
            mBuilder.propertyValues(v);
        }
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /**
     * "BEGIN" [ws] ":" [ws] "VEVENT" [ws] 1*CRLF entprops [ws] *CRLF "END" [ws]
     * ":" [ws] "VEVENT" [ws] 1*CRLF
     */
    private int parseEvententity(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "BEGIN", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VEVNET"
        ret = parseString(offset, "VEVENT", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.startRecord("VEVENT");
        }

        /* [ws] */
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // 1*CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        ret = parseEntprops(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // *CRLF
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        // "END"
        ret = parseString(offset, "END", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VEVENT"
        ret = parseString(offset, "VEVENT", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endRecord();
        }

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // 1 * CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        return sum;
    }

    /**
     * "BEGIN" [ws] ":" [ws] "VTODO" [ws] 1*CRLF entprops [ws] *CRLF "END" [ws]
     * ":" [ws] "VTODO" [ws] 1*CRLF
     */
    private int parseTodoentity(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "BEGIN", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VTODO"
        ret = parseString(offset, "VTODO", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.startRecord("VTODO");
        }

        // 1*CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        ret = parseEntprops(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // *CRLF
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        // "END"
        ret = parseString(offset, "END", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // ":"
        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // "VTODO"
        ret = parseString(offset, "VTODO", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endRecord();
        }

        // [ws]
        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        // 1 * CRLF
        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        while (PARSE_ERROR != (ret = parseCrlf(offset))) {
            offset += ret;
            sum += ret;
        }

        return sum;
    }

    /**
     * entprops *CRLF entprop / entprop
     */
    private int parseEntprops(int offset) {
        int ret = 0, sum = 0;
        if (mBuilder != null) {
            mBuilder.startProperty();
        }

        ret = parseEntprop(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.endProperty();
        }

        for (;;) {
            while (PARSE_ERROR != (ret = parseCrlf(offset))) {
                offset += ret;
                sum += ret;
            }
            if (mBuilder != null) {
                mBuilder.startProperty();
            }

            ret = parseEntprop(offset);
            if (PARSE_ERROR == ret) {
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
     * for VEVENT,VTODO prop. entprop0 / entprop1
     */
    private int parseEntprop(int offset) {
        int ret = 0;
        ret = parseEntprop0(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseEntprop1(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        return PARSE_ERROR;
    }

    /**
     * Same with card. ";" [ws] paramlist
     */
    private int parseParams(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, ";", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseParamlist(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;

        return sum;
    }

    /**
     * Same with card. paramlist [ws] ";" [ws] param / param
     */
    private int parseParamlist(int offset) {
        int ret = 0, sum = 0;

        ret = parseParam(offset);
        if (PARSE_ERROR == ret) {
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
            if (PARSE_ERROR == ret) {
                return sum;
            }
            offsetTemp += ret;
            sumTemp += ret;

            ret = removeWs(offsetTemp);
            offsetTemp += ret;
            sumTemp += ret;

            ret = parseParam(offsetTemp);
            if (PARSE_ERROR == ret) {
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
     * param0 - param7 / knowntype
     */
    private int parseParam(int offset) {
        int ret = 0;

        ret = parseParam0(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam1(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam2(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam3(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam4(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam5(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam6(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseParam7(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        int start = offset;
        ret = parseKnownType(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamType(null);
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return ret;
    }

    /**
     * simprop AND "CLASS" AND "STATUS" The value of these properties are not
     * seperated by ";"
     *
     * [ws] simprop [params] ":" value CRLF
     */
    private int parseEntprop0(int offset) {
        int ret = 0, sum = 0, start = 0;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        String propName = getWord(offset).toUpperCase();
        if (!mEvtPropNameGroup1.contains(propName)) {
            if (PARSE_ERROR == parseXWord(offset))
                return PARSE_ERROR;
        }
        ret = propName.length();
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName(propName);
        }

        ret = parseParams(offset);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseValue(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            ArrayList<String> v = new ArrayList<String>();
            v.add(exportEntpropValue(propName, mBuffer.substring(start,
                            offset)));
            mBuilder.propertyValues(v);
            // Filter value,match string, REFER:RFC
            if (PARSE_ERROR == valueFilter(propName, v))
                return PARSE_ERROR;
        }

        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;
        return sum;
    }

    /**
     * other event prop names except simprop AND "CLASS" AND "STATUS" The value
     * of these properties are seperated by ";" [ws] proper name [params] ":"
     * value CRLF
     */
    private int parseEntprop1(int offset) {
        int ret = 0, sum = 0;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        String propName = getWord(offset).toUpperCase();
        if (!mEvtPropNameGroup2.contains(propName)) {
            return PARSE_ERROR;
        }
        ret = propName.length();
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyName(propName);
        }

        ret = parseParams(offset);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, ":", false);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        int start = offset;
        ret = parseValue(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        // mutil-values
        if (mBuilder != null) {
            int end = 0;
            ArrayList<String> v = new ArrayList<String>();
            Pattern p = Pattern
                    .compile("([^;\\\\]*(\\\\[\\\\;:,])*[^;\\\\]*)(;?)");
            Matcher m = p.matcher(mBuffer.substring(start, offset));
            while (m.find()) {
                String s = exportEntpropValue(propName, m.group(1));
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
            // Filter value,match string, REFER:RFC
            if (PARSE_ERROR == valueFilter(propName, v))
                return PARSE_ERROR;
        }

        ret = parseCrlf(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;
        return sum;
    }

    /**
     * "TYPE" [ws] = [ws] ptypeval
     */
    private int parseParam0(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "TYPE", true);
        if (PARSE_ERROR == ret) {
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
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePtypeval(offset);
        if (PARSE_ERROR == ret) {
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
     * ["VALUE" [ws] "=" [ws]] pvalueval
     */
    private int parseParam1(int offset) {
        int ret = 0, sum = 0, start = offset;
        boolean flag = false;

        ret = parseString(offset, "VALUE", true);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
            flag = true;
        }
        if (flag == true && mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR != ret) {
            if (flag == false) { // "VALUE" does not exist
                return PARSE_ERROR;
            }
            offset += ret;
            sum += ret;
        } else {
            if (flag == true) { // "VALUE" exists
                return PARSE_ERROR;
            }
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePValueVal(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;
    }

    /** ["ENCODING" [ws] "=" [ws]] pencodingval */
    private int parseParam2(int offset) {
        int ret = 0, sum = 0, start = offset;
        boolean flag = false;

        ret = parseString(offset, "ENCODING", true);
        if (PARSE_ERROR != ret) {
            offset += ret;
            sum += ret;
            flag = true;
        }
        if (flag == true && mBuilder != null) {
            mBuilder.propertyParamType(mBuffer.substring(start, offset));
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR != ret) {
            if (flag == false) { // "VALUE" does not exist
                return PARSE_ERROR;
            }
            offset += ret;
            sum += ret;
        } else {
            if (flag == true) { // "VALUE" exists
                return PARSE_ERROR;
            }
        }

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parsePEncodingVal(offset);
        if (PARSE_ERROR == ret) {
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
     * "CHARSET" [WS] "=" [WS] charsetval
     */
    private int parseParam3(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "CHARSET", true);
        if (PARSE_ERROR == ret) {
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

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseCharsetVal(offset);
        if (PARSE_ERROR == ret) {
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
     * "LANGUAGE" [ws] "=" [ws] langval
     */
    private int parseParam4(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "LANGUAGE", true);
        if (PARSE_ERROR == ret) {
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

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseLangVal(offset);
        if (PARSE_ERROR == ret) {
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
     * "ROLE" [ws] "=" [ws] roleval
     */
    private int parseParam5(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "ROLE", true);
        if (PARSE_ERROR == ret) {
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

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseRoleVal(offset);
        if (PARSE_ERROR == ret) {
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
     * "STATUS" [ws] = [ws] statuval
     */
    private int parseParam6(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseString(offset, "STATUS", true);
        if (PARSE_ERROR == ret) {
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

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseStatuVal(offset);
        if (PARSE_ERROR == ret) {
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
     * XWord [ws] "=" [ws] word
     */
    private int parseParam7(int offset) {
        int ret = 0, sum = 0, start = offset;

        ret = parseXWord(offset);
        if (PARSE_ERROR == ret) {
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

        ret = parseString(offset, "=", true);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        start = offset;
        ret = parseWord(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;
        if (mBuilder != null) {
            mBuilder.propertyParamValue(mBuffer.substring(start, offset));
        }

        return sum;

    }

    /*
     * "WAVE" / "PCM" / "VCARD" / XWORD
     */
    private int parseKnownType(int offset) {
        int ret = 0;

        ret = parseString(offset, "WAVE", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "PCM", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "VCARD", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseXWord(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        return PARSE_ERROR;
    }

    /*
     * knowntype / Xword
     */
    private int parsePtypeval(int offset) {
        int ret = 0;

        ret = parseKnownType(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseXWord(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        return PARSE_ERROR;
    }

    /**
     * "ATTENDEE" / "ORGANIZER" / "OWNER" / XWORD
     */
    private int parseRoleVal(int offset) {
        int ret = 0;

        ret = parseString(offset, "ATTENDEE", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "ORGANIZER", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "OWNER", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseXWord(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        return PARSE_ERROR;
    }

    /**
     * "ACCEPTED" / "NEED ACTION" / "SENT" / "TENTATIVE" / "CONFIRMED" /
     * "DECLINED" / "COMPLETED" / "DELEGATED / XWORD
     */
    private int parseStatuVal(int offset) {
        int ret = 0;

        ret = parseString(offset, "ACCEPTED", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "NEED ACTION", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseString(offset, "TENTATIVE", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        ret = parseString(offset, "CONFIRMED", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        ret = parseString(offset, "DECLINED", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        ret = parseString(offset, "COMPLETED", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }
        ret = parseString(offset, "DELEGATED", true);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        ret = parseXWord(offset);
        if (PARSE_ERROR != ret) {
            return ret;
        }

        return PARSE_ERROR;
    }

    /**
     * Check 4 special propName and it's value to match Hash.
     *
     * @return PARSE_ERROR:value not match. 1:go on,like nothing happen.
     */
    private int valueFilter(String propName, ArrayList<String> values) {
        if (propName == null || propName.equals("") || values == null
                || values.isEmpty())
            return 1; // go on, like nothing happen.

        if (mSpecialValueSetMap.containsKey(propName)) {
            for (String value : values) {
                if (!mSpecialValueSetMap.get(propName).contains(value)) {
                    if (!value.startsWith("X-"))
                        return PARSE_ERROR;
                }
            }
        }

        return 1;
    }

    /**
     *
     * Translate escape characters("\\", "\;") which define in vcalendar1.0
     * spec. But for fault tolerance, we will translate "\:" and "\,", which
     * isn't define in vcalendar1.0 explicitly, as the same behavior as other
     * client.
     *
     * Though vcalendar1.0 spec does not defined the value of property
     * "description", "summary", "aalarm", "dalarm", "malarm" and "palarm" could
     * contain escape characters, we do support escape characters in these
     * properties.
     *
     * @param str:
     *            the value string will be translated.
     * @return the string which do not contain any escape character in
     *         vcalendar1.0
     */
    private String exportEntpropValue(String propName, String str) {
        if (null == propName || null == str)
            return null;
        if ("".equals(propName) || "".equals(str))
            return "";

        if (!mEscAllowedProps.contains(propName))
            return str;

        String tmp = str.replace("\\\\", "\n\r\n");
        tmp = tmp.replace("\\;", ";");
        tmp = tmp.replace("\\:", ":");
        tmp = tmp.replace("\\,", ",");
        tmp = tmp.replace("\n\r\n", "\\");
        return tmp;
    }
}
