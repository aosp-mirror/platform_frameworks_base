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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewUpdatedListener
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import com.android.systemui.statusbar.window.StatusBarWindowController
import java.lang.IllegalStateException
import javax.inject.Inject
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
}

@SysUISingleton
class StatusBarInitializerImpl
@Inject
constructor(
    private val windowController: StatusBarWindowController,
    private val collapsedStatusBarFragmentProvider: Provider<CollapsedStatusBarFragment>,
    private val creationListeners: Set<@JvmSuppressWildcards OnStatusBarViewInitializedListener>,
) : StatusBarInitializer {

    override var statusBarViewUpdatedListener: OnStatusBarViewUpdatedListener? = null

    override fun initializeStatusBar() {
        windowController.fragmentHostManager
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
}
