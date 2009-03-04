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

package com.android.internal.telephony.gsm;

import android.app.PendingIntent;
import com.android.internal.telephony.gsm.SmsRawData;

/** Interface for applications to access the SIM phone book.
 *
 * <p>The following code snippet demonstrates a static method to
 * retrieve the ISimSms interface from Android:</p>
 * <pre>private static ISimSms getSimSmsInterface()
            throws DeadObjectException {
    IServiceManager sm = ServiceManagerNative.getDefault();
    ISimSms ss;
    ss = ISimSms.Stub.asInterface(sm.getService("isms"));
    return ss;
}
 * </pre>
 */

interface ISms {
    /**
     * Retrieves all messages currently stored on SIM.
     *
     * @return list of SmsRawData of all sms on SIM
     */
     List<SmsRawData> getAllMessagesFromSimEf();

    /**
     * Update the specified message on the SIM.
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_SIM_READ,
     *                  STATUS_ON_SIM_UNREAD, STATUS_ON_SIM_SENT,
     *                  STATUS_ON_SIM_UNSENT, STATUS_ON_SIM_FREE)
     * @param pdu the raw PDU to store
     * @return success or not
     *
     */
     boolean updateMessageOnSimEf(int messageIndex, int newStatus,
            in byte[] pdu);

    /**
     * Copy a raw SMS PDU to the SIM.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_SIM_READ, STATUS_ON_SIM_UNREAD,
     *               STATUS_ON_SIM_SENT, STATUS_ON_SIM_UNSENT)
     * @return success or not
     *
     */
    boolean copyMessageToSimEf(int status, in byte[] pdu, in byte[] smsc);

    /**
     * Send a SMS
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  defatult SMSC
     * @param pdu the raw PDU to send
     * @param sentIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    void sendRawPdu(in byte[] smsc, in byte[] pdu, in PendingIntent sentIntent,
            in PendingIntent deliveryIntent);

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
    void sendMultipartText(in String destinationAddress, in String scAddress,
            in List<String> parts, in List<PendingIntent> sentIntents,
            in List<PendingIntent> deliveryIntents);

}
