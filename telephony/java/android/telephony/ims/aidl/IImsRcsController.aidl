/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.net.Uri;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.telephony.ims.aidl.IImsRegistrationCallback;

import com.android.internal.telephony.IIntegerConsumer;

/**
 * Interface used to interact with the Telephony IMS.
 *
 * {@hide}
 */
interface IImsRcsController {
    // IMS RCS registration commands
    void registerImsRegistrationCallback(int subId, IImsRegistrationCallback c);
    void unregisterImsRegistrationCallback(int subId, IImsRegistrationCallback c);
    void getImsRcsRegistrationState(int subId, IIntegerConsumer consumer);
    void getImsRcsRegistrationTransportType(int subId, IIntegerConsumer consumer);

    // IMS RCS capability commands
    void registerRcsAvailabilityCallback(int subId, IImsCapabilityCallback c);
    void unregisterRcsAvailabilityCallback(int subId, IImsCapabilityCallback c);
    boolean isCapable(int subId, int capability, int radioTech);
    boolean isAvailable(int subId, int capability);

    // ImsUceAdapter specific
    void requestCapabilities(int subId, String callingPackage, String callingFeatureId,
            in List<Uri> contactNumbers, IRcsUceControllerCallback c);
    int getUcePublishState(int subId);
    boolean isUceSettingEnabled(int subId, String callingPackage, String callingFeatureId);
    void setUceSettingEnabled(int subId, boolean isEnabled);
    void registerUcePublishStateCallback(int subId, IRcsUcePublishStateCallback c);
    void unregisterUcePublishStateCallback(int subId, IRcsUcePublishStateCallback c);
}
