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

package com.android.systemui.animation

import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testCase

val Kosmos.dialogTransitionAnimator by Fixture {
    fakeDialogTransitionAnimator(
        // The main thread is checked in a bunch of places inside the different transitions
        // animators, so we have to pass the real main executor here.
        mainExecutor = testCase.context.mainExecutor,
        interactionJankMonitor = interactionJankMonitor,
    )
}
