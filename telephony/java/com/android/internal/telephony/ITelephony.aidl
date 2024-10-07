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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.WorkSource;
import android.net.NetworkStats;
import android.net.Uri;
import android.service.carrier.CarrierIdentifier;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.CallForwardingInfo;
import android.telephony.CarrierRestrictionRules;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.CellBroadcastIdRange;
import android.telephony.ClientRequestStats;
import android.telephony.ThermalMitigationRequest;
import android.telephony.gba.UaSecurityProtocolIdentifier;
import android.telephony.IBootstrapAuthenticationCallback;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.ICellInfoCallback;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.NetworkScanRequest;
import android.telephony.PhoneCapability;
import android.telephony.PhoneNumberRange;
import android.telephony.RadioAccessFamily;
import android.telephony.RadioAccessSpecifier;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SignalStrengthUpdateRequest;
import android.telephony.TelephonyHistogram;
import android.telephony.VisualVoicemailSmsFilterSettings;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.RcsClientConfiguration;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.aidl.IFeatureProvisioningCallback;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IRcsConfigCallback;
import android.telephony.satellite.INtnSignalStrengthCallback;
import android.telephony.satellite.ISatelliteCapabilitiesCallback;
import android.telephony.satellite.ISatelliteCommunicationAllowedStateCallback;
import android.telephony.satellite.ISatelliteDatagramCallback;
import android.telephony.satellite.ISatelliteTransmissionUpdateCallback;
import android.telephony.satellite.ISatelliteProvisionStateCallback;
import android.telephony.satellite.ISatelliteSupportedStateCallback;
import android.telephony.satellite.ISatelliteModemStateCallback;
import android.telephony.satellite.NtnSignalStrength;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteSubscriberInfo;
import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.ICallForwardingInfoCallback;
import com.android.internal.telephony.IccLogicalChannelRequest;
import com.android.internal.telephony.IImsStateCallback;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.INumberVerificationCallback;
import com.android.internal.telephony.OperatorInfo;

import java.util.List;
import java.util.Map;

import android.telephony.UiccCardInfo;
import android.telephony.UiccSlotInfo;
import android.telephony.UiccSlotMapping;

/**
 * Interface used to interact with the phone.  Mostly this is used by the
 * TelephonyManager class.  A few places are still using this directly.
 * Please clean them up if possible and use TelephonyManager instead.
 *
 * {@hide}
 */
interface ITelephony {

    /**
     * Dial a number. This doesn't place the call. It displays
     * the Dialer screen.
     * @param number the number to be dialed. If null, this
     * would display the Dialer screen with no number pre-filled.
     */
    @UnsupportedAppUsage
    void dial(String number);

    /**
     * Place a call to the specified number.
     * @param callingPackage The package making the call.
     * @param number the number to be called.
     */
    @UnsupportedAppUsage
    void call(String callingPackage, String number);

    /** @deprecated Use {@link #isRadioOnWithFeature(String, String) instead */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isRadioOn(String callingPackage);

    /**
     * Check to see if the radio is on or not.
     * @param callingPackage the name of the package making the call.
     * @param callingFeatureId The feature in the package.
     * @return returns true if the radio is on.
     */
    boolean isRadioOnWithFeature(String callingPackage, String callingFeatureId);

    /**
     * @deprecated Use {@link #isRadioOnForSubscriberWithFeature(int, String, String) instead
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isRadioOnForSubscriber(int subId, String callingPackage);

    /**
     * Check to see if the radio is on or not on particular subId.
     * @param subId user preferred subId.
     * @param callingPackage the name of the package making the call.
     * @param callingFeatureId The feature in the package.
     * @return returns true if the radio is on.
     */
    boolean isRadioOnForSubscriberWithFeature(int subId, String callingPackage, String callingFeatureId);

    /**
     * Set the user-set status for enriched calling with call composer.
     */
    void setCallComposerStatus(int subId, int status);

    /**
     * Get the user-set status for enriched calling with call composer.
     */
    int getCallComposerStatus(int subId);

    /**
     * Supply a pin to unlock the SIM for particular subId.
     * Blocks until a result is determined.
     * @param pin The pin to check.
     * @param subId user preferred subId.
     * @return whether the operation was a success.
     */
    boolean supplyPinForSubscriber(int subId, String pin);

    /**
     * Supply puk to unlock the SIM and set SIM pin to new pin.
     *  Blocks until a result is determined.
     * @param puk The puk to check.
     *        pin The new pin to be set in SIM
     * @param subId user preferred subId.
     * @return whether the operation was a success.
     */
    boolean supplyPukForSubscriber(int subId, String puk, String pin);

    /**
     * Supply a pin to unlock the SIM.  Blocks until a result is determined.
     * Returns a specific success/error code.
     * @param pin The pin to check.
     * @return retValue[0] = Phone.PIN_RESULT_SUCCESS on success. Otherwise error code
     *         retValue[1] = number of attempts remaining if known otherwise -1
     */
    int[] supplyPinReportResultForSubscriber(int subId, String pin);

    /**
     * Supply puk to unlock the SIM and set SIM pin to new pin.
     * Blocks until a result is determined.
     * Returns a specific success/error code
     * @param puk The puk to check
     *        pin The pin to check.
     * @return retValue[0] = Phone.PIN_RESULT_SUCCESS on success. Otherwise error code
     *         retValue[1] = number of attempts remaining if known otherwise -1
     */
    int[] supplyPukReportResultForSubscriber(int subId, String puk, String pin);

    /**
     * Handles PIN MMI commands (PIN/PIN2/PUK/PUK2), which are initiated
     * without SEND (so <code>dial</code> is not appropriate).
     *
     * @param dialString the MMI command to be executed.
     * @return true if MMI command is executed.
     */
    @UnsupportedAppUsage
    boolean handlePinMmi(String dialString);


    /**
     * Handles USSD commands.
     *
     * @param subId The subscription to use.
     * @param ussdRequest the USSD command to be executed.
     * @param wrappedCallback receives a callback result.
     */
    void handleUssdRequest(int subId, String ussdRequest, in ResultReceiver wrappedCallback);

    /**
     * Handles PIN MMI commands (PIN/PIN2/PUK/PUK2), which are initiated
     * without SEND (so <code>dial</code> is not appropriate) for
     * a particular subId.
     * @param dialString the MMI command to be executed.
     * @param subId user preferred subId.
     * @return true if MMI command is executed.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean handlePinMmiForSubscriber(int subId, String dialString);

    /**
     * Toggles the radio on or off.
     */
    @UnsupportedAppUsage
    void toggleRadioOnOff();

    /**
     * Toggles the radio on or off on particular subId.
     * @param subId user preferred subId.
     */
    void toggleRadioOnOffForSubscriber(int subId);

    /**
     * Set the radio to on or off
     */
    @UnsupportedAppUsage
    boolean setRadio(boolean turnOn);

    /**
     * Set the radio to on or off on particular subId.
     * @param subId user preferred subId.
     */
    boolean setRadioForSubscriber(int subId, boolean turnOn);

    /**
     * Set the radio to on or off unconditionally
     */
    boolean setRadioPower(boolean turnOn);

    /**
     * Vote on powering off the radio for a reason. The radio will be turned on only when there is
     * no reason to power it off. When any of the voters want to power it off, it will be turned
     * off. In case of emergency, the radio will be turned on even if there are some reasons for
     * powering it off, and these radio off votes will be cleared.
     * Multiple apps can vote for the same reason and the last vote will take effect. Each app is
     * responsible for its vote. A powering-off vote of a reason will be maintained until it is
     * cleared by calling {@link clearRadioPowerOffForReason} for that reason, or an emergency call
     * is made, or the device is rebooted. When an app comes backup from a crash, it needs to make
     * sure if its vote is as expected. An app can use the API {@link getRadioPowerOffReasons} to
     * check its vote.
     *
     * @param subId The subscription ID.
     * @param reason The reason for powering off radio.
     * @return true on success and false on failure.
     */
    boolean requestRadioPowerOffForReason(int subId, int reason);

    /**
     * Remove the vote on powering off the radio for a reasonas, requested by
     * {@link requestRadioPowerOffForReason}.
     *
     * @param subId The subscription ID.
     * @param reason The reason for powering off radio.
     * @return true on success and false on failure.
     */
    boolean clearRadioPowerOffForReason(int subId, int reason);

    /**
     * Get reasons for powering off radio, as requested by {@link requestRadioPowerOffForReason}.
     *
     * @param subId The subscription ID.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return List of reasons for powering off radio.
     */
    List getRadioPowerOffReasons(int subId, String callingPackage, String callingFeatureId);

    /**
     * This method has been removed due to security and stability issues.
     */
    @UnsupportedAppUsage
    void updateServiceLocation();

    /**
     * Version of updateServiceLocation that records the caller and validates permissions.
     */
    void updateServiceLocationWithPackageName(String callingPkg);

    /**
     * This method has been removed due to security and stability issues.
     */
    @UnsupportedAppUsage
    void enableLocationUpdates();

    /**
     * This method has been removed due to security and stability issues.
     */
    @UnsupportedAppUsage
    void disableLocationUpdates();

    /**
     * Allow mobile data connections.
     */
    @UnsupportedAppUsage
    boolean enableDataConnectivity(String callingPackage);

    /**
     * Disallow mobile data connections.
     */
    @UnsupportedAppUsage
    boolean disableDataConnectivity(String callingPackage);

    /**
     * Report whether data connectivity is possible.
     */
    boolean isDataConnectivityPossible(int subId);

    // Uses CellIdentity which is Parcelable here; will convert to CellLocation in client.
    CellIdentity getCellLocation(String callingPkg, String callingFeatureId);

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @see android.telephony.TelephonyManager#getNetworkCountryIso
     */
    String getNetworkCountryIsoForPhone(int phoneId);

    /**
     * Returns the neighboring cell information of the device.
     */
    List<NeighboringCellInfo> getNeighboringCellInfo(String callingPkg, String callingFeatureId);

    @UnsupportedAppUsage
    int getCallState();

    /**
     * Returns the call state for a specific subscriiption.
     */
    int getCallStateForSubscription(int subId, String callingPackage, String featureId);

    /**
     * Replaced by getDataActivityForSubId.
     */
    @UnsupportedAppUsage(maxTargetSdk = 28)
    int getDataActivity();

    /**
     * Returns a constant indicating the type of activity on a data connection
     * (cellular).
     *
     * @see #DATA_ACTIVITY_NONE
     * @see #DATA_ACTIVITY_IN
     * @see #DATA_ACTIVITY_OUT
     * @see #DATA_ACTIVITY_INOUT
     * @see #DATA_ACTIVITY_DORMANT
     */
    int getDataActivityForSubId(int subId);

    /**
     * Replaced by getDataStateForSubId.
     */
    @UnsupportedAppUsage(maxTargetSdk = 28)
    int getDataState();

    /**
     * Returns a constant indicating the current data connection state
     * (cellular).
     *
     * @see #DATA_DISCONNECTED
     * @see #DATA_CONNECTING
     * @see #DATA_CONNECTED
     * @see #DATA_SUSPENDED
     */
    int getDataStateForSubId(int subId);

    /**
     * Returns the current active phone type as integer.
     * Returns TelephonyManager.PHONE_TYPE_CDMA if RILConstants.CDMA_PHONE
     * and TelephonyManager.PHONE_TYPE_GSM if RILConstants.GSM_PHONE
     */
    @UnsupportedAppUsage
    int getActivePhoneType();

    /**
     * Returns the current active phone type as integer for particular slot.
     * Returns TelephonyManager.PHONE_TYPE_CDMA if RILConstants.CDMA_PHONE
     * and TelephonyManager.PHONE_TYPE_GSM if RILConstants.GSM_PHONE
     * @param slotIndex - slot to query.
     */
    int getActivePhoneTypeForSlot(int slotIndex);

    /**
     * Returns the CDMA ERI icon index to display
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getCdmaEriIconIndex(String callingPackage, String callingFeatureId);

    /**
     * Returns the CDMA ERI icon index to display on particular subId.
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getCdmaEriIconIndexForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getCdmaEriIconMode(String callingPackage, String callingFeatureId);

    /**
     * Returns the CDMA ERI icon mode on particular subId,
     * 0 - ON
     * 1 - FLASHING
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getCdmaEriIconModeForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Returns the CDMA ERI text,
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    String getCdmaEriText(String callingPackage, String callingFeatureId);

    /**
     * Returns the CDMA ERI text for particular subId,
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    String getCdmaEriTextForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Returns true if OTA service provisioning needs to run.
     * Only relevant on some technologies, others will always
     * return false.
     */
    boolean needsOtaServiceProvisioning();

