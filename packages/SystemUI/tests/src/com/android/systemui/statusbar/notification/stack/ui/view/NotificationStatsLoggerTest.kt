/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack.ui.view

import android.service.notification.notificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.NotificationVisibility
import com.android.internal.statusbar.statusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.logging.nano.Notifications
import com.android.systemui.statusbar.notification.logging.notificationPanelLogger
import com.android.systemui.statusbar.notification.stack.ExpandableViewState
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationStatsLoggerTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val testScope = kosmos.testScope
    private val mockNotificationListenerService = kosmos.notificationListenerService
    private val mockPanelLogger = kosmos.notificationPanelLogger
    private val mockStatusBarService = kosmos.statusBarService

    private val underTest = kosmos.notificationStatsLogger

    private val visibilityArrayCaptor = argumentCaptor<Array<NotificationVisibility>>()
    private val stringArrayCaptor = argumentCaptor<Array<String>>()
    private val notificationListProtoCaptor = argumentCaptor<Notifications.NotificationList>()

    @Test
    fun onNotificationListUpdated_itemsAdded_logsNewlyVisibleItems() =
        testScope.runTest {
            // WHEN new Notifications are added
            // AND they're visible
            val (ranks, locations) = fakeNotificationMaps("key0", "key1")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()

            // THEN visibility changes are reported
            verify(mockStatusBarService)
                .onNotificationVisibilityChanged(visibilityArrayCaptor.capture(), eq(emptyArray()))
            verify(mockNotificationListenerService)
                .setNotificationsShown(stringArrayCaptor.capture())
            val loggedVisibilities = visibilityArrayCaptor.value
            val loggedKeys = stringArrayCaptor.value
            assertThat(loggedVisibilities).hasLength(2)
            assertThat(loggedKeys).hasLength(2)
            assertThat(loggedVisibilities[0]).apply {
                isKeyEqualTo("key0")
                isRankEqualTo(0)
                isVisible()
                isInMainArea()
                isCountEqualTo(2)
            }
            assertThat(loggedVisibilities[1]).apply {
                isKeyEqualTo("key1")
                isRankEqualTo(1)
                isVisible()
                isInMainArea()
                isCountEqualTo(2)
            }
            assertThat(loggedKeys[0]).isEqualTo("key0")
            assertThat(loggedKeys[1]).isEqualTo("key1")
        }

    @Test
    fun onNotificationListUpdated_itemsRemoved_logsNoLongerVisibleItems() =
        testScope.runTest {
            // GIVEN some visible Notifications are reported
            val (ranks, locations) = fakeNotificationMaps("key0", "key1")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()
            clearInvocations(mockStatusBarService, mockNotificationListenerService)

            // WHEN the same Notifications are removed
            val emptyCallable = Callable { emptyMap<String, Int>() }
            underTest.onNotificationLocationsChanged(emptyCallable, emptyMap())
            runCurrent()

            // THEN visibility changes are reported
            verify(mockStatusBarService)
                .onNotificationVisibilityChanged(eq(emptyArray()), visibilityArrayCaptor.capture())
            verifyZeroInteractions(mockNotificationListenerService)
            val noLongerVisible = visibilityArrayCaptor.value
            assertThat(noLongerVisible).hasLength(2)
            assertThat(noLongerVisible[0]).apply {
                isKeyEqualTo("key0")
                isRankEqualTo(0)
                notVisible()
                isInMainArea()
                isCountEqualTo(0)
            }
            assertThat(noLongerVisible[1]).apply {
                isKeyEqualTo("key1")
                isRankEqualTo(1)
                notVisible()
                isInMainArea()
                isCountEqualTo(0)
            }
        }

    @Test
    fun onNotificationListUpdated_itemsBecomeInvisible_logsNoLongerVisibleItems() =
        testScope.runTest {
            // GIVEN some visible Notifications are reported
            val (ranks, locations) = fakeNotificationMaps("key0", "key1")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()
            clearInvocations(mockStatusBarService, mockNotificationListenerService)

            // WHEN the same Notifications are becoming invisible
            val emptyCallable = Callable { emptyMap<String, Int>() }
            underTest.onNotificationLocationsChanged(emptyCallable, ranks)
            runCurrent()

            // THEN visibility changes are reported
            verify(mockStatusBarService)
                .onNotificationVisibilityChanged(eq(emptyArray()), visibilityArrayCaptor.capture())
            verifyZeroInteractions(mockNotificationListenerService)
            val noLongerVisible = visibilityArrayCaptor.value
            assertThat(noLongerVisible).hasLength(2)
            assertThat(noLongerVisible[0]).apply {
                isKeyEqualTo("key0")
                isRankEqualTo(0)
                notVisible()
                isInMainArea()
                isCountEqualTo(2)
            }
            assertThat(noLongerVisible[1]).apply {
                isKeyEqualTo("key1")
                isRankEqualTo(1)
                notVisible()
                isInMainArea()
                isCountEqualTo(2)
            }
        }

    @Test
    fun onNotificationListUpdated_itemsChangedPositions_nothingLogged() =
        testScope.runTest {
            // GIVEN some visible Notifications are reported
            val (ranks, locations) = fakeNotificationMaps("key0", "key1")
            underTest.onNotificationLocationsChanged({ locations }, ranks)
            runCurrent()
            clearInvocations(mockStatusBarService, mockNotificationListenerService)

            // WHEN the reported Notifications are changing positions
            val (newRanks, newLocations) = fakeNotificationMaps("key1", "key0")
            underTest.onNotificationLocationsChanged({ newLocations }, newRanks)
            runCurrent()

            // THEN no visibility changes are reported
            verifyZeroInteractions(mockStatusBarService, mockNotificationListenerService)
        }

    @Test
    fun onNotificationListUpdated_calledTwice_usesTheNewCallable() =
        testScope.runTest {
            // GIVEN some visible Notifications are reported
            val (ranks, locations) = fakeNotificationMaps("key0", "key1", "key2")
            val callable = spy(Callable { locations })
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()
            clearInvocations(callable)

            // WHEN a new update comes
            val otherCallable = spy(Callable { locations })
            underTest.onNotificationLocationsChanged(otherCallable, ranks)
            runCurrent()

            // THEN we call the new Callable
            verifyZeroInteractions(callable)
            verify(otherCallable).call()
        }

    @Test
    fun onLockscreenOrShadeNotInteractive_logsNoLongerVisibleItems() =
        testScope.runTest {
            // GIVEN some visible Notifications are reported
            val (ranks, locations) = fakeNotificationMaps("key0", "key1")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()
            clearInvocations(mockStatusBarService, mockNotificationListenerService)

            // WHEN the Shade becomes non interactive
            underTest.onLockscreenOrShadeNotInteractive(emptyList())
            runCurrent()

            // THEN visibility changes are reported
            verify(mockStatusBarService)
                .onNotificationVisibilityChanged(eq(emptyArray()), visibilityArrayCaptor.capture())
            verifyZeroInteractions(mockNotificationListenerService)
            val noLongerVisible = visibilityArrayCaptor.value
            assertThat(noLongerVisible).hasLength(2)
            assertThat(noLongerVisible[0]).apply {
                isKeyEqualTo("key0")
                isRankEqualTo(0)
                notVisible()
                isInMainArea()
                isCountEqualTo(0)
            }
            assertThat(noLongerVisible[1]).apply {
                isKeyEqualTo("key1")
                isRankEqualTo(1)
                notVisible()
                isInMainArea()
                isCountEqualTo(0)
            }
        }

    @Test
    fun onLockscreenOrShadeInteractive_logsPanelShown() =
        testScope.runTest {
            // WHEN the Shade becomes interactive
            underTest.onLockscreenOrShadeInteractive(
                isOnLockScreen = true,
                listOf(
                    activeNotificationModel(
                        key = "key0",
                        uid = 0,
                        packageName = "com.android.first"
                    ),
                    activeNotificationModel(
                        key = "key1",
                        uid = 1,
                        packageName = "com.android.second"
                    ),
                )
            )
            runCurrent()

            // THEN the Panel shown event is reported
            verify(mockPanelLogger).logPanelShown(eq(true), notificationListProtoCaptor.capture())
            val loggedNotifications = notificationListProtoCaptor.value.notifications
            assertThat(loggedNotifications.size).isEqualTo(2)
            with(loggedNotifications[0]) {
                assertThat(uid).isEqualTo(0)
                assertThat(packageName).isEqualTo("com.android.first")
            }
            with(loggedNotifications[1]) {
                assertThat(uid).isEqualTo(1)
                assertThat(packageName).isEqualTo("com.android.second")
            }
        }

    @Test
    fun onNotificationExpansionChanged_whenExpandedInVisibleLocation_logsExpansion() =
        testScope.runTest {
            // WHEN a Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()

            // THEN the Expand event is reported
            verify(mockStatusBarService)
                .onNotificationExpansionChanged(
                    /* key = */ "key",
                    /* userAction = */ true,
                    /* expanded = */ true,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.ordinal
                )
        }

    @Test
    fun onNotificationExpansionChanged_whenCalledTwiceWithTheSameUpdate_doesNotDuplicateLogs() =
        testScope.runTest {
            // GIVEN a Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()
            clearInvocations(mockStatusBarService)

            // WHEN the logger receives the same expansion update
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()

            // THEN the Expand event is not reported again
            verifyZeroInteractions(mockStatusBarService)
        }

    @Test
    fun onNotificationExpansionChanged_whenCalledForNotVisibleItem_nothingLogged() =
        testScope.runTest {
            // WHEN a NOT visible Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_BOTTOM_STACK_HIDDEN,
                isUserAction = true
            )
            runCurrent()

            // No events are reported
            verifyZeroInteractions(mockStatusBarService)
        }

    @Test
    fun onNotificationExpansionChanged_whenNotVisibleItemBecomesVisible_logsChanges() =
        testScope.runTest {
            // WHEN a NOT visible Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_GONE,
                isUserAction = false
            )
            runCurrent()

            // AND it becomes visible
            val (ranks, locations) = fakeNotificationMaps("key")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()

            // THEN the Expand event is reported
            verify(mockStatusBarService)
                .onNotificationExpansionChanged(
                    /* key = */ "key",
                    /* userAction = */ false,
                    /* expanded = */ true,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.ordinal
                )
        }

    @Test
    fun onNotificationExpansionChanged_whenUpdatedItemBecomesVisible_logsChanges() =
        testScope.runTest {
            // GIVEN a NOT visible Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_GONE,
                isUserAction = false
            )
            runCurrent()
            // AND we open the shade, so we log its events
            val (ranks, locations) = fakeNotificationMaps("key")
            val callable = Callable { locations }
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()
            // AND we close the shade, so it is NOT visible
            val emptyCallable = Callable { emptyMap<String, Int>() }
            underTest.onNotificationLocationsChanged(emptyCallable, ranks)
            runCurrent()
            clearInvocations(mockStatusBarService) // clear the previous expand log

            // WHEN it receives an update
            underTest.onNotificationUpdated("key")
            // AND it becomes visible again
            underTest.onNotificationLocationsChanged(callable, ranks)
            runCurrent()

            // THEN we log its expand event again
            verify(mockStatusBarService)
                .onNotificationExpansionChanged(
                    /* key = */ "key",
                    /* userAction = */ false,
                    /* expanded = */ true,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.ordinal
                )
        }

    @Test
    fun onNotificationExpansionChanged_whenCollapsedForTheFirstTime_nothingLogged() =
        testScope.runTest {
            // WHEN a Notification is collapsed, and it is the first interaction
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = false,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = false
            )
            runCurrent()

            // THEN no events are reported, because we consider the Notification initially
            // collapsed, so only expanded is logged in the first time.
            verifyZeroInteractions(mockStatusBarService)
        }

    @Test
    fun onNotificationExpansionChanged_receivesMultipleUpdates_logsChanges() =
        testScope.runTest {
            // GIVEN a Notification is expanded
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()

            // WHEN the Notification is collapsed
            verify(mockStatusBarService)
                .onNotificationExpansionChanged(
                    /* key = */ "key",
                    /* userAction = */ true,
                    /* expanded = */ true,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.ordinal
                )

            // AND the Notification is expanded again
            underTest.onNotificationExpansionChanged(
                key = "key",
                isExpanded = false,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()

            // THEN the expansion changes are logged
            verify(mockStatusBarService)
                .onNotificationExpansionChanged(
                    /* key = */ "key",
                    /* userAction = */ true,
                    /* expanded = */ false,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.ordinal
                )
        }

    @Test
    fun onNotificationUpdated_clearsTrackedExpansionChanges() =
        testScope.runTest {
            // GIVEN some notification updates are posted
            underTest.onNotificationExpansionChanged(
                key = "key1",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()
            underTest.onNotificationExpansionChanged(
                key = "key2",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()
            clearInvocations(mockStatusBarService)

            // WHEN a Notification is updated
            underTest.onNotificationUpdated("key1")

            // THEN the tracked expansion changes are updated
            assertThat(underTest.lastReportedExpansionValues.keys).containsExactly("key2")
        }

    @Test
    fun onNotificationRemoved_clearsTrackedExpansionChanges() =
        testScope.runTest {
            // GIVEN some notification updates are posted
            underTest.onNotificationExpansionChanged(
                key = "key1",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()
            underTest.onNotificationExpansionChanged(
                key = "key2",
                isExpanded = true,
                location = ExpandableViewState.LOCATION_MAIN_AREA,
                isUserAction = true
            )
            runCurrent()
            clearInvocations(mockStatusBarService)

            // WHEN a Notification is removed
            underTest.onNotificationRemoved("key1")

            // THEN it is removed from the tracked expansion changes
            assertThat(underTest.lastReportedExpansionValues.keys).doesNotContain("key1")
        }

    private fun fakeNotificationMaps(
        vararg keys: String
    ): Pair<Map<String, Int>, Map<String, Int>> {
        val ranks: Map<String, Int> = keys.mapIndexed { index, key -> key to index }.toMap()
        val locations: Map<String, Int> =
            keys.associateWith { ExpandableViewState.LOCATION_MAIN_AREA }

        return Pair(ranks, locations)
    }

    private fun assertThat(visibility: NotificationVisibility) =
        NotificationVisibilitySubject(visibility)
}

private class NotificationVisibilitySubject(private val visibility: NotificationVisibility) {
    fun isKeyEqualTo(key: String) = assertThat(visibility.key).isEqualTo(key)
    fun isRankEqualTo(rank: Int) = assertThat(visibility.rank).isEqualTo(rank)
    fun isCountEqualTo(count: Int) = assertThat(visibility.count).isEqualTo(count)
    fun isVisible() = assertThat(this.visibility.visible).isTrue()
    fun notVisible() = assertThat(this.visibility.visible).isFalse()
    fun isInMainArea() =
        assertThat(this.visibility.location)
            .isEqualTo(NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA)
}
