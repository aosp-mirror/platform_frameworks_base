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

package android.bluetooth;

import java.util.*;

/**
 * The result of execution of an single AT command.<p>
 *
 *
 * This class can represent the final response to an AT command line, and also
 * intermediate responses to a single command within a chained AT command
 * line.<p>
 *
 * The actual responses that are intended to be send in reply to the AT command
 * line are stored in a string array. The final response is stored as an
 * int enum, converted to a string when toString() is called. Only a single
 * final response is sent from multiple commands chained into a single command
 * line.<p>
 * @hide
 */
public class AtCommandResult {
    // Result code enumerations
    public static final int OK = 0;
    public static final int ERROR = 1;
    public static final int UNSOLICITED = 2;

    private static final String OK_STRING = "OK";
    private static final String ERROR_STRING = "ERROR";

    private int mResultCode;  // Result code
    private StringBuilder mResponse; // Response with CRLF line breaks

    /**
     * Construct a new AtCommandResult with given result code, and an empty
     * response array.
     * @param resultCode One of OK, ERROR or UNSOLICITED.
     */
    public AtCommandResult(int resultCode) {
        mResultCode = resultCode;
        mResponse = new StringBuilder();
    }

    /**
     * Construct a new AtCommandResult with result code OK, and the specified
     * single line response.
     * @param response The single line response.
     */
    public AtCommandResult(String response) {
        this(OK);
        addResponse(response);
    }

    public int getResultCode() {
        return mResultCode;
    }

    /**
     * Add another line to the response.
     */
    public void addResponse(String response) {
        appendWithCrlf(mResponse, response);
    }

    /**
     * Add the given result into this AtCommandResult object.<p>
     * Used to combine results from multiple commands in a single command line
     * (command chaining).
     * @param result The AtCommandResult to add to this result.
     */
    public void addResult(AtCommandResult result) {
        if (result != null) {
            appendWithCrlf(mResponse, result.mResponse.toString());
            mResultCode = result.mResultCode;
        }
    }

    /**
     * Generate the string response ready to send
     */
    public String toString() {
        StringBuilder result = new StringBuilder(mResponse.toString());
        switch (mResultCode) {
        case OK:
            appendWithCrlf(result, OK_STRING);
            break;
        case ERROR:
            appendWithCrlf(result, ERROR_STRING);
            break;
        }
        return result.toString();
    }

    /** Append a string to a string builder, joining with a double
     * CRLF. Used to create multi-line AT command replies
     */
    public static void appendWithCrlf(StringBuilder str1, String str2) {
        if (str1.length() > 0 && str2.length() > 0) {
            str1.append("\r\n\r\n");
        }
        str1.append(str2);
    }
};
