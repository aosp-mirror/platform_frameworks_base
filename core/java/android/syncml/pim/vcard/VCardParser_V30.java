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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/**
 * This class is used to parse vcard3.0. <br>
 * It get useful data refer from android contact, and alter to vCard 2.1 format.
 * Then reuse vcard 2.1 parser to analyze the result.<br>
 * Please refer to vCard Specification 3.0
 */
public class VCardParser_V30 extends VCardParser_V21 {
    private static final String V21LINEBREAKER = "\r\n";

    private static final HashSet<String> acceptablePropsWithParam = new HashSet<String>(
            Arrays.asList("PHOTO", "LOGO", "TEL", "EMAIL", "ADR"));

    private static final HashSet<String> acceptablePropsWithoutParam = new HashSet<String>(
            Arrays.asList("ORG", "NOTE", "TITLE", "FN", "N"));

    private static final HashMap<String, String> propV30ToV21Map = new HashMap<String, String>();

    static {
        propV30ToV21Map.put("PHOTO", "PHOTO");
        propV30ToV21Map.put("LOGO", "PHOTO");
    }

    @Override
    public boolean parse(InputStream is, String encoding, VBuilder builder)
            throws IOException {
        // get useful info for android contact, and alter to vCard 2.1
        byte[] bytes = new byte[is.available()];
        is.read(bytes);
        String scStr = new String(bytes);
        StringBuilder v21str = new StringBuilder("");

        String[] strlist = splitProperty(scStr);

        if ("BEGIN:vCard".equals(strlist[0])
                || "BEGIN:VCARD".equals(strlist[0])) {
            v21str.append("BEGIN:VCARD" + V21LINEBREAKER);
        } else {
            return false;
        }

        for (int i = 1; i < strlist.length - 1; i++) {// for ever property
            // line
            String propName;
            String params;
            String value;

            String line = strlist[i];
            if ("".equals(line)) { // line breaker is useful in encoding string
                v21str.append(V21LINEBREAKER);
                continue;
            }

            String[] contentline = line.split(":", 2);
            String propNameAndParam = contentline[0];
            value = (contentline.length > 1) ? contentline[1] : "";
            if (propNameAndParam.length() > 0) {
                String[] nameAndParams = propNameAndParam.split(";", 2);
                propName = nameAndParams[0];
                params = (nameAndParams.length > 1) ? nameAndParams[1] : "";

                if (acceptablePropsWithParam.contains(propName)
                        || acceptablePropsWithoutParam.contains(propName)) {
                    v21str.append(mapContentlineV30ToV21(propName, params,
                            value));
                }
            }
        }// end for

        if ("END:vCard".equals(strlist[strlist.length - 1])
                || "END:VCARD".equals(strlist[strlist.length - 1])) {
            v21str.append("END:VCARD" + V21LINEBREAKER);
        } else {
            return false;
        }

        return super.parse(
        // use vCard 2.1 parser
                new ByteArrayInputStream(v21str.toString().getBytes()),
                encoding, builder);
    }

    /**
     * Convert V30 string to V21 string
     *
     * @param propName
     *            The name of property
     * @param params
     *            parameter of property
     * @param value
     *            value of property
     * @return the converted string
     */
    private String mapContentlineV30ToV21(String propName, String params,
            String value) {
        String result;

        if (propV30ToV21Map.containsKey(propName)) {
            result = propV30ToV21Map.get(propName);
        } else {
            result = propName;
        }
        // Alter parameter part of property to vCard 2.1 format
        if (acceptablePropsWithParam.contains(propName) && params.length() > 0)
            result = result
                    + ";"
                    + params.replaceAll(",", ";").replaceAll("ENCODING=B",
                            "ENCODING=BASE64").replaceAll("ENCODING=b",
                            "ENCODING=BASE64");

        return result + ":" + value + V21LINEBREAKER;
    }

    /**
     * Split ever property line to Stringp[], not split folding line.
     *
     * @param scStr
     *            the string to be splitted
     * @return a list of splitted string
     */
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
