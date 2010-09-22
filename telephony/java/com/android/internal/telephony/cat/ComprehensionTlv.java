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

package com.android.internal.telephony.cat;

import java.util.ArrayList;
import java.util.List;


/**
 * Class for representing COMPREHENSION-TLV objects.
 *
 * @see "ETSI TS 101 220 subsection 7.1.1"
 *
 * {@hide}
 */
class ComprehensionTlv {
    private int mTag;
    private boolean mCr;
    private int mLength;
    private int mValueIndex;
    private byte[] mRawValue;

    /**
     * Constructor. Private on purpose. Use
     * {@link #decodeMany(byte[], int) decodeMany} or
     * {@link #decode(byte[], int) decode} method.
     *
     * @param tag The tag for this object
     * @param cr Comprehension Required flag
     * @param length Length of the value
     * @param data Byte array containing the value
     * @param valueIndex Index in data at which the value starts
     */
    protected ComprehensionTlv(int tag, boolean cr, int length, byte[] data,
            int valueIndex) {
        mTag = tag;
        mCr = cr;
        mLength = length;
        mValueIndex = valueIndex;
        mRawValue = data;
    }

    public int getTag() {
        return mTag;
    }

    public boolean isComprehensionRequired() {
        return mCr;
    }

    public int getLength() {
        return mLength;
    }

    public int getValueIndex() {
        return mValueIndex;
    }

    public byte[] getRawValue() {
        return mRawValue;
    }

    /**
     * Parses a list of COMPREHENSION-TLV objects from a byte array.
     *
     * @param data A byte array containing data to be parsed
     * @param startIndex Index in data at which to start parsing
     * @return A list of COMPREHENSION-TLV objects parsed
     * @throws ResultException
     */
    public static List<ComprehensionTlv> decodeMany(byte[] data, int startIndex)
            throws ResultException {
        ArrayList<ComprehensionTlv> items = new ArrayList<ComprehensionTlv>();
        int endIndex = data.length;
        while (startIndex < endIndex) {
            ComprehensionTlv ctlv = ComprehensionTlv.decode(data, startIndex);
            items.add(ctlv);
            startIndex = ctlv.mValueIndex + ctlv.mLength;
        }

        return items;
    }

    /**
     * Parses an COMPREHENSION-TLV object from a byte array.
     *
     * @param data A byte array containing data to be parsed
     * @param startIndex Index in data at which to start parsing
     * @return A COMPREHENSION-TLV object parsed
     * @throws ResultException
     */
    public static ComprehensionTlv decode(byte[] data, int startIndex)
            throws ResultException {
        try {
            int curIndex = startIndex;
            int endIndex = data.length;

            /* tag */
            int tag;
            boolean cr; // Comprehension required flag
            int temp = data[curIndex++] & 0xff;
            switch (temp) {
            case 0:
            case 0xff:
            case 0x80:
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);

            case 0x7f: // tag is in three-byte format
                tag = ((data[curIndex] & 0xff) << 8)
                        | (data[curIndex + 1] & 0xff);
                cr = (tag & 0x8000) != 0;
                tag &= ~0x8000;
                curIndex += 2;
                break;

            default: // tag is in single-byte format
                tag = temp;
                cr = (tag & 0x80) != 0;
                tag &= ~0x80;
                break;
            }

            /* length */
            int length;
            temp = data[curIndex++] & 0xff;
            if (temp < 0x80) {
                length = temp;
            } else if (temp == 0x81) {
                length = data[curIndex++] & 0xff;
                if (length < 0x80) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            } else if (temp == 0x82) {
                length = ((data[curIndex] & 0xff) << 8)
                        | (data[curIndex + 1] & 0xff);
                curIndex += 2;
                if (length < 0x100) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            } else if (temp == 0x83) {
                length = ((data[curIndex] & 0xff) << 16)
                        | ((data[curIndex + 1] & 0xff) << 8)
                        | (data[curIndex + 2] & 0xff);
                curIndex += 3;
                if (length < 0x10000) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            } else {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }

            return new ComprehensionTlv(tag, cr, length, data, curIndex);

        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }
    }
}
