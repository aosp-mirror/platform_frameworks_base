/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package javax.obex;

import java.io.*;
import java.util.*;

/**
 * This class defines a set of helper methods for the implementation of OBEX.
 *
 * @version 0.3 November 28, 2008
 */
public class OBEXHelper {

    /**
     * Creates new OBEXHelper
     */
    private OBEXHelper() {
    }

    private static final String TAG = "OBEXHelper";

    /**
     * Updates the HeaderSet with the headers received in the byte array
     * provided.  Invalid headers are ignored.
     * <P>
     * The first two bits of an OBEX Header specifies the type of object that
     * is being sent.  The table below specifies the meaning of the high
     * bits.
     * <TABLE>
     * <TR><TH>Bits 8 and 7</TH><TH>Value</TH><TH>Description</TH></TR>
     * <TR><TD>00</TD><TD>0x00</TD><TD>Null Terminated Unicode text, prefixed
     * with 2 byte unsigned integer</TD></TR>
     * <TR><TD>01</TD><TD>0x40</TD><TD>Byte Sequence, length prefixed with
     * 2 byte unsigned integer</TD></TR>
     * <TR><TD>10</TD><TD>0x80</TD><TD>1 byte quantity</TD></TR>
     * <TR><TD>11</TD><TD>0xC0</TD><TD>4 byte quantity - transmitted in
     * network byte order (high byte first</TD></TR>
     * </TABLE>
     * This method uses the information in this table to determine the type of
     * Java object to create and passes that object with the full header
     * to setHeader() to update the HeaderSet object.  Invalid headers will
     * cause an exception to be thrown.  When it is thrown, it is ignored.
     *
     * @param header the HeaderSet to update
     *
     * @param headerArray the byte array containing headers
     *
     * @return the result of the last start body or end body header provided;
     * the first byte in the result will specify if a body or end of body is
     * received
     *
     * @exception IOException if an invalid header was found
     */
    public static byte[] updateHeaderSet(HeaderSet header, byte[] headerArray) throws IOException {
        int index = 0;
        int length = 0;
        int headerID;
        byte[] value = null;
        byte[] body = null;
        HeaderSet headerImpl = header;
        try {
            while (index < headerArray.length) {
                headerID = 0xFF & headerArray[index];
                switch (headerID & (0xC0)) {

                    /*
                     * 0x00 is a unicode null terminate string with the first
                     * two bytes after the header identifier being the length
                     */
                    case 0x00:
                        // Fall through
                        /*
                         * 0x40 is a byte sequence with the first
                         * two bytes after the header identifier being the length
                         */
                    case 0x40:
                        boolean trimTail = true;
                        index++;
                        length = 0xFF & headerArray[index];
                        length = length << 8;
                        index++;
                        length += 0xFF & headerArray[index];
                        length -= 3;
                        index++;
                        value = new byte[length];
                        System.arraycopy(headerArray, index, value, 0, length);
                        if (length == 0 || (length > 0 && (value[length - 1] != 0))) {
                            trimTail = false;
                        }
                        switch (headerID) {
                            case HeaderSet.TYPE:
                                try {
                                    // Remove trailing null
                                    if (trimTail == false) {
                                        headerImpl.setHeader(headerID, new String(value, 0,
                                                value.length, "ISO8859_1"));
                                    } else {
                                        headerImpl.setHeader(headerID, new String(value, 0,
                                                value.length - 1, "ISO8859_1"));
                                    }
                                } catch (UnsupportedEncodingException e) {
                                    throw new RuntimeException("ISO8859_1 is not supported"
                                            + e.getMessage());
                                }
                                break;

                            // This is the constant for the authentication challenge header
                            // This header does not have a constant defined in the Java
                            // OBEX API
                            case 0x4D:
                                headerImpl.authChall = new byte[length];
                                System.arraycopy(headerArray, index, headerImpl.authChall, 0,
                                        length);
                                break;

                            // This is the constant for the authentication response header
                            // This header does not have a constant defined in the Java
                            // OBEX API
                            case 0x4E:
                                headerImpl.authResp = new byte[length];
                                System
                                        .arraycopy(headerArray, index, headerImpl.authResp, 0,
                                                length);
                                break;

                            /*
                            * These two case statements are for the body (0x48)
                             * and end of body (0x49) headers.
                            */
                            case 0x48:
                                /* Fall Through */
                            case 0x49:
                                body = new byte[length + 1];
                                body[0] = (byte)headerID;
                                System.arraycopy(headerArray, index, body, 1, length);
                                break;

                            case HeaderSet.TIME_ISO_8601:
                                try {
                                    String dateString = new String(value, "ISO8859_1");
                                    Calendar temp = Calendar.getInstance();
                                    if ((dateString.length() == 16)
                                            && (dateString.charAt(15) == 'Z')) {
                                        temp.setTimeZone(TimeZone.getTimeZone("UTC"));
                                    }
                                    temp.set(Calendar.YEAR, Integer.parseInt(dateString.substring(
                                            0, 4)));
                                    temp.set(Calendar.MONTH, Integer.parseInt(dateString.substring(
                                            4, 6)));
                                    temp.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateString
                                            .substring(6, 8)));
                                    temp.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dateString
                                            .substring(9, 11)));
                                    temp.set(Calendar.MINUTE, Integer.parseInt(dateString
                                            .substring(11, 13)));
                                    temp.set(Calendar.SECOND, Integer.parseInt(dateString
                                            .substring(13, 15)));
                                    headerImpl.setHeader(HeaderSet.TIME_ISO_8601, temp);
                                } catch (UnsupportedEncodingException e) {
                                    throw new RuntimeException("ISO8859_1 is not supported"
                                            + e.getMessage());
                                } catch (Exception e) {
                                    throw new IOException(
                                            "Time Header does not follow ISO 8601 standard");
                                }
                                break;

                            default:
                                try {
                                    if ((headerID & 0xC0) == 0x00) {
                                        headerImpl.setHeader(headerID, OBEXHelper.convertToUnicode(
                                                value, true));
                                    } else {
                                        headerImpl.setHeader(headerID, value);
                                    }
                                } catch (Exception e) {
                                    // Not a valid header so ignore
                                }
                        }

