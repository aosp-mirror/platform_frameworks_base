/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.Date

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class VariableDateViewControllerTest : SysuiTestCase() {

    companion object {
        private const val TIME_STAMP = 1_500_000_000_000
        private const val LONG_PATTERN = "EEEMMMd"
        private const val SHORT_PATTERN = "MMMd"
        private const val CHAR_WIDTH = 10f
    }

    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var view: VariableDateView
    @Mock
    private lateinit var shadeInteractor: ShadeInteractor
    @Captor
    private lateinit var onMeasureListenerCaptor: ArgumentCaptor<VariableDateView.OnMeasureListener>

    private val qsExpansion = MutableStateFlow(0F)

    private var lastText: String? = null

    private lateinit var systemClock: FakeSystemClock
    private lateinit var testableLooper: TestableLooper
    private lateinit var testableHandler: Handler
    private lateinit var controller: VariableDateViewController

    private lateinit var longText: String
    private lateinit var shortText: String

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        testableHandler = Handler(testableLooper.looper)

        systemClock = FakeSystemClock()
        systemClock.setCurrentTimeMillis(TIME_STAMP)

        `when`(shadeInteractor.qsExpansion).thenReturn(qsExpansion)

        `when`(view.longerPattern).thenReturn(LONG_PATTERN)
        `when`(view.shorterPattern).thenReturn(SHORT_PATTERN)
        `when`(view.handler).thenReturn(testableHandler)

        `when`(view.setText(anyString())).thenAnswer {
            lastText = it.arguments[0] as? String
            Unit
        }
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.viewTreeObserver).thenReturn(mock())

        val date = Date(TIME_STAMP)
        longText = getTextForFormat(date, getFormatFromPattern(LONG_PATTERN))
        shortText = getTextForFormat(date, getFormatFromPattern(SHORT_PATTERN))

        // Assume some sizes for the text, the controller doesn't need to know if these sizes are
        // the true ones
        `when`(view.getDesiredWidthForText(any())).thenAnswer {
            getTextLength(it.arguments[0] as CharSequence)
        }

        controller = VariableDateViewController(
            systemClock,
            broadcastDispatcher,
            shadeInteractor,
            mock(),
            testableHandler,
            view
        )

        controller.init()
        testableLooper.processAllMessages()

        verify(view).onAttach(capture(onMeasureListenerCaptor))
    }

    @Test
    fun testViewStartsWithLongText() {
        assertThat(lastText).isEqualTo(longText)
    }

    @Test
    fun testListenerNotNull() {
        assertThat(onMeasureListenerCaptor.value).isNotNull()
    }

    @Test
    fun testLotsOfSpaceUseLongText() {
        onMeasureListenerCaptor.value.onMeasureAction(10000, View.MeasureSpec.EXACTLY)

        testableLooper.processAllMessages()
        assertThat(lastText).isEqualTo(longText)
    }

    @Test
    fun testSmallSpaceUseEmpty() {
        onMeasureListenerCaptor.value.onMeasureAction(1, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()

        assertThat(lastText).isEmpty()
    }

    @Test
    fun testSpaceInBetweenUseShortText() {
        val average = ((getTextLength(longText) + getTextLength(shortText)) / 2).toInt()

        onMeasureListenerCaptor.value.onMeasureAction(average, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()

        assertThat(lastText).isEqualTo(shortText)
    }

    @Test
    fun testSwitchBackToLonger() {
        onMeasureListenerCaptor.value.onMeasureAction(1, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()

        onMeasureListenerCaptor.value.onMeasureAction(10000, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()

        assertThat(lastText).isEqualTo(longText)
    }

    @Test
    fun testNoSwitchingWhenFrozen() {
        `when`(view.freezeSwitching).thenReturn(true)

        val average = ((getTextLength(longText) + getTextLength(shortText)) / 2).toInt()
        onMeasureListenerCaptor.value.onMeasureAction(average, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()
        assertThat(lastText).isEqualTo(longText)

        onMeasureListenerCaptor.value.onMeasureAction(1, View.MeasureSpec.EXACTLY)
        testableLooper.processAllMessages()
        assertThat(lastText).isEqualTo(longText)
    }

    @Test
    fun testQsExpansionTrue_ignoreAtMostMeasureRequests() {
        qsExpansion.value = 0f

        onMeasureListenerCaptor.value.onMeasureAction(
                getTextLength(shortText).toInt(),
                View.MeasureSpec.EXACTLY
            )
        testableLooper.processAllMessages()

        onMeasureListenerCaptor.value.onMeasureAction(10000, View.MeasureSpec.AT_MOST)
        testableLooper.processAllMessages()
        assertThat(lastText).isEqualTo(shortText)
    }

    @Test
    fun testQsExpansionFalse_acceptAtMostMeasureRequests() {
        qsExpansion.value = 1f

        onMeasureListenerCaptor.value.onMeasureAction(
                getTextLength(shortText).toInt(),
                View.MeasureSpec.EXACTLY
        )
        testableLooper.processAllMessages()

        onMeasureListenerCaptor.value.onMeasureAction(10000, View.MeasureSpec.AT_MOST)
        testableLooper.processAllMessages()
        assertThat(lastText).isEqualTo(longText)
    }

    private fun getTextLength(text: CharSequence): Float {
        return text.length * CHAR_WIDTH
    }
}