/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.ui.view

import android.content.Context
import android.widget.Toast
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.education.ui.viewmodel.ContextualEduContentViewModel
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A class to show contextual education on UI based on the edu produced from
 * [ContextualEduViewModel]
 */
@SysUISingleton
class ContextualEduUiCoordinator
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: ContextualEduViewModel,
    private val createToast: (String) -> Toast
) : CoreStartable {

    @Inject
    constructor(
        @Application applicationScope: CoroutineScope,
        context: Context,
        viewModel: ContextualEduViewModel,
    ) : this(
        applicationScope,
        viewModel,
        createToast = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG) }
    )

    override fun start() {
        applicationScope.launch {
            viewModel.eduContent.collect { contentModel ->
                if (contentModel.type == EducationUiType.Toast) {
                    showToast(contentModel)
                }
            }
        }
    }

    private fun showToast(model: ContextualEduContentViewModel) {
        val toast = createToast(model.message)
        toast.show()
    }
}
