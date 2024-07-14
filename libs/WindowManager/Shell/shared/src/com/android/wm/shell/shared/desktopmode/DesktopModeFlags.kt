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

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.window.flags.Flags

/*
 * A shared class to check desktop mode flags state.
 *
 * The class computes whether a Desktop Windowing flag should be enabled by using the aconfig flag
 * value and the developer option override state (if applicable).
 **/
enum class DesktopModeFlags(
    // Function called to obtain aconfig flag value.
    private val flagFunction: () -> Boolean,
    // Whether the flag state should be affected by developer option.
    private val shouldOverrideByDevOption: Boolean
) {
  // All desktop mode related flags will be added here
  DESKTOP_WINDOWING_MODE(Flags::enableDesktopWindowingMode, true),
  WALLPAPER_ACTIVITY(Flags::enableDesktopWindowingWallpaperActivity, true);

  /**
   * Determines state of flag based on the actual flag and desktop mode developer option overrides.
   *
   * Note, this method makes sure that a constant developer toggle overrides is read until reboot.
   */
  fun isEnabled(context: Context): Boolean =
      if (!Flags.showDesktopWindowingDevOption() ||
          !shouldOverrideByDevOption ||
          context.contentResolver == null) {
        flagFunction()
      } else {
        val shouldToggleBeEnabledByDefault = DesktopModeStatus.shouldDevOptionBeEnabledByDefault()
        when (getToggleOverride(context)) {
          ToggleOverride.OVERRIDE_UNSET -> flagFunction()
          // When toggle override matches its default state, don't override flags. This helps users
          // reset their feature overrides.
          ToggleOverride.OVERRIDE_OFF ->
              if (shouldToggleBeEnabledByDefault) false else flagFunction()
          ToggleOverride.OVERRIDE_ON -> if (shouldToggleBeEnabledByDefault) flagFunction() else true
        }
      }

  private fun getToggleOverride(context: Context): ToggleOverride {
    val override =
        cachedToggleOverride
            ?: run {
              val override = getToggleOverrideFromSystem(context)
              // Cache toggle override the first time we encounter context. Override does not change
              // with context, as context is just used to fetch System Property and Settings.Global
              cachedToggleOverride = override
              Log.d(TAG, "Toggle override initialized to: $override")
              override
            }

    return override
  }

  private fun getToggleOverrideFromSystem(context: Context): ToggleOverride {
    // A non-persistent System Property is used to store override to ensure it remains
    // constant till reboot.
    val overrideFromSystemProperties: ToggleOverride? =
        System.getProperty(SYSTEM_PROPERTY_OVERRIDE_KEY, null).convertToToggleOverride()
    return overrideFromSystemProperties
        ?: run {
          // Read Setting Global if System Property is not present (just after reboot)
          // or not valid (user manually changed the value)
          val overrideFromSettingsGlobal =
              convertToToggleOverrideWithFallback(
                  Settings.Global.getInt(
                      context.contentResolver,
                      Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                      ToggleOverride.OVERRIDE_UNSET.setting),
                  ToggleOverride.OVERRIDE_UNSET)
          // Initialize System Property
          System.setProperty(
              SYSTEM_PROPERTY_OVERRIDE_KEY, overrideFromSettingsGlobal.setting.toString())

          overrideFromSettingsGlobal
        }
  }

  /**
   * Override state of desktop mode developer option toggle.
   *
   * @property setting The integer value that is associated with the developer option toggle
   *   override
   */
  enum class ToggleOverride(val setting: Int) {
    /** No override is set. */
    OVERRIDE_UNSET(-1),
    /** Override to off. */
    OVERRIDE_OFF(0),
    /** Override to on. */
    OVERRIDE_ON(1)
  }

  private fun String?.convertToToggleOverride(): ToggleOverride? {
    val intValue = this?.toIntOrNull() ?: return null
    return settingToToggleOverrideMap[intValue]
        ?: run {
          Log.w(TAG, "Unknown toggleOverride int $intValue")
          null
        }
  }

  companion object {
    private const val TAG = "DesktopModeFlags"

    /**
     * Key for non-persistent System Property which is used to store desktop windowing developer
     * option overrides.
     */
    private const val SYSTEM_PROPERTY_OVERRIDE_KEY = "sys.wmshell.desktopmode.dev_toggle_override"

    /**
     * Local cache for toggle override, which is initialized once on its first access. It needs to
     * be refreshed only on reboots as overridden state takes effect on reboots.
     */
    private var cachedToggleOverride: ToggleOverride? = null

    private val settingToToggleOverrideMap = ToggleOverride.entries.associateBy { it.setting }

    @JvmStatic
    fun convertToToggleOverrideWithFallback(
        overrideInt: Int,
        fallbackOverride: ToggleOverride
    ): ToggleOverride {
      return settingToToggleOverrideMap[overrideInt]
          ?: run {
            Log.w(TAG, "Unknown toggleOverride int $overrideInt")
            fallbackOverride
          }
    }
  }
}
