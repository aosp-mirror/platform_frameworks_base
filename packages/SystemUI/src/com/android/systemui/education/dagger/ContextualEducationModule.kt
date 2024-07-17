/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.dagger

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.data.repository.ContextualEducationRepository
import com.android.systemui.education.data.repository.ContextualEducationRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.time.Clock
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
interface ContextualEducationModule {
    @Binds
    fun bindContextualEducationRepository(
        impl: ContextualEducationRepositoryImpl
    ): ContextualEducationRepository

    @Qualifier annotation class EduDataStoreScope

    @Qualifier annotation class EduClock

    companion object {
        @EduDataStoreScope
        @Provides
        fun provideEduDataStoreScope(
            @Background bgDispatcher: CoroutineDispatcher
        ): CoroutineScope {
            return CoroutineScope(bgDispatcher + SupervisorJob())
        }

        @EduClock
        @Provides
        fun provideEduClock(): Clock {
            return Clock.systemUTC()
        }
    }
}
