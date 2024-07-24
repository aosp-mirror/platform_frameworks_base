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

package com.android.systemui.statusbar.policy

import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.impl.CameraMetadataNative
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.time.FakeSystemClock
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class FlashlightControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var dumpManager: DumpManager

    @Mock
    private lateinit var cameraManager: CameraManager

    @Mock
    private lateinit var broadcastSender: BroadcastSender

    @Mock
    private lateinit var packageManager: PackageManager

    private lateinit var fakeSettings: FakeSettings
    private lateinit var fakeSystemClock: FakeSystemClock
    private lateinit var backgroundExecutor: FakeExecutor
    private lateinit var controller: FlashlightControllerImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        fakeSystemClock = FakeSystemClock()
        backgroundExecutor = FakeExecutor(fakeSystemClock)
        fakeSettings = FakeSettings()

        `when`(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
                .thenReturn(true)

        controller = FlashlightControllerImpl(
                dumpManager,
                cameraManager,
                backgroundExecutor,
                fakeSettings,
                broadcastSender,
                packageManager
        )
    }

    @Test
    fun testNoCameraManagerInteractionDirectlyOnConstructor() {
        verifyZeroInteractions(cameraManager)
    }

    @Test
    fun testCameraManagerInitAfterConstructionOnExecutor() {
        injectCamera()
        backgroundExecutor.runAllReady()

        verify(cameraManager).registerTorchCallback(eq(backgroundExecutor), any())
    }

    @Test
    fun testNoCallbackIfNoFlashCamera() {
        injectCamera(flash = false)
        backgroundExecutor.runAllReady()

        verify(cameraManager, never()).registerTorchCallback(any<Executor>(), any())
    }

    @Test
    fun testNoCallbackIfNoBackCamera() {
        injectCamera(facing = CameraCharacteristics.LENS_FACING_FRONT)
        backgroundExecutor.runAllReady()

        verify(cameraManager, never()).registerTorchCallback(any<Executor>(), any())
    }

    @Test
    fun testSetFlashlightInBackgroundExecutor() {
        val id = injectCamera()
        backgroundExecutor.runAllReady()

        clearInvocations(cameraManager)
        val enable = !controller.isEnabled
        controller.setFlashlight(enable)
        verifyNoMoreInteractions(cameraManager)

        backgroundExecutor.runAllReady()
        verify(cameraManager).setTorchMode(id, enable)
    }

    @Test
    fun testCallbackRemovedWhileDispatching_doesntCrash() {
        injectCamera()
        var remove = false
        val callback = object : FlashlightController.FlashlightListener {
            override fun onFlashlightChanged(enabled: Boolean) {
                if (remove) {
                    controller.removeCallback(this)
                }
            }

            override fun onFlashlightError() {}

            override fun onFlashlightAvailabilityChanged(available: Boolean) {}
        }
        controller.addCallback(callback)
        controller.addCallback(object : FlashlightController.FlashlightListener {
            override fun onFlashlightChanged(enabled: Boolean) {}

            override fun onFlashlightError() {}

            override fun onFlashlightAvailabilityChanged(available: Boolean) {}
        })
        backgroundExecutor.runAllReady()

        val captor = argumentCaptor<CameraManager.TorchCallback>()
        verify(cameraManager).registerTorchCallback(any(), capture(captor))
        remove = true
        captor.value.onTorchModeChanged(ID, true)
    }

    private fun injectCamera(
        flash: Boolean = true,
        facing: Int = CameraCharacteristics.LENS_FACING_BACK
    ): String {
        val cameraID = ID
        val camera = CameraCharacteristics(CameraMetadataNative().apply {
            set(CameraCharacteristics.FLASH_INFO_AVAILABLE, flash)
            set(CameraCharacteristics.LENS_FACING, facing)
        })
        `when`(cameraManager.cameraIdList).thenReturn(arrayOf(cameraID))
        `when`(cameraManager.getCameraCharacteristics(cameraID)).thenReturn(camera)
        return cameraID
    }

    companion object {
        private const val ID = "ID"
    }
}
