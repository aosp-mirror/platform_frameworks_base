/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.telephony.BarringInfo;
import android.telephony.CallAttributes;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.LinkCapacityEstimate;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.PhoneCapability;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;

/**
 * {@hide}
 */
oneway interface IPhoneStateListener {
    void onServiceStateChanged(in ServiceState serviceState);
    void onSignalStrengthChanged(int asu);
    void onMessageWaitingIndicatorChanged(boolean mwi);
    void onCallForwardingIndicatorChanged(boolean cfi);

    // Uses CellIdentity which is Parcelable here; will convert to CellLocation in client.
    void onCellLocationChanged(in CellIdentity location);
    void onCallStateChanged(int state, String incomingNumber);
    void onDataConnectionStateChanged(int state, int networkType);
    void onDataActivity(int direction);
    void onSignalStrengthsChanged(in SignalStrength signalStrength);
    void onCellInfoChanged(in List<CellInfo> cellInfo);
    void onPreciseCallStateChanged(in PreciseCallState callState);
    void onPreciseDataConnectionStateChanged(in PreciseDataConnectionState dataConnectionState);
    void onDataConnectionRealTimeInfoChanged(in DataConnectionRealTimeInfo dcRtInfo);
    void onSrvccStateChanged(in int state);
    void onVoiceActivationStateChanged(int activationState);
    void onDataActivationStateChanged(int activationState);
    void onOemHookRawEvent(in byte[] rawData);
    void onCarrierNetworkChange(in boolean active);
    void onUserMobileDataStateChanged(in boolean enabled);
    void onDisplayInfoChanged(in TelephonyDisplayInfo telephonyDisplayInfo);
    void onPhoneCapabilityChanged(in PhoneCapability capability);
    void onActiveDataSubIdChanged(in int subId);
    void onRadioPowerStateChanged(in int state);
    void onCallAttributesChanged(in CallAttributes callAttributes);
    @SuppressWarnings(value={"untyped-collection"})
    void onEmergencyNumberListChanged(in Map emergencyNumberList);
    void onOutgoingEmergencyCall(in EmergencyNumber placedEmergencyNumber, int subscriptionId);
    void onOutgoingEmergencySms(in EmergencyNumber sentEmergencyNumber, int subscriptionId);
    void onCallDisconnectCauseChanged(in int disconnectCause, in int preciseDisconnectCause);
    void onImsCallDisconnectCauseChanged(in ImsReasonInfo imsReasonInfo);
    void onRegistrationFailed(in CellIdentity cellIdentity,
             String chosenPlmn, int domain, int causeCode, int additionalCauseCode);
    void onBarringInfoChanged(in BarringInfo barringInfo);
    void onPhysicalChannelConfigChanged(in List<PhysicalChannelConfig> configs);
    void onDataEnabledChanged(boolean enabled, int reason);
    void onAllowedNetworkTypesChanged(in int reason, in long allowedNetworkType);
    void onLinkCapacityEstimateChanged(in List<LinkCapacityEstimate> linkCapacityEstimateList);
}
