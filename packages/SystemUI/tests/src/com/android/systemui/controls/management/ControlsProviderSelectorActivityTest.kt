/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsProviderSelectorActivityTest : SysuiTestCase() {
    @Main private val executor: Executor = MoreExecutors.directExecutor()

    @Background private val backExecutor: Executor = MoreExecutors.directExecutor()

    @Mock lateinit var listingController: ControlsListingController

    @Mock lateinit var controlsController: ControlsController

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var authorizedPanelsRepository: AuthorizedPanelsRepository

    @Mock lateinit var dialogFactory: PanelConfirmationDialogFactory

    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                TestableControlsProviderSelectorActivity(
                    executor,
                    backExecutor,
                    listingController,
                    controlsController,
                    userTracker,
                    authorizedPanelsRepository,
                    dialogFactory,
                    mockDispatcher,
                    latch
                )
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val intent = Intent()
        intent.putExtra(ControlsProviderSelectorActivity.BACK_SHOULD_EXIT, true)
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

    @Test
    fun testOnAppSelectedForNonPanelStartsFavoritingActivity() {
        val info = ControlsServiceInfo(ComponentName("test_pkg", "service"), "", null)
        activityRule.activity.onAppSelected(info)

        verifyNoMoreInteractions(dialogFactory)

        assertThat(activityRule.activity.lastStartedActivity?.component?.className)
            .isEqualTo(ControlsFavoritingActivity::class.java.name)

        assertThat(activityRule.activity.triedToFinish).isFalse()
    }

    @Test
    fun testOnAppSelectedForPanelTriggersDialog() {
        val label = "label"
        val info =
            ControlsServiceInfo(
                ComponentName("test_pkg", "service"),
                label,
                ComponentName("test_pkg", "activity")
            )

        val dialog: Dialog = mock()
        whenever(dialogFactory.createConfirmationDialog(any(), any(), any())).thenReturn(dialog)

        activityRule.activity.onAppSelected(info)
        verify(dialogFactory).createConfirmationDialog(any(), eq(label), any())
        verify(dialog).show()

        assertThat(activityRule.activity.triedToFinish).isFalse()
    }

    @Test
    fun dialogAcceptAddsPackage() {
        val label = "label"
        val info =
            ControlsServiceInfo(
                ComponentName("test_pkg", "service"),
                label,
                ComponentName("test_pkg", "activity")
            )

        val dialog: Dialog = mock()
        whenever(dialogFactory.createConfirmationDialog(any(), any(), any())).thenReturn(dialog)

        activityRule.activity.onAppSelected(info)

        val captor: ArgumentCaptor<Consumer<Boolean>> = argumentCaptor()
        verify(dialogFactory).createConfirmationDialog(any(), any(), capture(captor))

        captor.value.accept(true)

        val setCaptor: ArgumentCaptor<Set<String>> = argumentCaptor()
        verify(authorizedPanelsRepository).addAuthorizedPanels(capture(setCaptor))
        assertThat(setCaptor.value).containsExactly(info.componentName.packageName)
        val selectedComponentCaptor: ArgumentCaptor<SelectedItem> = argumentCaptor()
        verify(controlsController).setPreferredSelection(capture(selectedComponentCaptor))
        assertThat(selectedComponentCaptor.value.componentName).isEqualTo(info.componentName)

        assertThat(activityRule.activity.triedToFinish).isTrue()
    }

    @Test
    fun dialogCancelDoesntAddPackage() {
        val label = "label"
        val info =
            ControlsServiceInfo(
                ComponentName("test_pkg", "service"),
                label,
                ComponentName("test_pkg", "activity")
            )

        val dialog: Dialog = mock()
        whenever(dialogFactory.createConfirmationDialog(any(), any(), any())).thenReturn(dialog)

        activityRule.activity.onAppSelected(info)

        val captor: ArgumentCaptor<Consumer<Boolean>> = argumentCaptor()
        verify(dialogFactory).createConfirmationDialog(any(), any(), capture(captor))

        captor.value.accept(false)

        verify(authorizedPanelsRepository, never()).addAuthorizedPanels(any())

        assertThat(activityRule.activity.triedToFinish).isFalse()
    }

    class TestableControlsProviderSelectorActivity(
        executor: Executor,
        backExecutor: Executor,
        listingController: ControlsListingController,
        controlsController: ControlsController,
        userTracker: UserTracker,
        authorizedPanelsRepository: AuthorizedPanelsRepository,
        dialogFactory: PanelConfirmationDialogFactory,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch
    ) :
        ControlsProviderSelectorActivity(
            executor,
            backExecutor,
            listingController,
            controlsController,
            userTracker,
            authorizedPanelsRepository,
            dialogFactory
        ) {

        var lastStartedActivity: Intent? = null
        var triedToFinish = false

        override fun getOnBackInvokedDispatcher(): OnBackInvokedDispatcher {
            return mockDispatcher
        }

        override fun startActivity(intent: Intent?, options: Bundle?) {
            lastStartedActivity = intent
        }

        override fun onStop() {
            super.onStop()
            // ensures that test runner thread does not proceed until ui thread is done
            latch.countDown()
        }

        override fun animateExitAndFinish() {
            // Activity should only be finished from the rule.
            triedToFinish = true
        }
    }

    companion object {
        private fun ControlsServiceInfo(
            componentName: ComponentName,
            label: CharSequence,
            panelComponentName: ComponentName? = null
        ): ControlsServiceInfo {
            val serviceInfo =
                ServiceInfo().apply {
                    applicationInfo = ApplicationInfo()
                    packageName = componentName.packageName
                    name = componentName.className
                }
            return Mockito.spy(ControlsServiceInfo(mock(), serviceInfo)).apply {
                doReturn(label).`when`(this).loadLabel()
                doReturn(mock<Drawable>()).`when`(this).loadIcon()
                doReturn(panelComponentName).`when`(this).panelActivity
            }
        }
    }
}
