/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.footer.ui.viewmodel

import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.settingslib.Utils
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.dagger.QSFlagsModule.PM_LITE_ENABLED
import com.android.systemui.qs.footer.data.model.UserSwitcherStatusModel
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig
import com.android.systemui.res.R
import com.android.systemui.util.icuMessageFormat
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val TAG = "FooterActionsViewModel"

/** A ViewModel for the footer actions. */
class FooterActionsViewModel(
    /** The model for the security button. */
    val security: Flow<FooterActionsSecurityButtonViewModel?>,

    /** The model for the foreground services button. */
    val foregroundServices: Flow<FooterActionsForegroundServicesButtonViewModel?>,

    /** The model for the user switcher button. */
    val userSwitcher: Flow<FooterActionsButtonViewModel?>,

    /** The model for the settings button. */
    val settings: FooterActionsButtonViewModel,

    /** The model for the power button. */
    val power: FooterActionsButtonViewModel?,

    /**
     * Observe the device monitoring dialog requests and show the dialog accordingly. This function
     * will suspend indefinitely and will need to be cancelled to stop observing.
     *
     * Important: [quickSettingsContext] must be the [Context] associated to the
     * [Quick Settings fragment][com.android.systemui.qs.QSFragmentLegacy], and the call to this
     * function must be cancelled when that fragment is destroyed.
     */
    val observeDeviceMonitoringDialogRequests: suspend (quickSettingsContext: Context) -> Unit,
) {
    /** The alpha the UI rendering this ViewModel should have. */
    private val _alpha = MutableStateFlow(1f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()

    /** The alpha the background of the UI rendering this ViewModel should have. */
    private val _backgroundAlpha = MutableStateFlow(1f)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** Called when the expansion of the Quick Settings changed. */
    fun onQuickSettingsExpansionChanged(expansion: Float, isInSplitShade: Boolean) {
        if (isInSplitShade) {
            // In split shade, we want to fade in the background when the QS background starts to
            // show.
            val delay = 0.15f
            _alpha.value = expansion
            _backgroundAlpha.value = max(0f, expansion - delay) / (1f - delay)
        } else {
            // Only start fading in the footer actions when we are at least 90% expanded.
            val delay = 0.9f
            _alpha.value = max(0f, expansion - delay) / (1 - delay)
            _backgroundAlpha.value = 1f
        }
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val falsingManager: FalsingManager,
        private val footerActionsInteractor: FooterActionsInteractor,
        private val globalActionsDialogLiteProvider: Provider<GlobalActionsDialogLite>,
        @Named(PM_LITE_ENABLED) private val showPowerButton: Boolean,
    ) {
        /** Create a [FooterActionsViewModel] bound to the lifecycle of [lifecycleOwner]. */
        fun create(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
            val globalActionsDialogLite = globalActionsDialogLiteProvider.get()
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                // This should usually not happen, but let's make sure we already destroy
                // globalActionsDialogLite.
                globalActionsDialogLite.destroy()
            } else {
                // Destroy globalActionsDialogLite when the lifecycle is destroyed.
                lifecycleOwner.lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            globalActionsDialogLite.destroy()
                        }
                    }
                )
            }

            return FooterActionsViewModel(
                context,
                footerActionsInteractor,
                falsingManager,
                globalActionsDialogLite,
                showPowerButton,
            )
        }
    }
}

