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

package com.android.internal.telephony.gsm.stk;

import java.util.List;

/**
 * Class for representing BER-TLV objects.
 *
 * @see "ETSI TS 102 223 Annex C" for more information.
 *
 * {@hide}
 */
class BerTlv {
    private int mTag = BER_UNKNOWN_TAG;
    private List<ComprehensionTlv> mCompTlvs = null;

    public static final int BER_UNKNOWN_TAG             = 0x00;
    public static final int BER_PROACTIVE_COMMAND_TAG   = 0xd0;
    public static final int BER_MENU_SELECTION_TAG      = 0xd3;
    public static final int BER_EVENT_DOWNLOAD_TAG      = 0xd6;

    private BerTlv(int tag, List<ComprehensionTlv> ctlvs) {
        mTag = tag;
        mCompTlvs = ctlvs;
    }

    /**
     * Gets a list of ComprehensionTlv objects contained in this BER-TLV object.
     *
     * @return A list of COMPREHENSION-TLV object
     */
    public List<ComprehensionTlv> getComprehensionTlvs() {
        return mCompTlvs;
    }

    /**
     * Gets a tag id of the BER-TLV object.
     *
     * @return A tag integer.
     */
    public int getTag() {
        return mTag;
    }

    /**
     * Decodes a BER-TLV object from a byte array.
     *
     * @param data A byte array to decode from
     * @return A BER-TLV object decoded
     * @throws ResultException
     */
    public static BerTlv decode(byte[] data) throws ResultException {
        int curIndex = 0;
        int endIndex = data.length;
        int tag, length = 0;

        try {
            /* tag */
            tag = data[curIndex++] & 0xff;
            if (tag == BER_PROACTIVE_COMMAND_TAG) {
                /* length */
                int temp = data[curIndex++] & 0xff;
                if (temp < 0x80) {
                    length = temp;
                } else if (temp == 0x81) {
                    temp = data[curIndex++] & 0xff;
                    if (temp < 0x80) {
                        throw new ResultException(
                                ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                    }
                    length = temp;
                } else {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            } else {
                if (ComprehensionTlvTag.COMMAND_DETAILS.value() == (tag & ~0x80)) {
                    tag = BER_UNKNOWN_TAG;
                    curIndex = 0;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        } catch (ResultException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        /* COMPREHENSION-TLVs */
        if (endIndex - curIndex < length) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        List<ComprehensionTlv> ctlvs = ComprehensionTlv.decodeMany(data,
                curIndex);

        return new BerTlv(tag, ctlvs);
    }
}
