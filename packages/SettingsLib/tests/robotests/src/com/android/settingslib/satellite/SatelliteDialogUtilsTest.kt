/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.satellite

import android.content.Context
import android.platform.test.annotations.RequiresFlagsEnabled
import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_ENABLING_SATELLITE
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_OFF
import android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_ERROR
import android.telephony.satellite.SatelliteModemStateCallback
import android.util.AndroidRuntimeException
import androidx.test.core.app.ApplicationProvider
import com.android.internal.telephony.flags.Flags
import com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_WIFI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.verify
import org.mockito.internal.verification.Times
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SatelliteDialogUtilsTest {
    @JvmField
    @Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Spy
    var context: Context = ApplicationProvider.getApplicationContext()
    @Mock
    private lateinit var satelliteManager: SatelliteManager

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    @Before
    fun setUp() {
        `when`(context.getSystemService(SatelliteManager::class.java))
                .thenReturn(satelliteManager)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun mayStartSatelliteWarningDialog_satelliteIsOn_showWarningDialog() = runBlocking {
        `when`(satelliteManager.registerForModemStateChanged(any(), any()))
                .thenAnswer { invocation ->
                    val callback = invocation
                            .getArgument<SatelliteModemStateCallback>(1)
                    callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_ENABLING_SATELLITE)
                    null
                }

        try {
            SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                    context, coroutineScope, TYPE_IS_WIFI, allowClick = {
                assertTrue(it)
            })
        } catch (e: AndroidRuntimeException) {
            // Catch exception of starting activity .
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun mayStartSatelliteWarningDialog_satelliteIsOff_notShowWarningDialog() = runBlocking {
        `when`(satelliteManager.registerForModemStateChanged(any(), any()))
                .thenAnswer { invocation ->
                    val callback = invocation
                            .getArgument<SatelliteModemStateCallback>(1)
                    callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_OFF)
                    null
                }


        SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                context, coroutineScope, TYPE_IS_WIFI, allowClick = {
            assertFalse(it)
        })

        verify(context, Times(0)).startActivity(any())
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun mayStartSatelliteWarningDialog_noSatelliteManager_notShowWarningDialog() = runBlocking {
        `when`(context.getSystemService(SatelliteManager::class.java)).thenReturn(null)

        SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                context, coroutineScope, TYPE_IS_WIFI, allowClick = {
            assertFalse(it)
        })

        verify(context, Times(0)).startActivity(any())
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun mayStartSatelliteWarningDialog_satelliteErrorResult_notShowWarningDialog() = runBlocking {
        `when`(satelliteManager.registerForModemStateChanged(any(), any()))
                .thenReturn(SATELLITE_RESULT_MODEM_ERROR)

        SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                context, coroutineScope, TYPE_IS_WIFI, allowClick = {
            assertFalse(it)
        })

        verify(context, Times(0)).startActivity(any())
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun mayStartSatelliteWarningDialog_phoneCrash_notShowWarningDialog() = runBlocking {
        `when`(satelliteManager.registerForModemStateChanged(any(), any()))
                .thenThrow(IllegalStateException("Telephony is null!!!"))

        SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                context, coroutineScope, TYPE_IS_WIFI, allowClick = {
            assertFalse(it)
        })

        verify(context, Times(0)).startActivity(any())
    }
}
