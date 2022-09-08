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

package com.android.systemui.qs.footer

import android.content.Context
import android.os.UserHandle
import android.view.View
import com.android.internal.util.UserIcons
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.qs.footer.data.model.UserSwitcherStatusModel
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A list of fake [FooterActionsViewModel] to be used in screenshot tests and the gallery. */
fun fakeFooterActionsViewModels(
    @Application context: Context,
): List<FooterActionsViewModel> {
    return listOf(
        fakeFooterActionsViewModel(context),
        fakeFooterActionsViewModel(context, showPowerButton = false, isGuestUser = true),
        fakeFooterActionsViewModel(context, showUserSwitcher = false),
        fakeFooterActionsViewModel(context, showUserSwitcher = false, foregroundServices = 4),
        fakeFooterActionsViewModel(
            context,
            foregroundServices = 4,
            hasNewForegroundServices = true,
            userId = 1,
        ),
        fakeFooterActionsViewModel(
            context,
            securityText = "Security",
            foregroundServices = 4,
            showUserSwitcher = false,
        ),
        fakeFooterActionsViewModel(
            context,
            securityText = "Security (not clickable)",
            securityClickable = false,
            foregroundServices = 4,
            hasNewForegroundServices = true,
            userId = 2,
        ),
    )
}

private fun fakeFooterActionsViewModel(
    @Application context: Context,
    securityText: String? = null,
    securityClickable: Boolean = true,
    foregroundServices: Int = 0,
    hasNewForegroundServices: Boolean = false,
    showUserSwitcher: Boolean = true,
    showPowerButton: Boolean = true,
    userId: Int = UserHandle.USER_OWNER,
    isGuestUser: Boolean = false,
): FooterActionsViewModel {
    val interactor =
        FakeFooterActionsInteractor(
            securityButtonConfig =
                flowOf(
                    securityText?.let { text ->
                        SecurityButtonConfig(
                            icon =
                                Icon.Resource(
                                    R.drawable.ic_info_outline,
                                    contentDescription = null,
                                ),
                            text = text,
                            isClickable = securityClickable,
                        )
                    }
                ),
            foregroundServicesCount = flowOf(foregroundServices),
            hasNewForegroundServices = flowOf(hasNewForegroundServices),
            userSwitcherStatus =
                flowOf(
                    if (showUserSwitcher) {
                        UserSwitcherStatusModel.Enabled(
                            currentUserName = "foo",
                            currentUserImage =
                                UserIcons.getDefaultUserIcon(
                                    context.resources,
                                    userId,
                                    /* light= */ false,
                                ),
                            isGuestUser = isGuestUser,
                        )
                    } else {
                        UserSwitcherStatusModel.Disabled
                    }
                ),
            deviceMonitoringDialogRequests = flowOf(),
        )

    return FooterActionsViewModel(
        context,
        interactor,
        FalsingManagerFake(),
        globalActionsDialogLite = mock(),
        showPowerButton = showPowerButton,
    )
}

private class FakeFooterActionsInteractor(
    override val securityButtonConfig: Flow<SecurityButtonConfig?> = flowOf(null),
    override val foregroundServicesCount: Flow<Int> = flowOf(0),
    override val hasNewForegroundServices: Flow<Boolean> = flowOf(false),
    override val userSwitcherStatus: Flow<UserSwitcherStatusModel> =
        flowOf(UserSwitcherStatusModel.Disabled),
    override val deviceMonitoringDialogRequests: Flow<Unit> = flowOf(),
    private val onShowDeviceMonitoringDialogFromView: (View) -> Unit = {},
    private val onShowDeviceMonitoringDialog: (Context) -> Unit = {},
    private val onShowForegroundServicesDialog: (View) -> Unit = {},
    private val onShowPowerMenuDialog: (GlobalActionsDialogLite, View) -> Unit = { _, _ -> },
    private val onShowSettings: (Expandable) -> Unit = {},
    private val onShowUserSwitcher: (View) -> Unit = {},
) : FooterActionsInteractor {
    override fun showDeviceMonitoringDialog(view: View) {
        onShowDeviceMonitoringDialogFromView(view)
    }

    override fun showDeviceMonitoringDialog(quickSettingsContext: Context) {
        onShowDeviceMonitoringDialog(quickSettingsContext)
    }

    override fun showForegroundServicesDialog(view: View) {
        onShowForegroundServicesDialog(view)
    }

    override fun showPowerMenuDialog(globalActionsDialogLite: GlobalActionsDialogLite, view: View) {
        onShowPowerMenuDialog(globalActionsDialogLite, view)
    }

    override fun showSettings(expandable: Expandable) {
        onShowSettings(expandable)
    }

    override fun showUserSwitcher(view: View) {
        onShowUserSwitcher(view)
    }
}
