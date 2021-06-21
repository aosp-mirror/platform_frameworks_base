/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.app.PendingIntent
import android.content.ComponentName
import android.content.IIntentSender
import android.content.Intent
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.lifecycle.Lifecycle
import androidx.test.filters.MediumTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@MediumTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsRequestDialogTest : SysuiTestCase() {

    companion object {
        private val CONTROL_COMPONENT = ComponentName.unflattenFromString("TEST_PKG/.TEST_CLS")!!
        private const val LABEL = "TEST_LABEL"

        private val USER_ID = UserHandle.USER_SYSTEM
        private const val CONTROL_ID = "id"
    }

    @Mock
    private lateinit var controller: ControlsController

    @Mock
    private lateinit var listingController: ControlsListingController
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var iIntentSender: IIntentSender
    @Captor
    private lateinit var captor: ArgumentCaptor<ControlInfo>

    @Rule
    @JvmField
    var activityRule = ActivityTestRule<TestControlsRequestDialog>(
            object : SingleActivityFactory<TestControlsRequestDialog>(
                    TestControlsRequestDialog::class.java
            ) {
                    override fun create(intent: Intent?): TestControlsRequestDialog {
                        return TestControlsRequestDialog(
                                controller,
                                broadcastDispatcher,
                                listingController
                        )
                    }
            }, false, false)

    private lateinit var control: Control

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        control = Control.StatelessBuilder(CONTROL_ID, PendingIntent(iIntentSender))
                .setTitle("TITLE")
                .setSubtitle("SUBTITLE")
                .setDeviceType(DeviceTypes.TYPE_LIGHT)
                .setStructure("STRUCTURE")
                .build()

        val intent = Intent(mContext, TestControlsRequestDialog::class.java)
        intent.putExtra(Intent.EXTRA_USER_ID, USER_ID)
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, CONTROL_COMPONENT)
        intent.putExtra(ControlsProviderService.EXTRA_CONTROL, control)

        `when`(controller.currentUserId).thenReturn(USER_ID)
        `when`(listingController.getAppLabel(CONTROL_COMPONENT)).thenReturn(LABEL)
        `when`(controller.getFavoritesForComponent(CONTROL_COMPONENT)).thenReturn(emptyList())

        activityRule.launchActivity(intent)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    fun testActivityNotFinished() {
        assertNotEquals(Lifecycle.State.DESTROYED,
                activityRule.getActivity().lifecycle.currentState)
    }

    @Test
    fun testDialogAddsCorrectControl() {
        activityRule.activity.onClick(null, Dialog.BUTTON_POSITIVE)

        verify(controller)
                .addFavorite(eq(CONTROL_COMPONENT), eq(control.structure!!), capture(captor))

        captor.value.let {
            assertEquals(control.controlId, it.controlId)
            assertEquals(control.title, it.controlTitle)
            assertEquals(control.subtitle, it.controlSubtitle)
            assertEquals(control.deviceType, it.deviceType)
        }
    }
}
