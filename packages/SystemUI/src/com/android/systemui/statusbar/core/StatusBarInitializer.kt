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
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewUpdatedListener
import com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepository
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory
import com.android.systemui.statusbar.window.StatusBarWindowController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.lang.IllegalStateException
import javax.inject.Provider

/**
 * Responsible for creating the status bar window and initializing the root components of that
 * window (see [CollapsedStatusBarFragment])
 */
interface StatusBarInitializer : CoreStartable {

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
        fun onStatusBarViewInitialized(component: HomeStatusBarComponent)
    }

    interface OnStatusBarViewUpdatedListener {
        fun onStatusBarViewUpdated(
            statusBarViewController: PhoneStatusBarViewController,
            statusBarTransitions: PhoneStatusBarTransitions,
        )
    }

    interface Factory {
        fun create(
            statusBarWindowController: StatusBarWindowController,
            statusBarModePerDisplayRepository: StatusBarModePerDisplayRepository,
        ): StatusBarInitializer
    }
}

class StatusBarInitializerImpl
@AssistedInject
constructor(
    @Assisted private val statusBarWindowController: StatusBarWindowController,
    @Assisted private val statusBarModePerDisplayRepository: StatusBarModePerDisplayRepository,
    private val collapsedStatusBarFragmentProvider: Provider<CollapsedStatusBarFragment>,
    private val statusBarRootFactory: StatusBarRootFactory,
    private val componentFactory: HomeStatusBarComponent.Factory,
    private val creationListeners: Set<@JvmSuppressWildcards OnStatusBarViewInitializedListener>,
) : StatusBarInitializer {
    private var component: HomeStatusBarComponent? = null

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
        doStart()
    }

    override fun initializeStatusBar() {
        StatusBarRootModernization.assertInLegacyMode()
        doStart()
    }

    private fun doStart() {
        if (StatusBarRootModernization.isEnabled) doComposeStart() else doLegacyStart()
    }

    /**
     * Stand up the [PhoneStatusBarView] in a compose root. There will be no
     * [CollapsedStatusBarFragment] in this mode
     */
    private fun doComposeStart() {
        initialized = true
        val statusBarRoot =
            statusBarRootFactory.create(statusBarWindowController.backgroundView as ViewGroup) { cv
                ->
                val phoneStatusBarView = cv.findViewById<PhoneStatusBarView>(R.id.status_bar)
                component =
                    componentFactory.create(phoneStatusBarView).also { component ->
                        // CollapsedStatusBarFragment used to be responsible initializing
                        component.init()

                        statusBarViewUpdatedListener?.onStatusBarViewUpdated(
                            component.phoneStatusBarViewController,
                            component.phoneStatusBarTransitions,
                        )

                        if (StatusBarConnectedDisplays.isEnabled) {
                            statusBarModePerDisplayRepository.onStatusBarViewInitialized(component)
                        } else {
                            creationListeners.forEach { listener ->
                                listener.onStatusBarViewInitialized(component)
                            }
                        }
                    }
            }

        // Add the new compose view to the hierarchy because we don't use fragment transactions
        // anymore
        val windowBackgroundView = statusBarWindowController.backgroundView as ViewGroup
        windowBackgroundView.addView(statusBarRoot)
    }

    private fun doLegacyStart() {
        initialized = true
        statusBarWindowController.fragmentHostManager
            .addTagListener(
                CollapsedStatusBarFragment.TAG,
                object : FragmentHostManager.FragmentListener {
                    override fun onFragmentViewCreated(tag: String, fragment: Fragment) {
                        component =
                            (fragment as CollapsedStatusBarFragment).homeStatusBarComponent
                                ?: throw IllegalStateException()
                        statusBarViewUpdatedListener?.onStatusBarViewUpdated(
                            component!!.phoneStatusBarViewController,
                            component!!.phoneStatusBarTransitions,
                        )
                        creationListeners.forEach { listener ->
                            listener.onStatusBarViewInitialized(component!!)
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
        override fun create(
            statusBarWindowController: StatusBarWindowController,
            statusBarModePerDisplayRepository: StatusBarModePerDisplayRepository,
        ): StatusBarInitializerImpl
    }
}
