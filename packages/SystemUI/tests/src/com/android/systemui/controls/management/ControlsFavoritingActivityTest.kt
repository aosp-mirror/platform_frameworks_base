package com.android.systemui.controls.management

import android.content.Intent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
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
class ControlsFavoritingActivityTest : SysuiTestCase() {
    @Main private val executor: Executor = MoreExecutors.directExecutor()

    @Mock lateinit var controller: ControlsControllerImpl

    @Mock lateinit var listingController: ControlsListingController

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var uiController: ControlsUiController

    private lateinit var controlsFavoritingActivity: ControlsFavoritingActivity_Factory
    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            object :
                SingleActivityFactory<TestableControlsFavoritingActivity>(
                    TestableControlsFavoritingActivity::class.java
                ) {
                override fun create(intent: Intent?): TestableControlsFavoritingActivity {
                    return TestableControlsFavoritingActivity(
                        executor,
                        controller,
                        listingController,
                        userTracker,
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
        intent.putExtra(ControlsFavoritingActivity.EXTRA_FROM_PROVIDER_SELECTOR, true)
        activityRule.launchActivity(intent)
    }

    // b/259549854 to root-cause and fix
    @FlakyTest
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

    public class TestableControlsFavoritingActivity(
        executor: Executor,
        controller: ControlsControllerImpl,
        listingController: ControlsListingController,
        userTracker: UserTracker,
        uiController: ControlsUiController,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch
    ) :
        ControlsFavoritingActivity(
            executor,
            controller,
            listingController,
            userTracker,
            uiController
        ) {
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
