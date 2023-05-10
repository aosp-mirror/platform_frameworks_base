package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.widget.Button
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsEditingActivityTest : SysuiTestCase() {

    private companion object {
        val TEST_COMPONENT = ComponentName("TestPackageName", "TestClassName")
        val TEST_STRUCTURE: CharSequence = "TestStructure"
        val TEST_APP: CharSequence = "TestApp"
    }

    private val uiExecutor = FakeExecutor(FakeSystemClock())
    private val featureFlags = FakeFeatureFlags()

    @Mock lateinit var controller: ControlsControllerImpl

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var customIconCache: CustomIconCache

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
                        featureFlags,
                        uiExecutor,
                        controller,
                        userTracker,
                        customIconCache,
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

        featureFlags.set(Flags.CONTROLS_MANAGEMENT_NEW_FLOWS, false)
    }

    @Test
    fun testBackCallbackRegistrationAndUnregistration() {
        launchActivity()
        // 1. ensure that launching the activity results in it registering a callback
        verify(mockDispatcher)
            .registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                captureCallback.capture()
            )
        activityRule.finishActivity()
        latch.await() // ensure activity is finished
        // 2. ensure that when the activity is finished, it unregisters the same callback
        verify(mockDispatcher).unregisterOnBackInvokedCallback(captureCallback.value)
    }

    @Test
    fun testNewFlowDisabled_addControlsButton_gone() {
        with(launchActivity()) {
            val addControlsButton = requireViewById<Button>(R.id.addControls)
            assertThat(addControlsButton.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun testNewFlowEnabled_addControlsButton_visible() {
        featureFlags.set(Flags.CONTROLS_MANAGEMENT_NEW_FLOWS, true)
        with(launchActivity()) {
            val addControlsButton = requireViewById<Button>(R.id.addControls)
            assertThat(addControlsButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(addControlsButton.isEnabled).isTrue()
        }
    }

    @Test
    fun testNotLaunchFromFavoriting_saveButton_disabled() {
        featureFlags.set(Flags.CONTROLS_MANAGEMENT_NEW_FLOWS, true)
        with(launchActivity(isFromFavoriting = false)) {
            val saveButton = requireViewById<Button>(R.id.done)
            assertThat(saveButton.isEnabled).isFalse()
        }
    }

    @Test
    fun testLaunchFromFavoriting_saveButton_enabled() {
        featureFlags.set(Flags.CONTROLS_MANAGEMENT_NEW_FLOWS, true)
        with(launchActivity(isFromFavoriting = true)) {
            val saveButton = requireViewById<Button>(R.id.done)
            assertThat(saveButton.isEnabled).isTrue()
        }
    }

    @Test
    fun testNotFromFavoriting_addControlsPressed_launchesFavouriting() {
        featureFlags.set(Flags.CONTROLS_MANAGEMENT_NEW_FLOWS, true)
        with(launchActivity(isFromFavoriting = false)) {
            val addControls = requireViewById<Button>(R.id.addControls)

            activityRule.runOnUiThread { addControls.performClick() }

            with(startActivityData!!.intent) {
                assertThat(component)
                    .isEqualTo(ComponentName(context, ControlsFavoritingActivity::class.java))
                assertThat(getCharSequenceExtra(ControlsFavoritingActivity.EXTRA_STRUCTURE))
                    .isEqualTo(TEST_STRUCTURE)
                assertThat(
                        getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                    )
                    .isEqualTo(TEST_COMPONENT)
                assertThat(getCharSequenceExtra(ControlsFavoritingActivity.EXTRA_APP))
                    .isEqualTo(TEST_APP)
                assertThat(getByteExtra(ControlsFavoritingActivity.EXTRA_SOURCE, -1))
                    .isEqualTo(ControlsFavoritingActivity.EXTRA_SOURCE_VALUE_FROM_EDITING)
            }
        }
    }

    private fun launchActivity(
        componentName: ComponentName = TEST_COMPONENT,
        structure: CharSequence = TEST_STRUCTURE,
        isFromFavoriting: Boolean = false,
        app: CharSequence = TEST_APP,
    ): TestableControlsEditingActivity =
        activityRule.launchActivity(
            Intent().apply {
                putExtra(ControlsEditingActivity.EXTRA_FROM_FAVORITING, isFromFavoriting)
                putExtra(ControlsEditingActivity.EXTRA_STRUCTURE, structure)
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                putExtra(ControlsEditingActivity.EXTRA_APP, app)
            }
        )

    class TestableControlsEditingActivity(
        featureFlags: FakeFeatureFlags,
        executor: FakeExecutor,
        controller: ControlsControllerImpl,
        userTracker: UserTracker,
        customIconCache: CustomIconCache,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch
    ) :
        ControlsEditingActivity(
            featureFlags,
            executor,
            controller,
            userTracker,
            customIconCache,
        ) {

        var startActivityData: StartActivityData? = null
            private set

        override fun getOnBackInvokedDispatcher(): OnBackInvokedDispatcher {
            return mockDispatcher
        }

        override fun onStop() {
            super.onStop()
            // ensures that test runner thread does not proceed until ui thread is done
            latch.countDown()
        }

        override fun startActivity(intent: Intent) {
            startActivityData = StartActivityData(intent, null)
        }

        override fun startActivity(intent: Intent, options: Bundle?) {
            startActivityData = StartActivityData(intent, options)
        }
    }
}
