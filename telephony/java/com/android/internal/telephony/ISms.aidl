/*
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony;

import android.app.PendingIntent;
import com.android.internal.telephony.SmsRawData;

/** Interface for applications to access the ICC phone book.
 *
 * <p>The following code snippet demonstrates a static method to
 * retrieve the ISms interface from Android:</p>
 * <pre>private static ISms getSmsInterface()
            throws DeadObjectException {
    IServiceManager sm = ServiceManagerNative.getDefault();
    ISms ss;
    ss = ISms.Stub.asInterface(sm.getService("isms"));
    return ss;
}
 * </pre>
 */

interface ISms {
    /**
     * Retrieves all messages currently stored on ICC.
     *
     * @return list of SmsRawData of all sms on ICC
     */
     List<SmsRawData> getAllMessagesFromIccEf(String callingPkg);

    /**
     * Retrieves all messages currently stored on ICC.
     * @param subId the subId id.
     * @return list of SmsRawData of all sms on ICC
     */
    List<SmsRawData> getAllMessagesFromIccEfUsingSubId(in long subId, String callingPkg);

    /**
     * Update the specified message on the ICC.
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @return success or not
     *
     */
     boolean updateMessageOnIccEf(String callingPkg, int messageIndex, int newStatus,
            in byte[] pdu);

    /**
     * Update the specified message on the ICC.
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @param subId the subId id.
     * @return success or not
     *
     */
     boolean updateMessageOnIccEfUsingSubId(in long subId, String callingPkg,
             int messageIndex, int newStatus, in byte[] pdu);

    /**
     * Copy a raw SMS PDU to the ICC.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @return success or not
     *
     */
    boolean copyMessageToIccEf(String callingPkg, int status, in byte[] pdu, in byte[] smsc);

    /**
     * Copy a raw SMS PDU to the ICC.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @param subId the subId id.
     * @return success or not
     *
     */
    boolean copyMessageToIccEfUsingSubId(in long subId, String callingPkg, int status,
            in byte[] pdu, in byte[] smsc);

    /**
     * Send a data SMS.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applicaitons,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    void sendData(String callingPkg, in String destAddr, in String scAddr, in int destPort,
            in byte[] data, in PendingIntent sentIntent, in PendingIntent deliveryIntent);

    /**
     * Send a data SMS.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applicaitons,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     * @param subId the subId id.
     */
    void sendDataUsingSubId(long subId, String callingPkg, in String destAddr,
            in String scAddr, in int destPort, in byte[] data, in PendingIntent sentIntent,
            in PendingIntent deliveryIntent);

    /**
     * Send an SMS.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    void sendText(String callingPkg, in String destAddr, in String scAddr, in String text,
            in PendingIntent sentIntent, in PendingIntent deliveryIntent);

    /**
     * Send an SMS.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     * @param subId the subId on which the SMS has to be sent.
     */
    void sendTextUsingSubId(in long subId, String callingPkg, in String destAddr,
            in String scAddr, in String text, in PendingIntent sentIntent,
            in PendingIntent deliveryIntent);

    /**
     * Inject an SMS PDU into the android platform.
     *
     * @param pdu is the byte array of pdu to be injected into android application framework
     * @param format is the format of SMS pdu (android.telephony.SmsMessage.FORMAT_3GPP or
     * android.telephony.SmsMessage.FORMAT_3GPP2)
     * @param receivedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully received by the
     *  android application framework. This intent is broadcasted at
     *  the same time an SMS received from radio is acknowledged back.
     */
    void injectSmsPdu(in byte[] pdu, String format, in PendingIntent receivedIntent);

