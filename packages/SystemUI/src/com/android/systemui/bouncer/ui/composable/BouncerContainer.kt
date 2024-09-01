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

package com.android.systemui.bouncer.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.theme.PlatformTheme
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerSceneContentViewModel
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel

/** Container that includes the compose bouncer and is meant to be included in legacy keyguard. */
@Composable
fun BouncerContainer(
    viewModelFactory: BouncerSceneContentViewModel.Factory,
    dialogFactory: BouncerDialogFactory,
) {
    PlatformTheme {
        val backgroundColor = MaterialTheme.colorScheme.surface

        val bouncerViewModel = rememberViewModel("BouncerContainer") { viewModelFactory.create() }
        Box {
            Canvas(Modifier.fillMaxSize()) { drawRect(color = backgroundColor) }

            // Separate the bouncer content into a reusable composable that
            // doesn't have any SceneScope
            // dependencies
            BouncerContent(
                bouncerViewModel,
                dialogFactory,
                Modifier.sysuiResTag(Bouncer.TestTags.Root).fillMaxSize()
            )
        }
    }
}
