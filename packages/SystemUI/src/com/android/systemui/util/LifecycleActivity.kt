/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.Activity
import android.os.Bundle
import android.os.PersistableBundle
import androidx.lifecycle.LifecycleOwner
import com.android.settingslib.core.lifecycle.Lifecycle

open class LifecycleActivity : Activity(), LifecycleOwner {

    private val lifecycle = Lifecycle(this)

    override fun getLifecycle() = lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        lifecycle.onAttach(this)
        lifecycle.onCreate(savedInstanceState)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        super.onCreate(savedInstanceState)
    }

    override fun onCreate(
        savedInstanceState: Bundle?,
        persistentState: PersistableBundle?
    ) {
        lifecycle.onAttach(this)
        lifecycle.onCreate(savedInstanceState)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        super.onCreate(savedInstanceState, persistentState)
    }

    override fun onStart() {
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        super.onStart()
    }

    override fun onResume() {
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        super.onResume()
    }

    override fun onPause() {
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}