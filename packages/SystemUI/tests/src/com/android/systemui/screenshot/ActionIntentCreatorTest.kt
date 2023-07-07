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
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.`when` as whenever

@SmallTest
class ActionIntentCreatorTest : SysuiTestCase() {

    @Test
    fun testCreateShareIntent() {
        val uri = Uri.parse("content://fake")

        val output = ActionIntentCreator.createShareIntent(uri)

        assertThat(output.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertFlagsSet(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            output.flags
        )

        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(wrappedIntent?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(wrappedIntent?.data).isEqualTo(uri)
        assertThat(wrappedIntent?.type).isEqualTo("image/png")
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_SUBJECT)).isNull()
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_TEXT)).isNull()
        assertThat(wrappedIntent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            .isEqualTo(uri)
    }

    @Test
    fun testCreateShareIntentWithSubject() {
        val uri = Uri.parse("content://fake")
        val subject = "Example subject"

        val output = ActionIntentCreator.createShareIntentWithSubject(uri, subject)

        assertThat(output.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertFlagsSet(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            output.flags
        )

        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(wrappedIntent?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(wrappedIntent?.data).isEqualTo(uri)
        assertThat(wrappedIntent?.type).isEqualTo("image/png")
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo(subject)
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_TEXT)).isNull()
        assertThat(wrappedIntent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            .isEqualTo(uri)
    }

    @Test
    fun testCreateShareIntentWithExtraText() {
        val uri = Uri.parse("content://fake")
        val extraText = "Extra text"

        val output = ActionIntentCreator.createShareIntentWithExtraText(uri, extraText)

        assertThat(output.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertFlagsSet(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            output.flags
        )

        val wrappedIntent = output.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(wrappedIntent?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(wrappedIntent?.data).isEqualTo(uri)
        assertThat(wrappedIntent?.type).isEqualTo("image/png")
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_SUBJECT)).isNull()
        assertThat(wrappedIntent?.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(extraText)
        assertThat(wrappedIntent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            .isEqualTo(uri)
    }

    @Test
    fun testCreateEditIntent() {
        val uri = Uri.parse("content://fake")
        val context = mock<Context>()

        val output = ActionIntentCreator.createEditIntent(uri, context)

        assertThat(output.action).isEqualTo(Intent.ACTION_EDIT)
        assertThat(output.data).isEqualTo(uri)
        assertThat(output.type).isEqualTo("image/png")
        assertThat(output.component).isNull()
        val expectedFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertFlagsSet(expectedFlags, output.flags)
    }

    @Test
    fun testCreateEditIntent_withEditor() {
        val uri = Uri.parse("content://fake")
        val context = mock<Context>()
        var component = ComponentName("com.android.foo", "com.android.foo.Something")

        whenever(context.getString(eq(R.string.config_screenshotEditor)))
            .thenReturn(component.flattenToString())

        val output = ActionIntentCreator.createEditIntent(uri, context)

        assertThat(output.component).isEqualTo(component)
    }

    private fun assertFlagsSet(expected: Int, observed: Int) {
        assertThat(observed and expected).isEqualTo(expected)
    }
}
