/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.dagger

import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.statusbar.policy.RemoteInputView
import com.android.systemui.statusbar.policy.RemoteInputViewController
import com.android.systemui.statusbar.policy.RemoteInputViewControllerImpl
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Subcomponent
import javax.inject.Qualifier

@Subcomponent(modules = [InternalRemoteInputViewModule::class])
@RemoteInputViewScope
interface RemoteInputViewSubcomponent {
    val controller: RemoteInputViewController

    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance view: RemoteInputView,
            @BindsInstance remoteInputController: RemoteInputController
        ): RemoteInputViewSubcomponent
    }
}

@Module
private interface InternalRemoteInputViewModule {
    @Binds
    fun bindController(impl: RemoteInputViewControllerImpl): RemoteInputViewController
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class RemoteInputViewScope
