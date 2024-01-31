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

import android.app.Dialog
import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.android.compose.theme.PlatformTheme
import com.android.compose.ui.platform.DensityAwareComposeView
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.composable.BouncerContent
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.DisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.DisplayCutoutProvider
import com.android.systemui.communal.ui.compose.CommunalContainer
import com.android.systemui.communal.ui.compose.CommunalHub
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.keyboard.stickykeys.ui.view.StickyKeysIndicator
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel
import com.android.systemui.keyguard.shared.model.LockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.composable.LockscreenContent
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.people.ui.compose.PeopleScreen
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.scene.ui.composable.SceneContainer
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.volume.panel.ui.composable.VolumePanelRoot
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    override fun setCommunalEditWidgetActivityContent(
        activity: ComponentActivity,
        viewModel: BaseCommunalViewModel,
        widgetConfigurator: WidgetConfigurator,
        onOpenWidgetPicker: () -> Unit,
        onEditDone: () -> Unit,
    ) {
        activity.setContent {
            PlatformTheme {
                CommunalHub(
                    viewModel = viewModel,
                    onOpenWidgetPicker = onOpenWidgetPicker,
                    widgetConfigurator = widgetConfigurator,
                    onEditDone = onEditDone,
                )
            }
        }
    }

    override fun setVolumePanelActivityContent(
        activity: ComponentActivity,
        viewModel: VolumePanelViewModel,
        onDismissAnimationFinished: () -> Unit,
    ) {
        activity.setContent {
            VolumePanelRoot(
                viewModel = viewModel,
                onDismissAnimationFinished = onDismissAnimationFinished,
            )
        }
    }

    override fun createFooterActionsView(
        context: Context,
        viewModel: FooterActionsViewModel,
        qsVisibilityLifecycleOwner: LifecycleOwner,
    ): View {
        return DensityAwareComposeView(context).apply {
            setContent { PlatformTheme { FooterActions(viewModel, qsVisibilityLifecycleOwner) } }
        }
    }

    override fun createSceneContainerView(
        scope: CoroutineScope,
        context: Context,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        sceneByKey: Map<SceneKey, Scene>,
    ): View {
        return ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    DisplayCutoutProvider(
                        displayCutout = displayCutoutFromWindowInsets(scope, context, windowInsets)
                    ) {
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

    override fun createStickyKeysDialog(
        dialogFactory: SystemUIDialogFactory,
        viewModel: StickyKeysIndicatorViewModel
    ): Dialog {
        return dialogFactory.create { StickyKeysIndicator(viewModel) }
    }

    override fun createCommunalView(
        context: Context,
        viewModel: BaseCommunalViewModel,
    ): View {
        return ComposeView(context).apply {
            setContent { PlatformTheme { CommunalHub(viewModel = viewModel) } }
        }
    }

    override fun createCommunalContainer(context: Context, viewModel: BaseCommunalViewModel): View {
        return ComposeView(context).apply {
            setContent { PlatformTheme { CommunalContainer(viewModel = viewModel) } }
        }
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun displayCutoutFromWindowInsets(
        scope: CoroutineScope,
        context: Context,
        windowInsets: StateFlow<WindowInsets?>,
    ): StateFlow<DisplayCutout> =
        windowInsets
            .map {
                val boundingRect = it?.displayCutout?.boundingRectTop
                val width = boundingRect?.let { boundingRect.right - boundingRect.left } ?: 0
                val left = boundingRect?.left?.toDp(context) ?: 0.dp
                val top = boundingRect?.top?.toDp(context) ?: 0.dp
                val right = boundingRect?.right?.toDp(context) ?: 0.dp
                val bottom = boundingRect?.bottom?.toDp(context) ?: 0.dp
                val location =
                    when {
                        width <= 0f -> CutoutLocation.NONE
                        left <= 0.dp -> CutoutLocation.LEFT
                        right >= getDisplayWidth(context) -> CutoutLocation.RIGHT
                        else -> CutoutLocation.CENTER
                    }
                DisplayCutout(
                    left,
                    top,
                    right,
                    bottom,
                    location,
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DisplayCutout())

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun getDisplayWidth(context: Context): Dp {
        val point = Point()
        checkNotNull(context.display).getRealSize(point)
        return point.x.dp
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun Int.toDp(context: Context): Dp {
        return (this.toFloat() / context.resources.displayMetrics.density).dp
    }

    override fun createBouncer(
        context: Context,
        viewModel: BouncerViewModel,
        dialogFactory: BouncerDialogFactory,
    ): View {
        return ComposeView(context).apply {
            setContent { PlatformTheme { BouncerContent(viewModel, dialogFactory) } }
        }
    }

    override fun createLockscreen(
        context: Context,
        viewModel: LockscreenContentViewModel,
        blueprints: Set<@JvmSuppressWildcards LockscreenSceneBlueprint>,
    ): View {
        val sceneBlueprints =
            blueprints.mapNotNull { it as? ComposableLockscreenSceneBlueprint }.toSet()
        return ComposeView(context).apply {
            setContent {
                LockscreenContent(viewModel = viewModel, blueprints = sceneBlueprints)
                    .Content(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
