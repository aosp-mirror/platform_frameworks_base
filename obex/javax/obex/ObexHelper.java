/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * This class defines a set of helper methods for the implementation of Obex.
 * @hide
 */
public final class ObexHelper {

    /**
     * Defines the basic packet length used by OBEX. Every OBEX packet has the
     * same basic format:<BR>
     * Byte 0: Request or Response Code Byte 1&2: Length of the packet.
     */
    public static final int BASE_PACKET_LENGTH = 3;

    /** Prevent object construction of helper class */
    private ObexHelper() {
    }

    /**
     * The maximum packet size for OBEX packets that this client can handle. At
     * present, this must be changed for each port. TODO: The max packet size
     * should be the Max incoming MTU minus TODO: L2CAP package headers and
     * RFCOMM package headers. TODO: Retrieve the max incoming MTU from TODO:
     * LocalDevice.getProperty().
     */
    /*
     * android note set as 0xFFFE to match remote MPS
     */
    public static final int MAX_PACKET_SIZE_INT = 0xFFFE;

    /**
     * Temporary workaround to be able to push files to Windows 7.
     * TODO: Should be removed as soon as Microsoft updates their driver.
     */
    public static final int MAX_CLIENT_PACKET_SIZE = 0xFC00;

    public static final int OBEX_OPCODE_CONNECT = 0x80;

    public static final int OBEX_OPCODE_DISCONNECT = 0x81;

    public static final int OBEX_OPCODE_PUT = 0x02;

    public static final int OBEX_OPCODE_PUT_FINAL = 0x82;

    public static final int OBEX_OPCODE_GET = 0x03;

    public static final int OBEX_OPCODE_GET_FINAL = 0x83;

    public static final int OBEX_OPCODE_RESERVED = 0x04;

    public static final int OBEX_OPCODE_RESERVED_FINAL = 0x84;

    public static final int OBEX_OPCODE_SETPATH = 0x85;

    public static final int OBEX_OPCODE_ABORT = 0xFF;

    public static final int OBEX_AUTH_REALM_CHARSET_ASCII = 0x00;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_1 = 0x01;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_2 = 0x02;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_3 = 0x03;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_4 = 0x04;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_5 = 0x05;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_6 = 0x06;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_7 = 0x07;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_8 = 0x08;

    public static final int OBEX_AUTH_REALM_CHARSET_ISO_8859_9 = 0x09;

    public static final int OBEX_AUTH_REALM_CHARSET_UNICODE = 0xFF;

