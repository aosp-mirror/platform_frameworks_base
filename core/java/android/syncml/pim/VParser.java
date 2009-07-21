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

package android.syncml.pim;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

/**
 * This interface is used to parse the V format files, such as VCard & VCal
 *
 */
abstract public class VParser {
    // Assume that "iso-8859-1" is able to map "all" 8bit characters to some unicode and
    // decode the unicode to the original charset. If not, this setting will cause some bug. 
    public static String DEFAULT_CHARSET = "iso-8859-1";

    /**
     * The buffer used to store input stream
     */
    protected String mBuffer = null;

    /** The builder to build parsed data */
    protected VBuilder mBuilder = null;

    /** The encoding type */
    protected String mEncoding = null;

    protected final int PARSE_ERROR = -1;

    protected final String mDefaultEncoding = "8BIT";

    /**
     * If offset reach '\r\n' return 2. Else return PARSE_ERROR.
     */
    protected int parseCrlf(int offset) {
        if (offset >= mBuffer.length())
            return PARSE_ERROR;
        char ch = mBuffer.charAt(offset);
        if (ch == '\r') {
            offset++;
            ch = mBuffer.charAt(offset);
            if (ch == '\n') {
                return 2;
            }
        }
        return PARSE_ERROR;
    }

    /**
     * Parse the given stream
     *
     * @param is
     *            The source to parse.
     * @param encoding
     *            The encoding type.
     * @param builder
     *            The v builder which used to construct data.
     * @return Return true for success, otherwise false.
     * @throws IOException
     */
    public boolean parse(InputStream is, String encoding, VBuilder builder)
            throws IOException {
        setInputStream(is, encoding);
        mBuilder = builder;
        int ret = 0, offset = 0, sum = 0;

        if (mBuilder != null) {
            mBuilder.start();
        }
        for (;;) {
            ret = parseVFile(offset); // for next property length
            if (PARSE_ERROR == ret) {
                break;
            } else {
                offset += ret;
                sum += ret;
            }
        }
        if (mBuilder != null) {
            mBuilder.end();
        }
        return (mBuffer.length() == sum);
    }

    /**
     * Parse the given stream with the default encoding.
     * 
     * @param is
     *            The source to parse.
     * @param builder
     *            The v builder which used to construct data.
     * @return Return true for success, otherwise false.
     * @throws IOException
     */
    public boolean parse(InputStream is, VBuilder builder) throws IOException {
        return parse(is, DEFAULT_CHARSET, builder);
    }
    
    /**
     * Copy the content of input stream and filter the "folding"
     */
    protected void setInputStream(InputStream is, String encoding)
            throws UnsupportedEncodingException {
        InputStreamReader reader = new InputStreamReader(is, encoding);
        StringBuilder b = new StringBuilder();

        int ch;
        try {
            while ((ch = reader.read()) != -1) {
                if (ch == '\r') {
                    ch = reader.read();
                    if (ch == '\n') {
                        ch = reader.read();
                        if (ch == ' ' || ch == '\t') {
                            b.append((char) ch);
                            continue;
                        }
                        b.append("\r\n");
                        if (ch == -1) {
                            break;
                        }
                    } else {
                        b.append("\r");
                    }
                }
                b.append((char) ch);
            }
            mBuffer = b.toString();
        } catch (Exception e) {
            return;
        }
        return;
    }

    /**
     * abstract function, waiting implement.<br>
     * analyse from offset, return the length of consumed property.
     */
    abstract protected int parseVFile(int offset);

