/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony;

import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods for parsing parts of {@link android.telephony.ims.SipMessage}s.
 * See RFC 3261 for more information.
 * @hide
 */
// Note: This is lightweight in order to avoid a full SIP stack import in frameworks/base.
public class SipMessageParsingUtils {
    private static final String TAG = "SipMessageParsingUtils";
    // "Method" in request-line
    // Request-Line = Method SP Request-URI SP SIP-Version CRLF
    private static final String[] SIP_REQUEST_METHODS = new String[] {"INVITE", "ACK", "OPTIONS",
            "BYE", "CANCEL", "REGISTER", "PRACK", "SUBSCRIBE", "NOTIFY", "PUBLISH", "INFO", "REFER",
            "MESSAGE", "UPDATE"};

    // SIP Version 2.0 (corresponding to RCS 3261), set in "SIP-Version" of Status-Line and
    // Request-Line
    //
    // Request-Line = Method SP Request-URI SP SIP-Version CRLF
    // Status-Line = SIP-Version SP Status-Code SP Reason-Phrase CRLF
    private static final String SIP_VERSION_2 = "SIP/2.0";

    // headers are formatted Key:Value
    private static final String HEADER_KEY_VALUE_SEPARATOR = ":";
    // Multiple of the same header can be concatenated and put into one header Key:Value pair, for
    // example "v: XX1;branch=YY1,XX2;branch=YY2". This needs to be treated as two "v:" headers.
    private static final String SUBHEADER_VALUE_SEPARATOR = ",";

    // SIP header parameters have the format ";paramName=paramValue"
    private static final String PARAM_SEPARATOR = ";";
    // parameters are formatted paramName=ParamValue
    private static final String PARAM_KEY_VALUE_SEPARATOR = "=";

    // The via branch parameter definition
    private static final String BRANCH_PARAM_KEY = "branch";

    // via header key
    private static final String VIA_SIP_HEADER_KEY = "via";
    // compact form of the via header key
    private static final String VIA_SIP_HEADER_KEY_COMPACT = "v";

    // call-id header key
    private static final String CALL_ID_SIP_HEADER_KEY = "call-id";
    // compact form of the call-id header key
    private static final String CALL_ID_SIP_HEADER_KEY_COMPACT = "i";

    /**
     * @return true if the SIP message start line is considered a request (based on known request
     * methods).
     */
    public static boolean isSipRequest(String startLine) {
        String[] splitLine = splitStartLineAndVerify(startLine);
        if (splitLine == null) return false;
        return verifySipRequest(splitLine);
    }

    /**
     * Return the via branch parameter, which is used to identify the transaction ID (request and
     * response pair) in a SIP transaction.
     * @param headerString The string containing the headers of the SIP message.
     */
    public static String getTransactionId(String headerString) {
        // search for Via: or v: parameter, we only care about the first one.
        List<Pair<String, String>> headers = parseHeaders(headerString, true,
                VIA_SIP_HEADER_KEY, VIA_SIP_HEADER_KEY_COMPACT);
        for (Pair<String, String> header : headers) {
            // Headers can also be concatenated together using a "," between each header value.
            // format becomes v: XX1;branch=YY1,XX2;branch=YY2. Need to extract only the first ID's
            // branch param YY1.
            String[] subHeaders = header.second.split(SUBHEADER_VALUE_SEPARATOR);
            for (String subHeader : subHeaders) {
                // Search for ;branch=z9hG4bKXXXXXX and return parameter value
                String[] params = subHeader.split(PARAM_SEPARATOR);
                if (params.length < 2) {
                    // This param doesn't include a branch param, move to next param.
                    Log.w(TAG, "getTransactionId: via detected without branch param:"
                            + subHeader);
                    continue;
                }
                // by spec, each param can only appear once in a header.
                for (String param : params) {
                    String[] pair = param.split(PARAM_KEY_VALUE_SEPARATOR);
                    if (pair.length < 2) {
                        // ignore info before the first parameter
                        continue;
                    }
                    if (pair.length > 2) {
                        Log.w(TAG,
                                "getTransactionId: unexpected parameter" + Arrays.toString(pair));
                    }
                    // Trim whitespace in parameter
                    pair[0] = pair[0].trim();
                    pair[1] = pair[1].trim();
                    if (BRANCH_PARAM_KEY.equalsIgnoreCase(pair[0])) {
                        // There can be multiple "Via" headers in the SIP message, however we want
                        // to return the first once found, as this corresponds with the transaction
                        // that is relevant here.
                        return pair[1];
                    }
                }
            }
        }
        return null;
    }

