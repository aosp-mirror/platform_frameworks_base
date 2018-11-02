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

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.plugins.ActivityStarter

class OngoingPrivacyDialog constructor(
    val context: Context,
    val dialogBuilder: PrivacyDialogBuilder
) {

    val iconHeight = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dialog_icon_height)
    val textMargin = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dialog_text_margin)
    val iconColor = context.resources.getColor(
            com.android.internal.R.color.text_color_primary, context.theme)

    fun createDialog(): Dialog {
        val builder = AlertDialog.Builder(context)
                .setNeutralButton(R.string.ongoing_privacy_dialog_open_settings, null)
        if (dialogBuilder.app != null) {
            builder.setPositiveButton(R.string.ongoing_privacy_dialog_open_app,
                    object : DialogInterface.OnClickListener {
                        val intent = context.packageManager
                                .getLaunchIntentForPackage(dialogBuilder.app.packageName)

                        override fun onClick(dialog: DialogInterface?, which: Int) {
                            Dependency.get(ActivityStarter::class.java).startActivity(intent, false)
                        }
                    })
            builder.setNegativeButton(R.string.ongoing_privacy_dialog_cancel, null)
        } else {
            builder.setPositiveButton(R.string.ongoing_privacy_dialog_okay, null)
        }
        builder.setView(getContentView())
        return builder.create()
    }

    fun getContentView(): View {
        val layoutInflater = LayoutInflater.from(context)
        val contentView = layoutInflater.inflate(R.layout.ongoing_privacy_dialog_content, null)

        val iconsContainer = contentView.findViewById(R.id.icons_container) as LinearLayout
        val textContainer = contentView.findViewById(R.id.text_container) as LinearLayout

        addIcons(dialogBuilder, iconsContainer)
        val lm = ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                ViewGroup.MarginLayoutParams.WRAP_CONTENT)
        lm.topMargin = textMargin
        val now = System.currentTimeMillis()
        dialogBuilder.generateText(now).forEach {
            val text = layoutInflater.inflate(R.layout.ongoing_privacy_text_item, null) as TextView
            text.setText(it)
            textContainer.addView(text, lm)
        }
        return contentView
    }

    private fun addIcons(dialogBuilder: PrivacyDialogBuilder, iconsContainer: LinearLayout) {

        fun LinearLayout.addIcon(icon: Drawable) {
            val image = ImageView(context).apply {
                setImageDrawable(icon.apply {
                    setBounds(0, 0, iconHeight, iconHeight)
                    maxHeight = this@addIcon.height
                })
                adjustViewBounds = true
            }
            addView(image, LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
        }

        dialogBuilder.generateIcons().forEach {
            it.mutate()
            it.setTint(iconColor)
            iconsContainer.addIcon(it)
        }
        dialogBuilder.app.let {
            it?.icon?.let { iconsContainer.addIcon(it) }
        }
    }
}
