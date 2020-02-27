/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.notification;

import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.server.notification.NotificationHistoryProto.Notification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Notification history reader/writer for Protocol Buffer format
 */
final class NotificationHistoryProtoHelper {
    private static final String TAG = "NotifHistoryProto";

    // Static-only utility class.
    private NotificationHistoryProtoHelper() {}

    private static List<String> readStringPool(ProtoInputStream proto) throws IOException {
        final long token = proto.start(NotificationHistoryProto.STRING_POOL);
        List<String> stringPool;
        if (proto.nextField(NotificationHistoryProto.StringPool.SIZE)) {
            stringPool = new ArrayList(proto.readInt(NotificationHistoryProto.StringPool.SIZE));
        } else {
            stringPool = new ArrayList();
        }
        while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (proto.getFieldNumber()) {
                case (int) NotificationHistoryProto.StringPool.STRINGS:
                    stringPool.add(proto.readString(NotificationHistoryProto.StringPool.STRINGS));
                    break;
            }
        }
        proto.end(token);
        return stringPool;
    }

    private static void writeStringPool(ProtoOutputStream proto,
            final NotificationHistory notifications) {
        final long token = proto.start(NotificationHistoryProto.STRING_POOL);
        final String[] pooledStrings = notifications.getPooledStringsToWrite();
        proto.write(NotificationHistoryProto.StringPool.SIZE, pooledStrings.length);
        for (int i = 0; i < pooledStrings.length; i++) {
            proto.write(NotificationHistoryProto.StringPool.STRINGS, pooledStrings[i]);
        }
        proto.end(token);
    }

    private static void readNotification(ProtoInputStream proto, List<String> stringPool,
            NotificationHistory notifications, NotificationHistoryFilter filter)
            throws IOException {
        final long token = proto.start(NotificationHistoryProto.NOTIFICATION);
        try {
            HistoricalNotification notification = readNotification(proto, stringPool);
            if (filter.matchesPackageAndChannelFilter(notification)
                    && filter.matchesCountFilter(notifications)) {
                notifications.addNotificationToWrite(notification);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading notification", e);
        } finally {
            proto.end(token);
        }
    }

    private static HistoricalNotification readNotification(ProtoInputStream parser,
            List<String> stringPool) throws IOException {
        final HistoricalNotification.Builder notification = new HistoricalNotification.Builder();
        String pkg = null;
        while (true) {
            switch (parser.nextField()) {
                case (int) NotificationHistoryProto.Notification.PACKAGE:
                    pkg = parser.readString(Notification.PACKAGE);
                    notification.setPackage(pkg);
                    stringPool.add(pkg);
                    break;
                case (int) Notification.PACKAGE_INDEX:
                    pkg = stringPool.get(parser.readInt(Notification.PACKAGE_INDEX) - 1);
                    notification.setPackage(pkg);
                    break;
                case (int) Notification.CHANNEL_NAME:
                    String channelName = parser.readString(Notification.CHANNEL_NAME);
                    notification.setChannelName(channelName);
                    stringPool.add(channelName);
                    break;
                case (int) Notification.CHANNEL_NAME_INDEX:
                    notification.setChannelName(stringPool.get(parser.readInt(
                            Notification.CHANNEL_NAME_INDEX) - 1));
                    break;
                case (int) Notification.CHANNEL_ID:
                    String channelId = parser.readString(Notification.CHANNEL_ID);
                    notification.setChannelId(channelId);
                    stringPool.add(channelId);
                    break;
                case (int) Notification.CHANNEL_ID_INDEX:
                    notification.setChannelId(stringPool.get(parser.readInt(
                            Notification.CHANNEL_ID_INDEX) - 1));
                    break;
                case (int) Notification.UID:
                    notification.setUid(parser.readInt(Notification.UID));
                    break;
                case (int) Notification.USER_ID:
                    notification.setUserId(parser.readInt(Notification.USER_ID));
                    break;
                case (int) Notification.POSTED_TIME_MS:
                    notification.setPostedTimeMs(parser.readLong(Notification.POSTED_TIME_MS));
                    break;
                case (int) Notification.TITLE:
                    notification.setTitle(parser.readString(Notification.TITLE));
                    break;
                case (int) Notification.TEXT:
                    notification.setText(parser.readString(Notification.TEXT));
                    break;
                case (int) Notification.ICON:
                    final long iconToken = parser.start(Notification.ICON);
                    loadIcon(parser, notification, pkg);
                    parser.end(iconToken);
                    break;
                case (int) Notification.CONVERSATION_ID_INDEX:
                    String conversationId =
                            stringPool.get(parser.readInt(Notification.CONVERSATION_ID_INDEX) - 1);
                    notification.setConversationId(conversationId);
                    break;
                case (int) Notification.CONVERSATION_ID:
                    conversationId = parser.readString(Notification.CONVERSATION_ID);
                    notification.setConversationId(conversationId);
                    stringPool.add(conversationId);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return notification.build();
            }
        }
    }

    private static void loadIcon(ProtoInputStream parser,
            HistoricalNotification.Builder notification, String pkg) throws IOException {
        int iconType = Notification.TYPE_UNKNOWN;
        String imageBitmapFileName = null;
        int imageResourceId = Resources.ID_NULL;
        String imageResourceIdPackage = null;
        byte[] imageByteData = null;
        int imageByteDataLength = 0;
        int imageByteDataOffset = 0;
        String imageUri = null;

        while (true) {
            switch (parser.nextField()) {
                case (int) Notification.Icon.IMAGE_TYPE:
                    iconType = parser.readInt(Notification.Icon.IMAGE_TYPE);
                    break;
                case (int) Notification.Icon.IMAGE_DATA:
                    imageByteData = parser.readBytes(Notification.Icon.IMAGE_DATA);
                    break;
                case (int) Notification.Icon.IMAGE_DATA_LENGTH:
                    imageByteDataLength = parser.readInt(Notification.Icon.IMAGE_DATA_LENGTH);
                    break;
                case (int) Notification.Icon.IMAGE_DATA_OFFSET:
                    imageByteDataOffset = parser.readInt(Notification.Icon.IMAGE_DATA_OFFSET);
                    break;
                case (int) Notification.Icon.IMAGE_BITMAP_FILENAME:
                    imageBitmapFileName = parser.readString(
                            Notification.Icon.IMAGE_BITMAP_FILENAME);
                    break;
                case (int) Notification.Icon.IMAGE_RESOURCE_ID:
                    imageResourceId = parser.readInt(Notification.Icon.IMAGE_RESOURCE_ID);
                    break;
                case (int) Notification.Icon.IMAGE_RESOURCE_ID_PACKAGE:
                    imageResourceIdPackage = parser.readString(
                            Notification.Icon.IMAGE_RESOURCE_ID_PACKAGE);
                    break;
                case (int) Notification.Icon.IMAGE_URI:
                    imageUri = parser.readString(Notification.Icon.IMAGE_URI);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (iconType == Icon.TYPE_DATA) {

                        if (imageByteData != null) {
                            notification.setIcon(Icon.createWithData(
                                    imageByteData, imageByteDataOffset, imageByteDataLength));
                        }
                    } else if (iconType == Icon.TYPE_RESOURCE) {
                        if (imageResourceId != Resources.ID_NULL) {
                            notification.setIcon(Icon.createWithResource(
                                    imageResourceIdPackage != null
                                            ? imageResourceIdPackage
                                            : pkg,
                                    imageResourceId));
                        }
                    } else if (iconType == Icon.TYPE_URI) {
                        if (imageUri != null) {
                            notification.setIcon(Icon.createWithContentUri(imageUri));
                        }
                    } else if (iconType == Icon.TYPE_BITMAP) {
                        // TODO: read file from disk
                    }
                    return;
            }
        }
    }

    private static void writeIcon(ProtoOutputStream proto, HistoricalNotification notification) {
        final long token = proto.start(Notification.ICON);

        proto.write(Notification.Icon.IMAGE_TYPE, notification.getIcon().getType());
        switch (notification.getIcon().getType()) {
            case Icon.TYPE_DATA:
                proto.write(Notification.Icon.IMAGE_DATA, notification.getIcon().getDataBytes());
                proto.write(Notification.Icon.IMAGE_DATA_LENGTH,
                        notification.getIcon().getDataLength());
                proto.write(Notification.Icon.IMAGE_DATA_OFFSET,
                        notification.getIcon().getDataOffset());
                break;
            case Icon.TYPE_RESOURCE:
                proto.write(Notification.Icon.IMAGE_RESOURCE_ID, notification.getIcon().getResId());
                if (!notification.getPackage().equals(notification.getIcon().getResPackage())) {
                    proto.write(Notification.Icon.IMAGE_RESOURCE_ID_PACKAGE,
                            notification.getIcon().getResPackage());
                }
                break;
            case Icon.TYPE_URI:
                proto.write(Notification.Icon.IMAGE_URI, notification.getIcon().getUriString());
                break;
            case Icon.TYPE_BITMAP:
                // TODO: write file to disk
                break;
        }

        proto.end(token);
    }

    private static void writeNotification(ProtoOutputStream proto,
            final String[] stringPool, final HistoricalNotification notification) {
        final long token = proto.start(NotificationHistoryProto.NOTIFICATION);
        final int packageIndex = Arrays.binarySearch(stringPool, notification.getPackage());
        if (packageIndex >= 0) {
            proto.write(Notification.PACKAGE_INDEX, packageIndex + 1);
        } else {
            // Package not in Stringpool for some reason, write full string instead
            Slog.w(TAG, "notification package name (" + notification.getPackage()
                    + ") not found in string cache");
            proto.write(Notification.PACKAGE, notification.getPackage());
        }
        final int channelNameIndex = Arrays.binarySearch(stringPool, notification.getChannelName());
        if (channelNameIndex >= 0) {
            proto.write(Notification.CHANNEL_NAME_INDEX, channelNameIndex + 1);
        } else {
            Slog.w(TAG, "notification channel name (" + notification.getChannelName()
                    + ") not found in string cache");
            proto.write(Notification.CHANNEL_NAME, notification.getChannelName());
        }
        final int channelIdIndex = Arrays.binarySearch(stringPool, notification.getChannelId());
        if (channelIdIndex >= 0) {
            proto.write(Notification.CHANNEL_ID_INDEX, channelIdIndex + 1);
        } else {
            Slog.w(TAG, "notification channel id (" + notification.getChannelId()
                    + ") not found in string cache");
            proto.write(Notification.CHANNEL_ID, notification.getChannelId());
        }
        if (!TextUtils.isEmpty(notification.getConversationId())) {
            final int conversationIdIndex = Arrays.binarySearch(
                    stringPool, notification.getConversationId());
            if (conversationIdIndex >= 0) {
                proto.write(Notification.CONVERSATION_ID_INDEX, conversationIdIndex + 1);
            } else {
                Slog.w(TAG, "notification conversation id (" + notification.getConversationId()
                        + ") not found in string cache");
                proto.write(Notification.CONVERSATION_ID, notification.getConversationId());
            }
        }
        proto.write(Notification.UID, notification.getUid());
        proto.write(Notification.USER_ID, notification.getUserId());
        proto.write(Notification.POSTED_TIME_MS, notification.getPostedTimeMs());
        proto.write(Notification.TITLE, notification.getTitle());
        proto.write(Notification.TEXT, notification.getText());
        writeIcon(proto, notification);
        proto.end(token);
    }

    public static void read(InputStream in, NotificationHistory notifications,
            NotificationHistoryFilter filter) throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        List<String> stringPool = new ArrayList<>();
        while (true) {
            switch (proto.nextField()) {
                case (int) NotificationHistoryProto.STRING_POOL:
                    stringPool = readStringPool(proto);
                    break;
                case (int) NotificationHistoryProto.NOTIFICATION:
                    readNotification(proto, stringPool, notifications, filter);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (filter.isFiltering()) {
                        notifications.poolStringsFromNotifications();
                    } else {
                        notifications.addPooledStrings(stringPool);
                    }
                    return;
            }
        }
    }

    public static void write(OutputStream out, NotificationHistory notifications, int version) {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        proto.write(NotificationHistoryProto.MAJOR_VERSION, version);
        // String pool should be written before the history itself
        writeStringPool(proto, notifications);

        List<HistoricalNotification> notificationsToWrite = notifications.getNotificationsToWrite();
        final int count = notificationsToWrite.size();
        for (int i = 0; i < count; i++) {
            writeNotification(proto, notifications.getPooledStringsToWrite(),
                    notificationsToWrite.get(i));
        }

        proto.flush();
    }
}
