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

package com.android.systemui.qs.ui.adapter

import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.QSImpl
import com.android.systemui.qs.dagger.QSComponent
import com.android.systemui.qs.dagger.QSSceneComponent
import com.android.systemui.settings.brightness.MirrorController
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class QSSceneAdapterImplTest : SysuiTestCase() {

    private val kosmos = Kosmos().apply { testCase = this@QSSceneAdapterImplTest }
    private val testDispatcher = kosmos.testDispatcher
    private val testScope = kosmos.testScope

    private val qsImplProvider =
        object : Provider<QSImpl> {
            val impls = mutableListOf<QSImpl>()

            override fun get(): QSImpl {
                return mock<QSImpl> {
                        lateinit var _view: View
                        whenever(onComponentCreated(any(), any())).then {
                            _view = it.getArgument<QSComponent>(0).getRootView()
                            Unit
                        }
                        whenever(view).thenAnswer { _view }
                    }
                    .also { impls.add(it) }
            }
        }

    private val qsSceneComponentFactory =
        object : QSSceneComponent.Factory {
            val components = mutableListOf<QSSceneComponent>()

            override fun create(rootView: View): QSSceneComponent {
                return mock<QSSceneComponent> { whenever(this.getRootView()).thenReturn(rootView) }
                    .also { components.add(it) }
            }
        }
    private val configuration = Configuration(context.resources.configuration)

    private val fakeConfigurationRepository =
        FakeConfigurationRepository().apply { onConfigurationChange(configuration) }
    private val configurationInteractor = ConfigurationInteractor(fakeConfigurationRepository)

    private val mockAsyncLayoutInflater =
        mock<AsyncLayoutInflater>() {
            whenever(inflate(anyInt(), nullable(), any())).then { invocation ->
                val mockView = mock<View>()
                whenever(mockView.context).thenReturn(context)
                invocation
                    .getArgument<AsyncLayoutInflater.OnInflateFinishedListener>(2)
                    .onInflateFinished(
                        mockView,
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                    )
            }
        }

    private val shadeInteractor = kosmos.shadeInteractor
    private val dumpManager = mock<DumpManager>()

    private val underTest =
        QSSceneAdapterImpl(
            qsSceneComponentFactory,
            qsImplProvider,
            shadeInteractor,
            dumpManager,
            testDispatcher,
            testScope.backgroundScope,
            configurationInteractor,
            { mockAsyncLayoutInflater },
        )

    @Test
    fun inflate() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            assertThat(qsImpl).isNull()

            underTest.inflate(context)
            runCurrent()

            assertThat(qsImpl).isNotNull()
            assertThat(qsImpl).isSameInstanceAs(qsImplProvider.impls[0])
            val inOrder = inOrder(qsImpl!!)
            inOrder.verify(qsImpl!!).onCreate(nullable())
            inOrder
                .verify(qsImpl!!)
                .onComponentCreated(
                    eq(qsSceneComponentFactory.components[0]),
                    any(),
                )
        }

    @Test
    fun initialState_closed() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            with(qsImpl!!) {
                verify(this).setQsVisible(false)
                verify(this)
                    .setQsExpansion(
                        /* expansion= */ 0f,
                        /* panelExpansionFraction= */ 1f,
                        /* proposedTranslation= */ 0f,
                        /* squishinessFraction= */ 1f,
                    )
                verify(this).setListening(false)
                verify(this).setExpanded(false)
            }
        }

    @Test
    fun state_qqs() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()
            clearInvocations(qsImpl!!)

            underTest.setState(QSSceneAdapter.State.QQS)
            with(qsImpl!!) {
                verify(this).setQsVisible(true)
                verify(this)
                    .setQsExpansion(
                        /* expansion= */ 0f,
                        /* panelExpansionFraction= */ 1f,
                        /* proposedTranslation= */ 0f,
                        /* squishinessFraction= */ 1f,
                    )
                verify(this).setListening(true)
                verify(this).setExpanded(false)
            }
        }

    @Test
    fun state_qs() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()
            clearInvocations(qsImpl!!)

            underTest.setState(QSSceneAdapter.State.QS)
            with(qsImpl!!) {
                verify(this).setQsVisible(true)
                verify(this)
                    .setQsExpansion(
                        /* expansion= */ 1f,
                        /* panelExpansionFraction= */ 1f,
                        /* proposedTranslation= */ 0f,
                        /* squishinessFraction= */ 1f,
                    )
                verify(this).setListening(true)
                verify(this).setExpanded(true)
            }
        }

    @Test
    fun state_expanding() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)
            val progress = 0.34f

            underTest.inflate(context)
            runCurrent()
            clearInvocations(qsImpl!!)

            underTest.setState(QSSceneAdapter.State.Expanding(progress))
            with(qsImpl!!) {
                verify(this).setQsVisible(true)
                verify(this)
                    .setQsExpansion(
                        /* expansion= */ progress,
                        /* panelExpansionFraction= */ 1f,
                        /* proposedTranslation= */ 0f,
                        /* squishinessFraction= */ 1f,
                    )
                verify(this).setListening(true)
                verify(this).setExpanded(true)
            }
        }

    @Test
    fun state_unsquishing() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)
            val squishiness = 0.342f

            underTest.inflate(context)
            runCurrent()
            clearInvocations(qsImpl!!)

            underTest.setState(QSSceneAdapter.State.UnsquishingQQS { squishiness })
            with(qsImpl!!) {
                verify(this).setQsVisible(true)
                verify(this)
                    .setQsExpansion(
                        /* expansion= */ 0f,
                        /* panelExpansionFraction= */ 1f,
                        /* proposedTranslation= */ 0f,
                        /* squishinessFraction= */ squishiness,
                    )
                verify(this).setListening(true)
                verify(this).setExpanded(false)
            }
        }

    @Test
    fun customizing_QS_noAnimations() =
        testScope.runTest {
            val customizerState by collectLastValue(underTest.customizerState)

            underTest.inflate(context)
            runCurrent()
            underTest.setState(QSSceneAdapter.State.QS)

            assertThat(customizerState).isEqualTo(CustomizerState.Hidden)

            underTest.setCustomizerShowing(true)
            assertThat(customizerState).isEqualTo(CustomizerState.Showing)

            underTest.setCustomizerShowing(false)
            assertThat(customizerState).isEqualTo(CustomizerState.Hidden)
        }

    // This matches the calls made by QSCustomizer
    @Test
    fun customizing_QS_animations_correctStates() =
        testScope.runTest {
            val customizerState by collectLastValue(underTest.customizerState)
            val animatingInDuration = 100L
            val animatingOutDuration = 50L

            underTest.inflate(context)
            runCurrent()
            underTest.setState(QSSceneAdapter.State.QS)

            assertThat(customizerState).isEqualTo(CustomizerState.Hidden)

            // Start showing customizer with animation
            underTest.setCustomizerAnimating(true)
            underTest.setCustomizerShowing(true, animatingInDuration)
            assertThat(customizerState)
                .isEqualTo(CustomizerState.AnimatingIntoCustomizer(animatingInDuration))

            // Finish animation
            underTest.setCustomizerAnimating(false)
            assertThat(customizerState).isEqualTo(CustomizerState.Showing)

            // Start closing customizer with animation
            underTest.setCustomizerAnimating(true)
            underTest.setCustomizerShowing(false, animatingOutDuration)
            assertThat(customizerState)
                .isEqualTo(CustomizerState.AnimatingOutOfCustomizer(animatingOutDuration))

            // Finish animation
            underTest.setCustomizerAnimating(false)
            assertThat(customizerState).isEqualTo(CustomizerState.Hidden)
        }

    @Test
    fun customizing_moveToQQS_stopCustomizing() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()
            underTest.setState(QSSceneAdapter.State.QS)
            underTest.setCustomizerShowing(true)

            underTest.setState(QSSceneAdapter.State.QQS)
            runCurrent()
            verify(qsImpl!!).closeCustomizerImmediately()
        }

    @Test
    fun customizing_moveToClosed_stopCustomizing() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()
            underTest.setState(QSSceneAdapter.State.QS)
            underTest.setCustomizerShowing(true)
            runCurrent()

            underTest.setState(QSSceneAdapter.State.CLOSED)
            verify(qsImpl!!).closeCustomizerImmediately()
        }

    @Test
    fun reinflation_previousStateDestroyed() =
        testScope.runTest {
            // Run all flows... In particular, initial configuration propagation that could cause
            // QSImpl to re-inflate.
            runCurrent()
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()
            val oldQsImpl = qsImpl!!

            underTest.inflate(context)
            runCurrent()
            val newQSImpl = qsImpl!!

            assertThat(oldQsImpl).isNotSameInstanceAs(newQSImpl)
            val inOrder = inOrder(oldQsImpl, newQSImpl)
            val bundleArgCaptor = argumentCaptor<Bundle>()

            inOrder.verify(oldQsImpl).onSaveInstanceState(capture(bundleArgCaptor))
            inOrder.verify(oldQsImpl).onDestroy()
            assertThat(newQSImpl).isSameInstanceAs(qsImplProvider.impls[1])
            inOrder.verify(newQSImpl).onCreate(nullable())
            inOrder
                .verify(newQSImpl)
                .onComponentCreated(
                    qsSceneComponentFactory.components[1],
                    bundleArgCaptor.value,
                )
        }

    @Test
    fun changeInLocale_reinflation() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            val oldQsImpl = qsImpl!!

            val newLocale =
                if (configuration.locales[0] == Locale("en-US")) {
                    Locale("es-UY")
                } else {
                    Locale("en-US")
                }
            configuration.setLocale(newLocale)
            fakeConfigurationRepository.onConfigurationChange(configuration)
            runCurrent()

            assertThat(oldQsImpl).isNotSameInstanceAs(qsImpl!!)
        }

    @Test
    fun changeInFontSize_reinflation() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            val oldQsImpl = qsImpl!!

            configuration.fontScale *= 2
            fakeConfigurationRepository.onConfigurationChange(configuration)
            runCurrent()

            assertThat(oldQsImpl).isNotSameInstanceAs(qsImpl!!)
        }

    @Test
    fun changeInAssetPath_reinflation() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            val oldQsImpl = qsImpl!!

            configuration.assetsSeq += 1
            fakeConfigurationRepository.onConfigurationChange(configuration)
            runCurrent()

            assertThat(oldQsImpl).isNotSameInstanceAs(qsImpl!!)
        }

    @Test
    fun otherChangesInConfiguration_noReinflation_configurationChangeDispatched() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            val oldQsImpl = qsImpl!!
            configuration.densityDpi *= 2
            configuration.windowConfiguration.maxBounds.scale(2f)
            configuration.windowConfiguration.rotation = Surface.ROTATION_270
            fakeConfigurationRepository.onConfigurationChange(configuration)
            runCurrent()

            assertThat(oldQsImpl).isSameInstanceAs(qsImpl!!)
            verify(qsImpl!!).onConfigurationChanged(configuration)
            verify(qsImpl!!.view).dispatchConfigurationChanged(configuration)
        }

    @Test
    fun dispatchNavBarSize_beforeInflation() =
        testScope.runTest {
            runCurrent()
            val navBarHeight = 171

            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.applyBottomNavBarPadding(navBarHeight)
            underTest.inflate(context)
            runCurrent()

            verify(qsImpl!!).applyBottomNavBarToCustomizerPadding(navBarHeight)
        }

    @Test
    fun dispatchNavBarSize_afterInflation() =
        testScope.runTest {
            runCurrent()
            val navBarHeight = 171

            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            underTest.applyBottomNavBarPadding(navBarHeight)
            runCurrent()

            verify(qsImpl!!).applyBottomNavBarToCustomizerPadding(navBarHeight)
        }

    @Test
    fun dispatchNavBarSize_reinflation() =
        testScope.runTest {
            runCurrent()
            val navBarHeight = 171

            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            underTest.applyBottomNavBarPadding(navBarHeight)
            runCurrent()

            underTest.inflate(context)
            runCurrent()

            verify(qsImpl!!).applyBottomNavBarToCustomizerPadding(navBarHeight)
        }

    @Test
    fun dispatchSplitShade() =
        testScope.runTest {
            val shadeRepository = kosmos.fakeShadeRepository
            shadeRepository.setShadeMode(ShadeMode.Single)
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            verify(qsImpl!!).setInSplitShade(false)

            shadeRepository.setShadeMode(ShadeMode.Split)
            runCurrent()
            verify(qsImpl!!).setInSplitShade(true)
        }

    @Test
    fun requestCloseCustomizer() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            underTest.requestCloseCustomizer()
            verify(qsImpl!!).closeCustomizer()
        }

    @Test
    fun setBrightnessMirrorController() =
        testScope.runTest {
            val qsImpl by collectLastValue(underTest.qsImpl)

            underTest.inflate(context)
            runCurrent()

            val mirrorController = mock<MirrorController>()
            underTest.setBrightnessMirrorController(mirrorController)

            verify(qsImpl!!).setBrightnessMirrorController(mirrorController)

            underTest.setBrightnessMirrorController(null)
            verify(qsImpl!!).setBrightnessMirrorController(null)
        }
}
