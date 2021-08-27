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

package com.android.systemui.qs.user

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.tiles.UserDetailView
import javax.inject.Inject
import javax.inject.Provider

/**
 * Controller for [UserDialog].
 */
@SysUISingleton
class UserSwitchDialogController @VisibleForTesting constructor(
    private val userDetailViewAdapterProvider: Provider<UserDetailView.Adapter>,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val dialogFactory: (Context) -> UserDialog
) {

    @Inject
    constructor(
        userDetailViewAdapterProvider: Provider<UserDetailView.Adapter>,
        activityStarter: ActivityStarter,
        falsingManager: FalsingManager
    ) : this(
        userDetailViewAdapterProvider,
        activityStarter,
        falsingManager,
        { UserDialog(it) }
    )

    companion object {
        private val USER_SETTINGS_INTENT = Intent(Settings.ACTION_USER_SETTINGS)
    }

    /**
     * Show a [UserDialog].
     *
     * Populate the dialog with information from and adapter obtained from
     * [userDetailViewAdapterProvider] and show it as launched from [view].
     */
    fun showDialog(view: View) {
        with(dialogFactory(view.context)) {
            setShowForAllUsers(true)
            setCanceledOnTouchOutside(true)
            create() // Needs to be called before we can retrieve views

            settingsButton.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    activityStarter.postStartActivityDismissingKeyguard(USER_SETTINGS_INTENT, 0)
                }
                dismiss()
            }
            doneButton.setOnClickListener { dismiss() }

            val adapter = userDetailViewAdapterProvider.get()
            adapter.injectCallback {
                dismiss()
            }
            adapter.linkToViewGroup(grid)

            show()
        }
    }
}