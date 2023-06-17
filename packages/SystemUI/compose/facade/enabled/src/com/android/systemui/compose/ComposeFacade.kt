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

package com.android.systemui.compose

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.multishade.ui.composable.MultiShade
import com.android.systemui.multishade.ui.viewmodel.MultiShadeViewModel
import com.android.systemui.people.ui.compose.PeopleScreen
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.scene.ui.composable.SceneContainer
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.util.time.SystemClock

/** The Compose facade, when Compose is available. */
object ComposeFacade : BaseComposeFacade {
    override fun isComposeAvailable(): Boolean = true

    override fun composeInitializer(): ComposeInitializer = ComposeInitializerImpl

    override fun setPeopleSpaceActivityContent(
        activity: ComponentActivity,
        viewModel: PeopleViewModel,
        onResult: (PeopleViewModel.Result) -> Unit,
    ) {
        activity.setContent { PlatformTheme { PeopleScreen(viewModel, onResult) } }
    }

    override fun createFooterActionsView(
        context: Context,
        viewModel: FooterActionsViewModel,
        qsVisibilityLifecycleOwner: LifecycleOwner,
    ): View {
        return ComposeView(context).apply {
            setContent { PlatformTheme { FooterActions(viewModel, qsVisibilityLifecycleOwner) } }
        }
    }

    override fun createMultiShadeView(
        context: Context,
        viewModel: MultiShadeViewModel,
        clock: SystemClock,
    ): View {
        return ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    MultiShade(
                        viewModel = viewModel,
                        clock = clock,
                    )
                }
            }
        }
    }

    override fun createSceneContainerView(
        context: Context,
        viewModel: SceneContainerViewModel,
        sceneByKey: Map<SceneKey, Scene>,
    ): View {
        return ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    SceneContainer(
                        viewModel = viewModel,
                        sceneByKey =
                            sceneByKey.mapValues { (_, scene) -> scene as ComposableScene },
                    )
                }
            }
        }
    }
}
