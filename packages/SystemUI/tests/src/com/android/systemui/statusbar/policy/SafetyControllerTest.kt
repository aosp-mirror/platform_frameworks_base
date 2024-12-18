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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.safetycenter.SafetyCenterManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidJUnit4::class)
class SafetyControllerTest : SysuiTestCase() {

    private val TEST_PC_PKG = "testPermissionControllerPackageName"
    private val OTHER_PKG = "otherPackageName"

    @Mock
    private lateinit var context: Context
    @Mock
    private lateinit var scm: SafetyCenterManager
    @Mock
    private lateinit var pm: PackageManager
    @Mock
    private lateinit var handler: Handler
    @Mock
    private lateinit var listener: SafetyController.Listener

    private val packageDataScheme = "package"

    private lateinit var controller: SafetyController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(pm.permissionControllerPackageName).thenReturn(TEST_PC_PKG)
        `when`(scm.isSafetyCenterEnabled).thenReturn(false)
        `when`(handler.post(any(Runnable::class.java))).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        controller = SafetyController(context, pm, scm, handler)
    }

    @Test
    fun addingFirstListenerRegistersReceiver() {
        `when`(scm.isSafetyCenterEnabled).thenReturn(true)
        controller.addCallback(listener)
        verify(listener, times(1)).onSafetyCenterEnableChanged(true)
        val filter = ArgumentCaptor.forClass(IntentFilter::class.java)
        verify(context, times(1)).registerReceiver(
            same(controller.mPermControllerChangeReceiver), filter.capture())
        assertEquals(Intent.ACTION_PACKAGE_CHANGED, filter.value.getAction(0))
        assertEquals(packageDataScheme, filter.value.getDataScheme(0))
    }

    @Test
    fun removingLastListenerDeregistersReceiver() {
        controller.addCallback(listener)
        controller.removeCallback(listener)
        verify(context, times(1)).unregisterReceiver(
            eq(controller.mPermControllerChangeReceiver))
    }

    @Test
    fun listenersCalledWhenBroadcastReceivedWithPCPackageAndStateChange() {
        `when`(scm.isSafetyCenterEnabled).thenReturn(false)
        controller.addCallback(listener)
        reset(listener)
        `when`(scm.isSafetyCenterEnabled).thenReturn(true)
        val testIntent = Intent(Intent.ACTION_PACKAGE_CHANGED)
        testIntent.data = Uri.parse("package:$TEST_PC_PKG")
        controller.mPermControllerChangeReceiver.onReceive(context, testIntent)
        verify(listener, times(1)).onSafetyCenterEnableChanged(true)
    }

    @Test
    fun listenersNotCalledWhenBroadcastReceivedWithOtherPackage() {
        `when`(scm.isSafetyCenterEnabled).thenReturn(true)
        controller.addCallback(listener)
        reset(listener)
        val testIntent = Intent(Intent.ACTION_PACKAGE_CHANGED)
        testIntent.data = Uri.parse("package:$OTHER_PKG")
        controller.mPermControllerChangeReceiver.onReceive(context, testIntent)
        verify(listener, never()).onSafetyCenterEnableChanged(true)
    }

    @Test
    fun listenersNotCalledWhenBroadcastReceivedWithNoStateChange() {
        `when`(scm.isSafetyCenterEnabled).thenReturn(false)
        controller.addCallback(listener)
        reset(listener)
        val testIntent = Intent(Intent.ACTION_PACKAGE_CHANGED)
        testIntent.data = Uri.parse("package:$TEST_PC_PKG")
        controller.mPermControllerChangeReceiver.onReceive(context, testIntent)
        verify(listener, never()).onSafetyCenterEnableChanged(true)
    }

    @Test
    fun listenerRemovedWhileDispatching_doesNotCrash() {
        var remove = false
        val callback = object : SafetyController.Listener {
            override fun onSafetyCenterEnableChanged(isSafetyCenterEnabled: Boolean) {
                if (remove) {
                    controller.removeCallback(this)
                }
            }
        }

        controller.addCallback(callback)
        controller.addCallback {}

        remove = true

        `when`(scm.isSafetyCenterEnabled).thenReturn(true)
        val testIntent = Intent(Intent.ACTION_PACKAGE_CHANGED)
        testIntent.data = Uri.parse("package:$TEST_PC_PKG")
        controller.mPermControllerChangeReceiver.onReceive(context, testIntent)
    }

    @Test
    fun listenerRemovedWhileDispatching_otherCallbacksCalled() {
        var remove = false
        var called = false

        val callback1 = object : SafetyController.Listener {
            override fun onSafetyCenterEnableChanged(isSafetyCenterEnabled: Boolean) {
                if (remove) {
                    controller.removeCallback(this)
                }
            }
        }

        val callback2 = object : SafetyController.Listener {
            override fun onSafetyCenterEnableChanged(isSafetyCenterEnabled: Boolean) {
                // When the first callback is removed, we track if this is called
                if (remove) {
                    called = true
                }
            }
        }

        controller.addCallback(callback1)
        controller.addCallback(callback2)

        remove = true

        `when`(scm.isSafetyCenterEnabled).thenReturn(true)
        val testIntent = Intent(Intent.ACTION_PACKAGE_CHANGED)
        testIntent.data = Uri.parse("package:$TEST_PC_PKG")
        controller.mPermControllerChangeReceiver.onReceive(context, testIntent)

        assertThat(called).isTrue()
    }
}
