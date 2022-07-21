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

package com.android.systemui.compose.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.people.emptyPeopleSpaceViewModel
import com.android.systemui.people.fewPeopleSpaceViewModel
import com.android.systemui.people.fullPeopleSpaceViewModel
import com.android.systemui.people.ui.compose.PeopleScreen
import com.android.systemui.people.ui.viewmodel.PeopleViewModel

@Composable
fun EmptyPeopleScreen(onResult: (PeopleViewModel.Result) -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = emptyPeopleSpaceViewModel(context)
    PeopleScreen(viewModel, onResult)
}

@Composable
fun FewPeopleScreen(onResult: (PeopleViewModel.Result) -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = fewPeopleSpaceViewModel(context)
    PeopleScreen(viewModel, onResult)
}

@Composable
fun FullPeopleScreen(onResult: (PeopleViewModel.Result) -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = fullPeopleSpaceViewModel(context)
    PeopleScreen(viewModel, onResult)
}
