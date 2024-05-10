/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.app.IUriGrantsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Arrays

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class TileRequestDialogTest : SysuiTestCase() {

    companion object {
        private const val APP_NAME = "App name"
        private const val LABEL = "Label"
        private const val PACKAGE = "package"
        private const val UID = 12345
        private val DEFAULT_ICON = R.drawable.android
    }

    private lateinit var dialog: TileRequestDialog

    @Mock
    private lateinit var ugm: IUriGrantsManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        // Create in looper so we can make sure that the tile is fully updated
        TestableLooper.get(this).runWithLooper {
            dialog = TileRequestDialog(mContext)
        }
    }

    @After
    fun teardown() {
        if (this::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    @Test
    fun setTileData_hasCorrectViews() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)

        assertThat(content.childCount).isEqualTo(2)
        assertThat(content.getChildAt(0)).isInstanceOf(TextView::class.java)
        assertThat(content.getChildAt(1)).isInstanceOf(QSTileView::class.java)
    }

    @Test
    fun setTileData_hasCorrectAppName() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val text = content.getChildAt(0) as TextView
        assertThat(text.text.toString()).contains(APP_NAME)
    }

    @Test
    fun setTileData_hasCorrectLabel() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.label as TextView).text.toString()).isEqualTo(LABEL)
    }

    @Test
    fun setTileData_hasIcon() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.icon.iconView as ImageView).drawable).isNotNull()
    }

    @Test
    fun setTileData_nullIcon_hasIcon() {
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, null, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.icon.iconView as ImageView).drawable).isNotNull()
    }

    @Test
    fun setTileData_hasNoStateDescription() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        assertThat(tile.stateDescription).isEqualTo("")
    }

    @Test
    fun setTileData_tileNotClickable() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        assertThat(tile.isClickable).isFalse()
        assertThat(tile.isLongClickable).isFalse()
    }

    @Test
    fun setTileData_tileHasCorrectContentDescription() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        assertThat(tile.contentDescription).isEqualTo(LABEL)
    }

    @Test
    fun uriIconLoadSuccess_correctIcon() {
        val tintColor = Color.BLACK
        val icon = Mockito.mock(Icon::class.java)
        val drawable = context.getDrawable(R.drawable.cloud)!!.apply {
            setTint(tintColor)
        }
        whenever(icon.loadDrawable(any())).thenReturn(drawable)
        whenever(icon.loadDrawableCheckingUriGrant(
            any(),
            eq(ugm),
            anyInt(),
            anyString())
        ).thenReturn(drawable)

        val size = 100

        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        verify(icon).loadDrawableCheckingUriGrant(any(), eq(ugm), eq(UID), eq(PACKAGE))

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        val iconDrawable = (tile.icon.iconView as ImageView).drawable.apply {
            setTint(tintColor)
        }

        assertThat(areDrawablesEqual(iconDrawable, drawable, size)).isTrue()
    }

    @Test
    fun uriIconLoadFail_defaultIcon() {
        val tintColor = Color.BLACK
        val icon = Mockito.mock(Icon::class.java)
        val drawable = context.getDrawable(R.drawable.cloud)!!.apply {
            setTint(tintColor)
        }
        whenever(icon.loadDrawable(any())).thenReturn(drawable)
        whenever(icon.loadDrawableCheckingUriGrant(
            any(),
            eq(ugm),
            anyInt(),
            anyString())
        ).thenReturn(null)

        val size = 100

        val tileData = TileRequestDialog.TileData(UID, APP_NAME, LABEL, icon, PACKAGE)

        dialog.setTileData(tileData, ugm)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        verify(icon).loadDrawableCheckingUriGrant(any(), eq(ugm), eq(UID), eq(PACKAGE))

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        val iconDrawable = (tile.icon.iconView as ImageView).drawable.apply {
            setTint(tintColor)
        }

        val defaultIcon = context.getDrawable(DEFAULT_ICON)!!.apply {
            setTint(tintColor)
        }

        assertThat(areDrawablesEqual(iconDrawable, defaultIcon, size)).isTrue()
    }
}

private fun areDrawablesEqual(drawable1: Drawable, drawable2: Drawable, size: Int = 24): Boolean {
    val bm1 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val bm2 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    val canvas1 = Canvas(bm1)
    val canvas2 = Canvas(bm2)

    drawable1.setBounds(0, 0, size, size)
    drawable2.setBounds(0, 0, size, size)

    drawable1.draw(canvas1)
    drawable2.draw(canvas2)

    return equalBitmaps(bm1, bm2).also {
        bm1.recycle()
        bm2.recycle()
    }
}

private fun equalBitmaps(a: Bitmap, b: Bitmap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    val w = a.width
    val h = a.height
    val aPix = IntArray(w * h)
    val bPix = IntArray(w * h)
    a.getPixels(aPix, 0, w, 0, 0, w, h)
    b.getPixels(bPix, 0, w, 0, 0, w, h)
    return Arrays.equals(aPix, bPix)
}

