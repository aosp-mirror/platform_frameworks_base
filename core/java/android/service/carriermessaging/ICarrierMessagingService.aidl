/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.carriermessaging;

import android.net.Uri;
import android.service.carriermessaging.ICarrierMessagingCallback;
import android.service.carriermessaging.MessagePdu;

/**
 * <p class="note"><strong>Note:</strong>
 * This service can only be implemented by a carrier privileged app.
 */
oneway interface ICarrierMessagingService {
    /**
     * Request filtering an incoming SMS message.
     * The service will call callback.onFilterComplete with the filtering result.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a data SMS. It will be -1 for text SMS
     * @param callback the callback to notify upon completion
     */
    void filterSms(
        in MessagePdu pdu, String format, int destPort, in ICarrierMessagingCallback callback);

    /**
     * Request sending a new text SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendSmsComplete} with the send
     * status.
     *
     * @param text the text to send
     * @param format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     * @param callback the callback to notify upon completion
     */
    void sendTextSms(String text, String format, String destAddress,
            in ICarrierMessagingCallback callback);

    /**
     * Request sending a new data SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendSmsComplete} with the send
     * status.
     *
     * @param data the data to send
     * @param format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     * @param destPort port number of the recipient of the message
     * @param callback the callback to notify upon completion
     */
    void sendDataSms(in byte[] data, String format, String destAddress, int destPort,
            in ICarrierMessagingCallback callback);

    /**
     * Request sending a new multi-part text SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendMultipartSmsComplete}
     * with the send status.
     *
     * @param parts the parts of the multi-part text SMS to send
     * @param format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     * @param callback the callback to notify upon completion
     */
    void sendMultipartTextSms(in List<String> parts, String format, String destAddress,
            in ICarrierMessagingCallback callback);

    /**
     * Request sending a new MMS PDU from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendMmsComplete} with the send
     * status.
     *
     * @param pduUri the content provider URI of the PDU to send
     * @param locationUrl the optional url to send this MMS PDU.
     *         If this is not specified, PDU should be sent to the default MMSC url.
     * @param callback the callback to notify upon completion
     */
    void sendMms(in Uri pduUri, String locationUrl, in ICarrierMessagingCallback callback);

    /**
     * Request downloading a new MMS.
     * The service will call {@link ICarrierMessagingCallback#onDownloadMmsComplete} with the
     * download status.
     *
     * @param pduUri the content provider URI of the PDU to be downloaded.
     * @param locationUrl the URL of the message to be downloaded.
     * @param callback the callback to notify upon completion
     */
    void downloadMms(in Uri pduUri, String locationUrl, in ICarrierMessagingCallback callback);
}

