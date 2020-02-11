/*
 * Copyright (c) 2013 The Android Open Source Project
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


package android.telephony.ims.aidl;

import android.os.PersistableBundle;

import android.telephony.ims.aidl.IImsConfigCallback;

import com.android.ims.ImsConfigListener;

/**
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include items provisioned by the operator.
 *
 * {@hide}
 */
interface IImsConfig {

    void addImsConfigCallback(IImsConfigCallback c);
    void removeImsConfigCallback(IImsConfigCallback c);
    int getConfigInt(int item);
    String getConfigString(int item);
    // Return result code defined in ImsConfig#OperationStatusConstants
    int setConfigInt(int item, int value);
    // Return result code defined in ImsConfig#OperationStatusConstants
    int setConfigString(int item, String value);
    void updateImsCarrierConfigs(in PersistableBundle bundle);
    void notifyRcsAutoConfigurationReceived(in byte[] config, boolean isCompressed);
}
