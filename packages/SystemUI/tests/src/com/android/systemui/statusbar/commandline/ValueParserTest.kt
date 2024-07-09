package com.android.systemui.statusbar.commandline

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ValueParserTest : SysuiTestCase() {
    @Test
    fun parseString() {
        assertThat(Type.String.parseValue("test")).isEqualTo(Result.success("test"))
    }

    @Test
    fun parseInt() {
        assertThat(Type.Int.parseValue("123")).isEqualTo(Result.success(123))

        assertTrue(Type.Int.parseValue("not an Int").isFailure)
    }

    @Test
    fun parseFloat() {
        assertThat(Type.Float.parseValue("1.23")).isEqualTo(Result.success(1.23f))

        assertTrue(Type.Int.parseValue("not a Float").isFailure)
    }

    @Test
    fun parseBoolean() {
        assertThat(Type.Boolean.parseValue("true")).isEqualTo(Result.success(true))
        assertThat(Type.Boolean.parseValue("false")).isEqualTo(Result.success(false))

        assertTrue(Type.Boolean.parseValue("not a Boolean").isFailure)
    }

    @Test
    fun mapToComplexType() {
        val parseSquare = Type.Int.map { Rect(it, it, it, it) }

        assertThat(parseSquare.parseValue("10")).isEqualTo(Result.success(Rect(10, 10, 10, 10)))
    }

    @Test
    fun mapToFallibleComplexType() {
        val fallibleParseSquare =
            Type.Int.map {
                if (it > 0) {
                    Rect(it, it, it, it)
                } else {
                    null
                }
            }

        assertThat(fallibleParseSquare.parseValue("10"))
            .isEqualTo(Result.success(Rect(10, 10, 10, 10)))
        assertTrue(fallibleParseSquare.parseValue("-10").isFailure)
    }
}
