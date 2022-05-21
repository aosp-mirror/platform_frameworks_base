package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.util.DisplayMetrics
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.phone.LSShadeTransitionLogger
import com.android.systemui.statusbar.phone.LockscreenGestureLogger
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LSShadeTransitionLoggerTest : SysuiTestCase() {
    lateinit var logger: LSShadeTransitionLogger
    @Mock
    lateinit var gestureLogger: LockscreenGestureLogger
    @Mock
    lateinit var displayMetrics: DisplayMetrics
    @JvmField @Rule
    val mockito = MockitoJUnit.rule()

    @Before
    fun setup() {
        logger = LSShadeTransitionLogger(
                LogBuffer("Test", 10, mock()),
                gestureLogger,
                displayMetrics)
    }

    @Test
    fun testLogDragDownStarted() {
        val view: ExpandableView = mock()
        // log a non-null, non row, ensure no crash
        logger.logDragDownStarted(view)
    }
}