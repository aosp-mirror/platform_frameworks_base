/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.display

import androidx.test.filters.SmallTest
import android.hardware.display.DisplayManagerInternal
import android.util.AtomicFileOutputStream
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.never

@SmallTest
class DisplayBackupHelperTest {
    private val mockInjector = mock<DisplayBackupHelper.Injector>()
    private val mockDmsInternal = mock<DisplayManagerInternal>()
    private val mockWriteTopologyFile = mock<AtomicFileOutputStream>()
    private val byteArray = byteArrayOf(0b00000001, 0b00000010, 0b00000011)
    private val helper = createBackupHelper(0, byteArray)

    @Test
    fun testBackupDisplayReturnsBytes() {
        assertThat(helper.getBackupPayload("display")).isEqualTo(byteArray)
    }

    @Test
    fun testBackupSomethingReturnsNull() {
        assertThat(helper.getBackupPayload("something")).isNull()
    }

    @Test
    fun testBackupDisplayReturnsNullWhenFlagDisabled() {
        whenever(mockInjector.isDisplayTopologyFlagEnabled()).thenReturn(false)
        assertThat(helper.getBackupPayload("display")).isNull()
    }

    @Test
    fun testRestoreDisplay() {
        helper.applyRestoredPayload("display", byteArray)
        verify(mockWriteTopologyFile).write(byteArray)
        verify(mockWriteTopologyFile).markSuccess()
        verify(mockDmsInternal).reloadTopologies(0)
    }

    @Test
    fun testRestoreSomethingDoesNothing() {
        helper.applyRestoredPayload("something", byteArray)
        verify(mockWriteTopologyFile, never()).write(byteArray)
        verify(mockWriteTopologyFile, never()).markSuccess()
        verify(mockDmsInternal, never()).reloadTopologies(0)
    }

    @Test
    fun testRestoreDisplayDoesNothingWhenFlagDisabled() {
        whenever(mockInjector.isDisplayTopologyFlagEnabled()).thenReturn(false)
        helper.applyRestoredPayload("display", byteArray)
        verify(mockWriteTopologyFile, never()).write(byteArray)
        verify(mockWriteTopologyFile, never()).markSuccess()
        verify(mockDmsInternal, never()).reloadTopologies(0)
    }

    fun createBackupHelper(userId: Int, topologyToBackup: ByteArray): DisplayBackupHelper {
        whenever(mockInjector.getDisplayManagerInternal()).thenReturn(mockDmsInternal)
        whenever(mockInjector.readTopologyFile(userId)).thenReturn(topologyToBackup)
        whenever(mockInjector.writeTopologyFile(userId)).thenReturn(mockWriteTopologyFile)
        whenever(mockInjector.isDisplayTopologyFlagEnabled()).thenReturn(true)

        return DisplayBackupHelper(userId, mockInjector)
    }
}