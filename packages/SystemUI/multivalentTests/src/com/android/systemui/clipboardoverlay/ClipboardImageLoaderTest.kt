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
package com.android.systemui.clipboardoverlay

import android.content.ContentResolver
import android.content.Context
import android.content.pm.UserInfo
import android.net.Uri
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeUserTracker
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ClipboardImageLoaderTest : SysuiTestCase() {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockContentResolver: ContentResolver
    @Mock private lateinit var mockSecondaryContentResolver: ContentResolver

    private lateinit var clipboardImageLoader: ClipboardImageLoader
    private var fakeUserTracker: FakeUserTracker =
        FakeUserTracker(userContentResolverProvider = { mockContentResolver })

    private val userInfos = listOf(UserInfo(0, "system", 0), UserInfo(50, "secondary", 0))

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        fakeUserTracker.set(userInfos, 0)
    }

    @Test
    @Throws(IOException::class)
    @DisableFlags(FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    fun test_imageLoadSuccess_legacy() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        fakeUserTracker =
            FakeUserTracker(userContentResolverProvider = { mockSecondaryContentResolver })
        fakeUserTracker.set(userInfos, 1)

        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever<ContentResolver?>(mockContext.contentResolver)
            .thenReturn(mockSecondaryContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        clipboardImageLoader.load(testUri)

        verify(mockSecondaryContentResolver).loadThumbnail(eq(testUri), any(), any())
    }

    @Test
    @Throws(IOException::class)
    @EnableFlags(FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    fun test_imageLoadSuccess() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        fakeUserTracker =
            FakeUserTracker(userContentResolverProvider = { mockSecondaryContentResolver })
        fakeUserTracker.set(userInfos, 1)

        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever(mockContext.resources).thenReturn(context.resources)

        clipboardImageLoader.load(testUri)

        verify(mockSecondaryContentResolver).loadThumbnail(eq(testUri), any(), any())
    }

    @Test
    @Throws(IOException::class)
    fun test_imageLoadFailure() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        clipboardImageLoader =
            ClipboardImageLoader(
                mockContext,
                fakeUserTracker,
                testDispatcher,
                CoroutineScope(testDispatcher),
            )
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        val res = clipboardImageLoader.load(testUri)

        verify(mockContentResolver).loadThumbnail(eq(testUri), any(), any())
        assertNull(res)
    }
}
