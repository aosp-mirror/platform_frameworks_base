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

package com.android.systemui.statusbar.notification.row.icon

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.android.internal.widget.NotificationRowIconView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotifRemoteViewsFactory
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder
import javax.inject.Inject

/**
 * A factory which owns the construction of any NotificationRowIconView inside of Notifications in
 * SystemUI. This allows overriding the small icon with the app icon in notifications.
 */
class NotificationRowIconViewInflaterFactory
@Inject
constructor(private val appIconProvider: AppIconProvider) : NotifRemoteViewsFactory {
    override fun instantiate(
        row: ExpandableNotificationRow,
        @NotificationRowContentBinder.InflationFlag layoutType: Int,
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? {
        return when (name) {
            NotificationRowIconView::class.java.name ->
                NotificationRowIconView(context, attrs).also { view ->
                    val sbn = row.entry.sbn
                    view.setIconProvider(
                        object : NotificationRowIconView.NotificationIconProvider {
                            override fun shouldShowAppIcon(): Boolean {
                                // TODO(b/371174789): implement me
                                return true
                            }

                            override fun getAppIcon(): Drawable {
                                return appIconProvider.getOrFetchAppIcon(sbn.packageName, context)
                            }
                        }
                    )
                }
            else -> null
        }
    }
}
