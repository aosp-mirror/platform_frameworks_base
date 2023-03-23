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

package com.android.systemui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ChooserPinMigrationTest : SysuiTestCase() {

    private val fakeFeatureFlags = FakeFeatureFlags()
    private val fakePreferences =
        mutableMapOf(
            "TestPinnedPackage/TestPinnedClass" to true,
            "TestUnpinnedPackage/TestUnpinnedClass" to false,
        )
    private val intent = kotlinArgumentCaptor<Intent>()
    private val permission = kotlinArgumentCaptor<String>()

    private lateinit var chooserPinMigration: ChooserPinMigration

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockResources: Resources
    @Mock
    private lateinit var mockLegacyPinPrefsFileSupplier:
        ChooserPinMigration.Companion.LegacyPinPrefsFileSupplier
    @Mock private lateinit var mockFile: File
    @Mock private lateinit var mockSharedPreferences: SharedPreferences
    @Mock private lateinit var mockSharedPreferencesEditor: SharedPreferences.Editor
    @Mock private lateinit var mockBroadcastSender: BroadcastSender

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(mockContext.resources).thenReturn(mockResources)
        whenever(mockContext.getSharedPreferences(any<File>(), anyInt()))
            .thenReturn(mockSharedPreferences)
        whenever(mockResources.getString(anyInt())).thenReturn("TestPackage/TestClass")
        whenever(mockSharedPreferences.all).thenReturn(fakePreferences)
        whenever(mockSharedPreferences.edit()).thenReturn(mockSharedPreferencesEditor)
        whenever(mockSharedPreferencesEditor.commit()).thenReturn(true)
        whenever(mockLegacyPinPrefsFileSupplier.get()).thenReturn(mockFile)
        whenever(mockFile.exists()).thenReturn(true)
        whenever(mockFile.delete()).thenReturn(true)
        fakeFeatureFlags.set(Flags.CHOOSER_MIGRATION_ENABLED, true)
    }

    @Test
    fun start_performsMigration() {
        // Arrange
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verify(mockBroadcastSender).sendBroadcast(intent.capture(), permission.capture())
        assertThat(intent.value.action).isEqualTo("android.intent.action.CHOOSER_PIN_MIGRATION")
        assertThat(intent.value.`package`).isEqualTo("TestPackage")
        assertThat(intent.value.extras?.keySet()).hasSize(2)
        assertThat(intent.value.hasExtra("TestPinnedPackage/TestPinnedClass")).isTrue()
        assertThat(intent.value.getBooleanExtra("TestPinnedPackage/TestPinnedClass", false))
            .isTrue()
        assertThat(intent.value.hasExtra("TestUnpinnedPackage/TestUnpinnedClass")).isTrue()
        assertThat(intent.value.getBooleanExtra("TestUnpinnedPackage/TestUnpinnedClass", true))
            .isFalse()
        assertThat(permission.value).isEqualTo("android.permission.RECEIVE_CHOOSER_PIN_MIGRATION")

        // Assert
        verify(mockSharedPreferencesEditor).clear()
        verify(mockSharedPreferencesEditor).commit()

        // Assert
        verify(mockFile).delete()
    }

    @Test
    fun start_doesNotDeleteLegacyPreferencesFile_whenClearingItFails() {
        // Arrange
        whenever(mockSharedPreferencesEditor.commit()).thenReturn(false)
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verify(mockBroadcastSender).sendBroadcast(intent.capture(), permission.capture())
        assertThat(intent.value.action).isEqualTo("android.intent.action.CHOOSER_PIN_MIGRATION")
        assertThat(intent.value.`package`).isEqualTo("TestPackage")
        assertThat(intent.value.extras?.keySet()).hasSize(2)
        assertThat(intent.value.hasExtra("TestPinnedPackage/TestPinnedClass")).isTrue()
        assertThat(intent.value.getBooleanExtra("TestPinnedPackage/TestPinnedClass", false))
            .isTrue()
        assertThat(intent.value.hasExtra("TestUnpinnedPackage/TestUnpinnedClass")).isTrue()
        assertThat(intent.value.getBooleanExtra("TestUnpinnedPackage/TestUnpinnedClass", true))
            .isFalse()
        assertThat(permission.value).isEqualTo("android.permission.RECEIVE_CHOOSER_PIN_MIGRATION")

        // Assert
        verify(mockSharedPreferencesEditor).clear()
        verify(mockSharedPreferencesEditor).commit()

        // Assert
        verify(mockFile, never()).delete()
    }

    @Test
    fun start_OnlyDeletesLegacyPreferencesFile_whenEmpty() {
        // Arrange
        whenever(mockSharedPreferences.all).thenReturn(emptyMap())
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verifyZeroInteractions(mockBroadcastSender)

        // Assert
        verifyZeroInteractions(mockSharedPreferencesEditor)

        // Assert
        verify(mockFile).delete()
    }

    @Test
    fun start_DoesNotDoMigration_whenFlagIsDisabled() {
        // Arrange
        fakeFeatureFlags.set(Flags.CHOOSER_MIGRATION_ENABLED, false)
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verifyZeroInteractions(mockBroadcastSender)

        // Assert
        verifyZeroInteractions(mockSharedPreferencesEditor)

        // Assert
        verify(mockFile, never()).delete()
    }

    @Test
    fun start_DoesNotDoMigration_whenLegacyPreferenceFileNotPresent() {
        // Arrange
        whenever(mockFile.exists()).thenReturn(false)
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verifyZeroInteractions(mockBroadcastSender)

        // Assert
        verifyZeroInteractions(mockSharedPreferencesEditor)

        // Assert
        verify(mockFile, never()).delete()
    }

    @Test
    fun start_DoesNotDoMigration_whenConfiguredChooserComponentIsInvalid() {
        // Arrange
        whenever(mockResources.getString(anyInt())).thenReturn("InvalidComponent")
        chooserPinMigration =
            ChooserPinMigration(
                mockContext,
                fakeFeatureFlags,
                mockBroadcastSender,
                mockLegacyPinPrefsFileSupplier,
            )

        // Act
        chooserPinMigration.start()

        // Assert
        verifyZeroInteractions(mockBroadcastSender)

        // Assert
        verifyZeroInteractions(mockSharedPreferencesEditor)

        // Assert
        verify(mockFile, never()).delete()
    }
}
