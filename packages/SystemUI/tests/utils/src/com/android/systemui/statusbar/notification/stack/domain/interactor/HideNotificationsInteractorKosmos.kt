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
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.unfold.domain.interactor.unfoldTransitionInteractor
import com.android.systemui.util.animation.data.repository.animationStatusRepository

val Kosmos.hideNotificationsInteractor by Fixture {
    HideNotificationsInteractor(
        unfoldTransitionInteractor = unfoldTransitionInteractor,
        configurationInteractor = configurationInteractor,
        animationsStatus = animationStatusRepository,
        powerInteractor = powerInteractor,
    )
}
