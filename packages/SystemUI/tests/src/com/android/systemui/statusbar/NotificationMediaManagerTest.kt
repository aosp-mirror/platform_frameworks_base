/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.whenever
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Temporary test for the lock screen live wallpaper project.
 *
 * TODO(b/273443374): remove this test
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationMediaManagerTest : SysuiTestCase() {

    @Mock private lateinit var notificationMediaManager: NotificationMediaManager

    @Mock private lateinit var mockBackDropView: BackDropView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doCallRealMethod()
            .whenever(notificationMediaManager)
            .updateMediaMetaData(anyBoolean(), anyBoolean())
        doReturn(mockBackDropView).whenever(notificationMediaManager).backDropView
    }

    @After fun tearDown() {}

    /** Check that updateMediaMetaData is a no-op with mIsLockscreenLiveWallpaperEnabled = true */
    @Test
    fun testUpdateMediaMetaDataDisabled() {
        notificationMediaManager.mIsLockscreenLiveWallpaperEnabled = true
        for (metaDataChanged in listOf(true, false)) {
            for (allowEnterAnimation in listOf(true, false)) {
                notificationMediaManager.updateMediaMetaData(metaDataChanged, allowEnterAnimation)
                verify(notificationMediaManager, never()).mediaMetadata
            }
        }
    }
}
