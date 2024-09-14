/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.ipc

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

/** Rule for messenger service testing. */
open class MessengerServiceRule<C : MessengerServiceClient>(
    private val serviceClass: Class<out MessengerService>,
    val client: C,
) : TestWatcher() {
    val application: Application = ApplicationProvider.getApplicationContext()
    val isRobolectric = Build.FINGERPRINT.contains("robolectric")

    private var serviceController: ServiceController<out Service>? = null

    override fun starting(description: Description) {
        if (isRobolectric) {
            runBlocking { setupRobolectricService() }
        }
    }

    override fun finished(description: Description) {
        client.close()
        if (isRobolectric) {
            runBlocking {
                withContext(Dispatchers.Main) { serviceController?.run { unbind().destroy() } }
            }
        }
    }

    private suspend fun setupRobolectricService() {
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            throw IllegalStateException(
                "To avoid deadlock, run test with @LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)"
            )
        }
        withContext(Dispatchers.Main) {
            serviceController = Robolectric.buildService(serviceClass)
            val service = serviceController!!.create().get()
            Shadows.shadowOf(application).apply {
                setComponentNameAndServiceForBindService(
                    ComponentName(application, serviceClass),
                    service.onBind(Intent(application, serviceClass)),
                )
                setBindServiceCallsOnServiceConnectedDirectly(true)
                setUnbindServiceCallsOnServiceDisconnected(false)
            }
        }
    }
}
