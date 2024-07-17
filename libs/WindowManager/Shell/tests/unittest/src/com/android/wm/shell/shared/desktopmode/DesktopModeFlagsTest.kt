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

import android.os.SystemProperties
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
import com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.Companion.convertToToggleOverrideWithFallback
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.DESKTOP_WINDOWING_MODE
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_OFF
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_ON
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.ToggleOverride.OVERRIDE_UNSET
import com.android.wm.shell.shared.desktopmode.DesktopModeFlags.WALLPAPER_ACTIVITY
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

/**
 * Test class for [DesktopModeFlags]
 *
 * Usage: atest WMShellUnitTests:DesktopModeFlagsTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeFlagsTest : ShellTestCase() {

  @Rule(order = 1)
  @JvmField
  val setFlagsRule = SetFlagsRule()

  @Rule(order = 2)
  @JvmField
  val extendedMockitoRule: ExtendedMockitoRule =
    ExtendedMockitoRule.Builder(this).mockStatic(SystemProperties::class.java).build()

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
  fun isEnabled_overrideUnset_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_UNSET.setting)

    // For overridableFlag, for unset overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_overrideUnset_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_UNSET.setting)

    // For overridableFlag, for unset overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_noOverride_featureFlagOn_returnsTrue() {
    setOverride(null)

    // For overridableFlag, in absence of overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_noOverride_featureFlagOff_returnsFalse() {
    setOverride(null)

    // For overridableFlag, in absence of overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_unrecognizableOverride_featureFlagOn_returnsTrue() {
    setOverride(INVALID_TOGGLE_OVERRIDE_SETTING)

    // For overridableFlag, for recognizable overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_unrecognizableOverride_featureFlagOff_returnsFalse() {
    setOverride(INVALID_TOGGLE_OVERRIDE_SETTING)

    // For overridableFlag, for recognizable overrides, follow flag
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_overrideOff_featureFlagOn_returnsFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_overrideOn_featureFlagOff_returnsTrue() {
    setOverride(OVERRIDE_ON.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_overrideOffThenOn_featureFlagOn_returnsFalseAndFalse() {
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
  fun isEnabled_overrideOnThenOff_featureFlagOff_returnsTrueAndTrue() {
    setOverride(OVERRIDE_ON.setting)

    // For overridableFlag, follow override if they exist
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()

    setOverride(OVERRIDE_OFF.setting)

    // Keep overrides constant through the process
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_noSystemProperty_overrideOn_featureFlagOff_returnsTrueAndStoresPropertyOn() {
    setSystemProperty(INVALID_TOGGLE_OVERRIDE_SETTING)
    setOverride(OVERRIDE_ON.setting)

    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
    // Store System Property if not present
    verifySystemPropertySet(OVERRIDE_ON.setting)
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_noSystemProperty_overrideUnset_featureFlagOn_returnsTrueAndStoresPropertyUnset() {
    setSystemProperty(INVALID_TOGGLE_OVERRIDE_SETTING)
    setOverride(OVERRIDE_UNSET.setting)

    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
    // Store System Property if not present
    verifySystemPropertySet(OVERRIDE_UNSET.setting)
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_noSystemProperty_overrideUnset_featureFlagOff_returnsFalseAndStoresPropertyUnset() {
    setSystemProperty(INVALID_TOGGLE_OVERRIDE_SETTING)
    setOverride(OVERRIDE_UNSET.setting)

    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
    // Store System Property if not present
    verifySystemPropertySet(OVERRIDE_UNSET.setting)
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  @Suppress("ktlint:standard:max-line-length")
  fun isEnabled_systemPropertyInvalidInteger_overrideOff_featureFlagOn_returnsFalseAndStoresPropertyOff() {
    setSystemProperty(INVALID_TOGGLE_OVERRIDE_SETTING)
    setOverride(OVERRIDE_OFF.setting)

    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
    // Store System Property if currently invalid
    verifySystemPropertySet(OVERRIDE_OFF.setting)
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_systemPropertyOff_overrideOn_featureFlagOn_returnsFalseAndDoesNotUpdateProperty() {
    setSystemProperty(OVERRIDE_OFF.setting)
    setOverride(OVERRIDE_ON.setting)

    // Have a consistent override until reboot
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse()
    verifySystemPropertyNotUpdated()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_systemPropertyOn_overrideOff_featureFlagOff_returnsTrueAndDoesNotUpdateProperty() {
    setSystemProperty(OVERRIDE_ON.setting)
    setOverride(OVERRIDE_OFF.setting)

    // Have a consistent override until reboot
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
    verifySystemPropertyNotUpdated()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  @Suppress("ktlint:standard:max-line-length")
  fun isEnabled_systemPropertyUnset_overrideOff_featureFlagOn_returnsTrueAndDoesNotUpdateProperty() {
    setSystemProperty(OVERRIDE_UNSET.setting)
    setOverride(OVERRIDE_OFF.setting)

    // Have a consistent override until reboot
    assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue()
    verifySystemPropertyNotUpdated()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
      FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideUnset_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_UNSET.setting)

    // For unset overrides, follow flag
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideUnset_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_UNSET.setting)

    // For unset overrides, follow flag
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
      FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideOn_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_ON.setting)

    // When toggle override matches its default state (dw flag), don't override flags
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideOn_featureFlagOff_returnFalse() {
    setOverride(OVERRIDE_ON.setting)

    // When toggle override matches its default state (dw flag), don't override flags
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
      FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideOff_featureFlagOn_returnsFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // Follow override if they exist, and is not equal to default toggle state (dw flag)
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagEnabled_overrideOff_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // Follow override if they exist, and is not equal to default toggle state (dw flag)
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_dwFlagDisabled_overrideUnset_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_UNSET.setting)

    // For unset overrides, follow flag
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagDisabled_overrideUnset_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_UNSET.setting)

    // For unset overrides, follow flag
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_dwFlagDisabled_overrideOn_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_ON.setting)

    // Follow override if they exist, and is not equal to default toggle state (dw flag)
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagDisabled_overrideOn_featureFlagOff_returnTrue() {
    setOverride(OVERRIDE_ON.setting)

    // Follow override if they exist, and is not equal to default toggle state (dw flag)
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(
      FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
  fun isEnabled_dwFlagDisabled_overrideOff_featureFlagOn_returnsTrue() {
    setOverride(OVERRIDE_OFF.setting)

    // When toggle override matches its default state (dw flag), don't override flags
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue()
  }

  @Test
  @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
  @DisableFlags(
      FLAG_ENABLE_DESKTOP_WINDOWING_MODE, FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun isEnabled_dwFlagDisabled_overrideOff_featureFlagOff_returnsFalse() {
    setOverride(OVERRIDE_OFF.setting)

    // When toggle override matches its default state (dw flag), don't override flags
    assertThat(WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse()
  }

  @Test
  fun convertToToggleOverrideWithFallback_validInt_returnsToggleOverride() {
    assertThat(convertToToggleOverrideWithFallback(0, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_OFF)
    assertThat(convertToToggleOverrideWithFallback(1, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_ON)
    assertThat(convertToToggleOverrideWithFallback(-1, OVERRIDE_ON)).isEqualTo(OVERRIDE_UNSET)
  }

  @Test
  fun convertToToggleOverrideWithFallback_invalidInt_returnsFallback() {
    assertThat(convertToToggleOverrideWithFallback(2, OVERRIDE_ON)).isEqualTo(OVERRIDE_ON)
    assertThat(convertToToggleOverrideWithFallback(-2, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_UNSET)
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
    val cachedToggleOverride = DesktopModeFlags::class.java.getDeclaredField("cachedToggleOverride")
    cachedToggleOverride.isAccessible = true
    cachedToggleOverride.set(null, null)

    setSystemProperty(INVALID_TOGGLE_OVERRIDE_SETTING)
  }

  private fun setSystemProperty(systemProperty: Int) {
    ExtendedMockito.doReturn(systemProperty).`when` {
      SystemProperties.getInt(eq(SYSTEM_PROPERTY_OVERRIDE_KEY), any())
    }
  }

  private fun verifySystemPropertySet(systemProperty: Int) {
    ExtendedMockito.verify {
      SystemProperties.set(eq(SYSTEM_PROPERTY_OVERRIDE_KEY), eq(systemProperty.toString()))
    }
  }

  private fun verifySystemPropertyNotUpdated() {
    ExtendedMockito.verify(
        { SystemProperties.set(eq(SYSTEM_PROPERTY_OVERRIDE_KEY), any()) }, Mockito.never())
  }

  private companion object {
    const val SYSTEM_PROPERTY_OVERRIDE_KEY = "sys.wmshell.desktopmode.dev_toggle_override"

    const val INVALID_TOGGLE_OVERRIDE_SETTING = -2
  }
}
