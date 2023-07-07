/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.content.pm.PackageManager
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_FLIPPED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tuner.TunerService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardBypassControllerTest : SysuiTestCase() {

    private lateinit var keyguardBypassController: KeyguardBypassController
    private lateinit var postureControllerCallback: DevicePostureController.Callback
    @Mock private lateinit var tunerService: TunerService
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var lockscreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    @Mock private lateinit var devicePostureController: DevicePostureController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var packageManager: PackageManager
    @Captor
    private val postureCallbackCaptor =
        ArgumentCaptor.forClass(DevicePostureController.Callback::class.java)
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        context.setMockPackageManager(packageManager)
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true)
        whenever(keyguardStateController.isFaceAuthEnabled).thenReturn(true)
    }

    @After
    fun tearDown() {
        reset(devicePostureController)
        reset(keyguardStateController)
    }

    private fun defaultConfigPostureClosed() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_auth_supported_posture,
            DEVICE_POSTURE_CLOSED
        )
        initKeyguardBypassController()
        verify(devicePostureController).addCallback(postureCallbackCaptor.capture())
        postureControllerCallback = postureCallbackCaptor.value
    }

    private fun defaultConfigPostureOpened() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_auth_supported_posture,
            DEVICE_POSTURE_OPENED
        )
        initKeyguardBypassController()
        verify(devicePostureController).addCallback(postureCallbackCaptor.capture())
        postureControllerCallback = postureCallbackCaptor.value
    }

    private fun defaultConfigPostureFlipped() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_auth_supported_posture,
            DEVICE_POSTURE_FLIPPED
        )
        initKeyguardBypassController()
        verify(devicePostureController).addCallback(postureCallbackCaptor.capture())
        postureControllerCallback = postureCallbackCaptor.value
    }

    private fun defaultConfigPostureUnknown() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_auth_supported_posture,
            DEVICE_POSTURE_UNKNOWN
        )
        initKeyguardBypassController()
        verify(devicePostureController, never()).addCallback(postureCallbackCaptor.capture())
    }

    private fun initKeyguardBypassController() {
        keyguardBypassController =
            KeyguardBypassController(
                context,
                tunerService,
                statusBarStateController,
                lockscreenUserManager,
                keyguardStateController,
                shadeExpansionStateManager,
                devicePostureController,
                dumpManager
            )
    }

    @Test
    fun configDevicePostureClosed_matchState_isPostureAllowedForFaceAuth_returnTrue() {
        defaultConfigPostureClosed()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isTrue()
    }

    @Test
    fun configDevicePostureOpen_matchState_isPostureAllowedForFaceAuth_returnTrue() {
        defaultConfigPostureOpened()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isTrue()
    }

    @Test
    fun configDevicePostureFlipped_matchState_isPostureAllowedForFaceAuth_returnTrue() {
        defaultConfigPostureFlipped()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_FLIPPED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isTrue()
    }

    @Test
    fun configDevicePostureClosed_changeOpened_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureClosed()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun configDevicePostureClosed_changeFlipped_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureClosed()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_FLIPPED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun configDevicePostureOpened_changeClosed_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureOpened()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun configDevicePostureOpened_changeFlipped_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureOpened()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_FLIPPED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun configDevicePostureFlipped_changeClosed_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureFlipped()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun configDevicePostureFlipped_changeOpened_isPostureAllowedForFaceAuth_returnFalse() {
        defaultConfigPostureFlipped()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        assertThat(keyguardBypassController.isPostureAllowedForFaceAuth()).isFalse()
    }

    @Test
    fun defaultConfigPostureClosed_canOverrideByPassAlways_shouldReturnFalse() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_unlock_bypass_override,
            1 /* FACE_UNLOCK_BYPASS_ALWAYS */
        )

        defaultConfigPostureClosed()

        postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        assertThat(keyguardBypassController.bypassEnabled).isFalse()
    }

    @Test
    fun defaultConfigPostureUnknown_canNotOverrideByPassAlways_shouldReturnTrue() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_unlock_bypass_override,
            1 /* FACE_UNLOCK_BYPASS_ALWAYS */
        )

        defaultConfigPostureUnknown()

        assertThat(keyguardBypassController.bypassEnabled).isTrue()
    }

    @Test
    fun defaultConfigPostureUnknown_canNotOverrideByPassNever_shouldReturnFalse() {
        context.orCreateTestableResources.addOverride(
            R.integer.config_face_unlock_bypass_override,
            2 /* FACE_UNLOCK_BYPASS_NEVER */
        )

        defaultConfigPostureUnknown()

        assertThat(keyguardBypassController.bypassEnabled).isFalse()
    }
}
