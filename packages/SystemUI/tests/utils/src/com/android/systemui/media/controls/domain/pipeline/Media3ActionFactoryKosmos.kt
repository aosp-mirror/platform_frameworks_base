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

package com.android.systemui.media.controls.domain.pipeline

import android.content.applicationContext
import android.os.Bundle
import android.os.Handler
import android.os.looper
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.android.systemui.Flags
import com.android.systemui.graphics.imageLoader
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.shared.mediaLogger
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.fakeSessionTokenFactory
import com.android.systemui.util.concurrency.execution
import com.google.common.collect.ImmutableList
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Set up fake [Media3ActionFactory]. Note that tests using this fake will need to be
 * annotated @RunWithLooper
 */
var Kosmos.media3ActionFactory: Media3ActionFactory by
    Kosmos.Fixture {
        if (Flags.mediaControlsButtonMedia3()) {
            val customLayout = ImmutableList.of<CommandButton>()
            val media3Controller =
                mock<MediaController>().also {
                    whenever(it.customLayout).thenReturn(customLayout)
                    whenever(it.sessionExtras).thenReturn(Bundle())
                    whenever(it.isCommandAvailable(any())).thenReturn(true)
                    whenever(it.isSessionCommandAvailable(any<SessionCommand>())).thenReturn(true)
                }
            fakeMediaControllerFactory.setMedia3Controller(media3Controller)
            fakeSessionTokenFactory.setMedia3SessionToken(mock<SessionToken>())
        }

        val runnableCaptor = argumentCaptor<Runnable>()
        val handler =
            mock<Handler> {
                on { post(runnableCaptor.capture()) } doAnswer
                    {
                        runnableCaptor.lastValue.run()
                        true
                    }
            }
        Media3ActionFactory(
            context = applicationContext,
            imageLoader = imageLoader,
            controllerFactory = fakeMediaControllerFactory,
            tokenFactory = fakeSessionTokenFactory,
            logger = mediaLogger,
            looper = looper,
            handler = handler,
            bgScope = testScope,
            execution = execution,
        )
    }
