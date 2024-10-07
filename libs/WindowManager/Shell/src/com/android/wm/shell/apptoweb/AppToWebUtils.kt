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

@file:JvmName("AppToWebUtils")

package com.android.wm.shell.apptoweb

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.net.Uri

private val GenericBrowserIntent = Intent()
    .setAction(Intent.ACTION_VIEW)
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .setData(Uri.parse("http:"))

/**
 * Returns a boolean indicating whether a given package is a browser app.
 */
fun isBrowserApp(context: Context, packageName: String, userId: Int): Boolean {
    GenericBrowserIntent.setPackage(packageName)
    val list = context.packageManager.queryIntentActivitiesAsUser(
        GenericBrowserIntent, PackageManager.MATCH_ALL, userId
    )

    list.forEach {
        if (it.activityInfo != null && it.handleAllWebDataURI) {
            return true
        }
    }
    return false
}

/**
 * Returns intent if there is a browser application available to handle the uri. Otherwise, returns
 * null.
 */
fun getBrowserIntent(uri: Uri, packageManager: PackageManager): Intent? {
    val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        .setData(uri)
        .addFlags(FLAG_ACTIVITY_NEW_TASK)
    // If there is no browser application available to handle intent, return null
    val component = intent.resolveActivity(packageManager) ?: return null
    intent.setComponent(component)
    return intent
}