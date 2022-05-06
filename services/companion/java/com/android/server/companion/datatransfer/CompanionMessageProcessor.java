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

    // Rough size for each CompanionMessage, each message can exceed 50K for a little, but not
    // too much. Hard limit is 100K, WCS data processing limit. Closer to 100K, less stable at
    // the WCS data processing layer. Refer to
    // https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient
    // #public-abstract-taskinteger-sendmessage-string-nodeid,-string-path,-byte[]-data
    private static final int MESSAGE_SIZE_IN_BYTES = 50000;

    private final CompanionSecureCommunicationsManager mSecureCommsManager;

    // Association id -> (parent id -> received messages)
    private final Map<Integer, Map<Integer, List<CompanionMessageInfo>>> mAssociationsMessagesMap =
            new HashMap<>();
    // Association id -> next parent id
    private final Map<Integer, Integer> mNextParentId = new HashMap<>();

    public CompanionMessageProcessor(CompanionSecureCommunicationsManager secureCommsManager) {
        mSecureCommsManager = secureCommsManager;
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
            proto.write(CompanionMessage.ID, parentMessageId + i + 1);

            long paginationInfoToken = proto.start(CompanionMessage.PAGINATION_INFO);
            proto.write(CompanionMessage.PaginationInfo.PARENT_ID, parentMessageId);
            proto.write(CompanionMessage.PaginationInfo.PAGE, i + 1);
            proto.write(CompanionMessage.PaginationInfo.TOTAL, totalMessageCount);
            proto.end(paginationInfoToken);

            proto.write(CompanionMessage.TYPE, messageType);
            byte[] currentData = Arrays.copyOfRange(data, i * MESSAGE_SIZE_IN_BYTES,
                    Math.min((i + 1) * MESSAGE_SIZE_IN_BYTES, data.length));
            proto.write(CompanionMessage.DATA, currentData);

            Slog.i(LOG_TAG, "Sending " + currentData.length + " bytes to " + packageName);

            mSecureCommsManager.sendSecureMessage(associationId, proto.getBytes());
        }
    }

    /**
     * Process message and store it. If all the messages with the same parent id have been received,
     * return the message with combined message data. Otherwise, return null if there's still data
     * parts missing.
     */
    public CompanionMessageInfo processMessage(int messageId, int associationId, byte[] message) {
        ProtoInputStream proto = new ProtoInputStream(message);
        try {
            int id = proto.readInt(CompanionMessage.ID);
            if (id == messageId) {
                // Read proto data
                long paginationToken = proto.start(CompanionMessage.PAGINATION_INFO);
                int parentId = proto.readInt(CompanionMessage.PaginationInfo.PARENT_ID);
                int page = proto.readInt(CompanionMessage.PaginationInfo.PAGE);
                int total = proto.readInt(CompanionMessage.PaginationInfo.TOTAL);
                proto.end(paginationToken);
                int type = proto.readInt(CompanionMessage.TYPE);
                byte[] data = proto.readBytes(CompanionMessage.DATA);

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
                    childMessages.sort(Comparator.comparing(CompanionMessageInfo::getPage));
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    for (int i = 0; i < childMessages.size(); i++) {
                        stream.write(childMessages.get(i).getData());
                    }
                    mAssociationsMessagesMap.remove(parentId);
                    return new CompanionMessageInfo(parentId, 0, total, type, stream.toByteArray());
                }
            } else {
                Slog.e(LOG_TAG, "Message id mismatch.");
                return null;
            }
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Can't read proto message id: " + messageId + ", message: "
                    + new String(message) + ".");
            return null;
        }
        return null;
    }

    /**
     * Find the next parent id. The parent and child ids are incremental.
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
