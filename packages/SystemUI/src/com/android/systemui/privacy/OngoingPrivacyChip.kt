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

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.graphics.Color
import android.os.UserHandle
import android.os.UserManager
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController

class OngoingPrivacyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttrs, defStyleRes) {

    companion object {
        val OPS = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
    }

    private lateinit var appName: TextView
    private lateinit var iconsContainer: LinearLayout
    private var privacyList = emptyList<PrivacyItem>()
    private val appOpsController = Dependency.get(AppOpsController::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val currentUser = ActivityManager.getCurrentUser()
    private val currentUserIds = userManager.getProfiles(currentUser).map { it.id }
    private var listening = false

    var builder = PrivacyDialogBuilder(context, privacyList)

    private val callback = object : AppOpsController.Callback {
        override fun onActiveStateChanged(
            code: Int,
            uid: Int,
            packageName: String,
            active: Boolean
        ) {
            val userId = UserHandle.getUserId(uid)
            if (userId in currentUserIds) {
                updatePrivacyList()
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        appName = findViewById(R.id.app_name)
        iconsContainer = findViewById(R.id.icons_container)
    }

    fun setListening(listen: Boolean) {
        if (listening == listen) return
        listening = listen
        if (listening) {
            appOpsController.addCallback(OPS, callback)
            updatePrivacyList()
        } else {
            appOpsController.removeCallback(OPS, callback)
        }
    }

    private fun updatePrivacyList() {
        privacyList = currentUserIds.flatMap { appOpsController.getActiveAppOpsForUser(it) }
                .mapNotNull { toPrivacyItem(it) }
        builder = PrivacyDialogBuilder(context, privacyList)
        updateView()
    }

    private fun toPrivacyItem(appOpItem: AppOpItem): PrivacyItem? {
        val type: PrivacyType = when (appOpItem.code) {
            AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
            AppOpsManager.OP_COARSE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
            else -> return null
        }
        val app = PrivacyApplication(appOpItem.packageName, context)
        return PrivacyItem(type, app, appOpItem.timeStarted)
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
            visibility = GONE
            return
        } else {
            generateContentDescription()
            visibility = VISIBLE
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