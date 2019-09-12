/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.RemoteInput;
import android.graphics.drawable.Icon;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * By diffing two entries, determines is view reinflation needed.
 */
public class NotificationUiAdjustment {

    public final String key;
    public final List<Notification.Action> smartActions;
    public final List<CharSequence> smartReplies;

    @VisibleForTesting
    NotificationUiAdjustment(
            String key, List<Notification.Action> smartActions, List<CharSequence> smartReplies) {
        this.key = key;
        this.smartActions = smartActions == null
                ? Collections.emptyList()
                : smartActions;
        this.smartReplies = smartReplies == null
                ? Collections.emptyList()
                : smartReplies;
    }

    public static NotificationUiAdjustment extractFromNotificationEntry(
            NotificationEntry entry) {
        return new NotificationUiAdjustment(
                entry.key, entry.getSmartActions(), entry.getSmartReplies());
    }

    public static boolean needReinflate(
            @NonNull NotificationUiAdjustment oldAdjustment,
            @NonNull NotificationUiAdjustment newAdjustment) {
        if (oldAdjustment == newAdjustment) {
            return false;
        }
        if (areDifferent(oldAdjustment.smartActions, newAdjustment.smartActions)) {
            return true;
        }
        if (!newAdjustment.smartReplies.equals(oldAdjustment.smartReplies)) {
            return true;
        }
        return false;
    }

    public static boolean areDifferent(
            @NonNull List<Notification.Action> first, @NonNull List<Notification.Action> second) {
        if (first == second) {
            return false;
        }
        if (first == null || second == null) {
            return true;
        }
        if (first.size() != second.size()) {
            return true;
        }
        for (int i = 0; i < first.size(); i++) {
            Notification.Action firstAction = first.get(i);
            Notification.Action secondAction = second.get(i);

            if (!TextUtils.equals(firstAction.title, secondAction.title)) {
                return true;
            }

            if (areDifferent(firstAction.getIcon(), secondAction.getIcon())) {
                return true;
            }

            if (!Objects.equals(firstAction.actionIntent, secondAction.actionIntent)) {
                return true;
            }

            if (areDifferent(firstAction.getRemoteInputs(), secondAction.getRemoteInputs())) {
                return true;
            }
        }
        return false;
    }

    private static boolean areDifferent(@Nullable Icon first, @Nullable Icon second) {
        if (first == second) {
            return false;
        }
        if (first == null || second == null) {
            return true;
        }
        return !first.sameAs(second);
    }

    private static boolean areDifferent(
            @Nullable RemoteInput[] first, @Nullable RemoteInput[] second) {
        if (first == second) {
            return false;
        }
        if (first == null || second == null) {
            return true;
        }
        if (first.length != second.length) {
            return true;
        }
        for (int i = 0; i < first.length; i++) {
            RemoteInput firstRemoteInput = first[i];
            RemoteInput secondRemoteInput = second[i];

            if (!TextUtils.equals(firstRemoteInput.getLabel(), secondRemoteInput.getLabel())) {
                return true;
            }
            if (areDifferent(firstRemoteInput.getChoices(), secondRemoteInput.getChoices())) {
                return true;
            }
        }
        return false;
    }

    private static boolean areDifferent(
            @Nullable CharSequence[] first, @Nullable CharSequence[] second) {
        if (first == second) {
            return false;
        }
        if (first == null || second == null) {
            return true;
        }
        if (first.length != second.length) {
            return true;
        }
        for (int i = 0; i < first.length; i++) {
            CharSequence firstCharSequence = first[i];
            CharSequence secondCharSequence = second[i];
            if (!TextUtils.equals(firstCharSequence, secondCharSequence)) {
                return true;
            }
        }
        return false;
    }
}
