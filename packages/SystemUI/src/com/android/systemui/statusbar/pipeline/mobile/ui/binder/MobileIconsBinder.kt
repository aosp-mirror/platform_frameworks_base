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

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.phone.ui.IconManager
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import kotlinx.coroutines.launch

object MobileIconsBinder {
    /**
     * Start this ViewModel collecting on the list of mobile subscriptions in the scope of [view]
     * which is passed in and managed by [IconManager]. Once the subscription list flow starts
     * collecting, [MobileUiAdapter] will send updates to the icon manager.
     */
    @JvmStatic
    fun bind(view: View, viewModel: MobileIconsViewModel) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.subscriptionIdsFlow.collect {
                        // TODO(b/249790733): This is an empty collect, because [MobileUiAdapter]
                        //  sets up a side-effect in this flow to trigger the methods on
                        // [StatusBarIconController] which allows for this pipeline to be a data
                        // source for the mobile icons.
                    }
                }
            }
        }
    }
}
