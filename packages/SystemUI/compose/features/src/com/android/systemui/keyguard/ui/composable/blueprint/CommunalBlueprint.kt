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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.ui.composable.LockscreenLongPress
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject

/** Renders the lockscreen scene when showing the communal glanceable hub. */
class CommunalBlueprint
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
) : ComposableLockscreenSceneBlueprint {

    override val id: String = "communal"

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        LockscreenLongPress(
            viewModel = viewModel.longPress,
            modifier = modifier,
        ) { _ ->
            Box(modifier.background(Color.Black)) {
                Text(
                    text = "TODO(b/316211368): communal blueprint",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Module
interface CommunalBlueprintModule {
    @Binds @IntoSet fun blueprint(blueprint: CommunalBlueprint): ComposableLockscreenSceneBlueprint
}
