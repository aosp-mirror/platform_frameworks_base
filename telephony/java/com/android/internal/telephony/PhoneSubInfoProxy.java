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

package com.android.internal.telephony;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ServiceManager;


public class PhoneSubInfoProxy extends IPhoneSubInfo.Stub {
    private PhoneSubInfo mPhoneSubInfo;

    public PhoneSubInfoProxy(PhoneSubInfo phoneSubInfo) {
        mPhoneSubInfo = phoneSubInfo;
        if(ServiceManager.getService("iphonesubinfo") == null) {
            ServiceManager.addService("iphonesubinfo", this);
        }
    }

    public void setmPhoneSubInfo(PhoneSubInfo phoneSubInfo) {
        this.mPhoneSubInfo = phoneSubInfo;
    }

    public String getDeviceId() {
        return mPhoneSubInfo.getDeviceId();
    }

    public String getDeviceSvn() {
        return mPhoneSubInfo.getDeviceSvn();
    }

    /**
     * Retrieves the unique sbuscriber ID, e.g., IMSI for GSM phones.
     */
    public String getSubscriberId() {
        return mPhoneSubInfo.getSubscriberId();
    }

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    public String getIccSerialNumber() {
        return mPhoneSubInfo.getIccSerialNumber();
    }

    /**
     * Retrieves the phone number string for line 1.
     */
    public String getLine1Number() {
        return mPhoneSubInfo.getLine1Number();
    }

    /**
     * Retrieves the alpha identifier for line 1.
     */
    public String getLine1AlphaTag() {
        return mPhoneSubInfo.getLine1AlphaTag();
    }

    /**
     * Retrieves the voice mail number.
     */
    public String getVoiceMailNumber() {
        return mPhoneSubInfo.getVoiceMailNumber();
    }

    /**
     * Retrieves the complete voice mail number.
     */
    public String getCompleteVoiceMailNumber() {
        return mPhoneSubInfo.getCompleteVoiceMailNumber();
    }

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    public String getVoiceMailAlphaTag() {
        return mPhoneSubInfo.getVoiceMailAlphaTag();
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mPhoneSubInfo.dump(fd, pw, args);
    }
}
