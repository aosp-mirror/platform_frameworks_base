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
package com.android.systemui.util.concurrency

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.util.time.FakeSystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor

@Module
interface FakeExecutorModule {
    @Binds @Main @SysUISingleton fun bindMainExecutor(executor: FakeExecutor): Executor

    @Binds @UiBackground @SysUISingleton fun bindUiBgExecutor(executor: FakeExecutor): Executor

    companion object {
        @Provides fun provideFake(clock: FakeSystemClock) = FakeExecutor(clock)
    }
}
