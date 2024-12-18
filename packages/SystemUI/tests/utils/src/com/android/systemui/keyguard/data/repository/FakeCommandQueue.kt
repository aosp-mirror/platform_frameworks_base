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
import androidx.collection.ArrayMap
import com.android.internal.statusbar.StatusBarIcon
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

    val icons = ArrayMap<String, StatusBarIcon>()

    private val perDisplayDisableFlags1 = mutableMapOf<Int, Int>()
    private val perDisplayDisableFlags2 = mutableMapOf<Int, Int>()

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

    override fun setIcon(slot: String, icon: StatusBarIcon) {
        icons[slot] = icon
    }

    override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {
        perDisplayDisableFlags1[displayId] = state1
        perDisplayDisableFlags2[displayId] = state2
    }

    override fun disable(displayId: Int, state1: Int, state2: Int) {
        disable(displayId, state1, state2, /* animate= */ false)
    }

    fun disableFlags1ForDisplay(displayId: Int) = perDisplayDisableFlags1[displayId]

    fun disableFlags2ForDisplay(displayId: Int) = perDisplayDisableFlags2[displayId]
}

@Module
interface FakeCommandQueueModule {
    @Binds fun bindFake(fake: FakeCommandQueue): CommandQueue
}
