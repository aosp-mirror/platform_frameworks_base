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
 * This class implements the javax.obex.HeaderSet interface for OBEX over
 * RFCOMM.
 *
 * @version 0.3 November 28, 2008
 */
public final class HeaderSet {

    /**
     * Represents the OBEX Count header.  This allows the connection statement
     * to tell the server how many objects it plans to send or retrieve.
     * <P>
     * The value of <code>COUNT</code> is 0xC0 (192).
     */
    public static final int COUNT = 0xC0;

    /**
     * Represents the OBEX Name header.  This specifies the name of the object.
     * <P>
     * The value of <code>NAME</code> is 0x01 (1).
     */
    public static final int NAME = 0x01;

    /**
     * Represents the OBEX Type header.  This allows a request to specify the
     * type of the object (e.g. text, html, binary, etc.).
     * <P>
     * The value of <code>TYPE</code> is 0x42 (66).
     */
    public static final int TYPE = 0x42;

    /**
     * Represents the OBEX Length header.  This is the length of the object in
     * bytes.
     * <P>
     * The value of <code>LENGTH</code> is 0xC3 (195).
     */
    public static final int LENGTH = 0xC3;

    /**
     * Represents the OBEX Time header using the ISO 8601 standards.  This is
     * the preferred time header.
     * <P>
     * The value of <code>TIME_ISO_8601</code> is 0x44 (68).
     */
    public static final int TIME_ISO_8601 = 0x44;

    /**
     * Represents the OBEX Time header using the 4 byte representation.  This
     * is only included for backwards compatibility.  It represents the number
     * of seconds since January 1, 1970.
     * <P>
     * The value of <code>TIME_4_BYTE</code> is 0xC4 (196).
     */
    public static final int TIME_4_BYTE = 0xC4;

    /**
     * Represents the OBEX Description header.  This is a text description of
     * the object.
     * <P>
     * The value of <code>DESCRIPTION</code> is 0x05 (5).
     */
    public static final int DESCRIPTION = 0x05;

    /**
     * Represents the OBEX Target header.  This is the name of the service an
     * operation is targeted to.
     * <P>
     * The value of <code>TARGET</code> is 0x46 (70).
     */
    public static final int TARGET = 0x46;

    /**
     * Represents the OBEX HTTP header.  This allows an HTTP 1.X header to be
     * included in a request or reply.
     * <P>
     * The value of <code>HTTP</code> is 0x47 (71).
     */
    public static final int HTTP = 0x47;

    /**
     * Represents the OBEX Who header.  Identifies the OBEX application to
     * determine if the two peers are talking to each other.
     * <P>
     * The value of <code>WHO</code> is 0x4A (74).
     */
    public static final int WHO = 0x4A;

    /**
     * Represents the OBEX Object Class header.  This header specifies the
     * OBEX object class of the object.
     * <P>
     * The value of <code>OBJECT_CLASS</code> is 0x4F (79).
     */
    public static final int OBJECT_CLASS = 0x4F;

    /**
     * Represents the OBEX Application Parameter header.  This header specifies
     * additional application request and response information.
     * <P>
     * The value of <code>APPLICATION_PARAMETER</code> is 0x4C (76).
     */
    public static final int APPLICATION_PARAMETER = 0x4C;

    private Long count; // 4 byte unsigned integer

    private String name; // null terminated Unicode text string

    private String type; // null terminated ASCII text string

    private Long length; // 4 byte unsigend integer

    private Calendar isoTime; // String of the form YYYYMMDDTHHMMSSZ

    private Calendar byteTime; // 4 byte unsigned integer

    private String description; // null terminated Unicode text String

    private byte[] target; // byte sequence

    private byte[] http; // byte sequence

    private byte[] who; // length prefixed byte sequence

    private byte[] appParam; // byte sequence of the form tag length

    //value
    public byte[] authChall; // The authentication challenge header

    public byte[] authResp; // The authentication response header

    public byte[] connectionID; // THe connection ID

    private byte[] objectClass; // byte sequence

    private String[] unicodeUserDefined; //null terminated unicode string

    private byte[][] sequenceUserDefined; // byte sequence user defined

    private Byte[] byteUserDefined; // 1 byte

    private Long[] integerUserDefined; // 4 byte unsigned integer

    public int responseCode;

    public byte[] nonce;

    private Random random;

