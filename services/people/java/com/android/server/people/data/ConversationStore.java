/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.LocusId;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.people.ConversationInfosProto;

import com.google.android.collect.Lists;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * The store that stores and accesses the conversations data for a package.
 */
class ConversationStore {

    private static final String TAG = ConversationStore.class.getSimpleName();

    private static final String CONVERSATIONS_FILE_NAME = "conversations";

    private static final int CONVERSATION_INFOS_END_TOKEN = -1;

    // Shortcut ID -> Conversation Info
    @GuardedBy("this")
    private final Map<String, ConversationInfo> mConversationInfoMap = new ArrayMap<>();

    // Locus ID -> Shortcut ID
    @GuardedBy("this")
    private final Map<LocusId, String> mLocusIdToShortcutIdMap = new ArrayMap<>();

    // Contact URI -> Shortcut ID
    @GuardedBy("this")
    private final Map<Uri, String> mContactUriToShortcutIdMap = new ArrayMap<>();

    // Phone Number -> Shortcut ID
    @GuardedBy("this")
    private final Map<String, String> mPhoneNumberToShortcutIdMap = new ArrayMap<>();

    // Notification Channel ID -> Shortcut ID
    @GuardedBy("this")
    private final Map<String, String> mNotifChannelIdToShortcutIdMap = new ArrayMap<>();

    private final ScheduledExecutorService mScheduledExecutorService;
    private final File mPackageDir;

    private ConversationInfosProtoDiskReadWriter mConversationInfosProtoDiskReadWriter;

    ConversationStore(@NonNull File packageDir,
            @NonNull ScheduledExecutorService scheduledExecutorService) {
        mScheduledExecutorService = scheduledExecutorService;
        mPackageDir = packageDir;
    }

    /**
     * Loads conversations from disk to memory in a background thread. This should be called
     * after the device powers on and the user has been unlocked.
     */
    @WorkerThread
    void loadConversationsFromDisk() {
        ConversationInfosProtoDiskReadWriter conversationInfosProtoDiskReadWriter =
                getConversationInfosProtoDiskReadWriter();
        if (conversationInfosProtoDiskReadWriter == null) {
            return;
        }
        List<ConversationInfo> conversationsOnDisk =
                conversationInfosProtoDiskReadWriter.read(CONVERSATIONS_FILE_NAME);
        if (conversationsOnDisk == null) {
            return;
        }
        for (ConversationInfo conversationInfo : conversationsOnDisk) {
            updateConversationsInMemory(conversationInfo);
        }
    }

    /**
     * Immediately flushes current conversations to disk. This should be called when device is
     * powering off.
     */
    @MainThread
    void saveConversationsToDisk() {
        ConversationInfosProtoDiskReadWriter conversationInfosProtoDiskReadWriter =
                getConversationInfosProtoDiskReadWriter();
        if (conversationInfosProtoDiskReadWriter != null) {
            List<ConversationInfo> conversations;
            synchronized (this) {
                conversations = new ArrayList<>(mConversationInfoMap.values());
            }
            conversationInfosProtoDiskReadWriter.saveConversationsImmediately(conversations);
        }
    }

    @MainThread
    void addOrUpdate(@NonNull ConversationInfo conversationInfo) {
        updateConversationsInMemory(conversationInfo);
        scheduleUpdateConversationsOnDisk();
    }

    @MainThread
    @Nullable
    ConversationInfo deleteConversation(@NonNull String shortcutId) {
        ConversationInfo conversationInfo;
        synchronized (this) {
            conversationInfo = mConversationInfoMap.remove(shortcutId);
            if (conversationInfo == null) {
                return null;
            }

            LocusId locusId = conversationInfo.getLocusId();
            if (locusId != null) {
                mLocusIdToShortcutIdMap.remove(locusId);
            }

            Uri contactUri = conversationInfo.getContactUri();
            if (contactUri != null) {
                mContactUriToShortcutIdMap.remove(contactUri);
            }

            String phoneNumber = conversationInfo.getContactPhoneNumber();
            if (phoneNumber != null) {
                mPhoneNumberToShortcutIdMap.remove(phoneNumber);
            }

            String notifChannelId = conversationInfo.getNotificationChannelId();
            if (notifChannelId != null) {
                mNotifChannelIdToShortcutIdMap.remove(notifChannelId);
            }
        }
        scheduleUpdateConversationsOnDisk();
        return conversationInfo;
    }

