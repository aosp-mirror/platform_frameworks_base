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

package com.android.systemui.window.ui.viewmodel

import com.android.systemui.keyguard.ui.transitions.FakeBouncerTransition
import com.android.systemui.keyguard.ui.transitions.FakeGlanceableHubTransition
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.window.domain.interactor.windowRootViewBlurInteractor
import org.mockito.internal.util.collections.Sets

val Kosmos.fakeBouncerTransitions by
    Kosmos.Fixture<Set<FakeBouncerTransition>> {
        Sets.newSet(FakeBouncerTransition(), FakeBouncerTransition())
    }

val Kosmos.fakeGlanceableHubTransitions by
    Kosmos.Fixture<Set<FakeGlanceableHubTransition>> {
        Sets.newSet(FakeGlanceableHubTransition(), FakeGlanceableHubTransition())
    }

val Kosmos.windowRootViewModel by
    Kosmos.Fixture {
        WindowRootViewModel(
            fakeBouncerTransitions,
            fakeGlanceableHubTransitions,
            windowRootViewBlurInteractor,
        )
    }
