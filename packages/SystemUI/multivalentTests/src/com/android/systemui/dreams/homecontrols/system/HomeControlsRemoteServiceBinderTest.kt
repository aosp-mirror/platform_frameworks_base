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

package com.android.systemui.dreams.homecontrols.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.pm.UserInfo
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.authorizedPanelsRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dreams.homecontrols.shared.IOnControlsSettingsChangeListener
import com.android.systemui.dreams.homecontrols.system.domain.interactor.controlsComponent
import com.android.systemui.dreams.homecontrols.system.domain.interactor.controlsListingController
import com.android.systemui.dreams.homecontrols.system.domain.interactor.homeControlsComponentInteractor
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsRemoteServiceBinderTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val lifecycleOwner = TestLifecycleOwner(coroutineDispatcher = kosmos.testDispatcher)
    private val fakeControlsSettingsRepository = FakeControlsSettingsRepository()

    private val underTest by lazy {
        HomeControlsRemoteServiceBinder(
            kosmos.homeControlsComponentInteractor,
            fakeControlsSettingsRepository,
            kosmos.backgroundCoroutineContext,
            logcatLogBuffer(),
            lifecycleOwner,
        )
    }

    @Before
    fun setUp() {
        with(kosmos) {
            fakeUserRepository.setUserInfos(listOf(PRIMARY_USER))
            whenever(controlsComponent.getControlsListingController())
                .thenReturn(Optional.of(controlsListingController))
        }
    }

    @Test
    fun testRegisterSingleListener() =
        testScope.runTest {
            setup()
            val controlsSettings by collectLastValue(addCallback())
            runServicesUpdate()

            assertThat(controlsSettings)
                .isEqualTo(
                    CallbackArgs(
                        panelComponent = TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = false,
                    )
                )
        }

    @Test
    fun testRegisterMultipleListeners() =
        testScope.runTest {
            setup()
            val controlsSettings1 by collectLastValue(addCallback())
            val controlsSettings2 by collectLastValue(addCallback())
            runServicesUpdate()

            assertThat(controlsSettings1)
                .isEqualTo(
                    CallbackArgs(
                        panelComponent = TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = false,
                    )
                )
            assertThat(controlsSettings2)
                .isEqualTo(
                    CallbackArgs(
                        panelComponent = TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = false,
                    )
                )
        }

    @Test
    fun testListenerCalledWhenStateChanges() =
        testScope.runTest {
            setup()
            val controlsSettings by collectLastValue(addCallback())
            runServicesUpdate()

            assertThat(controlsSettings)
                .isEqualTo(
                    CallbackArgs(
                        panelComponent = TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = false,
                    )
                )

            kosmos.authorizedPanelsRepository.removeAuthorizedPanels(setOf(TEST_PACKAGE))

            // Updated with null component now that we are no longer authorized.
            assertThat(controlsSettings)
                .isEqualTo(
                    CallbackArgs(panelComponent = null, allowTrivialControlsOnLockscreen = false)
                )
        }

    private fun TestScope.runServicesUpdate() {
        runCurrent()
        val listings = listOf(ControlsServiceInfo(TEST_COMPONENT, "panel", hasPanel = true))
        val callback = withArgCaptor {
            Mockito.verify(kosmos.controlsListingController).addCallback(capture())
        }
        callback.onServicesUpdated(listings)
        runCurrent()
    }

    private fun addCallback() = conflatedCallbackFlow {
        val callback =
            object : IOnControlsSettingsChangeListener.Stub() {
                override fun onControlsSettingsChanged(
                    panelComponent: ComponentName?,
                    allowTrivialControlsOnLockscreen: Boolean,
                ) {
                    trySend(CallbackArgs(panelComponent, allowTrivialControlsOnLockscreen))
                }
            }
        underTest.registerListenerForCurrentUser(callback)
        awaitClose { underTest.unregisterListenerForCurrentUser(callback) }
    }

    private suspend fun TestScope.setup() {
        kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
        kosmos.fakeUserTracker.set(listOf(PRIMARY_USER), 0)
        kosmos.authorizedPanelsRepository.addAuthorizedPanels(setOf(TEST_PACKAGE))
        kosmos.selectedComponentRepository.setSelectedComponent(TEST_SELECTED_COMPONENT_PANEL)
        runCurrent()
    }

    private data class CallbackArgs(
        val panelComponent: ComponentName?,
        val allowTrivialControlsOnLockscreen: Boolean,
    )

    private fun ControlsServiceInfo(
        componentName: ComponentName,
        label: CharSequence,
        hasPanel: Boolean,
    ): ControlsServiceInfo {
        val serviceInfo =
            ServiceInfo().apply {
                applicationInfo = ApplicationInfo()
                packageName = componentName.packageName
                name = componentName.className
            }
        return FakeControlsServiceInfo(context, serviceInfo, label, hasPanel)
    }

    private class FakeControlsServiceInfo(
        context: Context,
        serviceInfo: ServiceInfo,
        private val label: CharSequence,
        hasPanel: Boolean,
    ) : ControlsServiceInfo(context, serviceInfo) {

        init {
            if (hasPanel) {
                panelActivity = serviceInfo.componentName
            }
        }

        override fun loadLabel(): CharSequence {
            return label
        }
    }

    private companion object {
        const val PRIMARY_USER_ID = 0
        val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY,
            )

        private const val TEST_PACKAGE = "pkg"
        private val TEST_COMPONENT = ComponentName(TEST_PACKAGE, "service")
        private val TEST_SELECTED_COMPONENT_PANEL =
            SelectedComponentRepository.SelectedComponent(TEST_PACKAGE, TEST_COMPONENT, true)
    }
}
