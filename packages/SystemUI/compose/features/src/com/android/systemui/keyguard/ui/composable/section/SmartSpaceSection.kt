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

package com.android.systemui.keyguard.ui.composable.section

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import javax.inject.Inject

class SmartSpaceSection @Inject constructor() {
    @Composable
    fun SceneScope.SmartSpace(modifier: Modifier = Modifier) {
        MovableElement(key = SmartSpaceElementKey, modifier = modifier) {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color.Cyan),
            ) {
                Text(
                    text = "TODO(b/316211368): Smart space",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private val SmartSpaceElementKey = ElementKey("SmartSpace")
