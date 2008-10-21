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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;

import android.syncml.pim.VBuilder;

public class VCalParser_V20 extends VCalParser_V10 {
    private static final String V10LINEBREAKER = "\r\n";

    private static final HashSet<String> acceptableComponents = new HashSet<String>(
            Arrays.asList("VEVENT", "VTODO", "VALARM", "VTIMEZONE"));

    private static final HashSet<String> acceptableV20Props = new HashSet<String>(
            Arrays.asList("DESCRIPTION", "DTEND", "DTSTART", "DUE",
                    "COMPLETED", "RRULE", "STATUS", "SUMMARY", "LOCATION"));

    private boolean hasTZ = false; // MUST only have one TZ property

    private String[] lines;

    private int index;

    @Override
    public boolean parse(InputStream is, String encoding, VBuilder builder)
            throws IOException {
        // get useful info for android calendar, and alter to vcal 1.0
        byte[] bytes = new byte[is.available()];
        is.read(bytes);
        String scStr = new String(bytes);
        StringBuilder v10str = new StringBuilder("");

        lines = splitProperty(scStr);
        index = 0;

        if ("BEGIN:VCALENDAR".equals(lines[index]))
            v10str.append("BEGIN:VCALENDAR" + V10LINEBREAKER);
        else
            return false;
        index++;
        if (false == parseV20Calbody(lines, v10str)
                || index > lines.length - 1)
            return false;

        if (lines.length - 1 == index && "END:VCALENDAR".equals(lines[index]))
            v10str.append("END:VCALENDAR" + V10LINEBREAKER);
        else
            return false;

        return super.parse(
                // use vCal 1.0 parser
                new ByteArrayInputStream(v10str.toString().getBytes()),
                encoding, builder);
    }

    /**
     * Parse and pick acceptable iCalendar body and translate it to
     * calendarV1.0 format.
     * @param lines iCalendar components/properties line list.
     * @param buffer calendarV10 format string buffer
     * @return true for success, or false
     */
    private boolean parseV20Calbody(String[] lines, StringBuilder buffer) {
        try {
            while (!"VERSION:2.0".equals(lines[index]))
                index++;
            buffer.append("VERSION:1.0" + V10LINEBREAKER);

            index++;
            for (; index < lines.length - 1; index++) {
                String[] keyAndValue = lines[index].split(":", 2);
                String key = keyAndValue[0];
                String value = keyAndValue[1];

                if ("BEGIN".equals(key.trim())) {
                    if (!key.equals(key.trim()))
                        return false; // MUST be "BEGIN:componentname"
                    index++;
                    if (false == parseV20Component(value, buffer))
                        return false;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }

        return true;
    }

    /**
     * Parse and pick acceptable calendar V2.0's component and translate it to
     * V1.0 format.
     * @param compName component name
     * @param buffer calendarV10 format string buffer
     * @return true for success, or false
     * @throws ArrayIndexOutOfBoundsException
     */
    private boolean parseV20Component(String compName, StringBuilder buffer)
            throws ArrayIndexOutOfBoundsException {
        String endTag = "END:" + compName;
        String[] propAndValue;
        String propName, value;

        if (acceptableComponents.contains(compName)) {
            if ("VEVENT".equals(compName) || "VTODO".equals(compName)) {
                buffer.append("BEGIN:" + compName + V10LINEBREAKER);
                while (!endTag.equals(lines[index])) {
                    propAndValue = lines[index].split(":", 2);
                    propName = propAndValue[0].split(";", 2)[0];
                    value = propAndValue[1];

                    if ("".equals(lines[index]))
                        buffer.append(V10LINEBREAKER);
                    else if (acceptableV20Props.contains(propName)) {
                        buffer.append(propName + ":" + value + V10LINEBREAKER);
                    } else if ("BEGIN".equals(propName.trim())) {
                        // MUST be BEGIN:VALARM
                        if (propName.equals(propName.trim())
                                && "VALARM".equals(value)) {
                            buffer.append("AALARM:default" + V10LINEBREAKER);
                            while (!"END:VALARM".equals(lines[index]))
                                index++;
                        } else
                            return false;
                    }
                    index++;
                } // end while
                buffer.append(endTag + V10LINEBREAKER);
            } else if ("VALARM".equals(compName)) { // VALARM component MUST
                // only appear within either VEVENT or VTODO
                return false;
            } else if ("VTIMEZONE".equals(compName)) {
                do {
                    if (false == hasTZ) {// MUST only have 1 time TZ property
                        propAndValue = lines[index].split(":", 2);
                        propName = propAndValue[0].split(";", 2)[0];

                        if ("TZOFFSETFROM".equals(propName)) {
                            value = propAndValue[1];
                            buffer.append("TZ" + ":" + value + V10LINEBREAKER);
                            hasTZ = true;
                        }
                    }
                    index++;
                } while (!endTag.equals(lines[index]));
            } else
                return false;
        } else {
            while (!endTag.equals(lines[index]))
                index++;
        }

        return true;
    }

    /** split ever property line to String[], not split folding line. */
    private String[] splitProperty(String scStr) {
        /*
         * Property splitted by \n, and unfold folding lines by removing
         * CRLF+LWSP-char
         */
        scStr = scStr.replaceAll("\r\n", "\n").replaceAll("\n ", "")
                .replaceAll("\n\t", "");
        String[] strs = scStr.split("\n");
        return strs;
    }
}
