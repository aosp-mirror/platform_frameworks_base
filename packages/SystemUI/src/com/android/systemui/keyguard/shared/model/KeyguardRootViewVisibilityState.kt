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
 *
 */

package com.android.systemui.keyguard.shared.model

/**
 * Provides a stateful representation of the visibility of the KeyguardRootView
 *
 * @param statusBarState State of the status bar represented by [StatusBarState]
 * @param goingToFullShade Whether status bar is going to full shade
 * @param occlusionTransitionRunning Whether the occlusion transition is running in this instant
 */
data class KeyguardRootViewVisibilityState(
    val statusBarState: Int,
    val goingToFullShade: Boolean,
    val occlusionTransitionRunning: Boolean,
)
