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
package com.android.systemui.keyguard

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider.ScreenListener
import javax.inject.Inject

@SysUISingleton
class LifecycleScreenStatusProvider @Inject constructor(screenLifecycle: ScreenLifecycle) :
    ScreenStatusProvider, ScreenLifecycle.Observer {

    init {
        screenLifecycle.addObserver(this)
    }

    private val listeners: MutableList<ScreenListener> = mutableListOf()

    override fun removeCallback(listener: ScreenListener) {
        listeners.remove(listener)
    }

    override fun addCallback(listener: ScreenListener) {
        listeners.add(listener)
    }

    override fun onScreenTurnedOn() {
        listeners.forEach(ScreenListener::onScreenTurnedOn)
    }
}
