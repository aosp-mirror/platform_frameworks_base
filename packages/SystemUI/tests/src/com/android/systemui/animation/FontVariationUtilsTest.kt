package com.android.systemui.animation

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG_WGHT = "wght"
private const val TAG_WDTH = "wdth"
private const val TAG_OPSZ = "opsz"
private const val TAG_ROND = "ROND"

@RunWith(AndroidTestingRunner::class)
@SmallTest
class FontVariationUtilsTest : SysuiTestCase() {
    @Test
    fun testUpdateFontVariation_getCorrectFvarStr() {
        val fontVariationUtils = FontVariationUtils()
        val initFvar =
            fontVariationUtils.updateFontVariation(
                weight = 100,
                width = 100,
                opticalSize = -1,
                roundness = 100
            )
        Assert.assertEquals("'$TAG_WGHT' 100, '$TAG_WDTH' 100, '$TAG_ROND' 100", initFvar)
        val updatedFvar =
            fontVariationUtils.updateFontVariation(
                weight = 200,
                width = 100,
                opticalSize = 0,
                roundness = 100
            )
        Assert.assertEquals(
            "'$TAG_WGHT' 200, '$TAG_WDTH' 100, '$TAG_OPSZ' 0, '$TAG_ROND' 100",
            updatedFvar
        )
    }

    @Test
    fun testStyleValueUnchange_getBlankStr() {
        val fontVariationUtils = FontVariationUtils()
        fontVariationUtils.updateFontVariation(
            weight = 100,
            width = 100,
            opticalSize = 0,
            roundness = 100
        )
        val updatedFvar1 =
            fontVariationUtils.updateFontVariation(
                weight = 100,
                width = 100,
                opticalSize = 0,
                roundness = 100
            )
        Assert.assertEquals("", updatedFvar1)
        val updatedFvar2 = fontVariationUtils.updateFontVariation()
        Assert.assertEquals("", updatedFvar2)
    }
}
