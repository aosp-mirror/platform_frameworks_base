/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.LauncherUserInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.LauncherActivityInfoInternal;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutQueryWrapper;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IShortcutChangeCallback;
import android.content.pm.PackageInstaller;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.LauncherActivityInfoInternal;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.ParcelFileDescriptor;
import android.window.IDumpCallback;

import com.android.internal.infra.AndroidFuture;

import java.util.List;

/**
 * {@hide}
 */
interface ILauncherApps {
    void addOnAppsChangedListener(String callingPackage, in IOnAppsChangedListener listener);
    void removeOnAppsChangedListener(in IOnAppsChangedListener listener);
    ParceledListSlice getLauncherActivities(
            String callingPackage, String packageName, in UserHandle user);
    LauncherActivityInfoInternal resolveLauncherActivityInternal(
            String callingPackage, in ComponentName component, in UserHandle user);
    void startSessionDetailsActivityAsUser(in IApplicationThread caller, String callingPackage,
                String callingFeatureId, in PackageInstaller.SessionInfo sessionInfo,
                in Rect sourceBounds, in Bundle opts, in UserHandle user);
    void startActivityAsUser(in IApplicationThread caller, String callingPackage,
            String callingFeatureId, in ComponentName component, in Rect sourceBounds,
            in Bundle opts, in UserHandle user);
    PendingIntent getActivityLaunchIntent(String callingPackage, in ComponentName component,
            in UserHandle user);
    LauncherUserInfo getLauncherUserInfo(in UserHandle user);
    List<String> getPreInstalledSystemPackages(in UserHandle user);
    IntentSender getAppMarketActivityIntent(String callingPackage, String packageName,
            in UserHandle user);
    IntentSender getPrivateSpaceSettingsIntent();
    void showAppDetailsAsUser(in IApplicationThread caller, String callingPackage,
            String callingFeatureId, in ComponentName component, in Rect sourceBounds,
            in Bundle opts, in UserHandle user);
    boolean isPackageEnabled(String callingPackage, String packageName, in UserHandle user);
    Bundle getSuspendedPackageLauncherExtras(String packageName, in UserHandle user);
    boolean isActivityEnabled(
            String callingPackage, in ComponentName component, in UserHandle user);
    ApplicationInfo getApplicationInfo(
            String callingPackage, String packageName, int flags, in UserHandle user);

    LauncherApps.AppUsageLimit getAppUsageLimit(String callingPackage, String packageName,
            in UserHandle user);

    ParceledListSlice getShortcuts(String callingPackage, in ShortcutQueryWrapper query,
            in UserHandle user);
    void getShortcutsAsync(String callingPackage, in ShortcutQueryWrapper query,
            in UserHandle user, in AndroidFuture<List<ShortcutInfo>> cb);
    void pinShortcuts(String callingPackage, String packageName, in List<String> shortcutIds,
            in UserHandle user);
    boolean startShortcut(String callingPackage, String packageName, String featureId, String id,
            in Rect sourceBounds, in Bundle startActivityOptions, int userId);

    int getShortcutIconResId(String callingPackage, String packageName, String id,
            int userId);
    ParcelFileDescriptor getShortcutIconFd(String callingPackage, String packageName, String id,
            int userId);

    boolean hasShortcutHostPermission(String callingPackage);
    boolean shouldHideFromSuggestions(String packageName, in UserHandle user);

    ParceledListSlice getShortcutConfigActivities(
            String callingPackage, String packageName, in UserHandle user);
    IntentSender getShortcutConfigActivityIntent(String callingPackage, in ComponentName component,
            in UserHandle user);
    PendingIntent getShortcutIntent(String callingPackage, String packageName, String shortcutId,
            in Bundle opts, in UserHandle user);

    // Unregister is performed using package installer
    void registerPackageInstallerCallback(String callingPackage,
            in IPackageInstallerCallback callback);
    ParceledListSlice getAllSessions(String callingPackage);

    void registerShortcutChangeCallback(String callingPackage, in ShortcutQueryWrapper query,
	    in IShortcutChangeCallback callback);
    void unregisterShortcutChangeCallback(String callingPackage,
            in IShortcutChangeCallback callback);

    void cacheShortcuts(String callingPackage, String packageName, in List<String> shortcutIds,
            in UserHandle user, int cacheFlags);
    void uncacheShortcuts(String callingPackage, String packageName, in List<String> shortcutIds,
            in UserHandle user, int cacheFlags);

    String getShortcutIconUri(String callingPackage, String packageName, String shortcutId,
            int userId);
    Map<String, LauncherActivityInfoInternal> getActivityOverrides(String callingPackage, int userId);

    /** Register a callback to be called right before the wmtrace data is moved to the bugreport. */
    void registerDumpCallback(IDumpCallback cb);

    /** Unregister a callback, so that it won't be called when LauncherApps dumps. */
    void unRegisterDumpCallback(IDumpCallback cb);

    void setArchiveCompatibilityOptions(boolean enableIconOverlay, boolean enableUnarchivalConfirmation);

    List<UserHandle> getUserProfiles();

    /** Saves view capture data to the wm trace directory. */
    void saveViewCaptureData();
}
