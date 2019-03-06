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
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ResultReceiver;
import android.os.WorkSource;
import android.net.NetworkStats;
import android.net.Uri;
import android.service.carrier.CarrierIdentifier;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierRestrictionRules;
import android.telephony.CellInfo;
import android.telephony.ClientRequestStats;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.ICellInfoCallback;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.NetworkScanRequest;
import android.telephony.PhoneNumberRange;
import android.telephony.RadioAccessFamily;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyHistogram;
import android.telephony.VisualVoicemailSmsFilterSettings;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.INumberVerificationCallback;
import com.android.internal.telephony.OperatorInfo;

import java.util.List;
import java.util.Map;

import android.telephony.UiccCardInfo;
import android.telephony.UiccSlotInfo;

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

    /**
     * Check to see if the radio is on or not.
     * @param callingPackage the name of the package making the call.
     * @return returns true if the radio is on.
     */
    boolean isRadioOn(String callingPackage);

    /**
     * Check to see if the radio is on or not on particular subId.
     * @param subId user preferred subId.
     * @param callingPackage the name of the package making the call.
     * @return returns true if the radio is on.
     */
    @UnsupportedAppUsage
    boolean isRadioOnForSubscriber(int subId, String callingPackage);

    /**
     * Supply a pin to unlock the SIM.  Blocks until a result is determined.
     * @param pin The pin to check.
     * @return whether the operation was a success.
     */
    @UnsupportedAppUsage
    boolean supplyPin(String pin);

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
     * @return whether the operation was a success.
     */
    boolean supplyPuk(String puk, String pin);

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
    int[] supplyPinReportResult(String pin);

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
    int[] supplyPukReportResult(String puk, String pin);

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
    @UnsupportedAppUsage
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
     * Request to update location information in service state
     */
    @UnsupportedAppUsage
    void updateServiceLocation();

    /**
     * Request to update location information for a subscrition in service state
     * @param subId user preferred subId.
     */
    void updateServiceLocationForSubscriber(int subId);

    /**
     * Enable location update notifications.
     */
    @UnsupportedAppUsage
    void enableLocationUpdates();

    /**
     * Enable location update notifications.
     * @param subId user preferred subId.
     */
    void enableLocationUpdatesForSubscriber(int subId);

    /**
     * Disable location update notifications.
     */
    @UnsupportedAppUsage
    void disableLocationUpdates();

    /**
     * Disable location update notifications.
     * @param subId user preferred subId.
     */
    void disableLocationUpdatesForSubscriber(int subId);

    /**
     * Allow mobile data connections.
     */
    @UnsupportedAppUsage
    boolean enableDataConnectivity();

    /**
     * Disallow mobile data connections.
     */
    @UnsupportedAppUsage
    boolean disableDataConnectivity();

    /**
     * Report whether data connectivity is possible.
     */
    boolean isDataConnectivityPossible(int subId);

    Bundle getCellLocation(String callingPkg);

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @see android.telephony.TelephonyManager#getNetworkCountryIso
     */
    String getNetworkCountryIsoForPhone(int phoneId);

    /**
     * Returns the neighboring cell information of the device.
     */
    List<NeighboringCellInfo> getNeighboringCellInfo(String callingPkg);

     @UnsupportedAppUsage
     int getCallState();

    /**
     * Returns the call state for a slot.
     */
     int getCallStateForSlot(int slotIndex);

     @UnsupportedAppUsage
     int getDataActivity();
     @UnsupportedAppUsage
     int getDataState();

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
     */
    int getCdmaEriIconIndex(String callingPackage);

    /**
     * Returns the CDMA ERI icon index to display on particular subId.
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     */
    int getCdmaEriIconIndexForSubscriber(int subId, String callingPackage);

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     * @param callingPackage package making the call.
     */
    int getCdmaEriIconMode(String callingPackage);

    /**
     * Returns the CDMA ERI icon mode on particular subId,
     * 0 - ON
     * 1 - FLASHING
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     */
    int getCdmaEriIconModeForSubscriber(int subId, String callingPackage);

    /**
     * Returns the CDMA ERI text,
     * @param callingPackage package making the call.
     */
    String getCdmaEriText(String callingPackage);

    /**
     * Returns the CDMA ERI text for particular subId,
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     */
    String getCdmaEriTextForSubscriber(int subId, String callingPackage);

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
    int getVoiceMessageCountForSubscriber(int subId, String callingPackage);

    /**
      * Returns true if current state supports both voice and data
      * simultaneously. This can change based on location or network condition.
      */
    boolean isConcurrentVoiceAndDataAllowed(int subId);

    Bundle getVisualVoicemailSettings(String callingPackage, int subId);

    String getVisualVoicemailPackageName(String callingPackage, int subId);

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
    void sendVisualVoicemailSmsForSubscriber(in String callingPackage, in int subId,
            in String number, in int port, in String text, in PendingIntent sentIntent);

    // Send the special dialer code. The IPC caller must be the current default dialer.
    void sendDialerSpecialCode(String callingPackageName, String inputCode);

    /**
     * Returns the network type for data transmission
     * Legacy call, permission-free
     */
    @UnsupportedAppUsage
    int getNetworkType();

    /**
     * Returns the network type of a subId.
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     */
    int getNetworkTypeForSubscriber(int subId, String callingPackage);

    /**
     * Returns the network type for data transmission
     * @param callingPackage package making the call.
     */
    int getDataNetworkType(String callingPackage);

    /**
     * Returns the data network type of a subId
     * @param subId user preferred subId.
     * @param callingPackage package making the call.
     */
    int getDataNetworkTypeForSubscriber(int subId, String callingPackage);

    /**
      * Returns the voice network type of a subId
      * @param subId user preferred subId.
      * @param callingPackage package making the call.
      * Returns the network type
      */
    int getVoiceNetworkTypeForSubscriber(int subId, String callingPackage);

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
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    int getLteOnCdmaMode(String callingPackage);

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param callingPackage the name of the calling package
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    int getLteOnCdmaModeForSubscriber(int subId, String callingPackage);

    /**
     * Returns all observed cell information of the device.
     */
    List<CellInfo> getAllCellInfo(String callingPkg);

    /**
     * Request a cell information update for the specified subscription,
     * reported via the CellInfoCallback.
     */
    void requestCellInfoUpdate(int subId, in ICellInfoCallback cb, String callingPkg);

    /**
     * Request a cell information update for the specified subscription,
     * reported via the CellInfoCallback.
     *
     * @param workSource the requestor to whom the power consumption for this should be attributed.
     */
    void requestCellInfoUpdateWithWorkSource(
            int subId, in ICellInfoCallback cb, in String callingPkg, in WorkSource ws);

    /**
     * Sets minimum time in milli-seconds between onCellInfoChanged
     */
    void setCellInfoListRate(int rateInMillis);

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * @param subId The subscription to use.
     * @param callingPackage the name of the package making the call.
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     */
    IccOpenLogicalChannelResponse iccOpenLogicalChannel(
            int subId, String callingPackage, String AID, int p2);

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * @param subId The subscription to use.
     * @param channel is the channel id to be closed as retruned by a
     *            successful iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     */
    @UnsupportedAppUsage
    boolean iccCloseLogicalChannel(int subId, int channel);

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * @param subId The subscription to use.
     * @param channel is the channel id to be closed as retruned by a
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
    @UnsupportedAppUsage
    String iccTransmitApduLogicalChannel(int subId, int channel, int cla, int instruction,
            int p1, int p2, int p3, String data);

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
     * Get the calculated preferred network type.
     * Used for device configuration by some CDMA operators.
     * @param callingPackage The package making the call.
     *
     * @return the calculated preferred network type, defined in RILConstants.java.
     */
    int getCalculatedPreferredNetworkType(String callingPackage);

    /*
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * @param subId the id of the subscription to query.
     * @return the preferred network type, defined in RILConstants.java.
     */
    int getPreferredNetworkType(int subId);

    /**
     * Check whether DUN APN is required for tethering.
     *
     * @return {@code true} if DUN APN is required for tethering.
     * @hide
     */
    boolean getTetherApnRequired();

    /**
    * Enables framework IMS and triggers IMS Registration.
    */
    void enableIms(int slotId);

    /**
    * Disables framework IMS and triggers IMS deregistration.
    */
    void disableIms(int slotId);

    /**
     *  Get IImsMmTelFeature binder from ImsResolver that corresponds to the subId and MMTel feature
     *  as well as registering the MmTelFeature for callbacks using the IImsServiceFeatureCallback
     *  interface.
     */
    IImsMmTelFeature getMmTelFeatureAndListen(int slotId, in IImsServiceFeatureCallback callback);

    /**
     *  Get IImsRcsFeature binder from ImsResolver that corresponds to the subId and RCS feature
     *  as well as registering the RcsFeature for callbacks using the IImsServiceFeatureCallback
     *  interface.
     */
    IImsRcsFeature getRcsFeatureAndListen(int slotId, in IImsServiceFeatureCallback callback);

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
    boolean setImsService(int slotId, boolean isCarrierImsService, String packageName);

    /**
    * @return the package name of the carrier/device ImsService associated with this slot.
    */
    String getImsService(int slotId, boolean isCarrierImsService);

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
     * @return CellNetworkScanResult containing status of scan and networks.
     */
    CellNetworkScanResult getCellNetworkScanResults(int subId, String callingPackage);

    /**
     * Perform a radio network scan and return the id of this scan.
     *
     * @param subId the id of the subscription.
     * @param request Defines all the configs for network scan.
     * @param messenger Callback messages will be sent using this messenger.
     * @param binder the binder object instantiated in TelephonyManager.
     * @param callingPackage the calling package
     * @return An id for this scan.
     */
    int requestNetworkScan(int subId, in NetworkScanRequest request, in Messenger messenger,
            in IBinder binder, in String callingPackage);

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
     * Set the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * @param subId the id of the subscription to update.
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     */
    boolean setPreferredNetworkType(int subId, int networkType);

    /**
     * User enable/disable Mobile Data.
     *
     * @param enable true to turn on, else false
     */
    void setUserDataEnabled(int subId, boolean enable);

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
     * Get the overall enabled state of Mobile Data.
     *
     * @return true on enabled
     */
    boolean isDataEnabled(int subId);

     /**
     * Checks if manual network selection is allowed.
     *
     * @return {@code true} if manual network selection is allowed, otherwise return {@code false}.
     */
     boolean isManualNetworkSelectionAllowed(int subId);

    /**
     * Get P-CSCF address from PCO after data connection is established or modified.
     * @param apnType the apnType, "ims" for IMS APN, "emergency" for EMERGENCY APN
     * @param callingPackage The package making the call.
     */
    String[] getPcscfAddress(String apnType, String callingPackage);

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
     */
    int checkCarrierPrivilegesForPackage(String pkgName);

    /**
     * Similar to above, but check across all phones.
     */
    int checkCarrierPrivilegesForPackageAnyPhone(String pkgName);

    /**
     * Returns list of the package names of the carrier apps that should handle the input intent
     * and have carrier privileges for the given phoneId.
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
     * @return the displayed dialing number if set, or null if not set.
     */
    String getLine1NumberForDisplay(int subId, String callingPackage);

    /**
     * Returns the displayed alphatag of the dialing number if it was set
     * previously via {@link #setLine1NumberForDisplay}. Otherwise returns null.
     *
     * @param subId whose alphatag associated with line 1 is returned.
     * @param callingPackage The package making the call.
     * @return the displayed alphatag of the dialing number if set, or null if
     *         not set.
     */
    String getLine1AlphaTagForDisplay(int subId, String callingPackage);

    String[] getMergedSubscriberIds(String callingPackage);

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
     * Returns the result and response from RIL for oem request
     *
     * @param oemReq the data is sent to ril.
     * @param oemResp the respose data from RIL.
     * @return negative value request was not handled or get error
     *         0 request was handled succesfully, but no response data
     *         positive value success, data length of response
     */
    int invokeOemRilRequestRaw(in byte[] oemReq, out byte[] oemResp);

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
     * Set phone radio type and access technology.
     *
     * @param rafs an RadioAccessFamily array to indicate all phone's
     *        new radio access family. The length of RadioAccessFamily
     *        must equ]]al to phone count.
     */
    void setRadioCapability(in RadioAccessFamily[] rafs);

    /**
     * Get phone radio type and access technology.
     *
     * @param phoneId which phone you want to get
     * @param callingPackage the name of the package making the call
     * @return phone radio type and access technology
     */
    int getRadioAccessFamily(in int phoneId, String callingPackage);

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
     * @return {@code true} if the user has enabled video calling, {@code false} otherwise.
     */
    boolean isVideoCallingEnabled(String callingPackage);

    /**
     * Whether the DTMF tone length can be changed.
     *
     * @param subId The subscription to use.
     * @param callingPackage The package making the call.
     * @return {@code true} if the DTMF tone length can be changed.
     */
    boolean canChangeDtmfToneLength(int subId, String callingPackage);

    /**
     * Whether the device is a world phone.
     *
     * @param callingPackage The package making the call.
     * @return {@code true} if the devices is a world phone.
     */
    boolean isWorldPhone(int subId, String callingPackage);

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

    /**
      * Returns the unique device ID of phone, for example, the IMEI for
      * GSM and the MEID for CDMA phones. Return null if device ID is not available.
      *
      * @param callingPackage The package making the call.
      * <p>Requires Permission:
      *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
      */
    String getDeviceId(String callingPackage);

    /**
     * Returns the IMEI for the given slot.
     *
     * @param slotIndex - device slot.
     * @param callingPackage The package making the call.
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getImeiForSlot(int slotIndex, String callingPackage);

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
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getMeidForSlot(int slotIndex, String callingPackage);

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
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    String getDeviceSoftwareVersionForSlot(int slotIndex, String callingPackage);

    /**
     * Returns the subscription ID associated with the specified PhoneAccount.
     */
    int getSubIdForPhoneAccount(in PhoneAccount phoneAccount);

    void factoryReset(int subId);

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
     * Get the service state on specified subscription
     * @param subId Subscription id
     * @param callingPackage The package making the call
     * @return Service state on specified subscription.
     */
    ServiceState getServiceStateForSubscriber(int subId, String callingPackage);

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
     * Returns a list of packages that have carrier privileges.
     */
    List<String> getPackagesWithCarrierPrivileges();

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
     * <p>The precise carrier id can be used to further differentiate a carrier by different
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
    int getSubscriptionPreciseCarrierId(int subId);

    /**
     * Similar like {@link #getSimCarrierIdName()}, returns user-facing name of the
     * precise carrier id {@link #getSimPreciseCarrierId()}
     *
     * <p>The returned name is unlocalized.
     *
     * @return user-facing name of the subscription precise carrier id. Return {@code null} if the
     * subscription is unavailable or the carrier cannot be identified.
     * @hide
     */
    String getSubscriptionPreciseCarrierName(int subId);

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
     * Action set from carrier signalling broadcast receivers to enable/disable metered apns
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param enabled control enable or disable metered apns.
     * @hide
     */
    void carrierActionSetMeteredApnsEnabled(int subId, boolean visible);

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
     * Get aggregated video call data usage since boot.
     * Permissions android.Manifest.permission.READ_NETWORK_USAGE_HISTORY is required.
     *
     * @param perUidStats True if requesting data usage per uid, otherwise overall usage.
     * @return Snapshot of video call data usage
     * @hide
     */
    NetworkStats getVtDataUsage(int subId, boolean perUidStats);

    /**
     * Policy control of data connection. Usually used when data limit is passed.
     * @param enabled True if enabling the data, otherwise disabling.
     * @param subId Subscription index
     * @hide
     */
    void setPolicyDataEnabled(boolean enabled, int subId);

    /**
     * Get Client request stats which will contain statistical information
     * on each request made by client.
     * @param callingPackage package making the call.
     * @param subId Subscription index
     * @hide
     */
    List<ClientRequestStats> getClientRequestStats(String callingPackage, int subid);

    /**
     * Set SIM card power state.
     * @param slotIndex SIM slot id
     * @param state  State of SIM (power down, power up, pass through)
     * @hide
     * */
    void setSimPowerStateForSlot(int slotIndex, int state);

    /**
     * Returns a list of Forbidden PLMNs from the specified SIM App
     * Returns null if the query fails.
     *
     * <p>Requires that the calling app has READ_PRIVILEGED_PHONE_STATE or READ_PHONE_STATE
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     */
    String[] getForbiddenPlmns(int subId, int appType, String callingPackage);

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
     * Gets information about currently inserted UICCs and enabled eUICCs.
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
    UiccSlotInfo[] getUiccSlotsInfo();

    /**
     * Map logicalSlot to physicalSlot, and activate the physicalSlot if it is inactive.
     * @param physicalSlots Index i in the array representing physical slot for phone i. The array
     *        size should be same as getPhoneCount().
     * @return boolean Return true if the switch succeeds, false if the switch fails.
     */
    boolean switchSlots(in int[] physicalSlots);

    /**
     * Sets radio indication update mode. This can be used to control the behavior of indication
     * update from modem to Android frameworks. For example, by default several indication updates
     * are turned off when screen is off, but in some special cases (e.g. carkit is connected but
     * screen is off) we want to turn on those indications even when the screen is off.
     */
    void setRadioIndicationUpdateMode(int subId, int filters, int mode);

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
            String gid2, String plmn, String spn);

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
    int getNumberOfModemsWithSimultaneousDataConnections(int subId, String callingPackage);

    /**
     * Return the network selection mode on the subscription with id {@code subId}.
     */
     int getNetworkSelectionMode(int subId);

    /**
     * Get a list of SMS apps on a user.
     */
    String[] getSmsApps(int userId);

    /**
     * Get the default SMS app on a given user.
     */
    String getDefaultSmsApp(int userId);

    /**
     * Set the default SMS app to a given package on a given user.
     */
    void setDefaultSmsApp(int userId, String packageName);

     /**
      * Return the modem radio power state for slot index.
      *
      */
     int getRadioPowerState(int slotIdex, String callingPackage);

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
    Map getCurrentEmergencyNumberList(String callingPackage);

    /**
     * Identify if the number is emergency number, based on all the active subscriptions.
     */
    boolean isCurrentEmergencyNumber(String number, boolean exactMatch);

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
     * Set the provisioning status for the IMS MmTel capability using the specified subscription.
     */
    void setImsProvisioningStatusForCapability(int subId, int capability, int tech,
            boolean isProvisioned);

    /**
     * Get the provisioning status for the IMS MmTel capability specified.
     */
    boolean getImsProvisioningStatusForCapability(int subId, int capability, int tech);

    /** Is the capability and tech flagged as provisioned in the cache */
    boolean isMmTelCapabilityProvisionedInCache(int subId, int capability, int tech);

    /** Set the provisioning for the capability and tech in the cache */
    void cacheMmTelCapabilityProvisioning(int subId, int capability, int tech,
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
     * Update Emergency Number List for Test Mode.
     */
    void updateEmergencyNumberListTestMode(int action, in EmergencyNumber num);

    /**
     * Get the full emergency number list for Test Mode.
     */
    List<String> getEmergencyNumberListTestMode();

    /**
     * Enable or disable a logical modem stack associated with the slotIndex.
     */
    boolean enableModemForSlot(int slotIndex, boolean enable);

    /**
     * Indicate if the enablement of multi SIM functionality is restricted.
     * @hide
     */
    void setMultisimCarrierRestriction(boolean isMultisimCarrierRestricted);

    /**
     * Returns if the usage of multiple SIM cards at the same time is supported.
     *
     * @param callingPackage The package making the call.
     * @return true if multisim is supported, false otherwise.
     */
    boolean isMultisimSupported(String callingPackage);

    /**
     * Switch configs to enable multi-sim or switch back to single-sim
     * @hide
     */
    void switchMultiSimConfig(int numOfSims);

    /**
     * Get if reboot is required upon altering modems configurations
     * @hide
     */
    boolean isRebootRequiredForModemConfigChange();

    /**
     * Get the mapping from logical slots to physical slots.
     */
    int[] getSlotsMapping();

    /**
     * Get the IRadio HAL Version encoded as 100 * MAJOR_VERSION + MINOR_VERSION or -1 if unknown
     */
     int getRadioHalVersion();
}
