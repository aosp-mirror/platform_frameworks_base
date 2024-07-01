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

package com.android.systemui.communal.ui.compose.section

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.ambient.statusbar.dagger.AmbientStatusBarComponent
import com.android.systemui.ambient.statusbar.ui.AmbientStatusBarView
import com.android.systemui.communal.ui.compose.Communal
import com.android.systemui.res.R
import javax.inject.Inject

class AmbientStatusBarSection
@Inject
constructor(
    private val factory: AmbientStatusBarComponent.Factory,
) {
    @Composable
    fun SceneScope.AmbientStatusBar(modifier: Modifier = Modifier) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context)
                        .inflate(
                            /* resource = */ R.layout.ambient_status_bar_view,
                            /* root = */ FrameLayout(context),
                            /* attachToRoot = */ false,
                        ) as AmbientStatusBarView)
                    .apply {
                        visibility = View.VISIBLE
                        factory.create(this).getController().apply { init() }
                    }
            },
            modifier = modifier.element(Communal.Elements.StatusBar)
        )
    }
}
