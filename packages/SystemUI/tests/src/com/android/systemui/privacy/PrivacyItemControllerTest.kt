/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.support.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import com.android.systemui.Dependency
import com.android.systemui.Dependency.BG_HANDLER
import com.android.systemui.Dependency.MAIN_HANDLER
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class PrivacyItemControllerTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = ActivityManager.getCurrentUser()
        val TEST_UID = CURRENT_USER_ID * UserHandle.PER_USER_RANGE
        const val SYSTEM_UID = 1000
        const val TEST_PACKAGE_NAME = "test"
        const val DEVICE_SERVICES_STRING = "Device services"
        const val TAG = "PrivacyItemControllerTest"
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
    }

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var userManager: UserManager
    @Captor
    private lateinit var argCaptor: ArgumentCaptor<List<PrivacyItem>>
    @Captor
    private lateinit var argCaptorCallback: ArgumentCaptor<AppOpsController.Callback>

    private lateinit var testableLooper: TestableLooper
    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var handler: Handler

    fun PrivacyItemController(context: Context) =
            PrivacyItemController(context, appOpsController, handler, handler)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        handler = Handler(testableLooper.looper)

        appOpsController = mDependency.injectMockDependency(AppOpsController::class.java)
        mDependency.injectTestDependency(Dependency.BG_HANDLER, handler)
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER, handler)
        mContext.addMockSystemService(UserManager::class.java, userManager)
        mContext.getOrCreateTestableResources().addOverride(R.string.device_services,
                DEVICE_SERVICES_STRING)

        doReturn(listOf(object : UserInfo() {
            init {
                id = CURRENT_USER_ID
            }
        })).`when`(userManager).getProfiles(anyInt())

        privacyItemController = PrivacyItemController(mContext)
    }

    @Test
    fun testSetListeningTrueByAddingCallback() {
        privacyItemController.addCallback(callback)
        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS),
                any(AppOpsController.Callback::class.java))
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(anyList())
    }

    @Test
    fun testSetListeningTrue() {
        privacyItemController.setListening(true)
        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS),
                any(AppOpsController.Callback::class.java))
    }

    @Test
    fun testSetListeningFalse() {
        privacyItemController.setListening(true)
        privacyItemController.setListening(false)
        verify(appOpsController).removeCallback(eq(PrivacyItemController.OPS),
                any(AppOpsController.Callback::class.java))
    }

    @Test
    fun testDistinctItems() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 1)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        privacyItemController.addCallback(callback)
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
    }

    @Test
    fun testSystemApps() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_COARSE_LOCATION, SYSTEM_UID, TEST_PACKAGE_NAME,
                0))).`when`(appOpsController).getActiveAppOpsForUser(anyInt())
        privacyItemController.addCallback(callback)
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
        assertEquals(context.getString(R.string.device_services),
                argCaptor.value[0].application.applicationName)
    }

    @Test
    fun testRegisterReceiver_allUsers() {
        val spiedContext = spy(mContext)
        val itemController = PrivacyItemController(spiedContext)
        itemController.setListening(true)
        verify(spiedContext, atLeastOnce()).registerReceiverAsUser(
                eq(itemController.userSwitcherReceiver), eq(UserHandle.ALL), any(), eq(null),
                eq(null))
        verify(spiedContext, never()).unregisterReceiver(eq(itemController.userSwitcherReceiver))
    }

    @Test
    fun testReceiver_ACTION_USER_FOREGROUND() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_USER_FOREGROUND))
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testReceiver_ACTION_MANAGED_PROFILE_ADDED() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_MANAGED_PROFILE_ADDED))
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testReceiver_ACTION_MANAGED_PROFILE_REMOVED() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED))
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testAddMultipleCallbacks() {
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(anyList())

        privacyItemController.addCallback(otherCallback)
        testableLooper.processAllMessages()
        verify(otherCallback).privacyChanged(anyList())
        // Adding a callback should not unnecessarily call previous ones
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun testMultipleCallbacksAreUpdated() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        testableLooper.processAllMessages()
        reset(callback)
        reset(otherCallback)

        verify(appOpsController).addCallback(any<IntArray>(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, "", true)
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(anyList())
        verify(otherCallback).privacyChanged(anyList())
    }

    @Test
    fun testRemoveCallback() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOpsForUser(anyInt())
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        testableLooper.processAllMessages()
        reset(callback)
        reset(otherCallback)

        verify(appOpsController).addCallback(any<IntArray>(), capture(argCaptorCallback))
        privacyItemController.removeCallback(callback)
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, "", true)
        testableLooper.processAllMessages()
        verify(callback, never()).privacyChanged(anyList())
        verify(otherCallback).privacyChanged(anyList())
    }
}