    /**
     * From offset, jump ' ', '\t', '\r\n' sequence, return the length of jump.<br>
     * 1 * (SPACE / HTAB / CRLF)
     */
    protected int parseWsls(int offset) {
        int ret = 0, sum = 0;

        try {
            char ch = mBuffer.charAt(offset);
            if (ch == ' ' || ch == '\t') {
                sum++;
                offset++;
            } else if ((ret = parseCrlf(offset)) != PARSE_ERROR) {
                offset += ret;
                sum += ret;
            } else {
                return PARSE_ERROR;
            }
            for (;;) {
                ch = mBuffer.charAt(offset);
                if (ch == ' ' || ch == '\t') {
                    sum++;
                    offset++;
                } else if ((ret = parseCrlf(offset)) != PARSE_ERROR) {
                    offset += ret;
                    sum += ret;
                } else {
                    break;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            ;
        }
        if (sum > 0)
            return sum;
        return PARSE_ERROR;
    }

    /**
     * To determine if the given string equals to the start of the current
     * string.
     *
     * @param offset
     *            The offset in buffer of current string
     * @param tar
     *            The given string.
     * @param ignoreCase
     *            To determine case sensitive or not.
     * @return The consumed characters, otherwise return PARSE_ERROR.
     */
    protected int parseString(int offset, final String tar, boolean ignoreCase) {
        int sum = 0;
        if (tar == null) {
            return PARSE_ERROR;
        }

        if (ignoreCase) {
            int len = tar.length();
            try {
                if (mBuffer.substring(offset, offset + len).equalsIgnoreCase(
                        tar)) {
                    sum = len;
                } else {
                    return PARSE_ERROR;
                }
            } catch (IndexOutOfBoundsException e) {
                return PARSE_ERROR;
            }

        } else { /* case sensitive */
            if (mBuffer.startsWith(tar, offset)) {
                sum = tar.length();
            } else {
                return PARSE_ERROR;
            }
        }
        return sum;
    }

    /**
     * Skip the white space in string.
     */
    protected int removeWs(int offset) {
        if (offset >= mBuffer.length())
            return PARSE_ERROR;
        int sum = 0;
        char ch;
        while ((ch = mBuffer.charAt(offset)) == ' ' || ch == '\t') {
            offset++;
            sum++;
        }
        return sum;
    }

    /**
     * "X-" word, and its value. Return consumed length.
     */
    protected int parseXWord(int offset) {
        int ret = 0, sum = 0;
        ret = parseString(offset, "X-", true);
        if (PARSE_ERROR == ret)
            return PARSE_ERROR;
        offset += ret;
        sum += ret;

        ret = parseWord(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        sum += ret;
        return sum;
    }

    /**
     * From offset, parse as :mEncoding ?= 7bit / 8bit / quoted-printable /
     * base64
     */
    protected int parseValue(int offset) {
        int ret = 0;

        if (mEncoding == null || mEncoding.equalsIgnoreCase("7BIT")
                || mEncoding.equalsIgnoreCase("8BIT")
                || mEncoding.toUpperCase().startsWith("X-")) {
            ret = parse8bit(offset);
            if (ret != PARSE_ERROR) {
                return ret;
            }
            return PARSE_ERROR;
        }

        if (mEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            ret = parseQuotedPrintable(offset);
            if (ret != PARSE_ERROR) {
                return ret;
            }
            return PARSE_ERROR;
        }

        if (mEncoding.equalsIgnoreCase("BASE64")) {
            ret = parseBase64(offset);
            if (ret != PARSE_ERROR) {
                return ret;
            }
            return PARSE_ERROR;
        }
        return PARSE_ERROR;
    }

    /**
     * Refer to RFC 1521, 8bit text
     */
    protected int parse8bit(int offset) {
        int index = 0;

        index = mBuffer.substring(offset).indexOf("\r\n");

        if (index == -1)
            return PARSE_ERROR;
        else
            return index;

    }

    /**
     * Refer to RFC 1521, quoted printable text ([*(ptext / SPACE / TAB) ptext]
     * ["="] CRLF)
     */
    protected int parseQuotedPrintable(int offset) {
        int ret = 0, sum = 0;

        ret = removeWs(offset);
        offset += ret;
        sum += ret;

        for (;;) {
            ret = parsePtext(offset);
            if (PARSE_ERROR == ret)
                break;
            offset += ret;
            sum += ret;

            ret = removeWs(offset);
            offset += ret;
            sum += ret;
        }

        ret = parseString(offset, "=", false);
        if (ret != PARSE_ERROR) {
            // offset += ret;
            sum += ret;
        }

        return sum;
    }

    /**
     * return 1 or 3 <any ASCII character except "=", SPACE, or TAB>
     */
    protected int parsePtext(int offset) {
        int ret = 0;

        try {
            char ch = mBuffer.charAt(offset);
            if (isPrintable(ch) && ch != '=' && ch != ' ' && ch != '\t') {
                return 1;
            }
        } catch (IndexOutOfBoundsException e) {
            return PARSE_ERROR;
        }

        ret = parseOctet(offset);
        if (ret != PARSE_ERROR) {
            return ret;
        }
        return PARSE_ERROR;
    }

    /**
     * start with "=" two of (DIGIT / "A" / "B" / "C" / "D" / "E" / "F") <br>
     * So maybe return 3.
     */
    protected int parseOctet(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "=", false);
        if (PARSE_ERROR == ret)
            return PARSE_ERROR;
        offset += ret;
        sum += ret;

        try {
            int ch = mBuffer.charAt(offset);
            if (ch == ' ' || ch == '\t')
                return ++sum;
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) {
                offset++;
                sum++;
                ch = mBuffer.charAt(offset);
                if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) {
                    sum++;
                    return sum;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            ;
        }
        return PARSE_ERROR;
    }

    /**
     * Refer to RFC 1521, base64 text The end of the text is marked with two
     * CRLF sequences
     */
    protected int parseBase64(int offset) {
        int sum = 0;
        try {
            for (;;) {
                char ch;
                ch = mBuffer.charAt(offset);

                if (ch == '\r') {
                    int ret = parseString(offset, "\r\n\r\n", false);
                    sum += ret;
                    break;
                } else {
                    /* ignore none base64 character */
                    sum++;
                    offset++;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return PARSE_ERROR;
        }
        sum -= 2;/* leave one CRLF to parse the end of this property */
        return sum;
    }

    /**
     * Any printable ASCII sequence except [ ]=:.,;
     */
    protected int parseWord(int offset) {
        int sum = 0;
        try {
            for (;;) {
                char ch = mBuffer.charAt(offset);
                if (!isPrintable(ch))
                    break;
                if (ch == ' ' || ch == '=' || ch == ':' || ch == '.'
                        || ch == ',' || ch == ';')
                    break;
                if (ch == '\\') {
                    ch = mBuffer.charAt(offset + 1);
                    if (ch == ';') {
                        offset++;
                        sum++;
                    }
                }
                offset++;
                sum++;
            }
        } catch (IndexOutOfBoundsException e) {
            ;
        }
        if (sum == 0)
            return PARSE_ERROR;
        return sum;
    }

    /**
     * If it is a letter or digit.
     */
    protected boolean isLetterOrDigit(char ch) {
        if (ch >= '0' && ch <= '9')
            return true;
        if (ch >= 'a' && ch <= 'z')
            return true;
        if (ch >= 'A' && ch <= 'Z')
            return true;
        return false;
    }

    /**
     * If it is printable in ASCII
     */
    protected boolean isPrintable(char ch) {
        if (ch >= ' ' && ch <= '~')
            return true;
        return false;
    }

    /**
     * If it is a letter.
     */
    protected boolean isLetter(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
            return true;
        }
        return false;
    }

    /**
     * Get a word from current position.
     */
    protected String getWord(int offset) {
        StringBuilder word = new StringBuilder();
        try {
            for (;;) {
                char ch = mBuffer.charAt(offset);
                if (isLetterOrDigit(ch) || ch == '-') {
                    word.append(ch);
                    offset++;
                } else {
                    break;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            ;
        }
        return word.toString();
    }

    /**
     * If is: "INLINE" / "URL" / "CONTENT-ID" / "CID" / "X-" word
     */
    protected int parsePValueVal(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "INLINE", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "URL", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "CONTENT-ID", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "CID", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "INLINE", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseXWord(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        return PARSE_ERROR;
    }

    /**
     * If is: "7BIT" / "8BIT" / "QUOTED-PRINTABLE" / "BASE64" / "X-" word and
     * set mEncoding.
     */
    protected int parsePEncodingVal(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "7BIT", true);
        if (ret != PARSE_ERROR) {
            mEncoding = "7BIT";
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "8BIT", true);
        if (ret != PARSE_ERROR) {
            mEncoding = "8BIT";
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "QUOTED-PRINTABLE", true);
        if (ret != PARSE_ERROR) {
            mEncoding = "QUOTED-PRINTABLE";
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "BASE64", true);
        if (ret != PARSE_ERROR) {
            mEncoding = "BASE64";
            sum += ret;
            return sum;
        }

        ret = parseXWord(offset);
        if (ret != PARSE_ERROR) {
            mEncoding = mBuffer.substring(offset).substring(0, ret);
            sum += ret;
            return sum;
        }

        return PARSE_ERROR;
    }

    /**
     * Refer to RFC1521, section 7.1<br>
     * If is: "us-ascii" / "iso-8859-xxx" / "X-" word
     */
    protected int parseCharsetVal(int offset) {
        int ret = 0, sum = 0;

        ret = parseString(offset, "us-ascii", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-1", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-2", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-3", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-4", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-5", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-6", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-7", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-8", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseString(offset, "iso-8859-9", true);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        ret = parseXWord(offset);
        if (ret != PARSE_ERROR) {
            sum += ret;
            return sum;
        }

        return PARSE_ERROR;
    }

    /**
     * Refer to RFC 1766<br>
     * like: XXX(sequence letters)-XXX(sequence letters)
     */
    protected int parseLangVal(int offset) {
        int ret = 0, sum = 0;

        ret = parseTag(offset);
        if (PARSE_ERROR == ret) {
            return PARSE_ERROR;
        }
        offset += ret;
        sum += ret;

        for (;;) {
            ret = parseString(offset, "-", false);
            if (PARSE_ERROR == ret) {
                break;
            }
            offset += ret;
            sum += ret;

            ret = parseTag(offset);
            if (PARSE_ERROR == ret) {
                break;
            }
            offset += ret;
            sum += ret;
        }
        return sum;
    }

    /**
     * From first 8 position, is sequence LETTER.
     */
    protected int parseTag(int offset) {
        int sum = 0, i = 0;

        try {
            for (i = 0; i < 8; i++) {
                char ch = mBuffer.charAt(offset);
                if (!isLetter(ch)) {
                    break;
                }
                sum++;
                offset++;
            }
        } catch (IndexOutOfBoundsException e) {
            ;
        }
        if (i == 0) {
            return PARSE_ERROR;
        }
        return sum;
    }

}
