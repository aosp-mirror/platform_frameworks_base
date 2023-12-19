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

@file:JvmName("NotificationContentDescription")

package com.android.systemui.statusbar.notification

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.text.TextUtils
import android.util.Log
import androidx.annotation.MainThread
import com.android.systemui.res.R

/**
 * Returns accessibility content description for a given notification.
 *
 * NOTE: This is a relatively slow call.
 */
@MainThread
fun contentDescForNotification(c: Context, n: Notification?): CharSequence {
    var appName = ""
    try {
        val builder = Notification.Builder.recoverBuilder(c, n)
        appName = builder.loadHeaderAppName()
    } catch (e: RuntimeException) {
        Log.e("ContentDescription", "Unable to recover builder", e)
        // Trying to get the app name from the app info instead.
        val appInfo =
            n?.extras?.getParcelable(
                Notification.EXTRA_BUILDER_APPLICATION_INFO,
                ApplicationInfo::class.java
            )
        if (appInfo != null) {
            appName = appInfo.loadLabel(c.packageManager).toString()
        }
    }
    val title = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)
    val text = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)
    val ticker = n?.tickerText

    // Some apps just put the app name into the title
    val titleOrText = if (TextUtils.equals(title, appName)) text else title
    val desc =
        if (!TextUtils.isEmpty(titleOrText)) titleOrText
        else if (!TextUtils.isEmpty(ticker)) ticker else ""
    return c.getString(R.string.accessibility_desc_notification_icon, appName, desc)
}
