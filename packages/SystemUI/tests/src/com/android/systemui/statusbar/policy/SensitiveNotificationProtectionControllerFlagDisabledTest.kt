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

package com.android.systemui.statusbar.policy

import android.app.IActivityManager
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.server.notification.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@DisableFlags(Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING)
class SensitiveNotificationProtectionControllerFlagDisabledTest : SysuiTestCase() {
    private val logger = SensitiveNotificationProtectionControllerLogger(logcatLogBuffer())

    @Mock private lateinit var handler: Handler
    @Mock private lateinit var activityManager: IActivityManager
    @Mock private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var controller: SensitiveNotificationProtectionControllerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller =
            SensitiveNotificationProtectionControllerImpl(
                mContext,
                FakeGlobalSettings(),
                mediaProjectionManager,
                activityManager,
                handler,
                FakeExecutor(FakeSystemClock()),
                logger
            )
    }

    @Test
    fun init_noRegisterMediaProjectionManagerCallback() {
        verifyZeroInteractions(mediaProjectionManager)
    }
}