    void forAllConversations(@NonNull Consumer<ConversationInfo> consumer) {
        List<ConversationInfo> conversations;
        synchronized (this) {
            conversations = new ArrayList<>(mConversationInfoMap.values());
        }
        for (ConversationInfo ci : conversations) {
            consumer.accept(ci);
        }
    }

    @Nullable
    synchronized ConversationInfo getConversation(@Nullable String shortcutId) {
        return shortcutId != null ? mConversationInfoMap.get(shortcutId) : null;
    }

    @Nullable
    synchronized ConversationInfo getConversationByLocusId(@NonNull LocusId locusId) {
        return getConversation(mLocusIdToShortcutIdMap.get(locusId));
    }

    @Nullable
    synchronized ConversationInfo getConversationByContactUri(@NonNull Uri contactUri) {
        return getConversation(mContactUriToShortcutIdMap.get(contactUri));
    }

    @Nullable
    synchronized ConversationInfo getConversationByPhoneNumber(@NonNull String phoneNumber) {
        return getConversation(mPhoneNumberToShortcutIdMap.get(phoneNumber));
    }

    @Nullable
    synchronized ConversationInfo getConversationByNotificationChannelId(
            @NonNull String notifChannelId) {
        return getConversation(mNotifChannelIdToShortcutIdMap.get(notifChannelId));
    }

    void onDestroy() {
        synchronized (this) {
            mConversationInfoMap.clear();
            mContactUriToShortcutIdMap.clear();
            mLocusIdToShortcutIdMap.clear();
            mNotifChannelIdToShortcutIdMap.clear();
            mPhoneNumberToShortcutIdMap.clear();
        }
        ConversationInfosProtoDiskReadWriter writer = getConversationInfosProtoDiskReadWriter();
        if (writer != null) {
            writer.deleteConversationsFile();
        }
    }

