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

package com.android.systemui.statusbar.notification.icon;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.StatusBarIconView;

/**
 * Data class for storing icons associated with a notification
 */
public final class IconPack {

    private final boolean mAreIconsAvailable;
    @Nullable private final StatusBarIconView mStatusBarIcon;
    @Nullable private final StatusBarIconView mShelfIcon;
    @Nullable private final StatusBarIconView mAodIcon;
    @Nullable private final StatusBarIconView mCenteredIcon;

    @Nullable private StatusBarIcon mSmallIconDescriptor;
    @Nullable private StatusBarIcon mPeopleAvatarDescriptor;

    private boolean mIsImportantConversation;

    /**
     * Builds an empty instance of IconPack that doesn't have any icons (because either they
     * haven't been inflated yet or there was an error while inflating them).
     */
    public static IconPack buildEmptyPack(@Nullable IconPack fromSource) {
        return new IconPack(false, null, null, null, null, fromSource);
    }

    /**
     * Builds an instance of an IconPack that contains successfully-inflated icons
     */
    public static IconPack buildPack(
            @NonNull StatusBarIconView statusBarIcon,
            @NonNull StatusBarIconView shelfIcon,
            @NonNull StatusBarIconView aodIcon,
            @Nullable StatusBarIconView centeredIcon,
            @Nullable IconPack source) {
        return new IconPack(true, statusBarIcon, shelfIcon, aodIcon, centeredIcon, source);
    }

    private IconPack(
            boolean areIconsAvailable,
            @Nullable StatusBarIconView statusBarIcon,
            @Nullable StatusBarIconView shelfIcon,
            @Nullable StatusBarIconView aodIcon,
            @Nullable StatusBarIconView centeredIcon,
            @Nullable IconPack source) {
        mAreIconsAvailable = areIconsAvailable;
        mStatusBarIcon = statusBarIcon;
        mShelfIcon = shelfIcon;
        mCenteredIcon = centeredIcon;
        mAodIcon = aodIcon;
        if (source != null) {
            mIsImportantConversation = source.mIsImportantConversation;
        }
    }

    /** The version of the notification icon that appears in the status bar. */
    @Nullable
    public StatusBarIconView getStatusBarIcon() {
        return mStatusBarIcon;
    }

    /**
     * The version of the icon that appears in the "shelf" at the bottom of the notification shade.
     * In general, this icon also appears somewhere on the notification and is "sucked" into the
     * shelf as the scrolls beyond it.
     */
    @Nullable
    public StatusBarIconView getShelfIcon() {
        return mShelfIcon;
    }

    @Nullable
    public StatusBarIconView getCenteredIcon() {
        return mCenteredIcon;
    }

    /** The version of the icon that's shown when pulsing (in AOD). */
    @Nullable
    public StatusBarIconView getAodIcon() {
        return mAodIcon;
    }

    @Nullable
    StatusBarIcon getSmallIconDescriptor() {
        return mSmallIconDescriptor;
    }

    void setSmallIconDescriptor(@Nullable StatusBarIcon smallIconDescriptor) {
        mSmallIconDescriptor = smallIconDescriptor;
    }

    @Nullable
    StatusBarIcon getPeopleAvatarDescriptor() {
        return mPeopleAvatarDescriptor;
    }

    void setPeopleAvatarDescriptor(@Nullable StatusBarIcon peopleAvatarDescriptor) {
        mPeopleAvatarDescriptor = peopleAvatarDescriptor;
    }

    boolean isImportantConversation() {
        return mIsImportantConversation;
    }

    void setImportantConversation(boolean importantConversation) {
        mIsImportantConversation = importantConversation;
    }

    public boolean getAreIconsAvailable() {
        return mAreIconsAvailable;
    }
}
