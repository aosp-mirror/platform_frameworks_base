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

package com.android.systemui.screenshot.data.repository

import android.annotation.UserIdInt
import com.android.systemui.screenshot.data.model.ProfileType

/** A facility for checking user profile types. */
fun interface ProfileTypeRepository {
    /**
     * Returns the profile type when [userId] refers to a profile user. If the profile type is
     * unknown or not a profile user, [ProfileType.NONE] is returned.
     */
    suspend fun getProfileType(@UserIdInt userId: Int): ProfileType
}
