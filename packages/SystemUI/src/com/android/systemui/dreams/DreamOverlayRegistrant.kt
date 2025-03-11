/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.dreams

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PatternMatcher
import android.os.RemoteException
import android.service.dreams.IDreamManager
import android.util.Log
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.qualifiers.SystemUser
import com.android.systemui.dreams.dagger.DreamModule
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import com.android.systemui.shared.condition.Monitor
import com.android.systemui.util.condition.ConditionalCoreStartable
import javax.inject.Inject
import javax.inject.Named

/**
 * [DreamOverlayRegistrant] is responsible for telling system server that SystemUI should be the
 * designated dream overlay component.
 */
class DreamOverlayRegistrant
@Inject
constructor(
    private val context: Context,
    @param:Named(DreamModule.DREAM_OVERLAY_SERVICE_COMPONENT)
    private val overlayServiceComponent: ComponentName,
    @SystemUser monitor: Monitor,
    private val packageManager: PackageManager,
    private val dreamManager: IDreamManager,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    @DreamLog private val logBuffer: LogBuffer,
) : ConditionalCoreStartable(monitor) {
    private var currentRegisteredState = false
    private val logger: DreamLogger = DreamLogger(logBuffer, TAG)

    private val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DEBUG) {
                    Log.d(TAG, "package changed receiver - onReceive")
                }

                registerOverlayService()
            }
        }

    internal val enabledInManifest: Boolean
        get() {
            return packageManager
                .getServiceInfo(
                    overlayServiceComponent,
                    PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
                )
                .enabled
        }

    internal val enabled: Boolean
        get() {
            // Always disabled via setting
            if (
                packageManager.getComponentEnabledSetting(overlayServiceComponent) ==
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                return false
            }

            // If the overlay is available in the manifest, then it is already available
            if (enabledInManifest) {
                return true
            }

            if (
                communalSettingsInteractor.isV2FlagEnabled() &&
                    packageManager.getComponentEnabledSetting(overlayServiceComponent) ==
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                return true
            }

            return false
        }

    /**
     * This method enables the dream overlay at runtime. This method allows expanding the eligible
     * device pool during development before enabling the component in said devices' manifest.
     */
    internal fun enableIfAvailable() {
        // If the overlay is available in the manifest, then it is already available
        if (enabledInManifest) {
            return
        }

        // Enable for hub on mobile
        if (communalSettingsInteractor.isV2FlagEnabled()) {
            // Not available on TV or auto
            if (
                packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)
            ) {
                if (DEBUG) {
                    Log.d(TAG, "unsupported platform")
                }
                return
            }

            // If the component is not in the default enabled state, then don't update
            if (
                packageManager.getComponentEnabledSetting(overlayServiceComponent) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) {
                return
            }

            packageManager.setComponentEnabledSetting(
                overlayServiceComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun registerOverlayService() {
        // The overlay service is only registered when its component setting is enabled.
        var register = false

        try {
            Log.d(TAG, "trying to find component:" + overlayServiceComponent)
            // Check to see if the service has been disabled by the user. In this case, we should
            // not proceed modifying the enabled setting.
            register = enabled
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "could not find dream overlay service")
        }

        if (currentRegisteredState == register) {
            return
        }

        currentRegisteredState = register

        try {
            if (DEBUG) {
                Log.d(
                    TAG,
                    if (currentRegisteredState)
                        "registering dream overlay service:$overlayServiceComponent"
                    else "clearing dream overlay service",
                )
            }

            dreamManager.registerDreamOverlayService(
                if (currentRegisteredState) overlayServiceComponent else null
            )
            logger.logDreamOverlayEnabled(currentRegisteredState)
        } catch (e: RemoteException) {
            Log.e(TAG, "could not register dream overlay service:$e")
        }
    }

    override fun onStart() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        filter.addDataSchemeSpecificPart(
            overlayServiceComponent.packageName,
            PatternMatcher.PATTERN_LITERAL,
        )
        // Note that we directly register the receiver here as data schemes are not supported by
        // BroadcastDispatcher.
        context.registerReceiver(receiver, filter)

        registerOverlayService()

        enableIfAvailable()
    }

    companion object {
        private const val TAG = "DreamOverlayRegistrant"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
