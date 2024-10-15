/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.core

import android.app.Fragment
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewUpdatedListener
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.lang.IllegalStateException
import javax.inject.Provider

/**
 * Responsible for creating the status bar window and initializing the root components of that
 * window (see [CollapsedStatusBarFragment])
 */
interface StatusBarInitializer {

    var statusBarViewUpdatedListener: OnStatusBarViewUpdatedListener?

    /**
     * Creates the status bar window and root views, and initializes the component.
     *
     * TODO(b/277764509): Initialize the status bar via [CoreStartable#start].
     */
    fun initializeStatusBar()

    interface OnStatusBarViewInitializedListener {

        /**
         * The status bar view has been initialized.
         *
         * @param component Dagger component that is created when the status bar view is created.
         *   Can be used to retrieve dependencies from that scope, including the status bar root
         *   view.
         */
        fun onStatusBarViewInitialized(component: StatusBarFragmentComponent)
    }

    interface OnStatusBarViewUpdatedListener {
        fun onStatusBarViewUpdated(
            statusBarViewController: PhoneStatusBarViewController,
            statusBarTransitions: PhoneStatusBarTransitions,
        )
    }

    interface Factory {
        fun create(displayId: Int): StatusBarInitializer
    }
}

class StatusBarInitializerImpl
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    private val collapsedStatusBarFragmentProvider: Provider<CollapsedStatusBarFragment>,
    private val creationListeners: Set<@JvmSuppressWildcards OnStatusBarViewInitializedListener>,
) : CoreStartable, StatusBarInitializer {
    private var component: StatusBarFragmentComponent? = null

    @get:VisibleForTesting
    var initialized = false
        private set

    override var statusBarViewUpdatedListener: OnStatusBarViewUpdatedListener? = null
        set(value) {
            field = value
            // If a listener is added after initialization, immediately call the callback
            component?.let { component ->
                field?.onStatusBarViewUpdated(
                    component.phoneStatusBarViewController,
                    component.phoneStatusBarTransitions,
                )
            }
        }

    override fun start() {
        if (StatusBarSimpleFragment.isEnabled) {
            doStart()
        }
    }

    override fun initializeStatusBar() {
        StatusBarSimpleFragment.assertInLegacyMode()
        doStart()
    }

    private fun doStart() {
        initialized = true
        statusBarWindowControllerStore.defaultDisplay.fragmentHostManager
            .addTagListener(
                CollapsedStatusBarFragment.TAG,
                object : FragmentHostManager.FragmentListener {
                    override fun onFragmentViewCreated(tag: String, fragment: Fragment) {
                        val statusBarFragmentComponent =
                            (fragment as CollapsedStatusBarFragment).statusBarFragmentComponent
                                ?: throw IllegalStateException()
                        statusBarViewUpdatedListener?.onStatusBarViewUpdated(
                            statusBarFragmentComponent.phoneStatusBarViewController,
                            statusBarFragmentComponent.phoneStatusBarTransitions,
                        )
                        creationListeners.forEach { listener ->
                            listener.onStatusBarViewInitialized(statusBarFragmentComponent)
                        }
                    }

                    override fun onFragmentViewDestroyed(tag: String?, fragment: Fragment?) {
                        // nop
                    }
                },
            )
            .fragmentManager
            .beginTransaction()
            .replace(
                R.id.status_bar_container,
                collapsedStatusBarFragmentProvider.get(),
                CollapsedStatusBarFragment.TAG,
            )
            .commit()
    }

    @AssistedFactory
    interface Factory : StatusBarInitializer.Factory {
        override fun create(displayId: Int): StatusBarInitializerImpl
    }
}