                        index += length;
                        break;

                    /*
                     * 0x80 is a byte header.  The only valid byte headers are
                     * the 16 user defined byte headers.
                     */
                    case 0x80:
                        index++;
                        try {
                            headerImpl.setHeader(headerID, Byte.valueOf(headerArray[index]));
                        } catch (Exception e) {
                            // Not a valid header so ignore
                        }
                        index++;
                        break;

                    /*
                     * 0xC0 is a 4 byte unsigned integer header and with the
                     * exception of TIME_4_BYTE will be converted to a Long
                     * and added.
                     */
                    case 0xC0:
                        index++;
                        value = new byte[4];
                        System.arraycopy(headerArray, index, value, 0, 4);
                        try {
                            if (headerID != HeaderSet.TIME_4_BYTE) {
                                // Determine if it is a connection ID.  These
                                // need to be handled differently
                                if (headerID == 0xCB) {
                                    headerImpl.connectionID = new byte[4];
                                    System.arraycopy(value, 0, headerImpl.connectionID, 0, 4);
                                } else {
                                    headerImpl.setHeader(headerID, Long
                                            .valueOf(convertToLong(value)));
                                }
                            } else {
                                Calendar temp = Calendar.getInstance();
                                temp.setTime(new Date(convertToLong(value) * 1000L));
                                headerImpl.setHeader(HeaderSet.TIME_4_BYTE, temp);
                            }
                        } catch (Exception e) {
                            // Not a valid header so ignore
                            throw new IOException("Header was not formatted properly");
                        }
                        index += 4;
                        break;
                }

            }
        } catch (IOException e) {
            throw new IOException("Header was not formatted properly");
        }

        return body;
    }

    /**
     * Creates the header part of OBEX packet based on the header provided.
     *
     * OPTIMIZATION: Could use getHeaderList() to get the array of headers to
     * OPTIMIZATION: include and then use the high two bits to determine the
     * OPTIMIZATION: the type of the object and construct the byte array from
     * OPTIMIZATION: that.  This will make the size smaller.
     *
     * @param head the header used to construct the byte array
     *
     * @param nullOut <code>true</code> if the header should be set to
     * <code>null</code> once it is added to the array or <code>false</code>
     * if it should not be nulled out
     *
     * @return the header of an OBEX packet
     */
    public static byte[] createHeader(HeaderSet head, boolean nullOut) {
        Long intHeader = null;
        String stringHeader = null;
        Calendar dateHeader = null;
        Byte byteHeader = null;
        StringBuffer buffer = null;
        byte[] value = null;
        byte[] result = null;
        byte[] lengthArray = new byte[2];
        int length;
        HeaderSet headImpl = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!(head instanceof HeaderSet)) {
            throw new IllegalArgumentException("Header not created by createHeaderSet");
        }
        headImpl = head;

        try {
            /*
             * Determine if there is a connection ID to send.  If there is,
             * then it should be the first header in the packet.
             */
            if ((headImpl.connectionID != null) && (headImpl.getHeader(HeaderSet.TARGET) == null)) {

                out.write((byte)0xCB);
                out.write(headImpl.connectionID);
            }

            // Count Header
            intHeader = (Long)headImpl.getHeader(HeaderSet.COUNT);
            if (intHeader != null) {
                out.write((byte)HeaderSet.COUNT);
                value = OBEXHelper.convertToByteArray(intHeader.longValue());
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.COUNT, null);
                }
            }

            // Name Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.NAME);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.NAME);
                value = OBEXHelper.convertToUnicodeByteArray(stringHeader);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.NAME, null);
                }
            }

            // Type Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.TYPE);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.TYPE);
                try {
                    value = stringHeader.getBytes("ISO8859_1");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("Unsupported Encoding Scheme: " + e.getMessage());
                }

                length = value.length + 4;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                out.write(0x00);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.TYPE, null);
                }
            }

            // Length Header
            intHeader = (Long)headImpl.getHeader(HeaderSet.LENGTH);
            if (intHeader != null) {
                out.write((byte)HeaderSet.LENGTH);
                value = OBEXHelper.convertToByteArray(intHeader.longValue());
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.LENGTH, null);
                }
            }

            // Time ISO Header
            dateHeader = (Calendar)headImpl.getHeader(HeaderSet.TIME_ISO_8601);
            if (dateHeader != null) {

                /*
                 * The ISO Header should take the form YYYYMMDDTHHMMSSZ.  The
                 * 'Z' will only be included if it is a UTC time.
                 */
                buffer = new StringBuffer();
                int temp = dateHeader.get(Calendar.YEAR);
                for (int i = temp; i < 1000; i = i * 10) {
                    buffer.append("0");
                }
                buffer.append(temp);
                temp = dateHeader.get(Calendar.MONTH);
                if (temp < 10) {
                    buffer.append("0");
                }
                buffer.append(temp);
                temp = dateHeader.get(Calendar.DAY_OF_MONTH);
                if (temp < 10) {
                    buffer.append("0");
                }
                buffer.append(temp);
                buffer.append("T");
                temp = dateHeader.get(Calendar.HOUR_OF_DAY);
                if (temp < 10) {
                    buffer.append("0");
                }
                buffer.append(temp);
                temp = dateHeader.get(Calendar.MINUTE);
                if (temp < 10) {
                    buffer.append("0");
                }
                buffer.append(temp);
                temp = dateHeader.get(Calendar.SECOND);
                if (temp < 10) {
                    buffer.append("0");
                }
                buffer.append(temp);

                if (dateHeader.getTimeZone().getID().equals("UTC")) {
                    buffer.append("Z");
                }

                try {
                    value = buffer.toString().getBytes("ISO8859_1");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("UnsupportedEncodingException: " + e.getMessage());
                }

                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(HeaderSet.TIME_ISO_8601);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.TIME_ISO_8601, null);
                }
            }

            // Time 4 Byte Header
            dateHeader = (Calendar)headImpl.getHeader(HeaderSet.TIME_4_BYTE);
            if (dateHeader != null) {
                out.write(HeaderSet.TIME_4_BYTE);

                /*
                 * Need to call getTime() twice.  The first call will return
                 * a java.util.Date object.  The second call returns the number
                 * of milliseconds since January 1, 1970.  We need to convert
                 * it to seconds since the TIME_4_BYTE expects the number of
                 * seconds since January 1, 1970.
                 */
                value = OBEXHelper.convertToByteArray(dateHeader.getTime().getTime() / 1000L);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.TIME_4_BYTE, null);
                }
            }

            // Description Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.DESCRIPTION);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.DESCRIPTION);
                value = OBEXHelper.convertToUnicodeByteArray(stringHeader);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.DESCRIPTION, null);
                }
            }

            // Target Header
            value = (byte[])headImpl.getHeader(HeaderSet.TARGET);
            if (value != null) {
                out.write((byte)HeaderSet.TARGET);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.TARGET, null);
                }
            }

            // HTTP Header
            value = (byte[])headImpl.getHeader(HeaderSet.HTTP);
            if (value != null) {
                out.write((byte)HeaderSet.HTTP);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.HTTP, null);
                }
            }

            // Who Header
            value = (byte[])headImpl.getHeader(HeaderSet.WHO);
            if (value != null) {
                out.write((byte)HeaderSet.WHO);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.WHO, null);
                }
            }

            // Connection ID Header
            value = (byte[])headImpl.getHeader(HeaderSet.APPLICATION_PARAMETER);
            if (value != null) {
                out.write((byte)HeaderSet.APPLICATION_PARAMETER);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.APPLICATION_PARAMETER, null);
                }
            }

            // Object Class Header
            value = (byte[])headImpl.getHeader(HeaderSet.OBJECT_CLASS);
            if (value != null) {
                out.write((byte)HeaderSet.OBJECT_CLASS);
                length = value.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.OBJECT_CLASS, null);
                }
            }

            // Check User Defined Headers
            for (int i = 0; i < 16; i++) {

                //Unicode String Header
                stringHeader = (String)headImpl.getHeader(i + 0x30);
                if (stringHeader != null) {
                    out.write((byte)i + 0x30);
                    value = OBEXHelper.convertToUnicodeByteArray(stringHeader);
                    length = value.length + 3;
                    lengthArray[0] = (byte)(255 & (length >> 8));
                    lengthArray[1] = (byte)(255 & length);
                    out.write(lengthArray);
                    out.write(value);
                    if (nullOut) {
                        headImpl.setHeader(i + 0x30, null);
                    }
                }

                // Byte Sequence Header
                value = (byte[])headImpl.getHeader(i + 0x70);
                if (value != null) {
                    out.write((byte)i + 0x70);
                    length = value.length + 3;
                    lengthArray[0] = (byte)(255 & (length >> 8));
                    lengthArray[1] = (byte)(255 & length);
                    out.write(lengthArray);
                    out.write(value);
                    if (nullOut) {
                        headImpl.setHeader(i + 0x70, null);
                    }
                }

                // Byte Header
                byteHeader = (Byte)headImpl.getHeader(i + 0xB0);
                if (byteHeader != null) {
                    out.write((byte)i + 0xB0);
                    out.write(byteHeader.byteValue());
                    if (nullOut) {
                        headImpl.setHeader(i + 0xB0, null);
                    }
                }

                // Integer header
                intHeader = (Long)headImpl.getHeader(i + 0xF0);
                if (intHeader != null) {
                    out.write((byte)i + 0xF0);
                    out.write(OBEXHelper.convertToByteArray(intHeader.longValue()));
                    if (nullOut) {
                        headImpl.setHeader(i + 0xF0, null);
                    }
                }
            }

            // Add the authentication challenge header
            if (headImpl.authChall != null) {
                out.write((byte)0x4D);
                length = headImpl.authChall.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(headImpl.authChall);
                if (nullOut) {
                    headImpl.authChall = null;
                }
            }

            // Add the authentication response header
            if (headImpl.authResp != null) {
                out.write((byte)0x4E);
                length = headImpl.authResp.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(headImpl.authResp);
                if (nullOut) {
                    headImpl.authResp = null;
                }
            }

        } catch (IOException e) {
        } finally {
            result = out.toByteArray();
            try {
                out.close();
            } catch (Exception ex) {
            }
        }

        return result;

    }

    /**
     * Determines where the maximum divide is between headers.  This method is
     * used by put and get operations to separate headers to a size that meets
     * the max packet size allowed.
     *
     * @param headerArray the headers to separate
     *
     * @param start the starting index to search
     *
     * @param maxSize the maximum size of a packet
     *
     * @return the index of the end of the header block to send or -1 if the
     * header could not be divided because the header is too large
     */
    public static int findHeaderEnd(byte[] headerArray, int start, int maxSize) {

        int fullLength = 0;
        int lastLength = -1;
        int index = start;
        int length = 0;

        while ((fullLength < maxSize) && (index < headerArray.length)) {
            int headerID = (headerArray[index] < 0 ? headerArray[index] + 256 : headerArray[index]);
            lastLength = fullLength;

            switch (headerID & (0xC0)) {

                case 0x00:
                    // Fall through
                case 0x40:

                    index++;
                    length = (headerArray[index] < 0 ? headerArray[index] + 256
                            : headerArray[index]);
                    length = length << 8;
                    index++;
                    length += (headerArray[index] < 0 ? headerArray[index] + 256
                            : headerArray[index]);
                    length -= 3;
                    index++;
                    index += length;
                    fullLength += length + 3;
                    break;

                case 0x80:

                    index++;
                    index++;
                    fullLength += 2;
                    break;

                case 0xC0:

                    index += 5;
                    fullLength += 5;
                    break;

            }

        }

        /*
         * Determine if this is the last header or not
         */
        if (lastLength == 0) {
            /*
             * Since this is the last header, check to see if the size of this
             * header is less then maxSize.  If it is, return the length of the
             * header, otherwise return -1.  The length of the header is
             * returned since it would be the start of the next header
             */
            if (fullLength < maxSize) {
                return headerArray.length;
            } else {
                return -1;
            }
        } else {
            return lastLength + start;
        }
    }

    /**
     * Converts the byte array to a long.
     *
     * @param b the byte array to convert to a long
     *
     * @return the byte array as a long
     */
    public static long convertToLong(byte[] b) {
        long result = 0;
        long value = 0;
        long power = 0;

        for (int i = (b.length - 1); i >= 0; i--) {
            value = b[i];
            if (value < 0) {
                value += 256;
            }

            result = result | (value << power);
            power += 8;
        }

        return result;
    }

    /**
     * Converts the long to a 4 byte array.  The long must be non negative.
     *
     * @param l the long to convert
     *
     * @return a byte array that is the same as the long
     */
    public static byte[] convertToByteArray(long l) {
        byte[] b = new byte[4];

        b[0] = (byte)(255 & (l >> 24));
        b[1] = (byte)(255 & (l >> 16));
        b[2] = (byte)(255 & (l >> 8));
        b[3] = (byte)(255 & l);

        return b;
    }

    /**
     * Converts the String to a UNICODE byte array.  It will also add the ending
     * null characters to the end of the string.
     *
     * @param s the string to convert
     *
     * @return the unicode byte array of the string
     */
    public static byte[] convertToUnicodeByteArray(String s) {
        if (s == null) {
            return null;
        }

        char c[] = s.toCharArray();
        byte[] result = new byte[(c.length * 2) + 2];
        for (int i = 0; i < c.length; i++) {
            result[(i * 2)] = (byte)(c[i] >> 8);
            result[((i * 2) + 1)] = (byte)c[i];
        }

        // Add the UNICODE null character
        result[result.length - 2] = 0;
        result[result.length - 1] = 0;

        return result;
    }

    /**
     * Retrieves the value from the byte array for the tag value specified.  The
     * array should be of the form Tag - Length - Value triplet.
     *
     * @param tag the tag to retrieve from the byte array
     *
     * @param triplet the byte sequence containing the tag length value form
     *
     * @return the value of the specified tag
     */
    public static byte[] getTagValue(byte tag, byte[] triplet) {

        int index = findTag(tag, triplet);
        if (index == -1) {
            return null;
        }

        index++;
        int length = triplet[index] & 0xFF;

        byte[] result = new byte[length];
        index++;
        System.arraycopy(triplet, index, result, 0, length);

        return result;
    }

    /**
     * Finds the index that starts the tag value pair in the byte array provide.
     *
     * @param tag the tag to look for
     *
     * @param value the byte array to search
     *
     * @return the starting index of the tag or -1 if the tag could not be found
     */
    public static int findTag(byte tag, byte[] value) {
        int length = 0;

        if (value == null) {
            return -1;
        }

        int index = 0;

        while ((index < value.length) && (value[index] != tag)) {
            length = value[index + 1] & 0xFF;
            index += length + 2;
        }

        if (index >= value.length) {
            return -1;
        }

        return index;
    }

    /**
     * Converts the byte array provided to a unicode string.
     *
     * @param b the byte array to convert to a string
     *
     * @param includesNull determine if the byte string provided contains the
     * UNICODE null character at the end or not;  if it does, it will be
     * removed
     *
     * @return a Unicode string
     *
     * @param IllegalArgumentException if the byte array has an odd length
     */
    public static String convertToUnicode(byte[] b, boolean includesNull) {
        if (b == null) {
            return null;
        }
        int arrayLength = b.length;
        if (!((arrayLength % 2) == 0)) {
            throw new IllegalArgumentException("Byte array not of a valid form");
        }
        arrayLength = (arrayLength >> 1);
        if (includesNull) {
            arrayLength -= 1;
        }

        char[] c = new char[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            int upper = b[2 * i];
            int lower = b[(2 * i) + 1];
            if (upper < 0) {
                upper += 256;
            }
            if (lower < 0) {
                lower += 256;
            }

            c[i] = (char)((upper << 8) | lower);
        }

        return new String(c);
    }

    /**
     * Computes the MD5 hash algorithm on the byte array provided.  This
     * implementation of MD5 is optimized for OBEX in that it provides for a
     * single entry and exist and thus does not build up the input before
     * applying the hash.
     *
     * OPTIMIZATION: Embedd MD5 algorithm in this method.  This will make the
     * OPTIMIZATION: size smaller.
     *
     * @param in the byte array to hash
     *
     * @return the MD5 hash of the byte array
     */
    public static byte[] computeMD5Hash(byte[] in) {

        MD5Hash hash = new MD5Hash(in);
        return hash.computeDigest();
    }

    /**
     * Computes an authentication challenge header.
     *
     *
     * @param nonce the challenge that will be provided to the peer;  the
     * challenge must be 16 bytes long
     *
     * @param realm a short description that describes what password to use
     *
     * @param access if <code>true</code> then full access will be granted if
     * successful; if <code>false</code> then read only access will be granted
     * if successful
     *
     * @param userID if <code>true</code>, a user ID is required in the reply;
     * if <code>false</code>, no user ID is required
     *
     * @exception IllegalArgumentException if the challenge is not 16 bytes
     * long; if the realm can not be encoded in less then 255 bytes
     *
     * @exception IOException if the encoding scheme ISO 8859-1 is not supported
     */
    public static byte[] computeAuthenticationChallenge(byte[] nonce, String realm, boolean access,
            boolean userID) throws IOException {
        byte[] authChall = null;

        if (nonce.length != 16) {
            throw new IllegalArgumentException("Nonce must be 16 bytes long");
        }

        /*
         * The authentication challenge is a byte sequence of the following form
         * byte 0: 0x00 - the tag for the challenge
         * byte 1: 0x10 - the length of the challenge; must be 16
         * byte 2-17: the authentication challenge
         * byte 18: 0x01 - the options tag; this is optional in the spec, but
         *                 we are going to include it in every message
         * byte 19: 0x01 - length of the options; must be 1
         * byte 20: the value of the options; bit 0 is set if user ID is
         *          required; bit 1 is set if access mode is read only
         * byte 21: 0x02 - the tag for authentication realm; only included if
         *                 an authentication realm is specified
         * byte 22: the length of the authentication realm; only included if
         *          the authentication realm is specified
         * byte 23: the encoding scheme of the authentication realm; we will use
         *          the ISO 8859-1 encoding scheme since it is part of the KVM
         * byte 24 & up: the realm if one is specified.
         */
        if (realm == null) {
            authChall = new byte[21];
        } else {
            if (realm.length() >= 255) {
                throw new IllegalArgumentException("Realm must be less then 255 bytes");
            }
            authChall = new byte[24 + realm.length()];
            authChall[21] = 0x02;
            authChall[22] = (byte)(realm.length() + 1);
            authChall[23] = 0x01; // ISO 8859-1 Encoding
            System.arraycopy(realm.getBytes("ISO8859_1"), 0, authChall, 24, realm.length());
        }

        // Include the nonce field in the header
        authChall[0] = 0x00;
        authChall[1] = 0x10;
        System.arraycopy(nonce, 0, authChall, 2, 16);

        // Include the options header
        authChall[18] = 0x01;
        authChall[19] = 0x01;
        authChall[20] = 0x00;

        if (!access) {
            authChall[20] = (byte)(authChall[20] | 0x02);
        }
        if (userID) {
            authChall[20] = (byte)(authChall[20] | 0x01);
        }

        return authChall;
    }
}

