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

package com.android.systemui.statusbar.phone.fragment

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.fragments.FragmentService
import com.android.systemui.qs.QSFragmentStartable
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import javax.inject.Provider

/**
 * Provides [FragmentService] with a way to automatically inflate [CollapsedStatusBarFragment],
 * similar to [QSFragmentStartable].
 */
@SysUISingleton
class CollapsedStatusBarFragmentStartable
@Inject
constructor(
    private val fragmentService: FragmentService,
    private val collapsedstatusBarFragmentProvider: Provider<CollapsedStatusBarFragment>
) : CoreStartable {
    override fun start() {
        fragmentService.addFragmentInstantiationProvider(
            CollapsedStatusBarFragment::class.java,
            collapsedstatusBarFragmentProvider,
        )
    }
}

@Module(subcomponents = [StatusBarFragmentComponent::class])
interface CollapsedStatusBarFragmentStartableModule {
    @Binds
    @IntoMap
    @ClassKey(CollapsedStatusBarFragmentStartable::class)
    fun bindsCollapsedStatusBarFragmentStartable(
        startable: CollapsedStatusBarFragmentStartable
    ): CoreStartable
}
