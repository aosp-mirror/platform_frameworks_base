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
package com.android.systemui.statusbar.notification.collection.render

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.NotificationPanelViewController
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent.StatusBarScope
import com.android.systemui.util.ListenerSet
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet

/** Provides certain notification panel events.  */
interface NotifPanelEventSource {

    /** Registers callbacks to be invoked when notification panel events occur.  */
    fun registerCallbacks(callbacks: Callbacks)

    /** Unregisters callbacks previously registered via [.registerCallbacks]  */
    fun unregisterCallbacks(callbacks: Callbacks)

    /** Callbacks for certain notification panel events. */
    interface Callbacks {

        /** Invoked when the notification panel starts or stops collapsing. */
        fun onPanelCollapsingChanged(isCollapsing: Boolean)

        /**
         * Invoked when the notification panel starts or stops launching an [android.app.Activity].
         */
        fun onLaunchingActivityChanged(isLaunchingActivity: Boolean)
    }
}

@Module
abstract class NotifPanelEventSourceModule {

    @Binds
    @SysUISingleton
    abstract fun bindEventSource(manager: NotifPanelEventSourceManager): NotifPanelEventSource

    @Module
    companion object {
        @JvmStatic
        @Provides
        fun provideManager(): NotifPanelEventSourceManager = NotifPanelEventSourceManagerImpl()
    }
}

@Module
object StatusBarNotifPanelEventSourceModule {
    @JvmStatic
    @Provides
    @IntoSet
    @StatusBarScope
    fun bindStartable(
        manager: NotifPanelEventSourceManager,
        notifPanelController: NotificationPanelViewController
    ): StatusBarComponent.Startable =
            EventSourceStatusBarStartableImpl(manager, notifPanelController)
}

/**
 * Management layer that bridges [SysUiSingleton] and [StatusBarScope]. Necessary because code that
 * wants to listen to [NotifPanelEventSource] lives in [SysUiSingleton], but the events themselves
 * come from [NotificationPanelViewController] in [StatusBarScope].
 */
interface NotifPanelEventSourceManager : NotifPanelEventSource {
    var eventSource: NotifPanelEventSource?
}

private class NotifPanelEventSourceManagerImpl
    : NotifPanelEventSourceManager, NotifPanelEventSource.Callbacks {

    private val callbackSet = ListenerSet<NotifPanelEventSource.Callbacks>()

    override var eventSource: NotifPanelEventSource? = null
        set(value) {
            field?.unregisterCallbacks(this)
            value?.registerCallbacks(this)
            field = value
        }

    override fun registerCallbacks(callbacks: NotifPanelEventSource.Callbacks) {
        callbackSet.addIfAbsent(callbacks)
    }

    override fun unregisterCallbacks(callbacks: NotifPanelEventSource.Callbacks) {
        callbackSet.remove(callbacks)
    }

    override fun onPanelCollapsingChanged(isCollapsing: Boolean) {
        callbackSet.forEach { it.onPanelCollapsingChanged(isCollapsing) }
    }

    override fun onLaunchingActivityChanged(isLaunchingActivity: Boolean) {
        callbackSet.forEach { it.onLaunchingActivityChanged(isLaunchingActivity) }
    }
}

private class EventSourceStatusBarStartableImpl(
    private val manager: NotifPanelEventSourceManager,
    private val notifPanelController: NotificationPanelViewController
) : StatusBarComponent.Startable {

    override fun start() {
        manager.eventSource = notifPanelController
    }

    override fun stop() {
        manager.eventSource = null
    }
}
