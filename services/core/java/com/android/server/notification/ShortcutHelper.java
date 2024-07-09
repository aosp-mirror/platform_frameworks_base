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

package com.android.server.notification;

import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_GET_PERSONS_DATA;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER;

import android.annotation.NonNull;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper for querying shortcuts.
 */
public class ShortcutHelper {
    private static final String TAG = "ShortcutHelper";

    private static final IntentFilter SHARING_FILTER = new IntentFilter();
    static {
        try {
            SHARING_FILTER.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Slog.e(TAG, "Bad mime type", e);
        }
    }

    /**
     * Listener to call when a shortcut we're tracking has been removed.
     */
    interface ShortcutListener {
        void onShortcutRemoved(String key);
    }

    private final ShortcutListener mShortcutListener;
    private LauncherApps mLauncherAppsService;
    private ShortcutServiceInternal mShortcutServiceInternal;
    private UserManager mUserManager;

    // Key: packageName|userId Value: <shortcutId, notifId>
    private final HashMap<String, HashMap<String, String>> mActiveShortcutBubbles = new HashMap<>();
    private boolean mShortcutChangedCallbackRegistered;

    // Bubbles can be created based on a shortcut, we need to listen for changes to
    // that shortcut so that we may update the bubble appropriately.
    private final LauncherApps.ShortcutChangeCallback mShortcutChangeCallback =
            new LauncherApps.ShortcutChangeCallback() {

                @Override
                public void onShortcutsAddedOrUpdated(@NonNull String packageName,
                        @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
                }

                public void onShortcutsRemoved(@NonNull String packageName,
                        @NonNull List<ShortcutInfo> removedShortcuts, @NonNull UserHandle user) {
                    final String packageUserKey = getPackageUserKey(packageName, user);
                    if (mActiveShortcutBubbles.get(packageUserKey) == null) return;
                    for (ShortcutInfo info : removedShortcuts) {
                        onShortcutRemoved(packageUserKey, info.getId());
                    }
                }
            };

    ShortcutHelper(LauncherApps launcherApps, ShortcutListener listener,
            ShortcutServiceInternal shortcutServiceInternal, UserManager userManager) {
        mLauncherAppsService = launcherApps;
        mShortcutListener = listener;
        mShortcutServiceInternal = shortcutServiceInternal;
        mUserManager = userManager;
    }

    @VisibleForTesting
    void setLauncherApps(LauncherApps launcherApps) {
        mLauncherAppsService = launcherApps;
    }

    @VisibleForTesting
    void setShortcutServiceInternal(ShortcutServiceInternal shortcutServiceInternal) {
        mShortcutServiceInternal = shortcutServiceInternal;
    }

    @VisibleForTesting
    void setUserManager(UserManager userManager) {
        mUserManager = userManager;
    }

    /**
     * Returns whether the given shortcut info is a conversation shortcut.
     */
    public static boolean isConversationShortcut(
            ShortcutInfo shortcutInfo, ShortcutServiceInternal shortcutServiceInternal,
            int callingUserId) {
        if (shortcutInfo == null || !shortcutInfo.isLongLived() || !shortcutInfo.isEnabled()) {
            return false;
        }
        // TODO (b/155016294) uncomment when sharing shortcuts are required
        /*
        shortcutServiceInternal.isSharingShortcut(callingUserId, "android",
                shortcutInfo.getPackage(), shortcutInfo.getId(), shortcutInfo.getUserId(),
                SHARING_FILTER);
         */
        return true;
    }

