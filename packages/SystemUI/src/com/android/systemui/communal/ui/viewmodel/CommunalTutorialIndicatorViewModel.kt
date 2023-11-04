/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.communal.ui.viewmodel

import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** View model for communal tutorial indicator on keyguard */
class CommunalTutorialIndicatorViewModel
@Inject
constructor(
    communalTutorialInteractor: CommunalTutorialInteractor,
    bottomAreaInteractor: KeyguardBottomAreaInteractor,
) {
    /** An observable for whether the tutorial indicator view should be visible. */
    val showIndicator: Flow<Boolean> = communalTutorialInteractor.isTutorialAvailable

    /** An observable for the alpha level for the tutorial indicator. */
    val alpha: Flow<Float> = bottomAreaInteractor.alpha.distinctUntilChanged()
}
