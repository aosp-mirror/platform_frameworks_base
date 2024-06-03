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

package com.android.systemui.jank

import android.os.HandlerThread
import android.os.fakeExecutorHandler
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import org.mockito.Mockito

val Kosmos.interactionJankMonitor by
    Fixture<InteractionJankMonitor> {
        val worker =
            Mockito.mock(HandlerThread::class.java).also { worker ->
                Mockito.doAnswer {
                        fakeExecutorHandler.also { handler ->
                            Mockito.doAnswer {
                                    // TODO(b/333927129): Should return `android.os.looper` instead
                                    null
                                }
                                .`when`(handler)
                                .looper
                        }
                    }
                    .`when`(worker)
                    .threadHandler
            }

        // Return a `spy` so that tests can verify method calls
        Mockito.spy(
            object : InteractionJankMonitor(worker) {
                override fun shouldMonitor(): Boolean = true
                override fun begin(builder: Configuration.Builder): Boolean = true
                override fun begin(v: View?, cujType: Int): Boolean = true
                override fun end(cujType: Int): Boolean = true
                override fun cancel(cujType: Int): Boolean = true
                override fun cancel(cujType: Int, reason: Int) = true
            }
        )
    }
