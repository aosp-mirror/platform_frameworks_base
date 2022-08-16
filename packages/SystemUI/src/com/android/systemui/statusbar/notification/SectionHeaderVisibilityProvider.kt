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

package com.android.systemui.statusbar.notification

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.R
import javax.inject.Inject

/**
 * A class which keeps track of whether section headers should be shown in the notification shade.
 *
 * (In an ideal world, this would directly monitor the state of the keyguard and invalidate the
 * pipeline to show/hide headers, but the KeyguardController already invalidates the pipeline when
 * the keyguard's state changes. Instead of having both classes monitor for state changes and ending
 * up with duplicate runs of the pipeline, we let the KeyguardController update the header
 * visibility when it invalidates, and we just store that state here.)
 */
@SysUISingleton
class SectionHeaderVisibilityProvider @Inject constructor(
    context: Context
) {
    var neverShowSectionHeaders = context.resources.getBoolean(R.bool.config_notification_never_show_section_headers)
        private set
    var sectionHeadersVisible = true
}
