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

import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
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

        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        private const val LOCATION = SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_ENABLED
    }

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var logger: PrivacyLogger

    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var executor: FakeExecutor
    private lateinit var deviceConfigProxy: DeviceConfigProxy

    fun createPrivacyItemController(): PrivacyItemController {
        return PrivacyItemController(
                appOpsController,
                executor,
                executor,
                deviceConfigProxy,
                userTracker,
                logger,
                FakeSystemClock(),
                dumpManager)
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        deviceConfigProxy = DeviceConfigProxyFake()

        privacyItemController = createPrivacyItemController()
        privacyItemController.addCallback(callback)

        executor.runAllReady()
    }

    @Test
    fun testMicCameraListeningByDefault() {
        assertTrue(privacyItemController.micCameraAvailable)
    }

    @Test
    fun testMicCameraChanged() {
        changeMicCamera(false) // default is true
        executor.runAllReady()

        verify(callback).onFlagMicCameraChanged(false)

        assertFalse(privacyItemController.micCameraAvailable)
    }

    @Test
    fun testLocationChanged() {
        changeLocation(true)
        executor.runAllReady()

        verify(callback).onFlagLocationChanged(true)
        assertTrue(privacyItemController.locationAvailable)
    }

    @Test
    fun testBothChanged() {
        changeAll(true)
        changeMicCamera(false)
        executor.runAllReady()

        verify(callback, atLeastOnce()).onFlagLocationChanged(true)
        verify(callback, atLeastOnce()).onFlagMicCameraChanged(false)

        assertTrue(privacyItemController.locationAvailable)
        assertFalse(privacyItemController.micCameraAvailable)
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
    fun testLocation_listening() {
        changeLocation(true)
        executor.runAllReady()

        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS), any())
    }

    @Test
    fun testAllFalse_notListening() {
        changeAll(true)
        executor.runAllReady()
        changeAll(false)
        changeMicCamera(false)
        executor.runAllReady()

        verify(appOpsController).removeCallback(any(), any())
    }

    @Test
    fun testMicDeleted_stillListening() {
        changeMicCamera(true)
        executor.runAllReady()
        changeMicCamera(null)
        executor.runAllReady()

        verify(appOpsController, never()).removeCallback(any(), any())
    }

    private fun changeMicCamera(value: Boolean?) = changeProperty(MIC_CAMERA, value)
    private fun changeLocation(value: Boolean?) = changeProperty(LOCATION, value)
    private fun changeAll(value: Boolean?) {
        changeMicCamera(value)
        changeLocation(value)
    }

    private fun changeProperty(name: String, value: Boolean?) {
        deviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                name,
                value?.toString(),
                false
        )
    }
}