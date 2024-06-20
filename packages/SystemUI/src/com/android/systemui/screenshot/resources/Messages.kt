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

package com.android.systemui.screenshot.resources

import android.content.Context
import androidx.annotation.OpenForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import javax.inject.Inject

/** String values from resources, for easy injection. */
@OpenForTesting
@SysUISingleton
open class Messages @Inject constructor(private val context: Context) {
    open val savingScreenshotAnnouncement by lazy {
        requireNotNull(context.resources.getString(R.string.screenshot_saving_title))
    }

    open val savingToWorkProfileAnnouncement by lazy {
        requireNotNull(context.resources.getString(R.string.screenshot_saving_work_profile_title))
    }

    open val savingToPrivateProfileAnnouncement by lazy {
        requireNotNull(context.resources.getString(R.string.screenshot_saving_private_profile))
    }
}