    @Nullable
    byte[] getBackupPayload() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream conversationInfosOut = new DataOutputStream(baos);
        forAllConversations(conversationInfo -> {
            byte[] backupPayload = conversationInfo.getBackupPayload();
            if (backupPayload == null) {
                return;
            }
            try {
                conversationInfosOut.writeInt(backupPayload.length);
                conversationInfosOut.write(backupPayload);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write conversation info to backup payload.", e);
            }
        });
        try {
            conversationInfosOut.writeInt(CONVERSATION_INFOS_END_TOKEN);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write conversation infos end token to backup payload.", e);
            return null;
        }
        return baos.toByteArray();
    }

    void restore(@NonNull byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            for (int conversationInfoSize = in.readInt();
                    conversationInfoSize != CONVERSATION_INFOS_END_TOKEN;
                    conversationInfoSize = in.readInt()) {
                byte[] conversationInfoPayload = new byte[conversationInfoSize];
                in.readFully(conversationInfoPayload, 0, conversationInfoSize);
                ConversationInfo conversationInfo = ConversationInfo.readFromBackupPayload(
                        conversationInfoPayload);
                if (conversationInfo != null) {
                    addOrUpdate(conversationInfo);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read conversation info from payload.", e);
        }
    }

    private synchronized void updateConversationsInMemory(
            @NonNull ConversationInfo conversationInfo) {
        mConversationInfoMap.put(conversationInfo.getShortcutId(), conversationInfo);

        LocusId locusId = conversationInfo.getLocusId();
        if (locusId != null) {
            mLocusIdToShortcutIdMap.put(locusId, conversationInfo.getShortcutId());
        }

        Uri contactUri = conversationInfo.getContactUri();
        if (contactUri != null) {
            mContactUriToShortcutIdMap.put(contactUri, conversationInfo.getShortcutId());
        }

        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (phoneNumber != null) {
            mPhoneNumberToShortcutIdMap.put(phoneNumber, conversationInfo.getShortcutId());
        }

        String notifChannelId = conversationInfo.getNotificationChannelId();
        if (notifChannelId != null) {
            mNotifChannelIdToShortcutIdMap.put(notifChannelId, conversationInfo.getShortcutId());
        }
    }

    /** Schedules a dump of all conversations onto disk, overwriting existing values. */
    @MainThread
    private void scheduleUpdateConversationsOnDisk() {
        ConversationInfosProtoDiskReadWriter conversationInfosProtoDiskReadWriter =
                getConversationInfosProtoDiskReadWriter();
        if (conversationInfosProtoDiskReadWriter != null) {
            List<ConversationInfo> conversations;
            synchronized (this) {
                conversations = new ArrayList<>(mConversationInfoMap.values());
            }
            conversationInfosProtoDiskReadWriter.scheduleConversationsSave(conversations);
        }
    }

    @Nullable
    private ConversationInfosProtoDiskReadWriter getConversationInfosProtoDiskReadWriter() {
        if (!mPackageDir.exists()) {
            Slog.e(TAG, "Package data directory does not exist: " + mPackageDir.getAbsolutePath());
            return null;
        }
        if (mConversationInfosProtoDiskReadWriter == null) {
            mConversationInfosProtoDiskReadWriter = new ConversationInfosProtoDiskReadWriter(
                    mPackageDir, CONVERSATIONS_FILE_NAME, mScheduledExecutorService);
        }
        return mConversationInfosProtoDiskReadWriter;
    }

    /** Reads and writes {@link ConversationInfo}s on disk. */
    private static class ConversationInfosProtoDiskReadWriter extends
            AbstractProtoDiskReadWriter<List<ConversationInfo>> {

        private final String mConversationInfoFileName;

        ConversationInfosProtoDiskReadWriter(@NonNull File rootDir,
                @NonNull String conversationInfoFileName,
                @NonNull ScheduledExecutorService scheduledExecutorService) {
            super(rootDir, scheduledExecutorService);
            mConversationInfoFileName = conversationInfoFileName;
        }

        @Override
        ProtoStreamWriter<List<ConversationInfo>> protoStreamWriter() {
            return (protoOutputStream, data) -> {
                for (ConversationInfo conversationInfo : data) {
                    long token = protoOutputStream.start(ConversationInfosProto.CONVERSATION_INFOS);
                    conversationInfo.writeToProto(protoOutputStream);
                    protoOutputStream.end(token);
                }
            };
        }

        @Override
        ProtoStreamReader<List<ConversationInfo>> protoStreamReader() {
            return protoInputStream -> {
                List<ConversationInfo> results = Lists.newArrayList();
                try {
                    while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                        if (protoInputStream.getFieldNumber()
                                != (int) ConversationInfosProto.CONVERSATION_INFOS) {
                            continue;
                        }
                        long token = protoInputStream.start(
                                ConversationInfosProto.CONVERSATION_INFOS);
                        ConversationInfo conversationInfo = ConversationInfo.readFromProto(
                                protoInputStream);
                        protoInputStream.end(token);
                        results.add(conversationInfo);
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read protobuf input stream.", e);
                }
                return results;
            };
        }

        /**
         * Schedules a flush of the specified conversations to disk.
         */
        @MainThread
        void scheduleConversationsSave(@NonNull List<ConversationInfo> conversationInfos) {
            scheduleSave(mConversationInfoFileName, conversationInfos);
        }

        /**
         * Saves the specified conversations immediately. This should be used when device is
         * powering off.
         */
        @MainThread
        void saveConversationsImmediately(@NonNull List<ConversationInfo> conversationInfos) {
            saveImmediately(mConversationInfoFileName, conversationInfos);
        }

        @WorkerThread
        void deleteConversationsFile() {
            delete(mConversationInfoFileName);
        }
    }
}
