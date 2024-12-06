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

package com.android.systemui.communal.data.repository

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.Flags.communalHub
import com.android.systemui.Flags.glanceableHubV2
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.data.model.CommunalEnabledState
import com.android.systemui.communal.data.model.DisabledReason
import com.android.systemui.communal.data.model.DisabledReason.DISABLED_REASON_DEVICE_POLICY
import com.android.systemui.communal.data.model.DisabledReason.DISABLED_REASON_FLAG
import com.android.systemui.communal.data.model.DisabledReason.DISABLED_REASON_INVALID_USER
import com.android.systemui.communal.data.model.DisabledReason.DISABLED_REASON_USER_SETTING
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.util.EnumSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

interface CommunalSettingsRepository {
    /** A [CommunalEnabledState] for the specified user. */
    fun getEnabledState(user: UserInfo): Flow<CommunalEnabledState>

    /**
     * Returns true if any glanceable hub functionality should be enabled via configs and flags.
     *
     * This should be used for preventing basic glanceable hub functionality from running on devices
     * that don't need it.
     *
     * If the glanceable_hub_v2 flag is enabled, checks the config_glanceableHubEnabled Android
     * config boolean. Otherwise, checks the old config_communalServiceEnabled config and
     * communal_hub flag.
     */
    fun getFlagEnabled(): Boolean

    /**
     * Returns true if the Android config config_glanceableHubEnabled and the glanceable_hub_v2 flag
     * are enabled.
     *
     * This should be used to flag off new glanceable hub or dream behavior that should launch
     * together with the new hub experience that brings the hub to mobile.
     *
     * The trunk-stable flag is controlled by server rollout and is on all devices. The Android
     * config flag is enabled via resource overlay only on products we want the hub to be present
     * on.
     */
    fun getV2FlagEnabled(): Boolean

    /** Keyguard widgets enabled state by Device Policy Manager for the specified user. */
    fun getAllowedByDevicePolicy(user: UserInfo): Flow<Boolean>

    /** The type of background to use for the hub. Used to experiment with different backgrounds. */
    fun getBackground(user: UserInfo): Flow<CommunalBackgroundType>
}

@SysUISingleton
class CommunalSettingsRepositoryImpl
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Main private val resources: Resources,
    private val featureFlagsClassic: FeatureFlagsClassic,
    private val secureSettings: SecureSettings,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val devicePolicyManager: DevicePolicyManager,
) : CommunalSettingsRepository {

    override fun getFlagEnabled(): Boolean {
        return if (getV2FlagEnabled()) {
            true
        } else {
            // This config (exposed as a classic feature flag) is targeted only to tablet.
            // TODO(b/379181581): clean up usages of communal_hub flag
            featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) && communalHub()
        }
    }

    override fun getV2FlagEnabled(): Boolean {
        return resources.getBoolean(com.android.internal.R.bool.config_glanceableHubEnabled) &&
            glanceableHubV2()
    }

    override fun getEnabledState(user: UserInfo): Flow<CommunalEnabledState> {
        if (!user.isMain) {
            return flowOf(CommunalEnabledState(DISABLED_REASON_INVALID_USER))
        }
        if (!getFlagEnabled()) {
            return flowOf(CommunalEnabledState(DISABLED_REASON_FLAG))
        }
        return combine(
                getEnabledByUser(user).mapToReason(DISABLED_REASON_USER_SETTING),
                getAllowedByDevicePolicy(user).mapToReason(DISABLED_REASON_DEVICE_POLICY),
            ) { reasons ->
                reasons.filterNotNull()
            }
            .map { reasons ->
                if (reasons.isEmpty()) {
                    EnumSet.noneOf(DisabledReason::class.java)
                } else {
                    EnumSet.copyOf(reasons)
                }
            }
            .map { reasons -> CommunalEnabledState(reasons) }
            .flowOn(bgDispatcher)
    }

    override fun getAllowedByDevicePolicy(user: UserInfo): Flow<Boolean> =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                // In COPE management mode, the restriction from the managed profile may
                // propagate to the main profile. Therefore listen to this broadcast across
                // all users and update the state each time it changes.
                user = UserHandle.ALL,
            )
            .emitOnStart()
            .map { devicePolicyManager.areKeyguardWidgetsAllowed(user.id) }

    override fun getBackground(user: UserInfo): Flow<CommunalBackgroundType> =
        secureSettings
            .observerFlow(userId = user.id, names = arrayOf(GLANCEABLE_HUB_BACKGROUND_SETTING))
            .emitOnStart()
            .map {
                val intType =
                    secureSettings.getIntForUser(
                        GLANCEABLE_HUB_BACKGROUND_SETTING,
                        CommunalBackgroundType.ANIMATED.value,
                        user.id,
                    )
                CommunalBackgroundType.entries.find { type -> type.value == intType }
                    ?: CommunalBackgroundType.ANIMATED
            }

    private fun getEnabledByUser(user: UserInfo): Flow<Boolean> =
        secureSettings
            .observerFlow(userId = user.id, names = arrayOf(Settings.Secure.GLANCEABLE_HUB_ENABLED))
            // Force an update
            .onStart { emit(Unit) }
            .map {
                secureSettings.getIntForUser(
                    Settings.Secure.GLANCEABLE_HUB_ENABLED,
                    ENABLED_SETTING_DEFAULT,
                    user.id,
                ) == 1
            }

    companion object {
        const val GLANCEABLE_HUB_BACKGROUND_SETTING = "glanceable_hub_background"
        private const val ENABLED_SETTING_DEFAULT = 1
    }
}

private fun DevicePolicyManager.areKeyguardWidgetsAllowed(userId: Int): Boolean =
    (getKeyguardDisabledFeatures(null, userId) and KEYGUARD_DISABLE_WIDGETS_ALL) == 0

private fun Flow<Boolean>.mapToReason(reason: DisabledReason) = map { enabled ->
    if (enabled) null else reason
}
