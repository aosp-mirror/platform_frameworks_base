package com.android.systemui.util.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ShapeDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class DrawableSizeTest : SysuiTestCase() {

    lateinit var resources: Resources

    @Before
    fun setUp() {
        resources = context.resources
    }

    @Test
    fun testDownscaleToSize_drawableZeroSize_unchanged() {
        val drawable = ShapeDrawable()
        val result = DrawableSize.downscaleToSize(resources, drawable, 100, 100)
        assertThat(result).isSameInstanceAs(drawable)
    }

    @Test
    fun testDownscaleToSize_drawableSmallerThanRequirement_unchanged() {
        val drawable =
            BitmapDrawable(
                resources,
                Bitmap.createBitmap(resources.displayMetrics, 150, 150, Bitmap.Config.ARGB_8888)
            )
        val result = DrawableSize.downscaleToSize(resources, drawable, 300, 300)
        assertThat(result).isSameInstanceAs(drawable)
    }

    @Test
    fun testDownscaleToSize_drawableLargerThanRequirementWithDensity_resized() {
        // This bitmap would actually fail to resize if the method doesn't check for
        // bitmap dimensions inside drawable.
        val drawable =
            BitmapDrawable(
                resources,
                Bitmap.createBitmap(resources.displayMetrics, 150, 75, Bitmap.Config.ARGB_8888)
            )

        val result = DrawableSize.downscaleToSize(resources, drawable, 75, 75)
        assertThat(result).isNotSameInstanceAs(drawable)
        assertThat(result.intrinsicWidth).isEqualTo(75)
        assertThat(result.intrinsicHeight).isEqualTo(37)
    }

    @Test
    fun testDownscaleToSize_drawableAnimated_unchanged() {
        val drawable =
            resources.getDrawable(android.R.drawable.stat_sys_download, resources.newTheme())
        val result = DrawableSize.downscaleToSize(resources, drawable, 1, 1)
        assertThat(result).isSameInstanceAs(drawable)
    }
}
