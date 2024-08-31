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

package com.android.systemui.statusbar.notification.row.ui.view

import android.content.Context
import android.graphics.drawable.Icon
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.R
import com.android.internal.widget.NotificationExpandButton

class EnRouteView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val configTracker = ConfigurationTracker(resources)

    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var expandButton: NotificationExpandButton

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = requireViewById(R.id.icon)
        title = requireViewById(R.id.title)
        text = requireViewById(R.id.text)

        expandButton = requireViewById(R.id.expand_button)
        expandButton.setExpanded(false)
    }

    /** the resources configuration has changed such that the view needs to be reinflated */
    fun isReinflateNeeded(): Boolean = configTracker.hasUnhandledConfigChange()

    fun setIcon(icon: Icon?) {
        this.icon.setImageIcon(icon)
    }

    fun setTitle(title: CharSequence?) {
        this.title.text = title
    }

    fun setText(text: CharSequence?) {
        this.text.text = text
    }
}
