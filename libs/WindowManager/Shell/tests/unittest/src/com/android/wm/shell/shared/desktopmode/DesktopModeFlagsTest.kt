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

package com.android.wm.shell.shared.desktopmode

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE
import com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.shared.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.DESKTOP_WINDOWING_MODE
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_OFF
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_ON
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_UNSET
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Test class for [DesktopModeFlags]
 *
 * Usage: atest WMShellUnitTests:DesktopModeFlagsTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeFlagsTest : ShellTestCase() {

  @JvmField @Rule val setFlagsRule = SetFlagsRule()

  @Before
  fun setUp() {
    resetCache()
  }

  // TODO(b/348193756): Add tests
  // isEnabled_flagNotOverridable_overrideOff_featureFlagOn_returnsTrue and
  // isEnabled_flagNotOverridable_overrideOn_featureFlagOff_returnsFalse after adding non
  // overridable flags to DesktopModeFlags.

  @Test
  @DisableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_devOptionFlagDisabled_overrideOff_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_OFF.setting)

    // In absence of dev options, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @DisableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_devOptionFlagDisabled_overrideOn_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_ON.setting)

    // In absence of dev options, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_unsetOverride_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_UNSET.setting)

    // For overridableFlag, for unset overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_unsetOverride_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_UNSET.setting)

    // For overridableFlag, for unset overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_noOverride_featureFlagOn_returnsTrue() {
    setOverride(null)

    // For overridableFlag, in absence of overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_noOverride_featureFlagOff_returnsFalse() {
    setOverride(null)

    // For overridableFlag, in absence of overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_unrecognizableOverride_featureFlagOn_returnsTrue() {
    setOverride(-2)

    // For overridableFlag, for recognizable overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_unrecognizableOverride_featureFlagOff_returnsFalse() {
    setOverride(-2)

    // For overridableFlag, for recognizable overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_overrideOff_featureFlagOn_returnsFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_overrideOn_featureFlagOff_returnsTrue() {
    setOverride(OVERRIDE_ON.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_overrideOffThenOn_featureFlagOn_returnsFalseAndFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()

    setOverride(OVERRIDE_ON.setting)

    // Keep overrides constant through the process
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_overrideOnThenOff_featureFlagOff_returnsTrueAndTrue() {
    setOverride(OVERRIDE_ON.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()

    setOverride(OVERRIDE_OFF.setting)

    // Keep overrides constant through the process
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_flagOverridable_noOverride_featureFlagOnThenOff_returnsTrueAndFalse() {
    setOverride(null)
    // For overridableFlag, in absence of overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()

    val mockitoSession: StaticMockitoSession =
        ExtendedMockito.mockitoSession()
            .strictness(Strictness.LENIENT)
            .spyStatic(DesktopModeStatus::class.java)
            .startMocking()
    try {
      // No caching of flags
      whenever(DesktopModeStatus.isDesktopModeFlagEnabled()).thenReturn(false)
      assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
    } finally {
      mockitoSession.finishMocking()
    }
  }

  private fun setOverride(setting: Int?) {
    val contentResolver = mContext.contentResolver
    val key = Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES
    if (setting == null) {
      Settings.Global.putString(contentResolver, key, null)
    } else {
      Settings.Global.putInt(contentResolver, key, setting)
    }
  }

  private fun resetCache() {
    val cacheToggleOverride =
        DESKTOP_WINDOWING_MODE::class.java.getDeclaredField("cachedToggleOverride")
    cacheToggleOverride.isAccessible = true
    cacheToggleOverride.set(DESKTOP_WINDOWING_MODE, null)
  }
}
