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

/**
 * SIM Tag-Length-Value record
 * TS 102 223 Annex C
 *
 * {@hide}
 *
 */
public class SimTlv
{
    //***** Private Instance Variables

    byte record[];
    int tlvOffset;
    int tlvLength;
    int curOffset;
    int curDataOffset;
    int curDataLength;
    boolean hasValidTlvObject;

    public SimTlv(byte[] record, int offset, int length) {
        this.record = record;

        this.tlvOffset = offset;
        this.tlvLength = length;
        curOffset = offset;

        hasValidTlvObject = parseCurrentTlvObject();
    }

    public boolean nextObject() {
        if (!hasValidTlvObject) return false;
        curOffset = curDataOffset + curDataLength;
        hasValidTlvObject = parseCurrentTlvObject();
        return hasValidTlvObject;
    }

    public boolean isValidObject() {
        return hasValidTlvObject;
    }

    /**
     * Returns the tag for the current TLV object
     * Return 0 if !isValidObject()
     * 0 and 0xff are invalid tag values
     * valid tags range from 1 - 0xfe
     */
    public int getTag() {
        if (!hasValidTlvObject) return 0;
        return record[curOffset] & 0xff;
    }

    /**
     * Returns data associated with current TLV object
     * returns null if !isValidObject()
     */

    public byte[] getData() {
        if (!hasValidTlvObject) return null;

        byte[] ret = new byte[curDataLength];
        System.arraycopy(record, curDataOffset, ret, 0, curDataLength);
        return ret;
    }

    /**
     * Updates curDataLength and curDataOffset
     * @return false on invalid record, true on valid record
     */

    private boolean parseCurrentTlvObject() {
        // 0x00 and 0xff are invalid tag values

        try {
            if (record[curOffset] == 0 || (record[curOffset] & 0xff) == 0xff) {
                return false;
            }

            if ((record[curOffset + 1] & 0xff) < 0x80) {
                // one byte length 0 - 0x7f
                curDataLength = record[curOffset + 1] & 0xff;
                curDataOffset = curOffset + 2;
            } else if ((record[curOffset + 1] & 0xff) == 0x81) {
                // two byte length 0x80 - 0xff
                curDataLength = record[curOffset + 2] & 0xff;
                curDataOffset = curOffset + 3;
            } else {
                return false;
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            return false;
        }

        if (curDataLength + curDataOffset > tlvOffset + tlvLength) {
            return false;
        }

        return true;
    }

}
