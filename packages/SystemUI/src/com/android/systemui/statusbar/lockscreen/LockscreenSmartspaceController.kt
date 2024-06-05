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

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
import android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS
import android.provider.Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceConfigPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.WeatherData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DATE_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.WEATHER_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.time.Instant
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/** Controller for managing the smartspace view on the lockscreen */
@SysUISingleton
class LockscreenSmartspaceController
@Inject
constructor(
        private val context: Context,
        private val featureFlags: FeatureFlags,
        private val smartspaceManager: SmartspaceManager,
        private val activityStarter: ActivityStarter,
        private val falsingManager: FalsingManager,
        private val systemClock: SystemClock,
        private val secureSettings: SecureSettings,
        private val userTracker: UserTracker,
        private val contentResolver: ContentResolver,
        private val configurationController: ConfigurationController,
        private val statusBarStateController: StatusBarStateController,
        private val deviceProvisionedController: DeviceProvisionedController,
        private val bypassController: KeyguardBypassController,
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        private val dumpManager: DumpManager,
        private val execution: Execution,
        @Main private val uiExecutor: Executor,
        @Background private val bgExecutor: Executor,
        @Main private val handler: Handler,
        @Named(DATE_SMARTSPACE_DATA_PLUGIN)
        optionalDatePlugin: Optional<BcSmartspaceDataPlugin>,
        @Named(WEATHER_SMARTSPACE_DATA_PLUGIN)
        optionalWeatherPlugin: Optional<BcSmartspaceDataPlugin>,
        optionalPlugin: Optional<BcSmartspaceDataPlugin>,
        optionalConfigPlugin: Optional<BcSmartspaceConfigPlugin>,
) : Dumpable {
    companion object {
        private const val TAG = "LockscreenSmartspaceController"
    }

    private var session: SmartspaceSession? = null
    private val datePlugin: BcSmartspaceDataPlugin? = optionalDatePlugin.orElse(null)
    private val weatherPlugin: BcSmartspaceDataPlugin? = optionalWeatherPlugin.orElse(null)
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private val configPlugin: BcSmartspaceConfigPlugin? = optionalConfigPlugin.orElse(null)

    // Smartspace can be used on multiple displays, such as when the user casts their screen
    private var smartspaceViews = mutableSetOf<SmartspaceView>()
    private var regionSamplers =
            mutableMapOf<SmartspaceView, RegionSampler>()

    private val regionSamplingEnabled =
            featureFlags.isEnabled(Flags.REGION_SAMPLING)
    private var isRegionSamplersCreated = false
    private var showNotifications = false
    private var showSensitiveContentForCurrentUser = false
    private var showSensitiveContentForManagedUser = false
    private var managedUserHandle: UserHandle? = null
    private var mSplitShadeEnabled = false

    // TODO(b/202758428): refactor so that we can test color updates via region samping, similar to
    //  how we test color updates when theme changes (See testThemeChangeUpdatesTextColor).

    // TODO: Move logic into SmartspaceView
    var stateChangeListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            (v as SmartspaceView).setSplitShadeEnabled(mSplitShadeEnabled)
            smartspaceViews.add(v as SmartspaceView)

            connectSession()

            updateTextColorFromWallpaper()
            statusBarStateListener.onDozeAmountChanged(0f, statusBarStateController.dozeAmount)
        }

        override fun onViewDetachedFromWindow(v: View) {
            smartspaceViews.remove(v as SmartspaceView)

            regionSamplers[v]?.stopRegionSampler()
            regionSamplers.remove(v as SmartspaceView)

            if (smartspaceViews.isEmpty()) {
                disconnect()
            }
        }
    }

    private val sessionListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        execution.assertIsMainThread()

        // The weather data plugin takes unfiltered targets and performs the filtering internally.
        weatherPlugin?.onTargetsAvailable(targets)
        val now = Instant.ofEpochMilli(systemClock.currentTimeMillis())
        val weatherTarget = targets.find { t ->
            t.featureType == SmartspaceTarget.FEATURE_WEATHER &&
                    now.isAfter(Instant.ofEpochMilli(t.creationTimeMillis)) &&
                    now.isBefore(Instant.ofEpochMilli(t.expiryTimeMillis))
        }
        if (weatherTarget != null) {
            val weatherData = weatherTarget.baseAction?.extras?.let { extras ->
                WeatherData.fromBundle(
                    extras,
                )
	    }
            if (weatherData != null) {
                keyguardUpdateMonitor.sendWeatherData(weatherData)
            }
        }

        val filteredTargets = targets.filter(::filterSmartspaceTarget)
        plugin?.onTargetsAvailable(filteredTargets)
        if (!isRegionSamplersCreated) {
            for (v in smartspaceViews) {
                if (regionSamplingEnabled) {
                    var regionSampler = RegionSampler(
                        v as View,
                        uiExecutor,
                        bgExecutor,
                        regionSamplingEnabled,
                        isLockscreen = true,
                    ) { updateTextColorFromRegionSampler() }
                    initializeTextColors(regionSampler)
                    regionSamplers[v] = regionSampler
                    regionSampler.startRegionSampler()
                }
            }
            isRegionSamplersCreated = true
        }
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
            smartspaceViews.forEach { it.setDozeAmount(eased) }
        }

        override fun onDozingChanged(isDozing: Boolean) {
            execution.assertIsMainThread()
            smartspaceViews.forEach { it.setDozing(isDozing) }
        }
    }

    private val deviceProvisionedListener =
        object : DeviceProvisionedController.DeviceProvisionedListener {
            override fun onDeviceProvisionedChanged() {
                connectSession()
            }

            override fun onUserSetupChanged() {
                connectSession()
            }
        }

    private val bypassStateChangedListener =
        object : KeyguardBypassController.OnBypassStateChangedListener {
            override fun onBypassStateChanged(isEnabled: Boolean) {
                updateBypassEnabled()
            }
        }

    init {
        deviceProvisionedController.addCallback(deviceProvisionedListener)
        dumpManager.registerDumpable(this)
    }

    fun isEnabled(): Boolean {
        execution.assertIsMainThread()

        return plugin != null
    }

    fun isDateWeatherDecoupled(): Boolean {
        execution.assertIsMainThread()

        return featureFlags.isEnabled(Flags.SMARTSPACE_DATE_WEATHER_DECOUPLED) &&
                datePlugin != null && weatherPlugin != null
    }

    fun isWeatherEnabled(): Boolean {
       execution.assertIsMainThread()
       val defaultValue = context.getResources().getBoolean(
               com.android.internal.R.bool.config_lockscreenWeatherEnabledByDefault)
       val showWeather = secureSettings.getIntForUser(
           LOCK_SCREEN_WEATHER_ENABLED,
           if (defaultValue) 1 else 0,
           userTracker.userId) == 1
       return showWeather
    }

    private fun updateBypassEnabled() {
        val bypassEnabled = bypassController.bypassEnabled
        smartspaceViews.forEach { it.setKeyguardBypassEnabled(bypassEnabled) }
    }

    /**
     * Constructs the date view and connects it to the smartspace service.
     */
    fun buildAndConnectDateView(parent: ViewGroup): View? {
        execution.assertIsMainThread()

        if (!isEnabled()) {
            throw RuntimeException("Cannot build view when not enabled")
        }
        if (!isDateWeatherDecoupled()) {
            throw RuntimeException("Cannot build date view when not decoupled")
        }

        val view = buildView(parent, datePlugin)
        connectSession()

        return view
    }

    /**
     * Constructs the weather view and connects it to the smartspace service.
     */
    fun buildAndConnectWeatherView(parent: ViewGroup): View? {
        execution.assertIsMainThread()

        if (!isEnabled()) {
            throw RuntimeException("Cannot build view when not enabled")
        }
        if (!isDateWeatherDecoupled()) {
            throw RuntimeException("Cannot build weather view when not decoupled")
        }

        val view = buildView(parent, weatherPlugin)
        connectSession()

        return view
    }

    /**
     * Constructs the smartspace view and connects it to the smartspace service.
     */
    fun buildAndConnectView(parent: ViewGroup): View? {
        execution.assertIsMainThread()

        if (!isEnabled()) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view = buildView(parent, plugin, configPlugin)
        connectSession()

        return view
    }

    private fun buildView(
            parent: ViewGroup,
            plugin: BcSmartspaceDataPlugin?,
            configPlugin: BcSmartspaceConfigPlugin? = null
    ): View? {
        if (plugin == null) {
            return null
        }

        val ssView = plugin.getView(parent)
        configPlugin?.let { ssView.registerConfigProvider(it) }
        ssView.setUiSurface(BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
        ssView.registerDataProvider(plugin)

        ssView.setIntentStarter(object : BcSmartspaceDataPlugin.IntentStarter {
            override fun startIntent(view: View, intent: Intent, showOnLockscreen: Boolean) {
                if (showOnLockscreen) {
                    activityStarter.startActivity(
                            intent,
                            true, /* dismissShade */
                            // launch animator - looks bad with the transparent smartspace bg
                            null,
                            true
                    )
                } else {
                    activityStarter.postStartActivityDismissingKeyguard(intent, 0)
                }
            }

            override fun startPendingIntent(
                    view: View,
                    pi: PendingIntent,
                    showOnLockscreen: Boolean
            ) {
                if (showOnLockscreen) {
                    val options = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            .toBundle()
                    pi.send(options)
                } else {
                    activityStarter.postStartActivityDismissingKeyguard(pi)
                }
            }
        })
        ssView.setFalsingManager(falsingManager)
        ssView.setKeyguardBypassEnabled(bypassController.bypassEnabled)
        return (ssView as View).apply { addOnAttachStateChangeListener(stateChangeListener) }
    }

    private fun connectSession() {
        if (datePlugin == null && weatherPlugin == null && plugin == null) return
        if (session != null || smartspaceViews.isEmpty()) {
            return
        }

        // Only connect after the device is fully provisioned to avoid connection caching
        // issues
        if (!deviceProvisionedController.isDeviceProvisioned() ||
                !deviceProvisionedController.isCurrentUserSetup()) {
            return
        }

        val newSession = smartspaceManager.createSmartspaceSession(
                SmartspaceConfig.Builder(
                        context, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD).build())
        Log.d(TAG, "Starting smartspace session for " +
                BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        deviceProvisionedController.removeCallback(deviceProvisionedListener)
        userTracker.addCallback(userTrackerCallback, uiExecutor)
        contentResolver.registerContentObserver(
                secureSettings.getUriFor(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL
        )
        contentResolver.registerContentObserver(
                secureSettings.getUriFor(LOCK_SCREEN_SHOW_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL
        )
        configurationController.addCallback(configChangeListener)
        statusBarStateController.addCallback(statusBarStateListener)
        bypassController.registerOnBypassStateChangedListener(bypassStateChangedListener)

        datePlugin?.registerSmartspaceEventNotifier { e -> session?.notifySmartspaceEvent(e) }
        weatherPlugin?.registerSmartspaceEventNotifier { e -> session?.notifySmartspaceEvent(e) }
        plugin?.registerSmartspaceEventNotifier { e -> session?.notifySmartspaceEvent(e) }

        updateBypassEnabled()
        reloadSmartspace()
    }

    fun setSplitShadeEnabled(enabled: Boolean) {
        mSplitShadeEnabled = enabled
        smartspaceViews.forEach { it.setSplitShadeEnabled(enabled) }
    }

    /**
     * Requests the smartspace session for an update.
     */
    fun requestSmartspaceUpdate() {
        session?.requestSmartspaceUpdate()
    }

    /**
     * Disconnects the smartspace view from the smartspace service and cleans up any resources.
     */
    fun disconnect() {
        if (!smartspaceViews.isEmpty()) return

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
        bypassController.unregisterOnBypassStateChangedListener(bypassStateChangedListener)
        session = null

        datePlugin?.registerSmartspaceEventNotifier(null)

        weatherPlugin?.registerSmartspaceEventNotifier(null)
        weatherPlugin?.onTargetsAvailable(emptyList())

        plugin?.registerSmartspaceEventNotifier(null)
        plugin?.onTargetsAvailable(emptyList())

        Log.d(TAG, "Ended smartspace session for lockscreen")
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
        if (isDateWeatherDecoupled()) {
            return t.featureType != SmartspaceTarget.FEATURE_WEATHER
        }
        if (!showNotifications) {
            return t.featureType == SmartspaceTarget.FEATURE_WEATHER
        }
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

    private fun initializeTextColors(regionSampler: RegionSampler) {
        val lightThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI_LightWallpaper)
        val darkColor = Utils.getColorAttrDefaultColor(lightThemeContext, R.attr.wallpaperTextColor)

        val darkThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI)
        val lightColor = Utils.getColorAttrDefaultColor(darkThemeContext, R.attr.wallpaperTextColor)

        regionSampler.setForegroundColors(lightColor, darkColor)
    }

    private fun updateTextColorFromRegionSampler() {
        regionSamplers.forEach { (view, region) ->
            val textColor = region.currentForegroundColor()
            if (textColor != null) {
                view.setPrimaryTextColor(textColor)
            }
        }
    }

    private fun updateTextColorFromWallpaper() {
        if (!regionSamplingEnabled || regionSamplers.isEmpty()) {
            val wallpaperTextColor =
                    Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor)
            smartspaceViews.forEach { it.setPrimaryTextColor(wallpaperTextColor) }
        } else {
            updateTextColorFromRegionSampler()
        }
    }

    private fun reloadSmartspace() {
        showNotifications = secureSettings.getIntForUser(
            LOCK_SCREEN_SHOW_NOTIFICATIONS,
            0,
            userTracker.userId
        ) == 1

        showSensitiveContentForCurrentUser = secureSettings.getIntForUser(
            LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            0,
            userTracker.userId
        ) == 1

        managedUserHandle = getWorkProfileUser()
        val managedId = managedUserHandle?.identifier
        if (managedId != null) {
            showSensitiveContentForManagedUser = secureSettings.getIntForUser(
                LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                0,
                managedId
            ) == 1
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

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Region Samplers: ${regionSamplers.size}")
        regionSamplers.map { (_, sampler) ->
            sampler.dump(pw)
        }
    }
}
