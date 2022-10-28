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
package com.android.systemui.shared.rotation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.WindowInsetsController
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class RotationButtonControllerTest : SysuiTestCase() {

  private lateinit var mController: RotationButtonController

  @Before
  fun setUp() {
    mController = RotationButtonController(
      mContext,
      /* lightIconColor = */ 0,
      /* darkIconColor = */ 0,
      /* iconCcwStart0ResId = */ 0,
      /* iconCcwStart90ResId = */ 0,
      /* iconCwStart0ResId = */ 0,
      /* iconCwStart90ResId = */ 0
    ) { 0 }
  }

  @Test
  fun ifGestural_showRotationSuggestion() {
    mController.onNavigationBarWindowVisibilityChange( /* showing = */ false)
    mController.onBehaviorChanged(Display.DEFAULT_DISPLAY,
                                  WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
    mController.onNavigationModeChanged(WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON)
    mController.onTaskbarStateChange( /* visible = */ false, /* stashed = */ false)
    assertThat(mController.canShowRotationButton()).isFalse()

    mController.onNavigationModeChanged(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL)

    assertThat(mController.canShowRotationButton()).isTrue()
  }

  @Test
  fun ifTaskbarVisible_showRotationSuggestion() {
    mController.onNavigationBarWindowVisibilityChange( /* showing = */ false)
    mController.onBehaviorChanged(Display.DEFAULT_DISPLAY,
                                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
    mController.onNavigationModeChanged(WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON)
    mController.onTaskbarStateChange( /* visible = */ false, /* stashed = */ false)
    assertThat(mController.canShowRotationButton()).isFalse()

    mController.onTaskbarStateChange( /* visible = */ true, /* stashed = */ false)

    assertThat(mController.canShowRotationButton()).isTrue()
  }
}
