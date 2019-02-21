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
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.UserHandle
import android.provider.Settings
import android.util.IconDrawableFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.plugins.ActivityStarter
import java.util.concurrent.TimeUnit

class OngoingPrivacyDialog constructor(
    private val context: Context,
    private val dialogBuilder: PrivacyDialogBuilder
) {

    private val iconSize = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dialog_icon_size)
    private val iconColor = context.resources.getColor(
            com.android.internal.R.color.text_color_primary, context.theme)
    private val iconMargin = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dialog_icon_margin)
    private val iconFactory = IconDrawableFactory.newInstance(context, true)
    private var dismissDialog: (() -> Unit)? = null
    private val appsAndTypes = dialogBuilder.appsAndTypes
            .sortedWith(compareBy({ -it.second.size }, // Sort by number of AppOps
            { it.second.min() },
            { it.first }))

    fun createDialog(): Dialog {
        val builder = AlertDialog.Builder(context).apply {
            setPositiveButton(R.string.ongoing_privacy_dialog_ok, null)
            setNeutralButton(R.string.ongoing_privacy_dialog_open_settings,
                    object : DialogInterface.OnClickListener {
                        val intent = Intent(Settings.ACTION_PRIVACY_SETTINGS).putExtra(
                                Intent.EXTRA_DURATION_MILLIS, TimeUnit.MINUTES.toMillis(1))

                        @Suppress("DEPRECATION")
                        override fun onClick(dialog: DialogInterface?, which: Int) {
                            Dependency.get(ActivityStarter::class.java)
                                    .postStartActivityDismissingKeyguard(intent, 0)
                        }
                    })
        }
        builder.setView(getContentView())
        val dialog = builder.create()
        dismissDialog = dialog::dismiss
        return dialog
    }

    fun getContentView(): View {
        val layoutInflater = LayoutInflater.from(context)
        val contentView = layoutInflater.inflate(R.layout.ongoing_privacy_dialog_content, null)

        val title = contentView.findViewById(R.id.title) as TextView
        val appsList = contentView.findViewById(R.id.items_container) as LinearLayout

        title.setText(dialogBuilder.getDialogTitle())

        val numItems = appsAndTypes.size
        for (i in 0..(numItems - 1)) {
            val item = appsAndTypes[i]
            addAppItem(appsList, item.first, item.second, dialogBuilder.types.size > 1)
        }
        return contentView
    }

    @Suppress("DEPRECATION")
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

        val lp = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = iconMargin
        }

        app.icon.let {
            appIcon.setImageDrawable(iconFactory.getShadowedIcon(it))
        }

        appName.text = app.applicationName
        if (showIcons) {
            dialogBuilder.generateIconsForApp(types).forEachIndexed { index, it ->
                it.setBounds(0, 0, iconSize, iconSize)
                val image = ImageView(context).apply {
                    imageTintList = ColorStateList.valueOf(iconColor)
                    setImageDrawable(it)
                }
                image.contentDescription = types[index].getName(context)
                icons.addView(image, lp)
            }
            icons.visibility = View.VISIBLE
        } else {
            icons.visibility = View.GONE
        }
        try {
            // Check if package exists
            context.packageManager.getPackageInfo(app.packageName, 0)
            item.setOnClickListener(object : View.OnClickListener {
                val intent = Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS)
                        .putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)
                        .putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(app.uid))
                override fun onClick(v: View?) {
                    Dependency.get(ActivityStarter::class.java)
                            .postStartActivityDismissingKeyguard(intent, 0)
                    dismissDialog?.invoke()
                }
            })
        } catch (e: PackageManager.NameNotFoundException) {}

        itemList.addView(item)
    }
}
