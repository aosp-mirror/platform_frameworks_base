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
import android.content.IntentSender;
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
            @Nullable ComponentName componentName, @ShortcutQuery.QueryFlags int flags,
            int userId);

    public abstract boolean
            isPinnedByCaller(int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String id, int userId);

    public abstract void pinShortcuts(int launcherUserId,
            @NonNull String callingPackage, @NonNull String packageName,
            @NonNull List<String> shortcutIds, int userId);

    public abstract Intent[] createShortcutIntents(
            int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    public abstract void addListener(@NonNull ShortcutChangeListener listener);

    public abstract int getShortcutIconResId(int launcherUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    public abstract ParcelFileDescriptor getShortcutIconFd(int launcherUserId,
            @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId);

    public abstract boolean hasShortcutHostPermission(int launcherUserId,
            @NonNull String callingPackage);

    public abstract boolean requestPinAppWidget(@NonNull String callingPackage,
            @NonNull AppWidgetProviderInfo appWidget, @Nullable Bundle extras,
            @Nullable IntentSender resultIntent, int userId);

    public abstract boolean isRequestPinItemSupported(int callingUserId, int requestType);
}
