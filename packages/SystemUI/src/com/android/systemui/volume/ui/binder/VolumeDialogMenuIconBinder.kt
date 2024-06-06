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

package com.android.systemui.volume.ui.binder

import android.animation.ValueAnimator
import android.graphics.drawable.Animatable2
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.volume.ui.viewmodel.VolumeMenuIconViewModel
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Binds volume dialog menu button icon. */
class VolumeDialogMenuIconBinder
@Inject
constructor(
    @Application private val coroutineScope: CoroutineScope,
    private val viewModel: VolumeMenuIconViewModel,
) {

    private var job: Job? = null

    fun bind(iconImageView: ImageView?) {
        job?.cancel()
        job =
            iconImageView?.let { imageView ->
                coroutineScope.launch {
                    viewModel.icon.collectLatest { icon ->
                        animate { CrossFadeHelper.fadeOut(imageView, it) }
                        IconViewBinder.bind(icon, imageView)
                        if (icon is Icon.Loaded && icon.drawable is Animatable2) {
                            icon.drawable.start()
                        }
                        animate { CrossFadeHelper.fadeIn(imageView, it) }
                    }
                }
            }
    }

    private suspend fun animate(update: (value: Float) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.start()
            anim.addUpdateListener { update(it.animatedValue as Float) }
            anim.doOnEnd { continuation.resume(Unit) }
            continuation.invokeOnCancellation {
                anim.removeAllListeners()
                anim.cancel()
            }
        }

    fun destroy() {
        job?.cancel()
        job = null
    }
}
