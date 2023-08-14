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
 *
 */

package com.android.systemui.keyguard.data.repository

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.CommandQueue
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import org.mockito.Mockito.mock

@SysUISingleton
class FakeCommandQueue @Inject constructor() :
    CommandQueue(mock(Context::class.java), mock(DisplayTracker::class.java)) {
    private val callbacks = mutableListOf<Callbacks>()

    override fun addCallback(callback: Callbacks) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: Callbacks) {
        callbacks.remove(callback)
    }

    fun doForEachCallback(func: (callback: Callbacks) -> Unit) {
        callbacks.forEach { func(it) }
    }

    fun callbackCount(): Int = callbacks.size
}

@Module
interface FakeCommandQueueModule {
    @Binds fun bindFake(fake: FakeCommandQueue): CommandQueue
}
