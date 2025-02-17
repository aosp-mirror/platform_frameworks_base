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

package com.android.systemui.graphics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.rule.provider.ProviderTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

const val AUTHORITY = "exception.provider.authority"
val TEST_URI = Uri.Builder().scheme("content").authority(AUTHORITY).path("path").build()

@SmallTest
@kotlinx.coroutines.ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ImageLoaderContentProviderTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val mockContext = mock<Context>()
    private lateinit var imageLoader: ImageLoader

    @Rule
    @JvmField
    @Suppress("DEPRECATION")
    public val providerTestRule =
        ProviderTestRule.Builder(ExceptionThrowingContentProvider::class.java, AUTHORITY).build()

    @Before
    fun setUp() {
        whenever(mockContext.contentResolver).thenReturn(providerTestRule.resolver)
        imageLoader = ImageLoader(mockContext, kosmos.testDispatcher)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadFromTestContentProvider_throwsException() {
        // This checks if the resolution actually throws the exception from test provider.
        mockContext.contentResolver.query(TEST_URI, null, null, null)
    }

    @Test
    fun loadFromRuntimeExceptionThrowingProvider_returnsNull() =
        testScope.runTest { assertThat(imageLoader.loadBitmap(ImageLoader.Uri(TEST_URI))).isNull() }
}

class ExceptionThrowingContentProvider : ContentProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        throw IllegalArgumentException("Test exception")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalArgumentException("Test exception")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalArgumentException("Test exception")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalArgumentException("Test exception")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw IllegalArgumentException("Test exception")
    }

    override fun onCreate(): Boolean = true
}
