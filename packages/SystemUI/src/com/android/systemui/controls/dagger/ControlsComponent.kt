/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.dagger

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.SecureSettings
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject

/**
 * Pseudo-component to inject into classes outside `com.android.systemui.controls`.
 *
 * If `featureEnabled` is false, all the optionals should be empty. The controllers will only be
 * instantiated if `featureEnabled` is true. Can also be queried for the availability of controls.
 */
@SysUISingleton
class ControlsComponent @Inject constructor(
    @ControlsFeatureEnabled private val featureEnabled: Boolean,
    private val context: Context,
    private val lazyControlsController: Lazy<ControlsController>,
    private val lazyControlsUiController: Lazy<ControlsUiController>,
    private val lazyControlsListingController: Lazy<ControlsListingController>,
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings
) {
    private val contentResolver: ContentResolver
        get() = context.contentResolver

    private var canShowWhileLockedSetting = false

    val showWhileLockedObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            updateShowWhileLocked()
        }
    }

    init {
        if (featureEnabled) {
            secureSettings.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_SHOW_CONTROLS),
                false, /* notifyForDescendants */
                showWhileLockedObserver
            )
            updateShowWhileLocked()
        }
    }

    fun getControlsController(): Optional<ControlsController> {
        return if (featureEnabled) Optional.of(lazyControlsController.get()) else Optional.empty()
    }

    fun getControlsUiController(): Optional<ControlsUiController> {
        return if (featureEnabled) Optional.of(lazyControlsUiController.get()) else Optional.empty()
    }

    fun getControlsListingController(): Optional<ControlsListingController> {
        return if (featureEnabled) {
            Optional.of(lazyControlsListingController.get())
        } else {
            Optional.empty()
        }
    }

    /**
     * @return true if controls are feature-enabled and the user has the setting enabled
     */
    fun isEnabled() = featureEnabled

    /**
     * Returns one of 3 states:
     * * AVAILABLE - Controls can be made visible
     * * AVAILABLE_AFTER_UNLOCK - Controls can be made visible only after device unlock
     * * UNAVAILABLE - Controls are not enabled
     */
    fun getVisibility(): Visibility {
        if (!isEnabled()) return Visibility.UNAVAILABLE
        if (lockPatternUtils.getStrongAuthForUser(userTracker.userHandle.identifier)
                == STRONG_AUTH_REQUIRED_AFTER_BOOT) {
            return Visibility.AVAILABLE_AFTER_UNLOCK
        }
        if (!canShowWhileLockedSetting && !keyguardStateController.isUnlocked()) {
            return Visibility.AVAILABLE_AFTER_UNLOCK
        }

        return Visibility.AVAILABLE
    }

    private fun updateShowWhileLocked() {
        canShowWhileLockedSetting = secureSettings.getInt(
            Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 0) != 0
    }

    enum class Visibility {
        AVAILABLE, AVAILABLE_AFTER_UNLOCK, UNAVAILABLE
    }
}
