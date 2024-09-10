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

package com.android.systemui.screenshot

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.resources.Messages
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Logic for determining the announcement that a screenshot has been taken (for accessibility). */
class AnnouncementResolver
@Inject
constructor(
    private val messages: Messages,
    private val profileTypes: ProfileTypeRepository,
    @Application private val mainScope: CoroutineScope,
) {

    suspend fun getScreenshotAnnouncement(userId: Int): String =
        when (profileTypes.getProfileType(userId)) {
            ProfileType.PRIVATE -> messages.savingToPrivateProfileAnnouncement
            ProfileType.WORK -> messages.savingToWorkProfileAnnouncement
            else -> messages.savingScreenshotAnnouncement
        }

    fun getScreenshotAnnouncement(userId: Int, announceCallback: Consumer<String>) {
        mainScope.launch { announceCallback.accept(getScreenshotAnnouncement(userId)) }
    }
}