    /**
     * Only returns shortcut info if it's found and if it's a conversation shortcut.
     */
    ShortcutInfo getValidShortcutInfo(String shortcutId, String packageName, UserHandle user) {
        // Shortcuts cannot be accessed when the user is locked.
        if (mLauncherAppsService == null  || !mUserManager.isUserUnlocked(user)) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (shortcutId == null || packageName == null || user == null) {
                return null;
            }
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(packageName);
            query.setShortcutIds(Arrays.asList(shortcutId));
            query.setQueryFlags(FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
                    | FLAG_MATCH_CACHED | FLAG_GET_PERSONS_DATA);
            List<ShortcutInfo> shortcuts = mLauncherAppsService.getShortcuts(query, user);
            ShortcutInfo info = shortcuts != null && shortcuts.size() > 0
                    ? shortcuts.get(0)
                    : null;
            if (isConversationShortcut(info, mShortcutServiceInternal, user.getIdentifier())) {
                return info;
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Caches the given shortcut in Shortcut Service.
     */
    void cacheShortcut(ShortcutInfo shortcutInfo, UserHandle user) {
        if (shortcutInfo.isLongLived() && !shortcutInfo.isCached()) {
            mShortcutServiceInternal.cacheShortcuts(user.getIdentifier(), "android",
                    shortcutInfo.getPackage(), Collections.singletonList(shortcutInfo.getId()),
                    shortcutInfo.getUserId(), ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
        }
    }

    /**
     * Shortcut based bubbles require some extra work to listen for shortcut changes.
     *
     * @param r the notification record to check
     * @param removedNotification true if this notification is being removed
     */
    void maybeListenForShortcutChangesForBubbles(NotificationRecord r,
            boolean removedNotification) {
        final String shortcutId = r.getNotification().getBubbleMetadata() != null
                ? r.getNotification().getBubbleMetadata().getShortcutId()
                : null;
        final String packageUserKey = getPackageUserKey(r.getSbn().getPackageName(), r.getUser());
        if (!removedNotification
                && !TextUtils.isEmpty(shortcutId)
                && r.getShortcutInfo() != null
                && r.getShortcutInfo().getId().equals(shortcutId)) {
            // Must track shortcut based bubbles in case the shortcut is removed
            HashMap<String, String> packageBubbles = mActiveShortcutBubbles.get(
                    packageUserKey);
            if (packageBubbles == null) {
                packageBubbles = new HashMap<>();
            }
            packageBubbles.put(shortcutId, r.getKey());
            mActiveShortcutBubbles.put(packageUserKey, packageBubbles);
            registerCallbackIfNeeded();
        } else {
            // No longer track shortcut
            HashMap<String, String> packageBubbles = mActiveShortcutBubbles.get(
                    packageUserKey);
            if (packageBubbles != null) {
                if (!TextUtils.isEmpty(shortcutId)) {
                    packageBubbles.remove(shortcutId);
                } else {
                    // Copy the shortcut IDs to avoid a concurrent modification exception.
                    final Set<String> shortcutIds = new HashSet<>(packageBubbles.keySet());

                    // Check if there was a matching entry
                    for (String pkgShortcutId : shortcutIds) {
                        String entryKey = packageBubbles.get(pkgShortcutId);
                        if (r.getKey().equals(entryKey)) {
                            // No longer has shortcut id so remove it
                            packageBubbles.remove(pkgShortcutId);
                        }
                    }
                }
                if (packageBubbles.isEmpty()) {
                    mActiveShortcutBubbles.remove(packageUserKey);
                }
            }
            unregisterCallbackIfNeeded();
        }
    }

    private String getPackageUserKey(String packageName, UserHandle user) {
        return packageName + "|" + user.getIdentifier();
    }

    private void onShortcutRemoved(String packageUserKey, String shortcutId) {
        HashMap<String, String> shortcutBubbles = mActiveShortcutBubbles.get(packageUserKey);
        ArrayList<String> bubbleKeysToRemove = new ArrayList<>();
        if (shortcutBubbles != null) {
            if (shortcutBubbles.containsKey(shortcutId)) {
                bubbleKeysToRemove.add(shortcutBubbles.get(shortcutId));
                shortcutBubbles.remove(shortcutId);
                if (shortcutBubbles.isEmpty()) {
                    mActiveShortcutBubbles.remove(packageUserKey);
                    unregisterCallbackIfNeeded();
                }
            }
            notifyNoMan(bubbleKeysToRemove);
        }
    }

    private void registerCallbackIfNeeded() {
        if (!mShortcutChangedCallbackRegistered) {
            mShortcutChangedCallbackRegistered = true;
            mShortcutServiceInternal.addShortcutChangeCallback(mShortcutChangeCallback);
        }
    }

    private void unregisterCallbackIfNeeded() {
        if (mShortcutChangedCallbackRegistered && mActiveShortcutBubbles.isEmpty()) {
            mShortcutServiceInternal.removeShortcutChangeCallback(mShortcutChangeCallback);
            mShortcutChangedCallbackRegistered = false;
        }
    }

    void destroy() {
        if (mShortcutChangedCallbackRegistered) {
            mShortcutServiceInternal.removeShortcutChangeCallback(mShortcutChangeCallback);
            mShortcutChangedCallbackRegistered = false;
        }
    }

    private void notifyNoMan(List<String> bubbleKeysToRemove) {
        // Let NoMan know about the updates
        for (int i = 0; i < bubbleKeysToRemove.size(); i++) {
            // update flag bubble
            String bubbleKey = bubbleKeysToRemove.get(i);
            if (mShortcutListener != null) {
                mShortcutListener.onShortcutRemoved(bubbleKey);
            }
        }
    }
}
