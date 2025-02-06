/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.posturing.dagger

import com.android.systemui.communal.posturing.data.repository.NoOpPosturingRepository
import com.android.systemui.communal.posturing.data.repository.PosturingRepository
import dagger.Binds
import dagger.Module

/** Module providing a reference implementation of the posturing signal. */
@Module
interface NoopPosturingModule {
    /** Binds a reference implementation of the posturing repository */
    @Binds fun bindPosturingRepository(impl: NoOpPosturingRepository): PosturingRepository
}
