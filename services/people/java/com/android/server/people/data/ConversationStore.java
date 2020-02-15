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
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.people.ConversationInfosProto;

import com.google.android.collect.Lists;

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

    private static final long DISK_WRITE_DELAY = 2L * DateUtils.MINUTE_IN_MILLIS;

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
    private final ContactsQueryHelper mHelper;

    private ConversationInfosProtoDiskReadWriter mConversationInfosProtoDiskReadWriter;

    ConversationStore(@NonNull File packageDir,
            @NonNull ScheduledExecutorService scheduledExecutorService,
            @NonNull ContactsQueryHelper helper) {
        mScheduledExecutorService = scheduledExecutorService;
        mPackageDir = packageDir;
        mHelper = helper;
    }

    /**
     * Loads conversations from disk to memory in a background thread. This should be called
     * after the device powers on and the user has been unlocked.
     */
    @MainThread
    void loadConversationsFromDisk() {
        mScheduledExecutorService.submit(() -> {
            synchronized (this) {
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
                    conversationInfo = restoreConversationPhoneNumber(conversationInfo);
                    updateConversationsInMemory(conversationInfo);
                }
            }
        });
    }

    /**
     * Immediately flushes current conversations to disk. This should be called when device is
     * powering off.
     */
    @MainThread
    synchronized void saveConversationsToDisk() {
        ConversationInfosProtoDiskReadWriter conversationInfosProtoDiskReadWriter =
                getConversationInfosProtoDiskReadWriter();
        if (conversationInfosProtoDiskReadWriter != null) {
            conversationInfosProtoDiskReadWriter.saveConversationsImmediately(
                    new ArrayList<>(mConversationInfoMap.values()));
        }
    }

    @MainThread
    synchronized void addOrUpdate(@NonNull ConversationInfo conversationInfo) {
        updateConversationsInMemory(conversationInfo);
        scheduleUpdateConversationsOnDisk();
    }

    @MainThread
    @Nullable
    synchronized ConversationInfo deleteConversation(@NonNull String shortcutId) {
        ConversationInfo conversationInfo = mConversationInfoMap.remove(shortcutId);
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
        scheduleUpdateConversationsOnDisk();
        return conversationInfo;
    }

    synchronized void forAllConversations(@NonNull Consumer<ConversationInfo> consumer) {
        for (ConversationInfo ci : mConversationInfoMap.values()) {
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
    ConversationInfo getConversationByNotificationChannelId(@NonNull String notifChannelId) {
        return getConversation(mNotifChannelIdToShortcutIdMap.get(notifChannelId));
    }

    @MainThread
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
    private synchronized void scheduleUpdateConversationsOnDisk() {
        ConversationInfosProtoDiskReadWriter conversationInfosProtoDiskReadWriter =
                getConversationInfosProtoDiskReadWriter();
        if (conversationInfosProtoDiskReadWriter != null) {
            conversationInfosProtoDiskReadWriter.scheduleConversationsSave(
                    new ArrayList<>(mConversationInfoMap.values()));
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
                    mPackageDir, CONVERSATIONS_FILE_NAME, DISK_WRITE_DELAY,
                    mScheduledExecutorService);
        }
        return mConversationInfosProtoDiskReadWriter;
    }

    /**
     * Conversation's phone number is not saved on disk, so it has to be fetched.
     */
    @WorkerThread
    private ConversationInfo restoreConversationPhoneNumber(
            @NonNull ConversationInfo conversationInfo) {
        if (conversationInfo.getContactUri() != null) {
            if (mHelper.query(conversationInfo.getContactUri().toString())) {
                String phoneNumber = mHelper.getPhoneNumber();
                if (!TextUtils.isEmpty(phoneNumber)) {
                    conversationInfo = new ConversationInfo.Builder(
                            conversationInfo).setContactPhoneNumber(
                            phoneNumber).build();
                }
            }
        }
        return conversationInfo;
    }

    /** Reads and writes {@link ConversationInfo} on disk. */
    static class ConversationInfosProtoDiskReadWriter extends
            AbstractProtoDiskReadWriter<List<ConversationInfo>> {

        private final String mConversationInfoFileName;

        ConversationInfosProtoDiskReadWriter(@NonNull File baseDir,
                @NonNull String conversationInfoFileName,
                long writeDelayMs, @NonNull ScheduledExecutorService scheduledExecutorService) {
            super(baseDir, writeDelayMs, scheduledExecutorService);
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
    }
}
