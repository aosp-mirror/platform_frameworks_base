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

package com.android.systemui.keyguard.ui.composable.section

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.height
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeViewStateProvider
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.util.Utils
import dagger.Lazy
import javax.inject.Inject

class StatusBarSection
@Inject
constructor(
    private val componentFactory: KeyguardStatusBarViewComponent.Factory,
    private val notificationPanelView: Lazy<NotificationPanelView>,
) {
    @Composable
    fun SceneScope.StatusBar(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val viewDisplayCutout = LocalDisplayCutout.current.viewDisplayCutoutKeyguardStatusBarView
        @SuppressLint("InflateParams")
        val view =
            remember(context) {
                (LayoutInflater.from(context)
                        .inflate(
                            R.layout.keyguard_status_bar,
                            null,
                            false,
                        ) as KeyguardStatusBarView)
                    .also {
                        it.layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    }
            }
        val viewController =
            remember(view) {
                val provider =
                    object : ShadeViewStateProvider {
                        override val lockscreenShadeDragProgress: Float = 0f
                        override val panelViewExpandedHeight: Float = 0f

                        override fun shouldHeadsUpBeVisible(): Boolean {
                            return false
                        }
                    }

                componentFactory.build(view, provider).keyguardStatusBarViewController
            }

        MovableElement(
            key = StatusBarElementKey,
            modifier = modifier,
        ) {
            content {
                AndroidView(
                    factory = {
                        notificationPanelView.get().findViewById<View>(R.id.keyguard_header)?.let {
                            (it.parent as ViewGroup).removeView(it)
                        }

                        viewController.init()
                        view
                    },
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height {
                            Utils.getStatusBarHeaderHeightKeyguard(context)
                        },
                    update = { viewController.setDisplayCutout(viewDisplayCutout) }
                )
            }
        }
    }
}

private val StatusBarElementKey = ElementKey("StatusBar")
