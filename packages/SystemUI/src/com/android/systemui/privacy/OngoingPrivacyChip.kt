/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.privacy

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R

class OngoingPrivacyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private lateinit var appName: TextView
    private lateinit var iconsContainer: LinearLayout
    var builder = PrivacyDialogBuilder(context, emptyList<PrivacyItem>())
    var privacyList = emptyList<PrivacyItem>()
        set(value) {
            field = value
            builder = PrivacyDialogBuilder(context, value)
            updateView()
        }

    override fun onFinishInflate() {
        super.onFinishInflate()

        appName = findViewById(R.id.app_name)
        iconsContainer = findViewById(R.id.icons_container)
    }

    // Should only be called if the builder icons or app changed
    private fun updateView() {
        fun setIcons(dialogBuilder: PrivacyDialogBuilder, iconsContainer: ViewGroup) {
            iconsContainer.removeAllViews()
            dialogBuilder.generateIcons().forEach {
                it.mutate()
                it.setTint(Color.WHITE)
                iconsContainer.addView(ImageView(context).apply {
                    setImageDrawable(it)
                    maxHeight = this@OngoingPrivacyChip.height
                })
            }
        }

        if (privacyList.isEmpty()) {
            return
        } else {
            generateContentDescription()
            setIcons(builder, iconsContainer)
            appName.visibility = GONE
            builder.app?.let {
                appName.apply {
                    setText(it.applicationName)
                    setTextColor(Color.WHITE)
                    visibility = VISIBLE
                }
            }
        }
        requestLayout()
    }

    private fun generateContentDescription() {
        val typesText = builder.generateTypesText()
        if (builder.app != null) {
            contentDescription = context.getString(R.string.ongoing_privacy_chip_content_single_app,
                    builder.app?.applicationName, typesText)
        } else {
            contentDescription = context.getString(
                    R.string.ongoing_privacy_chip_content_multiple_apps, typesText)
        }
    }
}