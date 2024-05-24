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

package android.text

import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TextLineJustificationTest {

    @Rule
    @JvmField
    val mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    private val PAINT = TextPaint().apply {
        textSize = 10f // make 1em = 10px
    }

    private fun makeTextLine(cs: CharSequence, paint: TextPaint) = TextLine.obtain().apply {
        set(paint, cs, 0, cs.length, Layout.DIR_LEFT_TO_RIGHT, Layout.DIRS_ALL_LEFT_TO_RIGHT, false,
                null, 0, 0, false)
    }

    private fun getClusterCount(cs: CharSequence, paint: TextPaint) = TextLine.LineInfo().apply {
        makeTextLine(cs, paint).also {
            it.metrics(null, null, false, this)
            TextLine.recycle(it)
        }
    }.clusterCount

    fun justifyTest_WithoutJustify() {
        val line = "Hello, World."
        val tl = makeTextLine(line, PAINT)

        // Without calling justify method, justifying should be false and all added spaces should
        // be zeros.
        assertThat(tl.isJustifying).isFalse()
        assertThat(tl.addedWordSpacingInPx).isEqualTo(0)
        assertThat(tl.addedLetterSpacingInPx).isEqualTo(0)
    }

    @Test
    fun justifyTest_IntrCharacter_Latin() {
        val line = "Hello, World."
        val clusterCount = getClusterCount(line, PAINT)
        val originalWidth = Layout.getDesiredWidth(line, PAINT)
        val extraWidth = 100f

        val tl = makeTextLine(line, PAINT)
        tl.justify(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, originalWidth + extraWidth)

        assertThat(tl.isJustifying).isTrue()
        assertThat(tl.addedWordSpacingInPx).isEqualTo(0)
        assertThat(tl.addedLetterSpacingInPx).isEqualTo(extraWidth / (clusterCount - 1))

        TextLine.recycle(tl)
    }

    @Test
    fun justifyTest_IntrCharacter_Japanese() {
        val line = "\u672C\u65E5\u306F\u6674\u5929\u306A\u308A\u3002"
        val clusterCount = getClusterCount(line, PAINT)
        val originalWidth = Layout.getDesiredWidth(line, PAINT)
        val extraWidth = 100f

        val tl = makeTextLine(line, PAINT)
        tl.justify(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, originalWidth + extraWidth)

        assertThat(tl.isJustifying).isTrue()
        assertThat(tl.addedWordSpacingInPx).isEqualTo(0)
        assertThat(tl.addedLetterSpacingInPx).isEqualTo(extraWidth / (clusterCount - 1))

        TextLine.recycle(tl)
    }

    @Test
    fun justifyTest_IntrWord_Latin() {
        val line = "Hello, World."
        val originalWidth = Layout.getDesiredWidth(line, PAINT)
        val extraWidth = 100f

        val tl = makeTextLine(line, PAINT)
        tl.justify(Layout.JUSTIFICATION_MODE_INTER_WORD, originalWidth + extraWidth)

        assertThat(tl.isJustifying).isTrue()
        // This text contains only one whitespace, so word spacing should be same to the extraWidth.
        assertThat(tl.addedWordSpacingInPx).isEqualTo(extraWidth)
        assertThat(tl.addedLetterSpacingInPx).isEqualTo(0)

        TextLine.recycle(tl)
    }

    @Test
    fun justifyTest_IntrWord_Japanese() {
        val line = "\u672C\u65E5\u306F\u6674\u0020\u5929\u306A\u308A\u3002"
        val originalWidth = Layout.getDesiredWidth(line, PAINT)
        val extraWidth = 100f

        val tl = makeTextLine(line, PAINT)
        tl.justify(Layout.JUSTIFICATION_MODE_INTER_WORD, originalWidth + extraWidth)

        assertThat(tl.isJustifying).isTrue()
        // This text contains only one whitespace, so word spacing should be same to the extraWidth.
        assertThat(tl.addedWordSpacingInPx).isEqualTo(extraWidth)
        assertThat(tl.addedLetterSpacingInPx).isEqualTo(0)

        TextLine.recycle(tl)
    }
}
