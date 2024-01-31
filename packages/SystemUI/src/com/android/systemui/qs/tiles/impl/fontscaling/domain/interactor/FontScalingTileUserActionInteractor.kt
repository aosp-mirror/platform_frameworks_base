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

package com.android.systemui.qs.tiles.impl.fontscaling.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.accessibility.fontscaling.FontScalingDialogDelegate
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.fontscaling.domain.model.FontScalingTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles font scaling tile clicks. */
class FontScalingTileUserActionInteractor
@Inject
constructor(
    @Main private val coroutineContext: CoroutineContext,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val fontScalingDialogDelegateProvider: Provider<FontScalingDialogDelegate>,
    private val keyguardStateController: KeyguardStateController,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val activityStarter: ActivityStarter,
) : QSTileUserActionInteractor<FontScalingTileModel> {

    override suspend fun handleInput(input: QSTileInput<FontScalingTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    // We animate from the touched view only if we are not on the keyguard
                    val animateFromView: Boolean =
                        action.view != null && !keyguardStateController.isShowing
                    val runnable = Runnable {
                        val dialog: SystemUIDialog =
                            fontScalingDialogDelegateProvider.get().createDialog()
                        if (animateFromView) {
                            dialogLaunchAnimator.showFromView(
                                dialog,
                                action.view!!,
                                DialogCuj(
                                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                    INTERACTION_JANK_TAG
                                )
                            )
                        } else {
                            dialog.show()
                        }
                    }

                    withContext(coroutineContext) {
                        activityStarter.executeRunnableDismissingKeyguard(
                            runnable,
                            /* cancelAction= */ null,
                            /* dismissShade= */ true,
                            /* afterKeyguardGone= */ true,
                            /* deferred= */ false
                        )
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.view,
                        Intent(Settings.ACTION_TEXT_READING_SETTINGS)
                    )
                }
            }
        }
    companion object {
        private const val INTERACTION_JANK_TAG = "font_scaling"
    }
}
