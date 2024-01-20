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
 *
 */

package com.android.systemui.compose

import android.content.Context
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A facade to interact with Compose, when it is available.
 *
 * You should access this facade by calling the static methods on
 * [com.android.systemui.compose.ComposeFacade] directly.
 */
interface BaseComposeFacade {
    /**
     * Whether Compose is currently available. This function should be checked before calling any
     * other functions on this facade.
     *
     * This value will never change at runtime.
     */
    fun isComposeAvailable(): Boolean

    /**
     * Return the [ComposeInitializer] to make Compose usable in windows outside normal activities.
     */
    fun composeInitializer(): ComposeInitializer

    /** Bind the content of [activity] to [viewModel]. */
    fun setPeopleSpaceActivityContent(
        activity: ComponentActivity,
        viewModel: PeopleViewModel,
        onResult: (PeopleViewModel.Result) -> Unit,
    )

    /** Bind the content of [activity] to [viewModel]. */
    fun setCommunalEditWidgetActivityContent(
        activity: ComponentActivity,
        viewModel: BaseCommunalViewModel,
        widgetConfigurator: WidgetConfigurator,
        onOpenWidgetPicker: () -> Unit,
        onEditDone: () -> Unit,
    )

    /** Create a [View] to represent [viewModel] on screen. */
    fun createFooterActionsView(
        context: Context,
        viewModel: FooterActionsViewModel,
        qsVisibilityLifecycleOwner: LifecycleOwner,
    ): View

    /** Create a [View] to represent [viewModel] on screen. */
    fun createSceneContainerView(
        scope: CoroutineScope,
        context: Context,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        sceneByKey: Map<SceneKey, Scene>,
    ): View

    /** Create a [View] to represent [viewModel] on screen. */
    fun createCommunalView(
        context: Context,
        viewModel: BaseCommunalViewModel,
    ): View

    /** Create a [View] to represent the [BouncerViewModel]. */
    fun createBouncer(
        context: Context,
        viewModel: BouncerViewModel,
        dialogFactory: BouncerDialogFactory,
    ): View

    /** Creates a container that hosts the communal UI and handles gesture transitions. */
    fun createCommunalContainer(context: Context, viewModel: BaseCommunalViewModel): View
}
