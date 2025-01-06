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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.shade.ShadeFoldAnimator
import javax.inject.Inject

@SysUISingleton
class ToAodFoldTransitionInteractor
@Inject
constructor(private val keyguardClockInteractor: KeyguardClockInteractor) {
    private var parentAnimator: NotificationPanelViewController.ShadeFoldAnimatorImpl? = null

    // TODO(b/331770313): Migrate to PowerInteractor; Deprecate ShadeFoldAnimator again
    val foldAnimator =
        object : ShadeFoldAnimator {

            override fun prepareFoldToAodAnimation() {
                parentAnimator?.prepareFoldToAodAnimation()
            }

            override fun startFoldToAodAnimation(
                startAction: Runnable,
                endAction: Runnable,
                cancelAction: Runnable,
            ) {
                parentAnimator?.let {
                    it.buildViewAnimator(startAction, endAction, cancelAction)
                        .setUpdateListener {
                            keyguardClockInteractor.animateFoldToAod(it.animatedFraction)
                        }
                        .start()
                }
            }

            override fun cancelFoldToAodAnimation() {
                parentAnimator?.cancelFoldToAodAnimation()
            }
        }

    fun initialize(parentAnimator: ShadeFoldAnimator) {
        this.parentAnimator =
            parentAnimator as? NotificationPanelViewController.ShadeFoldAnimatorImpl?
    }

    companion object {
        private val TAG = ToAodFoldTransitionInteractor::class.simpleName!!
    }
}
