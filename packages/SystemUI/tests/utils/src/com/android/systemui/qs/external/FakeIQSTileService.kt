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

package com.android.systemui.qs.external

import android.os.IBinder
import android.os.IInterface
import android.service.quicksettings.IQSTileService

class FakeIQSTileService : IQSTileService.Stub() {

    var isTileAdded: Boolean = false
        private set
    var isTileListening: Boolean = false
        private set
    var isUnlockComplete: Boolean = false
    val clicks: List<IBinder?>
        get() = mutableClicks

    private val mutableClicks: MutableList<IBinder?> = mutableListOf()
    override fun queryLocalInterface(descriptor: String): IInterface {
        return this
    }

    override fun asBinder(): IBinder = this

    override fun onTileAdded() {
        isTileAdded = true
    }

    override fun onTileRemoved() {
        isTileAdded = false
    }

    override fun onStartListening() {
        isTileListening = true
    }

    override fun onStopListening() {
        isTileListening = false
    }

    override fun onClick(wtoken: IBinder?) {
        mutableClicks.add(wtoken)
    }

    override fun onUnlockComplete() {
        isUnlockComplete = true
    }
}
