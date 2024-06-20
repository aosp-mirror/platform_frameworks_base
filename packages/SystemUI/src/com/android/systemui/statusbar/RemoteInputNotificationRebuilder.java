/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.Flags.lifetimeExtensionRefactor;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.net.Uri;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.inject.Inject;

/**
 * A helper class which will augment the notifications using arguments and other information
 * accessible to the entry in order to provide intermediate remote input states.
 */
@SysUISingleton
public class RemoteInputNotificationRebuilder {

    private final Context mContext;

    @Inject
    RemoteInputNotificationRebuilder(Context context) {
        mContext = context;
    }

    /**
     * When a smart reply is sent off to the app, we insert the text into the remote input history,
     * and show a spinner to indicate that the app has yet to respond.
     */
    @NonNull
    public StatusBarNotification rebuildForSendingSmartReply(NotificationEntry entry,
            CharSequence reply) {
        return rebuildWithRemoteInputInserted(entry, reply,
                true /* showSpinner */,
                null /* mimeType */, null /* uri */);
    }

    /**
     * When the app cancels a notification in response to a smart reply, we remove the spinner
     * and leave the previously-added reply.  This is the lifetime-extended appearance of the
     * notification.
     */
    @NonNull
    public StatusBarNotification rebuildForCanceledSmartReplies(
            NotificationEntry entry) {
        return rebuildWithExistingReplies(entry);
    }

    /**
     * Rebuilds to include any previously-added remote input replies.
     * For when the app cancels a notification that has already been lifetime extended.
     */
    @NonNull
    public StatusBarNotification rebuildWithExistingReplies(NotificationEntry entry) {
        return rebuildWithRemoteInputInserted(entry, null /* remoteInputText */,
                false /* showSpinner */, null /* mimeType */, null /* uri */);
    }

    /**
     * When the app cancels a notification in response to a remote input reply, we update the
     * notification with the reply text and/or attachment. This is the lifetime-extended
     * appearance of the notification.
     */
    @NonNull
    public StatusBarNotification rebuildForRemoteInputReply(NotificationEntry entry) {
        CharSequence remoteInputText = entry.remoteInputText;
        if (TextUtils.isEmpty(remoteInputText)) {
            remoteInputText = entry.remoteInputTextWhenReset;
        }
        String remoteInputMimeType = entry.remoteInputMimeType;
        Uri remoteInputUri = entry.remoteInputUri;
        StatusBarNotification newSbn = rebuildWithRemoteInputInserted(entry,
                remoteInputText, false /* showSpinner */, remoteInputMimeType,
                remoteInputUri);
        return newSbn;
    }

    /** Inner method for generating the SBN */
    @VisibleForTesting
    @NonNull
    StatusBarNotification rebuildWithRemoteInputInserted(NotificationEntry entry,
            CharSequence remoteInputText, boolean showSpinner, String mimeType, Uri uri) {
        StatusBarNotification sbn = entry.getSbn();
        Notification.Builder b = Notification.Builder
                .recoverBuilder(mContext, sbn.getNotification().clone());

        if (lifetimeExtensionRefactor()) {
            if (entry.remoteInputs == null) {
                entry.remoteInputs = new ArrayList<RemoteInputHistoryItem>();
            }

            // Append new remote input information to remoteInputs list
            if (remoteInputText != null || uri != null) {
                RemoteInputHistoryItem newItem = uri != null
                        ? new RemoteInputHistoryItem(mimeType, uri, remoteInputText)
                        : new RemoteInputHistoryItem(remoteInputText);
                // The list is latest-first, so new elements should be added as the first element.
                entry.remoteInputs.add(0, newItem);
            }

            // Read the whole remoteInputs list from the entry, then append all of those to the sbn.
            Parcelable[] oldHistoryItems = sbn.getNotification().extras
                    .getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);

            RemoteInputHistoryItem[] newHistoryItems = oldHistoryItems != null
                    ? Stream.concat(
                            entry.remoteInputs.stream(),
                            Arrays.stream(oldHistoryItems).map(p -> (RemoteInputHistoryItem) p))
                    .toArray(RemoteInputHistoryItem[]::new)
                    : entry.remoteInputs.toArray(RemoteInputHistoryItem[]::new);
            b.setRemoteInputHistory(newHistoryItems);

        } else {
            if (remoteInputText != null || uri != null) {
                RemoteInputHistoryItem newItem = uri != null
                        ? new RemoteInputHistoryItem(mimeType, uri, remoteInputText)
                        : new RemoteInputHistoryItem(remoteInputText);
                Parcelable[] oldHistoryItems = sbn.getNotification().extras
                        .getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
                RemoteInputHistoryItem[] newHistoryItems = oldHistoryItems != null
                        ? Stream.concat(
                                Stream.of(newItem),
                                Arrays.stream(oldHistoryItems).map(p -> (RemoteInputHistoryItem) p))
                        .toArray(RemoteInputHistoryItem[]::new)
                        : new RemoteInputHistoryItem[]{newItem};
                b.setRemoteInputHistory(newHistoryItems);
            }
        }
        b.setShowRemoteInputSpinner(showSpinner);
        b.setHideSmartReplies(true);

        Notification newNotification = b.build();

        // Undo any compatibility view inflation
        newNotification.contentView = sbn.getNotification().contentView;
        newNotification.bigContentView = sbn.getNotification().bigContentView;
        newNotification.headsUpContentView = sbn.getNotification().headsUpContentView;

        return new StatusBarNotification(
                sbn.getPackageName(),
                sbn.getOpPkg(),
                sbn.getId(),
                sbn.getTag(),
                sbn.getUid(),
                sbn.getInitialPid(),
                newNotification,
                sbn.getUser(),
                sbn.getOverrideGroupKey(),
                sbn.getPostTime());
    }


}
