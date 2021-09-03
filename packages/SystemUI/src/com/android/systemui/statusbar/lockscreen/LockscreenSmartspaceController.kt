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

package com.android.systemui.statusbar.lockscreen

import android.app.PendingIntent
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.settings.SecureSettings
import java.lang.RuntimeException
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Controller for managing the smartspace view on the lockscreen
 */
@SysUISingleton
class LockscreenSmartspaceController @Inject constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags,
    private val smartspaceManager: SmartspaceManager,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val contentResolver: ContentResolver,
    private val configurationController: ConfigurationController,
    private val statusBarStateController: StatusBarStateController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val execution: Execution,
    @Main private val uiExecutor: Executor,
    @Main private val handler: Handler,
    optionalPlugin: Optional<BcSmartspaceDataPlugin>
) {
    private var session: SmartspaceSession? = null
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private lateinit var smartspaceView: SmartspaceView

    lateinit var view: View
        private set

    private var showSensitiveContentForCurrentUser = false
    private var showSensitiveContentForManagedUser = false
    private var managedUserHandle: UserHandle? = null

    private val deviceProvisionedListener =
        object : DeviceProvisionedController.DeviceProvisionedListener {
            override fun onDeviceProvisionedChanged() {
                connectSession()
            }

            override fun onUserSetupChanged() {
                connectSession()
            }
        }

    private val sessionListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        execution.assertIsMainThread()
        val filteredTargets = targets.filter(::filterSmartspaceTarget)
        plugin?.onTargetsAvailable(filteredTargets)
    }

    private val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            execution.assertIsMainThread()
            reloadSmartspace()
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            execution.assertIsMainThread()
            reloadSmartspace()
        }
    }

    private val configChangeListener = object : ConfigurationController.ConfigurationListener {
        override fun onThemeChanged() {
            execution.assertIsMainThread()
            updateTextColorFromWallpaper()
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            execution.assertIsMainThread()
            smartspaceView.setDozeAmount(eased)
        }
    }

    init {
        deviceProvisionedController.addCallback(deviceProvisionedListener)
    }

    fun isEnabled(): Boolean {
        execution.assertIsMainThread()

        return featureFlags.isSmartspaceEnabled && plugin != null
    }

    /**
     * Constructs the smartspace view and connects it to the smartspace service. Subsequent calls
     * are idempotent until [disconnect] is called.
     */
    fun buildAndConnectView(parent: ViewGroup): View {
        execution.assertIsMainThread()

        if (!isEnabled()) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        buildView(parent)
        connectSession()

        return view
    }

    fun requestSmartspaceUpdate() {
        session?.requestSmartspaceUpdate()
    }

    private fun buildView(parent: ViewGroup) {
        if (plugin == null) {
            return
        }
        if (this::view.isInitialized) {
            // Due to some oddities with a singleton smartspace view, allow reparenting
            (view.getParent() as ViewGroup?)?.removeView(view)
            return
        }

        val ssView = plugin.getView(parent)
        ssView.registerDataProvider(plugin)
        ssView.setIntentStarter(object : BcSmartspaceDataPlugin.IntentStarter {
            override fun startIntent(v: View?, i: Intent?) {
                activityStarter.startActivity(i, true /* dismissShade */)
            }

            override fun startPendingIntent(pi: PendingIntent?) {
                activityStarter.startPendingIntentDismissingKeyguard(pi)
            }
        })
        ssView.setFalsingManager(falsingManager)

        this.smartspaceView = ssView
        this.view = ssView as View

        updateTextColorFromWallpaper()
        statusBarStateListener.onDozeAmountChanged(0f, statusBarStateController.dozeAmount)
    }

    private fun connectSession() {
        if (plugin == null || session != null) {
            return
        }

        // Only connect after the device is fully provisioned to avoid connection caching
        // issues
        if (!deviceProvisionedController.isDeviceProvisioned() ||
                !deviceProvisionedController.isCurrentUserSetup()) {
            return
        }

        val newSession = smartspaceManager.createSmartspaceSession(
                SmartspaceConfig.Builder(context, "lockscreen").build())
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        deviceProvisionedController.removeCallback(deviceProvisionedListener)
        userTracker.addCallback(userTrackerCallback, uiExecutor)
        contentResolver.registerContentObserver(
                secureSettings.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL
        )
        configurationController.addCallback(configChangeListener)
        statusBarStateController.addCallback(statusBarStateListener)

        reloadSmartspace()
    }

    /**
     * Disconnects the smartspace view from the smartspace service and cleans up any resources.
     * Calling [buildAndConnectView] again will cause the same view to be reconnected to the
     * service.
     */
    fun disconnect() {
        execution.assertIsMainThread()

        if (session == null) {
            return
        }

        session?.let {
            it.removeOnTargetsAvailableListener(sessionListener)
            it.close()
        }
        userTracker.removeCallback(userTrackerCallback)
        contentResolver.unregisterContentObserver(settingsObserver)
        configurationController.removeCallback(configChangeListener)
        statusBarStateController.removeCallback(statusBarStateListener)
        session = null

        plugin?.onTargetsAvailable(emptyList())
    }

    fun addListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.registerListener(listener)
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.unregisterListener(listener)
    }

    private fun filterSmartspaceTarget(t: SmartspaceTarget): Boolean {
        return when (t.userHandle) {
            userTracker.userHandle -> {
                !t.isSensitive || showSensitiveContentForCurrentUser
            }
            managedUserHandle -> {
                // Really, this should be "if this managed profile is associated with the current
                // active user", but we don't have a good way to check that, so instead we cheat:
                // Only the primary user can have an associated managed profile, so only show
                // content for the managed profile if the primary user is active
                userTracker.userHandle.identifier == UserHandle.USER_SYSTEM &&
                        (!t.isSensitive || showSensitiveContentForManagedUser)
            }
            else -> {
                false
            }
        }
    }

    private fun updateTextColorFromWallpaper() {
        val wallpaperTextColor = Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor)
        smartspaceView.setPrimaryTextColor(wallpaperTextColor)
    }

    private fun reloadSmartspace() {
        val setting = Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS

        showSensitiveContentForCurrentUser =
                secureSettings.getIntForUser(setting, 0, userTracker.userId) == 1

        managedUserHandle = getWorkProfileUser()
        val managedId = managedUserHandle?.identifier
        if (managedId != null) {
            showSensitiveContentForManagedUser =
                    secureSettings.getIntForUser(setting, 0, managedId) == 1
        }

        session?.requestSmartspaceUpdate()
    }

    private fun getWorkProfileUser(): UserHandle? {
        for (userInfo in userTracker.userProfiles) {
            if (userInfo.isManagedProfile) {
                return userInfo.userHandle
            }
        }
        return null
    }
}
