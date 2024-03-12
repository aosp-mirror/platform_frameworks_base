/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.PendingIntent;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstaller;
import android.content.pm.ParceledListSlice;
import android.content.pm.VersionedPackage;
import android.content.IntentSender;
import android.os.RemoteCallback;
import android.os.UserHandle;

import android.graphics.Bitmap;

/** {@hide} */
interface IPackageInstaller {
    int createSession(in PackageInstaller.SessionParams params, String installerPackageName,
            String installerAttributionTag, int userId);

    void updateSessionAppIcon(int sessionId, in Bitmap appIcon);
    void updateSessionAppLabel(int sessionId, String appLabel);

    void abandonSession(int sessionId);

    IPackageInstallerSession openSession(int sessionId);

    PackageInstaller.SessionInfo getSessionInfo(int sessionId);

    ParceledListSlice getAllSessions(int userId);
    ParceledListSlice getMySessions(String installerPackageName, int userId);

    ParceledListSlice getStagedSessions();

    void registerCallback(IPackageInstallerCallback callback, int userId);
    void unregisterCallback(IPackageInstallerCallback callback);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void uninstall(in VersionedPackage versionedPackage, String callerPackageName, int flags,
            in IntentSender statusReceiver, int userId);

    void uninstallExistingPackage(in VersionedPackage versionedPackage, String callerPackageName,
            in IntentSender statusReceiver, int userId);

    void installExistingPackage(String packageName, int installFlags, int installReason,
            in IntentSender statusReceiver, int userId, in List<String> whiteListedPermissions);

    @EnforcePermission("INSTALL_PACKAGES")
    void setPermissionsResult(int sessionId, boolean accepted);

    void bypassNextStagedInstallerCheck(boolean value);

    void bypassNextAllowedApexUpdateCheck(boolean value);

    void disableVerificationForUid(int uid);

    void setAllowUnlimitedSilentUpdates(String installerPackageName);
    void setSilentUpdatesThrottleTime(long throttleTimeInSeconds);
    void checkInstallConstraints(String installerPackageName, in List<String> packageNames,
            in PackageInstaller.InstallConstraints constraints, in RemoteCallback callback);
    void waitForInstallConstraints(String installerPackageName, in List<String> packageNames,
            in PackageInstaller.InstallConstraints constraints, in IntentSender callback,
            long timeout);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.DELETE_PACKAGES,android.Manifest.permission.REQUEST_DELETE_PACKAGES})")
    void requestArchive(String packageName, String callerPackageName, in IntentSender statusReceiver, in UserHandle userHandle, int flags);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.INSTALL_PACKAGES,android.Manifest.permission.REQUEST_INSTALL_PACKAGES})")
    void requestUnarchive(String packageName, String callerPackageName, in IntentSender statusReceiver, in UserHandle userHandle);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)")
    void installPackageArchived(in ArchivedPackageParcel archivedPackageParcel,
            in PackageInstaller.SessionParams params,
            in IntentSender statusReceiver,
            String installerPackageName, in UserHandle userHandle);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.INSTALL_PACKAGES,android.Manifest.permission.REQUEST_INSTALL_PACKAGES})")
    void reportUnarchivalStatus(int unarchiveId, int status, long requiredStorageBytes, in PendingIntent userActionIntent, in UserHandle userHandle);
}
