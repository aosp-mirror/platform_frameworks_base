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

package com.android.systemui.statusbar.notification.domain.interactor

import android.app.NotificationManager
import android.media.AudioManager
import android.provider.Settings.Global
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.data.repository.updateNotificationPolicy
import com.android.settingslib.notification.domain.interactor.NotificationsSoundPolicyInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationsSoundPolicyInteractorTest : SysuiTestCase() {

    @JvmField @Rule val expect = Expect.create()

    private val kosmos = testKosmos()

    private lateinit var underTest: NotificationsSoundPolicyInteractor

    @Before
    fun setup() {
        with(kosmos) { underTest = NotificationsSoundPolicyInteractor(zenModeRepository) }
    }

    @Test
    fun onlyAlarmsCategory_areAlarmsAllowed_isTrue() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_OFF)
                val expectedByCategory =
                    NotificationManager.Policy.ALL_PRIORITY_CATEGORIES.associateWith {
                        it == NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
                    }
                expectedByCategory.forEach { entry ->
                    zenModeRepository.updateNotificationPolicy(priorityCategories = entry.key)

                    val areAlarmsAllowed by collectLastValue(underTest.areAlarmsAllowed)
                    runCurrent()

                    expect.that(areAlarmsAllowed).isEqualTo(entry.value)
                }
            }
        }
    }

    @Test
    fun onlyMediaCategory_areAlarmsAllowed_isTrue() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_OFF)
                val expectedByCategory =
                    NotificationManager.Policy.ALL_PRIORITY_CATEGORIES.associateWith {
                        it == NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                    }
                expectedByCategory.forEach { entry ->
                    zenModeRepository.updateNotificationPolicy(priorityCategories = entry.key)

                    val isMediaAllowed by collectLastValue(underTest.isMediaAllowed)
                    runCurrent()

                    expect.that(isMediaAllowed).isEqualTo(entry.value)
                }
            }
        }
    }

    @Test
    fun atLeastOneCategoryAllowed_isRingerAllowed_isTrue() {
        with(kosmos) {
            testScope.runTest {
                for (category in NotificationManager.Policy.ALL_PRIORITY_CATEGORIES) {
                    zenModeRepository.updateNotificationPolicy(
                        priorityCategories = category,
                        state = NotificationManager.Policy.STATE_UNSET,
                    )

                    val isRingerAllowed by collectLastValue(underTest.isRingerAllowed)
                    runCurrent()

                    expect.that(isRingerAllowed).isTrue()
                }
            }
        }
    }

    @Test
    fun allCategoriesAllowed_isRingerAllowed_isTrue() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories =
                        NotificationManager.Policy.ALL_PRIORITY_CATEGORIES.reduce { acc, value ->
                            acc or value
                        },
                    state = NotificationManager.Policy.STATE_PRIORITY_CHANNELS_BLOCKED,
                )

                val isRingerAllowed by collectLastValue(underTest.isRingerAllowed)
                runCurrent()

                assertThat(isRingerAllowed).isTrue()
            }
        }
    }

    @Test
    fun noCategoriesAndBlocked_isRingerAllowed_isFalse() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories = 0,
                    state = NotificationManager.Policy.STATE_PRIORITY_CHANNELS_BLOCKED,
                )

                val isRingerAllowed by collectLastValue(underTest.isRingerAllowed)
                runCurrent()

                assertThat(isRingerAllowed).isFalse()
            }
        }
    }

    @Test
    fun zenModeNoInterruptions_allStreams_muted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateNotificationPolicy()
                zenModeRepository.updateZenMode(Global.ZEN_MODE_NO_INTERRUPTIONS)

                for (stream in AudioStream.supportedStreamTypes) {
                    val isZenMuted by collectLastValue(underTest.isZenMuted(AudioStream(stream)))
                    runCurrent()

                    expect.that(isZenMuted).isTrue()
                }
            }
        }
    }

    @Test
    fun zenModeOff_allStreams_notMuted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateNotificationPolicy()
                zenModeRepository.updateZenMode(Global.ZEN_MODE_OFF)

                for (stream in AudioStream.supportedStreamTypes) {
                    val isZenMuted by collectLastValue(underTest.isZenMuted(AudioStream(stream)))
                    runCurrent()

                    expect.that(isZenMuted).isFalse()
                }
            }
        }
    }

    @Test
    fun zenModeAlarms_ringedStreams_muted() {
        with(kosmos) {
            val expectedToBeMuted =
                setOf(
                    AudioManager.STREAM_RING,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_SYSTEM,
                )
            testScope.runTest {
                zenModeRepository.updateNotificationPolicy()
                zenModeRepository.updateZenMode(Global.ZEN_MODE_ALARMS)

                for (stream in AudioStream.supportedStreamTypes) {
                    val isZenMuted by collectLastValue(underTest.isZenMuted(AudioStream(stream)))
                    runCurrent()

                    expect.that(isZenMuted).isEqualTo(stream in expectedToBeMuted)
                }
            }
        }
    }

    @Test
    fun alarms_allowed_notMuted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
                )

                val isZenMuted by
                    collectLastValue(underTest.isZenMuted(AudioStream(AudioManager.STREAM_ALARM)))
                runCurrent()

                expect.that(isZenMuted).isFalse()
            }
        }
    }

    @Test
    fun media_allowed_notMuted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                )

                val isZenMuted by
                    collectLastValue(underTest.isZenMuted(AudioStream(AudioManager.STREAM_MUSIC)))
                runCurrent()

                expect.that(isZenMuted).isFalse()
            }
        }
    }

    @Test
    fun ringer_allowed_notificationsNotMuted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories =
                        NotificationManager.Policy.ALL_PRIORITY_CATEGORIES.reduce { acc, value ->
                            acc or value
                        },
                    state = NotificationManager.Policy.STATE_PRIORITY_CHANNELS_BLOCKED,
                )

                val isZenMuted by
                    collectLastValue(
                        underTest.isZenMuted(AudioStream(AudioManager.STREAM_NOTIFICATION))
                    )
                runCurrent()

                expect.that(isZenMuted).isFalse()
            }
        }
    }

    @Test
    fun ringer_allowed_ringNotMuted() {
        with(kosmos) {
            testScope.runTest {
                zenModeRepository.updateZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                zenModeRepository.updateNotificationPolicy(
                    priorityCategories =
                        NotificationManager.Policy.ALL_PRIORITY_CATEGORIES.reduce { acc, value ->
                            acc or value
                        },
                    state = NotificationManager.Policy.STATE_PRIORITY_CHANNELS_BLOCKED,
                )

                val isZenMuted by
                    collectLastValue(underTest.isZenMuted(AudioStream(AudioManager.STREAM_RING)))
                runCurrent()

                expect.that(isZenMuted).isFalse()
            }
        }
    }
}
