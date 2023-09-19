/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import androidx.annotation.NonNull;
import java.io.File;
import java.util.Arrays;
import java.util.Objects;

public class PackageUtil {

    private static final String TAG = InstallRepository.class.getSimpleName();
    private static final String DOWNLOADS_AUTHORITY = "downloads";
    private static final String SPLIT_BASE_APK_END_WITH = "base.apk";

    /**
     * Determines if the UID belongs to the system downloads provider and returns the
     * {@link ApplicationInfo} of the provider
     *
     * @param uid UID of the caller
     * @return {@link ApplicationInfo} of the provider if a downloads provider exists, it is a
     *     system app, and its UID matches with the passed UID, null otherwise.
     */
    public static ApplicationInfo getSystemDownloadsProviderInfo(PackageManager pm, int uid) {
        final ProviderInfo providerInfo = pm.resolveContentProvider(
            DOWNLOADS_AUTHORITY, 0);
        if (providerInfo == null) {
            // There seems to be no currently enabled downloads provider on the system.
            return null;
        }
        ApplicationInfo appInfo = providerInfo.applicationInfo;
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && uid == appInfo.uid) {
            return appInfo;
        }
        return null;
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    public static int getMaxTargetSdkVersionForUid(@NonNull Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        int targetSdkVersion = -1;
        if (packages != null) {
            for (String packageName : packages) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                    targetSdkVersion = Math.max(targetSdkVersion, info.targetSdkVersion);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion;
    }

    public static boolean canPackageQuery(Context context, int callingUid, Uri packageUri) {
        PackageManager pm = context.getPackageManager();
        ProviderInfo info = pm.resolveContentProvider(packageUri.getAuthority(),
            PackageManager.ComponentInfoFlags.of(0));
        if (info == null) {
            return false;
        }
        String targetPackage = info.packageName;

        String[] callingPackages = pm.getPackagesForUid(callingUid);
        if (callingPackages == null) {
            return false;
        }
        for (String callingPackage : callingPackages) {
            try {
                if (pm.canPackageQuery(callingPackage, targetPackage)) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // no-op
            }
        }
        return false;
    }

    /**
     * @param context the {@link Context} object
     * @param permission the permission name to check
     * @param callingUid the UID of the caller who's permission is being checked
     * @return {@code true} if the callingUid is granted the said permission
     */
    public static boolean isPermissionGranted(Context context, String permission, int callingUid) {
        return context.checkPermission(permission, -1, callingUid)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param pm the {@link PackageManager} object
     * @param permission the permission name to check
     * @param packageName the name of the package who's permission is being checked
     * @return {@code true} if the package is granted the said permission
     */
    public static boolean isPermissionGranted(PackageManager pm, String permission,
        String packageName) {
        return pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param context the {@link Context} object
     * @param callingUid the UID of the caller who's permission is being checked
     * @param originatingUid the UID from where install is being originated. This could be same as
     * callingUid or it will be the UID of the package performing a session based install
     * @param isTrustedSource whether install request is coming from a privileged app or an app that
     * has {@link Manifest.permission.INSTALL_PACKAGES} permission granted
     * @return {@code true} if the package is granted the said permission
     */
    public static boolean isInstallPermissionGrantedOrRequested(Context context, int callingUid,
        int originatingUid, boolean isTrustedSource) {
        boolean isDocumentsManager =
            isPermissionGranted(context, Manifest.permission.MANAGE_DOCUMENTS, callingUid);
        boolean isSystemDownloadsProvider =
            getSystemDownloadsProviderInfo(context.getPackageManager(), callingUid) != null;

        if (!isTrustedSource && !isSystemDownloadsProvider && !isDocumentsManager) {

            final int targetSdkVersion = getMaxTargetSdkVersionForUid(context, originatingUid);
            if (targetSdkVersion < 0) {
                // Invalid originating uid supplied. Abort install.
                Log.w(TAG, "Cannot get target sdk version for uid " + originatingUid);
                return false;
            } else if (targetSdkVersion >= Build.VERSION_CODES.O
                && !isUidRequestingPermission(context.getPackageManager(), originatingUid,
                Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                Log.e(TAG, "Requesting uid " + originatingUid + " needs to declare permission "
                    + Manifest.permission.REQUEST_INSTALL_PACKAGES);
                return false;
            }
        }
        return true;
    }

    /**
     * @param pm the {@link PackageManager} object
     * @param uid the UID of the caller who's permission is being checked
     * @param permission the permission name to check
     * @return {@code true} if the caller is requesting the said permission in its Manifest
     */
    public static boolean isUidRequestingPermission(PackageManager pm, int uid, String permission) {
        final String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        for (final String packageName : packageNames) {
            final PackageInfo packageInfo;
            try {
                packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore and try the next package
                continue;
            }
            if (packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param pi the {@link PackageInstaller} object to use
     * @param originatingUid the UID of the package performing a session based install
     * @param sessionId ID of the install session
     * @return {@code true} if the caller is the session owner
     */
    public static boolean isCallerSessionOwner(PackageInstaller pi, int originatingUid,
        int sessionId) {
        if (sessionId == SessionInfo.INVALID_ID) {
            return false;
        }
        if (originatingUid == Process.ROOT_UID) {
            return true;
        }
        PackageInstaller.SessionInfo sessionInfo = pi.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            return false;
        }
        int installerUid = sessionInfo.getInstallerUid();
        return originatingUid == installerUid;
    }

    /**
     * Generates a stub {@link PackageInfo} object for the given packageName
     */
    public static PackageInfo generateStubPackageInfo(String packageName) {
        final PackageInfo info = new PackageInfo();
        final ApplicationInfo aInfo = new ApplicationInfo();
        info.applicationInfo = aInfo;
        info.packageName = info.applicationInfo.packageName = packageName;
        return info;
    }

    /**
     * Generates an {@link AppSnippet} containing an appIcon and appLabel from the
     * {@link SessionInfo} object
     */
    public static AppSnippet getAppSnippet(Context context, SessionInfo info) {
        PackageManager pm = context.getPackageManager();
        CharSequence label = info.getAppLabel();
        Drawable icon = info.getAppIcon() != null ?
            new BitmapDrawable(context.getResources(), info.getAppIcon())
            : pm.getDefaultActivityIcon();
        return new AppSnippet(label, icon);
    }

    /**
     * Generates an {@link AppSnippet} containing an appIcon and appLabel from the
     * {@link PackageInfo} object
     */
    public static AppSnippet getAppSnippet(Context context, PackageInfo pkgInfo) {
        return getAppSnippet(context, pkgInfo.applicationInfo);
    }

    /**
     * Generates an {@link AppSnippet} containing an appIcon and appLabel from the
     * {@link ApplicationInfo} object
     */
    public static AppSnippet getAppSnippet(Context context, ApplicationInfo appInfo) {
        PackageManager pm = context.getPackageManager();
        CharSequence label = pm.getApplicationLabel(appInfo);
        Drawable icon = pm.getApplicationIcon(appInfo);
        return new AppSnippet(label, icon);
    }

    /**
     * Generates an {@link AppSnippet} containing an appIcon and appLabel from the
     * supplied APK file
     */
    public static AppSnippet getAppSnippet(Context context, ApplicationInfo appInfo,
        File sourceFile) {
        ApplicationInfo appInfoFromFile = processAppInfoForFile(appInfo, sourceFile);
        CharSequence label = getAppLabelFromFile(context, appInfoFromFile);
        Drawable icon = getAppIconFromFile(context, appInfoFromFile);
        return new AppSnippet(label, icon);
    }

    /**
     * Utility method to load application label
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    public static CharSequence getAppLabelFromFile(Context context, ApplicationInfo appInfo) {
        PackageManager pm = context.getPackageManager();
        CharSequence label = null;
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (appInfo.labelRes != 0) {
            try {
                label = appInfo.loadLabel(pm);
            } catch (Resources.NotFoundException e) {
            }
        }
        if (label == null) {
            label = (appInfo.nonLocalizedLabel != null) ?
                appInfo.nonLocalizedLabel : appInfo.packageName;
        }
        return label;
    }

    /**
     * Utility method to load application icon
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    public static Drawable getAppIconFromFile(Context context, ApplicationInfo appInfo) {
        PackageManager pm = context.getPackageManager();
        Drawable icon = null;
        // Try to load the icon from the package's resources. If an app has not explicitly
        // specified any resource, just use the default icon for now.
        try {
            if (appInfo.icon != 0) {
                try {
                    icon = appInfo.loadIcon(pm);
                } catch (Resources.NotFoundException e) {
                }
            }
            if (icon == null) {
                icon = context.getPackageManager().getDefaultActivityIcon();
            }
        } catch (OutOfMemoryError e) {
            Log.i(TAG, "Could not load app icon", e);
        }
        return icon;
    }

    private static ApplicationInfo processAppInfoForFile(ApplicationInfo appInfo, File sourceFile) {
        final String archiveFilePath = sourceFile.getAbsolutePath();
        appInfo.publicSourceDir = archiveFilePath;

        if (appInfo.splitNames != null && appInfo.splitSourceDirs == null) {
            final File[] files = sourceFile.getParentFile().listFiles();
            final String[] splits = Arrays.stream(appInfo.splitNames)
                .map(i -> findFilePath(files, i + ".apk"))
                .filter(Objects::nonNull)
                .toArray(String[]::new);

            appInfo.splitSourceDirs = splits;
            appInfo.splitPublicSourceDirs = splits;
        }
        return appInfo;
    }

    private static String findFilePath(File[] files, String postfix) {
        for (File file : files) {
            final String path = file.getAbsolutePath();
            if (path.endsWith(postfix)) {
                return path;
            }
        }
        return null;
    }

    /**
     * @return the packageName corresponding to a UID.
     */
    public static String getPackageNameForUid(Context context, int sourceUid,
        String callingPackage) {
        if (sourceUid == Process.INVALID_UID) {
            return null;
        }
        // If the sourceUid belongs to the system downloads provider, we explicitly return the
        // name of the Download Manager package. This is because its UID is shared with multiple
        // packages, resulting in uncertainty about which package will end up first in the list
        // of packages associated with this UID
        PackageManager pm = context.getPackageManager();
        ApplicationInfo systemDownloadProviderInfo = getSystemDownloadsProviderInfo(
            pm, sourceUid);
        if (systemDownloadProviderInfo != null) {
            return systemDownloadProviderInfo.packageName;
        }
        String[] packagesForUid = pm.getPackagesForUid(sourceUid);
        if (packagesForUid == null) {
            return null;
        }
        if (packagesForUid.length > 1) {
            if (callingPackage != null) {
                for (String packageName : packagesForUid) {
                    if (packageName.equals(callingPackage)) {
                        return packageName;
                    }
                }
            }
            Log.i(TAG, "Multiple packages found for source uid " + sourceUid);
        }
        return packagesForUid[0];
    }

    /**
     * Utility method to get package information for a given {@link File}
     */
    public static PackageInfo getPackageInfo(Context context, File sourceFile, int flags) {
        String filePath = sourceFile.getAbsolutePath();
        if (filePath.endsWith(SPLIT_BASE_APK_END_WITH)) {
            File dir = sourceFile.getParentFile();
            if (dir.listFiles().length > 1) {
                // split apks, use file directory to get archive info
                filePath = dir.getPath();
            }
        }
        try {
            return context.getPackageManager().getPackageArchiveInfo(filePath, flags);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The class to hold an incoming package's icon and label.
     * See {@link #getAppSnippet(Context, SessionInfo)},
     * {@link #getAppSnippet(Context, PackageInfo)},
     * {@link #getAppSnippet(Context, ApplicationInfo)},
     * {@link #getAppSnippet(Context, ApplicationInfo, File)}
     */
    public static class AppSnippet {

        private CharSequence mLabel;
        private Drawable mIcon;

        public AppSnippet(CharSequence label, Drawable icon) {
            mLabel = label;
            mIcon = icon;
        }

        public AppSnippet() {
        }

        public CharSequence getLabel() {
            return mLabel;
        }

        public void setLabel(CharSequence mLabel) {
            this.mLabel = mLabel;
        }

        public Drawable getIcon() {
            return mIcon;
        }

        public void setIcon(Drawable mIcon) {
            this.mIcon = mIcon;
        }
    }
}