    /**
     * Updates the HeaderSet with the headers received in the byte array
     * provided. Invalid headers are ignored.
     * <P>
     * The first two bits of an OBEX Header specifies the type of object that is
     * being sent. The table below specifies the meaning of the high bits.
     * <TABLE>
     * <TR>
     * <TH>Bits 8 and 7</TH>
     * <TH>Value</TH>
     * <TH>Description</TH>
     * </TR>
     * <TR>
     * <TD>00</TD>
     * <TD>0x00</TD>
     * <TD>Null Terminated Unicode text, prefixed with 2 byte unsigned integer</TD>
     * </TR>
     * <TR>
     * <TD>01</TD>
     * <TD>0x40</TD>
     * <TD>Byte Sequence, length prefixed with 2 byte unsigned integer</TD>
     * </TR>
     * <TR>
     * <TD>10</TD>
     * <TD>0x80</TD>
     * <TD>1 byte quantity</TD>
     * </TR>
     * <TR>
     * <TD>11</TD>
     * <TD>0xC0</TD>
     * <TD>4 byte quantity - transmitted in network byte order (high byte first</TD>
     * </TR>
     * </TABLE>
     * This method uses the information in this table to determine the type of
     * Java object to create and passes that object with the full header to
     * setHeader() to update the HeaderSet object. Invalid headers will cause an
     * exception to be thrown. When it is thrown, it is ignored.
     * @param header the HeaderSet to update
     * @param headerArray the byte array containing headers
     * @return the result of the last start body or end body header provided;
     *         the first byte in the result will specify if a body or end of
     *         body is received
     * @throws IOException if an invalid header was found
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
                                    throw e;
                                }
                                break;

                            case HeaderSet.AUTH_CHALLENGE:
                                headerImpl.mAuthChall = new byte[length];
                                System.arraycopy(headerArray, index, headerImpl.mAuthChall, 0,
                                        length);
                                break;

                            case HeaderSet.AUTH_RESPONSE:
                                headerImpl.mAuthResp = new byte[length];
                                System.arraycopy(headerArray, index, headerImpl.mAuthResp, 0,
                                        length);
                                break;

                            case HeaderSet.BODY:
                                /* Fall Through */
                            case HeaderSet.END_OF_BODY:
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
                                    throw e;
                                }
                                break;

                            default:
                                if ((headerID & 0xC0) == 0x00) {
                                    headerImpl.setHeader(headerID, ObexHelper.convertToUnicode(
                                            value, true));
                                } else {
                                    headerImpl.setHeader(headerID, value);
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
                                if (headerID == HeaderSet.CONNECTION_ID) {
                                    headerImpl.mConnectionID = new byte[4];
                                    System.arraycopy(value, 0, headerImpl.mConnectionID, 0, 4);
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
     * TODO: Could use getHeaderList() to get the array of headers to include
     * and then use the high two bits to determine the the type of the object
     * and construct the byte array from that. This will make the size smaller.
     * @param head the header used to construct the byte array
     * @param nullOut <code>true</code> if the header should be set to
     *        <code>null</code> once it is added to the array or
     *        <code>false</code> if it should not be nulled out
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
        headImpl = head;

        try {
            /*
             * Determine if there is a connection ID to send.  If there is,
             * then it should be the first header in the packet.
             */
            if ((headImpl.mConnectionID != null) && (headImpl.getHeader(HeaderSet.TARGET) == null)) {

                out.write((byte)HeaderSet.CONNECTION_ID);
                out.write(headImpl.mConnectionID);
            }

            // Count Header
            intHeader = (Long)headImpl.getHeader(HeaderSet.COUNT);
            if (intHeader != null) {
                out.write((byte)HeaderSet.COUNT);
                value = ObexHelper.convertToByteArray(intHeader.longValue());
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.COUNT, null);
                }
            }

            // Name Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.NAME);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.NAME);
                value = ObexHelper.convertToUnicodeByteArray(stringHeader);
                length = value.length + 3;
                lengthArray[0] = (byte)(0xFF & (length >> 8));
                lengthArray[1] = (byte)(0xFF & length);
                out.write(lengthArray);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.NAME, null);
                }
            } else if (headImpl.getEmptyNameHeader()) {
                out.write((byte) HeaderSet.NAME);
                lengthArray[0] = (byte) 0x00;
                lengthArray[1] = (byte) 0x03;
                out.write(lengthArray);
            }

            // Type Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.TYPE);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.TYPE);
                try {
                    value = stringHeader.getBytes("ISO8859_1");
                } catch (UnsupportedEncodingException e) {
                    throw e;
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
                value = ObexHelper.convertToByteArray(intHeader.longValue());
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
                    throw e;
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
                value = ObexHelper.convertToByteArray(dateHeader.getTime().getTime() / 1000L);
                out.write(value);
                if (nullOut) {
                    headImpl.setHeader(HeaderSet.TIME_4_BYTE, null);
                }
            }

            // Description Header
            stringHeader = (String)headImpl.getHeader(HeaderSet.DESCRIPTION);
            if (stringHeader != null) {
                out.write((byte)HeaderSet.DESCRIPTION);
                value = ObexHelper.convertToUnicodeByteArray(stringHeader);
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
                    value = ObexHelper.convertToUnicodeByteArray(stringHeader);
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
                    out.write(ObexHelper.convertToByteArray(intHeader.longValue()));
                    if (nullOut) {
                        headImpl.setHeader(i + 0xF0, null);
                    }
                }
            }

            // Add the authentication challenge header
            if (headImpl.mAuthChall != null) {
                out.write((byte)HeaderSet.AUTH_CHALLENGE);
                length = headImpl.mAuthChall.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(headImpl.mAuthChall);
                if (nullOut) {
                    headImpl.mAuthChall = null;
                }
            }

            // Add the authentication response header
            if (headImpl.mAuthResp != null) {
                out.write((byte)HeaderSet.AUTH_RESPONSE);
                length = headImpl.mAuthResp.length + 3;
                lengthArray[0] = (byte)(255 & (length >> 8));
                lengthArray[1] = (byte)(255 & length);
                out.write(lengthArray);
                out.write(headImpl.mAuthResp);
                if (nullOut) {
                    headImpl.mAuthResp = null;
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
     * Determines where the maximum divide is between headers. This method is
     * used by put and get operations to separate headers to a size that meets
     * the max packet size allowed.
     * @param headerArray the headers to separate
     * @param start the starting index to search
     * @param maxSize the maximum size of a packet
     * @return the index of the end of the header block to send or -1 if the
     *         header could not be divided because the header is too large
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
     * @param b the byte array to convert to a long
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
     * Converts the long to a 4 byte array. The long must be non negative.
     * @param l the long to convert
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
     * Converts the String to a UNICODE byte array. It will also add the ending
     * null characters to the end of the string.
     * @param s the string to convert
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
     * Retrieves the value from the byte array for the tag value specified. The
     * array should be of the form Tag - Length - Value triplet.
     * @param tag the tag to retrieve from the byte array
     * @param triplet the byte sequence containing the tag length value form
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
     * @param tag the tag to look for
     * @param value the byte array to search
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
     * @param b the byte array to convert to a string
     * @param includesNull determine if the byte string provided contains the
     *        UNICODE null character at the end or not; if it does, it will be
     *        removed
     * @return a Unicode string
     * @throws IllegalArgumentException if the byte array has an odd length
     */
    public static String convertToUnicode(byte[] b, boolean includesNull) {
        if (b == null || b.length == 0) {
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
            // If upper and lower both equal 0, it should be the end of string.
            // Ignore left bytes from array to avoid potential issues
            if (upper == 0 && lower == 0) {
                return new String(c, 0, i);
            }

            c[i] = (char)((upper << 8) | lower);
        }

        return new String(c);
    }

    /**
     * Compute the MD5 hash of the byte array provided. Does not accumulate
     * input.
     * @param in the byte array to hash
     * @return the MD5 hash of the byte array
     */
    public static byte[] computeMd5Hash(byte[] in) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return md5.digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Computes an authentication challenge header.
     * @param nonce the challenge that will be provided to the peer; the
     *        challenge must be 16 bytes long
     * @param realm a short description that describes what password to use
     * @param access if <code>true</code> then full access will be granted if
     *        successful; if <code>false</code> then read only access will be
     *        granted if successful
     * @param userID if <code>true</code>, a user ID is required in the reply;
     *        if <code>false</code>, no user ID is required
     * @throws IllegalArgumentException if the challenge is not 16 bytes long;
     *         if the realm can not be encoded in less then 255 bytes
     * @throws IOException if the encoding scheme ISO 8859-1 is not supported
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
