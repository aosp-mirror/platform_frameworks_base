/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.people

import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.NotificationPersonExtractorPlugin
import com.android.systemui.statusbar.policy.ExtensionController
import javax.inject.Inject

interface NotificationPersonExtractor {
    fun isPersonNotification(sbn: StatusBarNotification): Boolean
}

@SysUISingleton
class NotificationPersonExtractorPluginBoundary @Inject constructor(
    extensionController: ExtensionController
) : NotificationPersonExtractor {

    private var plugin: NotificationPersonExtractorPlugin? = null

    init {
        plugin = extensionController
                .newExtension(NotificationPersonExtractorPlugin::class.java)
                .withPlugin(NotificationPersonExtractorPlugin::class.java)
                .withCallback { extractor ->
                    plugin = extractor
                }
                .build()
                .get()
    }

    override fun isPersonNotification(sbn: StatusBarNotification): Boolean =
            plugin?.isPersonNotification(sbn) ?: false
}
