/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Parses CSV.
 * <p>
 * Call parse() with an InputStream.
 * <p>
 * CsvLineProcessor.onLine() will be called for each line in the source document.
 * <p>
 * To simplify parsing and to protect against using too much memory for bad
 * data, the maximum field length is {@link #MAX_FIELD_SIZE}.
 */
class CsvParser {
    /**
     * The maximum size of a single field in bytes.
     */
    public static final int MAX_FIELD_SIZE = (8*1024)-1;

    /**
     * Callback interface for each line of CSV as it is parsed.
     */
    interface LineProcessor {
        /**
         * A line of CSV was parsed.
         * 
         * @param lineNumber the line number in the file, starting at 1
         * @param fields the comma separated fields for the line
         */
        void onLine(int lineNumber, ArrayList<String> fields) throws ParseException;
    }

    /**
     * Parse the CSV text in input, calling onto processor for each row.
     */
    public static void parse(InputStream input, LineProcessor processor)
            throws IOException, ParseException {
        final Charset utf8 = StandardCharsets.UTF_8;
        final byte[] buf = new byte[MAX_FIELD_SIZE+1];
        int lineNumber = 1;
        int readPos = 0;
        int prev = 0;
        ArrayList<String> fields = new ArrayList<String>();
        boolean finalBuffer = false;
        boolean escaping = false;
        boolean sawQuote = false;

        while (!finalBuffer) {
            int amt = input.read(buf, readPos, buf.length-readPos);
            if (amt < 0) {
                // No more data. Process whatever's left from before.
                amt = readPos;
                finalBuffer = true;
            } else {
                // Process whatever's left from before, plus the new data.
                amt += readPos;
                finalBuffer = false;
            }

            // Process as much of this buffer as we can.
            int fieldStart = 0;
            int index = readPos;
            int escapeIndex = escaping ? readPos : -1;
            while (index < amt) {
                byte c = buf[index];
                if (c == '\r' || c == '\n') {
                    if (escaping) {
                        // TODO: Quotes do not escape newlines in our CSV dialect,
                        // but we actually see some data where it should.
                        fields.add(new String(buf, fieldStart, escapeIndex-fieldStart));
                        escapeIndex = -1;
                        escaping = false;
                        sawQuote = false;
                    } else {
                        fields.add(new String(buf, fieldStart, index-fieldStart));
                    }
                    // Don't report blank lines
                    if (fields.size() > 1 || (fields.size() == 1 && fields.get(0).length() > 0)) {
                        processor.onLine(lineNumber, fields);
                    }
                    fields = new ArrayList<String>();
                    if (!(c == '\n' && prev == '\r')) {
                        // Don't double increment for dos line endings.
                        lineNumber++;
                    }
                    fieldStart = index = index + 1;
                } else {
                    if (escaping) {
                        // Field started with a " so quotes are escaped with " and commas
                        // don't matter except when following a single quote.
                        if (c == '"') {
                            if (sawQuote) {
                                buf[escapeIndex] = buf[index];
                                escapeIndex++;
                                sawQuote = false;
                            } else {
                                sawQuote = true;
                            }
                            index++;
                        } else if (sawQuote && c == ',') {
                            fields.add(new String(buf, fieldStart, escapeIndex-fieldStart));
                            fieldStart = index = index + 1;
                            escapeIndex = -1;
                            escaping = false;
                            sawQuote = false;
                        } else {
                            buf[escapeIndex] = buf[index];
                            escapeIndex++;
                            index++;
                            sawQuote = false;
                        }
                    } else {
                        if (c == ',') {
                            fields.add(new String(buf, fieldStart, index-fieldStart));
                            fieldStart = index + 1;
                        } else if (c == '"' && fieldStart == index) {
                            // First character is a "
                            escaping = true;
                            fieldStart = escapeIndex = index + 1;
                        }
                        index++;
                    }
                }
                prev = c;
            }

            // A single field is greater than buf.length, so fail.
            if (fieldStart == 0 && index == buf.length) {
                throw new ParseException(lineNumber, "Line is too long: "
                        + new String(buf, 0, 20, utf8) + "...");
            }

            // Move whatever we didn't process to the beginning of the buffer
            // and try again.
            if (fieldStart != amt) {
                readPos = (escaping ? escapeIndex : index) - fieldStart;
                System.arraycopy(buf, fieldStart, buf, 0, readPos);
            } else {
                readPos = 0;
            }
        
            // Process whatever's left over
            if (finalBuffer) {
                fields.add(new String(buf, 0, readPos));
                // If there is any content, return the last line.
                if (fields.size() > 1 || (fields.size() == 1 && fields.get(0).length() > 0)) {
                    processor.onLine(lineNumber, fields);
                }
            }
        }
    }
}
