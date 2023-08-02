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

package com.android.systemui.screenshot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.truth.content.IntentSubject.assertThat
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.mockito.Mockito.`when` as whenever

@SmallTest
class ActionIntentCreatorTest : SysuiTestCase() {

    @Test
    fun testCreateShare() {
        val uri = Uri.parse("content://fake")

        val output = ActionIntentCreator.createShare(uri)

        assertThat(output).hasAction(Intent.ACTION_CHOOSER)
        assertThat(output)
            .hasFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        assertThat(output).extras().parcelable<Intent>(Intent.EXTRA_INTENT).isNotNull()
        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

        assertThat(wrappedIntent).hasAction(Intent.ACTION_SEND)
        assertThat(wrappedIntent).hasData(uri)
        assertThat(wrappedIntent).hasType("image/png")
        assertThat(wrappedIntent).extras().doesNotContainKey(Intent.EXTRA_SUBJECT)
        assertThat(wrappedIntent).extras().doesNotContainKey(Intent.EXTRA_TEXT)
        assertThat(wrappedIntent).extras().parcelable<Uri>(Intent.EXTRA_STREAM).isEqualTo(uri)
    }

    @Test
    fun testCreateShare_embeddedUserIdRemoved() {
        val uri = Uri.parse("content://555@fake")

        val output = ActionIntentCreator.createShare(uri)

        assertThat(output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            .hasData(Uri.parse("content://fake"))
    }

    @Test
    fun testCreateShareWithSubject() {
        val uri = Uri.parse("content://fake")
        val subject = "Example subject"

        val output = ActionIntentCreator.createShareWithSubject(uri, subject)

        assertThat(output).hasAction(Intent.ACTION_CHOOSER)
        assertThat(output)
            .hasFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(wrappedIntent).hasAction(Intent.ACTION_SEND)
        assertThat(wrappedIntent).hasData(uri)
        assertThat(wrappedIntent).hasType("image/png")
        assertThat(wrappedIntent).extras().string(Intent.EXTRA_SUBJECT).isEqualTo(subject)
        assertThat(wrappedIntent).extras().doesNotContainKey(Intent.EXTRA_TEXT)
        assertThat(wrappedIntent).extras().parcelable<Uri>(Intent.EXTRA_STREAM).isEqualTo(uri)
    }

    @Test
    fun testCreateShareWithText() {
        val uri = Uri.parse("content://fake")
        val extraText = "Extra text"

        val output = ActionIntentCreator.createShareWithText(uri, extraText)

        assertThat(output).hasAction(Intent.ACTION_CHOOSER)
        assertThat(output)
            .hasFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(wrappedIntent).hasAction(Intent.ACTION_SEND)
        assertThat(wrappedIntent).hasData(uri)
        assertThat(wrappedIntent).hasType("image/png")
        assertThat(wrappedIntent).extras().doesNotContainKey(Intent.EXTRA_SUBJECT)
        assertThat(wrappedIntent).extras().string(Intent.EXTRA_TEXT).isEqualTo(extraText)
        assertThat(wrappedIntent).extras().parcelable<Uri>(Intent.EXTRA_STREAM).isEqualTo(uri)
    }

    @Test
    fun testCreateEdit() {
        val uri = Uri.parse("content://fake")
        val context = mock<Context>()

        whenever(context.getString(eq(R.string.config_screenshotEditor))).thenReturn("")

        val output = ActionIntentCreator.createEdit(uri, context)

        assertThat(output).hasAction(Intent.ACTION_EDIT)
        assertThat(output).hasData(uri)
        assertThat(output).hasType("image/png")
        assertWithMessage("getComponent()").that(output.component).isNull()
        assertThat(output)
            .hasFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
    }

    @Test
    fun testCreateEdit_embeddedUserIdRemoved() {
        val uri = Uri.parse("content://555@fake")
        val context = mock<Context>()
        whenever(context.getString(eq(R.string.config_screenshotEditor))).thenReturn("")

        val output = ActionIntentCreator.createEdit(uri, context)

        assertThat(output).hasData(Uri.parse("content://fake"))
    }

    @Test
    fun testCreateEdit_withEditor() {
        val uri = Uri.parse("content://fake")
        val context = mock<Context>()
        val component = ComponentName("com.android.foo", "com.android.foo.Something")

        whenever(context.getString(eq(R.string.config_screenshotEditor)))
            .thenReturn(component.flattenToString())

        val output = ActionIntentCreator.createEdit(uri, context)

        assertThat(output).hasComponent(component)
    }
}
