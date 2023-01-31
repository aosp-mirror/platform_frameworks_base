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
package com.android.systemui.mediaprojection.devicepolicy

import android.os.UserHandle
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.ActivityManagerWrapper
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class WorkProfile

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PersonalProfile

/** Module for media projection device policy related dependencies */
@Module
class MediaProjectionDevicePolicyModule {
    @Provides
    @PersonalProfile
    fun personalUserHandle(activityManagerWrapper: ActivityManagerWrapper): UserHandle {
        // Current foreground user is the 'personal' profile
        return UserHandle.of(activityManagerWrapper.currentUserId)
    }

    @Provides
    @WorkProfile
    fun workProfileUserHandle(userTracker: UserTracker): UserHandle? =
        userTracker.userProfiles.find { it.isManagedProfile }?.userHandle
}
