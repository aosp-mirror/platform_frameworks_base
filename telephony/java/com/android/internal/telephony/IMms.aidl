/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.content.ContentValues;
import android.net.Uri;

/**
 * Service interface to handle MMS API requests
 */
interface IMms {
    /**
     * Send an MMS message
     *
     * @param subId the SIM id
     * @param callingPkg the package name of the calling app
     * @param pdu the MMS message encoded in standard MMS PDU format
     * @param locationUrl the optional location url for where this message should be sent to
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     */
    void sendMessage(long subId, String callingPkg, in byte[] pdu, String locationUrl,
            in PendingIntent sentIntent);

    /**
     * Download an MMS message using known location and transaction id
     *
     * @param subId the SIM id
     * @param callingPkg the package name of the calling app
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     */
    void downloadMessage(long subId, String callingPkg, String locationUrl,
            in PendingIntent downloadedIntent);

    /**
     * Update the status of a pending (send-by-IP) MMS message handled by the carrier app.
     * If the carrier app fails to send this message, it would be resent via carrier network.
     *
     * @param messageRef the reference number of the MMS message.
     * @param success True if and only if the message was sent successfully. If its value is
     *  false, this message should be resent via carrier network
     */
    void updateMmsSendStatus(int messageRef, boolean success);

    /**
     * Update the status of a pending (download-by-IP) MMS message handled by the carrier app.
     * If the carrier app fails to download this message, it would be re-downloaded via carrier
     * network.
     *
     * @param messageRef the reference number of the MMS message.
     * @param pdu non-empty if downloaded successfully, otherwise, it is empty and the message
     *  will be downloaded via carrier network
     */
    void updateMmsDownloadStatus(int messageRef, in byte[] pdu);

    /**
     * Get carrier-dependent configuration value as boolean. For example, if multipart SMS
     * is supported.
     *
     * @param name the configuration name
     * @param defaultValue the default value if fail to find the name
     */
    boolean getCarrierConfigBoolean(String name, boolean defaultValue);

    /**
     * Get carrier-dependent configuration value as int. For example, the MMS message size limit.
     *
     * @param name the configuration name
     * @param defaultValue the default value if fail to find the name
     */
    int getCarrierConfigInt(String name, int defaultValue);

    /**
     * Get carrier-dependent configuration value as String. For example, extra HTTP headers for
     * MMS request.
     *
     * @param name the configuration name
     * @param defaultValue the default value if fail to find the name
     */
    String getCarrierConfigString(String name, String defaultValue);

    /**
     * Set carrier-dependent configuration value as boolean. For example, if multipart SMS
     * is supported.
     *
     * @param name the configuration name
     * @param value the configuration value
     */
    void setCarrierConfigBoolean(String callingPkg, String name, boolean value);

    /**
     * Set carrier-dependent configuration value as int. For example, the MMS message size limit.
     *
     * @param name the configuration name
     * @param value the configuration value
     */
    void setCarrierConfigInt(String callingPkg, String name, int value);

    /**
     * Set carrier-dependent configuration value as String. For example, extra HTTP headers for
     * MMS request.
     *
     * @param name the configuration name
     * @param value the configuration value
     */
    void setCarrierConfigString(String callingPkg, String name, String value);

    /**
     * Import a text message into system's SMS store
     *
     * @param callingPkg the calling app's package name
     * @param address the destination address of the message
     * @param type the type of the message
     * @param text the message text
     * @param timestampMillis the message timestamp in milliseconds
     * @param seen if the message is seen
     * @param read if the message is read
     * @return the message URI, null if failed
     */
    Uri importTextMessage(String callingPkg, String address, int type, String text,
            long timestampMillis, boolean seen, boolean read);

    /**
      * Import a multimedia message into system's MMS store
      *
      * @param callingPkg the package name of the calling app
      * @param pdu the PDU of the message to import
      * @param messageId the optional message id
      * @param timestampSecs the message timestamp in seconds
      * @param seen if the message is seen
      * @param read if the message is read
      * @return the message URI, null if failed
      */
    Uri importMultimediaMessage(String callingPkg, in byte[] pdu, String messageId,
            long timestampSecs, boolean seen, boolean read);

    /**
     * Delete a system stored SMS or MMS message
     *
     * @param callingPkg the package name of the calling app
     * @param messageUri the URI of the stored message
     * @return true if deletion is successful, false otherwise
     */
    boolean deleteStoredMessage(String callingPkg, in Uri messageUri);

    /**
     * Delete a system stored SMS or MMS thread
     *
     * @param callingPkg the package name of the calling app
     * @param conversationId the ID of the message conversation
     * @return true if deletion is successful, false otherwise
     */
    boolean deleteStoredConversation(String callingPkg, long conversationId);

    /**
     * Update the status properties of a system stored SMS or MMS message, e.g.
     * the read status of a message, etc.
     *
     * @param callingPkg the package name of the calling app
     * @param messageUri the URI of the stored message
     * @param statusValues a list of status properties in key-value pairs to update
     * @return true if deletion is successful, false otherwise
     */
    boolean updateStoredMessageStatus(String callingPkg, in Uri messageUri,
            in ContentValues statusValues);

    /**
     * Add a text message draft to system SMS store
     *
     * @param callingPkg the package name of the calling app
     * @param address the destination address of message
     * @param text the body of the message to send
     * @return the URI of the stored draft message
     */
    Uri addTextMessageDraft(String callingPkg, String address, String text);

    /**
     * Add a multimedia message draft to system MMS store
     *
     * @param callingPkg the package name of the calling app
     * @param pdu the PDU data of the draft MMS
     * @return the URI of the stored draft message
     */
    Uri addMultimediaMessageDraft(String callingPkg, in byte[] pdu);

    /**
     * Send a system stored MMS message
     *
     * This is used for sending a previously sent, but failed-to-send, message or
     * for sending a text message that has been stored as a draft.
     *
     * @param subId the SIM id
     * @param callingPkg the package name of the calling app
     * @param messageUri the URI of the stored message
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     */
    void sendStoredMessage(long subId, String callingPkg, in Uri messageUri,
            in PendingIntent sentIntent);
}
