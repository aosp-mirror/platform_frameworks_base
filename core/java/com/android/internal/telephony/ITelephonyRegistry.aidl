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

import android.content.Intent;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.telephony.BarringInfo;
import android.telephony.CallQuality;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.LinkCapacityEstimate;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.PhoneCapability;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.MediaQualityStatus;

import com.android.internal.telephony.ICarrierConfigChangeListener;
import com.android.internal.telephony.ICarrierPrivilegesCallback;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ISatelliteStateChangeListener;

interface ITelephonyRegistry {
    void addOnSubscriptionsChangedListener(String pkg, String featureId,
            IOnSubscriptionsChangedListener callback);
    void addOnOpportunisticSubscriptionsChangedListener(String pkg, String featureId,
            IOnSubscriptionsChangedListener callback);
    void removeOnSubscriptionsChangedListener(String pkg,
            IOnSubscriptionsChangedListener callback);

    void listenWithEventList(in boolean renounceFineLocationAccess,
            in boolean renounceCoarseLocationAccess, in int subId, String pkg, String featureId,
            IPhoneStateListener callback, in int[] events, boolean notifyNow);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void notifyCallStateForAllSubs(int state, String incomingNumber);
    void notifyCallState(in int phoneId, in int subId, int state, String incomingNumber);
    void notifyServiceStateForPhoneId(in int phoneId, in int subId, in ServiceState state);
    void notifySignalStrengthForPhoneId(in int phoneId, in int subId,
            in SignalStrength signalStrength);
    void notifyMessageWaitingChangedForPhoneId(in int phoneId, in int subId, in boolean mwi);
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void notifyCallForwardingChanged(boolean cfi);
    void notifyCallForwardingChangedForSubscriber(in int subId, boolean cfi);
    void notifyDataActivityForSubscriber(int subId, int state);
    void notifyDataActivityForSubscriberWithSlot(int phoneId, int subId, int state);
    void notifyDataConnectionForSubscriber(
            int phoneId, int subId, in PreciseDataConnectionState preciseState);
    // Uses CellIdentity which is Parcelable here; will convert to CellLocation in client.
    void notifyCellLocationForSubscriber(in int subId, in CellIdentity cellLocation);
    @UnsupportedAppUsage
    void notifyCellInfo(in List<CellInfo> cellInfo);
    void notifyPreciseCallState(int phoneId, int subId, in int[] callStates, in String[] imsCallIds,
            in int[] imsCallServiceTypes, in int[] imsCallTypes);
    void notifyDisconnectCause(int phoneId, int subId, int disconnectCause,
            int preciseDisconnectCause);
    void notifyCellInfoForSubscriber(in int subId, in List<CellInfo> cellInfo);
    void notifySrvccStateChanged(in int subId, in int lteState);
    void notifySimActivationStateChangedForPhoneId(in int phoneId, in int subId,
            int activationState, int activationType);
    void notifyOemHookRawEventForSubscriber(in int phoneId, in int subId, in byte[] rawData);
    void notifySubscriptionInfoChanged();
    void notifyOpportunisticSubscriptionInfoChanged();
    void notifyCarrierNetworkChange(in boolean active);
    void notifyCarrierNetworkChangeWithSubId(in int subId, in boolean active);
    void notifyUserMobileDataStateChangedForPhoneId(in int phoneId, in int subId, in boolean state);
    void notifyDisplayInfoChanged(int slotIndex, int subId, in TelephonyDisplayInfo telephonyDisplayInfo);
    void notifyPhoneCapabilityChanged(in PhoneCapability capability);
    void notifyActiveDataSubIdChanged(int activeDataSubId);
    void notifyRadioPowerStateChanged(in int phoneId, in int subId, in int state);
    void notifyEmergencyNumberList(in int phoneId, in int subId);
    void notifyOutgoingEmergencyCall(in int phoneId, in int subId,
            in EmergencyNumber emergencyNumber);
    void notifyOutgoingEmergencySms(in int phoneId, in int subId,
            in EmergencyNumber emergencyNumber);
    void notifyCallQualityChanged(in CallQuality callQuality, int phoneId, int subId,
            int callNetworkType);
    void notifyMediaQualityStatusChanged(int phoneId, int subId, in MediaQualityStatus status);
    void notifyImsDisconnectCause(int subId, in ImsReasonInfo imsReasonInfo);
    void notifyRegistrationFailed(int slotIndex, int subId, in CellIdentity cellIdentity,
            String chosenPlmn, int domain, int causeCode, int additionalCauseCode);
    void notifyBarringInfoChanged(int slotIndex, int subId, in BarringInfo barringInfo);
    void notifyPhysicalChannelConfigForSubscriber(in int phoneId, in int subId,
            in List<PhysicalChannelConfig> configs);
    void notifyDataEnabled(in int phoneId, int subId, boolean enabled, int reason);
    void notifyAllowedNetworkTypesChanged(in int phoneId, in int subId, in int reason, in long allowedNetworkType);
    void notifyLinkCapacityEstimateChanged(in int phoneId, in int subId,
            in List<LinkCapacityEstimate> linkCapacityEstimateList);
    void notifySimultaneousCellularCallingSubscriptionsChanged(in int[] subIds);

    void addCarrierPrivilegesCallback(
            int phoneId, ICarrierPrivilegesCallback callback, String pkg, String featureId);
    void removeCarrierPrivilegesCallback(ICarrierPrivilegesCallback callback, String pkg);
    void notifyCarrierPrivilegesChanged(
            int phoneId, in List<String> privilegedPackageNames, in int[] privilegedUids);
    void notifyCarrierServiceChanged(int phoneId, in String packageName, int uid);

    void addCarrierConfigChangeListener(ICarrierConfigChangeListener listener,
            String pkg, String featureId);
    void removeCarrierConfigChangeListener(ICarrierConfigChangeListener listener, String pkg);
    void notifyCarrierConfigChanged(int phoneId, int subId, int carrierId, int specificCarrierId);

    void notifyCallbackModeStarted(int phoneId, int subId, int type, long durationMillis);
    void notifyCallbackModeRestarted(int phoneId, int subId, int type, long durationMillis);
    void notifyCallbackModeStopped(int phoneId, int subId, int type, int reason);
    void notifyCarrierRoamingNtnModeChanged(int subId, in boolean active);
    void notifyCarrierRoamingNtnEligibleStateChanged(int subId, in boolean eligible);
    void notifyCarrierRoamingNtnAvailableServicesChanged(int subId, in int[] availableServices);

    void addSatelliteStateChangeListener(ISatelliteStateChangeListener listener, String pkg, String featureId);
    void removeSatelliteStateChangeListener(ISatelliteStateChangeListener listener, String pkg);
    void notifySatelliteStateChanged(boolean isEnabled);
}
