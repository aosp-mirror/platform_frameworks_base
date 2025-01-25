package com.android.systemui.animation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
                roundness = 100,
            )
        Assert.assertEquals(
            "'${GSFAxes.WEIGHT}' 100, '${GSFAxes.WIDTH}' 100, '${GSFAxes.ROUND}' 100",
            initFvar,
        )
        val updatedFvar =
            fontVariationUtils.updateFontVariation(
                weight = 200,
                width = 100,
                opticalSize = 0,
                roundness = 100,
            )
        Assert.assertEquals(
            "'${GSFAxes.WEIGHT}' 200, '${GSFAxes.WIDTH}' 100, '${GSFAxes.OPTICAL_SIZE}' 0, '${GSFAxes.ROUND}' 100",
            updatedFvar,
        )
    }

    @Test
    fun testStyleValueUnchange_getBlankStr() {
        val fontVariationUtils = FontVariationUtils()
        fontVariationUtils.updateFontVariation(
            weight = 100,
            width = 100,
            opticalSize = 0,
            roundness = 100,
        )
        val updatedFvar1 =
            fontVariationUtils.updateFontVariation(
                weight = 100,
                width = 100,
                opticalSize = 0,
                roundness = 100,
            )
        Assert.assertEquals("", updatedFvar1)
        val updatedFvar2 = fontVariationUtils.updateFontVariation()
        Assert.assertEquals("", updatedFvar2)
    }
}
