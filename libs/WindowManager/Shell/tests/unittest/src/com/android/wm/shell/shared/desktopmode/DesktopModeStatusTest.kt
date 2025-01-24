/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.shared.desktopmode

import android.content.Context
import android.content.res.Resources
import android.os.SystemProperties
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.provider.Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES
import android.window.DesktopModeFlags
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@Presubmit
@EnableFlags(Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
class DesktopModeStatusTest : ShellTestCase() {
    @get:Rule(order = 0)
    val mSetFlagsRule = SetFlagsRule();

    @get:Rule(order = 1)
    val extendedMockitoRule =
        ExtendedMockitoRule.Builder(this)
            .mockStatic(SystemProperties::class.java)
            .build()

    private val mockContext = mock<Context>()
    private val mockResources = mock<Resources>()

    @Before
    fun setUp() {
        doReturn(mockResources).whenever(mockContext).getResources()
        doReturn(false).whenever(mockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported))
        doReturn(false).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        );
        doReturn(context.contentResolver).whenever(mockContext).contentResolver
        resetDesktopModeFlagsCache()
        resetEnforceDeviceRestriction()
        resetFlagOverride()
    }

    @After
    fun tearDown() {
        resetDesktopModeFlagsCache()
        resetEnforceDeviceRestriction()
        resetFlagOverride();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagDisabled_configsOff_returnsFalse() {
        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isFalse()
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagDisabled_configsOn_disableDeviceRestrictions_returnsFalse() {
        doReturn(true).whenever(mockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported))
        doReturn(true).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        );
        disableEnforceDeviceRestriction()

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isFalse()
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagDisabled_configDevOptionOn_returnsFalse() {
        doReturn(true).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        )

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isFalse()
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagDisabled_configDevOptionOn_flagOverrideOn_returnsTrue() {
        doReturn(true).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        )
        setFlagOverride(DesktopModeFlags.ToggleOverride.OVERRIDE_ON);

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isTrue()
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagEnabled_configsOff_returnsFalse() {
        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isFalse()
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagEnabled_configDesktopModeOff_returnsFalse() {
        doReturn(true).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        )

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isFalse()
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagEnabled_configDesktopModeOn_returnsTrue() {
        doReturn(true).whenever(mockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported))

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isTrue()
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagEnabled_configsOff_disableDeviceRestrictions_returnsTrue() {
        disableEnforceDeviceRestriction();

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isTrue()
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    fun canEnterDesktopMode_DWFlagEnabled_configDevOptionOn_flagOverrideOn_returnsTrue() {
        doReturn(true).whenever(mockResources).getBoolean(
            eq(R.bool.config_isDesktopModeDevOptionSupported)
        )
        setFlagOverride(DesktopModeFlags.ToggleOverride.OVERRIDE_ON)

        assertThat(DesktopModeStatus.canEnterDesktopMode(mockContext)).isTrue()
    }

    private fun resetEnforceDeviceRestriction() {
        doAnswer { invocation -> invocation.getArgument<Boolean>(1) }.whenever(
            SystemProperties.getBoolean(
                DesktopModeStatus.ENFORCE_DEVICE_RESTRICTIONS_PROPERTY,
                anyBoolean()
            )
        )
    }

    private fun disableEnforceDeviceRestriction() {
        doReturn(false).whenever(
            SystemProperties.getBoolean(
                DesktopModeStatus.ENFORCE_DEVICE_RESTRICTIONS_PROPERTY,
                anyBoolean()
            )
        )
    }

    private fun resetDesktopModeFlagsCache() {
        val cachedToggleOverride =
            DesktopModeFlags::class.java.getDeclaredField("sCachedToggleOverride")
        cachedToggleOverride.isAccessible = true
        cachedToggleOverride.set(null, null)
    }

    private fun resetFlagOverride() {
        Settings.Global.putString(
            context.contentResolver,
            DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES, null
        )
    }

    private fun setFlagOverride(override: DesktopModeFlags.ToggleOverride) {
        Settings.Global.putInt(
            context.contentResolver,
            DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES, override.setting
        )
    }
}
