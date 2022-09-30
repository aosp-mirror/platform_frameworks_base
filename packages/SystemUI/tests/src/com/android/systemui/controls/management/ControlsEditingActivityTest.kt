package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Intent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsEditingActivityTest : SysuiTestCase() {
    private val uiExecutor = FakeExecutor(FakeSystemClock())

    @Mock lateinit var controller: ControlsControllerImpl

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var customIconCache: CustomIconCache

    @Mock lateinit var uiController: ControlsUiController

    private lateinit var controlsEditingActivity: ControlsEditingActivity_Factory
    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            object :
                SingleActivityFactory<TestableControlsEditingActivity>(
                    TestableControlsEditingActivity::class.java
                ) {
                override fun create(intent: Intent?): TestableControlsEditingActivity {
                    return TestableControlsEditingActivity(
                        uiExecutor,
                        controller,
                        userTracker,
                        customIconCache,
                        uiController,
                        mockDispatcher,
                        latch
                    )
                }
            },
            false,
            false
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val intent = Intent()
        intent.putExtra(ControlsEditingActivity.EXTRA_STRUCTURE, "TestTitle")
        val cname = ComponentName("TestPackageName", "TestClassName")
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, cname)
        activityRule.launchActivity(intent)
    }

    @Test
    fun testBackCallbackRegistrationAndUnregistration() {
        // 1. ensure that launching the activity results in it registering a callback
        verify(mockDispatcher)
            .registerOnBackInvokedCallback(
                ArgumentMatchers.eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                captureCallback.capture()
            )
        activityRule.finishActivity()
        latch.await() // ensure activity is finished
        // 2. ensure that when the activity is finished, it unregisters the same callback
        verify(mockDispatcher).unregisterOnBackInvokedCallback(captureCallback.value)
    }

    public class TestableControlsEditingActivity(
        private val executor: FakeExecutor,
        private val controller: ControlsControllerImpl,
        private val userTracker: UserTracker,
        private val customIconCache: CustomIconCache,
        private val uiController: ControlsUiController,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch
    ) : ControlsEditingActivity(executor, controller, userTracker, customIconCache, uiController) {
        override fun getOnBackInvokedDispatcher(): OnBackInvokedDispatcher {
            return mockDispatcher
        }

        override fun onStop() {
            super.onStop()
            // ensures that test runner thread does not proceed until ui thread is done
            latch.countDown()
        }
    }
}
