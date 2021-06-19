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

package com.android.systemui.people.widget;

import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;

import android.app.people.PeopleSpaceTile;
import android.text.TextUtils;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Objects;

/** Class that encapsulates fields identifying a Conversation. */
public class PeopleTileKey {
    private String mShortcutId;
    private int mUserId;
    private String mPackageName;

    public PeopleTileKey(String shortcutId, int userId, String packageName) {
        mShortcutId = shortcutId;
        mUserId = userId;
        mPackageName = packageName;
    }

    public PeopleTileKey(PeopleSpaceTile tile) {
        mShortcutId = tile.getId();
        mUserId = tile.getUserHandle().getIdentifier();
        mPackageName = tile.getPackageName();
    }

    public PeopleTileKey(NotificationEntry entry) {
        mShortcutId = entry.getRanking() != null
                && entry.getRanking().getConversationShortcutInfo() != null
                ? entry.getRanking().getConversationShortcutInfo().getId()
                : EMPTY_STRING;
        mUserId = entry.getSbn().getUser() != null
                ? entry.getSbn().getUser().getIdentifier() : INVALID_USER_ID;
        mPackageName = entry.getSbn().getPackageName();
    }

    public String getShortcutId() {
        return mShortcutId;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getPackageName() {
        return mPackageName;
    }

    /** Returns whether PeopleTileKey is valid/well-formed. */
    public boolean isValid() {
        return !TextUtils.isEmpty(mShortcutId) && !TextUtils.isEmpty(mPackageName) && mUserId >= 0;
    }

    /**
     * Returns the uniquely identifying key for the conversation.
     *
     * <p>{@code userId} will always be a number, so we put user ID as the
     * delimiter between the app-provided strings of shortcut ID and package name.
     *
     * <p>There aren't restrictions on shortcut ID characters, but there are restrictions requiring
     * a {@code packageName} to always start with a letter. This restriction means we are
     * guaranteed to avoid cases like "a/b/0/0/package.name" having two potential keys, as the first
     * case is impossible given the package name restrictions:
     * <ul>
     *     <li>"a/b" + "/" + 0 + "/" + "0/packageName"</li>
     *     <li>"a/b/0" + "/" + 0 + "/" + "packageName"</li>
     * </ul>
     */
    @Override
    public String toString() {
        if (!isValid()) return EMPTY_STRING;
        return mShortcutId + "/" + mUserId + "/" + mPackageName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PeopleTileKey)) {
            return false;
        }
        final PeopleTileKey o = (PeopleTileKey) other;
        return Objects.equals(o.toString(), this.toString());
    }

    @Override
    public int hashCode() {
        return mPackageName.hashCode() + Integer.valueOf(mUserId).hashCode()
                + mShortcutId.hashCode();
    }
}
