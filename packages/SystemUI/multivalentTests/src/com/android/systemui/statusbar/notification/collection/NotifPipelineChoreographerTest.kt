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

package com.android.systemui.statusbar.notification.collection

import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import dagger.BindsInstance
import dagger.Component
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotifPipelineChoreographerTest : SysuiTestCase() {

    val viewChoreographer: Choreographer = mock()
    val timeoueSubscription: Runnable = mock()
    val executor: DelayableExecutor = mock<DelayableExecutor>().also {
        whenever(it.executeDelayed(any(), anyLong())).thenReturn(timeoueSubscription)
    }

    val pipelineChoreographer: NotifPipelineChoreographer =
        DaggerNotifPipelineChoreographerTestComponent.factory()
                .create(viewChoreographer, executor)
                .choreographer

    @Test
    fun scheduleThenEvalFrameCallback() {
        // GIVEN a registered eval listener and scheduled choreographer
        var hasEvaluated = false
        pipelineChoreographer.addOnEvalListener {
            hasEvaluated = true
        }
        pipelineChoreographer.schedule()
        val frameCallback: Choreographer.FrameCallback = withArgCaptor {
            verify(viewChoreographer).postFrameCallback(capture())
        }
        // WHEN the choreographer would invoke its callback
        frameCallback.doFrame(0)
        // THEN the choreographer would evaluate, and the timeoutSubscription would have been
        // cancelled
        assertTrue(hasEvaluated)
        verify(timeoueSubscription).run()
    }

    @Test
    fun scheduleThenEvalTimeoutCallback() {
        // GIVEN a registered eval listener and scheduled choreographer
        var hasEvaluated = false
        pipelineChoreographer.addOnEvalListener {
            hasEvaluated = true
        }
        pipelineChoreographer.schedule()
        val frameCallback: Choreographer.FrameCallback = withArgCaptor {
            verify(viewChoreographer).postFrameCallback(capture())
        }
        val runnable: Runnable = withArgCaptor {
            verify(executor).executeDelayed(capture(), anyLong())
        }
        // WHEN the executor would invoke its callback (indicating a timeout)
        runnable.run()
        // THEN the choreographer would evaluate, and the FrameCallback would have been unregistered
        assertTrue(hasEvaluated)
        verify(viewChoreographer).removeFrameCallback(frameCallback)
    }

    @Test
    fun scheduleThenCancel() {
        // GIVEN a scheduled choreographer
        pipelineChoreographer.schedule()
        val frameCallback: Choreographer.FrameCallback = withArgCaptor {
            verify(viewChoreographer).postFrameCallback(capture())
        }
        // WHEN the scheduled run is cancelled
        pipelineChoreographer.cancel()
        // THEN both the FrameCallback is unregistered and the timeout subscription is cancelled.
        verify(viewChoreographer).removeFrameCallback(frameCallback)
        verify(timeoueSubscription).run()
    }
}

@SysUISingleton
@Component(modules = [NotifPipelineChoreographerModule::class])
interface NotifPipelineChoreographerTestComponent {

    val choreographer: NotifPipelineChoreographer

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance viewChoreographer: Choreographer,
            @BindsInstance @Main executor: DelayableExecutor
        ): NotifPipelineChoreographerTestComponent
    }
}
