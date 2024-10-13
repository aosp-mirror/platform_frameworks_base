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

package com.android.systemui.volume.dialog.ui.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import com.android.internal.jank.Cuj
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import javax.inject.Inject

/** Provides [Animator.AnimatorListener] to measure Volume CUJ Jank */
@VolumeDialogPluginScope
class JankListenerFactory
@Inject
constructor(private val interactionJankMonitor: InteractionJankMonitor) {

    fun show(view: View, timeout: Long) = getJunkListener(view, "show", timeout)

    fun update(view: View, timeout: Long) = getJunkListener(view, "update", timeout)

    fun dismiss(view: View, timeout: Long) = getJunkListener(view, "dismiss", timeout)

    private fun getJunkListener(
        view: View,
        type: String,
        timeout: Long,
    ): Animator.AnimatorListener {
        return object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                interactionJankMonitor.begin(
                    InteractionJankMonitor.Configuration.Builder.withView(
                            Cuj.CUJ_VOLUME_CONTROL,
                            view,
                        )
                        .setTag(type)
                        .setTimeout(timeout)
                )
            }

            override fun onAnimationEnd(animation: Animator) {
                interactionJankMonitor.end(Cuj.CUJ_VOLUME_CONTROL)
            }

            override fun onAnimationCancel(animation: Animator) {
                interactionJankMonitor.cancel(Cuj.CUJ_VOLUME_CONTROL)
            }
        }
    }
}
