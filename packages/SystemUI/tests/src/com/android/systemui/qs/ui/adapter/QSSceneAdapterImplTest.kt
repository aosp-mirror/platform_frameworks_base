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

import android.os.Bundle
import android.view.View
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.QSImpl
import com.android.systemui.qs.dagger.QSComponent
import com.android.systemui.qs.dagger.QSSceneComponent
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

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

    private val mockAsyncLayoutInflater =
        mock<AsyncLayoutInflater>() {
            whenever(inflate(anyInt(), nullable(), any())).then { invocation ->
                val mockView = mock<View>()
                invocation
                    .getArgument<AsyncLayoutInflater.OnInflateFinishedListener>(2)
                    .onInflateFinished(
                        mockView,
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                    )
            }
        }

    private val underTest =
        QSSceneAdapterImpl(
            qsSceneComponentFactory,
            qsImplProvider,
            testDispatcher,
            testScope.backgroundScope,
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
                verify(this)
                    .setTransitionToFullShadeProgress(
                        /* isTransitioningToFullShade= */ false,
                        /* qsTransitionFraction= */ 1f,
                        /* qsSquishinessFraction = */ 1f,
                    )
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
                verify(this).setExpanded(true)
                verify(this)
                    .setTransitionToFullShadeProgress(
                        /* isTransitioningToFullShade= */ false,
                        /* qsTransitionFraction= */ 1f,
                        /* qsSquishinessFraction = */ 1f,
                    )
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
                verify(this)
                    .setTransitionToFullShadeProgress(
                        /* isTransitioningToFullShade= */ false,
                        /* qsTransitionFraction= */ 1f,
                        /* qsSquishinessFraction = */ 1f,
                    )
            }
        }

    @Test
    fun customizing_QS() =
        testScope.runTest {
            val customizing by collectLastValue(underTest.isCustomizing)

            underTest.inflate(context)
            runCurrent()
            underTest.setState(QSSceneAdapter.State.QS)

            assertThat(customizing).isFalse()

            underTest.setCustomizerShowing(true)
            assertThat(customizing).isTrue()

            underTest.setCustomizerShowing(false)
            assertThat(customizing).isFalse()
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
}