    /**
     * Return the call-id header key's associated value.
     * @param headerString The string containing the headers of the SIP message.
     */
    public static String getCallId(String headerString) {
        // search for the call-Id header, there should only be one in the header.
        List<Pair<String, String>> headers = parseHeaders(headerString, true,
                CALL_ID_SIP_HEADER_KEY, CALL_ID_SIP_HEADER_KEY_COMPACT);
        return !headers.isEmpty() ? headers.get(0).second : null;
    }

    private static String[] splitStartLineAndVerify(String startLine) {
        String[] splitLine = startLine.split(" ");
        if (isStartLineMalformed(splitLine)) return null;
        return splitLine;
    }

    private static boolean isStartLineMalformed(String[] startLine) {
        if (startLine == null || startLine.length == 0)  {
            return true;
        }
        // start lines contain three segments separated by spaces (SP):
        // Request-Line  =  Method SP Request-URI SP SIP-Version CRLF
        // Status-Line  =  SIP-Version SP Status-Code SP Reason-Phrase CRLF
        return (startLine.length != 3);
    }

    private static boolean verifySipRequest(String[] request) {
        // Request-Line  =  Method SP Request-URI SP SIP-Version CRLF
        boolean verified = request[2].contains(SIP_VERSION_2);
        verified &= (Uri.parse(request[1]).getScheme() != null);
        verified &= Arrays.stream(SIP_REQUEST_METHODS).anyMatch(s -> request[0].contains(s));
        return verified;
    }

    private static boolean verifySipResponse(String[] response) {
        // Status-Line = SIP-Version SP Status-Code SP Reason-Phrase CRLF
        boolean verified = response[0].contains(SIP_VERSION_2);
        int statusCode = Integer.parseInt(response[1]);
        verified &= (statusCode >= 100  && statusCode < 700);
        return verified;
    }

    /**
     * Parse a String representation of the Header portion of the SIP Message and re-structure it
     * into a List of key->value pairs representing each header in the order that they appeared in
     * the message.
     *
     * @param headerString The raw string containing all headers
     * @param stopAtFirstMatch Return early when the first match is found from matching header keys.
     * @param matchingHeaderKeys An optional list of Strings containing header keys that should be
     *                           returned if they exist. If none exist, all keys will be returned.
     *                           (This is internally an equalsIgnoreMatch comparison).
     * @return the matched header keys and values.
     */
    private static List<Pair<String, String>> parseHeaders(String headerString,
            boolean stopAtFirstMatch, String... matchingHeaderKeys) {
        // Ensure there is no leading whitespace
        headerString = removeLeadingWhitespace(headerString);

        List<Pair<String, String>> result = new ArrayList<>();
        // Split the string line-by-line.
        String[] headerLines = headerString.split("\\r?\\n");
        if (headerLines.length == 0) {
            return Collections.emptyList();
        }

        String headerKey = null;
        StringBuilder headerValueSegment = new StringBuilder();
        // loop through each line, either parsing a "key: value" pair or appending values that span
        // multiple lines.
        for (String line : headerLines) {
            // This line is a continuation of the last line if it starts with whitespace or tab
            if (line.startsWith("\t") || line.startsWith(" ")) {
                headerValueSegment.append(removeLeadingWhitespace(line));
                continue;
            }
            // This line is the start of a new key, If headerKey/value is already populated from a
            // previous key/value pair, add it to list of parsed header pairs.
            if (headerKey != null) {
                final String key = headerKey;
                if (matchingHeaderKeys == null || matchingHeaderKeys.length == 0
                        || Arrays.stream(matchingHeaderKeys).anyMatch(
                                (s) -> s.equalsIgnoreCase(key))) {
                    result.add(new Pair<>(key, headerValueSegment.toString()));
                    if (stopAtFirstMatch) {
                        return result;
                    }
                }
                headerKey = null;
                headerValueSegment = new StringBuilder();
            }

            // Format is "Key:Value", ignore any ":" after the first.
            String[] pair = line.split(HEADER_KEY_VALUE_SEPARATOR, 2);
            if (pair.length < 2) {
                // malformed line, skip
                Log.w(TAG, "parseHeaders - received malformed line: " + line);
                continue;
            }

            headerKey = pair[0].trim();
            for (int i = 1; i < pair.length; i++) {
                headerValueSegment.append(removeLeadingWhitespace(pair[i]));
            }
        }
        // Pick up the last pending header being parsed, if it exists.
        if (headerKey != null) {
            final String key = headerKey;
            if (matchingHeaderKeys == null || matchingHeaderKeys.length == 0
                    || Arrays.stream(matchingHeaderKeys).anyMatch(
                            (s) -> s.equalsIgnoreCase(key))) {
                result.add(new Pair<>(key, headerValueSegment.toString()));
            }
        }

        return result;
    }

    private static String removeLeadingWhitespace(String line) {
        return line.replaceFirst("^\\s*", "");
    }
}
