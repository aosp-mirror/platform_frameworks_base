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
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.whenever
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ClipboardImageLoaderTest : SysuiTestCase() {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockContentResolver: ContentResolver

    private lateinit var clipboardImageLoader: ClipboardImageLoader

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    @Throws(IOException::class)
    fun test_imageLoadSuccess() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        clipboardImageLoader =
            ClipboardImageLoader(mockContext, testDispatcher, CoroutineScope(testDispatcher))
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        clipboardImageLoader.load(testUri)

        verify(mockContentResolver).loadThumbnail(eq(testUri), any(), any())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @Throws(IOException::class)
    fun test_imageLoadFailure() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)
        clipboardImageLoader =
            ClipboardImageLoader(mockContext, testDispatcher, CoroutineScope(testDispatcher))
        val testUri = Uri.parse("testUri")
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        whenever(mockContext.resources).thenReturn(context.resources)

        val res = clipboardImageLoader.load(testUri)

        verify(mockContentResolver).loadThumbnail(eq(testUri), any(), any())
        assertNull(res)
    }
}
