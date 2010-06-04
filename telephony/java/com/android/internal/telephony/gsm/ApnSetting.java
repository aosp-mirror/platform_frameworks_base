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

import com.android.internal.telephony.*;
/**
 * This class represents a apn setting for create PDP link
 */
public class ApnSetting {

    String carrier;
    String apn;
    String proxy;
    String port;
    String mmsc;
    String mmsProxy;
    String mmsPort;
    String user;
    String password;
    int authType;
    public String[] types;
    int id;
    String numeric;


    public ApnSetting(int id, String numeric, String carrier, String apn, String proxy, String port,
            String mmsc, String mmsProxy, String mmsPort,
            String user, String password, int authType, String[] types) {
        this.id = id;
        this.numeric = numeric;
        this.carrier = carrier;
        this.apn = apn;
        this.proxy = proxy;
        this.port = port;
        this.mmsc = mmsc;
        this.mmsProxy = mmsProxy;
        this.mmsPort = mmsPort;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.types = types;
    }

    // data[0] = name
    // data[1] = apn
    // data[2] = proxy
    // data[3] = port
    // data[4] = username
    // data[5] = password
    // data[6] = server
    // data[7] = mmsc
    // data[8] = mmsproxy
    // data[9] = mmsport
    // data[10] = mcc
    // data[11] = mnc
    // data[12] = auth
    // data[13] = first type...
    public static ApnSetting fromString(String data) {
        if (data == null) return null;
        String[] a = data.split("\\s*,\\s*");
        if (a.length < 14) return null;
        int authType = 0;
        try {
            authType = Integer.parseInt(a[12]);
        } catch (Exception e) {
        }
        String[] typeArray = new String[a.length - 13];
        System.arraycopy(a, 13, typeArray, 0, a.length - 13);
        return new ApnSetting(-1,a[10]+a[11],a[0],a[1],a[2],a[3],a[7],a[8],
                a[9],a[4],a[5],authType,typeArray);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(carrier)
        .append(", ").append(id)
        .append(", ").append(numeric)
        .append(", ").append(apn)
        .append(", ").append(proxy)
        .append(", ").append(mmsc)
        .append(", ").append(mmsProxy)
        .append(", ").append(mmsPort)
        .append(", ").append(port)
        .append(", ").append(authType);
        for (String t : types) {
            sb.append(", ").append(t);
        }
        return sb.toString();
    }

    public boolean canHandleType(String type) {
        for (String t : types) {
            // DEFAULT handles all, and HIPRI is handled by DEFAULT
            if (t.equals(type) || t.equals(Phone.APN_TYPE_ALL) ||
                    (t.equals(Phone.APN_TYPE_DEFAULT) &&
                    type.equals(Phone.APN_TYPE_HIPRI))) {
                return true;
            }
        }
        return false;
    }
}