    /**
     * Sets the voicemail number for a particular subscriber.
     */
    boolean setVoiceMailNumber(int subId, String alphaTag, String number);

     /**
      * Sets the voice activation state for a particular subscriber.
      */
    void setVoiceActivationState(int subId, int activationState);

     /**
      * Sets the data activation state for a particular subscriber.
      */
    void setDataActivationState(int subId, int activationState);

     /**
      * Returns the voice activation state for a particular subscriber.
      * @param subId user preferred sub
      * @param callingPackage package queries voice activation state
      */
    int getVoiceActivationState(int subId, String callingPackage);

     /**
      * Returns the data activation state for a particular subscriber.
      * @param subId user preferred sub
      * @param callingPackage package queris data activation state
      */
    int getDataActivationState(int subId, String callingPackage);

    /**
     * Returns the unread count of voicemails for a subId.
     * @param subId user preferred subId.
     * Returns the unread count of voicemails
     */
    int getVoiceMessageCountForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
      * Returns true if current state supports both voice and data
      * simultaneously. This can change based on location or network condition.
      */
    boolean isConcurrentVoiceAndDataAllowed(int subId);

    Bundle getVisualVoicemailSettings(String callingPackage, int subId);

    String getVisualVoicemailPackageName(String callingPackage, String callingFeatureId, int subId);

    // Not oneway, caller needs to make sure the vaule is set before receiving a SMS
    void enableVisualVoicemailSmsFilter(String callingPackage, int subId,
            in VisualVoicemailSmsFilterSettings settings);

    oneway void disableVisualVoicemailSmsFilter(String callingPackage, int subId);

    // Get settings set by the calling package
    VisualVoicemailSmsFilterSettings getVisualVoicemailSmsFilterSettings(String callingPackage,
            int subId);

    /**
     *  Get settings set by the current default dialer, Internal use only.
     *  Requires READ_PRIVILEGED_PHONE_STATE permission.
     */
    VisualVoicemailSmsFilterSettings getActiveVisualVoicemailSmsFilterSettings(int subId);

    /**
     * Send a visual voicemail SMS. Internal use only.
     * Requires caller to be the default dialer and have SEND_SMS permission
     */
    void sendVisualVoicemailSmsForSubscriber(in String callingPackage, String callingAttributeTag,
            in int subId, in String number, in int port, in String text, in PendingIntent sentIntent);

    // Send the special dialer code. The IPC caller must be the current default dialer.
    void sendDialerSpecialCode(String callingPackageName, String inputCode);

    /**
     * Returns the network type of a subId.
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getNetworkTypeForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Returns the network type for data transmission
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getDataNetworkType(String callingPackage, String callingFeatureId);

    /**
     * Returns the data network type of a subId
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     */
    int getDataNetworkTypeForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
      * Returns the voice network type of a subId
      * @param subId user preferred subId.
      * @param callingPackage package making the call.getLteOnCdmaMode
      * @param callingFeatureId The feature in the package.
      * Returns the network type
      */
    int getVoiceNetworkTypeForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Return true if an ICC card is present
     */
    @UnsupportedAppUsage
    boolean hasIccCard();

    /**
     * Return true if an ICC card is present for a subId.
     * @param slotIndex user preferred slotIndex.
     * Return true if an ICC card is present
     */
    boolean hasIccCardUsingSlotIndex(int slotIndex);

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param callingPackage the name of the calling package
     * @param callingFeatureId The feature in the package.
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    int getLteOnCdmaMode(String callingPackage, String callingFeatureId);

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param callingPackage the name of the calling package
     * @param callingFeatureId The feature in the package.
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    int getLteOnCdmaModeForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Returns all observed cell information of the device.
     */
    List<CellInfo> getAllCellInfo(String callingPkg, String callingFeatureId);

    /**
     * Request a cell information update for the specified subscription,
     * reported via the CellInfoCallback.
     */
    void requestCellInfoUpdate(int subId, in ICellInfoCallback cb, String callingPkg,
            String callingFeatureId);

    /**
     * Request a cell information update for the specified subscription,
     * reported via the CellInfoCallback.
     *
     * @param workSource the requestor to whom the power consumption for this should be attributed.
     */
    void requestCellInfoUpdateWithWorkSource(int subId, in ICellInfoCallback cb,
            in String callingPkg, String callingFeatureId, in WorkSource ws);

    /**
     * Sets minimum time in milli-seconds between onCellInfoChanged
     */
    void setCellInfoListRate(int rateInMillis, int subId);

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * @param request the parcelable used to indicate how to open the logical channel.
     * @return an IccOpenLogicalChannelResponse object.
     */
    IccOpenLogicalChannelResponse iccOpenLogicalChannel(in IccLogicalChannelRequest request);

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * @param request the parcelable used to indicate how to close the logical channel.
     * @return true if the channel was closed successfully.
     */
    boolean iccCloseLogicalChannel(in IccLogicalChannelRequest request);

    /**
     * Transmit an APDU to the ICC card over a logical channel using the physical slot index and port index.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * @param slotIndex The physical slot index of the target ICC card
     * @param portIndex The unique index referring to a port belonging to the SIM slot
     * @param channel is the channel id to be closed as returned by a
     *            successful iccOpenLogicalChannel.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    String iccTransmitApduLogicalChannelByPort(int slotIndex, int portIndex, int channel, int cla, int instruction,
            int p1, int p2, int p3, String data);

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * @param subId The subscription to use.
     * @param channel is the channel id to be closed as returned by a
     *            successful iccOpenLogicalChannel.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    @UnsupportedAppUsage(trackingBug = 171933273)
    String iccTransmitApduLogicalChannel(int subId, int channel, int cla, int instruction,
            int p1, int p2, int p3, String data);

    /**
     * Transmit an APDU to the ICC card over the basic channel using the physical slot index and port index.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * @param slotIndex The physical slot index of the target ICC card
     * @param portIndex The unique index referring to a port belonging to the SIM slot
     * @param callingPackage the name of the package making the call.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    String iccTransmitApduBasicChannelByPort(int slotIndex, int portIndex, String callingPackage, int cla,
            int instruction, int p1, int p2, int p3, String data);

    /**
     * Transmit an APDU to the ICC card over the basic channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * @param subId The subscription to use.
     * @param callingPackage the name of the package making the call.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    String iccTransmitApduBasicChannel(int subId, String callingPackage, int cla, int instruction,
            int p1, int p2, int p3, String data);

    /**
     * Returns the response APDU for a command APDU sent through SIM_IO.
     *
     * @param subId The subscription to use.
     * @param fileID
     * @param command
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command.
     * @param filePath
     * @return The APDU response.
     */
    byte[] iccExchangeSimIO(int subId, int fileID, int command, int p1, int p2, int p3,
            String filePath);

    /**
     * Send ENVELOPE to the SIM and returns the response.
     *
     * @param subId The subscription to use.
     * @param contents  String containing SAT/USAT response in hexadecimal
     *                  format starting with command tag. See TS 102 223 for
     *                  details.
     * @return The APDU response from the ICC card, with the last 4 bytes
     *         being the status word. If the command fails, returns an empty
     *         string.
     */
    String sendEnvelopeWithStatus(int subId, String content);

    /**
     * Read one of the NV items defined in {@link RadioNVItems} / {@code ril_nv_items.h}.
     * Used for device configuration by some CDMA operators.
     *
     * @param itemID the ID of the item to read.
     * @return the NV item as a String, or null on any failure.
     */
    String nvReadItem(int itemID);

    /**
     * Write one of the NV items defined in {@link RadioNVItems} / {@code ril_nv_items.h}.
     * Used for device configuration by some CDMA operators.
     *
     * @param itemID the ID of the item to read.
     * @param itemValue the value to write, as a String.
     * @return true on success; false on any failure.
     */
    boolean nvWriteItem(int itemID, String itemValue);

    /**
     * Update the CDMA Preferred Roaming List (PRL) in the radio NV storage.
     * Used for device configuration by some CDMA operators.
     *
     * @param preferredRoamingList byte array containing the new PRL.
     * @return true on success; false on any failure.
     */
    boolean nvWriteCdmaPrl(in byte[] preferredRoamingList);

    /**
     * Rollback modem configurations to factory default except some config which are in whitelist.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex - device slot.
     * @return {@code true} on success; {@code false} on any failure.
     */
    boolean resetModemConfig(int slotIndex);

    /**
     * Generate a radio modem reset. Used for device configuration by some CDMA operators.
     * Different than {@link #setRadioPower(boolean)}, modem reboot will power down sim card.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex - device slot.
     * @return {@code true} on success; {@code false} on any failure.
     */
    boolean rebootModem(int slotIndex);

    /*
     * Get the allowed network type.
     * Used for device configuration by some CDMA operators.
     *
     * @param subId the id of the subscription to query.
     * @return the allowed network type bitmask, defined in RILConstants.java.
     */
    int getAllowedNetworkTypesBitmask(int subId);

    /**
     * Check whether DUN APN is required for tethering with subId.
     *
     * @param subId the id of the subscription to require tethering.
     * @return {@code true} if DUN APN is required for tethering.
     * @hide
     */
    boolean isTetheringApnRequiredForSubscriber(int subId);

    /**
    * Enables framework IMS and triggers IMS Registration.
    */
    void enableIms(int slotId);

    /**
    * Disables framework IMS and triggers IMS deregistration.
    */
    void disableIms(int slotId);

    /**
    * Toggle framework IMS disables and enables.
    */
    void resetIms(int slotIndex);

    /**
     *  Get IImsMmTelFeature binder from ImsResolver that corresponds to the subId and MMTel feature
     *  as well as registering the MmTelFeature for callbacks using the IImsServiceFeatureCallback
     *  interface.
     */
    void registerMmTelFeatureCallback(int slotId, in IImsServiceFeatureCallback callback);

    /**
     * Unregister a callback that was previously registered through
     * {@link #registerMmTelFeatureCallback}. This should always be called when the callback is no
     * longer being used.
     */
    void unregisterImsFeatureCallback(in IImsServiceFeatureCallback callback);

    /**
    * Returns the IImsRegistration associated with the slot and feature specified.
    */
    IImsRegistration getImsRegistration(int slotId, int feature);

    /**
    * Returns the IImsConfig associated with the slot and feature specified.
    */
    IImsConfig getImsConfig(int slotId, int feature);

    /**
    *  @return true if the ImsService to bind to for the slot id specified was set, false otherwise.
    */
    boolean setBoundImsServiceOverride(int slotIndex, boolean isCarrierService,
            in int[] featureTypes, in String packageName);

    /**
     *  @return true if the ImsService cleared any carrier ImsService overrides, false otherwise.
     */
    boolean clearCarrierImsServiceOverride(int slotIndex);

    /**
    * @return the package name of the carrier/device ImsService associated with this slot.
    */
    String getBoundImsServicePackage(int slotIndex, boolean isCarrierImsService, int featureType);

    /**
     * Get the MmTelFeature state attached to this subscription id.
     */
    void getImsMmTelFeatureState(int subId, IIntegerConsumer callback);

    /**
     * Set the network selection mode to automatic.
     *
     * @param subId the id of the subscription to update.
     */
    void setNetworkSelectionModeAutomatic(int subId);

