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

package com.android.systemui.volume.panel.ui.viewmodel

import android.content.Intent
import android.content.applicationContext
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.fakeConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.volume.panel.dagger.factory.volumePanelComponentFactory
import com.android.systemui.volume.panel.data.repository.volumePanelGlobalStateRepository
import com.android.systemui.volume.panel.domain.interactor.criteriaByKey
import com.android.systemui.volume.panel.domain.interactor.volumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.domain.unavailableCriteria
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.shared.model.mockVolumePanelUiComponentProvider
import com.android.systemui.volume.panel.ui.composable.componentByKey
import com.android.systemui.volume.panel.ui.layout.DefaultComponentsLayoutManager
import com.android.systemui.volume.panel.ui.layout.componentsLayoutManager
import com.android.systemui.volume.shared.volumePanelLogger
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VolumePanelViewModelTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            componentsLayoutManager = DefaultComponentsLayoutManager(BOTTOM_BAR)
            volumePanelGlobalStateRepository.updateVolumePanelState { it.copy(isVisible = true) }
        }

    private val realDumpManager = DumpManager()
    private val testableResources = context.orCreateTestableResources

    private lateinit var underTest: VolumePanelViewModel

    @Test
    fun dismissingPanel_changesVisibility() = test {
        testScope.runTest {
            underTest.dismissPanel()
            runCurrent()

            assertThat(volumePanelGlobalStateRepository.globalState.value.isVisible).isFalse()
        }
    }

    @Test
    fun orientationChanges_panelOrientationChanges() = test {
        testScope.runTest {
            val volumePanelState by collectLastValue(underTest.volumePanelState)
            testableResources.overrideConfiguration(
                Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
            )
            assertThat(volumePanelState!!.orientation).isEqualTo(Configuration.ORIENTATION_PORTRAIT)

            fakeConfigurationController.onConfigurationChanged(
                Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            )
            runCurrent()

            assertThat(volumePanelState!!.orientation)
                .isEqualTo(Configuration.ORIENTATION_LANDSCAPE)
        }
    }

    @Test
    fun components_areReturned() =
        test({
            componentByKey =
                mapOf(
                    COMPONENT_1 to mockVolumePanelUiComponentProvider,
                    COMPONENT_2 to mockVolumePanelUiComponentProvider,
                    BOTTOM_BAR to mockVolumePanelUiComponentProvider,
                )
            criteriaByKey = mapOf(COMPONENT_2 to Provider { unavailableCriteria })
        }) {
            testScope.runTest {
                val componentsLayout by collectLastValue(underTest.componentsLayout)
                runCurrent()

                assertThat(componentsLayout!!.contentComponents).hasSize(2)
                assertThat(componentsLayout!!.contentComponents[0].key).isEqualTo(COMPONENT_1)
                assertThat(componentsLayout!!.contentComponents[0].isVisible).isTrue()
                assertThat(componentsLayout!!.contentComponents[1].key).isEqualTo(COMPONENT_2)
                assertThat(componentsLayout!!.contentComponents[1].isVisible).isFalse()
                assertThat(componentsLayout!!.bottomBarComponent.key).isEqualTo(BOTTOM_BAR)
                assertThat(componentsLayout!!.bottomBarComponent.isVisible).isTrue()
            }
        }

    @Test
    fun dismissPanel_dismissesPanel() = test {
        testScope.runTest {
            underTest.dismissPanel()
            runCurrent()

            assertThat(volumePanelGlobalStateRepository.globalState.value.isVisible).isFalse()
        }
    }

    @Test
    fun testDumpableRegister_unregister() =
        with(kosmos) {
            testScope.runTest {
                val job = launch {
                    applicationCoroutineScope = this
                    underTest = createViewModel()

                    runCurrent()

                    assertThat(realDumpManager.getDumpables().any { it.name == DUMPABLE_NAME })
                        .isTrue()
                }

                runCurrent()
                job.cancel()

                assertThat(realDumpManager.getDumpables().any { it.name == DUMPABLE_NAME }).isTrue()
            }
        }

    @Test
    fun testDumpingState() =
        test({
            testableResources.addOverride(R.bool.volume_panel_is_large_screen, false)
            testableResources.overrideConfiguration(
                Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
            )
            componentByKey =
                mapOf(
                    COMPONENT_1 to mockVolumePanelUiComponentProvider,
                    COMPONENT_2 to mockVolumePanelUiComponentProvider,
                    BOTTOM_BAR to mockVolumePanelUiComponentProvider,
                )
            criteriaByKey = mapOf(COMPONENT_2 to Provider { unavailableCriteria })
        }) {
            testScope.runTest {
                runCurrent()

                StringWriter().use {
                    underTest.dump(PrintWriter(it), emptyArray())

                    assertThat(it.buffer.toString())
                        .isEqualTo(
                            "volumePanelState=" +
                                "VolumePanelState(orientation=1, isLargeScreen=false)\n" +
                                "componentsLayout=( " +
                                "headerComponents= " +
                                "contentComponents=" +
                                "test_component:1:visible=true, " +
                                "test_component:2:visible=false " +
                                "footerComponents= " +
                                "bottomBarComponent=test_bottom_bar:visible=true )\n"
                        )
                }
            }
        }

    @Test
    fun dismissBroadcast_dismissesPanel() = test {
        testScope.runTest {
            runCurrent() // run the flows to let allow the receiver to be registered
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                applicationContext,
                Intent(DISMISS_ACTION),
            )
            runCurrent()

            assertThat(volumePanelGlobalStateRepository.globalState.value.isVisible).isFalse()
        }
    }

    private fun test(setup: Kosmos.() -> Unit = {}, test: Kosmos.() -> Unit) =
        with(kosmos) {
            setup()
            underTest = createViewModel()

            test()
        }

    private fun Kosmos.createViewModel(): VolumePanelViewModel =
        VolumePanelViewModel(
            context.orCreateTestableResources.resources,
            applicationCoroutineScope,
            volumePanelComponentFactory,
            configurationController,
            broadcastDispatcher,
            realDumpManager,
            volumePanelLogger,
            volumePanelGlobalStateInteractor,
        )

    private companion object {
        const val DUMPABLE_NAME = "VolumePanelViewModel"

        const val BOTTOM_BAR: VolumePanelComponentKey = "test_bottom_bar"
        const val COMPONENT_1: VolumePanelComponentKey = "test_component:1"
        const val COMPONENT_2: VolumePanelComponentKey = "test_component:2"

        const val DISMISS_ACTION = "com.android.systemui.action.DISMISS_VOLUME_PANEL_DIALOG"
    }
}
