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
 *
 */

package com.android.systemui.compose

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel

/** The Compose facade, when Compose is *not* available. */
object ComposeFacade : BaseComposeFacade {
    override fun isComposeAvailable(): Boolean = false

    override fun composeInitializer(): ComposeInitializer {
        throwComposeUnavailableError()
    }

    override fun setPeopleSpaceActivityContent(
        activity: ComponentActivity,
        viewModel: PeopleViewModel,
        onResult: (PeopleViewModel.Result) -> Unit,
    ) {
        throwComposeUnavailableError()
    }

    override fun createFooterActionsView(
        context: Context,
        viewModel: FooterActionsViewModel,
        qsVisibilityLifecycleOwner: LifecycleOwner
    ): View {
        throwComposeUnavailableError()
    }

    private fun throwComposeUnavailableError(): Nothing {
        error(
            "Compose is not available. Make sure to check isComposeAvailable() before calling any" +
                " other function on ComposeFacade."
        )
    }
}
