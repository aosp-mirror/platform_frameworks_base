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
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.jank.InteractionJankMonitor.Configuration.Builder
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.util.mockito.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy

val Kosmos.interactionJankMonitor by
    Fixture<InteractionJankMonitor> {
        spy(InteractionJankMonitor(HandlerThread("InteractionJankMonitor-Kosmos"))).apply {
            doReturn(true).`when`(this).shouldMonitor()
            doReturn(true).`when`(this).begin(any(), anyInt())
            doReturn(true).`when`(this).begin(any<Builder>())
            doReturn(true).`when`(this).end(anyInt())
            doReturn(true).`when`(this).cancel(anyInt())
            doReturn(true).`when`(this).cancel(anyInt(), anyInt())
        }
    }
