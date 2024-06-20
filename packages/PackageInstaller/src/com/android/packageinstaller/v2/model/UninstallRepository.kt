/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Flags
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.android.packageinstaller.R
import com.android.packageinstaller.common.EventResultPersister
import com.android.packageinstaller.common.EventResultPersister.OutOfIdsException
import com.android.packageinstaller.common.UninstallEventReceiver
import com.android.packageinstaller.v2.model.PackageUtil.getMaxTargetSdkVersionForUid
import com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid
import com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted
import com.android.packageinstaller.v2.model.PackageUtil.isProfileOfOrSame

class UninstallRepository(private val context: Context) {

    private val appOpsManager: AppOpsManager? = context.getSystemService(AppOpsManager::class.java)
    private val packageManager: PackageManager = context.packageManager
    private val userManager: UserManager? = context.getSystemService(UserManager::class.java)
    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
    val uninstallResult = MutableLiveData<UninstallStage?>()
    private var uninstalledUser: UserHandle? = null
    private var callback: PackageManager.UninstallCompleteCallback? = null
    private var targetAppInfo: ApplicationInfo? = null
    private var targetActivityInfo: ActivityInfo? = null
    private lateinit var intent: Intent
    private lateinit var targetAppLabel: CharSequence
    private var targetPackageName: String? = null
    private var callingActivity: String? = null
    private var uninstallFromAllUsers = false
    private var isClonedApp = false
    private var uninstallId = 0

    fun performPreUninstallChecks(intent: Intent, callerInfo: CallerInfo): UninstallStage {
        this.intent = intent

        val callingUid = callerInfo.uid
        callingActivity = callerInfo.activityName

        if (callingUid == Process.INVALID_UID) {
            Log.e(LOG_TAG, "Could not determine the launching uid.")
            return UninstallAborted(UninstallAborted.ABORT_REASON_GENERIC_ERROR)
            // TODO: should we give any indication to the user?
        }

        val callingPackage = getPackageNameForUid(context, callingUid, null)
        if (callingPackage == null) {
            Log.e(LOG_TAG, "Package not found for originating uid $callingUid")
            return UninstallAborted(UninstallAborted.ABORT_REASON_GENERIC_ERROR)
        } else {
            if (appOpsManager!!.noteOpNoThrow(
                    AppOpsManager.OPSTR_REQUEST_DELETE_PACKAGES, callingUid, callingPackage
                ) != AppOpsManager.MODE_ALLOWED
            ) {
                Log.e(LOG_TAG, "Install from uid $callingUid disallowed by AppOps")
                return UninstallAborted(UninstallAborted.ABORT_REASON_GENERIC_ERROR)
            }
        }

        if (getMaxTargetSdkVersionForUid(context, callingUid) >= Build.VERSION_CODES.P &&
            !isPermissionGranted(
                context, Manifest.permission.REQUEST_DELETE_PACKAGES, callingUid
            ) &&
            !isPermissionGranted(context, Manifest.permission.DELETE_PACKAGES, callingUid)
        ) {
            Log.e(
                LOG_TAG,
                "Uid " + callingUid + " does not have " +
                    Manifest.permission.REQUEST_DELETE_PACKAGES + " or " +
                    Manifest.permission.DELETE_PACKAGES
            )
            return UninstallAborted(UninstallAborted.ABORT_REASON_GENERIC_ERROR)
        }

        // Get intent information.
        // We expect an intent with URI of the form package:<packageName>#<className>
        // className is optional; if specified, it is the activity the user chose to uninstall
        val packageUri = intent.data
        if (packageUri == null) {
            Log.e(LOG_TAG, "No package URI in intent")
            return UninstallAborted(UninstallAborted.ABORT_REASON_APP_UNAVAILABLE)
        }
        targetPackageName = packageUri.encodedSchemeSpecificPart
        if (targetPackageName == null) {
            Log.e(LOG_TAG, "Invalid package name in URI: $packageUri")
            return UninstallAborted(UninstallAborted.ABORT_REASON_APP_UNAVAILABLE)
        }

        uninstallFromAllUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, false)
        if (uninstallFromAllUsers && !userManager!!.isAdminUser) {
            Log.e(LOG_TAG, "Only admin user can request uninstall for all users")
            return UninstallAborted(UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED)
        }

