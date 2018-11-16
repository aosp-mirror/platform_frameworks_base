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
import android.content.Intent
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
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

    val iconSize = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dialog_icon_height)
    val iconColor = context.resources.getColor(
            com.android.internal.R.color.text_color_primary, context.theme)
    companion object {
        private const val MAX_ITEMS = 10
    }

    fun createDialog(): Dialog {
        val builder = AlertDialog.Builder(context).apply {
            setNegativeButton(R.string.ongoing_privacy_dialog_cancel, null)
            setPositiveButton(R.string.ongoing_privacy_dialog_open_settings,
                    object : DialogInterface.OnClickListener {
                        val intent = Intent(Intent.ACTION_REVIEW_PERMISSION_USAGE)

                        override fun onClick(dialog: DialogInterface?, which: Int) {
                            Dependency.get(ActivityStarter::class.java).startActivity(intent, false)
                        }
                    })
        }
        builder.setView(getContentView())
        return builder.create()
    }

    fun getContentView(): View {
        val layoutInflater = LayoutInflater.from(context)
        val contentView = layoutInflater.inflate(R.layout.ongoing_privacy_dialog_content, null)

        val title = contentView.findViewById(R.id.title) as TextView
        val appsList = contentView.findViewById(R.id.items_container) as LinearLayout

        title.setText(dialogBuilder.getDialogTitle())

        val numItems = dialogBuilder.appsAndTypes.size
        for (i in 0..(numItems - 1)) {
            if (i >= MAX_ITEMS) break
            val item = dialogBuilder.appsAndTypes[i]
            addAppItem(appsList, item.first, item.second, dialogBuilder.types.size > 1)
        }

        if (numItems > MAX_ITEMS) {
            val overflow = contentView.findViewById(R.id.overflow) as LinearLayout
            overflow.visibility = View.VISIBLE
            val overflowText = overflow.findViewById(R.id.app_name) as TextView
            overflowText.text = context.resources.getQuantityString(
                    R.plurals.ongoing_privacy_dialog_overflow_text,
                    numItems - MAX_ITEMS,
                    numItems - MAX_ITEMS
            )
            val overflowPlus = overflow.findViewById(R.id.app_icon) as ImageView
            overflowPlus.apply {
                imageTintList = ColorStateList.valueOf(iconColor)
                setImageDrawable(context.getDrawable(R.drawable.plus))
            }
        }

        return contentView
    }

    private fun addAppItem(
        itemList: LinearLayout,
        app: PrivacyApplication,
        types: List<PrivacyType>,
        showIcons: Boolean = true
    ) {
        val layoutInflater = LayoutInflater.from(context)
        val item = layoutInflater.inflate(R.layout.ongoing_privacy_dialog_item, itemList, false)
        val appIcon = item.findViewById(R.id.app_icon) as ImageView
        val appName = item.findViewById(R.id.app_name) as TextView
        val icons = item.findViewById(R.id.icons) as LinearLayout

        app.icon?.let {
            appIcon.setImageDrawable(it)
        }

        appName.text = app.applicationName
        if (showIcons) {
            dialogBuilder.generateIconsForApp(types).forEach {
                it.setBounds(0, 0, iconSize, iconSize)
                val image = ImageView(context).apply {
                    imageTintList = ColorStateList.valueOf(iconColor)
                    setImageDrawable(it)
                }
                icons.addView(image, iconSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            icons.visibility = View.VISIBLE
        } else {
            icons.visibility = View.GONE
        }
        itemList.addView(item)
    }
}