/**
 * This class will complete an MD5 hash of the buffer provided.
 */
class MD5Hash {

    // Constants for MD5Transform routine.
    private static final int A1 = 7;

    private static final int A2 = 12;

    private static final int A3 = 17;

    private static final int A4 = 22;

    private static final int B1 = 5;

    private static final int B2 = 9;

    private static final int B3 = 14;

    private static final int B4 = 20;

    private static final int C1 = 4;

    private static final int C2 = 11;

    private static final int C3 = 16;

    private static final int C4 = 23;

    private static final int D1 = 6;

    private static final int D2 = 10;

    private static final int D3 = 15;

    private static final int D4 = 21;

    private int state[];

    private int count[];

    /**
     * Keeps the present digest
     */
    private byte buffer[];

    /**
     * Completes a hash on the data provided.
     *
     * @param data the byte array to hash
     */
    public MD5Hash(byte data[]) {
        buffer = new byte[64];
        state = new int[4];
        count = new int[2];

        state[0] = 0x67452301;
        state[1] = 0xefcdab89;
        state[2] = 0x98badcfe;
        state[3] = 0x10325476;

        count[0] = 0;
        count[1] = 0;

        MD5Update(data, 0, data.length);
    }

    /**
    * Updates the MD5 hash buffer.
    *
    * @param input byte array of data
     *
    * @param offset offset into the array to start the digest calculation
     *
    * @param inputLen the length of the byte array
    */
    private void MD5Update(byte input[], int offset, int inputLen) {
        int i, index, partLen;

        // Compute number of bytes mod 64
        index = (count[0] >>> 3) & 0x3F;

        // Update number of bits
        int slen = inputLen << 3;
        if ((count[0] += slen) < slen) {
            count[1]++;
        }

        count[1] += (inputLen >>> 29);

        partLen = 64 - index;
        // Transform as many times as possible.
        if (inputLen >= partLen) {
            copy(index, input, offset, partLen);

            MD5Transform(buffer, 0, 0);

            for (i = partLen; i + 63 < inputLen; i += 64) {
                MD5Transform(input, offset, i);
            }

            index = 0;
        } else {
            i = 0;
        }

        copy(index, input, i + offset, inputLen - i);
    }

