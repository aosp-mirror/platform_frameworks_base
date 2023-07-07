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

package com.android.systemui.qs.footer.dagger

import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepository
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepositoryImpl
import com.android.systemui.qs.footer.data.repository.UserSwitcherRepository
import com.android.systemui.qs.footer.data.repository.UserSwitcherRepositoryImpl
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractorImpl
import dagger.Binds
import dagger.Module

/** Dagger module to provide/bind footer actions singletons. */
@Module
interface FooterActionsModule {
    @Binds fun userSwitcherRepository(impl: UserSwitcherRepositoryImpl): UserSwitcherRepository

    @Binds
    fun foregroundServicesRepository(
        impl: ForegroundServicesRepositoryImpl
    ): ForegroundServicesRepository

    @Binds fun footerActionsInteractor(impl: FooterActionsInteractorImpl): FooterActionsInteractor
}
