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

package com.android.systemui.privacy

import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.UserHandle
import android.permission.PermissionGroupUsage
import android.permission.PermissionManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.view.isVisible
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogTransitionAnimator
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

private val defaultDialogProvider =
    object : PrivacyDialogControllerV2.DialogProvider {
        override fun makeDialog(
            context: Context,
            list: List<PrivacyDialogV2.PrivacyElement>,
            manageApp: (String, Int, Intent) -> Unit,
            closeApp: (String, Int) -> Unit,
            openPrivacyDashboard: () -> Unit
        ): PrivacyDialogV2 {
            return PrivacyDialogV2(context, list, manageApp, closeApp, openPrivacyDashboard)
        }
    }

/**
 * Controller for [PrivacyDialogV2].
 *
 * This controller shows and dismissed the dialog, as well as determining the information to show in
 * it.
 */
@SysUISingleton
class PrivacyDialogControllerV2(
    private val permissionManager: PermissionManager,
    private val packageManager: PackageManager,
    private val locationManager: LocationManager,
    private val privacyItemController: PrivacyItemController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val backgroundExecutor: Executor,
    private val uiExecutor: Executor,
    private val privacyLogger: PrivacyLogger,
    private val keyguardStateController: KeyguardStateController,
    private val appOpsController: AppOpsController,
    private val uiEventLogger: UiEventLogger,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogProvider: DialogProvider
) {

    @Inject
    constructor(
        permissionManager: PermissionManager,
        packageManager: PackageManager,
        locationManager: LocationManager,
        privacyItemController: PrivacyItemController,
        userTracker: UserTracker,
        activityStarter: ActivityStarter,
        @Background backgroundExecutor: Executor,
        @Main uiExecutor: Executor,
        privacyLogger: PrivacyLogger,
        keyguardStateController: KeyguardStateController,
        appOpsController: AppOpsController,
        uiEventLogger: UiEventLogger,
        dialogTransitionAnimator: DialogTransitionAnimator
    ) : this(
        permissionManager,
        packageManager,
        locationManager,
        privacyItemController,
        userTracker,
        activityStarter,
        backgroundExecutor,
        uiExecutor,
        privacyLogger,
        keyguardStateController,
        appOpsController,
        uiEventLogger,
        dialogTransitionAnimator,
        defaultDialogProvider
    )

    private var dialog: Dialog? = null

    private val onDialogDismissed =
        object : PrivacyDialogV2.OnDialogDismissed {
            override fun onDialogDismissed() {
                privacyLogger.logPrivacyDialogDismissed()
                uiEventLogger.log(PrivacyDialogEvent.PRIVACY_DIALOG_DISMISSED)
                dialog = null
            }
        }

    @WorkerThread
    private fun closeApp(packageName: String, userId: Int) {
        uiEventLogger.log(
            PrivacyDialogEvent.PRIVACY_DIALOG_ITEM_CLICKED_TO_CLOSE_APP,
            userId,
            packageName
        )
        privacyLogger.logCloseAppFromDialog(packageName, userId)
        ActivityManager.getService().stopAppForUser(packageName, userId)
    }

    @MainThread
    private fun manageApp(packageName: String, userId: Int, navigationIntent: Intent) {
        uiEventLogger.log(
            PrivacyDialogEvent.PRIVACY_DIALOG_ITEM_CLICKED_TO_APP_SETTINGS,
            userId,
            packageName
        )
        privacyLogger.logStartSettingsActivityFromDialog(packageName, userId)
        startActivity(navigationIntent)
    }

    @MainThread
    private fun openPrivacyDashboard() {
        uiEventLogger.log(PrivacyDialogEvent.PRIVACY_DIALOG_CLICK_TO_PRIVACY_DASHBOARD)
        privacyLogger.logStartPrivacyDashboardFromDialog()
        startActivity(Intent(Intent.ACTION_REVIEW_PERMISSION_USAGE))
    }

    @MainThread
    private fun startActivity(navigationIntent: Intent) {
        if (!keyguardStateController.isUnlocked) {
            // If we are locked, hide the dialog so the user can unlock
            dialog?.hide()
        }
        // startActivity calls internally startActivityDismissingKeyguard
        activityStarter.startActivity(navigationIntent, true) {
            if (ActivityManager.isStartResultSuccessful(it)) {
                dismissDialog()
            } else {
                dialog?.show()
            }
        }
    }

    @WorkerThread
    private fun getStartViewPermissionUsageIntent(
        context: Context,
        packageName: String,
        permGroupName: String,
        attributionTag: CharSequence?,
        isAttributionSupported: Boolean
    ): Intent? {
        // We should only limit this intent to location provider
        if (
            attributionTag != null &&
                isAttributionSupported &&
                locationManager.isProviderPackage(null, packageName, attributionTag.toString())
        ) {
            val intent = Intent(Intent.ACTION_MANAGE_PERMISSION_USAGE)
            intent.setPackage(packageName)
            intent.putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, permGroupName)
            intent.putExtra(Intent.EXTRA_ATTRIBUTION_TAGS, arrayOf(attributionTag.toString()))
            intent.putExtra(Intent.EXTRA_SHOWING_ATTRIBUTION, true)
            val resolveInfo =
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            if (
                resolveInfo?.activityInfo?.permission ==
                    Manifest.permission.START_VIEW_PERMISSION_USAGE
            ) {
                intent.component = ComponentName(packageName, resolveInfo.activityInfo.name)
                return intent
            }
        }
        return null
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
     * Show the [PrivacyDialogV2]
     *
     * This retrieves the permission usage from [PermissionManager] and creates a new
     * [PrivacyDialogV2] with a list of [PrivacyDialogV2.PrivacyElement] to show.
     *
     * This list will be filtered by [filterAndSelect]. Only types available by
     * [PrivacyItemController] will be shown.
     *
     * @param context A context to use to create the dialog.
     * @see filterAndSelect
     */
    fun showDialog(context: Context, privacyChip: OngoingPrivacyChip? = null) {
        dismissDialog()
        backgroundExecutor.execute {
            val usage = permGroupUsage()
            val userInfos = userTracker.userProfiles
            privacyLogger.logUnfilteredPermGroupUsage(usage)
            val items =
                usage.mapNotNull {
                    val userInfo =
                        userInfos.firstOrNull { ui -> ui.id == UserHandle.getUserId(it.uid) }
                    if (
                        isAvailable(it.permissionGroupName) && (userInfo != null || it.isPhoneCall)
                    ) {
                        // Only try to get the app name if we actually need it
                        val appName =
                            if (it.isPhoneCall) {
                                ""
                            } else {
                                getLabelForPackage(it.packageName, it.uid)
                            }
                        val userId = UserHandle.getUserId(it.uid)
                        val viewUsageIntent =
                            getStartViewPermissionUsageIntent(
                                context,
                                it.packageName,
                                it.permissionGroupName,
                                it.attributionTag,
                                // attributionLabel is set only when subattribution policies
                                // are supported and satisfied
                                it.attributionLabel != null
                            )
                        PrivacyDialogV2.PrivacyElement(
                            permGroupToPrivacyType(it.permissionGroupName)!!,
                            it.packageName,
                            userId,
                            appName,
                            it.attributionTag,
                            it.attributionLabel,
                            it.proxyLabel,
                            it.lastAccessTimeMillis,
                            it.isActive,
                            it.isPhoneCall,
                            viewUsageIntent != null,
                            it.permissionGroupName,
                            viewUsageIntent
                                ?: getDefaultManageAppPermissionsIntent(it.packageName, userId)
                        )
                    } else {
                        null
                    }
                }
            uiExecutor.execute {
                val elements = filterAndSelect(items)
                if (elements.isNotEmpty()) {
                    val d =
                        dialogProvider.makeDialog(
                            context,
                            elements,
                            this::manageApp,
                            this::closeApp,
                            this::openPrivacyDashboard
                        )
                    d.setShowForAllUsers(true)
                    d.addOnDismissListener(onDialogDismissed)
                    if (privacyChip != null) {
                        val controller = getPrivacyDialogController(privacyChip)
                        if (controller == null) {
                            d.show()
                        } else {
                            dialogTransitionAnimator.show(d, controller)
                        }
                    } else {
                        d.show()
                    }
                    privacyLogger.logShowDialogV2Contents(elements)
                    dialog = d
                } else {
                    privacyLogger.logEmptyDialog()
                }
            }
        }
    }

    private fun getPrivacyDialogController(
        source: OngoingPrivacyChip
    ): DialogTransitionAnimator.Controller? {
        val delegate =
            DialogTransitionAnimator.Controller.fromView(source.launchableContentView)
                ?: return null
        return object : DialogTransitionAnimator.Controller by delegate {
            override fun shouldAnimateExit() = source.isVisible
        }
    }

    /** Dismisses the dialog */
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
            privacyLogger.logLabelNotFound(packageName)
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

    private fun isAvailable(group: String): Boolean {
        return when (group) {
            Manifest.permission_group.CAMERA -> privacyItemController.micCameraAvailable
            Manifest.permission_group.MICROPHONE -> privacyItemController.micCameraAvailable
            Manifest.permission_group.LOCATION -> privacyItemController.locationAvailable
            else -> false
        }
    }

    /**
     * Filters the list of elements to show.
     *
     * For each privacy type, it'll return all active elements. If there are no active elements,
     * it'll return the most recent access
     */
    private fun filterAndSelect(
        list: List<PrivacyDialogV2.PrivacyElement>
    ): List<PrivacyDialogV2.PrivacyElement> {
        return list
            .groupBy { it.type }
            .toSortedMap()
            .flatMap { (_, elements) ->
                val actives = elements.filter { it.isActive }
                if (actives.isNotEmpty()) {
                    actives.sortedByDescending { it.lastActiveTimestamp }
                } else {
                    elements.maxByOrNull { it.lastActiveTimestamp }?.let { listOf(it) }
                        ?: emptyList()
                }
            }
    }

    /**
     * Interface to create a [PrivacyDialogV2].
     *
     * Can be used to inject a mock creator.
     */
    interface DialogProvider {
        /** Create a [PrivacyDialogV2]. */
        fun makeDialog(
            context: Context,
            list: List<PrivacyDialogV2.PrivacyElement>,
            manageApp: (String, Int, Intent) -> Unit,
            closeApp: (String, Int) -> Unit,
            openPrivacyDashboard: () -> Unit
        ): PrivacyDialogV2
    }
}
