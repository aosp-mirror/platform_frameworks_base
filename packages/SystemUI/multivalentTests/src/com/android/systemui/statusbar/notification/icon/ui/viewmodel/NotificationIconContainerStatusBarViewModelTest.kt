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

package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.graphics.Rect
import android.graphics.drawable.Icon
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.headsUpNotificationIconViewStateRepository
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher
import com.android.systemui.statusbar.phone.data.repository.fakeDarkIconRepository
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class NotificationIconContainerStatusBarViewModelTest(flags: FlagsParameterization?) :
    SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val powerRepository = kosmos.fakePowerRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val darkIconRepository = kosmos.fakeDarkIconRepository
    private val headsUpViewStateRepository = kosmos.headsUpNotificationIconViewStateRepository
    private val activeNotificationsRepository = kosmos.activeNotificationListRepository

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private val dozeParams = kosmos.dozeParameters

    lateinit var underTest: NotificationIconContainerStatusBarViewModel

    @Before
    fun setup() {
        underTest = kosmos.notificationIconContainerStatusBarViewModel
        keyguardRepository.setKeyguardShowing(false)
        powerRepository.updateWakefulness(
            rawState = WakefulnessState.AWAKE,
            lastWakeReason = WakeSleepReason.OTHER,
            lastSleepReason = WakeSleepReason.OTHER,
        )
    }

    @Test
    fun animationsEnabled_isFalse_whenDeviceAsleepAndNotPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_AOD,
                )
            )
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenDeviceAsleepAndPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_PULSING,
                )
            )
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            whenever(dozeParams.shouldControlScreenOff()).thenReturn(false)
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenStartingToSleepAndControlScreenOff() =
        testScope.runTest {
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            assertThat(animationsEnabled).isTrue()

            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            whenever(dozeParams.shouldControlScreenOff()).thenReturn(true)

            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenNotAsleep() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenKeyguardIsNotShowing() =
        testScope.runTest {
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setKeyguardShowing(true)
            runCurrent()

            assertThat(animationsEnabled).isFalse()

            keyguardRepository.setKeyguardShowing(false)
            runCurrent()

            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun iconColors_testsDarkBounds() =
        testScope.runTest {
            darkIconRepository.darkState.value =
                SysuiDarkIconDispatcher.DarkChange(
                    emptyList(),
                    0f,
                    0xAABBCC,
                )
            val iconColorsLookup by collectLastValue(underTest.iconColors)
            assertThat(iconColorsLookup).isNotNull()

            val iconColors = iconColorsLookup?.iconColors(Rect())
            assertThat(iconColors).isNotNull()
            iconColors!!

            assertThat(iconColors.tint).isEqualTo(0xAABBCC)

            val staticDrawableColor = iconColors.staticDrawableColor(Rect())

            assertThat(staticDrawableColor).isEqualTo(0xAABBCC)
        }

    @Test
    fun iconColors_staticDrawableColor_notInDarkTintArea() =
        testScope.runTest {
            darkIconRepository.darkState.value =
                SysuiDarkIconDispatcher.DarkChange(
                    listOf(Rect(0, 0, 5, 5)),
                    0f,
                    0xAABBCC,
                )
            val iconColorsLookup by collectLastValue(underTest.iconColors)
            val iconColors = iconColorsLookup?.iconColors(Rect(1, 1, 4, 4))
            val staticDrawableColor = iconColors?.staticDrawableColor(Rect(6, 6, 7, 7))
            assertThat(staticDrawableColor).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
        }

    @Test
    fun iconColors_notInDarkTintArea() =
        testScope.runTest {
            darkIconRepository.darkState.value =
                SysuiDarkIconDispatcher.DarkChange(
                    listOf(Rect(0, 0, 5, 5)),
                    0f,
                    0xAABBCC,
                )
            val iconColorsLookup by collectLastValue(underTest.iconColors)
            val iconColors = iconColorsLookup?.iconColors(Rect(6, 6, 7, 7))
            assertThat(iconColors).isNull()
        }

    @Test
    fun isolatedIcon_animateOnAppear_shadeCollapsed() =
        testScope.runTest {
            val icon: Icon = mock()
            shadeTestUtil.setShadeExpansion(0f)
            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
            assertThat(isolatedIcon?.isAnimating).isTrue()
        }

    @Test
    fun isolatedIcon_dontAnimateOnAppear_shadeExpanded() =
        testScope.runTest {
            val icon: Icon = mock()
            shadeTestUtil.setShadeExpansion(.5f)
            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
            assertThat(isolatedIcon?.isAnimating).isFalse()
        }

    @Test
    fun isolatedIcon_updateWhenIconDataChanges() =
        testScope.runTest {
            val icon: Icon = mock()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
        }

    @Test
    fun isolatedIcon_lastMessageIsFromReply_notNull() =
        testScope.runTest {
            val icon: Icon = mock()
            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon,
                                isLastMessageFromReply = true,
                            )
                        )
                    }
                    .build()

            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
        }
}
