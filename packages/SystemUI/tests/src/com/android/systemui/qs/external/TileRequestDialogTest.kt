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

import android.graphics.drawable.Icon
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTileView
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class TileRequestDialogTest : SysuiTestCase() {

    companion object {
        private const val APP_NAME = "App name"
        private const val LABEL = "Label"
    }

    private lateinit var dialog: TileRequestDialog

    @Before
    fun setUp() {
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
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)

        assertThat(content.childCount).isEqualTo(2)
        assertThat(content.getChildAt(0)).isInstanceOf(TextView::class.java)
        assertThat(content.getChildAt(1)).isInstanceOf(QSTileView::class.java)
    }

    @Test
    fun setTileData_hasCorrectAppName() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val text = content.getChildAt(0) as TextView
        assertThat(text.text.toString()).contains(APP_NAME)
    }

    @Test
    fun setTileData_hasCorrectLabel() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.label as TextView).text.toString()).isEqualTo(LABEL)
    }

    @Test
    fun setTileData_hasIcon() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.icon.iconView as ImageView).drawable).isNotNull()
    }

    @Test
    fun setTileData_nullIcon_hasIcon() {
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, null)

        dialog.setTileData(tileData)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView
        assertThat((tile.icon.iconView as ImageView).drawable).isNotNull()
    }

    @Test
    fun setTileData_hasNoStateDescription() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        assertThat(tile.stateDescription).isEqualTo("")
    }

    @Test
    fun setTileData_tileNotClickable() {
        val icon = Icon.createWithResource(mContext, R.drawable.cloud)
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
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
        val tileData = TileRequestDialog.TileData(APP_NAME, LABEL, icon)

        dialog.setTileData(tileData)
        dialog.show()

        TestableLooper.get(this).processAllMessages()

        val content = dialog.requireViewById<ViewGroup>(TileRequestDialog.CONTENT_ID)
        val tile = content.getChildAt(1) as QSTileView

        assertThat(tile.contentDescription).isEqualTo(LABEL)
    }
}
