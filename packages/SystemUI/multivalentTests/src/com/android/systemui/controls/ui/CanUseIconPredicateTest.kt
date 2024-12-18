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

package com.android.systemui.controls.ui

import android.content.ContentProvider
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CanUseIconPredicateTest : SysuiTestCase() {

    private companion object {
        const val USER_ID_1 = 1
        const val USER_ID_2 = 2
    }

    val underTest: CanUseIconPredicate = CanUseIconPredicate(USER_ID_1)

    @Test
    fun testReturnsFalseForDifferentUser() {
        val user2Icon =
            Icon.createWithContentUri(
                ContentProvider.createContentUriForUser(
                    Uri.parse("content://test"),
                    UserHandle.of(USER_ID_2)
                )
            )

        assertThat(underTest.invoke(user2Icon)).isFalse()
    }

    @Test
    fun testReturnsTrueForCorrectUser() {
        val user1Icon =
            Icon.createWithContentUri(
                ContentProvider.createContentUriForUser(
                    Uri.parse("content://test"),
                    UserHandle.of(USER_ID_1)
                )
            )

        assertThat(underTest.invoke(user1Icon)).isTrue()
    }

    @Test
    fun testReturnsTrueForUriWithoutUser() {
        val uriIcon = Icon.createWithContentUri(Uri.parse("content://test"))

        assertThat(underTest.invoke(uriIcon)).isTrue()
    }

    @Test
    fun testReturnsTrueForNonUriIcon() {
        val bitmapIcon = Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        assertThat(underTest.invoke(bitmapIcon)).isTrue()
    }
}