        uninstalledUser = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        if (uninstalledUser == null) {
            uninstalledUser = Process.myUserHandle()
        } else {
            val profiles = userManager!!.userProfiles
            if (!profiles.contains(uninstalledUser)) {
                Log.e(
                    LOG_TAG,
                    "User " + Process.myUserHandle() + " can't request uninstall " +
                        "for user " + uninstalledUser
                )
                return UninstallAborted(UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED)
            }
        }

        callback = intent.getParcelableExtra(
            PackageInstaller.EXTRA_CALLBACK, PackageManager.UninstallCompleteCallback::class.java
        )

        try {
            targetAppInfo = packageManager.getApplicationInfo(
                targetPackageName!!,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ANY_USER.toLong())
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG, "Unable to get packageName")
        }

        if (targetAppInfo == null) {
            Log.e(LOG_TAG, "Invalid packageName: $targetPackageName")
            return UninstallAborted(UninstallAborted.ABORT_REASON_APP_UNAVAILABLE)
        }

        // The class name may have been specified (e.g. when deleting an app from all apps)
        val className = packageUri.fragment
        if (className != null) {
            try {
                targetActivityInfo = packageManager.getActivityInfo(
                    ComponentName(targetPackageName!!, className),
                    PackageManager.ComponentInfoFlags.of(0)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG, "Unable to get className")
                // Continue as the ActivityInfo isn't critical.
            }
        }

        return UninstallReady()
    }

    fun generateUninstallDetails(): UninstallStage {
        val messageBuilder = StringBuilder()

        targetAppLabel = targetAppInfo!!.loadSafeLabel(packageManager)

        // If the Activity label differs from the App label, then make sure the user
        // knows the Activity belongs to the App being uninstalled.
        if (targetActivityInfo != null) {
            val activityLabel = targetActivityInfo!!.loadSafeLabel(packageManager)
            if (!activityLabel.contentEquals(targetAppLabel)) {
                messageBuilder.append(
                    context.getString(R.string.uninstall_activity_text, activityLabel)
                )
                messageBuilder.append(" ").append(targetAppLabel).append(".\n\n")
            }
        }

        val isUpdate = (targetAppInfo!!.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val myUserHandle = Process.myUserHandle()
        val isSingleUser = isSingleUser()

        if (isUpdate) {
            messageBuilder.append(
                context.getString(
                    if (isSingleUser) {
                        R.string.uninstall_update_text
                    } else {
                        R.string.uninstall_update_text_multiuser
                    }
                )
            )
        } else if (uninstallFromAllUsers && !isSingleUser) {
            messageBuilder.append(context.getString(R.string.uninstall_application_text_all_users))
        } else if (uninstalledUser != myUserHandle) {
            // Uninstalling user is issuing uninstall for another user
            val customUserManager = context.createContextAsUser(uninstalledUser!!, 0)
                .getSystemService(UserManager::class.java)
            val userName = customUserManager!!.userName
            var messageString = context.getString(
                    R.string.uninstall_application_text_user,
                userName
            )
            if (userManager!!.isSameProfileGroup(myUserHandle, uninstalledUser!!)) {
                if (customUserManager.isManagedProfile) {
                    messageString = context.getString(
                            R.string.uninstall_application_text_current_user_work_profile, userName
                    )
                } else if (customUserManager.isCloneProfile){
                    isClonedApp = true
                    messageString = context.getString(
                            R.string.uninstall_application_text_current_user_clone_profile
                    )
                } else if (Flags.allowPrivateProfile()
                        && android.multiuser.Flags.enablePrivateSpaceFeatures()
                        && customUserManager.isPrivateProfile
                ) {
                    // TODO(b/324244123): Get these Strings from a User Property API.
                    messageString = context.getString(
                            R.string.uninstall_application_text_current_user_private_profile
                    )
                }
            }
            messageBuilder.append(messageString)
        } else if (isCloneProfile(uninstalledUser!!)) {
            isClonedApp = true
            messageBuilder.append(
                context.getString(
                    R.string.uninstall_application_text_current_user_clone_profile
                )
            )
        } else if (myUserHandle == UserHandle.SYSTEM &&
            hasClonedInstance(targetAppInfo!!.packageName)
        ) {
            messageBuilder.append(
                context.getString(
                    R.string.uninstall_application_text_with_clone_instance,
                    targetAppLabel
                )
            )
        } else {
            messageBuilder.append(context.getString(R.string.uninstall_application_text))
        }

        val message = messageBuilder.toString()

        val title = if (isClonedApp) {
            context.getString(R.string.cloned_app_label, targetAppLabel)
        } else {
            targetAppLabel.toString()
        }

        var suggestToKeepAppData = false
        try {
            val pkgInfo = packageManager.getPackageInfo(targetPackageName!!, 0)
            suggestToKeepAppData =
                pkgInfo.applicationInfo != null && pkgInfo.applicationInfo!!.hasFragileUserData()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG, "Cannot check hasFragileUserData for $targetPackageName", e)
        }

        var appDataSize: Long = 0
        if (suggestToKeepAppData) {
            appDataSize = getAppDataSize(
                targetPackageName!!,
                if (uninstallFromAllUsers) null else uninstalledUser
            )
        }

        return UninstallUserActionRequired(title, message, appDataSize)
    }

    /**
     * Returns whether there is only one "full" user on this device.
     *
     * **Note:** On devices that use [headless system user mode]
     * [android.os.UserManager.isHeadlessSystemUserMode], the system user is not "full",
     * so it's not be considered in the calculation.
     */
    private fun isSingleUser(): Boolean {
        val userCount = userManager!!.userCount
        return userCount == 1 || (UserManager.isHeadlessSystemUserMode() && userCount == 2)
    }

    private fun hasClonedInstance(packageName: String): Boolean {
        // Check if clone user is present on the device.
        var cloneUser: UserHandle? = null
        val profiles = userManager!!.userProfiles

        for (userHandle in profiles) {
            if (userHandle != UserHandle.SYSTEM && isCloneProfile(userHandle)) {
                cloneUser = userHandle
                break
            }
        }
        // Check if another instance of given package exists in clone user profile.
        return try {
            cloneUser != null &&
                packageManager.getPackageUidAsUser(
                packageName, PackageManager.PackageInfoFlags.of(0), cloneUser.identifier
                ) > 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isCloneProfile(userHandle: UserHandle): Boolean {
        val customUserManager = context.createContextAsUser(userHandle, 0)
            .getSystemService(UserManager::class.java)
        return customUserManager!!.isUserOfType(UserManager.USER_TYPE_PROFILE_CLONE)
    }

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to or `null` if files of all users should
     * be counted.
     * @return The number of bytes.
     */
    private fun getAppDataSize(pkg: String, user: UserHandle?): Long {
        if (user != null) {
            return getAppDataSizeForUser(pkg, user)
        }
        // We are uninstalling from all users. Get cumulative app data size for all users.
        val userHandles = userManager!!.getUserHandles(true)
        var totalAppDataSize: Long = 0
        val numUsers = userHandles.size
        for (i in 0 until numUsers) {
            totalAppDataSize += getAppDataSizeForUser(pkg, userHandles[i])
        }
        return totalAppDataSize
    }

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to
     * @return The number of bytes.
     */
    private fun getAppDataSizeForUser(pkg: String, user: UserHandle): Long {
        val storageStatsManager = context.getSystemService(StorageStatsManager::class.java)
        try {
            val stats = storageStatsManager!!.queryStatsForPackage(
                packageManager.getApplicationInfo(pkg, 0).storageUuid,
                pkg,
                user
            )
            return stats.getDataBytes()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Cannot determine amount of app data for $pkg", e)
        }
        return 0
    }

    fun initiateUninstall(keepData: Boolean) {
        // Get an uninstallId to track results and show a notification on non-TV devices.
        uninstallId = try {
            UninstallEventReceiver.addObserver(
                context, EventResultPersister.GENERATE_NEW_ID, this::handleUninstallResult
            )
        } catch (e: OutOfIdsException) {
            Log.e(LOG_TAG, "Failed to start uninstall", e)
            handleUninstallResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager.DELETE_FAILED_INTERNAL_ERROR, null, 0
            )
            return
        }

        // TODO: Check with UX whether to show UninstallUninstalling dialog / notification?
        uninstallResult.value = UninstallUninstalling(targetAppLabel, isClonedApp)

        val uninstallData = Bundle()
        uninstallData.putInt(EXTRA_UNINSTALL_ID, uninstallId)
        uninstallData.putString(EXTRA_PACKAGE_NAME, targetPackageName)
        uninstallData.putBoolean(Intent.EXTRA_UNINSTALL_ALL_USERS, uninstallFromAllUsers)
        uninstallData.putCharSequence(EXTRA_APP_LABEL, targetAppLabel)
        uninstallData.putBoolean(EXTRA_IS_CLONE_APP, isClonedApp)
        uninstallData.putInt(EXTRA_TARGET_USER_ID, uninstalledUser!!.identifier)
        Log.i(LOG_TAG, "Uninstalling extras = $uninstallData")

        // Get a PendingIntent for result broadcast and issue an uninstall request
        val broadcastIntent = Intent(BROADCAST_ACTION)
        broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, uninstallId)
        broadcastIntent.setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            uninstallId,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        if (!startUninstall(
                targetPackageName!!,
                uninstalledUser!!,
                pendingIntent,
                uninstallFromAllUsers,
                keepData
            )
        ) {
            handleUninstallResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager.DELETE_FAILED_INTERNAL_ERROR,
                null,
                0
            )
        }
    }

    private fun handleUninstallResult(
        status: Int,
        legacyStatus: Int,
        message: String?,
        serviceId: Int
    ) {
        if (callback != null) {
            // The caller will be informed about the result via a callback
            callback!!.onUninstallComplete(targetPackageName!!, legacyStatus, message)

            // Since the caller already received the results, just finish the app at this point
            uninstallResult.value = null
            return
        }
        val returnResult = intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)
        if (returnResult || callingActivity != null) {
            val intent = Intent()
            intent.putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus)
            if (status == PackageInstaller.STATUS_SUCCESS) {
                uninstallResult.setValue(
                    UninstallSuccess(resultIntent = intent, activityResultCode = Activity.RESULT_OK)
                )
            } else {
                uninstallResult.setValue(
                    UninstallFailed(
                        returnResult = true,
                        resultIntent = intent,
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                )
            }
            return
        }

        // Caller did not want the result back. So, we either show a Toast, or a Notification.
        if (status == PackageInstaller.STATUS_SUCCESS) {
            val statusMessage = if (isClonedApp) {
                context.getString(
                R.string.uninstall_done_clone_app,
                    targetAppLabel
            )
            } else {
                context.getString(R.string.uninstall_done_app, targetAppLabel)
            }
            uninstallResult.setValue(
                UninstallSuccess(activityResultCode = legacyStatus, message = statusMessage)
            )
        } else {
            val uninstallFailureChannel = NotificationChannel(
                UNINSTALL_FAILURE_CHANNEL,
                context.getString(R.string.uninstall_failure_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager!!.createNotificationChannel(uninstallFailureChannel)

            val uninstallFailedNotification: Notification.Builder =
                Notification.Builder(context, UNINSTALL_FAILURE_CHANNEL)

            val myUserHandle = Process.myUserHandle()
            when (legacyStatus) {
                PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER -> {
                    // Find out if the package is an active admin for some non-current user.
                    val otherBlockingUserHandle =
                        findUserOfDeviceAdmin(myUserHandle, targetPackageName!!)
                    if (otherBlockingUserHandle == null) {
                        Log.d(
                            LOG_TAG,
                            "Uninstall failed because $targetPackageName" +
                                " is a device admin"
                        )
                        addDeviceManagerButton(context, uninstallFailedNotification)
                        setBigText(
                            uninstallFailedNotification,
                            context.getString(
                                R.string.uninstall_failed_device_policy_manager
                            )
                        )
                    } else {
                        Log.d(
                            LOG_TAG,
                            "Uninstall failed because $targetPackageName" +
                                " is a device admin of user $otherBlockingUserHandle"
                        )
                        val userName = context.createContextAsUser(otherBlockingUserHandle, 0)
                            .getSystemService(UserManager::class.java)!!.userName
                        setBigText(
                            uninstallFailedNotification,
                            String.format(
                                context.getString(
                                    R.string.uninstall_failed_device_policy_manager_of_user
                                ),
                                userName
                            )
                        )
                    }
                }

                PackageManager.DELETE_FAILED_OWNER_BLOCKED -> {
                    val otherBlockingUserHandle = findBlockingUser(targetPackageName!!)
                    val isProfileOfOrSame = isProfileOfOrSame(
                        userManager!!,
                        myUserHandle,
                        otherBlockingUserHandle
                    )
                    if (isProfileOfOrSame) {
                        addDeviceManagerButton(context, uninstallFailedNotification)
                    } else {
                        addManageUsersButton(context, uninstallFailedNotification)
                    }
                    var bigText: String? = null
                    if (otherBlockingUserHandle == null) {
                        Log.d(
                            LOG_TAG,
                            "Uninstall failed for $targetPackageName " +
                                "with code $status no blocking user"
                        )
                    } else if (otherBlockingUserHandle === UserHandle.SYSTEM) {
                        bigText = context.getString(R.string.uninstall_blocked_device_owner)
                    } else {
                        bigText = context.getString(
                            if (uninstallFromAllUsers) {
                                R.string.uninstall_all_blocked_profile_owner
                            } else {
                                R.string.uninstall_blocked_profile_owner
                            }
                        )
                    }
                    bigText?.let { setBigText(uninstallFailedNotification, it) }
                }

                else -> {
                    Log.d(
                        LOG_TAG,
                        "Uninstall blocked for $targetPackageName" +
                            " with legacy code $legacyStatus"
                    )
                }
            }
            uninstallFailedNotification.setContentTitle(
                context.getString(R.string.uninstall_failed_app, targetAppLabel)
            )
            uninstallFailedNotification.setOngoing(false)
            uninstallFailedNotification.setSmallIcon(R.drawable.ic_error)

            uninstallResult.setValue(
                UninstallFailed(
                    returnResult = false,
                    uninstallNotificationId = uninstallId,
                    uninstallNotification = uninstallFailedNotification.build()
                )
            )
        }
    }

    /**
     * @param myUserHandle [UserHandle] of the current user.
     * @param packageName Name of the package being uninstalled.
     * @return the [UserHandle] of the user in which a package is a device admin.
     */
    private fun findUserOfDeviceAdmin(myUserHandle: UserHandle, packageName: String): UserHandle? {
        for (otherUserHandle in userManager!!.getUserHandles(true)) {
            // We only catch the case when the user in question is neither the
            // current user nor its profile.
            if (isProfileOfOrSame(userManager, myUserHandle, otherUserHandle)) {
                continue
            }
            val dpm = context.createContextAsUser(otherUserHandle, 0)
                .getSystemService(DevicePolicyManager::class.java)
            if (dpm!!.packageHasActiveAdmins(packageName)) {
                return otherUserHandle
            }
        }
        return null
    }

    /**
     *
     * @param packageName Name of the package being uninstalled.
     * @return [UserHandle] of the user in which a package is blocked from being uninstalled.
     */
    private fun findBlockingUser(packageName: String): UserHandle? {
        for (otherUserHandle in userManager!!.getUserHandles(true)) {
            // TODO (b/307399586): Add a negation when the logic of the method is fixed
            if (packageManager.canUserUninstall(packageName, otherUserHandle)) {
                return otherUserHandle
            }
        }
        return null
    }

    /**
     * Set big text for the notification.
     *
     * @param builder The builder of the notification
     * @param text The text to set.
     */
    private fun setBigText(
        builder: Notification.Builder,
        text: CharSequence
    ) {
        builder.setStyle(Notification.BigTextStyle().bigText(text))
    }

    /**
     * Add a button to the notification that links to the user management.
     *
     * @param context The context the notification is created in
     * @param builder The builder of the notification
     */
    private fun addManageUsersButton(
        context: Context,
        builder: Notification.Builder
    ) {
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_settings_multiuser),
                context.getString(R.string.manage_users),
                PendingIntent.getActivity(
                    context,
                    0,
                    getUserSettingsIntent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
                .build()
        )
    }

    private fun getUserSettingsIntent(): Intent {
        val intent = Intent(Settings.ACTION_USER_SETTINGS)
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Add a button to the notification that links to the device policy management.
     *
     * @param context The context the notification is created in
     * @param builder The builder of the notification
     */
    private fun addDeviceManagerButton(
        context: Context,
        builder: Notification.Builder
    ) {
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_lock),
                context.getString(R.string.manage_device_administrators),
                PendingIntent.getActivity(
                    context,
                    0,
                    getDeviceManagerIntent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
                .build()
        )
    }

    private fun getDeviceManagerIntent(): Intent {
        val intent = Intent()
        intent.setClassName(
            "com.android.settings",
            "com.android.settings.Settings\$DeviceAdminSettingsActivity"
        )
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Starts an uninstall for the given package.
     *
     * @return `true` if there was no exception while uninstalling. This does not represent
     * the result of the uninstall. Result will be made available in [handleUninstallResult]
     */
    private fun startUninstall(
        packageName: String,
        targetUser: UserHandle,
        pendingIntent: PendingIntent,
        uninstallFromAllUsers: Boolean,
        keepData: Boolean
    ): Boolean {
        var flags = if (uninstallFromAllUsers) PackageManager.DELETE_ALL_USERS else 0
        flags = flags or if (keepData) PackageManager.DELETE_KEEP_DATA else 0

        return try {
            context.createContextAsUser(targetUser, 0)
                .packageManager.packageInstaller.uninstall(
                    VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    flags,
                    pendingIntent.intentSender
                )
            true
        } catch (e: IllegalArgumentException) {
            Log.e(LOG_TAG, "Failed to uninstall", e)
            false
        }
    }

    fun cancelUninstall() {
        if (callback != null) {
            callback!!.onUninstallComplete(
                targetPackageName!!,
                PackageManager.DELETE_FAILED_ABORTED,
                "Cancelled by user"
            )
        }
    }

    companion object {
        private val LOG_TAG = UninstallRepository::class.java.simpleName
        private const val UNINSTALL_FAILURE_CHANNEL = "uninstall_failure"
        private const val BROADCAST_ACTION = "com.android.packageinstaller.ACTION_UNINSTALL_COMMIT"
        private const val EXTRA_UNINSTALL_ID = "com.android.packageinstaller.extra.UNINSTALL_ID"
        private const val EXTRA_APP_LABEL = "com.android.packageinstaller.extra.APP_LABEL"
        private const val EXTRA_IS_CLONE_APP = "com.android.packageinstaller.extra.IS_CLONE_APP"
        private const val EXTRA_PACKAGE_NAME =
            "com.android.packageinstaller.extra.EXTRA_PACKAGE_NAME"
        private const val EXTRA_TARGET_USER_ID = "EXTRA_TARGET_USER_ID"
    }

    class CallerInfo(val activityName: String?, val uid: Int)
}
