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
import android.os.Bundle;

/**
 * Service interface to handle MMS API requests
 */
interface IMms {
    /**
     * Send an MMS message with attribution tag.
     *
     * @param subId the SIM id
     * @param callingUser user id of the calling app
     * @param callingPkg the package name of the calling app
     * @param contentUri the content uri from which to read MMS message encoded in standard MMS
     *  PDU format
     * @param locationUrl the optional location url for where this message should be sent to
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  sending the message. See {@link android.telephony.SmsManager} for the value names and types.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     * @param messageId An id that uniquely identifies the message requested to be sent.
     * @param attributionTag a tag that attributes the call to a client App.
     */
    void sendMessage(int subId, in int callingUser, String callingPkg, in Uri contentUri,
            String locationUrl, in Bundle configOverrides, in PendingIntent sentIntent,
            in long messageId, String attributionTag);

    /**
     * Download an MMS message using known location and transaction id
     *
     * @param subId the SIM id
     * @param callingUser user id of the calling app
     * @param callingPkg the package name of the calling app
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param contentUri a contentUri to which the downloaded MMS message will be written
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  downloading the message. See {@link android.telephony.SmsManager} for the value names and
     *  types.
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     * @param messageId An id that uniquely identifies the message requested to be downloaded.
     * @param attributionTag a tag that attributes the call to a client App.
    */
    void downloadMessage(int subId, in int callingUser, String callingPkg, String locationUrl,
            in Uri contentUri, in Bundle configOverrides,
            in PendingIntent downloadedIntent, in long messageId, String attributionTag);

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
     * @param callingUser user id of the calling app
      * @param callingPkg the package name of the calling app
      * @param contentUri the content uri from which to read PDU of the message to import
      * @param messageId the optional message id
      * @param timestampSecs the message timestamp in seconds
      * @param seen if the message is seen
      * @param read if the message is read
      * @return the message URI, null if failed
      */
    Uri importMultimediaMessage(in int callingUser, String callingPkg, in Uri contentUri, String messageId,
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
     * Archive or unarchive a stored conversation
     *
     * @param callingPkg the package name of the calling app
     * @param conversationId the ID of the message conversation
     * @param archived true to archive the conversation, false otherwise
     * @return true if update is successful, false otherwise
     */
    boolean archiveStoredConversation(String callingPkg, long conversationId, boolean archived);

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
     * @param callingUser user id of the calling app
     * @param callingPkg the package name of the calling app
     * @param contentUri the content Uri from which to read PDU data of the draft MMS
     * @return the URI of the stored draft message
     */
    Uri addMultimediaMessageDraft(in int callingUser, String callingPkg, in Uri contentUri);

    /**
     * Send a system stored MMS message
     *
     * This is used for sending a previously sent, but failed-to-send, message or
     * for sending a text message that has been stored as a draft.
     *
     * @param subId the SIM id
     * @param callingPkg the package name of the calling app
     * @param messageUri the URI of the stored message
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  sending the message. See {@link android.telephony.SmsManager} for the value names and types.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     */
    void sendStoredMessage(int subId, String callingPkg, in Uri messageUri,
            in Bundle configOverrides, in PendingIntent sentIntent);

    /**
     * Turns on/off the flag to automatically write sent/received SMS/MMS messages into system
     *
     * When this flag is on, all SMS/MMS sent/received are stored by system automatically
     * When this flag is off, only SMS/MMS sent by non-default SMS apps are stored by system
     * automatically
     *
     * This flag can only be changed by default SMS apps
     *
     * @param callingPkg the name of the calling app package
     * @param enabled Whether to enable message auto persisting
     */
    void setAutoPersisting(String callingPkg, boolean enabled);

    /**
     * Get the value of the flag to automatically write sent/received SMS/MMS messages into system
     *
     * When this flag is on, all SMS/MMS sent/received are stored by system automatically
     * When this flag is off, only SMS/MMS sent by non-default SMS apps are stored by system
     * automatically
     *
     * @return the current value of the auto persist flag
     */
    boolean getAutoPersisting();
}
