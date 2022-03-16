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
import com.android.systemui.R
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.window.StatusBarWindowController
import java.lang.IllegalStateException
import javax.inject.Inject

/**
 * Responsible for creating the status bar window and initializing the root components of that
 * window (see [CollapsedStatusBarFragment])
 */
@CentralSurfacesScope
class StatusBarInitializer @Inject constructor(
    private val windowController: StatusBarWindowController
) {

    var statusBarViewUpdatedListener: OnStatusBarViewUpdatedListener? = null

    /**
     * Creates the status bar window and root views, and initializes the component
     */
    fun initializeStatusBar(
        centralSurfacesComponent: CentralSurfacesComponent
    ) {
        windowController.fragmentHostManager.addTagListener(
                CollapsedStatusBarFragment.TAG,
                object : FragmentHostManager.FragmentListener {
                    override fun onFragmentViewCreated(tag: String, fragment: Fragment) {
                        val statusBarFragmentComponent = (fragment as CollapsedStatusBarFragment)
                                .statusBarFragmentComponent ?: throw IllegalStateException()
                        statusBarViewUpdatedListener?.onStatusBarViewUpdated(
                            statusBarFragmentComponent.phoneStatusBarView,
                            statusBarFragmentComponent.phoneStatusBarViewController,
                            statusBarFragmentComponent.phoneStatusBarTransitions
                        )
                    }

                    override fun onFragmentViewDestroyed(tag: String?, fragment: Fragment?) {
                        // nop
                    }
                }).fragmentManager
                .beginTransaction()
                .replace(R.id.status_bar_container,
                        centralSurfacesComponent.createCollapsedStatusBarFragment(),
                        CollapsedStatusBarFragment.TAG)
                .commit()
    }

    interface OnStatusBarViewUpdatedListener {
        fun onStatusBarViewUpdated(
            statusBarView: PhoneStatusBarView,
            statusBarViewController: PhoneStatusBarViewController,
            statusBarTransitions: PhoneStatusBarTransitions
        )
    }
}
