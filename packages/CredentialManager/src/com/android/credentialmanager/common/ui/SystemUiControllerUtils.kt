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

package com.android.credentialmanager.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.android.compose.SystemUiController
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.credentialmanager.common.material.ModalBottomSheetDefaults

@Composable
fun setTransparentSystemBarsColor(sysUiController: SystemUiController) {
    sysUiController.setSystemBarsColor(color = Color.Transparent, darkIcons = false)
}

@Composable
fun setBottomSheetSystemBarsColor(sysUiController: SystemUiController) {
    sysUiController.setStatusBarColor(
        color = ModalBottomSheetDefaults.scrimColor,
        darkIcons = false
    )
    sysUiController.setNavigationBarColor(
        color = LocalAndroidColorScheme.current.surfaceBright,
        darkIcons = false
    )
}