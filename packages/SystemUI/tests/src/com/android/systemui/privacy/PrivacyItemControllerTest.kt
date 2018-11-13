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

import android.app.AppOpsManager
import android.os.Handler
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify

import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class PrivacyItemControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback

    private lateinit var testableLooper: TestableLooper
    private lateinit var privacyItemController: PrivacyItemController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        appOpsController = mDependency.injectMockDependency(AppOpsController:: class.java)
        mDependency.injectTestDependency(Dependency.BG_LOOPER, testableLooper.looper)
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER, Handler(testableLooper.looper))

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
                any(AppOpsController.Callback:: class.java))
    }
}