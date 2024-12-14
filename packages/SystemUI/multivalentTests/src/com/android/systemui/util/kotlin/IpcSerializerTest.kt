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
 */

package com.android.systemui.util.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class IpcSerializerTest : SysuiTestCase() {

    private val serializer = IpcSerializer()

    @Ignore("b/253046405")
    @Test
    fun serializeManyIncomingIpcs(): Unit =
        runBlocking(Dispatchers.Main.immediate) {
            val processor = launch(start = CoroutineStart.LAZY) { serializer.process() }
            withContext(Dispatchers.IO) {
                val lastEvaluatedTime = AtomicLong(System.currentTimeMillis())
                // First, launch many serialization requests in parallel
                repeat(100_000) {
                    launch(Dispatchers.Unconfined) {
                        val enqueuedTime = System.currentTimeMillis()
                        serializer.runSerialized {
                            val last = lastEvaluatedTime.getAndSet(enqueuedTime)
                            assertTrue(
                                "expected $last less than or equal to $enqueuedTime ",
                                last <= enqueuedTime,
                            )
                        }
                    }
                }
                // Then, process them all in the order they came in.
                processor.start()
            }
            // All done, stop processing
            processor.cancel()
        }

    @Test(timeout = 5000)
    fun serializeOnOneThread_doesNotDeadlock() = runBlocking {
        val job = launch { serializer.process() }
        repeat(100) { serializer.runSerializedBlocking {} }
        job.cancel()
    }
}