    /**
     * Creates new <code>HeaderSet</code> object.
     *
     * @param size the max packet size for this connection
     */
    public HeaderSet() {
        unicodeUserDefined = new String[16];
        sequenceUserDefined = new byte[16][];
        byteUserDefined = new Byte[16];
        integerUserDefined = new Long[16];
        responseCode = -1;
        random = new Random();
    }

    /**
     * Sets the value of the header identifier to the value provided.  The type
     * of object must correspond to the Java type defined in the description of
     * this interface.  If <code>null</code> is passed as the
     * <code>headerValue</code> then the header will be removed from the set of
     * headers to include in the next request.
     *
     * @param headerID the identifier to include in the message
     *
     * @param headerValue the value of the header identifier
     *
     * @exception IllegalArgumentException if the header identifier provided is
     * not one defined in this interface or a user-defined header; if the type of
     * <code>headerValue</code> is not the correct Java type as defined in the
     * description of this interface\
     */
    public void setHeader(int headerID, Object headerValue) {
        long temp = -1;

        switch (headerID) {
            case COUNT:
                if (!(headerValue instanceof Long)) {
                    if (headerValue == null) {
                        count = null;
                        break;
                    }
                    throw new IllegalArgumentException("Count must be a Long");
                }
                temp = ((Long)headerValue).longValue();
                if ((temp < 0L) || (temp > 0xFFFFFFFFL)) {
                    throw new IllegalArgumentException("Count must be between 0 and 0xFFFFFFFF");
                }
                count = (Long)headerValue;
                break;
            case NAME:
                if ((headerValue != null) && (!(headerValue instanceof String))) {
                    throw new IllegalArgumentException("Name must be a String");
                }
                name = (String)headerValue;
                break;
            case TYPE:
                if ((headerValue != null) && (!(headerValue instanceof String))) {
                    throw new IllegalArgumentException("Type must be a String");
                }
                type = (String)headerValue;
                break;
            case LENGTH:
                if (!(headerValue instanceof Long)) {
                    if (headerValue == null) {
                        length = null;
                        break;
                    }
                    throw new IllegalArgumentException("Length must be a Long");
                }
                temp = ((Long)headerValue).longValue();
                if ((temp < 0L) || (temp > 0xFFFFFFFFL)) {
                    throw new IllegalArgumentException("Length must be between 0 and 0xFFFFFFFF");
                }
                length = (Long)headerValue;
                break;
            case TIME_ISO_8601:
                if ((headerValue != null) && (!(headerValue instanceof Calendar))) {
                    throw new IllegalArgumentException("Time ISO 8601 must be a Calendar");
                }
                isoTime = (Calendar)headerValue;
                break;
            case TIME_4_BYTE:
                if ((headerValue != null) && (!(headerValue instanceof Calendar))) {
                    throw new IllegalArgumentException("Time 4 Byte must be a Calendar");
                }
                byteTime = (Calendar)headerValue;
                break;
            case DESCRIPTION:
                if ((headerValue != null) && (!(headerValue instanceof String))) {
                    throw new IllegalArgumentException("Description must be a String");
                }
                description = (String)headerValue;
                break;
            case TARGET:
                if (headerValue == null) {
                    target = null;
                } else {
                    if (!(headerValue instanceof byte[])) {
                        throw new IllegalArgumentException("Target must be a byte array");
                    } else {
                        target = new byte[((byte[])headerValue).length];
                        System.arraycopy(headerValue, 0, target, 0, target.length);
                    }
                }
                break;
            case HTTP:
                if (headerValue == null) {
                    http = null;
                } else {
                    if (!(headerValue instanceof byte[])) {
                        throw new IllegalArgumentException("HTTP must be a byte array");
                    } else {
                        http = new byte[((byte[])headerValue).length];
                        System.arraycopy(headerValue, 0, http, 0, http.length);
                    }
                }
                break;
            case WHO:
                if (headerValue == null) {
                    who = null;
                } else {
                    if (!(headerValue instanceof byte[])) {
                        throw new IllegalArgumentException("WHO must be a byte array");
                    } else {
                        who = new byte[((byte[])headerValue).length];
                        System.arraycopy(headerValue, 0, who, 0, who.length);
                    }
                }
                break;
            case OBJECT_CLASS:
                if (headerValue == null) {
                    objectClass = null;
                } else {
                    if (!(headerValue instanceof byte[])) {
                        throw new IllegalArgumentException("Object Class must be a byte array");
                    } else {
                        objectClass = new byte[((byte[])headerValue).length];
                        System.arraycopy(headerValue, 0, objectClass, 0, objectClass.length);
                    }
                }
                break;
            case APPLICATION_PARAMETER:
                if (headerValue == null) {
                    appParam = null;
                } else {
                    if (!(headerValue instanceof byte[])) {
                        throw new IllegalArgumentException(
                                "Application Parameter must be a byte array");
                    } else {
                        appParam = new byte[((byte[])headerValue).length];
                        System.arraycopy(headerValue, 0, appParam, 0, appParam.length);
                    }
                }
                break;
            default:
                // Verify that it was not a Unicode String user Defined
                if ((headerID >= 0x30) && (headerID <= 0x3F)) {
                    if ((headerValue != null) && (!(headerValue instanceof String))) {
                        throw new IllegalArgumentException(
                                "Unicode String User Defined must be a String");
                    }
                    unicodeUserDefined[headerID - 0x30] = (String)headerValue;

                    break;
                }
                // Verify that it was not a byte sequence user defined value
                if ((headerID >= 0x70) && (headerID <= 0x7F)) {

                    if (headerValue == null) {
                        sequenceUserDefined[headerID - 0x70] = null;
                    } else {
                        if (!(headerValue instanceof byte[])) {
                            throw new IllegalArgumentException(
                                    "Byte Sequence User Defined must be a byte array");
                        } else {
                            sequenceUserDefined[headerID - 0x70] = new byte[((byte[])headerValue).length];
                            System.arraycopy(headerValue, 0, sequenceUserDefined[headerID - 0x70],
                                    0, sequenceUserDefined[headerID - 0x70].length);
                        }
                    }
                    break;
                }
                // Verify that it was not a Byte user Defined
                if ((headerID >= 0xB0) && (headerID <= 0xBF)) {
                    if ((headerValue != null) && (!(headerValue instanceof Byte))) {
                        throw new IllegalArgumentException("ByteUser Defined must be a Byte");
                    }
                    byteUserDefined[headerID - 0xB0] = (Byte)headerValue;

                    break;
                }
                // Verify that is was not the 4 byte unsigned integer user
                // defined header
                if ((headerID >= 0xF0) && (headerID <= 0xFF)) {
                    if (!(headerValue instanceof Long)) {
                        if (headerValue == null) {
                            integerUserDefined[headerID - 0xF0] = null;
                            break;
                        }
                        throw new IllegalArgumentException("Integer User Defined must be a Long");
                    }
                    temp = ((Long)headerValue).longValue();
                    if ((temp < 0L) || (temp > 0xFFFFFFFFL)) {
                        throw new IllegalArgumentException(
                                "Integer User Defined must be between 0 and 0xFFFFFFFF");
                    }
                    integerUserDefined[headerID - 0xF0] = (Long)headerValue;
                    break;
                }
                throw new IllegalArgumentException("Invalid Header Identifier");
        }
    }

