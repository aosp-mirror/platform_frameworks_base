/*
 * Copyright (C) 2009 Qualcomm Innovation Center, Inc.  All Rights Reserved.
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

package com.android.internal.telephony;

/**
 * This is RIL_Data_Call_Response_v5 from ril.h
 * TODO: Rename to DataCallResponse.
 */
public class DataCallState {
    public int version = 0;
    public int status = 0;
    public int cid = 0;
    public int active = 0;
    public String type = "";
    public String ifname = "";
    public String [] addresses = new String[0];
    public String [] dnses = new String[0];
    public String[] gateways = new String[0];

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("DataCallState: {")
           .append("version=").append(version)
           .append(" status=").append(status)
           .append(" cid=").append(cid)
           .append(" active=").append(active)
           .append(" type=").append(type)
           .append("' ifname='").append(ifname);
        sb.append("' addresses=[");
        for (String addr : addresses) {
            sb.append(addr);
            sb.append(",");
        }
        if (addresses.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("] dnses=[");
        for (String addr : dnses) {
            sb.append(addr);
            sb.append(",");
        }
        if (dnses.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("] gateways=[");
        for (String addr : gateways) {
            sb.append(addr);
            sb.append(",");
        }
        if (gateways.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("]}");
        return sb.toString();
    }
}
