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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.SwipeUpAnywhereGestureHandler
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.gesture.TapGestureDetector
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Provides dependencies for the AlternateBouncerViewBinder. */
@ExperimentalCoroutinesApi
class AlternateBouncerDependencies
@Inject
constructor(
    val viewModel: AlternateBouncerViewModel,
    val falsingManager: FalsingManager,
    val swipeUpAnywhereGestureHandler: SwipeUpAnywhereGestureHandler,
    val tapGestureDetector: TapGestureDetector,
    val udfpsIconViewModel: AlternateBouncerUdfpsIconViewModel,
    val udfpsAccessibilityOverlayViewModel:
        Lazy<AlternateBouncerUdfpsAccessibilityOverlayViewModel>,
    val messageAreaViewModel: AlternateBouncerMessageAreaViewModel,
)
