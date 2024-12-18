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

package com.android.systemui.common.ui.view

import android.view.Choreographer
import android.view.View
import com.android.app.tracing.coroutines.TrackTracer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** utilities related to [Choreographer]. */
interface ChoreographerUtils {
    /**
     * Waits until the next [view] doFrame is completed.
     *
     * Note that this is expected to work properly when called from any thread. If called during a
     * doFrame, it waits for the next one to be completed.
     *
     * This differs from [kotlinx.coroutines.android.awaitFrame] as it uses
     * [Handler.postAtFrontOfQueue] instead of [Handler.post] when called from a thread different
     * than the UI thread for that view. Using [Handler.post] might lead to posting the runnable
     * after a few frame, effectively missing the "next do frame".
     */
    suspend fun waitUntilNextDoFrameDone(view: View)
}

object ChoreographerUtilsImpl : ChoreographerUtils {
    private val t = TrackTracer("ChoreographerUtils")

    override suspend fun waitUntilNextDoFrameDone(view: View) {
        t.traceAsync("waitUntilNextDoFrameDone") { waitUntilNextDoFrameDoneTraced(view) }
    }

    suspend fun waitUntilNextDoFrameDoneTraced(view: View) {
        suspendCancellableCoroutine { cont ->
            val frameCallback =
                Choreographer.FrameCallback {
                    t.instant { "We're in doFrame, waiting for it to end." }
                    view.handler.postAtFrontOfQueue {
                        t.instant { "DoFrame ended." }
                        cont.resume(Unit)
                    }
                }
            view.runOnUiThreadUrgently {
                t.instant { "Waiting for next doFrame" }
                val choreographer = Choreographer.getInstance()
                cont.invokeOnCancellation { choreographer.removeFrameCallback(frameCallback) }
                choreographer.postFrameCallback(frameCallback)
            }
        }
    }

    /**
     * Execute [r] on the view UI thread, taking priority over everything else scheduled there. Runs
     * directly if we're already in the correct thread.
     */
    private fun View.runOnUiThreadUrgently(r: () -> Unit) {
        if (handler.looper.isCurrentThread) {
            r()
        } else {
            handler.postAtFrontOfQueue(r)
        }
    }
}