    /**
     * Computes the final MD5 digest
     *
     * @return the MD5 digest
     */
    public byte[] computeDigest() {
        byte bits[];
        byte digest[];
        int index;
        int length;

        // Save number of bits
        bits = MD5Encode(count, 8);

        // Pad out to 56 mod 64.
        index = ((count[0] >>> 3) & 0x3f);
        length = (index < 56) ? (56 - index) : (120 - index);

        // build padding buffer.
        if (length > 0) {
            byte padding[] = new byte[length];
            padding[0] = (byte)0x80;
            for (int i = 1; i < length; i++) {
                padding[i] = 0;
            }

            MD5Update(padding, 0, length);
        }

        // Append length
        MD5Update(bits, 0, 8);

        // Store state in digest
        digest = MD5Encode(state, 16);

        return digest;
    }

    /**
     * Copies the input array into the local objects buffer.  It performs a
     * check to verify that data is available to copy.
     *
     * @param startIndex the offset into the local buffer to copy
     *
     * @param input the array to copy into the local buffer
     *
     * @param index the index to start copying from
     *
     * @param length the amount of data to copy
     */
    private void copy(int startIndex, byte input[], int index, int length) {
        if (index == input.length)
            return;
        System.arraycopy(input, index, buffer, startIndex, length);
    }

