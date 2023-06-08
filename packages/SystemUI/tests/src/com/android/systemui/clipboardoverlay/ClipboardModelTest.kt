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
package com.android.systemui.clipboardoverlay

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.whenever
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ClipboardModelTest : SysuiTestCase() {
    @Mock private lateinit var mClipboardUtils: ClipboardOverlayUtils
    @Mock private lateinit var mMockContext: Context
    @Mock private lateinit var mMockContentResolver: ContentResolver
    private lateinit var mSampleClipData: ClipData

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mSampleClipData = ClipData("Test", arrayOf("text/plain"), ClipData.Item("Test Item"))
    }

    @Test
    fun test_textClipData() {
        val source = "test source"
        val model = ClipboardModel.fromClipData(mContext, mClipboardUtils, mSampleClipData, source)
        assertEquals(mSampleClipData, model.clipData)
        assertEquals(source, model.source)
        assertEquals(ClipboardModel.Type.TEXT, model.type)
        assertEquals(mSampleClipData.getItemAt(0).text, model.text)
        assertEquals(mSampleClipData.getItemAt(0).textLinks, model.textLinks)
        assertEquals(mSampleClipData.getItemAt(0).uri, model.uri)
        assertFalse(model.isSensitive)
        assertFalse(model.isRemote)
        assertNull(model.loadThumbnail(mContext))
    }

    @Test
    fun test_sensitiveExtra() {
        val description = mSampleClipData.description
        val b = PersistableBundle()
        b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        description.extras = b
        val data = ClipData(description, mSampleClipData.getItemAt(0))
        val (_, _, _, _, _, _, sensitive) =
            ClipboardModel.fromClipData(mContext, mClipboardUtils, data, "")
        assertTrue(sensitive)
    }

    @Test
    fun test_remoteExtra() {
        whenever(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true)
        val model = ClipboardModel.fromClipData(mContext, mClipboardUtils, mSampleClipData, "")
        assertTrue(model.isRemote)
    }

    @Test
    @Throws(IOException::class)
    fun test_imageClipData() {
        val testBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        whenever(mMockContext.contentResolver).thenReturn(mMockContentResolver)
        whenever(mMockContext.resources).thenReturn(mContext.resources)
        whenever(mMockContentResolver.loadThumbnail(any(), any(), any())).thenReturn(testBitmap)
        whenever(mMockContentResolver.getType(any())).thenReturn("image")
        val imageClipData =
            ClipData("Test", arrayOf("text/plain"), ClipData.Item(Uri.parse("test")))
        val model = ClipboardModel.fromClipData(mMockContext, mClipboardUtils, imageClipData, "")
        assertEquals(ClipboardModel.Type.IMAGE, model.type)
        assertEquals(testBitmap, model.loadThumbnail(mMockContext))
    }

    @Test
    @Throws(IOException::class)
    fun test_imageClipData_loadFailure() {
        whenever(mMockContext.contentResolver).thenReturn(mMockContentResolver)
        whenever(mMockContext.resources).thenReturn(mContext.resources)
        whenever(mMockContentResolver.loadThumbnail(any(), any(), any())).thenThrow(IOException())
        whenever(mMockContentResolver.getType(any())).thenReturn("image")
        val imageClipData =
            ClipData("Test", arrayOf("text/plain"), ClipData.Item(Uri.parse("test")))
        val model = ClipboardModel.fromClipData(mMockContext, mClipboardUtils, imageClipData, "")
        assertEquals(ClipboardModel.Type.IMAGE, model.type)
        assertNull(model.loadThumbnail(mMockContext))
    }
}
