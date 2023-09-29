package com.android.systemui.qs.tiles.base

import android.content.Intent
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserActionHandler
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QSTileIntentUserActionHandlerTest : SysuiTestCase() {

    @Mock private lateinit var activityStarted: ActivityStarter

    lateinit var underTest: QSTileIntentUserActionHandler

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = QSTileIntentUserActionHandler(activityStarted)
    }

    @Test
    fun testPassesIntentToStarter() {
        val intent = Intent("test.ACTION")

        underTest.handle(null, intent)

        verify(activityStarted).postStartActivityDismissingKeyguard(eq(intent), eq(0), any())
    }
}