    /**
     * Rotates the bytes in <code>x</code> <code>n</code> times to the left.
     * The rotation wraps the bits around.
     *
     * @param x the integer to rotate
     *
     * @param n the number of bits to rotate
     *
     * @return <code>x</code> rotated to the left <code>n</code> times
     */
    private int rotate(int x, int n) {
        return (((x) << (n)) | ((x) >>> (32 - (n))));
    }

    /**
     * Completes a single step in the MD5 hash algorithm.
     */
    private int MD5Step(int a, int b, int c, int d, int x, int s, int ac, int round) {
        switch (round) {
            case 1:
                a += (((b) & (c)) | ((~b) & (d))) + (x) + (ac);
                break;
            case 2:
                a += (((b) & (d)) | ((c) & (~d))) + (x) + (ac);
                break;
            case 3:
                a += ((b) ^ (c) ^ (d)) + (x) + (ac);
                break;
            case 4:
                a += ((c) ^ ((b) | (~d))) + (x) + (ac);
                break;
        }
        a = rotate(a, (s));
        a += (b);
        return a;
    }

    /**
     * Performs the core MD5 algorithm.  This method will add the data provided
     * and recompute the hash.
     *
     * @param data the block of data to add
     *
     * @param index the starting index into the data to start processing
     *
     * @param length the length of the byte array to process
     */
    private void MD5Transform(byte data[], int index, int length) {
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int x[];

        x = MD5Decode(data, index, length, 64);

        // Round 1
        a = MD5Step(a, b, c, d, x[0], A1, 0xd76aa478, 1);
        d = MD5Step(d, a, b, c, x[1], A2, 0xe8c7b756, 1);
        c = MD5Step(c, d, a, b, x[2], A3, 0x242070db, 1);
        b = MD5Step(b, c, d, a, x[3], A4, 0xc1bdceee, 1);
        a = MD5Step(a, b, c, d, x[4], A1, 0xf57c0faf, 1);
        d = MD5Step(d, a, b, c, x[5], A2, 0x4787c62a, 1);
        c = MD5Step(c, d, a, b, x[6], A3, 0xa8304613, 1);
        b = MD5Step(b, c, d, a, x[7], A4, 0xfd469501, 1);
        a = MD5Step(a, b, c, d, x[8], A1, 0x698098d8, 1);
        d = MD5Step(d, a, b, c, x[9], A2, 0x8b44f7af, 1);
        c = MD5Step(c, d, a, b, x[10], A3, 0xffff5bb1, 1);
        b = MD5Step(b, c, d, a, x[11], A4, 0x895cd7be, 1);
        a = MD5Step(a, b, c, d, x[12], A1, 0x6b901122, 1);
        d = MD5Step(d, a, b, c, x[13], A2, 0xfd987193, 1);
        c = MD5Step(c, d, a, b, x[14], A3, 0xa679438e, 1);
        b = MD5Step(b, c, d, a, x[15], A4, 0x49b40821, 1);

        // Round 2
        a = MD5Step(a, b, c, d, x[1], B1, 0xf61e2562, 2);
        d = MD5Step(d, a, b, c, x[6], B2, 0xc040b340, 2);
        c = MD5Step(c, d, a, b, x[11], B3, 0x265e5a51, 2);
        b = MD5Step(b, c, d, a, x[0], B4, 0xe9b6c7aa, 2);
        a = MD5Step(a, b, c, d, x[5], B1, 0xd62f105d, 2);
        d = MD5Step(d, a, b, c, x[10], B2, 0x2441453, 2);
        c = MD5Step(c, d, a, b, x[15], B3, 0xd8a1e681, 2);
        b = MD5Step(b, c, d, a, x[4], B4, 0xe7d3fbc8, 2);
        a = MD5Step(a, b, c, d, x[9], B1, 0x21e1cde6, 2);
        d = MD5Step(d, a, b, c, x[14], B2, 0xc33707d6, 2);
        c = MD5Step(c, d, a, b, x[3], B3, 0xf4d50d87, 2);
        b = MD5Step(b, c, d, a, x[8], B4, 0x455a14ed, 2);
        a = MD5Step(a, b, c, d, x[13], B1, 0xa9e3e905, 2);
        d = MD5Step(d, a, b, c, x[2], B2, 0xfcefa3f8, 2);
        c = MD5Step(c, d, a, b, x[7], B3, 0x676f02d9, 2);
        b = MD5Step(b, c, d, a, x[12], B4, 0x8d2a4c8a, 2);

        // Round 3
        a = MD5Step(a, b, c, d, x[5], C1, 0xfffa3942, 3);
        d = MD5Step(d, a, b, c, x[8], C2, 0x8771f681, 3);
        c = MD5Step(c, d, a, b, x[11], C3, 0x6d9d6122, 3);
        b = MD5Step(b, c, d, a, x[14], C4, 0xfde5380c, 3);
        a = MD5Step(a, b, c, d, x[1], C1, 0xa4beea44, 3);
        d = MD5Step(d, a, b, c, x[4], C2, 0x4bdecfa9, 3);
        c = MD5Step(c, d, a, b, x[7], C3, 0xf6bb4b60, 3);
        b = MD5Step(b, c, d, a, x[10], C4, 0xbebfbc70, 3);
        a = MD5Step(a, b, c, d, x[13], C1, 0x289b7ec6, 3);
        d = MD5Step(d, a, b, c, x[0], C2, 0xeaa127fa, 3);
        c = MD5Step(c, d, a, b, x[3], C3, 0xd4ef3085, 3);
        b = MD5Step(b, c, d, a, x[6], C4, 0x4881d05, 3);
        a = MD5Step(a, b, c, d, x[9], C1, 0xd9d4d039, 3);
        d = MD5Step(d, a, b, c, x[12], C2, 0xe6db99e5, 3);
        c = MD5Step(c, d, a, b, x[15], C3, 0x1fa27cf8, 3);
        b = MD5Step(b, c, d, a, x[2], C4, 0xc4ac5665, 3);

        // Round 4
        a = MD5Step(a, b, c, d, x[0], D1, 0xf4292244, 4);
        d = MD5Step(d, a, b, c, x[7], D2, 0x432aff97, 4);
        c = MD5Step(c, d, a, b, x[14], D3, 0xab9423a7, 4);
        b = MD5Step(b, c, d, a, x[5], D4, 0xfc93a039, 4);
        a = MD5Step(a, b, c, d, x[12], D1, 0x655b59c3, 4);
        d = MD5Step(d, a, b, c, x[3], D2, 0x8f0ccc92, 4);
        c = MD5Step(c, d, a, b, x[10], D3, 0xffeff47d, 4);
        b = MD5Step(b, c, d, a, x[1], D4, 0x85845dd1, 4);
        a = MD5Step(a, b, c, d, x[8], D1, 0x6fa87e4f, 4);
        d = MD5Step(d, a, b, c, x[15], D2, 0xfe2ce6e0, 4);
        c = MD5Step(c, d, a, b, x[6], D3, 0xa3014314, 4);
        b = MD5Step(b, c, d, a, x[13], D4, 0x4e0811a1, 4);
        a = MD5Step(a, b, c, d, x[4], D1, 0xf7537e82, 4);
        d = MD5Step(d, a, b, c, x[11], D2, 0xbd3af235, 4);
        c = MD5Step(c, d, a, b, x[2], D3, 0x2ad7d2bb, 4);
        b = MD5Step(b, c, d, a, x[9], D4, 0xeb86d391, 4);

        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }

