/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * SmsBroadcastConfigInfo defines one configuration of Cell Broadcast
 * Message (CBM) to be received by the ME
 *
 * fromServiceId - toServiceId defines a range of CBM message identifiers
 * whose value is 0x0000 - 0xFFFF as defined in TS 23.041 9.4.1.2.2 for GMS
 * and 9.4.4.2.2 for UMTS. All other values can be treated as empty
 * CBM message ID.
 *
 * fromCodeScheme - toCodeScheme defines a range of CBM data coding schemes
 * whose value is 0x00 - 0xFF as defined in TS 23.041 9.4.1.2.3 for GMS
 * and 9.4.4.2.3 for UMTS.
 * All other values can be treated as empty CBM data coding scheme.
 *
 * selected false means message types specified in {@code <fromServiceId, toServiceId>}
 * and {@code <fromCodeScheme, toCodeScheme>} are not accepted, while true means accepted.
 *
 */
public final class SmsBroadcastConfigInfo {
    private int mFromServiceId;
    private int mToServiceId;
    private int mFromCodeScheme;
    private int mToCodeScheme;
    private boolean mSelected;

    /**
     * Initialize the object from rssi and cid.
     */
    public SmsBroadcastConfigInfo(int fromId, int toId, int fromScheme,
            int toScheme, boolean selected) {
        mFromServiceId = fromId;
        mToServiceId = toId;
        mFromCodeScheme = fromScheme;
        mToCodeScheme = toScheme;
        mSelected = selected;
    }

    /**
     * @param fromServiceId the fromServiceId to set
     */
    public void setFromServiceId(int fromServiceId) {
        mFromServiceId = fromServiceId;
    }

    /**
     * @return the fromServiceId
     */
    public int getFromServiceId() {
        return mFromServiceId;
    }

    /**
     * @param toServiceId the toServiceId to set
     */
    public void setToServiceId(int toServiceId) {
        mToServiceId = toServiceId;
    }

    /**
     * @return the toServiceId
     */
    public int getToServiceId() {
        return mToServiceId;
    }

    /**
     * @param fromCodeScheme the fromCodeScheme to set
     */
    public void setFromCodeScheme(int fromCodeScheme) {
        mFromCodeScheme = fromCodeScheme;
    }

    /**
     * @return the fromCodeScheme
     */
    public int getFromCodeScheme() {
        return mFromCodeScheme;
    }

    /**
     * @param toCodeScheme the toCodeScheme to set
     */
    public void setToCodeScheme(int toCodeScheme) {
        mToCodeScheme = toCodeScheme;
    }

    /**
     * @return the toCodeScheme
     */
    public int getToCodeScheme() {
        return mToCodeScheme;
    }

    /**
     * @param selected the selected to set
     */
    public void setSelected(boolean selected) {
        mSelected = selected;
    }

    /**
     * @return the selected
     */
    public boolean isSelected() {
        return mSelected;
    }

    @Override
    public String toString() {
        return "SmsBroadcastConfigInfo: Id [" +
                mFromServiceId + ',' + mToServiceId + "] Code [" +
                mFromCodeScheme + ',' + mToCodeScheme + "] " +
            (mSelected ? "ENABLED" : "DISABLED");
    }
}
