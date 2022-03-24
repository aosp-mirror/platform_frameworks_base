/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.privacy

import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.permission.PermissionGroupUsage
import android.permission.PermissionManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.internal.logging.UiEventLogger
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

private val defaultDialogProvider = object : PrivacyDialogController.DialogProvider {
    override fun makeDialog(
        context: Context,
        list: List<PrivacyDialog.PrivacyElement>,
        starter: (String, Int, CharSequence?, Intent?) -> Unit
    ): PrivacyDialog {
        return PrivacyDialog(context, list, starter)
    }
}

/**
 * Controller for [PrivacyDialog].
 *
 * This controller shows and dismissed the dialog, as well as determining the information to show in
 * it.
 */
@SysUISingleton
class PrivacyDialogController(
    private val permissionManager: PermissionManager,
    private val packageManager: PackageManager,
    private val privacyItemController: PrivacyItemController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val backgroundExecutor: Executor,
    private val uiExecutor: Executor,
    private val privacyLogger: PrivacyLogger,
    private val keyguardStateController: KeyguardStateController,
    private val appOpsController: AppOpsController,
    private val uiEventLogger: UiEventLogger,
    @VisibleForTesting private val dialogProvider: DialogProvider
) {

    @Inject
    constructor(
        permissionManager: PermissionManager,
        packageManager: PackageManager,
        privacyItemController: PrivacyItemController,
        userTracker: UserTracker,
        activityStarter: ActivityStarter,
        @Background backgroundExecutor: Executor,
        @Main uiExecutor: Executor,
        privacyLogger: PrivacyLogger,
        keyguardStateController: KeyguardStateController,
        appOpsController: AppOpsController,
        uiEventLogger: UiEventLogger
    ) : this(
            permissionManager,
            packageManager,
            privacyItemController,
            userTracker,
            activityStarter,
            backgroundExecutor,
            uiExecutor,
            privacyLogger,
            keyguardStateController,
            appOpsController,
            uiEventLogger,
            defaultDialogProvider
    )

    companion object {
        private const val TAG = "PrivacyDialogController"
    }

    private var dialog: Dialog? = null

    private val onDialogDismissed = object : PrivacyDialog.OnDialogDismissed {
        override fun onDialogDismissed() {
            privacyLogger.logPrivacyDialogDismissed()
            uiEventLogger.log(PrivacyDialogEvent.PRIVACY_DIALOG_DISMISSED)
            dialog = null
        }
    }

    @MainThread
    private fun startActivity(
        packageName: String,
        userId: Int,
        attributionTag: CharSequence?,
        navigationIntent: Intent?
    ) {
        val intent = if (navigationIntent == null) {
            getDefaultManageAppPermissionsIntent(packageName, userId)
        } else {
            navigationIntent
        }
        uiEventLogger.log(PrivacyDialogEvent.PRIVACY_DIALOG_ITEM_CLICKED_TO_APP_SETTINGS,
            userId, packageName)
        privacyLogger.logStartSettingsActivityFromDialog(packageName, userId)
        if (!keyguardStateController.isUnlocked) {
            // If we are locked, hide the dialog so the user can unlock
            dialog?.hide()
        }
        // startActivity calls internally startActivityDismissingKeyguard
        activityStarter.startActivity(intent, true) {
            if (ActivityManager.isStartResultSuccessful(it)) {
                dismissDialog()
            } else {
                dialog?.show()
            }
        }
    }

    @WorkerThread
    private fun getManagePermissionIntent(
        packageName: String,
        userId: Int,
        permGroupName: CharSequence,
        attributionTag: CharSequence?,
        isAttributionSupported: Boolean
    ): Intent
    {
        lateinit var intent: Intent
        if (attributionTag != null && isAttributionSupported) {
            intent = Intent(Intent.ACTION_MANAGE_PERMISSION_USAGE)
            intent.setPackage(packageName)
            intent.putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, permGroupName.toString())
            intent.putExtra(Intent.EXTRA_ATTRIBUTION_TAGS, arrayOf(attributionTag.toString()))
            intent.putExtra(Intent.EXTRA_SHOWING_ATTRIBUTION, true)
            val resolveInfo = packageManager.resolveActivity(
                    intent, PackageManager.ResolveInfoFlags.of(0))
            if (resolveInfo != null && resolveInfo.activityInfo != null &&
                    resolveInfo.activityInfo.permission ==
                    android.Manifest.permission.START_VIEW_PERMISSION_USAGE) {
                intent.component = ComponentName(packageName, resolveInfo.activityInfo.name)
                return intent
            }
        }
        return getDefaultManageAppPermissionsIntent(packageName, userId)
    }

    fun getDefaultManageAppPermissionsIntent(packageName: String, userId: Int): Intent {
        val intent = Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS)
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
        return intent
    }

    @WorkerThread
    private fun permGroupUsage(): List<PermissionGroupUsage> {
        return permissionManager.getIndicatorAppOpUsageData(appOpsController.isMicMuted)
    }

    /**
     * Show the [PrivacyDialog]
     *
     * This retrieves the permission usage from [PermissionManager] and creates a new
     * [PrivacyDialog] with a list of [PrivacyDialog.PrivacyElement] to show.
     *
     * This list will be filtered by [filterAndSelect]. Only types available by
     * [PrivacyItemController] will be shown.
     *
     * @param context A context to use to create the dialog.
     * @see filterAndSelect
     */
    fun showDialog(context: Context) {
        dismissDialog()
        backgroundExecutor.execute {
            val usage = permGroupUsage()
            val userInfos = userTracker.userProfiles
            privacyLogger.logUnfilteredPermGroupUsage(usage)
            val items = usage.mapNotNull {
                val type = filterType(permGroupToPrivacyType(it.permissionGroupName))
                val userInfo = userInfos.firstOrNull { ui -> ui.id == UserHandle.getUserId(it.uid) }
                if (userInfo != null || it.isPhoneCall) {
                    type?.let { t ->
                        // Only try to get the app name if we actually need it
                        val appName = if (it.isPhoneCall) {
                            ""
                        } else {
                            getLabelForPackage(it.packageName, it.uid)
                        }
                        val userId = UserHandle.getUserId(it.uid)
                        PrivacyDialog.PrivacyElement(
                                t,
                                it.packageName,
                                userId,
                                appName,
                                it.attributionTag,
                                it.attributionLabel,
                                it.proxyLabel,
                                it.lastAccessTimeMillis,
                                it.isActive,
                                // If there's no user info, we're in a phoneCall in secondary user
                                userInfo?.isManagedProfile ?: false,
                                it.isPhoneCall,
                                it.permissionGroupName,
                                getManagePermissionIntent(
                                        it.packageName,
                                        userId,
                                        it.permissionGroupName,
                                        it.attributionTag,
                                        // attributionLabel is set only when subattribution policies
                                        // are supported and satisfied
                                        it.attributionLabel != null
                                )
                        )
                    }
                } else {
                    // No matching user or phone call
                    null
                }
            }
            uiExecutor.execute {
                val elements = filterAndSelect(items)
                if (elements.isNotEmpty()) {
                    val d = dialogProvider.makeDialog(context, elements, this::startActivity)
                    d.setShowForAllUsers(true)
                    d.addOnDismissListener(onDialogDismissed)
                    d.show()
                    privacyLogger.logShowDialogContents(elements)
                    dialog = d
                } else {
                    Log.w(TAG, "Trying to show empty dialog")
                }
            }
        }
    }

    /**
     * Dismisses the dialog
     */
    fun dismissDialog() {
        dialog?.dismiss()
    }

    @WorkerThread
    private fun getLabelForPackage(packageName: String, uid: Int): CharSequence {
        return try {
            packageManager
                .getApplicationInfoAsUser(packageName, 0, UserHandle.getUserId(uid))
                .loadLabel(packageManager)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Label not found for: $packageName")
            packageName
        }
    }

    private fun permGroupToPrivacyType(group: String): PrivacyType? {
        return when (group) {
            Manifest.permission_group.CAMERA -> PrivacyType.TYPE_CAMERA
            Manifest.permission_group.MICROPHONE -> PrivacyType.TYPE_MICROPHONE
            Manifest.permission_group.LOCATION -> PrivacyType.TYPE_LOCATION
            else -> null
        }
    }

    private fun filterType(type: PrivacyType?): PrivacyType? {
        return type?.let {
            if ((it == PrivacyType.TYPE_CAMERA || it == PrivacyType.TYPE_MICROPHONE) &&
                privacyItemController.micCameraAvailable) {
                it
            } else if (it == PrivacyType.TYPE_LOCATION && privacyItemController.locationAvailable) {
                it
            } else {
                null
            }
        }
    }

    /**
     * Filters the list of elements to show.
     *
     * For each privacy type, it'll return all active elements. If there are no active elements,
     * it'll return the most recent access
     */
    private fun filterAndSelect(
        list: List<PrivacyDialog.PrivacyElement>
    ): List<PrivacyDialog.PrivacyElement> {
        return list.groupBy { it.type }.toSortedMap().flatMap { (_, elements) ->
            val actives = elements.filter { it.active }
            if (actives.isNotEmpty()) {
                actives.sortedByDescending { it.lastActiveTimestamp }
            } else {
                elements.maxByOrNull { it.lastActiveTimestamp }?.let {
                    listOf(it)
                } ?: emptyList()
            }
        }
    }

    /**
     * Interface to create a [PrivacyDialog].
     *
     * Can be used to inject a mock creator.
     */
    interface DialogProvider {
        /**
         * Create a [PrivacyDialog].
         */
        fun makeDialog(
            context: Context,
            list: List<PrivacyDialog.PrivacyElement>,
            starter: (String, Int, CharSequence?, Intent?) -> Unit
        ): PrivacyDialog
    }
}