    /**
     * Retrieves the value of the header identifier provided.  The type of the
     * Object returned is defined in the description of this interface.
     *
     * @param headerID the header identifier whose value is to be returned
     *
     * @return the value of the header provided or <code>null</code> if the
     * header identifier specified is not part of this <code>HeaderSet</code>
     * object
     *
     * @exception IllegalArgumentException if the <code>headerID</code> is not
     * one defined in this interface or any of the user-defined headers
     *
     * @exception IOException if an error occurred in the transport layer during
     * the operation or if the connection has been closed
     */
    public Object getHeader(int headerID) throws IOException {

        switch (headerID) {
            case COUNT:
                return count;
            case NAME:
                return name;
            case TYPE:
                return type;
            case LENGTH:
                return length;
            case TIME_ISO_8601:
                return isoTime;
            case TIME_4_BYTE:
                return byteTime;
            case DESCRIPTION:
                return description;
            case TARGET:
                return target;
            case HTTP:
                return http;
            case WHO:
                return who;
            case OBJECT_CLASS:
                return objectClass;
            case APPLICATION_PARAMETER:
                return appParam;
            default:
                // Verify that it was not a Unicode String user Defined
                if ((headerID >= 0x30) && (headerID <= 0x3F)) {
                    return unicodeUserDefined[headerID - 0x30];
                }
                // Verify that it was not a byte sequence user defined header
                if ((headerID >= 0x70) && (headerID <= 0x7F)) {
                    return sequenceUserDefined[headerID - 0x70];
                }
                // Verify that it was not a byte user defined header
                if ((headerID >= 0xB0) && (headerID <= 0xBF)) {
                    return byteUserDefined[headerID - 0xB0];
                }
                // Verify that it was not a itneger user defined header
                if ((headerID >= 0xF0) && (headerID <= 0xFF)) {
                    return integerUserDefined[headerID - 0xF0];
                }
                throw new IllegalArgumentException("Invalid Header Identifier");
        }
    }

