package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.controls.Control
import android.testing.TestableLooper
import android.view.View
import android.widget.Button
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.controller.createLoadDataObject
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.SafeIconLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ControlsFavoritingActivityTest : SysuiTestCase() {

    private companion object {
        val TEST_COMPONENT = ComponentName("TestPackageName", "TestClassName")
        val TEST_STRUCTURE: CharSequence = "TestStructure"
        val TEST_CONTROL =
            mock(Control::class.java, Answers.RETURNS_MOCKS)!!.apply {
                whenever(structure).thenReturn(TEST_STRUCTURE)
            }
        val TEST_APP: CharSequence = "TestApp"
        val TEST_UID = 12345

        private fun View.waitForPost() {
            val latch = CountDownLatch(1)
            post(latch::countDown)
            latch.await()
        }
    }

    @Main private val executor: Executor = MoreExecutors.directExecutor()
    private val featureFlags = FakeFeatureFlags()

    @Mock lateinit var controller: ControlsControllerImpl

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var controlsListingController: ControlsListingController

    @Mock(answer = Answers.RETURNS_MOCKS) lateinit var safeIconLoaderFactory: SafeIconLoader.Factory

    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>
    @Captor
    private lateinit var controlsCallback: ArgumentCaptor<Consumer<ControlsController.LoadData>>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                TestableControlsFavoritingActivity(
                    executor,
                    controller,
                    userTracker,
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
        Mockito.`when`(controlsListingController.getCurrentServices())
            .thenReturn(listOf(serviceInfo))
    }

    // b/259549854 to root-cause and fix
    @FlakyTest
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
    fun testButtons() {
        with(launchActivity()) {
            val rearrangeButton = requireViewById<Button>(R.id.rearrange)
            assertThat(rearrangeButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(rearrangeButton.isEnabled).isFalse()

            val otherAppsButton = requireViewById<Button>(R.id.other_apps)
            otherAppsButton.waitForPost()
            assertThat(otherAppsButton.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun testRearrangePressed_savesAndlaunchesActivity() {
        with(launchActivity()) {
            verify(controller).loadForComponent(any(), capture(controlsCallback), any())
            activityRule.runOnUiThread {
                controlsCallback.value.accept(
                    createLoadDataObject(
                        listOf(ControlStatus(TEST_CONTROL, TEST_COMPONENT, true)),
                        emptyList(),
                    )
                )
                requireViewById<Button>(R.id.rearrange).performClick()
            }

            verify(controller).replaceFavoritesForStructure(any())
            with(startActivityData!!.intent) {
                assertThat(component)
                    .isEqualTo(ComponentName(context, ControlsEditingActivity::class.java))
                assertThat(
                        getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                    )
                    .isEqualTo(TEST_COMPONENT)
                assertThat(getCharSequenceExtra(ControlsEditingActivity.EXTRA_APP))
                    .isEqualTo(TEST_APP)
                assertThat(getBooleanExtra(ControlsEditingActivity.EXTRA_FROM_FAVORITING, false))
                    .isTrue()
                assertThat(getCharSequenceExtra(ControlsEditingActivity.EXTRA_STRUCTURE))
                    .isEqualTo(TEST_STRUCTURE)
            }
        }
    }

    private fun launchActivity(
        componentName: ComponentName = TEST_COMPONENT,
        structure: CharSequence = TEST_STRUCTURE,
        app: CharSequence = TEST_APP,
        source: Byte = ControlsFavoritingActivity.EXTRA_SOURCE_VALUE_FROM_PROVIDER_SELECTOR,
    ): TestableControlsFavoritingActivity =
        activityRule.launchActivity(
            Intent().apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                putExtra(ControlsFavoritingActivity.EXTRA_STRUCTURE, structure)
                putExtra(ControlsFavoritingActivity.EXTRA_APP, app)
                putExtra(ControlsFavoritingActivity.EXTRA_SOURCE, source)
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

    class TestableControlsFavoritingActivity(
        executor: Executor,
        controller: ControlsControllerImpl,
        userTracker: UserTracker,
        controlsListingController: ControlsListingController,
        safeIconLoaderFactory: SafeIconLoader.Factory,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch,
    ) :
        ControlsFavoritingActivity(
            executor,
            controller,
            userTracker,
            safeIconLoaderFactory,
            controlsListingController,
        ) {

        var triedToFinish = false

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

        override fun animateExitAndFinish() {
            triedToFinish = true
        }
    }
}
