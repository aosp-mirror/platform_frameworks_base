/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import com.android.internal.util.HexDump;

import java.util.ArrayList;

/**
 * This class represents a SMS user data header.
 *
 */
public class SmsHeader
{
    /** See TS 23.040 9.2.3.24 for description of this element ID. */
    public static final int CONCATENATED_8_BIT_REFERENCE = 0x00;
    /** See TS 23.040 9.2.3.24 for description of this element ID. */
    public static final int SPECIAL_SMS_MESSAGE_INDICATION = 0x01;
    /** See TS 23.040 9.2.3.24 for description of this element ID. */
    public static final int APPLICATION_PORT_ADDRESSING_8_BIT = 0x04;
    /** See TS 23.040 9.2.3.24 for description of this element ID. */
    public static final int APPLICATION_PORT_ADDRESSING_16_BIT= 0x05;
    /** See TS 23.040 9.2.3.24 for description of this element ID. */
    public static final int CONCATENATED_16_BIT_REFERENCE = 0x08;

    public static final int PORT_WAP_PUSH = 2948;
    public static final int PORT_WAP_WSP = 9200;

    private byte[] m_data;
    private ArrayList<Element> m_elements = new ArrayList<Element>();

    /**
     * Creates an SmsHeader object from raw user data header bytes.
     *
     * @param data is user data header bytes
     * @return an SmsHeader object
     */
    public static SmsHeader parse(byte[] data)
    {
        SmsHeader header = new SmsHeader();
        header.m_data = data;

        int index = 0;
        while (index < data.length)
        {
            int id = data[index++] & 0xff;
            int length = data[index++] & 0xff;
            byte[] elementData = new byte[length];
            System.arraycopy(data, index, elementData, 0, length);
            header.add(new Element(id, elementData));
            index += length;
        }

        return header;
    }

    public SmsHeader()
    {
    }

    /**
     * Returns the list of SmsHeader Elements that make up the header.
     *
     * @return the list of SmsHeader Elements.
     */
    public ArrayList<Element> getElements()
    {
        return m_elements;
    }

    /**
     * Add an element to the SmsHeader.
     *
     * @param element to add.
     */
    public void add(Element element)
    {
        m_elements.add(element);
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        builder.append("UDH LENGTH: " + m_data.length + " octets");
        builder.append("UDH: ");
        builder.append(HexDump.toHexString(m_data));
        builder.append("\n");

        for (Element e : getElements()) {
            builder.append("  0x" + HexDump.toHexString((byte)e.getID()) + " - ");
            switch (e.getID())
            {
                case CONCATENATED_8_BIT_REFERENCE:
                {
                    builder.append("Concatenated Short Message 8bit ref\n");
                    byte[] data = e.getData();
                    builder.append("    " + data.length + " (0x");
                    builder.append(HexDump.toHexString((byte)data.length)+") Bytes - Information Element\n");
                    builder.append("      " + data[0] + " : SM reference number\n");
                    builder.append("      " + data[1] + " : number of messages\n");
                    builder.append("      " + data[2] + " : this SM sequence number\n");
                    break;
                }

                case CONCATENATED_16_BIT_REFERENCE:
                {
                    builder.append("Concatenated Short Message 16bit ref\n");
                    byte[] data = e.getData();
                    builder.append("    " + data.length + " (0x");
                    builder.append(HexDump.toHexString((byte)data.length)+") Bytes - Information Element\n");
                    builder.append("      " + (data[0] & 0xff) * 256 + (data[1] & 0xff) +
                                   " : SM reference number\n");
                    builder.append("      " + data[2] + " : number of messages\n");
                    builder.append("      " + data[3] + " : this SM sequence number\n");
                    break;
                }

                case APPLICATION_PORT_ADDRESSING_16_BIT:
                {
                    builder.append("Application port addressing 16bit\n");
                    byte[] data = e.getData();

                    builder.append("    " + data.length + " (0x");
                    builder.append(HexDump.toHexString((byte)data.length)+") Bytes - Information Element\n");

                    int source = (data[0] & 0xff) << 8;
                    source |= (data[1] & 0xff);
                    builder.append("      " + source + " : DESTINATION port\n");

                    int dest = (data[2] & 0xff) << 8;
                    dest |= (data[3] & 0xff);
                    builder.append("      " + dest + " : SOURCE port\n");
                    break;
                }

                default:
                {
                    builder.append("Unknown element\n");
                    break;
                }
            }
        }

        return builder.toString();
    }

    private int calcSize() {
        int size = 1; // +1 for the UDHL field
        for (Element e : m_elements) {
            size += e.getData().length;
            size += 2; // 1 byte ID, 1 byte length
        }

        return size;
    }

    /**
     * Converts SmsHeader object to a byte array as specified in TS 23.040 9.2.3.24.
     * @return Byte array representing the SmsHeader
     */
    public byte[] toByteArray() {
        if (m_elements.size() == 0) return null;

        if (m_data == null) {
            int size = calcSize();
            int cur = 1;
            m_data = new byte[size];

            m_data[0] = (byte) (size-1);  // UDHL does not include itself

            for (Element e : m_elements) {
                int length = e.getData().length;
                m_data[cur++] = (byte) e.getID();
                m_data[cur++] = (byte) length;
                System.arraycopy(e.getData(), 0, m_data, cur, length);
                cur += length;
            }
        }

        return m_data;
    }

    /**
     * A single Element in the SMS User Data Header.
     *
     * See TS 23.040 9.2.3.24.
     *
     */
    public static class Element
    {
        private byte[] m_data;
        private int m_id;

        public Element(int id, byte[] data)
        {
            m_id = id;
            m_data = data;
        }

        /**
         * Returns the Information Element Identifier for this element.
         *
         * @return the IE identifier.
         */
        public int getID()
        {
            return m_id;
        }

        /**
         * Returns the data portion of this element.
         *
         * @return element data.
         */
        public byte[] getData()
        {
            return m_data;
        }
    }
}
