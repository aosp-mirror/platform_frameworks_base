/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.telephony.ims.RcsEventQueryParams;
import android.telephony.ims.RcsEventQueryResultDescriptor;
import android.telephony.ims.RcsFileTransferCreationParams;
import android.telephony.ims.RcsIncomingMessageCreationParams;
import android.telephony.ims.RcsMessageSnippet;
import android.telephony.ims.RcsMessageQueryParams;
import android.telephony.ims.RcsMessageQueryResultParcelable;
import android.telephony.ims.RcsOutgoingMessageCreationParams;
import android.telephony.ims.RcsParticipantQueryParams;
import android.telephony.ims.RcsParticipantQueryResultParcelable;
import android.telephony.ims.RcsQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParams;
import android.telephony.ims.RcsThreadQueryResultParcelable;

/**
 * RPC definition between RCS storage APIs and phone process.
 * {@hide}
 */
interface IRcs {
    /////////////////////////
    // RcsMessageStore APIs
    /////////////////////////
    RcsThreadQueryResultParcelable getRcsThreads(in RcsThreadQueryParams queryParams, String callingPackage);

    RcsThreadQueryResultParcelable getRcsThreadsWithToken(
        in RcsQueryContinuationToken continuationToken, String callingPackage);

    RcsParticipantQueryResultParcelable getParticipants(in RcsParticipantQueryParams queryParams, String callingPackage);

    RcsParticipantQueryResultParcelable getParticipantsWithToken(
        in RcsQueryContinuationToken continuationToken, String callingPackage);

    RcsMessageQueryResultParcelable getMessages(in RcsMessageQueryParams queryParams, String callingPackage);

    RcsMessageQueryResultParcelable getMessagesWithToken(
        in RcsQueryContinuationToken continuationToken, String callingPackage);

    RcsEventQueryResultDescriptor getEvents(in RcsEventQueryParams queryParams, String callingPackage);

    RcsEventQueryResultDescriptor getEventsWithToken(
        in RcsQueryContinuationToken continuationToken, String callingPackage);

    // returns true if the thread was successfully deleted
    boolean deleteThread(int threadId, int threadType, String callingPackage);

    // Creates an Rcs1To1Thread and returns its row ID
    int createRcs1To1Thread(int participantId, String callingPackage);

    // Creates an RcsGroupThread and returns its row ID
    int createGroupThread(in int[] participantIds, String groupName, in Uri groupIcon, String callingPackage);

    /////////////////////////
    // RcsThread APIs
    /////////////////////////

    // Creates a new RcsIncomingMessage on the given thread and returns its row ID
    int addIncomingMessage(int rcsThreadId,
            in RcsIncomingMessageCreationParams rcsIncomingMessageCreationParams, String callingPackage);

    // Creates a new RcsOutgoingMessage on the given thread and returns its row ID
    int addOutgoingMessage(int rcsThreadId,
            in RcsOutgoingMessageCreationParams rcsOutgoingMessageCreationParams, String callingPackage);

    // TODO: modify RcsProvider URI's to allow deleting a message without specifying its thread
    void deleteMessage(int rcsMessageId, boolean isIncoming, int rcsThreadId, boolean isGroup, String callingPackage);

    RcsMessageSnippet getMessageSnippet(int rcsThreadId, String callingPackage);

    /////////////////////////
    // Rcs1To1Thread APIs
    /////////////////////////
    void set1To1ThreadFallbackThreadId(int rcsThreadId, long fallbackId, String callingPackage);

    long get1To1ThreadFallbackThreadId(int rcsThreadId, String callingPackage);

    int get1To1ThreadOtherParticipantId(int rcsThreadId, String callingPackage);

    /////////////////////////
    // RcsGroupThread APIs
    /////////////////////////
    void setGroupThreadName(int rcsThreadId, String groupName, String callingPackage);

    String getGroupThreadName(int rcsThreadId, String callingPackage);

    void setGroupThreadIcon(int rcsThreadId, in Uri groupIcon, String callingPackage);

    Uri getGroupThreadIcon(int rcsThreadId, String callingPackage);

    void setGroupThreadOwner(int rcsThreadId, int participantId, String callingPackage);

    int getGroupThreadOwner(int rcsThreadId, String callingPackage);

    void setGroupThreadConferenceUri(int rcsThreadId, in Uri conferenceUri, String callingPackage);

    Uri getGroupThreadConferenceUri(int rcsThreadId, String callingPackage);

    void addParticipantToGroupThread(int rcsThreadId, int participantId, String callingPackage);

    void removeParticipantFromGroupThread(int rcsThreadId, int participantId, String callingPackage);

    /////////////////////////
    // RcsParticipant APIs
    /////////////////////////

    // Creates a new RcsParticipant and returns its rowId
    int createRcsParticipant(String canonicalAddress, String alias, String callingPackage);

    String getRcsParticipantCanonicalAddress(int participantId, String callingPackage);

    String getRcsParticipantAlias(int participantId, String callingPackage);

    void setRcsParticipantAlias(int id, String alias, String callingPackage);

    String getRcsParticipantContactId(int participantId, String callingPackage);

    void setRcsParticipantContactId(int participantId, String contactId, String callingPackage);

