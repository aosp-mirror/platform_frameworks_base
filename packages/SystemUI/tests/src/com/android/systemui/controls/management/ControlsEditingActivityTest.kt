package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.testing.TestableLooper
import android.view.View
import android.widget.Button
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.SafeIconLoader
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ControlsEditingActivityTest : SysuiTestCase() {

    private companion object {
        val TEST_COMPONENT = ComponentName("TestPackageName", "TestClassName")
        val TEST_STRUCTURE: CharSequence = "TestStructure"
        val TEST_APP: CharSequence = "TestApp"
        val TEST_UID = 12345
    }

    private val uiExecutor = FakeExecutor(FakeSystemClock())

    @Mock lateinit var controller: ControlsControllerImpl

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var customIconCache: CustomIconCache

    @Mock lateinit var controlsListingController: ControlsListingController

    @Mock(answer = Answers.RETURNS_MOCKS) lateinit var safeIconLoaderFactory: SafeIconLoader.Factory

    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                TestableControlsEditingActivity(
                    uiExecutor,
                    controller,
                    userTracker,
                    customIconCache,
                    controlsListingController,
                    safeIconLoaderFactory,
                    mockDispatcher,
                    latch,
                )
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val serviceInfo = ControlsServiceInfo(TEST_COMPONENT, "", TEST_UID)
        `when`(controlsListingController.getCurrentServices()).thenReturn(listOf(serviceInfo))
    }

    @Test
    fun testBackCallbackRegistrationAndUnregistration() {
        launchActivity()
        // 1. ensure that launching the activity results in it registering a callback
        verify(mockDispatcher)
            .registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                captureCallback.capture(),
            )
        activityRule.finishActivity()
        latch.await() // ensure activity is finished
        // 2. ensure that when the activity is finished, it unregisters the same callback
        verify(mockDispatcher).unregisterOnBackInvokedCallback(captureCallback.value)
    }

    @Test
    fun testAddControlsButton_visible() {
        with(launchActivity()) {
            val addControlsButton = requireViewById<Button>(R.id.addControls)
            assertThat(addControlsButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(addControlsButton.isEnabled).isTrue()
        }
    }

    @Test
    fun testNotLaunchFromFavoriting_saveButton_disabled() {
        with(launchActivity(isFromFavoriting = false)) {
            val saveButton = requireViewById<Button>(R.id.done)
            assertThat(saveButton.isEnabled).isFalse()
        }
    }

    @Test
    fun testLaunchFromFavoriting_saveButton_enabled() {
        with(launchActivity(isFromFavoriting = true)) {
            val saveButton = requireViewById<Button>(R.id.done)
            assertThat(saveButton.isEnabled).isTrue()
        }
    }

    @Test
    fun testNotFromFavoriting_addControlsPressed_launchesFavouriting() {
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

    private fun ControlsServiceInfo(
        componentName: ComponentName,
        label: CharSequence,
        uid: Int,
    ): ControlsServiceInfo {
        val serviceInfo =
            ServiceInfo().apply {
                applicationInfo = ApplicationInfo().apply { this.uid = uid }
                packageName = componentName.packageName
                name = componentName.className
            }
        return Mockito.spy(ControlsServiceInfo(mContext, serviceInfo)).apply {
            Mockito.doReturn(label).`when`(this).loadLabel()
            Mockito.doReturn(mock(Drawable::class.java)).`when`(this).loadIcon()
        }
    }

    class TestableControlsEditingActivity(
        executor: FakeExecutor,
        controller: ControlsControllerImpl,
        userTracker: UserTracker,
        customIconCache: CustomIconCache,
        controlsListingController: ControlsListingController,
        safeIconLoaderFactory: SafeIconLoader.Factory,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch,
    ) :
        ControlsEditingActivity(
            executor,
            controller,
            userTracker,
            customIconCache,
            controlsListingController,
            safeIconLoaderFactory,
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
