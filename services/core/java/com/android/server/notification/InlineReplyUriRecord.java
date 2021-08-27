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

import android.app.ActivityManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;

/**
 * A record of inline reply (ex. RemoteInput) URI grants associated with a Notification.
 */
public final class InlineReplyUriRecord {
    private final IBinder mPermissionOwner;
    private final ArraySet<Uri> mUris;
    private final UserHandle mUser;
    private final String mPackageName;
    private final String mKey;

    /**
     * Construct a new InlineReplyUriRecord.
     * @param owner The PermissionOwner associated with this record.
     * @param user The user associated with this record.
     * @param packageName The name of the package which posted the notification.
     * @param key The key of the original NotificationRecord this notification as created with.
     */
    public InlineReplyUriRecord(IBinder owner, UserHandle user, String packageName, String key) {
        mPermissionOwner = owner;
        mUris = new ArraySet<>();
        mUser = user;
        mPackageName = packageName;
        mKey = key;
    }

    /**
     * Get the permission owner associated with this record.
     */
    public IBinder getPermissionOwner() {
        return mPermissionOwner;
    }

    /**
     * Get the content URIs associated with this record.
     */
    public ArraySet<Uri> getUris() {
        return mUris;
    }

    /**
     * Associate a new content URI with this record.
     */
    public void addUri(Uri uri) {
        mUris.add(uri);
    }

    /**
     * Get the user id associated with this record.
     * If the UserHandle associated with this record belongs to USER_ALL, return the ID for
     * USER_SYSTEM instead, to avoid errors around modifying URI permissions for an invalid user ID.
     */
    public int getUserId() {
        int userId = mUser.getIdentifier();
        if (UserManager.isHeadlessSystemUserMode() && userId == UserHandle.USER_ALL) {
            return ActivityManager.getCurrentUser();
        } else if (userId == UserHandle.USER_ALL) {
            return UserHandle.USER_SYSTEM;
        } else {
            return userId;
        }
    }

    /**
     * Get the name of the package associated with this record.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the key associated with this record.
     */
    public String getKey() {
        return mKey;
    }
}