    /////////////////////////
    // RcsMessage APIs
    /////////////////////////
    void setMessageSubId(int messageId, boolean isIncoming, int subId, String callingPackage);

    int getMessageSubId(int messageId, boolean isIncoming, String callingPackage);

    void setMessageStatus(int messageId, boolean isIncoming, int status, String callingPackage);

    int getMessageStatus(int messageId, boolean isIncoming, String callingPackage);

    void setMessageOriginationTimestamp(int messageId, boolean isIncoming, long originationTimestamp, String callingPackage);

    long getMessageOriginationTimestamp(int messageId, boolean isIncoming, String callingPackage);

    void setGlobalMessageIdForMessage(int messageId, boolean isIncoming, String globalId, String callingPackage);

    String getGlobalMessageIdForMessage(int messageId, boolean isIncoming, String callingPackage);

    void setMessageArrivalTimestamp(int messageId, boolean isIncoming, long arrivalTimestamp, String callingPackage);

    long getMessageArrivalTimestamp(int messageId, boolean isIncoming, String callingPackage);

    void setMessageSeenTimestamp(int messageId, boolean isIncoming, long seenTimestamp, String callingPackage);

    long getMessageSeenTimestamp(int messageId, boolean isIncoming, String callingPackage);

    void setTextForMessage(int messageId, boolean isIncoming, String text, String callingPackage);

    String getTextForMessage(int messageId, boolean isIncoming, String callingPackage);

    void setLatitudeForMessage(int messageId, boolean isIncoming, double latitude, String callingPackage);

    double getLatitudeForMessage(int messageId, boolean isIncoming, String callingPackage);

    void setLongitudeForMessage(int messageId, boolean isIncoming, double longitude, String callingPackage);

    double getLongitudeForMessage(int messageId, boolean isIncoming, String callingPackage);

    // Returns the ID's of the file transfers attached to the given message
    int[] getFileTransfersAttachedToMessage(int messageId, boolean isIncoming, String callingPackage);

    int getSenderParticipant(int messageId, String callingPackage);

    /////////////////////////
    // RcsOutgoingMessageDelivery APIs
    /////////////////////////

    // Returns the participant ID's that this message is intended to be delivered to
    int[] getMessageRecipients(int messageId, String callingPackage);

    long getOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId, String callingPackage);

    void setOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId, long deliveredTimestamp, String callingPackage);

    long getOutgoingDeliverySeenTimestamp(int messageId, int participantId, String callingPackage);

    void setOutgoingDeliverySeenTimestamp(int messageId, int participantId, long seenTimestamp, String callingPackage);

    int getOutgoingDeliveryStatus(int messageId, int participantId, String callingPackage);

    void setOutgoingDeliveryStatus(int messageId, int participantId, int status, String callingPackage);

    /////////////////////////
    // RcsFileTransferPart APIs
    /////////////////////////

    // Performs the initial write to storage and returns the row ID.
    int storeFileTransfer(int messageId, boolean isIncoming,
            in RcsFileTransferCreationParams fileTransferCreationParams, String callingPackage);

    void deleteFileTransfer(int partId, String callingPackage);

    void setFileTransferSessionId(int partId, String sessionId, String callingPackage);

    String getFileTransferSessionId(int partId, String callingPackage);

    void setFileTransferContentUri(int partId, in Uri contentUri, String callingPackage);

    Uri getFileTransferContentUri(int partId, String callingPackage);

    void setFileTransferContentType(int partId, String contentType, String callingPackage);

    String getFileTransferContentType(int partId, String callingPackage);

    void setFileTransferFileSize(int partId, long fileSize, String callingPackage);

    long getFileTransferFileSize(int partId, String callingPackage);

    void setFileTransferTransferOffset(int partId, long transferOffset, String callingPackage);

    long getFileTransferTransferOffset(int partId, String callingPackage);

    void setFileTransferStatus(int partId, int transferStatus, String callingPackage);

    int getFileTransferStatus(int partId, String callingPackage);

    void setFileTransferWidth(int partId, int width, String callingPackage);

    int getFileTransferWidth(int partId, String callingPackage);

    void setFileTransferHeight(int partId, int height, String callingPackage);

    int getFileTransferHeight(int partId, String callingPackage);

    void setFileTransferLength(int partId, long length, String callingPackage);

    long getFileTransferLength(int partId, String callingPackage);

    void setFileTransferPreviewUri(int partId, in Uri uri, String callingPackage);

    Uri getFileTransferPreviewUri(int partId, String callingPackage);

    void setFileTransferPreviewType(int partId, String type, String callingPackage);

    String getFileTransferPreviewType(int partId, String callingPackage);

    /////////////////////////
    // RcsEvent APIs
    /////////////////////////
    int createGroupThreadNameChangedEvent(long timestamp, int threadId, int originationParticipantId, String newName, String callingPackage);

    int createGroupThreadIconChangedEvent(long timestamp, int threadId, int originationParticipantId, in Uri newIcon, String callingPackage);

    int createGroupThreadParticipantJoinedEvent(long timestamp, int threadId, int originationParticipantId, int participantId, String callingPackage);

    int createGroupThreadParticipantLeftEvent(long timestamp, int threadId, int originationParticipantId, int participantId, String callingPackage);

    int createParticipantAliasChangedEvent(long timestamp, int participantId, String newAlias, String callingPackage);
}