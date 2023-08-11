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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.NotificationRowModule.NOTIF_REMOTEVIEWS_FACTORIES
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Named

/**
 * Implementation of [NotifLayoutInflaterFactory]. This class uses a set of
 * [NotifRemoteViewsFactory] objects to create replacement views for Notification RemoteViews.
 */
class NotifLayoutInflaterFactory
@AssistedInject
constructor(
    @Assisted private val row: ExpandableNotificationRow,
    @Assisted @InflationFlag val layoutType: Int,
    @Named(NOTIF_REMOTEVIEWS_FACTORIES)
    private val remoteViewsFactories: Set<@JvmSuppressWildcards NotifRemoteViewsFactory>
) : LayoutInflater.Factory2 {

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        var handledFactory: NotifRemoteViewsFactory? = null
        var result: View? = null
        for (layoutFactory in remoteViewsFactories) {
            layoutFactory.instantiate(row, layoutType, parent, name, context, attrs)?.run {
                check(handledFactory == null) {
                    "$layoutFactory tries to produce name:$name with type:$layoutType. " +
                        "However, $handledFactory produced view for $name before."
                }
                handledFactory = layoutFactory
                result = this
            }
        }
        logOnCreateView(name, result, handledFactory)
        return result
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        onCreateView(null, name, context, attrs)

    private fun logOnCreateView(
        name: String,
        replacedView: View?,
        factory: NotifRemoteViewsFactory?
    ) {
        if (SPEW && replacedView != null && factory != null) {
            Log.d(TAG, "$factory produced $replacedView for name:$name with type:$layoutType")
        }
    }

    private companion object {
        private const val TAG = "NotifLayoutInflaterFac"
        private val SPEW = Log.isLoggable(TAG, Log.VERBOSE)
    }

    @AssistedFactory
    interface Provider {
        fun provide(
            row: ExpandableNotificationRow,
            @InflationFlag layoutType: Int
        ): NotifLayoutInflaterFactory
    }
}
