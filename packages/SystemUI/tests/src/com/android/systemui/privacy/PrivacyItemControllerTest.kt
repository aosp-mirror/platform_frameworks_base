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
import android.content.Intent
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.support.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import com.android.systemui.Dependency
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class PrivacyItemControllerTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = ActivityManager.getCurrentUser()
        val OTHER_USER = UserHandle(CURRENT_USER_ID + 1)
        const val TAG = "PrivacyItemControllerTest"
    }

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var userManager: UserManager

    private lateinit var testableLooper: TestableLooper
    private lateinit var privacyItemController: PrivacyItemController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        appOpsController = mDependency.injectMockDependency(AppOpsController::class.java)
        mDependency.injectTestDependency(Dependency.BG_LOOPER, testableLooper.looper)
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER, Handler(testableLooper.looper))
        mContext.addMockSystemService(UserManager::class.java, userManager)

        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, 0, "", 0)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        privacyItemController = PrivacyItemController(mContext, callback)
    }

    @Test
    fun testSetListeningTrue() {
        privacyItemController.setListening(true)
        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS),
                any(AppOpsController.Callback::class.java))
        testableLooper.processAllMessages()
        verify(callback).privacyChanged(anyList())
    }

    @Test
    fun testSetListeningFalse() {
        privacyItemController.setListening(true)
        privacyItemController.setListening(false)
        verify(appOpsController).removeCallback(eq(PrivacyItemController.OPS),
                any(AppOpsController.Callback::class.java))
    }

    @Test
    fun testRegisterReceiver_allUsers() {
        val spiedContext = spy(mContext)
        val itemController = PrivacyItemController(spiedContext, callback)

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
}