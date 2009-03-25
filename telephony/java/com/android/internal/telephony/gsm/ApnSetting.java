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
    String[] types;
    int id;
    String numeric;


    ApnSetting(int id, String numeric, String carrier, String apn, String proxy, String port,
            String mmsc, String mmsProxy, String mmsPort,
            String user, String password, String[] types) {
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
        this.types = types;
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
        .append(", ").append(port);
        for (String t : types) {
            sb.append(", ").append(t);
        }
        return sb.toString();
    }

    boolean canHandleType(String type) {
        for (String t : types) {
            if (t.equals(type) || t.equals(Phone.APN_TYPE_ALL)) {
                return true;
            }
        }
        return false;
    }
}