    /**
     * Retrieves the list of headers that may be retrieved via the
     * <code>getHeader</code> method that will not return <code>null</code>.
     * In other words, this method returns all the headers that are available
     * in this object.
     *
     * @see #getHeader
     *
     * @return the array of headers that are set in this object or
     * <code>null</code> if no headers are available
     *
     * @exception IOException if an error occurred in the transport layer during
     * the operation or the connection has been closed
     */
    public int[] getHeaderList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (count != null) {
            out.write(COUNT);
        }
        if (name != null) {
            out.write(NAME);
        }
        if (type != null) {
            out.write(TYPE);
        }
        if (length != null) {
            out.write(LENGTH);
        }
        if (isoTime != null) {
            out.write(TIME_ISO_8601);
        }
        if (byteTime != null) {
            out.write(TIME_4_BYTE);
        }
        if (description != null) {
            out.write(DESCRIPTION);
        }
        if (target != null) {
            out.write(TARGET);
        }
        if (http != null) {
            out.write(HTTP);
        }
        if (who != null) {
            out.write(WHO);
        }
        if (appParam != null) {
            out.write(APPLICATION_PARAMETER);
        }
        if (objectClass != null) {
            out.write(OBJECT_CLASS);
        }

        for (int i = 0x30; i < 0x40; i++) {
            if (unicodeUserDefined[i - 0x30] != null) {
                out.write(i);
            }
        }

        for (int i = 0x70; i < 0x80; i++) {
            if (sequenceUserDefined[i - 0x70] != null) {
                out.write(i);
            }
        }

        for (int i = 0xB0; i < 0xC0; i++) {
            if (byteUserDefined[i - 0xB0] != null) {
                out.write(i);
            }
        }

        for (int i = 0xF0; i < 0x100; i++) {
            if (integerUserDefined[i - 0xF0] != null) {
                out.write(i);
            }
        }

        byte[] headers = out.toByteArray();
        out.close();

        if ((headers == null) || (headers.length == 0)) {
            return null;
        }

        int[] result = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            // Convert the byte to a positive integer.  That is, an integer
            // between 0 and 256.
            result[i] = headers[i] & 0xFF;
        }

        return result;
    }

    /**
     * Sets the authentication challenge header.  The <code>realm</code> will
     * be encoded based upon the default encoding scheme used by the
     * implementation to encode strings.  Therefore, the encoding scheme used
     * to encode the <code>realm</code> is application dependent.
     *
     * @param realm a short description that describes what password to use; if
     * <code>null</code> no realm will be sent in the authentication challenge
     * header
     *
     * @param userID if <code>true</code>, a user ID is required in the reply;
     * if <code>false</code>, no user ID is required
     *
     * @param access if <code>true</code> then full access will be granted if
     * successful; if <code>false</code> then read-only access will be granted
     * if successful
     */
    public void createAuthenticationChallenge(String realm, boolean userID, boolean access) {

        try {
            nonce = new byte[16];
            for (int i = 0; i < 16; i++) {
                nonce[i] = (byte)random.nextInt();
            }

            authChall = OBEXHelper.computeAuthenticationChallenge(nonce, realm, access, userID);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * Returns the response code received from the server.  Response codes
     * are defined in the <code>ResponseCodes</code> class.
     *
     * @see ResponseCodes
     *
     * @return the response code retrieved from the server
     *
     * @exception IOException if an error occurred in the transport layer during
     * the transaction; if this method is called on a <code>HeaderSet</code>
     * object created by calling <code>createHeaderSet()</code> in a
     * <code>ClientSession</code> object; if this object was created by an OBEX
     * server
     */
    public int getResponseCode() throws IOException {
        if (responseCode == -1) {
            throw new IOException("May not be called on a server");
        } else {
            return responseCode;
        }
    }
}
