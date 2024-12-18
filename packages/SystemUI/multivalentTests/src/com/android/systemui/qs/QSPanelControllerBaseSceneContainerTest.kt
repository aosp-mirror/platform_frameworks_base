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

package com.android.systemui.qs

import android.content.res.Configuration
import android.content.res.Resources
import android.testing.TestableLooper.RunWithLooper
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.haptics.qs.QSLongPressEffect
import com.android.systemui.haptics.qs.qsLongPressEffect
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.InstantTaskExecutorRule
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@EnableSceneContainer
class QSPanelControllerBaseSceneContainerTest : SysuiTestCase() {

    @Rule @JvmField val mInstantTaskExecutor = InstantTaskExecutorRule()

    private val kosmos = testKosmos()

    @Mock private lateinit var qsPanel: QSPanel
    @Mock private lateinit var qsHost: QSHost
    @Mock private lateinit var qsCustomizerController: QSCustomizerController
    @Mock private lateinit var metricsLogger: MetricsLogger
    private val uiEventLogger = UiEventLoggerFake()
    @Mock private lateinit var qsLogger: QSLogger
    private val dumpManager = DumpManager()
    @Mock private lateinit var tileLayout: PagedTileLayout
    @Mock private lateinit var resources: Resources
    private val configuration = Configuration()
    @Mock private lateinit var viewTreeObserver: ViewTreeObserver
    @Mock private lateinit var mediaHost: MediaHost

    private var isSplitShade = false
    private val splitShadeStateController =
        object : SplitShadeStateController {
            override fun shouldUseSplitNotificationShade(resources: Resources): Boolean {
                return isSplitShade
            }
        }
    private val longPressEffectProvider: Provider<QSLongPressEffect> = Provider {
        kosmos.qsLongPressEffect
    }

    private val mediaVisible = MutableStateFlow(false)

    private lateinit var underTest: TestableQSPanelControllerBase

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        allowTestableLooperAsMainThread()
        Dispatchers.setMain(kosmos.testDispatcher)

        whenever(qsPanel.isAttachedToWindow).thenReturn(true)
        whenever(qsPanel.orCreateTileLayout).thenReturn(tileLayout)
        whenever(qsPanel.tileLayout).thenReturn(tileLayout)
        whenever(qsPanel.resources).thenReturn(resources)
        whenever(qsPanel.viewTreeObserver).thenReturn(viewTreeObserver)
        whenever(qsHost.tiles).thenReturn(emptyList())
        whenever(resources.configuration).thenReturn(configuration)

        underTest = createUnderTest()
        underTest.init()
    }

    @After
    fun tearDown() {
        disallowTestableLooperAsMainThread()
        Dispatchers.resetMain()
    }

    @Test
    fun configurationChange_onlySplitShadeConfigChanges_horizontalInSceneUpdated() =
        with(kosmos) {
            testScope.runTest {
                clearInvocations(qsPanel)

                mediaVisible.value = true
                runCurrent()
                isSplitShade = false
                configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                assertThat(underTest.shouldUseHorizontalInScene()).isTrue()
                verify(qsPanel).setColumnRowLayout(true)
                clearInvocations(qsPanel)

                isSplitShade = true
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                assertThat(underTest.shouldUseHorizontalInScene()).isFalse()
                verify(qsPanel).setColumnRowLayout(false)
            }
        }

    @Test
    fun configurationChange_shouldUseHorizontalInSceneInLongDevices() =
        with(kosmos) {
            testScope.runTest {
                clearInvocations(qsPanel)

                mediaVisible.value = true
                runCurrent()
                isSplitShade = false
                // When device is rotated to landscape and is long
                configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                // Then the layout changes
                assertThat(underTest.shouldUseHorizontalInScene()).isTrue()
                verify(qsPanel).setColumnRowLayout(true)
                clearInvocations(qsPanel)

                // When device changes to not-long
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_NO
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                // Then the layout changes back
                assertThat(underTest.shouldUseHorizontalInScene()).isFalse()
                verify(qsPanel).setColumnRowLayout(false)
            }
        }

    @Test
    fun configurationChange_horizontalInScene_onlyInLandscape() =
        with(kosmos) {
            testScope.runTest {
                clearInvocations(qsPanel)

                mediaVisible.value = true
                runCurrent()
                isSplitShade = false

                // When device is rotated to landscape and is long
                configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                // Then the layout changes
                assertThat(underTest.shouldUseHorizontalInScene()).isTrue()
                verify(qsPanel).setColumnRowLayout(true)
                clearInvocations(qsPanel)

                // When it is rotated back to portrait
                configuration.orientation = Configuration.ORIENTATION_PORTRAIT
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                // Then the layout changes back
                assertThat(underTest.shouldUseHorizontalInScene()).isFalse()
                verify(qsPanel).setColumnRowLayout(false)
            }
        }

    @Test
    fun changeMediaVisible_changesHorizontalInScene() =
        with(kosmos) {
            testScope.runTest {
                mediaVisible.value = false
                runCurrent()
                isSplitShade = false
                configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES
                underTest.mOnConfigurationChangedListener.onConfigurationChange(configuration)

                assertThat(underTest.shouldUseHorizontalInScene()).isFalse()
                clearInvocations(qsPanel)

                mediaVisible.value = true
                runCurrent()

                assertThat(underTest.shouldUseHorizontalInScene()).isTrue()
                verify(qsPanel).setColumnRowLayout(true)
            }
        }

    @Test
    fun startFromMediaHorizontalLong_shouldUseHorizontal() =
        with(kosmos) {
            testScope.runTest {
                mediaVisible.value = true
                runCurrent()
                isSplitShade = false
                configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                configuration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES

                underTest = createUnderTest()
                underTest.init()
                runCurrent()

                assertThat(underTest.shouldUseHorizontalInScene()).isTrue()
                verify(qsPanel).setColumnRowLayout(true)
            }
        }

    private fun createUnderTest(): TestableQSPanelControllerBase {
        return TestableQSPanelControllerBase(
            qsPanel,
            qsHost,
            qsCustomizerController,
            mediaHost,
            metricsLogger,
            uiEventLogger,
            qsLogger,
            dumpManager,
            splitShadeStateController,
            longPressEffectProvider,
            mediaVisible,
        )
    }

    private class TestableQSPanelControllerBase(
        view: QSPanel,
        qsHost: QSHost,
        qsCustomizerController: QSCustomizerController,
        mediaHost: MediaHost,
        metricsLogger: MetricsLogger,
        uiEventLogger: UiEventLogger,
        qsLogger: QSLogger,
        dumpManager: DumpManager,
        splitShadeStateController: SplitShadeStateController,
        longPressEffectProvider: Provider<QSLongPressEffect>,
        private val mediaVisibleFlow: StateFlow<Boolean>
    ) :
        QSPanelControllerBase<QSPanel>(
            view,
            qsHost,
            qsCustomizerController,
            /* usingMediaPlayer= */ false,
            mediaHost,
            metricsLogger,
            uiEventLogger,
            qsLogger,
            dumpManager,
            splitShadeStateController,
            longPressEffectProvider
        ) {

        init {
            whenever(view.dumpableTag).thenReturn(hashCode().toString())
        }
        override fun getMediaVisibleFlow(): StateFlow<Boolean> {
            return mediaVisibleFlow
        }
    }
}
