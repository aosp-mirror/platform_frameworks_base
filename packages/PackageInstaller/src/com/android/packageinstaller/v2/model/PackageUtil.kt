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

package com.android.packageinstaller.v2.model

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import java.io.File

object PackageUtil {
    private val LOG_TAG = InstallRepository::class.java.simpleName
    private const val DOWNLOADS_AUTHORITY = "downloads"
    private const val SPLIT_BASE_APK_END_WITH = "base.apk"

    /**
     * Determines if the UID belongs to the system downloads provider and returns the
     * [ApplicationInfo] of the provider
     *
     * @param uid UID of the caller
     * @return [ApplicationInfo] of the provider if a downloads provider exists, it is a
     * system app, and its UID matches with the passed UID, null otherwise.
     */
    private fun getSystemDownloadsProviderInfo(pm: PackageManager, uid: Int): ApplicationInfo? {
        // Check if there are currently enabled downloads provider on the system.
        val providerInfo = pm.resolveContentProvider(DOWNLOADS_AUTHORITY, 0)
            ?: return null
        val appInfo = providerInfo.applicationInfo
        return if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) && uid == appInfo.uid) {
            appInfo
        } else null
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    @JvmStatic
    fun getMaxTargetSdkVersionForUid(context: Context, uid: Int): Int {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        var targetSdkVersion = -1
        if (packages != null) {
            for (packageName in packages) {
                try {
                    val info = pm.getApplicationInfo(packageName!!, 0)
                    targetSdkVersion = maxOf(targetSdkVersion, info.targetSdkVersion)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion
    }

    @JvmStatic
    fun canPackageQuery(context: Context, callingUid: Int, packageUri: Uri): Boolean {
        val pm = context.packageManager
        val info = pm.resolveContentProvider(
            packageUri.authority!!,
            PackageManager.ComponentInfoFlags.of(0)
        ) ?: return false
        val targetPackage = info.packageName
        val callingPackages = pm.getPackagesForUid(callingUid) ?: return false
        for (callingPackage in callingPackages) {
            try {
                if (pm.canPackageQuery(callingPackage!!, targetPackage)) {
                    return true
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // no-op
            }
        }
        return false
    }

    /**
     * @param context the [Context] object
     * @param permission the permission name to check
     * @param callingUid the UID of the caller who's permission is being checked
     * @return `true` if the callingUid is granted the said permission
     */
    @JvmStatic
    fun isPermissionGranted(context: Context, permission: String, callingUid: Int): Boolean {
        return (context.checkPermission(permission, -1, callingUid)
            == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * @param pm the [PackageManager] object
     * @param permission the permission name to check
     * @param packageName the name of the package who's permission is being checked
     * @return `true` if the package is granted the said permission
     */
    @JvmStatic
    fun isPermissionGranted(pm: PackageManager, permission: String, packageName: String): Boolean {
        return pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * @param context the [Context] object
     * @param callingUid the UID of the caller who's permission is being checked
     * @param originatingUid the UID from where install is being originated. This could be same as
     * callingUid or it will be the UID of the package performing a session based install
     * @param isTrustedSource whether install request is coming from a privileged app or an app that
     * has [Manifest.permission.INSTALL_PACKAGES] permission granted
     * @return `true` if the package is granted the said permission
     */
    @JvmStatic
    fun isInstallPermissionGrantedOrRequested(
        context: Context,
        callingUid: Int,
        originatingUid: Int,
        isTrustedSource: Boolean,
    ): Boolean {
        val isDocumentsManager =
            isPermissionGranted(context, Manifest.permission.MANAGE_DOCUMENTS, callingUid)
        val isSystemDownloadsProvider =
            getSystemDownloadsProviderInfo(context.packageManager, callingUid) != null

        if (!isTrustedSource && !isSystemDownloadsProvider && !isDocumentsManager) {
            val targetSdkVersion = getMaxTargetSdkVersionForUid(context, originatingUid)
            if (targetSdkVersion < 0) {
                // Invalid originating uid supplied. Abort install.
                Log.w(LOG_TAG, "Cannot get target sdk version for uid $originatingUid")
                return false
            } else if (targetSdkVersion >= Build.VERSION_CODES.O
                && !isUidRequestingPermission(
                    context.packageManager, originatingUid,
                    Manifest.permission.REQUEST_INSTALL_PACKAGES
                )
            ) {
                Log.e(
                    LOG_TAG, "Requesting uid " + originatingUid + " needs to declare permission "
                        + Manifest.permission.REQUEST_INSTALL_PACKAGES
                )
                return false
            }
        }
        return true
    }

    /**
     * @param pm the [PackageManager] object
     * @param uid the UID of the caller who's permission is being checked
     * @param permission the permission name to check
     * @return `true` if the caller is requesting the said permission in its Manifest
     */
    private fun isUidRequestingPermission(
        pm: PackageManager,
        uid: Int,
        permission: String,
    ): Boolean {
        val packageNames = pm.getPackagesForUid(uid) ?: return false
        for (packageName in packageNames) {
            val packageInfo: PackageInfo = try {
                pm.getPackageInfo(packageName!!, PackageManager.GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                // Ignore and try the next package
                continue
            }
            if (packageInfo.requestedPermissions != null
                && listOf(*packageInfo.requestedPermissions!!).contains(permission)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * @param pi the [PackageInstaller] object to use
     * @param originatingUid the UID of the package performing a session based install
     * @param sessionId ID of the install session
     * @return `true` if the caller is the session owner
     */
    @JvmStatic
    fun isCallerSessionOwner(pi: PackageInstaller, originatingUid: Int, sessionId: Int): Boolean {
        if (originatingUid == Process.ROOT_UID) {
            return true
        }
        val sessionInfo = pi.getSessionInfo(sessionId) ?: return false
        val installerUid = sessionInfo.getInstallerUid()
        return originatingUid == installerUid
    }

    /**
     * Generates a stub [PackageInfo] object for the given packageName
     */
    @JvmStatic
    fun generateStubPackageInfo(packageName: String?): PackageInfo {
        val info = PackageInfo()
        val aInfo = ApplicationInfo()
        info.applicationInfo = aInfo
        info.applicationInfo!!.packageName = packageName
        info.packageName = info.applicationInfo!!.packageName
        return info
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [PackageInstaller.SessionInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, info: PackageInstaller.SessionInfo): AppSnippet {
        val pm = context.packageManager
        val label = info.getAppLabel()
        val icon = if (info.getAppIcon() != null) BitmapDrawable(
            context.resources,
            info.getAppIcon()
        ) else pm.defaultActivityIcon
        return AppSnippet(label, icon)
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [PackageInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, pkgInfo: PackageInfo): AppSnippet {
        return pkgInfo.applicationInfo?.let { getAppSnippet(context, it) } ?: run {
            AppSnippet(pkgInfo.packageName, context.packageManager.defaultActivityIcon)
        }
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [ApplicationInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, appInfo: ApplicationInfo): AppSnippet {
        val pm = context.packageManager
        val label = pm.getApplicationLabel(appInfo)
        val icon = pm.getApplicationIcon(appInfo)
        return AppSnippet(label, icon)
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * supplied APK file
     */
    @JvmStatic
    fun getAppSnippet(context: Context, pkgInfo: PackageInfo, sourceFile: File): AppSnippet {
        pkgInfo.applicationInfo?.let {
            val appInfoFromFile = processAppInfoForFile(it, sourceFile)
            val label = getAppLabelFromFile(context, appInfoFromFile)
            val icon = getAppIconFromFile(context, appInfoFromFile)
            return AppSnippet(label, icon)
        } ?: run {
            return AppSnippet(pkgInfo.packageName, context.packageManager.defaultActivityIcon)
        }
    }

    /**
     * Utility method to load application label
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    private fun getAppLabelFromFile(context: Context, appInfo: ApplicationInfo): CharSequence? {
        val pm = context.packageManager
        var label: CharSequence? = null
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (appInfo.labelRes != 0) {
            try {
                label = appInfo.loadLabel(pm)
            } catch (e: Resources.NotFoundException) {
            }
        }
        if (label == null) {
            label = if (appInfo.nonLocalizedLabel != null) appInfo.nonLocalizedLabel
            else appInfo.packageName
        }
        return label
    }

    /**
     * Utility method to load application icon
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    private fun getAppIconFromFile(context: Context, appInfo: ApplicationInfo): Drawable? {
        val pm = context.packageManager
        var icon: Drawable? = null
        // Try to load the icon from the package's resources. If an app has not explicitly
        // specified any resource, just use the default icon for now.
        try {
            if (appInfo.icon != 0) {
                try {
                    icon = appInfo.loadIcon(pm)
                } catch (e: Resources.NotFoundException) {
                }
            }
            if (icon == null) {
                icon = context.packageManager.defaultActivityIcon
            }
        } catch (e: OutOfMemoryError) {
            Log.i(LOG_TAG, "Could not load app icon", e)
        }
        return icon
    }

    private fun processAppInfoForFile(appInfo: ApplicationInfo, sourceFile: File): ApplicationInfo {
        val archiveFilePath = sourceFile.absolutePath
        appInfo.publicSourceDir = archiveFilePath
        if (appInfo.splitNames != null && appInfo.splitSourceDirs == null) {
            val files = sourceFile.parentFile?.listFiles()
            val splits = appInfo.splitNames!!
                .mapNotNull { findFilePath(files, "$it.apk") }
                .toTypedArray()

            appInfo.splitSourceDirs = splits
            appInfo.splitPublicSourceDirs = splits
        }
        return appInfo
    }

    private fun findFilePath(files: Array<File>?, postfix: String): String? {
        files?.let {
            for (file in it) {
                val path = file.absolutePath
                if (path.endsWith(postfix)) {
                    return path
                }
            }
        }
        return null
    }

    /**
     * @return the packageName corresponding to a UID.
     */
    @JvmStatic
    fun getPackageNameForUid(context: Context, sourceUid: Int, callingPackage: String?): String? {
        if (sourceUid == Process.INVALID_UID) {
            return null
        }
        // If the sourceUid belongs to the system downloads provider, we explicitly return the
        // name of the Download Manager package. This is because its UID is shared with multiple
        // packages, resulting in uncertainty about which package will end up first in the list
        // of packages associated with this UID
        val pm = context.packageManager
        val systemDownloadProviderInfo = getSystemDownloadsProviderInfo(pm, sourceUid)
        if (systemDownloadProviderInfo != null) {
            return systemDownloadProviderInfo.packageName
        }
        val packagesForUid = pm.getPackagesForUid(sourceUid) ?: return null
        if (packagesForUid.size > 1) {
            if (callingPackage != null) {
                for (packageName in packagesForUid) {
                    if (packageName == callingPackage) {
                        return packageName
                    }
                }
            }
            Log.i(LOG_TAG, "Multiple packages found for source uid $sourceUid")
        }
        return packagesForUid[0]
    }

    /**
     * Utility method to get package information for a given [File]
     */
    @JvmStatic
    fun getPackageInfo(context: Context, sourceFile: File, flags: Int): PackageInfo? {
        var filePath = sourceFile.absolutePath
        if (filePath.endsWith(SPLIT_BASE_APK_END_WITH)) {
            val dir = sourceFile.parentFile
            if ((dir?.listFiles()?.size ?: 0) > 1) {
                // split apks, use file directory to get archive info
                filePath = dir.path
            }
        }
        return try {
            context.packageManager.getPackageArchiveInfo(filePath, flags)
        } catch (ignored: Exception) {
            null
        }
    }

    /**
     * Is a profile part of a user?
     *
     * @param userManager The user manager
     * @param userHandle The handle of the user
     * @param profileHandle The handle of the profile
     *
     * @return If the profile is part of the user or the profile parent of the user
     */
    @JvmStatic
    fun isProfileOfOrSame(
        userManager: UserManager,
        userHandle: UserHandle,
        profileHandle: UserHandle?,
    ): Boolean {
        if (profileHandle == null) {
            return false
        }
        return if (userHandle == profileHandle) {
            true
        } else userManager.getProfileParent(profileHandle) != null
            && userManager.getProfileParent(profileHandle) == userHandle
    }

    /**
     * The class to hold an incoming package's icon and label.
     * See [getAppSnippet]
     */
    data class AppSnippet(var label: CharSequence?, var icon: Drawable?)
}
