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

import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.systemui.CoreStartable
import com.android.systemui.Flags
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.data.repository.ContextualEducationRepository
import com.android.systemui.education.data.repository.UserContextualEducationRepository
import com.android.systemui.education.domain.interactor.ContextualEducationInteractor
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduStatsInteractor
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduStatsInteractorImpl
import com.android.systemui.education.ui.view.ContextualEduUiCoordinator
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.time.Clock
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
interface ContextualEducationModule {
    @Binds
    fun bindContextualEducationRepository(
        impl: UserContextualEducationRepository
    ): ContextualEducationRepository

    @Qualifier annotation class EduDataStoreScope

    @Qualifier annotation class EduClock

    companion object {
        @EduDataStoreScope
        @Provides
        fun provideEduDataStoreScope(
            @Background bgDispatcher: CoroutineDispatcher
        ): CoroutineScope {
            return CoroutineScope(bgDispatcher + SupervisorJob() + createCoroutineTracingContext("EduDataStoreScope"))
        }

        @EduClock
        @Provides
        fun provideEduClock(): Clock {
            return Clock.systemUTC()
        }

        @Provides
        @IntoMap
        @ClassKey(ContextualEducationInteractor::class)
        fun provideContextualEducationInteractor(
            implLazy: Lazy<ContextualEducationInteractor>
        ): CoreStartable {
            return if (Flags.keyboardTouchpadContextualEducation()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                return NoOpCoreStartable
            }
        }

        @Provides
        fun provideKeyboardTouchpadEduStatsInteractor(
            implLazy: Lazy<KeyboardTouchpadEduStatsInteractorImpl>
        ): KeyboardTouchpadEduStatsInteractor {
            return if (Flags.keyboardTouchpadContextualEducation()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                return NoOpKeyboardTouchpadEduStatsInteractor
            }
        }

        @Provides
        @IntoMap
        @ClassKey(KeyboardTouchpadEduInteractor::class)
        fun provideKeyboardTouchpadEduInteractor(
            implLazy: Lazy<KeyboardTouchpadEduInteractor>
        ): CoreStartable {
            return if (Flags.keyboardTouchpadContextualEducation()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                return NoOpCoreStartable
            }
        }

        @Provides
        @IntoMap
        @ClassKey(ContextualEduUiCoordinator::class)
        fun provideContextualEduUiCoordinator(
            implLazy: Lazy<ContextualEduUiCoordinator>
        ): CoreStartable {
            return if (Flags.keyboardTouchpadContextualEducation()) {
                implLazy.get()
            } else {
                // No-op implementation when the flag is disabled.
                return NoOpCoreStartable
            }
        }
    }
}

private object NoOpKeyboardTouchpadEduStatsInteractor : KeyboardTouchpadEduStatsInteractor {
    override fun incrementSignalCount(gestureType: GestureType) {}

    override fun updateShortcutTriggerTime(gestureType: GestureType) {}
}

private object NoOpCoreStartable : CoreStartable {
    override fun start() {}
}
