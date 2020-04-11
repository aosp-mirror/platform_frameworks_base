/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.UserHandle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RingerModeTrackerImpl @Inject constructor(
    audioManager: AudioManager,
    broadcastDispatcher: BroadcastDispatcher,
    @Background executor: Executor
) : RingerModeTracker {

    override val ringerMode: LiveData<Int> = RingerModeLiveData(
            broadcastDispatcher,
            executor,
            AudioManager.RINGER_MODE_CHANGED_ACTION,
            audioManager::getRingerMode
    )

    override val ringerModeInternal: LiveData<Int> = RingerModeLiveData(
            broadcastDispatcher,
            executor,
            AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION,
            audioManager::getRingerModeInternal
    )
}

class RingerModeLiveData(
    private val broadcastDispatcher: BroadcastDispatcher,
    private val executor: Executor,
    intent: String,
    private val getter: () -> Int
) : MutableLiveData<Int>() {

    private val filter = IntentFilter(intent)
    var initialSticky: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            initialSticky = isInitialStickyBroadcast
            postValue(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1))
        }
    }

    override fun getValue(): Int {
        return super.getValue() ?: -1
    }

    override fun onActive() {
        super.onActive()
        broadcastDispatcher.registerReceiver(receiver, filter, executor, UserHandle.ALL)
        executor.execute {
            postValue(getter())
        }
    }

    override fun onInactive() {
        super.onInactive()
        broadcastDispatcher.unregisterReceiver(receiver)
    }
}