fun FooterActionsViewModel(
    @Application appContext: Context,
    footerActionsInteractor: FooterActionsInteractor,
    falsingManager: FalsingManager,
    globalActionsDialogLite: GlobalActionsDialogLite,
    showPowerButton: Boolean,
): FooterActionsViewModel {
    suspend fun observeDeviceMonitoringDialogRequests(quickSettingsContext: Context) {
        footerActionsInteractor.deviceMonitoringDialogRequests.collect {
            footerActionsInteractor.showDeviceMonitoringDialog(
                quickSettingsContext,
                expandable = null,
            )
        }
    }

    fun onSecurityButtonClicked(quickSettingsContext: Context, expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showDeviceMonitoringDialog(quickSettingsContext, expandable)
    }

    fun onForegroundServiceButtonClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showForegroundServicesDialog(expandable)
    }

    fun onUserSwitcherClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showUserSwitcher(expandable)
    }

    fun onSettingsButtonClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showSettings(expandable)
    }

    fun onPowerButtonClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showPowerMenuDialog(globalActionsDialogLite, expandable)
    }

    val qsThemedContext = ContextThemeWrapper(appContext, R.style.Theme_SystemUI_QuickSettings)

    val security =
        footerActionsInteractor.securityButtonConfig
            .map { config ->
                config?.let { securityButtonViewModel(it, ::onSecurityButtonClicked) }
            }
            .distinctUntilChanged()

    val foregroundServices =
        combine(
                footerActionsInteractor.foregroundServicesCount,
                footerActionsInteractor.hasNewForegroundServices,
                security,
            ) { foregroundServicesCount, hasNewChanges, securityModel ->
                if (foregroundServicesCount <= 0) {
                    return@combine null
                }

                foregroundServicesButtonViewModel(
                    qsThemedContext,
                    foregroundServicesCount,
                    securityModel,
                    hasNewChanges,
                    ::onForegroundServiceButtonClicked,
                )
            }
            .distinctUntilChanged()

    val userSwitcher =
        footerActionsInteractor.userSwitcherStatus
            .map { userSwitcherStatus ->
                when (userSwitcherStatus) {
                    UserSwitcherStatusModel.Disabled -> null
                    is UserSwitcherStatusModel.Enabled -> {
                        if (userSwitcherStatus.currentUserImage == null) {
                            Log.e(
                                TAG,
                                "Skipped the addition of user switcher button because " +
                                    "currentUserImage is missing",
                            )
                            return@map null
                        }

                        userSwitcherButtonViewModel(
                            qsThemedContext,
                            userSwitcherStatus,
                            ::onUserSwitcherClicked
                        )
                    }
                }
            }
            .distinctUntilChanged()

    val settings = settingsButtonViewModel(qsThemedContext, ::onSettingsButtonClicked)
    val power =
        if (showPowerButton) {
            powerButtonViewModel(qsThemedContext, ::onPowerButtonClicked)
        } else {
            null
        }

    return FooterActionsViewModel(
        security = security,
        foregroundServices = foregroundServices,
        userSwitcher = userSwitcher,
        settings = settings,
        power = power,
        observeDeviceMonitoringDialogRequests = ::observeDeviceMonitoringDialogRequests,
    )
}

fun securityButtonViewModel(
    config: SecurityButtonConfig,
    onSecurityButtonClicked: (Context, Expandable) -> Unit,
): FooterActionsSecurityButtonViewModel {
    val (icon, text, isClickable) = config
    return FooterActionsSecurityButtonViewModel(
        icon,
        text,
        if (isClickable) onSecurityButtonClicked else null,
    )
}

fun foregroundServicesButtonViewModel(
    qsThemedContext: Context,
    foregroundServicesCount: Int,
    securityModel: FooterActionsSecurityButtonViewModel?,
    hasNewChanges: Boolean,
    onForegroundServiceButtonClicked: (Expandable) -> Unit,
): FooterActionsForegroundServicesButtonViewModel {
    val text =
        icuMessageFormat(
            qsThemedContext.resources,
            R.string.fgs_manager_footer_label,
            foregroundServicesCount,
        )

    return FooterActionsForegroundServicesButtonViewModel(
        foregroundServicesCount,
        text = text,
        displayText = securityModel == null,
        hasNewChanges = hasNewChanges,
        onForegroundServiceButtonClicked,
    )
}

fun userSwitcherButtonViewModel(
    qsThemedContext: Context,
    status: UserSwitcherStatusModel.Enabled,
    onUserSwitcherClicked: (Expandable) -> Unit,
): FooterActionsButtonViewModel {
    val icon = status.currentUserImage!!
    return FooterActionsButtonViewModel(
        id = R.id.multi_user_switch,
        icon =
            Icon.Loaded(
                icon,
                ContentDescription.Loaded(
                    userSwitcherContentDescription(qsThemedContext, status.currentUserName)
                ),
            ),
        iconTint = null,
        backgroundColor = R.attr.shadeInactive,
        onClick = onUserSwitcherClicked,
    )
}

private fun userSwitcherContentDescription(
    qsThemedContext: Context,
    currentUser: String?
): String? {
    return currentUser?.let { user ->
        qsThemedContext.getString(R.string.accessibility_quick_settings_user, user)
    }
}

fun settingsButtonViewModel(
    qsThemedContext: Context,
    onSettingsButtonClicked: (Expandable) -> Unit,
): FooterActionsButtonViewModel {
    return FooterActionsButtonViewModel(
        id = R.id.settings_button_container,
        Icon.Resource(
            R.drawable.ic_settings,
            ContentDescription.Resource(R.string.accessibility_quick_settings_settings)
        ),
        iconTint =
            Utils.getColorAttrDefaultColor(
                qsThemedContext,
                R.attr.onShadeInactiveVariant,
            ),
        backgroundColor = R.attr.shadeInactive,
        onSettingsButtonClicked,
    )
}

fun powerButtonViewModel(
    qsThemedContext: Context,
    onPowerButtonClicked: (Expandable) -> Unit,
): FooterActionsButtonViewModel {
    return FooterActionsButtonViewModel(
        id = R.id.pm_lite,
        Icon.Resource(
            android.R.drawable.ic_lock_power_off,
            ContentDescription.Resource(R.string.accessibility_quick_settings_power_menu)
        ),
        iconTint =
            Utils.getColorAttrDefaultColor(
                qsThemedContext,
                R.attr.onShadeActive,
            ),
        backgroundColor = R.attr.shadeActive,
        onPowerButtonClicked,
    )
}
