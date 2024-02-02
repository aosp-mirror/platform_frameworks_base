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
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.row.NotificationRowModule.NOTIF_REMOTEVIEWS_FACTORIES
import com.android.systemui.util.asIndenting
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named

/**
 * Implementation of [NotifLayoutInflaterFactory]. This class uses a set of
 * [NotifRemoteViewsFactory] objects to create replacement views for Notification RemoteViews.
 */
open class NotifLayoutInflaterFactory
@Inject
constructor(
    dumpManager: DumpManager,
    @Named(NOTIF_REMOTEVIEWS_FACTORIES)
    private val remoteViewsFactories: Set<@JvmSuppressWildcards NotifRemoteViewsFactory>
) : LayoutInflater.Factory2, Dumpable {
    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        var view: View? = null
        var handledFactory: NotifRemoteViewsFactory? = null
        for (layoutFactory in remoteViewsFactories) {
            view = layoutFactory.instantiate(parent, name, context, attrs)
            if (view != null) {
                check(handledFactory == null) {
                    "${layoutFactory.javaClass.name} tries to produce view. However, " +
                        "${handledFactory?.javaClass?.name} produced view for $name before."
                }
                handledFactory = layoutFactory
            }
        }
        logOnCreateView(name, view, handledFactory)
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        onCreateView(null, name, context, attrs)

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val indentingPW = pw.asIndenting()

        indentingPW.appendLine("$TAG ReplacementFactories:")
        indentingPW.withIncreasedIndent {
            remoteViewsFactories.forEach { indentingPW.appendLine(it.javaClass.simpleName) }
        }
    }

    private fun logOnCreateView(
        name: String,
        replacedView: View?,
        factory: NotifRemoteViewsFactory?
    ) {
        if (SPEW && replacedView != null && factory != null) {
            Log.d(TAG, "$factory produced view for $name: $replacedView")
        }
    }

    private companion object {
        private const val TAG = "NotifLayoutInflaterFac"
        private val SPEW = Log.isLoggable(TAG, Log.VERBOSE)
    }
}
