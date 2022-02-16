/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.util.List;

/**
 * Entry points used by {@link LauncherApps}.
 *
 * <p>No permission / argument checks will be performed inside.
 * Callers must check the calling app permission and the calling package name.
 * @hide
 */
public abstract class ShortcutServiceInternal {
    public interface ShortcutChangeListener {
        void onShortcutChanged(@NonNull String packageName, @UserIdInt int userId);
    }

    public abstract List<ShortcutInfo>
            getShortcuts(int launcherUserId,
            @NonNull String callingPackage, long changedSince,
            @Nullable String packageName, @Nullable List<String> shortcutIds,
            @Nullable List<LocusId> locusIds, @Nullable ComponentName componentName,
            @ShortcutQuery.QueryFlags int flags, int userId, int callingPid, int callingUid);

    public abstract boolean
            isPinnedByCaller(int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String id, int userId);

    public abstract void pinShortcuts(int launcherUserId,
            @NonNull String callingPackage, @NonNull String packageName,
            @NonNull List<String> shortcutIds, int userId);

    public abstract Intent[] createShortcutIntents(
            int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId,
            int callingPid, int callingUid);

    public abstract void addListener(@NonNull ShortcutChangeListener listener);

    public abstract void addShortcutChangeCallback(
            @NonNull LauncherApps.ShortcutChangeCallback callback);

    public abstract int getShortcutIconResId(int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    /**
     * Get the theme res ID of the starting window, it can be 0 if not specified.
     */
    public abstract @Nullable String getShortcutStartingThemeResName(int launcherUserId,
            @NonNull String callingPackage, @NonNull String packageName, @NonNull String shortcutId,
            int userId);

    public abstract ParcelFileDescriptor getShortcutIconFd(int launcherUserId,
            @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    public abstract boolean hasShortcutHostPermission(int launcherUserId,
            @NonNull String callingPackage, int callingPid, int callingUid);

    public abstract void setShortcutHostPackage(@NonNull String type, @Nullable String packageName,
            int userId);

    public abstract boolean requestPinAppWidget(@NonNull String callingPackage,
            @NonNull AppWidgetProviderInfo appWidget, @Nullable Bundle extras,
            @Nullable IntentSender resultIntent, int userId);

    public abstract boolean isRequestPinItemSupported(int callingUserId, int requestType);

    public abstract boolean isForegroundDefaultLauncher(@NonNull String callingPackage,
            int callingUid);

    public abstract void cacheShortcuts(int launcherUserId,
            @NonNull String callingPackage, @NonNull String packageName,
            @NonNull List<String> shortcutIds, int userId, int cacheFlags);
    public abstract void uncacheShortcuts(int launcherUserId,
            @NonNull String callingPackage, @NonNull String packageName,
            @NonNull List<String> shortcutIds, int userId, int cacheFlags);

    /**
     * Retrieves all of the direct share targets that match the given IntentFilter for the specified
     * user.
     */
    public abstract List<ShortcutManager.ShareShortcutInfo> getShareTargets(
            @NonNull String callingPackage, @NonNull IntentFilter intentFilter, int userId);

    /**
     * Returns the icon Uri of the shortcut, and grants Uri read permission to the caller.
     */
    public abstract String getShortcutIconUri(int launcherUserId, @NonNull String launcherPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    public abstract boolean isSharingShortcut(int callingUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId,
            @NonNull IntentFilter filter);
}
