/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import javax.inject.Inject

@SysUISingleton
class MediaContainerController @Inject constructor(
    private val layoutInflater: LayoutInflater
) : NodeController {

    override val nodeLabel = "MediaContainer"
    var mediaContainerView: MediaContainerView? = null
        private set

    fun reinflateView(parent: ViewGroup) {
        var oldPos = -1
        mediaContainerView?.let { _view ->
            _view.removeFromTransientContainer()
            if (_view.parent === parent) {
                oldPos = parent.indexOfChild(_view)
                parent.removeView(_view)
            }
        }
        val inflated = layoutInflater.inflate(
                R.layout.keyguard_media_container,
                parent,
                false /* attachToRoot */)
                as MediaContainerView
        if (oldPos != -1) {
            parent.addView(inflated, oldPos)
        }
        mediaContainerView = inflated
    }

    override val view: View
        get() = mediaContainerView!!
}