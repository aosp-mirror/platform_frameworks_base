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

package com.android.systemui.qs.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.map

/** Models UI state and handles user input for the quick settings scene. */
@SysUISingleton
class QuickSettingsSceneViewModel
@Inject
constructor(
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val qsSceneAdapter: QSSceneAdapter,
    val notifications: NotificationsPlaceholderViewModel,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
) {
    val destinationScenes =
        qsSceneAdapter.isCustomizing.map { customizing ->
            if (customizing) {
                mapOf<UserAction, SceneModel>(UserAction.Back to SceneModel(SceneKey.QuickSettings))
            } else {
                mapOf(
                    UserAction.Back to SceneModel(SceneKey.Shade),
                    UserAction.Swipe(Direction.UP) to SceneModel(SceneKey.Shade),
                )
            }
        }

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }
}
