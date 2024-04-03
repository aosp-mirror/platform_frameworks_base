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

package com.android.systemui.shade

import com.android.systemui.statusbar.GestureRecorder
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.HeadsUpManager
import javax.inject.Inject

class ShadeSurfaceImpl @Inject constructor() : ShadeSurface, ShadeViewControllerEmptyImpl() {
    override fun initDependencies(
        centralSurfaces: CentralSurfaces,
        recorder: GestureRecorder,
        hideExpandedRunnable: Runnable,
        headsUpManager: HeadsUpManager
    ) {}

    override fun cancelPendingCollapse() {
        // Do nothing
    }

    override fun cancelAnimation() {
        // Do nothing
    }

    override fun fadeOut(startDelayMs: Long, durationMs: Long, endAction: Runnable) {
        // Do nothing
    }

    override fun setBouncerShowing(bouncerShowing: Boolean) {
        // Do nothing
    }

    override fun setTouchAndAnimationDisabled(disabled: Boolean) {
        // TODO(b/332732878): determine if still needed
    }

    override fun setWillPlayDelayedDozeAmountAnimation(willPlay: Boolean) {
        // TODO(b/322494538): determine if still needed
    }

    override fun setDozing(dozing: Boolean, animate: Boolean) {
        // Do nothing
    }

    override fun setImportantForAccessibility(mode: Int) {
        // Do nothing
    }

    override fun resetTranslation() {
        // Do nothing
    }

    override fun resetAlpha() {
        // Do nothing
    }

    override fun onScreenTurningOn() {
        // Do nothing
    }

    override fun onThemeChanged() {
        // Do nothing
    }

    override fun updateExpansionAndVisibility() {
        // Do nothing
    }

    override fun updateResources() {
        // Do nothing
    }
}
