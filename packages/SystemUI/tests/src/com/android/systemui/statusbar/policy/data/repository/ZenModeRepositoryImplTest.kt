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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.policy.data.repository

import android.app.NotificationManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ZenModeRepositoryImplTest : SysuiTestCase() {
    @Mock lateinit var zenModeController: ZenModeController

    lateinit var underTest: ZenModeRepositoryImpl

    private val testPolicy = NotificationManager.Policy(0, 1, 0)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest = ZenModeRepositoryImpl(zenModeController)
    }

    @Test
    fun zenMode_reflectsCurrentControllerState() = runTest {
        whenever(zenModeController.zen).thenReturn(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
        val zenMode by collectLastValue(underTest.zenMode)
        assertThat(zenMode).isEqualTo(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
    }

    @Test
    fun zenMode_updatesWhenControllerStateChanges() = runTest {
        val zenMode by collectLastValue(underTest.zenMode)
        runCurrent()
        whenever(zenModeController.zen).thenReturn(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
        withArgCaptor { Mockito.verify(zenModeController).addCallback(capture()) }
            .onZenChanged(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
        assertThat(zenMode).isEqualTo(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
    }

    @Test
    fun policy_reflectsCurrentControllerState() {
        runTest {
            whenever(zenModeController.consolidatedPolicy).thenReturn(testPolicy)
            val policy by collectLastValue(underTest.consolidatedNotificationPolicy)
            assertThat(policy).isEqualTo(testPolicy)
        }
    }

    @Test
    fun policy_updatesWhenControllerStateChanges() = runTest {
        val policy by collectLastValue(underTest.consolidatedNotificationPolicy)
        runCurrent()
        whenever(zenModeController.consolidatedPolicy).thenReturn(testPolicy)
        withArgCaptor { Mockito.verify(zenModeController).addCallback(capture()) }
            .onConsolidatedPolicyChanged(testPolicy)
        assertThat(policy).isEqualTo(testPolicy)
    }
}
