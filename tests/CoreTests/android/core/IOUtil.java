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

package android.core;

import java.io.InputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;

public final class IOUtil {

    private IOUtil() {
    }

    /**
     * returns the content of an InputStream as a String.
     *
     * @param a the input stream.
     * @return the string
     * @throws java.io.IOException
     */
    public static String read(InputStream a) throws IOException {
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            r = a.read();
            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }

    /**
     * reads characters from a reader and returns them as a string.
     *
     * @param a the reader.
     * @return the string.
     * @throws IOException
     */
    public static String read(Reader a) throws IOException {
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            r = a.read();
            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }

    /**
     * returns the content of an InputStream as a String. It reads x characters.
     *
     * @param a the input stream.
     * @param x number of characters to read.
     * @return the string
     * @throws IOException
     */
    public static String read(InputStream a, int x) throws IOException {
        byte[] b = new byte[x];
        int len = a.read(b, 0, x);
        if (len < 0) {
            return "";
        }
        return new String(b, 0, len);
    }

    /**
     * reads a number of characters from a reader and returns them as a string.
     *
     * @param a the reader.
     * @param x the number of characters to read.
     * @return the string.
     * @throws IOException
     */
    public static String read(Reader a, int x) throws IOException {
        char[] b = new char[x];
        int len = a.read(b, 0, x);
        if (len < 0) {
            return "";
        }
        return new String(b, 0, len);
    }

    /**
     * returns the content of the input stream as a String. It only appends
     * every second character.
     *
     * @param a the input stream.
     * @return the string created from every second character of the input stream.
     * @throws IOException
     */
    public static String skipRead(InputStream a) throws IOException {
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            a.skip(1);
            r = a.read();
            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }

    /**
     * reads every second characters from a reader and returns them as a string.
     *
     * @param a the reader.
     * @return the string.
     * @throws IOException
     */
    public static String skipRead(Reader a) throws IOException {
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            a.skip(1);
            r = a.read();
            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }

    /**
     * reads characters from a InputStream, skips back y characters and continues
     * reading from that new position up to the end.
     *
     * @param a the InputStream.
     * @param x the position of the mark. the marks position is x+y
     * @param y the number of characters to jump back after the position x+y was reached.
     * @return the string.
     * @throws IOException
     */
    public static String markRead(InputStream a, int x, int y) throws IOException {
        int m = 0;
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            m++;
            r = a.read();
            if (m == x)
                a.mark((x + y));
            if (m == (x + y))
                a.reset();

            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }

    /**
     * reads characters from a reader, skips back y characters and continues
     * reading from that new position up to the end.
     *
     * @param a the reader.
     * @param x the position of the mark. the marks position is x+y
     * @param y the number of characters to jump back after the position x+y was reached.
     * @return the string.
     * @throws IOException
     */
    public static String markRead(Reader a, int x, int y) throws IOException {
        int m = 0;
        int r;
        StringBuilder builder = new StringBuilder();
        do {
            m++;
            r = a.read();
            if (m == x)
                a.mark((x + y));
            if (m == (x + y))
                a.reset();

            if (r != -1)
                builder.append((char) r);
        } while (r != -1);
        return builder.toString();
    }
}