    /**
     * Perform a radio scan and return the list of avialble networks.
     *
     * @param subId the id of the subscription.
     * @param callingPackage the calling package
     * @param callingFeatureId The feature in the package
     * @return CellNetworkScanResult containing status of scan and networks.
     */
    CellNetworkScanResult getCellNetworkScanResults(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Perform a radio network scan and return the id of this scan.
     *
     * @param subId the id of the subscription.
     * @param renounceFineLocationAccess Set this to true if the caller would not like to
     * receive fine location related information
     * @param request Defines all the configs for network scan.
     * @param messenger Callback messages will be sent using this messenger.
     * @param binder the binder object instantiated in TelephonyManager.
     * @param callingPackage the calling package
     * @param callingFeatureId The feature in the package
     * @return An id for this scan.
     */
    int requestNetworkScan(int subId, in boolean renounceFineLocationAccess,
            in NetworkScanRequest request, in Messenger messenger, in IBinder binder,
	    in String callingPackage, String callingFeatureId);

    /**
     * Stop an existing radio network scan.
     *
     * @param subId the id of the subscription.
     * @param scanId The id of the scan that is going to be stopped.
     */
    void stopNetworkScan(int subId, int scanId);

    /**
     * Ask the radio to connect to the input network and change selection mode to manual.
     *
     * @param subId the id of the subscription.
     * @param operatorInfo the operator inforamtion, included the PLMN, long name and short name of
     * the operator to attach to.
     * @param persistSelection whether the selection will persist until reboot. If true, only allows
     * attaching to the selected PLMN until reboot; otherwise, attach to the chosen PLMN and resume
     * normal network selection next time.
     * @return {@code true} on success; {@code true} on any failure.
     */
    boolean setNetworkSelectionModeManual(
            int subId, in OperatorInfo operatorInfo, boolean persisSelection);

    /**
     * Get the allowed network types for certain reason.
     *
     * @param subId the id of the subscription.
     * @param reason the reason the allowed network type change is taking place
     * @return allowedNetworkTypes the allowed network types.
     */
    long getAllowedNetworkTypesForReason(int subId, int reason);

    /**
     * Set the allowed network types and provide the reason triggering the allowed network change.
     *
     * @param subId the id of the subscription.
     * @param reason the reason the allowed network type change is taking place
     * @param allowedNetworkTypes the allowed network types.
     * @return true on success; false on any failure.
     */
    boolean setAllowedNetworkTypesForReason(int subId, int reason, long allowedNetworkTypes);

    /**
     * Get the user enabled state of Mobile Data.
     *
     * TODO: remove and use isUserDataEnabled.
     * This can't be removed now because some vendor codes
     * calls through ITelephony directly while they should
     * use TelephonyManager.
     *
     * @return true on enabled
     */
    @UnsupportedAppUsage
    boolean getDataEnabled(int subId);

    /**
     * Get the user enabled state of Mobile Data.
     *
     * @return true on enabled
     */
    boolean isUserDataEnabled(int subId);

    /**
     * Check if data is enabled on the device. It can be disabled by
     * user, carrier, policy or thermal.
     * @return true on enabled
     */
    boolean isDataEnabled(int subId);

    /**
     * Control of data connection and provide the reason triggering the data connection control.
     *
     * @param subId user preferred subId.
     * @param reason the reason the data enable change is taking place
     * @param enable true to turn on, else false
     * @param callingPackage the package that changed the data enabled state
     */
     void setDataEnabledForReason(int subId, int reason, boolean enable, String callingPackage);

    /**
     * Return whether data is enabled for certain reason
     * @param subId user preferred subId.       .
     * @param reason the reason the data enable change is taking place
     * @return true on enabled
    */
    boolean isDataEnabledForReason(int subId, int reason);

     /**
     * Checks if manual network selection is allowed.
     *
     * @return {@code true} if manual network selection is allowed, otherwise return {@code false}.
     */
     boolean isManualNetworkSelectionAllowed(int subId);

    /**
     * Set IMS registration state
     */
    void setImsRegistrationState(boolean registered);

    /**
     * Return MDN string for CDMA phone.
     * @param subId user preferred subId.
     */
    String getCdmaMdn(int subId);

    /**
     * Return MIN string for CDMA phone.
     * @param subId user preferred subId.
     */
    String getCdmaMin(int subId);

    /**
     * Request that the next incoming call from a number matching {@code range} be intercepted.
     * @param range The range of phone numbers the caller expects a phone call from.
     * @param timeoutMillis The amount of time to wait for such a call, or
     *                      {@link #MAX_NUMBER_VERIFICATION_TIMEOUT_MILLIS}, whichever is lesser.
     * @param callback the callback aidl
     * @param callingPackage the calling package name.
     */
    void requestNumberVerification(in PhoneNumberRange range, long timeoutMillis,
            in INumberVerificationCallback callback, String callingPackage);

    /**
     * Has the calling application been granted special privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * TODO: Add a link to documentation.
     *
     * @param subId The subscription to use.
     * @return carrier privilege status defined in TelephonyManager.
     */
    int getCarrierPrivilegeStatus(int subId);

    /**
     * Similar to above, but check for the given uid.
     */
    int getCarrierPrivilegeStatusForUid(int subId, int uid);

    /**
     * Similar to above, but check for the package whose name is pkgName.
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     */
    int checkCarrierPrivilegesForPackage(int subId, String pkgName);

    /**
     * Similar to above, but check across all phones.
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     */
    int checkCarrierPrivilegesForPackageAnyPhone(String pkgName);

    /**
     * Returns list of the package names of the carrier apps that should handle the input intent
     * and have carrier privileges for the given phoneId.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param intent Intent that will be sent.
     * @param phoneId The phoneId on which the carrier app has carrier privileges.
     * @return list of carrier app package names that can handle the intent on phoneId.
     *         Returns null if there is an error and an empty list if there
     *         are no matching packages.
     */
    List<String> getCarrierPackageNamesForIntentAndPhone(in Intent intent, int phoneId);

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * @param subId the subscriber that the alphatag and dialing number belongs to.
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     */
    boolean setLine1NumberForDisplayForSubscriber(int subId, String alphaTag, String number);

    /**
     * Returns the displayed dialing number string if it was set previously via
     * {@link #setLine1NumberForDisplay}. Otherwise returns null.
     *
     * @param subId whose dialing number for line 1 is returned.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return the displayed dialing number if set, or null if not set.
     */
    String getLine1NumberForDisplay(int subId, String callingPackage, String callingFeatureId);

    /**
     * Returns the displayed alphatag of the dialing number if it was set
     * previously via {@link #setLine1NumberForDisplay}. Otherwise returns null.
     *
     * @param subId whose alphatag associated with line 1 is returned.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return the displayed alphatag of the dialing number if set, or null if
     *         not set.
     */
    String getLine1AlphaTagForDisplay(int subId, String callingPackage, String callingFeatureId);

    /**
     * Return the set of subscriber IDs that should be considered "merged together" for data usage
     * purposes. This is commonly {@code null} to indicate no merging is required. Any returned
     * subscribers are sorted in a deterministic order.
     * <p>
     * The returned set of subscriber IDs will include the subscriber ID corresponding to this
     * TelephonyManager's subId.
     *
     * @hide
     */
    String[] getMergedSubscriberIds(int subId, String callingPackage, String callingFeatureId);

    /**
     * @hide
     */
    String[] getMergedImsisFromGroup(int subId, String callingPackage);

    /**
     * Override the operator branding for the current ICCID.
     *
     * Once set, whenever the SIM is present in the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *  or has to be carrier app - see #hasCarrierPrivileges.
     *
     * @param subId The subscription to use.
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     */
    boolean setOperatorBrandOverride(int subId, String brand);

    /**
     * Override the roaming indicator for the current ICCID.
     *
     * Using this call, the carrier app (see #hasCarrierPrivileges) can override
     * the platform's notion of a network operator being considered roaming or not.
     * The change only affects the ICCID that was active when this call was made.
     *
     * If null is passed as any of the input, the corresponding value is deleted.
     *
     * <p>Requires that the caller have carrier privilege. See #hasCarrierPrivileges.
     *
     * @param subId for which the roaming overrides apply.
     * @param gsmRoamingList - List of MCCMNCs to be considered roaming for 3GPP RATs.
     * @param gsmNonRoamingList - List of MCCMNCs to be considered not roaming for 3GPP RATs.
     * @param cdmaRoamingList - List of SIDs to be considered roaming for 3GPP2 RATs.
     * @param cdmaNonRoamingList - List of SIDs to be considered not roaming for 3GPP2 RATs.
     * @return true if the operation was executed correctly.
     */
    boolean setRoamingOverride(int subId, in List<String> gsmRoamingList,
            in List<String> gsmNonRoamingList, in List<String> cdmaRoamingList,
            in List<String> cdmaNonRoamingList);

    /**
     * Check if any mobile Radios need to be shutdown.
     *
     * @return true is any mobile radio needs to be shutdown
     */
    boolean needMobileRadioShutdown();

    /**
     * Shutdown Mobile Radios
     */
    void shutdownMobileRadios();

    /**
     * Get phone radio type and access technology.
     *
     * @param phoneId which phone you want to get
     * @param callingPackage the name of the package making the call
     * @return phone radio type and access technology
     */
    int getRadioAccessFamily(in int phoneId, String callingPackage);

    void uploadCallComposerPicture(int subscriptionId, String callingPackage,
            String contentType, in ParcelFileDescriptor fd, in ResultReceiver callback);

    /**
     * Enables or disables video calling.
     *
     * @param enable Whether to enable video calling.
     */
    void enableVideoCalling(boolean enable);

    /**
     * Whether video calling has been enabled by the user.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return {@code true} if the user has enabled video calling, {@code false} otherwise.
     */
    boolean isVideoCallingEnabled(String callingPackage, String callingFeatureId);

    /**
     * Whether the DTMF tone length can be changed.
     *
     * @param subId The subscription to use.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return {@code true} if the DTMF tone length can be changed.
     */
    boolean canChangeDtmfToneLength(int subId, String callingPackage, String callingFeatureId);

    /**
     * Whether the device is a world phone.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return {@code true} if the devices is a world phone.
     */
    boolean isWorldPhone(int subId, String callingPackage, String callingFeatureId);

    /**
     * Whether the phone supports TTY mode.
     *
     * @return {@code true} if the device supports TTY mode.
     */
    boolean isTtyModeSupported();

    boolean isRttSupported(int subscriptionId);

    /**
     * Whether the phone supports hearing aid compatibility.
     *
     * @return {@code true} if the device supports hearing aid compatibility.
     */
    boolean isHearingAidCompatibilitySupported();

    /**
     * Get IMS Registration Status on a particular subid.
     *
     * @param subId user preferred subId.
     *
     * @return {@code true} if the IMS status is registered.
     */
    boolean isImsRegistered(int subId);

    /**
     * Returns the Status of Wi-Fi Calling for the subscription id specified.
     */
    boolean isWifiCallingAvailable(int subId);

     /**
     * Returns the Status of VT (video telephony) for the subscription ID specified.
     */
    boolean isVideoTelephonyAvailable(int subId);

    /**
    * Returns the MMTEL IMS registration technology for the subsciption ID specified.
    */
    int getImsRegTechnologyForMmTel(int subId);

    /** @deprecated Use {@link #getDeviceIdWithFeature(String, String) instead */
    @UnsupportedAppUsage
    String getDeviceId(String callingPackage);

    /**
      * Returns the unique device ID of phone, for example, the IMEI for
      * GSM and the MEID for CDMA phones. Return null if device ID is not available.
      *
      * @param callingPackage The package making the call.
      * @param callingFeatureId The feature in the package
      * <p>Requires Permission:
      *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
      */
    String getDeviceIdWithFeature(String callingPackage, String callingFeatureId);

    /**
     * Returns the IMEI for the given slot.
     *
     * @param slotIndex - device slot.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getImeiForSlot(int slotIndex, String callingPackage, String callingFeatureId);

    /**
     * Returns the primary IMEI of the device
     *
     * @param callingPackage The package name of the caller
     * @param callingFeatureId The feature Id of the calling package
     * @throws UnsupportedOperationException if the radio doesn't support this feature.
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    String getPrimaryImei(String callingPackage, String callingFeatureId);

    /**
     * Returns the Type Allocation Code from the IMEI for the given slot.
     *
     * @param slotIndex - Which slot to retrieve the Type Allocation Code from.
     */
    String getTypeAllocationCodeForSlot(int slotIndex);

    /**
     * Returns the MEID for the given slot.
     *
     * @param slotIndex - device slot.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getMeidForSlot(int slotIndex, String callingPackage, String callingFeatureId);

    /**
     * Returns the Manufacturer Code from the MEID for the given slot.
     *
     * @param slotIndex - Which slot to retrieve the Manufacturer Code from.
     */
    String getManufacturerCodeForSlot(int slotIndex);

    /**
     * Returns the device software version.
     *
     * @param slotIndex - device slot.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getDeviceSoftwareVersionForSlot(int slotIndex, String callingPackage,
            String callingFeatureId);

    /**
     * Returns the subscription ID associated with the specified PhoneAccountHandle.
     */
    int getSubIdForPhoneAccountHandle(in PhoneAccountHandle phoneAccountHandle,
            String callingPackage, String callingFeatureId);

    /**
     * Returns the PhoneAccountHandle associated with a subscription ID.
     */
    PhoneAccountHandle getPhoneAccountHandleForSubscriptionId(int subscriptionId);

    void factoryReset(int subId, String callingPackage);

    /**
     * Returns users's current locale based on the SIM.
     *
     * The returned string will be a well formed BCP-47 language tag, or {@code null}
     * if no locale could be derived.
     */
    String getSimLocaleForSubscriber(int subId);

    /**
     * Requests the modem activity info asynchronously.
     * The implementor is expected to reply with the
     * {@link android.telephony.ModemActivityInfo} object placed into the Bundle with the key
     * {@link android.telephony.TelephonyManager#MODEM_ACTIVITY_RESULT_KEY}.
     * The result code is ignored.
     */
    oneway void requestModemActivityInfo(in ResultReceiver result);

    /**
     * Get the service state on specified SIM slot.
     * @param slotIndex of phone whose service state is returned
     * @param renounceFineLocationAccess Set this to true if the caller would not like to
     * receive fine location related information
     * @param renounceCoarseLocationAccess Set this to true if the caller would not like to
     * receive coarse location related information
     * @param callingPackage The package making the call
     * @param callingFeatureId The feature in the package
     * @return Service state on specified SIM slot.
     */
    ServiceState getServiceStateForSlot(int slotIndex, boolean renounceFineLocationAccess,
            boolean renounceCoarseLocationAccess, String callingPackage, String callingFeatureId);

    /**
     * Returns the URI for the per-account voicemail ringtone set in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail ringtone.
     * @return The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     */
    Uri getVoicemailRingtoneUri(in PhoneAccountHandle accountHandle);

    /**
     * Sets the per-account voicemail ringtone.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges, or
     * has permission {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail ringtone.
     * @param uri The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     */
    void setVoicemailRingtoneUri(String callingPackage,
            in PhoneAccountHandle phoneAccountHandle, in Uri uri);

    /**
     * Returns whether vibration is set for voicemail notification in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail vibration setting.
     * @return {@code true} if the vibration is set for this PhoneAccount, {@code false} otherwise.
     */
    boolean isVoicemailVibrationEnabled(in PhoneAccountHandle accountHandle);

    /**
     * Sets the per-account preference whether vibration is enabled for voicemail notifications.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges, or
     * has permission {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail vibration setting.
     * @param enabled Whether to enable or disable vibration for voicemail notifications from a
     * specific PhoneAccount.
     */
    void setVoicemailVibrationEnabled(String callingPackage,
            in PhoneAccountHandle phoneAccountHandle, boolean enabled);

    /**
     * Returns a list of packages that have carrier privileges for the specific phone.
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     */
    List<String> getPackagesWithCarrierPrivileges(int phoneId);

     /**
      * Returns a list of packages that have carrier privileges.
      * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
      */
    List<String> getPackagesWithCarrierPrivilegesForAllPhones();

    /**
     * Return the application ID for the app type.
     *
     * @param subId the subscription ID that this request applies to.
     * @param appType the uicc app type,
     * @return Application ID for specificied app type or null if no uicc or error.
     */
    String getAidForAppType(int subId, int appType);

    /**
    * Return the Electronic Serial Number.
    *
    * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
    *
    * @param subId the subscription ID that this request applies to.
    * @return ESN or null if error.
    * @hide
    */
    String getEsn(int subId);

    /**
    * Return the Preferred Roaming List Version
    *
    * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
    * @param subId the subscription ID that this request applies to.
    * @return PRLVersion or null if error.
    * @hide
    */
    String getCdmaPrlVersion(int subId);

    /**
     * Get snapshot of Telephony histograms
     * @return List of Telephony histograms
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges.
     */
    List<TelephonyHistogram> getTelephonyHistograms();

    /**
     * Set the allowed carrier list and the excluded carrier list, indicating the priority between
     * the two lists.
     *
     * <p>Requires system privileges. In the future we may add this to carrier APIs.
     *
     * @return {@link #SET_CARRIER_RESTRICTION_SUCCESS} in case of success.
     * {@link #SET_CARRIER_RESTRICTION_NOT_SUPPORTED} if the modem does not support the
     * configuration. {@link #SET_CARRIER_RESTRICTION_ERROR} in all other error cases.
     */
    int setAllowedCarriers(in CarrierRestrictionRules carrierRestrictionRules);

    /**
     * Get the allowed carrier list and the excluded carrier list indicating the priority between
     * the two lists.
     *
     * <p>Requires system privileges. In the future we may add this to carrier APIs.
     *
     * @return {@link CarrierRestrictionRules}; empty lists mean all carriers are allowed. It
     * returns null in case of error.
     */
    CarrierRestrictionRules getAllowedCarriers();

    /**
     * Returns carrier id of the given subscription.
     * <p>To recognize carrier as a first class identity, assign each carrier with a canonical
     * integer a.k.a carrier id.
     *
     * @param subId The subscription id
     * @return Carrier id of given subscription id. return {@link #UNKNOWN_CARRIER_ID} if
     * subscription is unavailable or carrier cannot be identified.
     * @throws IllegalStateException if telephony service is unavailable.
     * @hide
     */
    int getSubscriptionCarrierId(int subId);

    /**
     * Returns carrier name of the given subscription.
     * <p>Carrier name is a user-facing name of carrier id {@link #getSimCarrierId(int)},
     * usually the brand name of the subsidiary (e.g. T-Mobile). Each carrier could configure
     * multiple {@link #getSimOperatorName() SPN} but should have a single carrier name.
     * Carrier name is not canonical identity, use {@link #getSimCarrierId(int)} instead.
     * <p>Returned carrier name is unlocalized.
     *
     * @return Carrier name of given subscription id. return {@code null} if subscription is
     * unavailable or carrier cannot be identified.
     * @throws IllegalStateException if telephony service is unavailable.
     * @hide
     */
    String getSubscriptionCarrierName(int subId);

    /**
     * Returns fine-grained carrier id of the current subscription.
     *
     * <p>The specific carrier id can be used to further differentiate a carrier by different
     * networks, by prepaid v.s.postpaid or even by 4G v.s.3G plan. Each carrier has a unique
     * carrier id {@link #getSimCarrierId()} but can have multiple precise carrier id. e.g,
     * {@link #getSimCarrierId()} will always return Tracfone (id 2022) for a Tracfone SIM, while
     * {@link #getSimPreciseCarrierId()} can return Tracfone AT&T or Tracfone T-Mobile based on the
     * current underlying network.
     *
     * <p>For carriers without any fine-grained carrier ids, return {@link #getSimCarrierId()}
     *
     * @return Returns fine-grained carrier id of the current subscription.
     * Return {@link #UNKNOWN_CARRIER_ID} if the subscription is unavailable or the carrier cannot
     * be identified.
     * @hide
     */
    int getSubscriptionSpecificCarrierId(int subId);

    /**
     * Similar like {@link #getSimCarrierIdName()}, returns user-facing name of the
     * specific carrier id {@link #getSimSpecificCarrierId()}
     *
     * <p>The returned name is unlocalized.
     *
     * @return user-facing name of the subscription specific carrier id. Return {@code null} if the
     * subscription is unavailable or the carrier cannot be identified.
     * @hide
     */
    String getSubscriptionSpecificCarrierName(int subId);

    /**
     * Returns carrier id based on MCCMNC only. This will return a MNO carrier id used for fallback
     * check when exact carrier id {@link #getSimCarrierId()} configurations are not found
     *
     * @param isSubscriptionMccMnc. If {@true} it means this is a query for subscription mccmnc
     * {@false} otherwise.
     *
     * @return carrier id from passing mccmnc.
     * @hide
     */
    int getCarrierIdFromMccMnc(int slotIndex, String mccmnc, boolean isSubscriptionMccMnc);

    /**
     * Action set from carrier signalling broadcast receivers to enable/disable radio
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param enabled control enable or disable radio.
     * @hide
     */
    void carrierActionSetRadioEnabled(int subId, boolean enabled);

    /**
     * Action set from carrier signalling broadcast receivers to start/stop reporting default
     * network conditions.
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param report control start/stop reporting default network events.
     * @hide
     */
    void carrierActionReportDefaultNetworkStatus(int subId, boolean report);

    /**
     * Action set from carrier signalling broadcast receivers to reset all carrier actions.
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @hide
     */
    void carrierActionResetAll(int subId);

    void getCallForwarding(int subId, int callForwardingReason,
            ICallForwardingInfoCallback callback);

    void setCallForwarding(int subId, in CallForwardingInfo callForwardingInfo,
            IIntegerConsumer callback);

    void getCallWaitingStatus(int subId, IIntegerConsumer callback);

    void setCallWaitingStatus(int subId, boolean enabled, IIntegerConsumer callback);

    /**
     * Get Client request stats which will contain statistical information
     * on each request made by client.
     * @param callingPackage package making the call.
     * @param callingFeatureId The feature in the package.
     * @param subId Subscription index
     * @hide
     */
    List<ClientRequestStats> getClientRequestStats(String callingPackage, String callingFeatureId,
            int subid);

    /**
     * Set SIM card power state.
     * @param slotIndex SIM slot id
     * @param state  State of SIM (power down, power up, pass through)
     * @hide
     */
    void setSimPowerStateForSlot(int slotIndex, int state);

    /**
     * Set SIM card power state.
     * @param slotIndex SIM slot id
     * @param state  State of SIM (power down, power up, pass through)
     * @param callback callback to receive result info
     * @hide
     */
    void setSimPowerStateForSlotWithCallback(int slotIndex, int state, IIntegerConsumer callback);

    /**
     * Returns a list of Forbidden PLMNs from the specified SIM App
     * Returns null if the query fails.
     *
     * <p>Requires that the calling app has READ_PRIVILEGED_PHONE_STATE or READ_PHONE_STATE
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     */
    String[] getForbiddenPlmns(int subId, int appType, String callingPackage,
             String callingFeatureId);

    /**
     * Set the forbidden PLMN list from the givven app type (ex APPTYPE_USIM) on a particular
     * subscription.
     *
     * @param subId subId the id of the subscription
     * @param appType appType the uicc app type, must be USIM or SIM.
     * @param fplmns plmns the Forbiden plmns list that needed to be written to the SIM.
     * @param callingPackage the op Package name.
     * @param callingFeatureId the feature in the package.
     * @return number of fplmns that is successfully written to the SIM
     */
    int setForbiddenPlmns(int subId, int appType, in List<String> fplmns, String callingPackage,
            String callingFeatureId);

    /**
     * Check if phone is in emergency callback mode
     * @return true if phone is in emergency callback mode
     * @param subId the subscription ID that this action applies to.
     * @hide
     */
    boolean getEmergencyCallbackMode(int subId);

    /**
     * Get the most recently available signal strength information.
     *
     * Get the most recent SignalStrength information reported by the modem. Due
     * to power saving this information may not always be current.
     * @param subId Subscription index
     * @return the most recent cached signal strength info from the modem
     * @hide
     */
    SignalStrength getSignalStrength(int subId);

    /**
     * Get the card ID of the default eUICC card. If there is no eUICC, returns
     * {@link #INVALID_CARD_ID}.
     *
     * @param subId subscription ID used for authentication
     * @param callingPackage package making the call
     * @return card ID of the default eUICC card.
     */
    int getCardIdForDefaultEuicc(int subId, String callingPackage);

    /**
     * Gets information about currently inserted UICCs and eUICCs.
     * <p>
     * Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     * <p>
     * If the caller has carrier priviliges on any active subscription, then they have permission to
     * get simple information like the card ID ({@link UiccCardInfo#getCardId()}), whether the card
     * is an eUICC ({@link UiccCardInfo#isEuicc()}), and the slot index where the card is inserted
     * ({@link UiccCardInfo#getSlotIndex()}).
     * <p>
     * To get private information such as the EID ({@link UiccCardInfo#getEid()}) or ICCID
     * ({@link UiccCardInfo#getIccId()}), the caller must have carrier priviliges on that specific
     * UICC or eUICC card.
     * <p>
     * See {@link UiccCardInfo} for more details on the kind of information available.
     *
     * @param callingPackage package making the call, used to evaluate carrier privileges
     * @return a list of UiccCardInfo objects, representing information on the currently inserted
     * UICCs and eUICCs. Each UiccCardInfo in the list will have private information filtered out if
     * the caller does not have adequate permissions for that card.
     */
    List<UiccCardInfo> getUiccCardsInfo(String callingPackage);

    /**
     * Get slot info for all the UICC slots.
     * @return UiccSlotInfo array.
     * @hide
     */
    UiccSlotInfo[] getUiccSlotsInfo(String callingPackage);

    /**
     * Map logicalSlot to physicalSlot, and activate the physicalSlot if it is inactive.
     * @param physicalSlots Index i in the array representing physical slot for phone i. The array
     *        size should be same as getPhoneCount().
     * @deprecated Use {@link #setSimSlotMapping(in List<UiccSlotMapping> slotMapping)} instead.
     * @return boolean Return true if the switch succeeds, false if the switch fails.
     */
    boolean switchSlots(in int[] physicalSlots);

    /**
     * Maps the logical slots to the SlotPortMapping which consist of both physical slot index and
     * port index. Logical slot is the slot that is seen by modem. Physical slot is the actual
     * physical slot. Port index is the index (enumerated value) for the associated port available
     * on the SIM. Each physical slot can have multiple ports which enables multi-enabled profile
     * (MEP). If eUICC physical slot supports 2 ports, then the port index is numbered 0,1 and if
     * eUICC2 supports 4 ports then the port index is numbered 0,1,2,3. Each portId is unique within
     * a UICC physical slot but not necessarily unique across UICC’s. SEP(Single enabled profile)
     * eUICC and non-eUICC will only have port Index 0.
     *
     * Logical slots that are already mapped to the requested SlotPortMapping are not impacted.
     * @param slotMapping Index i in the list representing slot mapping for phone i.
     *
     * @return {@code true} if the switch succeeds, {@code false} if the switch fails.
     */
    boolean setSimSlotMapping(in List<UiccSlotMapping> slotMapping);

    /**
     * Returns whether mobile data roaming is enabled on the subscription with id {@code subId}.
     *
     * @param subId the subscription id
     * @return {@code true} if the data roaming is enabled on this subscription.
     */
    boolean isDataRoamingEnabled(int subId);

    /**
     * Enables/Disables the data roaming on the subscription with id {@code subId}.
     *
     * @param subId the subscription id
     * @param isEnabled {@code true} to enable mobile data roaming, otherwise disable it.
     */
    void setDataRoamingEnabled(int subId, boolean isEnabled);

    /**
     * Gets the roaming mode for the CDMA phone with the subscription id {@code subId}.
     *
     * @param the subscription id.
     * @return the roaming mode for CDMA phone.
     */
    int getCdmaRoamingMode(int subId);

    /**
     * Sets the roaming mode on the CDMA phone with the subscription {@code subId} to the given
     * roaming mode {@code mode}.
     *
     * @param subId the subscription id.
     * @param mode the roaming mode should be set.
     * @return {@code true} if successed.
     */
    boolean setCdmaRoamingMode(int subId, int mode);

    /**
     * Gets the subscription mode for the CDMA phone with the subscription id {@code subId}.
     *
     * @param the subscription id.
     * @return the subscription mode for CDMA phone.
     */
    int getCdmaSubscriptionMode(int subId);

    /**
     * Sets the subscription mode for CDMA phone with the subscription {@code subId} to the given
     * subscription mode {@code mode}.
     *
     * @param subId the subscription id.
     * @param mode the subscription mode should be set.
     * @return {@code true} if successed.
     */
    boolean setCdmaSubscriptionMode(int subId, int mode);

    /**
     * A test API to override carrier information including mccmnc, imsi, iccid, gid1, gid2,
     * plmn and spn. This would be handy for, eg, forcing a particular carrier id, carrier's config
     * (also any country or carrier overlays) to be loaded when using a test SIM with a call box.
     */
    void setCarrierTestOverride(int subId, String mccmnc, String imsi, String iccid, String gid1,
            String gid2, String plmn, String spn, String carrierPrivilegeRules, String apn);

    /**
     * Forcibly sets a package as a carrier provisioning package.
     *
     * This override is ephemeral, and will disappear upon phone process restart (including
     * device reboot).
     *
     * WARNING: This API is meant for testing purposes only. This enables automated testing for
     * cases where the carrier service package is used as a permissioning gate for features such as
     * restricted carrier network access (restricted APNs and Merged Carrier Wifi)
     *
     * @param carrierServicePackage The package that should be marked as the carrier service
     *     package, or {@code null} to disable the override.
     */
    void setCarrierServicePackageOverride(int subId, String carrierServicePackage,
                String callingPackage);

    /**
     * A test API to return installed carrier id list version.
     */
    int getCarrierIdListVersion(int subId);

    /**
     * A test API to reload the UICC profile.
     * @hide
     */
    void refreshUiccProfile(int subId);

    /**
     * How many modems can have simultaneous data connections.
     * @hide
     */
    int getNumberOfModemsWithSimultaneousDataConnections(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Return the network selection mode on the subscription with id {@code subId}.
     */
     int getNetworkSelectionMode(int subId);

     /**
     * Return true if the device is in emergency sms mode, false otherwise.
     */
     boolean isInEmergencySmsMode();

    /**
     * Return the modem radio power state for slot index.
     *
     */
    int getRadioPowerState(int slotIndex, String callingPackage, String callingFeatureId);

    // IMS specific AIDL commands, see ImsMmTelManager.java

    /**
     * Adds an IMS registration status callback for the subscription id specified.
     */
    void registerImsRegistrationCallback(int subId, IImsRegistrationCallback c);
     /**
      * Removes an existing IMS registration status callback for the subscription specified.
      */
    void unregisterImsRegistrationCallback(int subId, IImsRegistrationCallback c);

    /**
     * Adds an IMS emergency registration status callback for the subscription id specified.
     */
    void registerImsEmergencyRegistrationCallback(int subId, IImsRegistrationCallback c);
    /**
     * Removes an existing IMS emergency registration status callback for the subscription
     * id specified.
     */
    void unregisterImsEmergencyRegistrationCallback(int subId, IImsRegistrationCallback c);

    /**
     * Get the IMS service registration state for the MmTelFeature associated with this sub id.
     */
    void getImsMmTelRegistrationState(int subId, IIntegerConsumer consumer);

    /**
     * Get the transport type for the IMS service registration state.
     */
    void getImsMmTelRegistrationTransportType(int subId, IIntegerConsumer consumer);

    /**
     * Adds an IMS MmTel capabilities callback for the subscription specified.
     */
    void registerMmTelCapabilityCallback(int subId, IImsCapabilityCallback c);

    /**
     * Removes an existing IMS MmTel capabilities callback for the subscription specified.
     */
    void unregisterMmTelCapabilityCallback(int subId, IImsCapabilityCallback c);

    /**
     * return true if the IMS MmTel capability for the given registration tech is capable.
     */
    boolean isCapable(int subId, int capability, int regTech);

    /**
     * return true if the IMS MmTel capability for the given registration tech is available.
     */
    boolean isAvailable(int subId, int capability, int regTech);

    /**
     * Return whether or not the MmTel capability is supported for the requested transport type.
     */
    void isMmTelCapabilitySupported(int subId, IIntegerConsumer callback, int capability,
            int transportType);

    /**
     * Returns true if the user's setting for 4G LTE is enabled, for the subscription specified.
     */
    boolean isAdvancedCallingSettingEnabled(int subId);

    /**
     * Modify the user's setting for whether or not 4G LTE is enabled.
     */
    void setAdvancedCallingSettingEnabled(int subId, boolean isEnabled);

    /**
     * return true if the user's setting for VT is enabled for the subscription.
     */
    boolean isVtSettingEnabled(int subId);

    /**
     * Modify the user's setting for whether or not VT is available for the subscrption specified.
     */
    void setVtSettingEnabled(int subId, boolean isEnabled);

    /**
     * return true if the user's setting for whether or not Voice over WiFi is currently enabled.
     */
    boolean isVoWiFiSettingEnabled(int subId);

    /**
     * sets the user's setting for Voice over WiFi enabled state.
     */
    void setVoWiFiSettingEnabled(int subId, boolean isEnabled);

    /**
     * return true if the user's setting for Voice over Cross SIM is enabled and false if it is not
     */
    boolean isCrossSimCallingEnabledByUser(int subId);

    /**
     * Sets the user's setting for whether or not Voice over Cross SIM is enabled.
     */
    void setCrossSimCallingEnabled(int subId, boolean isEnabled);

    /**
     * return true if the user's setting for Voice over WiFi while roaming is enabled.
     */
    boolean isVoWiFiRoamingSettingEnabled(int subId);

    /**
     * Sets the user's preference for whether or not Voice over WiFi is enabled for the current
     * subscription while roaming.
     */
    void setVoWiFiRoamingSettingEnabled(int subId, boolean isEnabled);

    /**
     * Set the Voice over WiFi enabled state, but do not persist the setting.
     */
    void setVoWiFiNonPersistent(int subId, boolean isCapable, int mode);

    /**
     * return the Voice over WiFi mode preference set by the user for the subscription specified.
     */
    int getVoWiFiModeSetting(int subId);

    /**
     * sets the user's preference for the Voice over WiFi mode for the subscription specified.
     */
    void setVoWiFiModeSetting(int subId, int mode);

    /**
     * return the Voice over WiFi mode preference set by the user for the subscription specified
     * while roaming.
     */
    int getVoWiFiRoamingModeSetting(int subId);

    /**
     * sets the user's preference for the Voice over WiFi mode for the subscription specified
     * while roaming.
     */
    void setVoWiFiRoamingModeSetting(int subId, int mode);

    /**
     * Modify the user's setting for whether or not RTT is enabled for the subscrption specified.
     */
    void setRttCapabilitySetting(int subId, boolean isEnabled);

    /**
     * return true if TTY over VoLTE is enabled for the subscription specified.
     */
    boolean isTtyOverVolteEnabled(int subId);

    /**
     * Return the emergency number list from all the active subscriptions.
     */
    @SuppressWarnings(value={"untyped-collection"})
    Map getEmergencyNumberList(String callingPackage, String callingFeatureId);

    /**
     * Identify if the number is emergency number, based on all the active subscriptions.
     */
    boolean isEmergencyNumber(String number, boolean exactMatch);

    /**
     * Return a list of certs in hex string from loaded carrier privileges access rules.
     */
    List<String> getCertsFromCarrierPrivilegeAccessRules(int subId);

    /**
     * Register an IMS provisioning change callback with Telephony.
     */
    void registerImsProvisioningChangedCallback(int subId, IImsConfigCallback callback);

    /**
     * unregister an existing IMS provisioning change callback.
     */
    void unregisterImsProvisioningChangedCallback(int subId, IImsConfigCallback callback);

    /**
     * Register an IMS provisioning change callback with Telephony.
     */
    void registerFeatureProvisioningChangedCallback(int subId,
            IFeatureProvisioningCallback callback);

    /**
     * unregister an existing IMS provisioning change callback.
     */
    void unregisterFeatureProvisioningChangedCallback(int subId,
            IFeatureProvisioningCallback callback);

    /**
     * Set the provisioning status for the IMS MmTel capability using the specified subscription.
     */
    void setImsProvisioningStatusForCapability(int subId, int capability, int tech,
            boolean isProvisioned);

    /**
     * Get the provisioning status for the IMS MmTel capability specified.
     */
    boolean getImsProvisioningStatusForCapability(int subId, int capability, int tech);

    /**
     * Get the provisioning status for the IMS Rcs capability specified.
     */
    boolean getRcsProvisioningStatusForCapability(int subId, int capability, int tech);

    /**
     * Set the provisioning status for the IMS Rcs capability using the specified subscription.
     */
    void setRcsProvisioningStatusForCapability(int subId, int capability, int tech,
            boolean isProvisioned);

    /**
     * Return an integer containing the provisioning value for the specified provisioning key.
     */
    int getImsProvisioningInt(int subId, int key);

    /**
     * return a String containing the provisioning value for the provisioning key specified.
     */
    String getImsProvisioningString(int subId, int key);

    /**
     * Set the integer provisioning value for the provisioning key specified.
     */
    int setImsProvisioningInt(int subId, int key, int value);

    /**
     * Set the String provisioning value for the provisioning key specified.
     */
    int setImsProvisioningString(int subId, int key, String value);

    /**
     * Start emergency callback mode for testing.
     */
    void startEmergencyCallbackMode();

    /**
     * Update Emergency Number List for Test Mode.
     */
    void updateEmergencyNumberListTestMode(int action, in EmergencyNumber num);

    /**
     * Get the full emergency number list for Test Mode.
     */
    List<String> getEmergencyNumberListTestMode();

    /**
     * A test API to return the emergency number db version.
     */
    int getEmergencyNumberDbVersion(int subId);

    /**
     * Notify Telephony for OTA emergency number database installation complete.
     */
    void notifyOtaEmergencyNumberDbInstalled();

    /**
     * Override a customized file partition name for OTA emergency number database.
     */
    void updateOtaEmergencyNumberDbFilePath(in ParcelFileDescriptor otaParcelFileDescriptor);

    /**
     * Reset file partition to default for OTA emergency number database.
     */
    void resetOtaEmergencyNumberDbFilePath();

    /**
     * Enable or disable a logical modem stack associated with the slotIndex.
     */
    boolean enableModemForSlot(int slotIndex, boolean enable);

    /**
     * Indicate if the enablement of multi SIM functionality is restricted.
     * @hide
     */
    void setMultiSimCarrierRestriction(boolean isMultiSimCarrierRestricted);

    /**
     * Returns if the usage of multiple SIM cards at the same time is supported.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return {@link #MULTISIM_ALLOWED} if the device supports multiple SIMs.
     * {@link #MULTISIM_NOT_SUPPORTED_BY_HARDWARE} if the device does not support multiple SIMs.
     * {@link #MULTISIM_NOT_SUPPORTED_BY_CARRIER} in the device supports multiple SIMs, but the
     * functionality is restricted by the carrier.
     */
    int isMultiSimSupported(String callingPackage, String callingFeatureId);

    /**
     * Switch configs to enable multi-sim or switch back to single-sim
     * @hide
     */
    void switchMultiSimConfig(int numOfSims);

    /**
     * Get if altering modems configurations will trigger reboot.
     * @hide
     */
    boolean doesSwitchMultiSimConfigTriggerReboot(int subId, String callingPackage,
             String callingFeatureId);

    /**
     * Get the mapping from logical slots to port index.
     */
    List<UiccSlotMapping> getSlotsMapping(String callingPackage);

    /**
     * Get the IRadio HAL Version encoded as 100 * MAJOR_VERSION + MINOR_VERSION or -1 if unknown
     */
    int getRadioHalVersion();

    /**
     * Get the HAL Version of a specific service
     * encoded as 100 * MAJOR_VERSION + MINOR_VERSION or -1 if unknown
     */
    int getHalVersion(int service);

    /**
     * Get the current calling package name.
     */
    String getCurrentPackageName();

    /**
     * Returns true if the specified type of application (e.g. {@link #APPTYPE_CSIM} is present
     * on the UICC card.
     * @hide
     */
    boolean isApplicationOnUicc(int subId, int appType);

    boolean isModemEnabledForSlot(int slotIndex, String callingPackage, String callingFeatureId);

    boolean isDataEnabledForApn(int apnType, int subId, String callingPackage);

    boolean isApnMetered(int apnType, int subId);

    oneway void setSystemSelectionChannels(in List<RadioAccessSpecifier> specifiers,
            int subId, IBooleanConsumer resultCallback);

    List<RadioAccessSpecifier> getSystemSelectionChannels(int subId);

    boolean isMvnoMatched(int slotIndex, int mvnoType, String mvnoMatchData);

    /**
     * Enqueue a pending sms Consumer, which will answer with the user specified selection for an
     * outgoing SmsManager operation.
     */
    oneway void enqueueSmsPickResult(String callingPackage, String callingAttributeTag,
        IIntegerConsumer subIdResult);

    /**
     * Show error dialog to switch to managed profile.
     */
    oneway void showSwitchToManagedProfileDialog();

    /**
     * Returns the MMS user agent.
     */
    String getMmsUserAgent(int subId);

    /**
     * Returns the MMS user agent profile URL.
     */
    String getMmsUAProfUrl(int subId);

    void setMobileDataPolicyEnabled(int subscriptionId, int policy, boolean enabled);

    boolean isMobileDataPolicyEnabled(int subscriptionId, int policy);

    /**
     * Command line command to enable or disable handling of CEP data for test purposes.
     */
    oneway void setCepEnabled(boolean isCepEnabled);

    /**
     * Notify Rcs auto config received.
     */
    void notifyRcsAutoConfigurationReceived(int subId, in byte[] config, boolean isCompressed);

    boolean isIccLockEnabled(int subId);

    int setIccLockEnabled(int subId, boolean enabled, String password);

    int changeIccLockPassword(int subId, String oldPassword, String newPassword);

    /**
     * Request for receiving user activity notification
     */
    oneway void requestUserActivityNotification();

    /**
     * Called when userActivity is signalled in the power manager.
     * This is safe to call from any thread, with any window manager locks held or not.
     */
    oneway void userActivity();

    /**
     * Get the user manual network selection.
     * Return null if inactive or phone process is down.
     *
     * @param subId the id of the subscription
     * @return operatorinfo on success
     */
    String getManualNetworkSelectionPlmn(int subId);

    /**
     * Whether device can connect to 5G network when two SIMs are active.
     */
    boolean canConnectTo5GInDsdsMode();

    /**
     * Returns a list of the equivalent home PLMNs (EF_EHPLMN) from the USIM app.
     *
     * @return A list of equivalent home PLMNs. Returns an empty list if EF_EHPLMN is empty or
     * does not exist on the SIM card.
     */
    List<String> getEquivalentHomePlmns(int subId, String callingPackage, String callingFeatureId);

    /**
     * Enable or disable Voice over NR (VoNR)
     * @param subId the subscription ID that this action applies to.
     * @param enabled enable or disable VoNR.
     * @return operation result.
     */
    int setVoNrEnabled(int subId, boolean enabled);

    /**
     * Is voice over NR enabled
     * @return true if VoNR is enabled else false
     */
    boolean isVoNrEnabled(int subId);

    /**
     * Enable/Disable E-UTRA-NR Dual Connectivity
     * @return operation result. See TelephonyManager.EnableNrDualConnectivityResult for
     * details
     * @param subId the id of the subscription
     * @param enable enable/disable dual connectivity
     */
    int setNrDualConnectivityState(int subId, int nrDualConnectivityState);

    /**
     * Is E-UTRA-NR Dual Connectivity enabled
     * @param subId the id of the subscription
     * @return true if dual connectivity is enabled else false
     */
    boolean isNrDualConnectivityEnabled(int subId);

    /**
     * Checks whether the device supports the given capability on the radio interface.
     *
     * @param capability the name of the capability
     * @return the availability of the capability
     */
    boolean isRadioInterfaceCapabilitySupported(String capability);

    /**
     * Thermal mitigation request to control functionalities at modem.
     *
     * @param subId the id of the subscription
     * @param thermalMitigationRequest holds the parameters necessary for the request.
     * @param callingPackage the package name of the calling package.
     * @throws InvalidThermalMitigationRequestException if the parametes are invalid.
     */
    int sendThermalMitigationRequest(int subId,
            in ThermalMitigationRequest thermalMitigationRequest,
            String callingPackage);

    /**
     * get the Generic Bootstrapping Architecture authentication keys
     */
    void bootstrapAuthenticationRequest(int subId, int appType, in Uri nafUrl,
            in UaSecurityProtocolIdentifier securityProtocol,
            boolean forceBootStrapping, IBootstrapAuthenticationCallback callback);

    /**
     * Set the GbaService Package Name that Telephony will bind to.
     */
    boolean setBoundGbaServiceOverride(int subId, String packageName);

    /**
     * Return the package name of the currently bound GbaService.
     */
    String getBoundGbaService(int subId);

    /**
     * Set the release time for telephony to unbind GbaService.
     */
    boolean setGbaReleaseTimeOverride(int subId, int interval);

    /**
     * Return the release time for telephony to unbind GbaService.
     */
    int getGbaReleaseTime(int subId);

    /**
     * Provide the client configuration parameters of the RCS application.
     */
    void setRcsClientConfiguration(int subId, in RcsClientConfiguration rcc);

    /**
     * return value to indicate whether the device and the carrier can support RCS VoLTE
     * single registration.
     */
    boolean isRcsVolteSingleRegistrationCapable(int subId);

    /**
     * Register RCS provisioning callback.
     */
    void registerRcsProvisioningCallback(int subId, IRcsConfigCallback callback);

    /**
     * Unregister RCS provisioning callback.
     */
    void unregisterRcsProvisioningCallback(int subId, IRcsConfigCallback callback);

    /**
     * trigger RCS reconfiguration.
     */
    void triggerRcsReconfiguration(int subId);

    /**
     * Enables or disables the test mode for RCS VoLTE single registration.
     */
    void setRcsSingleRegistrationTestModeEnabled(boolean enabled);

    /**
     * Gets the test mode for RCS VoLTE single registration.
     */
    boolean getRcsSingleRegistrationTestModeEnabled();

    /**
     * Overrides the config of RCS VoLTE single registration enabled for the device.
     */
    void setDeviceSingleRegistrationEnabledOverride(String enabled);

    /**
     * Gets the config of RCS VoLTE single registration enabled for the device.
     */
    boolean getDeviceSingleRegistrationEnabled();

    /**
     * Overrides the config of RCS VoLTE single registration enabled for the carrier/subscription.
     */
    boolean setCarrierSingleRegistrationEnabledOverride(int subId, String enabled);

    /**
     * Sends a device to device message; only for use through shell.
     */
    void sendDeviceToDeviceMessage(int message, int value);

    /**
     * Sets the specified transport active; only for use through shell.
     */
    void setActiveDeviceToDeviceTransport(String transport);

    /**
     * Forces Device to Device communication to be enabled, even if the device config has it
     * disabled.
     */
    void setDeviceToDeviceForceEnabled(boolean isForceEnabled);

    /**
     * Gets the config of RCS VoLTE single registration enabled for the carrier/subscription.
     */
    boolean getCarrierSingleRegistrationEnabled(int subId);

    /**
     * Overrides the ims feature validation result
     */
    boolean setImsFeatureValidationOverride(int subId, String enabled);

    /**
     * Gets the ims feature validation override value
     */
    boolean getImsFeatureValidationOverride(int subId);

    /**
     *  Return the mobile provisioning url that is used to launch a browser to allow users to manage
     *  their mobile plan.
     */
    String getMobileProvisioningUrl();

    /*
     * Remove the EAB contacts from the EAB database.
     */
    int removeContactFromEab(int subId, String contacts);

    /**
     * Get the EAB contact from the EAB database.
     */
    String getContactFromEab(String contact);

    /**
     * Get the EAB capability from the EAB database.
     */
    String getCapabilityFromEab(String contact);

    /*
     * Check whether the device supports RCS User Capability Exchange or not.
     */
    boolean getDeviceUceEnabled();

    /*
     * Set the device supports RCS User Capability Exchange.
     */
     void setDeviceUceEnabled(boolean isEnabled);

    /**
     * Add feature tags to the IMS registration being tracked by UCE and potentially
     * generate a new PUBLISH to the network.
     * Note: This is designed for a SHELL command only.
     */
    RcsContactUceCapability addUceRegistrationOverrideShell(int subId, in List<String> featureTags);

    /**
     * Remove feature tags from the IMS registration being tracked by UCE and potentially
     * generate a new PUBLISH to the network.
     * Note: This is designed for a SHELL command only.
     */
    RcsContactUceCapability removeUceRegistrationOverrideShell(int subId,
            in List<String> featureTags);

    /**
     * Clear overridden feature tags in the IMS registration being tracked by UCE and potentially
     * generate a new PUBLISH to the network.
     * Note: This is designed for a SHELL command only.
     */
    RcsContactUceCapability clearUceRegistrationOverrideShell(int subId);

    /**
     * Get the latest RcsContactUceCapability structure that is used in SIP PUBLISH procedures.
     * Note: This is designed for a SHELL command only.
     */
    RcsContactUceCapability getLatestRcsContactUceCapabilityShell(int subId);

    /**
     * Returns the last PIDF XML sent to the network during the last PUBLISH or "none" if the
     * device does not have an active PUBLISH.
     * Note: This is designed for a SHELL command only.
     */
    String getLastUcePidfXmlShell(int subId);

    /**
      * Remove UCE requests cannot be sent to the network status.
      * Note: This is designed for a SHELL command only.
      */
    boolean removeUceRequestDisallowedStatus(int subId);

    /**
     * Set the timeout for contact capabilities request.
     * Note: This is designed for a SHELL command only.
     */
    boolean setCapabilitiesRequestTimeout(int subId, long timeoutAfterMs);

    /**
     * Set a SignalStrengthUpdateRequest to receive notification when Signal Strength breach the
     * specified thresholds.
     */
    void setSignalStrengthUpdateRequest(int subId, in SignalStrengthUpdateRequest request,
            String callingPackage);

    /**
     * Clear a SignalStrengthUpdateRequest from system.
     */
    void clearSignalStrengthUpdateRequest(int subId, in SignalStrengthUpdateRequest request,
            String callingPackage);

    /**
     * Gets the current phone capability.
     */
    PhoneCapability getPhoneCapability();

    /**
     * Prepare TelephonyManager for an unattended reboot. The reboot is
     * required to be done shortly after the API is invoked.
     *
     * Requires system privileges.
     *
     * @return {@link #PREPARE_UNATTENDED_REBOOT_SUCCESS} in case of success.
     * {@link #PREPARE_UNATTENDED_REBOOT_PIN_REQUIRED} if the device contains
     * at least one SIM card for which the user needs to manually enter the PIN
     * code after the reboot. {@link #PREPARE_UNATTENDED_REBOOT_ERROR} in case
     * of error.
     */
    int prepareForUnattendedReboot();

    /**
     * Request to get the current slicing configuration including URSP rules and
     * NSSAIs (configured, allowed and rejected).
     */
    void getSlicingConfig(in ResultReceiver callback);

    /**
     * Check whether the given premium capability is available for purchase from the carrier.
     */
    boolean isPremiumCapabilityAvailableForPurchase(int capability, int subId);

    /**
     * Purchase the given premium capability from the carrier.
     */
    void purchasePremiumCapability(int capability, IIntegerConsumer callback, int subId);

    /**
     * Register an IMS connection state callback
     */
    void registerImsStateCallback(int subId, int feature, in IImsStateCallback cb,
            in String callingPackage);

    /**
     * Unregister an IMS connection state callback
     */
    void unregisterImsStateCallback(in IImsStateCallback cb);

    /**
     * return last known cell identity
     * @param subId user preferred subId.
     * @param callingPackage the name of the package making the call.
     * @param callingFeatureId The feature in the package.
     */
    CellIdentity getLastKnownCellIdentity(int subId, String callingPackage,
            String callingFeatureId);

    /**
     *  @return true if the modem service is set successfully, false otherwise.
     */
    boolean setModemService(in String serviceName);

    /**
     * @return the service name of the modem service which bind to.
     */
    String getModemService();

    /**
     * Is Provisioning required for capability
     * @return true if provisioning is required for the MMTEL capability and IMS
     * registration technology specified, false if it is not required.
     */
    boolean isProvisioningRequiredForCapability(int subId, int capability, int tech);

    /**
     * Is RCS Provisioning required for capability
     * @return true if provisioning is required for the RCS capability and IMS
     * registration technology specified, false if it is not required.
     */
    boolean isRcsProvisioningRequiredForCapability(int subId, int capability, int tech);

    /**
     * Sets a voice service state from telecom based on the current PhoneAccounts registered. See
     * PhoneAccount#CAPABILITY_VOICE_CALLING_AVAILABLE.
     */
    void setVoiceServiceStateOverride(int subId, boolean hasService, String callingPackage);

    /**
     * Returns the package name that provides the {@link CarrierService} implementation for the
     * specified {@code logicalSlotIndex}, or {@code null} if no package with carrier privileges
     * declares one.
     *
     * @param logicalSlotIndex The slot index to fetch the {@link CarrierService} package for
     * @return The system-selected package that provides the {@link CarrierService} implementation
     * for the slot, or {@code null} if none is resolved
     */
    String getCarrierServicePackageNameForLogicalSlot(int logicalSlotIndex);

    /**
     * set removable eSIM as default eUICC.
     *
     * @hide
     */
    void setRemovableEsimAsDefaultEuicc(boolean isDefault, String callingPackage);

    /**
     * Returns whether the removable eSIM is default eUICC or not.
     *
     * @hide
     */
    boolean isRemovableEsimDefaultEuicc(String callingPackage);

    /**
     * Get the component name of the default app to direct respond-via-message intent for the
     * user associated with this subscription, update the cache if there is no respond-via-message
     * application currently configured for this user.
     * @return component name of the app and class to direct Respond Via Message intent to, or
     * {@code null} if the functionality is not supported.
     * @hide
     */
    ComponentName getDefaultRespondViaMessageApplication(int subId, boolean updateIfNeeded);

    /**
     * Get the SIM state for the logical SIM slot index.
     *
     * @param slotIndex Logical SIM slot index.
     */
    int getSimStateForSlotIndex(int slotIndex);

    /**
     * Request telephony to persist state for debugging emergency call failures.
     *
     * @param dropBoxTag Tag to use when persisting data to dropbox service.
     * @param enableLogcat whether to collect logcat output
     * @param logcatStartTimestampMillis timestamp from when logcat buffers would be persisted
     * @param enableTelecomDump whether to collect telecom dumpsys
     * @param enableTelephonyDump whether to collect telephony dumpsys
     *
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.DUMP)")
    void persistEmergencyCallDiagnosticData(String dropboxTag, boolean enableLogcat,
        long logcatStartTimestampMillis, boolean enableTelecomDump, boolean enableTelephonyDump);
    /**
     * Set whether the radio is able to connect with null ciphering or integrity
     * algorithms. This is a global setting and will apply to all active subscriptions
     * and all new subscriptions after this.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @param enabled when true, null  cipher and integrity algorithms are allowed.
     * @hide
     */
    void setNullCipherAndIntegrityEnabled(boolean enabled);

    /**
     * Get whether the radio is able to connect with null ciphering or integrity
     * algorithms. Note that this retrieves the phone-global preference and not
     * the state of the radio.
     *
     * @hide
     */
    boolean isNullCipherAndIntegrityPreferenceEnabled();

    /**
     * Get current broadcast ranges.
     */
    List<CellBroadcastIdRange> getCellBroadcastIdRanges(int subId);

    /**
     * Set reception of cell broadcast messages with the list of the given ranges
     */
    void setCellBroadcastIdRanges(int subId, in List<CellBroadcastIdRange> ranges,
            IIntegerConsumer callback);

    /**
     * Returns whether the domain selection service is supported.
     *
     * @return {@code true} if the domain selection service is supported.
     */
    boolean isDomainSelectionSupported();

    /**
     * Get the carrier restriction status of the device.
     */
    void getCarrierRestrictionStatus(IIntegerConsumer internalCallback, String packageName);

    /**
     * Request to enable or disable the satellite modem.
     *
     * @param enableSatellite True to enable the satellite modem and false to disable.
     * @param enableDemoMode True if demo mode is enabled and false otherwise. When
     *                       disabling satellite, {@code enableDemoMode} is always considered as
     *                       {@code false} by Telephony.
     * @param isEmergency {@code true} means satellite is enabled for emergency mode, {@code false}
     *                    otherwise. When disabling satellite, {@code isEmergency} is always
     *                    considered as {@code false} by Telephony.
     * @param callback The callback to get the result of the request.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            boolean isEmergency, in IIntegerConsumer callback);

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param receiver Result receiver to get the error code of the request and whether the
     *                 satellite modem is enabled.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestIsSatelliteEnabled(in ResultReceiver receiver);

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param receiver Result receiver to get the error code of the request and whether the
     *                 satellite demo mode is enabled.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestIsDemoModeEnabled(in ResultReceiver receiver);

    /**
     * Request to get whether the satellite service is enabled with emergency mode.
     *
     * @param receiver Result receiver to get the error code of the request and whether the
     *                 satellite is enabled with emergency mode.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestIsEmergencyModeEnabled(in ResultReceiver receiver);

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param receiver Result receiver to get the error code of the request and whether the
     *                 satellite service is supported on the device.
     */
    void requestIsSatelliteSupported(in ResultReceiver receiver);

    /**
     * Request to get the capabilities of the satellite service.
     *
     * @param receiver Result receiver to get the error code of the request and the requested
     *                 capabilities of the satellite service.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestSatelliteCapabilities(in ResultReceiver receiver);

    /**
     * Start receiving satellite transmission updates.
     *
     * @param resultCallback The callback to get the result of the request.
     * @param callback The callback to handle transmission updates.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void startSatelliteTransmissionUpdates(in IIntegerConsumer resultCallback,
            in ISatelliteTransmissionUpdateCallback callback);

    /**
     * Stop receiving satellite transmission updates.
     *
     * @param resultCallback The callback to get the result of the request.
     * @param callback The callback that was passed to startSatelliteTransmissionUpdates.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void stopSatelliteTransmissionUpdates(in IIntegerConsumer resultCallback,
            in ISatelliteTransmissionUpdateCallback callback);

    /**
     * Register the subscription with a satellite provider.
     * This is needed to register the subscription if the provider allows dynamic registration.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @provisionData Data from the provisioning app that can be used by provisioning server
     * @param callback The callback to get the result of the request.
     *
     * @return The signal transport used by callers to cancel the provision request.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    ICancellationSignal provisionSatelliteService(in String token,
            in byte[] provisionData, in IIntegerConsumer callback);

    /**
     * Unregister the subscription with the satellite provider.
     * This is needed to unregister the subscription if the provider allows dynamic registration.
     * Once deprovisioned,
     * {@link SatelliteCallback.SatelliteProvisionStateListener#onSatelliteProvisionStateChanged}
     * should report as deprovisioned.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param callback The callback to get the result of the request.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void deprovisionSatelliteService(in String token, in IIntegerConsumer callback);

    /**
     * Registers for provision state changed from satellite modem.
     *
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteError} result of the operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForSatelliteProvisionStateChanged(in ISatelliteProvisionStateCallback callback);

    /**
     * Unregisters for provision state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to registerForSatelliteProvisionStateChanged.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForSatelliteProvisionStateChanged(
            in ISatelliteProvisionStateCallback callback);

    /**
     * Request to get whether the device is provisioned with a satellite provider.
     *
     * @param receiver Result receiver to get the error code of the request and whether the
     *                 device is provisioned with a satellite provider.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestIsSatelliteProvisioned(in ResultReceiver receiver);

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteError} result of the operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForSatelliteModemStateChanged(ISatelliteModemStateCallback callback);

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to registerForSatelliteStateChanged.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForModemStateChanged(ISatelliteModemStateCallback callback);

   /**
     * Register to receive incoming datagrams over satellite.
     *
     * @param callback The callback to handle the incoming datagrams.
     *
     * @return The {@link SatelliteError} result of the operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForIncomingDatagram(ISatelliteDatagramCallback callback);

   /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to registerForIncomingDatagram.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForIncomingDatagram(ISatelliteDatagramCallback callback);

   /**
    * Poll pending satellite datagrams over satellite.
    *
    * @param callback The callback to get the result of the request.
    */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
                + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void pollPendingDatagrams(IIntegerConsumer callback);

   /**
    * Send datagram over satellite.
    *
    * @param datagramType Type of datagram.
    * @param datagram Datagram to send over satellite.
    * @param needFullScreenPointingUI this is used to indicate pointingUI app to open in
    *                                 full screen mode.
    * @param callback The callback to get the result of the request.
    */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void sendDatagram(int datagramType, in SatelliteDatagram datagram,
            in boolean needFullScreenPointingUI, IIntegerConsumer callback);

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param subId The subId of the subscription to get whether satellite communication is allowed
     *              for the current location for.
     * @param receiver Result receiver to get the error code of the request and whether satellite
     *                 communication is allowed for the current location.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestIsCommunicationAllowedForCurrentLocation(int subId, in ResultReceiver receiver);

    /**
     * Request to get the time after which the satellite will be visible.
     *
     * @param receiver Result receiver to get the error code of the request and the requested
     *                 time after which the satellite will be visible.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestTimeForNextSatelliteVisibility(in ResultReceiver receiver);

    /**
     * Inform whether the device is aligned with the satellite within in margin for demo mode.
     *
     * @param isAligned {@true} Device is aligned with the satellite for demo mode
     *                  {@false} Device is not aligned with the satellite for demo mode
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void setDeviceAlignedWithSatellite(boolean isAligned);

    /**
     * This API can be used by only CTS to update satellite vendor service package name.
     *
     * @param servicePackageName The package name of the satellite vendor service.
     * @param provisioned Whether satellite should be provisioned or not.
     *
     * @return {@code true} if the satellite vendor service is set successfully,
     * {@code false} otherwise.
     */
    boolean setSatelliteServicePackageName(in String servicePackageName, in String provisioned);

    /**
     * This API can be used by only CTS to update satellite gateway service package name.
     *
     * @param servicePackageName The package name of the satellite gateway service.
     * @return {@code true} if the satellite gateway service is set successfully,
     * {@code false} otherwise.
     */
    boolean setSatelliteGatewayServicePackageName(in String servicePackageName);

    /**
     * This API can be used by only CTS to update the timeout duration in milliseconds that
     * satellite should stay at listening mode to wait for the next incoming page before disabling
     * listening mode.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    boolean setSatelliteListeningTimeoutDuration(in long timeoutMillis);

    /**
     * This API can be used by only CTS to control ingoring cellular service state event.
     *
     * @param enabled Whether to enable boolean config.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    boolean setSatelliteIgnoreCellularServiceState(in boolean enabled);

    /**
     * This API can be used by only CTS to update satellite pointing UI app package and class names.
     *
     * @param packageName The package name of the satellite pointing UI app.
     * @param className The class name of the satellite pointing UI app.
     * @return {@code true} if the satellite pointing UI app package and class is set successfully,
     * {@code false} otherwise.
     */
    boolean setSatellitePointingUiClassName(in String packageName, in String className);

    /**
     * This API can be used by only CTS to override the timeout durations used by the
     * DatagramController module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    boolean setDatagramControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis);

    /**
     * This API can be used by only CTS to override the timeout durations used by the
     * SatelliteController module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    boolean setSatelliteControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis);

    /**
     * This API can be used in only testing to override connectivity status in monitoring emergency
     * calls and sending EVENT_DISPLAY_EMERGENCY_MESSAGE to Dialer.
     *
     * @param handoverType The type of handover from emergency call to satellite messaging. Use one
     *                     of the following values to enable the override:
     *                     1 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS
     *                     2 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911
     *                     To disable the override, use -1 for handoverType.
     * @param delaySeconds The event EVENT_DISPLAY_EMERGENCY_MESSAGE will be sent to Dialer
     *                     delaySeconds after the emergency call starts.
     * @return {@code true} if the handover type is set successfully, {@code false} otherwise.
     */
    boolean setEmergencyCallToSatelliteHandoverType(int handoverType, int delaySeconds);

    /**
     * This API should be used by only CTS tests to forcefully set the country codes.
     *
     * @param reset {@code true} mean the overridden country codes should not be used, {@code false}
     *              otherwise.
     * @return {@code true} if the country code is set successfully, {@code false} otherwise.
     */
    boolean setCountryCodes(in boolean reset, in List<String> currentNetworkCountryCodes,
            in Map cachedNetworkCountryCodes, in String locationCountryCode,
            in long locationCountryCodeTimestampNanos);

    /**
     * This API should be used by only CTS tests to override the overlay configs of satellite
     * access controller.
     *
     * @param reset {@code true} mean the overridden configs should not be used, {@code false}
     *              otherwise.
     * @return {@code true} if the overlay configs are set successfully, {@code false} otherwise.
     */
    boolean setSatelliteAccessControlOverlayConfigs(in boolean reset, in boolean isAllowed,
            in String s2CellFile, in long locationFreshDurationNanos,
            in List<String> satelliteCountryCodes);

    /**
     * This API can be used in only testing to override oem-enabled satellite provision status.
     *
     * @param reset {@code true} mean the overriding status should not be used, {@code false}
     *              otherwise.
     * @param isProvisioned The overriding provision status.
     * @return {@code true} if the provision status is set successfully, {@code false} otherwise.
     */
    boolean setOemEnabledSatelliteProvisionStatus(in boolean reset, in boolean isProvisioned);

    /**
     * Test method to confirm the file contents are not altered.
     */
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
                 + "android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
     List<String> getShaIdFromAllowList(String pkgName, int carrierId);

    /**
     * Add a restriction reason for disallowing satellite communication.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication for carrier.
     * @param callback Listener for the {@link SatelliteManager.SatelliteError} result of the
     * operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void addAttachRestrictionForCarrier(int subId, int reason, in IIntegerConsumer callback);

    /**
     * Remove a restriction reason for disallowing satellite communication.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication.
     * @param callback Listener for the {@link SatelliteManager.SatelliteError} result of the
     * operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void removeAttachRestrictionForCarrier(int subId, int reason, in IIntegerConsumer callback);

    /**
     * Get reasons for disallowing satellite communication, as requested by
     * {@link #addAttachRestrictionForCarrier(int, int)}.
     *
     * @param subId The subId of the subscription to request for.
     *
     * @return Set of reasons for disallowing satellite communication.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int[] getAttachRestrictionReasonsForCarrier(int subId);

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * @param receiver Result receiver to get the error code of the request and the current signal
     * strength of the satellite connection.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestNtnSignalStrength(in ResultReceiver receiver);

    /**
     * Registers for NTN signal strength changed from satellite modem. If the registration operation
     * is not successful, a {@link SatelliteException} that contains {@link SatelliteResult} will be
     * thrown.
     *
     * @param callback The callback to handle the NTN signal strength changed event. If the
     * operation is successful, {@link NtnSignalStrengthCallback#onNtnSignalStrengthChanged(
     * NtnSignalStrength)} will return an instance of {@link NtnSignalStrength} with a value of
     * {@link NtnSignalStrength.NtnSignalStrengthLevel} when the signal strength of non-terrestrial
     * network has changed.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void registerForNtnSignalStrengthChanged(in INtnSignalStrengthCallback callback);

    /**
     * Unregisters for NTN signal strength changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForNtnSignalStrengthChanged(Executor, NtnSignalStrengthCallback)}.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForNtnSignalStrengthChanged(in INtnSignalStrengthCallback callback);

    /**
     * Registers for satellite capabilities change event from the satellite service.
     *
     * @param callback The callback to handle the satellite capabilities changed event.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForCapabilitiesChanged(in ISatelliteCapabilitiesCallback callback);

    /**
     * Unregisters for satellite capabilities change event from the satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to.
     * {@link #registerForCapabilitiesChanged(Executor, SatelliteCapabilitiesCallback)}.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForCapabilitiesChanged(in ISatelliteCapabilitiesCallback callback);

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value : config_send_satellite_datagram_to_modem_in_demo_mode, which determines whether
     * outgoing satellite datagrams should be sent to modem in demo mode.
     *
     * @param shouldSendToDemoMode Whether send datagram in demo mode should be sent to satellite
     * modem or not.
     *
     * @return {@code true} if the operation is successful, {@code false} otherwise.
     */
    boolean setShouldSendDatagramToModemInDemoMode(boolean shouldSendToModemInDemoMode);

    /**
     *  @return {@code true} if the DomainSelectionService is set,
     *          {@code false} otherwise.
     */
    boolean setDomainSelectionServiceOverride(in ComponentName componentName);

    /**
     *  @return {@code true} if the DomainSelectionService override is cleared,
     *          {@code false} otherwise.
     */
    boolean clearDomainSelectionServiceOverride();

    /**
     * @return {@code true} if the AOSP domain selection service is supported,
     *         {@code false} otherwise.
     */
    boolean isAospDomainSelectionService();

    /**
     * Enable or disable notifications sent for cellular identifier disclosure events.
     *
     * Disclosure events are defined as instances where a device has sent a cellular identifier
     * on the Non-access stratum (NAS) before a security context is established. As a result the
     * identifier is sent in the clear, which has privacy implications for the user.
     *
     * <p>Requires permission: android.Manifest.MODIFY_PHONE_STATE</p>
     *
     * @param enabled if notifications about disclosure events should be enabled
     * @throws IllegalStateException if the Telephony process is not currently available
     * @throws SecurityException if the caller does not have the required privileges
     * @throws UnsupportedOperationException if the modem does not support this feature.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
        + "android.Manifest.permission.MODIFY_PHONE_STATE)")
    void setEnableCellularIdentifierDisclosureNotifications(boolean enable);

    /**
     * Get whether or not cellular identifier disclosure notifications are enabled.
     *
     * <p>Requires permission: android.Manifest.READ_PRIVILEGED_PHONE_STATE</p>
     *
     * @throws IllegalStateException if the Telephony process is not currently available
     * @throws SecurityException if the caller does not have the required privileges
     * @throws UnsupportedOperationException if the modem does not support this feature.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
        + "android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
    boolean isCellularIdentifierDisclosureNotificationsEnabled();

    /**
     * Enables or disables notifications sent when cellular null cipher or integrity algorithms
     * are in use by the cellular modem.
     *
     * @throws IllegalStateException if the Telephony process is not currently available
     * @throws SecurityException if the caller does not have the required privileges
     * @throws UnsupportedOperationException if the modem does not support reporting on ciphering
     * and integrity algorithms in use
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
        + "android.Manifest.permission.MODIFY_PHONE_STATE)")
    void setNullCipherNotificationsEnabled(boolean enable);

    /**
     * Get whether notifications are enabled for null cipher or integrity algorithms in use by the
     * cellular modem.
     *
     * @throws IllegalStateException if the Telephony process is not currently available
     * @throws SecurityException if the caller does not have the required privileges
     * @throws UnsupportedOperationException if the modem does not support reporting on ciphering
     * and integrity algorithms in use
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
        + "android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
    boolean isNullCipherNotificationsEnabled();

    /**
     * Get the aggregated satellite plmn list. This API collects plmn data from multiple sources,
     * including carrier config, entitlement server, and config update.
     *
     * @param subId subId The subscription ID of the carrier.
     *
     * @return List of plmns for carrier satellite service. If no plmn is available, empty list will
     * be returned.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    List<String> getSatellitePlmnsForCarrier(int subId);

    /**
     * Registers for supported state changed from satellite modem.
     *
     * @param callback The callback to handle the satellite supported state changed event.
     *
     * @return The {@link SatelliteError} result of the operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForSatelliteSupportedStateChanged(
            in ISatelliteSupportedStateCallback callback);

    /**
     * Unregisters for supported state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to registerForSatelliteSupportedStateChanged.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForSatelliteSupportedStateChanged(
            in ISatelliteSupportedStateCallback callback);

    /**
     * Registers for satellite communication allowed state changed.
     *
     * @param subId The subId of the subscription to register for communication allowed state.
     * @param callback The callback to handle the communication allowed state changed event.
     *
     * @return The {@link SatelliteError} result of the operation.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    int registerForCommunicationAllowedStateChanged(int subId,
            in ISatelliteCommunicationAllowedStateCallback callback);

    /**
     * Unregisters for satellite communication allowed state.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId The subId of the subscription to unregister for supported state changed.
     * @param callback The callback that was passed to registerForCommunicationAllowedStateChanged.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void unregisterForCommunicationAllowedStateChanged(int subId,
            in ISatelliteCommunicationAllowedStateCallback callback);

    /**
     * This API can be used by only CTS to override the boolean configs used by the
     * DatagramController module.
     *
     * @param enable Whether to enable boolean config.
     * @return {@code true} if the boolean config is set successfully, {@code false} otherwise.
     */
    boolean setDatagramControllerBooleanConfig(boolean reset, int booleanType, boolean enable);

    /**
     * This API can be used by only CTS to set the cache whether satellite communication is allowed.
     *
     * @param state a state indicates whether satellite access allowed state should be cached and
     * the allowed state.
     * @return {@code true} if the setting is successful, {@code false} otherwise.
     */
    boolean setIsSatelliteCommunicationAllowedForCurrentLocationCache(in String state);

    /**
     * Request to get the session stats of the satellite service.
     *
     * @param subId The subId of the subscription to get the session stats for.
     * @param receiver Result receiver to get the error code of the request and the requested
     *                 session stats of the satellite service.
     * @hide
     */
    void requestSatelliteSessionStats(int subId, in ResultReceiver receiver);

    /**
     * Request to get list of prioritized satellite subscriber ids to be used for provision.
     *
     * @param result The result receiver, which returns the list of prioritized satellite tokens
     * to be used for provision if the request is successful or an error code if the request failed.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void requestSatelliteSubscriberProvisionStatus(in ResultReceiver result);

    /**
     * Deliver the list of provisioned satellite subscriber infos.
     *
     * @param list The list of provisioned satellite subscriber infos.
     * @param result The result receiver that returns whether deliver success or fail.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void provisionSatellite(in List<SatelliteSubscriberInfo> list, in ResultReceiver result);

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value :
     * config_satellite_gateway_service_package and
     * config_satellite_carrier_roaming_esos_provisioned_class.
     * These values are set before sending an intent to broadcast there are any change to list of
     * subscriber informations.
     *
     * @param name the name is one of the following that constitute an intent.
     * Component package name, or component class name.
     * @return {@code true} if the setting is successful, {@code false} otherwise.
     * @hide
     */
    boolean setSatelliteSubscriberIdListChangedIntentComponent(in String name);

    /**
     * This API can be used by only CTS to override the Euicc UI component.
     *
     * @param componentName ui component to be launched for testing
     * @hide
     */
    void setTestEuiccUiComponent(in ComponentName componentName);

    /**
     * This API can be used by only CTS to retrieve the Euicc UI component.
     *
     * @return The Euicc UI component for testing.
     * @hide
     */
    ComponentName getTestEuiccUiComponent();

    /**
     * This API can be used only for test purpose to override the carrier romaing Ntn eligibility
     *
     * @param status to update Ntn Eligibility.
     * @param resetRequired to reset the overridden flag in satellite controller.
     * @return {@code true} if the shell command is successful, {@code false} otherwise.
     * @hide
     */
    boolean overrideCarrierRoamingNtnEligibilityChanged(
            in boolean status, in boolean resetRequired);

    /**
     * Deliver the list of deprovisioned satellite subscriber infos.
     *
     * @param list The list of deprovisioned satellite subscriber infos.
     * @param result The result receiver that returns whether deliver success or fail.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission("
            + "android.Manifest.permission.SATELLITE_COMMUNICATION)")
    void deprovisionSatellite(in List<SatelliteSubscriberInfo> list, in ResultReceiver result);
}
