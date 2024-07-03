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
import com.android.wm.shell.shared.DesktopModeStatus

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
  DESKTOP_WINDOWING_MODE(DesktopModeStatus::isDesktopModeFlagEnabled, true);

  private val TAG = "DesktopModeFlags"

  // Cache for toggle override, which is initialized once on its first access. It needs to be refreshed
  // only on reboots as overridden state takes effect on reboots.
  private var cachedToggleOverride: ToggleOverride? = null

  /**
   * Determines state of flag based on the actual flag and desktop mode developer option
   * overrides.
   *
   * Note, this method makes sure that a constant developer toggle overrides is read until reboot.
   */
  fun isEnabled(context: Context): Boolean =
      if (!Flags.showDesktopWindowingDevOption() ||
          !shouldOverrideByDevOption ||
          context.contentResolver == null) {
        flagFunction()
      } else {
        when (getToggleOverride(context)) {
          ToggleOverride.OVERRIDE_UNSET -> flagFunction()
          ToggleOverride.OVERRIDE_OFF -> false
          ToggleOverride.OVERRIDE_ON -> true
        }
      }

  private fun getToggleOverride(context: Context): ToggleOverride {
    val override = cachedToggleOverride ?: run {
      // Cache toggle override the first time we encounter context. Override does not change
      // with context, as context is just used to fetch a system property.

      // TODO(b/348193756): Cache a persistent value for Settings.Global until reboot. Current
      //  cache will change with process restart.
      val toggleOverride =
          Settings.Global.getInt(
              context.contentResolver,
              Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
              ToggleOverride.OVERRIDE_UNSET.setting)

      val newOverride =
          settingToToggleOverrideMap[toggleOverride]
              ?: run {
                Log.w(TAG, "Unknown toggleOverride $toggleOverride")
                ToggleOverride.OVERRIDE_UNSET
              }
      cachedToggleOverride = newOverride
      Log.d(TAG, "Toggle override initialized to: $newOverride")
      newOverride
    }

    return override
  }

  // TODO(b/348193756): Share ToggleOverride enum with Settings
  // 'DesktopModePreferenceController'
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

  private val settingToToggleOverrideMap = ToggleOverride.entries.associateBy { it.setting }
}
