/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.server.companion.proto.CompanionMessage;
import com.android.server.companion.securechannel.CompanionSecureCommunicationsManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class builds and reads CompanionMessage. And also paginate and combine messages.
 */
public class CompanionMessageProcessor {

    private static final String LOG_TAG = CompanionMessageProcessor.class.getSimpleName();

    /** Listener for incoming complete messages. */
    interface Listener {
        /** When a complete message is received from the companion app. */
        void onCompleteMessageReceived(@NonNull CompanionMessageInfo message);
    }

    // Rough size for each CompanionMessage, each message can exceed 50K for a little, but not
    // too much. Hard limit is 100K, WCS data processing limit. Closer to 100K, less stable at
    // the WCS data processing layer. Refer to
    // https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient
    // #public-abstract-taskinteger-sendmessage-string-nodeid,-string-path,-byte[]-data
    private static final int MESSAGE_SIZE_IN_BYTES = 50000;

    private final CompanionSecureCommunicationsManager mSecureCommsManager;

    @Nullable
    private Listener mListener;

    // Association id -> (parent id -> received messages)
    private final Map<Integer, Map<Integer, List<CompanionMessageInfo>>> mAssociationsMessagesMap =
            new HashMap<>();
    // Association id -> next parent id
    private final Map<Integer, Integer> mNextParentId = new HashMap<>();

    public CompanionMessageProcessor(CompanionSecureCommunicationsManager secureCommsManager) {
        mSecureCommsManager = secureCommsManager;
        mSecureCommsManager.setListener(this::onDecryptedMessageReceived);
    }

    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * Paginate the data into multiple messages with size limit. And dispatch the messages to the
     * companion app.
     */
    public void paginateAndDispatchMessagesToApp(byte[] data, int messageType,
            String packageName, int userId, int associationId) {
        Slog.i(LOG_TAG, "Paginating " + data.length + " bytes.");

        final int totalMessageCount = (data.length / MESSAGE_SIZE_IN_BYTES)
                + ((data.length % MESSAGE_SIZE_IN_BYTES == 0) ? 0 : 1);
        int parentMessageId = findNextParentId(associationId, totalMessageCount);

        for (int i = 0; i < totalMessageCount; i++) {
            ProtoOutputStream proto = new ProtoOutputStream();
            int messageId = parentMessageId + i + 1;
            proto.write(CompanionMessage.ID, messageId);

            long paginationInfoToken = proto.start(CompanionMessage.PAGINATION_INFO);
            proto.write(CompanionMessage.PaginationInfo.PARENT_ID, parentMessageId);
            proto.write(CompanionMessage.PaginationInfo.PAGE, i + 1);
            proto.write(CompanionMessage.PaginationInfo.TOTAL, totalMessageCount);
            proto.end(paginationInfoToken);

            proto.write(CompanionMessage.TYPE, messageType);
            byte[] currentData = Arrays.copyOfRange(data, i * MESSAGE_SIZE_IN_BYTES,
                    Math.min((i + 1) * MESSAGE_SIZE_IN_BYTES, data.length));
            proto.write(CompanionMessage.DATA, currentData);

            byte[] message = proto.getBytes();

            Slog.i(LOG_TAG, "Sending [" + message.length + "] bytes to " + packageName);

            mSecureCommsManager.sendSecureMessage(associationId, messageId, message);
        }
    }

    /**
     * Process the message and store it. If all the messages with the same parent id have been
     * received, return the message with combined message data. Otherwise, return null if there's
     * still data parts missing.
     */
    public CompanionMessageInfo onDecryptedMessageReceived(int messageId, int associationId,
            byte[] message) {
        Slog.i(LOG_TAG, "Partial message received, size [" + message.length
                + "], reading from protobuf.");

        ProtoInputStream proto = new ProtoInputStream(message);
        try {
            int id = 0;
            int parentId = 0;
            int page = 0;
            int total = 0;
            int type = CompanionMessage.UNKNOWN;
            byte[] data = null;

            // Read proto data
            while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (proto.getFieldNumber()) {
                    case (int) CompanionMessage.ID:
                        id = proto.readInt(CompanionMessage.ID);
                        break;
                    case (int) CompanionMessage.PAGINATION_INFO:
                        long paginationToken = proto.start(CompanionMessage.PAGINATION_INFO);
                        while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                            switch (proto.getFieldNumber()) {
                                case (int) CompanionMessage.PaginationInfo.PARENT_ID:
                                    parentId = proto.readInt(
                                            CompanionMessage.PaginationInfo.PARENT_ID);
                                    break;
                                case (int) CompanionMessage.PaginationInfo.PAGE:
                                    page = proto.readInt(CompanionMessage.PaginationInfo.PAGE);
                                    break;
                                case (int) CompanionMessage.PaginationInfo.TOTAL:
                                    total = proto.readInt(CompanionMessage.PaginationInfo.TOTAL);
                                    break;
                                default:
                                    Slog.e(LOG_TAG, "Unexpected field id "
                                            + proto.getFieldNumber() + " for PaginationInfo.");
                                    break;
                            }
                        }
                        proto.end(paginationToken);
                        break;
                    case (int) CompanionMessage.TYPE:
                        type = proto.readInt(CompanionMessage.TYPE);
                        break;
                    case (int) CompanionMessage.DATA:
                        data = proto.readBytes(CompanionMessage.DATA);
                        break;
                    default:
                        Slog.e(LOG_TAG, "Unexpected field id " + proto.getFieldNumber()
                                + " for CompanionMessage.");
                        break;
                }
            }

            if (id == messageId) {
                CompanionMessageInfo messageInfo = new CompanionMessageInfo(id, page, total, type,
                        data);
                // Add the message into mAssociationsMessagesMap
                Map<Integer, List<CompanionMessageInfo>> associationMessages =
                        mAssociationsMessagesMap.getOrDefault(associationId, new HashMap<>());
                List<CompanionMessageInfo> childMessages = associationMessages.getOrDefault(
                        parentId, new ArrayList<>());
                childMessages.add(messageInfo);
                associationMessages.put(parentId, childMessages);
                mAssociationsMessagesMap.put(associationId, associationMessages);
                // Check if all the messages with the same parentId are received.
                if (childMessages.size() == total) {
                    Slog.i(LOG_TAG, "All [" + total + "] messages are received for parentId ["
                            + parentId + "]. Processing.");

                    childMessages.sort(Comparator.comparing(CompanionMessageInfo::getPage));
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    for (int i = 0; i < childMessages.size(); i++) {
                        stream.write(childMessages.get(i).getData());
                    }
                    mAssociationsMessagesMap.remove(parentId);
                    mListener.onCompleteMessageReceived(
                            new CompanionMessageInfo(parentId, 0, total, type,
                                    stream.toByteArray()));
                } else {
                    Slog.i(LOG_TAG, "[" + childMessages.size() + "/" + total
                            + "] messages are received for parentId [" + parentId + "]");
                }
            } else {
                Slog.e(LOG_TAG, "Message id mismatch.");
                return null;
            }
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Can't read proto from the message.");
            return null;
        }
        return null;
    }

    /**
     * Find the next parent id from [1, Integer.MAX_VALUE].
     * The parent and child ids are incremental.
     */
    private int findNextParentId(int associationId, int totalMessageCount) {
        int nextParentId = mNextParentId.getOrDefault(associationId, 1);

        // If the last child message id exceeds the Integer range, start from 1 again.
        if (nextParentId > Integer.MAX_VALUE - totalMessageCount - 1) {
            nextParentId = 1;
        }

        mNextParentId.put(associationId, nextParentId + totalMessageCount + 1);

        return nextParentId;
    }
}
