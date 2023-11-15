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

package com.android.systemui.flags

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor
import javax.inject.Inject

/** A class in which engineers can define flag dependencies */
@SysUISingleton
class FlagDependencies @Inject constructor(featureFlags: FeatureFlagsClassic, handler: Handler) :
    FlagDependenciesBase(featureFlags, handler) {
    override fun defineDependencies() {
        FooterViewRefactor.token dependsOn NotificationIconContainerRefactor.token
    }
}