    /**
     * Encodes the input array.  <code>input</code> must be a multiple of
     * four.
     *
     * @param input the array to encode
     *
     * @param length the length of the array to encode
     */
    private byte[] MD5Encode(int input[], int length) {
        int i, j;
        byte output[] = new byte[length];

        for (i = 0, j = 0; j < length; i++, j += 4) {
            output[j] = (byte)(input[i] & 0xff);
            output[j + 1] = (byte)((input[i] >>> 8) & 0xff);
            output[j + 2] = (byte)((input[i] >>> 16) & 0xff);
            output[j + 3] = (byte)((input[i] >>> 24) & 0xff);
        }

        return output;
    }

    /**
     * Decodes the array.  The <code>input</code> array must be a multiple of
     * four.  Results are undefined if this is not true.
     *
     * @param input the array to decode
     *
     * @param index the starting position into the input array to start decoding
     *
     * @param posn
     *
     * @param length
     */
    private int[] MD5Decode(byte input[], int offset, int posn, int length) {
        int output[] = new int[length / 4];
        int i, j;
        int limit = length + posn + offset;

        for (i = 0, j = offset + posn; j < limit; i++, j += 4)
            output[i] = ((input[j]) & 0xff) | (((input[j + 1]) & 0xff) << 8)
                    | (((input[j + 2]) & 0xff) << 16) | (((input[j + 3]) & 0xff) << 24);

        return output;
    }
}
