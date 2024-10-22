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

package com.android.systemui.communal.widgets

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.UserHandle
import androidx.lifecycle.LifecycleService
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IAppWidgetHostListener
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IGlanceableHubWidgetsListener
import javax.inject.Inject

/**
 * Service for the [GlanceableHubWidgetManager], which runs in a foreground user in Headless System
 * User Mode (HSUM), manages widgets as the user who owns them, and communicates back to the
 * headless system user, where these widgets are rendered.
 */
class GlanceableHubWidgetManagerService
@Inject
constructor(glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper) : LifecycleService() {

    init {
        // The service should only run in a foreground user.
        glanceableHubMultiUserHelper.assertNotInHeadlessSystemUser()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return WidgetManagerServiceBinder().asBinder()
    }

    private fun addWidgetsListenerInternal(listener: IGlanceableHubWidgetsListener?) {
        TODO("Not yet implemented")
    }

    private fun removeWidgetsListenerInternal(listener: IGlanceableHubWidgetsListener?) {
        TODO("Not yet implemented")
    }

    private fun setAppWidgetHostListenerInternal(
        appWidgetId: Int,
        listener: IAppWidgetHostListener?,
    ) {
        TODO("Not yet implemented")
    }

    private fun addWidgetInternal(provider: ComponentName?, user: UserHandle?, rank: Int) {
        TODO("Not yet implemented")
    }

    private fun deleteWidgetInternal(appWidgetId: Int) {
        TODO("Not yet implemented")
    }

    private fun updateWidgetOrderInternal(appWidgetIds: IntArray?, ranks: IntArray?) {
        TODO("Not yet implemented")
    }

    private fun resizeWidgetInternal(
        appWidgetId: Int,
        spanY: Int,
        appWidgetIds: IntArray?,
        ranks: IntArray?,
    ) {
        TODO("Not yet implemented")
    }

    private inner class WidgetManagerServiceBinder : IGlanceableHubWidgetManagerService.Stub() {
        override fun addWidgetsListener(listener: IGlanceableHubWidgetsListener?) {
            val iden = clearCallingIdentity()

            try {
                addWidgetsListenerInternal(listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun removeWidgetsListener(listener: IGlanceableHubWidgetsListener?) {
            val iden = clearCallingIdentity()

            try {
                removeWidgetsListenerInternal(listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun setAppWidgetHostListener(appWidgetId: Int, listener: IAppWidgetHostListener?) {
            val iden = clearCallingIdentity()

            try {
                setAppWidgetHostListenerInternal(appWidgetId, listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun addWidget(provider: ComponentName?, user: UserHandle?, rank: Int) {
            val iden = clearCallingIdentity()

            try {
                addWidgetInternal(provider, user, rank)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun deleteWidget(appWidgetId: Int) {
            val iden = clearCallingIdentity()

            try {
                deleteWidgetInternal(appWidgetId)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun updateWidgetOrder(appWidgetIds: IntArray?, ranks: IntArray?) {
            val iden = clearCallingIdentity()

            try {
                updateWidgetOrderInternal(appWidgetIds, ranks)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun resizeWidget(
            appWidgetId: Int,
            spanY: Int,
            appWidgetIds: IntArray?,
            ranks: IntArray?,
        ) {
            val iden = clearCallingIdentity()

            try {
                resizeWidgetInternal(appWidgetId, spanY, appWidgetIds, ranks)
            } finally {
                restoreCallingIdentity(iden)
            }
        }
    }
}
