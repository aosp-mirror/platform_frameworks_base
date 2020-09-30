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

package com.android.systemui.privacy

import android.os.UserManager
import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpsController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class PrivacyItemControllerFlagsTest : SysuiTestCase() {
    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        fun <T> any(): T = Mockito.any<T>()

        private const val ALL_INDICATORS =
                SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
    }

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var dumpManager: DumpManager

    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var executor: FakeExecutor
    private lateinit var deviceConfigProxy: DeviceConfigProxy

    fun PrivacyItemController(): PrivacyItemController {
        return PrivacyItemController(
                appOpsController,
                executor,
                executor,
                broadcastDispatcher,
                deviceConfigProxy,
                userManager,
                dumpManager
        )
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        deviceConfigProxy = DeviceConfigProxyFake()

        privacyItemController = PrivacyItemController()
        privacyItemController.addCallback(callback)

        executor.runAllReady()
    }

    @Test
    fun testNotListeningByDefault() {
        assertFalse(privacyItemController.allIndicatorsAvailable)
        assertFalse(privacyItemController.micCameraAvailable)

        verify(appOpsController, never()).addCallback(any(), any())
    }

    @Test
    fun testMicCameraChanged() {
        changeMicCamera(true)
        executor.runAllReady()

        verify(callback).onFlagMicCameraChanged(true)
        verify(callback, never()).onFlagAllChanged(anyBoolean())

        assertTrue(privacyItemController.micCameraAvailable)
        assertFalse(privacyItemController.allIndicatorsAvailable)
    }

    @Test
    fun testAllChanged() {
        changeAll(true)
        executor.runAllReady()

        verify(callback).onFlagAllChanged(true)
        verify(callback, never()).onFlagMicCameraChanged(anyBoolean())

        assertTrue(privacyItemController.allIndicatorsAvailable)
        assertFalse(privacyItemController.micCameraAvailable)
    }

    @Test
    fun testBothChanged() {
        changeAll(true)
        changeMicCamera(true)
        executor.runAllReady()

        verify(callback, atLeastOnce()).onFlagAllChanged(true)
        verify(callback, atLeastOnce()).onFlagMicCameraChanged(true)

        assertTrue(privacyItemController.allIndicatorsAvailable)
        assertTrue(privacyItemController.micCameraAvailable)
    }

    @Test
    fun testAll_listeningToAll() {
        changeAll(true)
        executor.runAllReady()

        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS), any())
    }

    @Test
    fun testMicCamera_listening() {
        changeMicCamera(true)
        executor.runAllReady()

        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS), any())
    }

    @Test
    fun testAll_listening() {
        changeAll(true)
        executor.runAllReady()

        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS), any())
    }

    @Test
    fun testAllFalse_notListening() {
        changeAll(true)
        executor.runAllReady()
        changeAll(false)
        executor.runAllReady()

        verify(appOpsController).removeCallback(any(), any())
    }

    @Test
    fun testSomeListening_stillListening() {
        changeAll(true)
        changeMicCamera(true)
        executor.runAllReady()
        changeAll(false)
        executor.runAllReady()

        verify(appOpsController, never()).removeCallback(any(), any())
    }

    @Test
    fun testAllDeleted_stopListening() {
        changeAll(true)
        executor.runAllReady()
        changeAll(null)
        executor.runAllReady()

        verify(appOpsController).removeCallback(any(), any())
    }

    @Test
    fun testMicDeleted_stopListening() {
        changeMicCamera(true)
        executor.runAllReady()
        changeMicCamera(null)
        executor.runAllReady()

        verify(appOpsController).removeCallback(any(), any())
    }

    private fun changeMicCamera(value: Boolean?) = changeProperty(MIC_CAMERA, value)
    private fun changeAll(value: Boolean?) = changeProperty(ALL_INDICATORS, value)

    private fun changeProperty(name: String, value: Boolean?) {
        deviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                name,
                value?.toString(),
                false
        )
    }
}