    /**
     * Send a multi-part text based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    void sendMultipartText(String callingPkg, in String destinationAddress, in String scAddress,
            in List<String> parts, in List<PendingIntent> sentIntents,
            in List<PendingIntent> deliveryIntents);

    /**
     * Send a multi-part text based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     * @param subId the subId on which the SMS has to be sent.
     */
    void sendMultipartTextUsingSubId(in long subId, String callingPkg,
            in String destinationAddress, in String scAddress,
            in List<String> parts, in List<PendingIntent> sentIntents,
            in List<PendingIntent> deliveryIntents);

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcast(int)
     */
    boolean enableCellBroadcast(int messageIdentifier);

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param subId for which the broadcast has to be enabled
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcast(int)
     */
    boolean enableCellBroadcastUsingSubId(in long subId, int messageIdentifier);

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcast(int)
     */
    boolean disableCellBroadcast(int messageIdentifier);

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param subId for which the broadcast has to be disabled
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcast(int)
     */
    boolean disableCellBroadcastUsingSubId(in long subId, int messageIdentifier);

    /*
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcastRange(int, int)
     */
    boolean enableCellBroadcastRange(int startMessageId, int endMessageId);

    /*
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param subId for which the broadcast has to be enabled
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcastRange(int, int)
     */
    boolean enableCellBroadcastRangeUsingSubId(long subId, int startMessageId, int endMessageId);

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcastRange(int, int)
     */
    boolean disableCellBroadcastRange(int startMessageId, int endMessageId);

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP) or
     *   C.R1001-G (3GPP2)
     * @param subId for which the broadcast has to be disabled
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcastRange(int, int, int)
     */
    boolean disableCellBroadcastRangeUsingSubId(long subId, int startMessageId,
            int endMessageId);

    /**
     * Returns the premium SMS send permission for the specified package.
     * Requires system permission.
     */
    int getPremiumSmsPermission(String packageName);

    /**
     * Returns the premium SMS send permission for the specified package.
     * Requires system permission.
     */
    int getPremiumSmsPermissionUsingSubId(long subId, String packageName);

    /**
     * Set the SMS send permission for the specified package.
     * Requires system permission.
     */
    void setPremiumSmsPermission(String packageName, int permission);

    /**
     * Set the SMS send permission for the specified package.
     * Requires system permission.
     */
     /**
     * Set the SMS send permission for the specified package.
     * Requires system permission.
     */
    void setPremiumSmsPermissionUsingSubId(long subId, String packageName, int permission);

    /**
     * SMS over IMS is supported if IMS is registered and SMS is supported
     * on IMS.
     *
     * @return true if SMS over IMS is supported, false otherwise
     *
     * @see #getImsSmsFormat()
     */
    boolean isImsSmsSupported();

    /**
     * SMS over IMS is supported if IMS is registered and SMS is supported
     * on IMS.
     * @param subId for subId which isImsSmsSupported is queried
     * @return true if SMS over IMS is supported, false otherwise
     *
     * @see #getImsSmsFormat()
     */
    boolean isImsSmsSupportedUsingSubId(long subId);

    /*
     * get user prefered SMS subId
     * @return subId id
     */
    long getPreferredSmsSubscription();


    /**
     * Gets SMS format supported on IMS.  SMS over IMS format is
     * either 3GPP or 3GPP2.
     *
     * @return android.telephony.SmsMessage.FORMAT_3GPP,
     *         android.telephony.SmsMessage.FORMAT_3GPP2
     *      or android.telephony.SmsMessage.FORMAT_UNKNOWN
     *
     * @see #isImsSmsSupported()
     */
    String getImsSmsFormat();

    /**
     * Gets SMS format supported on IMS.  SMS over IMS format is
     * either 3GPP or 3GPP2.
     * @param subId for subId which getImsSmsFormat is queried
     * @return android.telephony.SmsMessage.FORMAT_3GPP,
     *         android.telephony.SmsMessage.FORMAT_3GPP2
     *      or android.telephony.SmsMessage.FORMAT_UNKNOWN
     *
     * @see #isImsSmsSupported()
     */
    String getImsSmsFormatUsingSubId(long subId);




    /*
     * Get SMS prompt property,  enabled or not
     * @return true if enabled, false otherwise
     */
    boolean isSMSPromptEnabled();
}
