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

package com.android.systemui.privacy.logging

import android.permission.PermGroupUsage
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogMessage
import com.android.systemui.log.dagger.PrivacyLog
import com.android.systemui.privacy.PrivacyDialog
import com.android.systemui.privacy.PrivacyItem
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

private const val TAG = "PrivacyLog"
private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
class PrivacyLogger @Inject constructor(
    @PrivacyLog private val buffer: LogBuffer
) {

    fun logUpdatedItemFromAppOps(code: Int, uid: Int, packageName: String, active: Boolean) {
        log(LogLevel.INFO, {
            int1 = code
            int2 = uid
            str1 = packageName
            bool1 = active
        }, {
            "App Op: $int1 for $str1($int2), active=$bool1"
        })
    }

    fun logRetrievedPrivacyItemsList(list: List<PrivacyItem>) {
        log(LogLevel.INFO, {
            str1 = listToString(list)
        }, {
            "Retrieved list to process: $str1"
        })
    }

    fun logPrivacyItemsToHold(list: List<PrivacyItem>) {
        log(LogLevel.DEBUG, {
            str1 = listToString(list)
        }, {
            "Holding items: $str1"
        })
    }

    fun logPrivacyItemsUpdateScheduled(delay: Long) {
        log(LogLevel.INFO, {
            val scheduledFor = System.currentTimeMillis() + delay
            val formattedTimestamp = DATE_FORMAT.format(scheduledFor)
            str1 = formattedTimestamp
        }, {
            "Updating items scheduled for $str1"
        })
    }

    fun logCurrentProfilesChanged(profiles: List<Int>) {
        log(LogLevel.INFO, {
            str1 = profiles.toString()
        }, {
            "Profiles changed: $str1"
        })
    }

    fun logChipVisible(visible: Boolean) {
        log(LogLevel.INFO, {
            bool1 = visible
        }, {
            "Chip visible: $bool1"
        })
    }

    fun logStatusBarIconsVisible(
        showCamera: Boolean,
        showMicrophone: Boolean,
        showLocation: Boolean
    ) {
        log(LogLevel.INFO, {
            bool1 = showCamera
            bool2 = showMicrophone
            bool3 = showLocation
        }, {
            "Status bar icons visible: camera=$bool1, microphone=$bool2, location=$bool3"
        })
    }

    fun logUnfilteredPermGroupUsage(contents: List<PermGroupUsage>) {
        log(LogLevel.DEBUG, {
            str1 = contents.toString()
        }, {
            "Perm group usage: $str1"
        })
    }

    fun logShowDialogContents(contents: List<PrivacyDialog.PrivacyElement>) {
        log(LogLevel.INFO, {
            str1 = contents.toString()
        }, {
            "Privacy dialog shown. Contents: $str1"
        })
    }

    fun logEmptyDialog() {
        log(LogLevel.WARNING, {}, {
            "Trying to show an empty dialog"
        })
    }

    fun logPrivacyDialogDismissed() {
        log(LogLevel.INFO, {}, {
            "Privacy dialog dismissed"
        })
    }

    fun logStartSettingsActivityFromDialog(packageName: String, userId: Int) {
        log(LogLevel.INFO, {
            str1 = packageName
            int1 = userId
        }, {
            "Start settings activity from dialog for packageName=$str1, userId=$int1 "
        })
    }

    private fun listToString(list: List<PrivacyItem>): String {
        return list.joinToString(separator = ", ", transform = PrivacyItem::log)
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }
}