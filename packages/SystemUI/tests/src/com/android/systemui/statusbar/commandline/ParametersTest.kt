package com.android.systemui.statusbar.commandline

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@SmallTest
class ParametersTest : SysuiTestCase() {
    @Test
    fun singleArgOptional_returnsNullBeforeParse() {
        val optional by SingleArgParamOptional(longName = "longName", valueParser = Type.Int)
        assertThat(optional).isNull()
    }

    @Test
    fun singleArgOptional_returnsParsedValue() {
        val param = SingleArgParamOptional(longName = "longName", valueParser = Type.Int)
        param.parseArgsFromIter(listOf("3").listIterator())
        val optional by param
        assertThat(optional).isEqualTo(3)
    }

    @Test
    fun singleArgRequired_throwsBeforeParse() {
        val req by SingleArgParam(longName = "param", valueParser = Type.Boolean)
        assertThrows(IllegalStateException::class.java) { req }
    }

    @Test
    fun singleArgRequired_returnsParsedValue() {
        val param = SingleArgParam(longName = "param", valueParser = Type.Boolean)
        param.parseArgsFromIter(listOf("true").listIterator())
        val req by param
        assertTrue(req)
    }

    @Test
    fun param_handledAfterParse() {
        val optParam = SingleArgParamOptional(longName = "string1", valueParser = Type.String)
        val reqParam = SingleArgParam(longName = "string2", valueParser = Type.Float)

        assertFalse(optParam.handled)
        assertFalse(reqParam.handled)

        optParam.parseArgsFromIter(listOf("test").listIterator())
        reqParam.parseArgsFromIter(listOf("1.23").listIterator())

        assertTrue(optParam.handled)
        assertTrue(reqParam.handled)
    }
}
