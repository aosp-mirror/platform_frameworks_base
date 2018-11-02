/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import com.android.systemui.Dependency
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController

class PrivacyItemController(val context: Context, val callback: Callback) {

    companion object {
        val OPS = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
    }

    private var privacyList = emptyList<PrivacyItem>()
    private val appOpsController = Dependency.get(AppOpsController::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val currentUser = ActivityManager.getCurrentUser()
    private val currentUserIds = userManager.getProfiles(currentUser).map { it.id }
    private val bgHandler = Handler(Dependency.get(Dependency.BG_LOOPER))
    private val uiHandler = Dependency.get(Dependency.MAIN_HANDLER)
    private val notifyChanges = Runnable {
        callback.privacyChanged(privacyList)
    }
    private val updateListAndNotifyChanges = Runnable {
        updatePrivacyList()
        uiHandler.post(notifyChanges)
    }

    private var listening = false

    private val cb = object : AppOpsController.Callback {
        override fun onActiveStateChanged(
            code: Int,
            uid: Int,
            packageName: String,
            active: Boolean
        ) {
            val userId = UserHandle.getUserId(uid)
            if (userId in currentUserIds) {
                update()
            }
        }
    }

    private fun update() {
        bgHandler.post(updateListAndNotifyChanges)
    }

    fun setListening(listen: Boolean) {
        if (listening == listen) return
        listening = listen
        if (listening) {
            appOpsController.addCallback(OPS, cb)
            update()
        } else {
            appOpsController.removeCallback(OPS, cb)
        }
    }

    private fun updatePrivacyList() {
        privacyList = currentUserIds.flatMap { appOpsController.getActiveAppOpsForUser(it) }
                .mapNotNull { toPrivacyItem(it) }
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
        return PrivacyItem(type, app)
    }

    // Used by containing class to get notified of changes
    interface Callback {
        fun privacyChanged(privacyItems: List<PrivacyItem>)
    }
}