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

import android.graphics.drawable.Drawable
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.Utils
import com.android.settingslib.drawable.UserIconDrawable
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.FakeFgsManagerController
import com.android.systemui.qs.QSSecurityFooterUtils
import com.android.systemui.qs.footer.FooterActionsTestUtils
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig
import com.android.systemui.security.data.model.SecurityModel
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.statusbar.policy.FakeSecurityController
import com.android.systemui.statusbar.policy.FakeUserInfoController
import com.android.systemui.statusbar.policy.FakeUserInfoController.FakeInfo
import com.android.systemui.statusbar.policy.MockUserSwitcherControllerWrapper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class FooterActionsViewModelTest : SysuiTestCase() {
    private lateinit var utils: FooterActionsTestUtils

    @Before
    fun setUp() {
        utils = FooterActionsTestUtils(context, TestableLooper.get(this))
    }

    @Test
    fun settingsButton() = runBlockingTest {
        val underTest = utils.footerActionsViewModel(showPowerButton = false)
        val settings = underTest.settings

        assertThat(settings.icon)
            .isEqualTo(
                Icon.Resource(
                    R.drawable.ic_settings,
                    ContentDescription.Resource(R.string.accessibility_quick_settings_settings)
                )
            )
        assertThat(settings.background).isEqualTo(R.drawable.qs_footer_action_circle)
        assertThat(settings.iconTint).isNull()
    }

    @Test
    fun powerButton() = runBlockingTest {
        // Without power button.
        val underTestWithoutPower = utils.footerActionsViewModel(showPowerButton = false)
        assertThat(underTestWithoutPower.power).isNull()

        // With power button.
        val underTestWithPower = utils.footerActionsViewModel(showPowerButton = true)
        val power = underTestWithPower.power
        assertThat(power).isNotNull()
        assertThat(power!!.icon)
            .isEqualTo(
                Icon.Resource(
                    android.R.drawable.ic_lock_power_off,
                    ContentDescription.Resource(R.string.accessibility_quick_settings_power_menu)
                )
            )
        assertThat(power.background).isEqualTo(R.drawable.qs_footer_action_circle_color)
        assertThat(power.iconTint)
            .isEqualTo(
                Utils.getColorAttrDefaultColor(
                    context,
                    com.android.internal.R.attr.textColorOnAccent,
                ),
            )
    }

    @Test
    fun userSwitcher() = runBlockingTest {
        val picture: Drawable = mock()
        val userInfoController = FakeUserInfoController(FakeInfo(picture = picture))
        val settings = FakeSettings()
        val userId = 42
        val userTracker = FakeUserTracker(userId)
        val userSwitcherControllerWrapper =
            MockUserSwitcherControllerWrapper(currentUserName = "foo")

        // Mock UserManager.
        val userManager = mock<UserManager>()
        var isUserSwitcherEnabled = false
        var isGuestUser = false
        whenever(userManager.isUserSwitcherEnabled(any())).thenAnswer { isUserSwitcherEnabled }
        whenever(userManager.isGuestUser(any())).thenAnswer { isGuestUser }

        val underTest =
            utils.footerActionsViewModel(
                showPowerButton = false,
                footerActionsInteractor =
                    utils.footerActionsInteractor(
                        userSwitcherRepository =
                            utils.userSwitcherRepository(
                                userTracker = userTracker,
                                settings = settings,
                                userManager = userManager,
                                userInfoController = userInfoController,
                                userSwitcherController = userSwitcherControllerWrapper.controller,
                            ),
                    )
            )

        // Collect the user switcher into currentUserSwitcher.
        var currentUserSwitcher: FooterActionsButtonViewModel? = null
        val job = launch { underTest.userSwitcher.collect { currentUserSwitcher = it } }
        fun currentUserSwitcher(): FooterActionsButtonViewModel? {
            // Make sure we finish collecting the current user switcher. This is necessary because
            // combined flows launch multiple coroutines in the current scope so we need to make
            // sure we process all coroutines triggered by our flow collection before we make
            // assertions on the current buttons.
            advanceUntilIdle()
            return currentUserSwitcher
        }

        // The user switcher is disabled.
        assertThat(currentUserSwitcher()).isNull()

        // Make the user manager return that the User Switcher is enabled. A change of the setting
        // for the current user will be fired to notify us of that change.
        isUserSwitcherEnabled = true

        // Update the setting for a random user: nothing should change, given that at this point we
        // weren't notified of the change yet.
        utils.setUserSwitcherEnabled(settings, true, 3)
        assertThat(currentUserSwitcher()).isNull()

        // Update the setting for the observed user: now we will be notified and the button should
        // be there.
        utils.setUserSwitcherEnabled(settings, true, userId)
        val userSwitcher = currentUserSwitcher()
        assertThat(userSwitcher).isNotNull()
        assertThat(userSwitcher!!.icon)
            .isEqualTo(Icon.Loaded(picture, ContentDescription.Loaded("Signed in as foo")))
        assertThat(userSwitcher.background).isEqualTo(R.drawable.qs_footer_action_circle)

        // Change the current user name.
        userSwitcherControllerWrapper.currentUserName = "bar"
        assertThat(currentUserSwitcher()?.icon?.contentDescription)
            .isEqualTo(ContentDescription.Loaded("Signed in as bar"))

        fun iconTint(): Int? = currentUserSwitcher()!!.iconTint

        // We tint the icon if the current user is not the guest.
        assertThat(iconTint()).isNull()

        // Make the UserManager return that the current user is the guest. A change of the user
        // info will be fired to notify us of that change.
        isGuestUser = true

        // At this point, there was no change of the user info yet so we still didn't pick the
        // UserManager change.
        assertThat(iconTint()).isNull()

        // Trigger a user info change: there should now be a tint.
        userInfoController.updateInfo { userAccount = "doe" }
        assertThat(iconTint())
            .isEqualTo(
                Utils.getColorAttrDefaultColor(
                    context,
                    android.R.attr.colorForeground,
                )
            )

        // Make sure we don't tint the icon if it is a user image (and not the default image), even
        // in guest mode.
        userInfoController.updateInfo { this.picture = mock<UserIconDrawable>() }
        assertThat(iconTint()).isNull()

        job.cancel()
    }

    @Test
    fun security() = runBlockingTest {
        val securityController = FakeSecurityController()
        val qsSecurityFooterUtils = mock<QSSecurityFooterUtils>()

        // Mock QSSecurityFooter to map a SecurityModel into a SecurityButtonConfig using the
        // logic in securityToConfig.
        var securityToConfig: (SecurityModel) -> SecurityButtonConfig? = { null }
        whenever(qsSecurityFooterUtils.getButtonConfig(any())).thenAnswer {
            securityToConfig(it.arguments.first() as SecurityModel)
        }

        val underTest =
            utils.footerActionsViewModel(
                footerActionsInteractor =
                    utils.footerActionsInteractor(
                        qsSecurityFooterUtils = qsSecurityFooterUtils,
                        securityRepository =
                            utils.securityRepository(
                                securityController = securityController,
                            ),
                    ),
            )

        // Collect the security model into currentSecurity.
        var currentSecurity: FooterActionsSecurityButtonViewModel? = null
        val job = launch { underTest.security.collect { currentSecurity = it } }
        fun currentSecurity(): FooterActionsSecurityButtonViewModel? {
            advanceUntilIdle()
            return currentSecurity
        }

        // By default, we always return a null SecurityButtonConfig.
        assertThat(currentSecurity()).isNull()

        // Map any SecurityModel into a non-null SecurityButtonConfig.
        val buttonConfig =
            SecurityButtonConfig(
                icon = Icon.Resource(res = 0, contentDescription = null),
                text = "foo",
                isClickable = true,
            )
        securityToConfig = { buttonConfig }

        // There was no change of the security info yet, so the mapper was not called yet.
        assertThat(currentSecurity()).isNull()

        // Trigger a SecurityModel change, which will call the mapper and add a button.
        securityController.updateState {}
        var security = currentSecurity()
        assertThat(security).isNotNull()
        assertThat(security!!.icon).isEqualTo(buttonConfig.icon)
        assertThat(security.text).isEqualTo(buttonConfig.text)
        assertThat(security.onClick).isNotNull()

        // If the config.clickable = false, then onClick should be null.
        securityToConfig = { buttonConfig.copy(isClickable = false) }
        securityController.updateState {}
        security = currentSecurity()
        assertThat(security).isNotNull()
        assertThat(security!!.onClick).isNull()

        job.cancel()
    }

    @Test
    fun foregroundServices() = runBlockingTest {
        val securityController = FakeSecurityController()
        val fgsManagerController =
            FakeFgsManagerController(
                isAvailable = true,
                showFooterDot = false,
                numRunningPackages = 0,
            )
        val qsSecurityFooterUtils = mock<QSSecurityFooterUtils>()

        // Mock QSSecurityFooter to map a SecurityModel into a SecurityButtonConfig using the
        // logic in securityToConfig.
        var securityToConfig: (SecurityModel) -> SecurityButtonConfig? = { null }
        whenever(qsSecurityFooterUtils.getButtonConfig(any())).thenAnswer {
            securityToConfig(it.arguments.first() as SecurityModel)
        }

        val underTest =
            utils.footerActionsViewModel(
                footerActionsInteractor =
                    utils.footerActionsInteractor(
                        qsSecurityFooterUtils = qsSecurityFooterUtils,
                        securityRepository = utils.securityRepository(securityController),
                        foregroundServicesRepository =
                            utils.foregroundServicesRepository(fgsManagerController),
                    ),
            )

        // Collect the security model into currentSecurity.
        var currentForegroundServices: FooterActionsForegroundServicesButtonViewModel? = null
        val job = launch { underTest.foregroundServices.collect { currentForegroundServices = it } }
        fun currentForegroundServices(): FooterActionsForegroundServicesButtonViewModel? {
            advanceUntilIdle()
            return currentForegroundServices
        }

        // We don't show the foreground services button if the number of running packages is not
        // > 1.
        assertThat(currentForegroundServices()).isNull()

        // We show it at soon as the number of services is at least 1. Given that there is no
        // security, it should be displayed with text.
        fgsManagerController.numRunningPackages = 1
        val foregroundServices = currentForegroundServices()
        assertThat(foregroundServices).isNotNull()
        assertThat(foregroundServices!!.foregroundServicesCount).isEqualTo(1)
        assertThat(foregroundServices.text).isEqualTo("1 app is active")
        assertThat(foregroundServices.displayText).isTrue()
        assertThat(foregroundServices.onClick).isNotNull()

        // We handle plurals correctly.
        fgsManagerController.numRunningPackages = 3
        assertThat(currentForegroundServices()?.text).isEqualTo("3 apps are active")

        // Showing new changes (the footer dot) is currently disabled.
        assertThat(foregroundServices.hasNewChanges).isFalse()

        // Enabling it will show the new changes.
        fgsManagerController.showFooterDot.value = true
        assertThat(currentForegroundServices()?.hasNewChanges).isTrue()

        // Dismissing the dialog should remove the new changes dot.
        fgsManagerController.simulateDialogDismiss()
        assertThat(currentForegroundServices()?.hasNewChanges).isFalse()

        // Showing the security button will make this show as a simple button without text.
        assertThat(foregroundServices.displayText).isTrue()
        securityToConfig = {
            SecurityButtonConfig(
                icon = Icon.Resource(res = 0, contentDescription = null),
                text = "foo",
                isClickable = true,
            )
        }
        securityController.updateState {}
        assertThat(currentForegroundServices()?.displayText).isFalse()

        job.cancel()
    }

    @Test
    fun observeDeviceMonitoringDialogRequests() = runBlockingTest {
        val qsSecurityFooterUtils = mock<QSSecurityFooterUtils>()
        val broadcastDispatcher = mock<BroadcastDispatcher>()

        // Return a fake broadcastFlow that emits 3 fake events when collected.
        val broadcastFlow = flowOf(Unit, Unit, Unit)
        whenever(
                broadcastDispatcher.broadcastFlow(
                    any(),
                    nullable(),
                    anyInt(),
                    nullable(),
                )
            )
            .thenAnswer { broadcastFlow }

        // Increment nDialogRequests whenever a request to show the dialog is made by the
        // FooterActionsInteractor.
        var nDialogRequests = 0
        whenever(qsSecurityFooterUtils.showDeviceMonitoringDialog(any(), nullable())).then {
            nDialogRequests++
        }

        val underTest =
            utils.footerActionsViewModel(
                footerActionsInteractor =
                    utils.footerActionsInteractor(
                        qsSecurityFooterUtils = qsSecurityFooterUtils,
                        broadcastDispatcher = broadcastDispatcher,
                    ),
            )

        val job = launch {
            underTest.observeDeviceMonitoringDialogRequests(quickSettingsContext = mock())
        }

        advanceUntilIdle()
        assertThat(nDialogRequests).isEqualTo(3)

        job.cancel()
    }

    @Test
    fun isVisible() {
        val underTest = utils.footerActionsViewModel()
        assertThat(underTest.isVisible.value).isTrue()

        underTest.onVisibilityChangeRequested(visible = false)
        assertThat(underTest.isVisible.value).isFalse()

        underTest.onVisibilityChangeRequested(visible = true)
        assertThat(underTest.isVisible.value).isTrue()
    }
}
